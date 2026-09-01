(ns samuraibff.http.auth
  "HTTP auth middleware + OIDC login endpoints.

  This namespace implements a hybrid auth model:

  1) **Browser UI** uses server-side OAuth2 Authorization Code + PKCE.
     - `GET /auth/login` redirects to Keycloak
     - `GET /auth/callback` exchanges code for tokens
     - access token is stored in an HttpOnly cookie

  2) **Non-browser clients** (and dev tools) can authenticate by providing an
     access token either:
     - in `Authorization: Bearer <token>` header, or
     - as `?token=<token>` query parameter.

  Token verification is delegated to `samuraibff.auth.oidc`.

  ## Request context

  When `wrap-authenticate` is installed, Ring requests get:

  - `:auth/user`      – user map or nil
  - `:auth/token`     – token string or nil
  - `:auth/tenant-id` – best-effort tenant id derived from claims

  ## Config

  Uses `:auth` keys from the shared config map:

  - `:required?`   boolean
  - `:issuer`      string
  - `:audience`    string
  - `:client-id`   string (OAuth client id; defaults to :audience)
  - `:cookie-name` string (defaults to \"access_token\")

  Security notes:
  - Cookies are set as HttpOnly and SameSite=Lax.
  - `Secure` flag is enabled automatically when request is https.
  - In production behind TLS termination, we rely on `X-Forwarded-Proto` /
    `X-Forwarded-Host` to compute browser-facing OIDC redirect URIs and cookie
    security flags."
  (:require
    [clojure.string :as str]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [ring.util.codec :as codec]
    [ring.util.response :as resp]
    [samuraibff.auth.oidc :as oidc]
    [samuraibff.db.tenants :as db.tenants]
    [samuraibff.features :as features]
    [samuraibff.grpc.client :as grpc.client])
  (:import
    (java.net URI)
    (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest SecureRandom)
    (java.time Duration)
    (java.util Base64)))

(def ^:private http-client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- json-response
  "Return a Ring JSON response.

  Inputs:
  - status: int
  - body: map

  Returns: Ring response map." 
  [status body]
  ;; NOTE: Return a *data* body (map). Muuntaja (installed in router) handles
  ;; JSON encoding. This is required so Reitit response coercion can validate
  ;; the response body against Malli schemas.
  {:status status
   :body body})

(defn- request-scheme
  "Best-effort scheme derivation.

  Inputs:
  - req: Ring request

  Returns: " 
  [req]
  (or (some-> req :scheme name)
      "http"))

(defn- forwarded-scheme
  "Return scheme derived from reverse proxy headers (best-effort).

  Supports:
  - X-Forwarded-Proto: https
  - Forwarded: proto=https;host=...

  Returns: http | https | nil" 
  [req]
  (let [h (:headers req)
        xfp (some-> (or (get h "x-forwarded-proto") (get h "X-Forwarded-Proto"))
                      str/trim str/lower-case not-empty)
        forwarded (some-> (or (get h "forwarded") (get h "Forwarded"))
                          str/trim not-empty)]
    (cond
      (some? xfp)
      (case xfp
        "https" "https"
        "http" "http"
        nil)

      (some? forwarded)
      (let [proto (some-> (re-find #"(?i)proto=([^;,]+)" forwarded)
                          second
                          str/trim
                          (str/replace #"^\"|\"$" "")
                          str/lower-case)]
        (case proto
          "https" "https"
          "http" "http"
          nil))

      :else nil)))

(defn- request-host
  "Return host header (best effort)." 
  [req]
  (or (get-in req [:headers "host"])
      (get-in req [:headers "Host"])))

(defn- forwarded-host
  "Return host derived from reverse proxy headers (best-effort).

  Supports:
  - X-Forwarded-Host
  - Forwarded: host=...

  Returns: string or nil" 
  [req]
  (let [h (:headers req)
        xfh (some-> (or (get h "x-forwarded-host") (get h "X-Forwarded-Host"))
                      str/trim not-empty)
        forwarded (some-> (or (get h "forwarded") (get h "Forwarded"))
                          str/trim not-empty)]
    (cond
      (some? xfh) xfh

      (some? forwarded)
      (some-> (re-find #"(?i)host=([^;,]+)" forwarded)
              second
              str/trim
              (str/replace #"^\"|\"$" "")
              not-empty)

      :else nil)))

(defn- request-origin
  "Determine external origin for redirects.

  Precedence:
  1) config [:bff :public-origin-uri] (browser-facing; recommended behind a load balancer)
  2) proxy-derived {scheme}://{host} via X-Forwarded-* / Forwarded
  3) config [:bff :origin-uri] (may be pod-IP for inter-BFF callbacks)
  4) request-derived {scheme}://{host}

  Returns: string" 
  [config req]
  (or (get-in config [:bff :public-origin-uri])
      (when-let [host (forwarded-host req)]
        (let [scheme (or (forwarded-scheme req) (request-scheme req))]
          (str scheme "://" host)))
      (get-in config [:bff :origin-uri])
      (let [scheme (request-scheme req)
            host (request-host req)]
        (when host
          (str scheme "://" host)))))

(defn- base64url
  "Encode bytes to base64url without padding." 
  [^bytes bs]
  (-> (Base64/getUrlEncoder)
      (.withoutPadding)
      (.encodeToString bs)))

(defn- random-bytes
  "Return n cryptographically secure random bytes." 
  [n]
  (let [buf (byte-array n)
        rng (SecureRandom.)]
    (.nextBytes rng buf)
    buf))

(defn- pkce-verifier
  "Generate a PKCE verifier string." 
  []
  (base64url (random-bytes 48)))

(defn- sha256
  "SHA-256 digest of a string, as bytes." 
  [^String s]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes s StandardCharsets/UTF_8)))

(defn- pkce-challenge
  "Generate a PKCE code_challenge from a verifier." 
  [verifier]
  (base64url (sha256 verifier)))

(defn- token-cookie-name [config]
  (or (get-in config [:auth :cookie-name]) "access_token"))

(defn- cookie-secure?
  [req]
  (= "https" (or (forwarded-scheme req) (request-scheme req))))

(defn- set-cookie
  "Set a cookie on a Ring response (using ring.util.response).

  Inputs:
  - resp: Ring response
  - k: cookie name
  - v: value
  - opts: ring cookie opts

  Returns: response" 
  [resp0 k v opts]
  (resp/set-cookie resp0 k v opts))

(defn- clear-cookie
  "Clear a cookie on a Ring response." 
  [resp0 k]
  (resp/set-cookie resp0 k "" {:max-age 0 :path "/"}))

(defn- api-path?
  "Return true if the request path is under /api.

  Used to avoid blocking /auth/login when a browser has a stale cookie." 
  [req]
  (let [uri (or (:uri req) "")]
    (str/starts-with? uri "/api")))

(defn wrap-authenticate
  "Ring middleware that parses & verifies an access token (if present).

  Behavior:
  - if no token: passes request through with :auth/user nil
  - if token present and valid: attaches :auth/user and :auth/tenant-id
  - if token present but invalid:
      - when auth is required AND the request is under /api => returns 401 JSON
      - otherwise continues as anonymous

  Rationale:
  - If a browser has an expired/stale cookie, we still want `/auth/login` to work
    so the user can re-authenticate.

  Inputs:
  - handler: Ring handler
  - config: full config map

  Returns: wrapped handler" 
  [handler config]
  (fn [req]
    (let [token (oidc/extract-token config req)
          required? (oidc/auth-required? config)
          guest-tenant-id (when-not required?
                            (some-> (get-in config [:auth :guest-tenant-id])
                                    str
                                    str/trim
                                    not-empty))
          anonymous-request (fn []
                              (assoc req
                                     :auth/token token
                                     :auth/user nil
                                     :auth/tenant-id guest-tenant-id))]
      (if-not token
        (handler (anonymous-request))
        (try
          (let [user (oidc/verify-token config token)
                tenant-id (oidc/extract-tenant-from-claims* config user)]
            (handler (assoc req
                            :auth/token token
                            :auth/user user
                            :auth/tenant-id tenant-id)))
          (catch Exception e
            (if (and required? (api-path? req))
              (do
                (log/info "Auth failed (token invalid)" {:message (.getMessage e)
                                                         :uri (:uri req)})
                (json-response 401 {:ok false :message "invalid-token"}))
              (do
                (log/warn e "Auth failed but ignored")
                (handler (anonymous-request))))))))))

(defn wrap-require-auth
  "Ring middleware enforcing that :auth/user is present.

  Intended to be used *after* `wrap-authenticate`.

  If auth is not required by config, this middleware is a no-op.

  Inputs:
  - handler: Ring handler
  - config: full config map

  Returns: wrapped handler" 
  [handler config]
  (fn [req]
    (if-not (oidc/auth-required? config)
      (handler req)
      (if (:auth/user req)
        (handler req)
        (json-response 403 {:ok false :message "missing-token"})))))

(defn login-handler
  "Handler for `GET /auth/login`.

  Redirects to Keycloak authorization endpoint using PKCE.

  Cookies set:
  - pkce_verifier (HttpOnly)
  - pkce_state    (HttpOnly)

  Query params:
  - next (optional): where to redirect after login (defaults to /recordings)

  Returns: Ring redirect response." 
  [config]
  (fn [{:keys [params] :as req}]
    (let [issuer (or (get-in config [:auth :issuer])
                     (throw (ex-info "Missing auth issuer" {:config (select-keys config [:auth])})))
          client-id (or (get-in config [:auth :client-id])
                        (get-in config [:auth :audience])
                        (throw (ex-info "Missing auth client id" {:config (select-keys config [:auth])})))
          verifier (pkce-verifier)
          challenge (pkce-challenge verifier)
          state (base64url (random-bytes 18))
          origin (or (request-origin config req)
                     (throw (ex-info "Cannot determine request origin" {:headers (:headers req)})))
          redirect-uri (str origin "/auth/callback")
          next0 (or (get params :next) (get params "next") "/recordings")
          auth-url (str (str/replace issuer #"/+$" "") "/protocol/openid-connect/auth")
          url (str auth-url
                   "?" (codec/form-encode
                         {:client_id client-id
                          :response_type "code"
                          :scope "openid email profile"
                          :redirect_uri redirect-uri
                          :code_challenge_method "S256"
                          :code_challenge challenge
                          :state state}))
          resp0 (resp/redirect url)
          secure? (cookie-secure? req)
          common {:http-only true
                  :same-site :lax
                  :secure secure?
                  :path "/"
                  :max-age 600}]
      (-> resp0
          (set-cookie "pkce_verifier" verifier common)
          (set-cookie "pkce_state" state common)
          (set-cookie "post_login_next" (str next0) (assoc common :http-only false))))))

(defn- token-endpoint
  [issuer]
  (str (str/replace issuer #"/+$" "") "/protocol/openid-connect/token"))

(defn- form-body
  "Encode map as x-www-form-urlencoded string." 
  [m]
  (codec/form-encode m))

(defn- http-post-form!
  "POST x-www-form-urlencoded and return {:status int :body string}." 
  [url params]
  (let [^HttpRequest req (-> (HttpRequest/newBuilder)
                             (.uri (URI/create url))
                             (.timeout (Duration/ofSeconds 7))
                             (.header "Content-Type" "application/x-www-form-urlencoded")
                             (.POST (HttpRequest$BodyPublishers/ofString (form-body params)))
                             (.build))
        ^java.net.http.HttpResponse resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)}))

(defn callback-handler
  "Handler for `GET /auth/callback`.

  Performs PKCE state verification and exchanges `code` for tokens.

  On success:
  - sets HttpOnly cookie with access token
  - clears pkce cookies
  - redirects to remembered `post_login_next` cookie (or /recordings)

  Returns: Ring response." 
  [config]
  (fn [{:keys [params cookies] :as req}]
    (let [code (or (get params :code) (get params "code"))
          state (or (get params :state) (get params "state"))
          expected-state (some-> cookies (get "pkce_state") :value)
          verifier (some-> cookies (get "pkce_verifier") :value)
          next0 (or (some-> cookies (get "post_login_next") :value) "/recordings")]
      (cond
        (some? (or (get params :error) (get params "error")))
        (json-response 400 {:ok false
                            :message "oidc-error"
                            :error (or (get params :error) (get params "error"))
                            :error_description (or (get params :error_description)
                                                   (get params "error_description"))})

        (str/blank? (str code))
        (json-response 400 {:ok false :message "missing-code"})

        (or (str/blank? (str expected-state)) (not= expected-state state))
        (json-response 400 {:ok false :message "state-mismatch"})

        (str/blank? (str verifier))
        (json-response 400 {:ok false :message "missing-pkce-verifier"})

        :else
        (let [issuer (or (get-in config [:auth :issuer])
                         (throw (ex-info "Missing auth issuer" {:config (select-keys config [:auth])})))
              client-id (or (get-in config [:auth :client-id])
                            (get-in config [:auth :audience]))
              origin (or (request-origin config req)
                         (throw (ex-info "Cannot determine request origin" {:headers (:headers req)})))
              redirect-uri (str origin "/auth/callback")
              token-url (token-endpoint issuer)
              {:keys [status body]} (http-post-form!
                                     token-url
                                     {:grant_type "authorization_code"
                                      :client_id client-id
                                      :code code
                                      :redirect_uri redirect-uri
                                      :code_verifier verifier})]
          (if-not (<= 200 status 299)
            (do
              (log/warn "Token exchange failed" {:status status :body (subs (str body) 0 (min 2000 (count (str body))))})
              (json-response 400 {:ok false :message "token-exchange-failed" :status status}))
            (let [token-json (json/read-value body (json/object-mapper {:decode-key-fn keyword}))
                  access-token (:access_token token-json)
                  cookie-name (token-cookie-name config)
                  secure? (cookie-secure? req)
                  resp0 (resp/redirect next0)]
              (when (str/blank? (str access-token))
                (throw (ex-info "Token response missing access_token" {:token token-json})))
              (-> resp0
                  (set-cookie cookie-name access-token {:http-only true
                                                       :same-site :lax
                                                       :secure secure?
                                                       :path "/"})
                  (clear-cookie "pkce_verifier")
                  (clear-cookie "pkce_state")
                  (clear-cookie "post_login_next")))))))))

(defn logout-handler
  "Handler for `POST /auth/logout`.

  Clears access token cookie.

  Returns: 204 response." 
  [config]
  (fn [_req]
    (let [cookie-name (token-cookie-name config)]
      (-> {:status 204 :headers {} :body ""}
          (clear-cookie cookie-name)))))

(defn- realtime-track-ids
  "Return the ordered operator-configured realtime track IDs safe for UI use.

  Inputs:
  - config: SamuraiBFF configuration map

  Returns a non-empty vector of strings without exposing service addresses."
  [config]
  (or (some->> (get-in config [:grpc :realtime-tracks])
               seq
               (mapv :id))
      ["default"]))

(defn- realtime-track-capabilities
  "Return sanitized provider capabilities for the operator-configured tracks.

  Capability discovery is best-effort. Provider network coordinates and model
  provenance remain server-side; callers receive only user-relevant behavior."
  [config grpc]
  (let [configured (get-in config [:grpc :realtime-tracks])
        tracks (or (seq (grpc.client/tracks grpc))
                   (seq configured)
                   [{:id "default"}])]
    (mapv
     (fn [{:keys [id] :as track}]
       (try
         (let [capabilities (grpc.client/get-capabilities track 500)]
           {:id id
            :available true
            :provider_profile_id (:provider-profile-id capabilities)
            :windowed_realtime (:windowed-realtime? capabilities)
            :native_streaming (:native-streaming? capabilities)
            :segment_timestamps (:segment-timestamps? capabilities)
            :word_timestamps (:word-timestamps? capabilities)
            :speaker_labels (:speaker-labels? capabilities)
            :aligned_diarized_languages (:aligned-diarized-languages capabilities)
            :language_detection (:language-detection? capabilities)
            :supported_languages (:supported-languages capabilities)
            :preferred_sample_rate (:preferred-sample-rate capabilities)
            :maximum_audio_seconds (:maximum-audio-seconds capabilities)
            :maximum_concurrent_sessions (:maximum-concurrent-sessions capabilities)})
         (catch Exception e
           (log/warn e "Realtime ASR capability discovery failed" {:track id})
           {:id id :available false})))
     tracks)))

(defn me-handler
  "Handler for `GET /api/me`.

  Requires `wrap-authenticate` middleware to be installed.

  Behavior:
  - if authenticated: returns {ok true, authenticated true, user {...}, tenant_id ...}
  - if not authenticated and auth required: 401
  - if not authenticated and auth not required: {ok true, authenticated false}

  Returns: JSON response." 
  [config grpc]
  (fn [req]
    (if-let [user (:auth/user req)]
      (let [tenant-id-str (:auth/tenant-id req)
            tenant-uuid (try
                          (some-> tenant-id-str str java.util.UUID/fromString)
                          (catch Exception _ nil))
            ds (get-in req [:samuraibff/deps :db :ds])
            tenant-name (when (and ds tenant-uuid)
                          (db.tenants/find-tenant-name ds tenant-uuid))]
        (json-response 200 {:ok true
                             :authenticated true
                             :tenant_id tenant-id-str
                             :tenant_name tenant-name
                             :realtime_tracks (realtime-track-ids config)
                             :realtime_track_capabilities (realtime-track-capabilities config grpc)
                             :features (features/feature-state config)
                            :user (select-keys user [:sub :preferred_username :email])}))
      (if (oidc/auth-required? config)
        (json-response 401 {:ok false :authenticated false :message "not-authenticated"})
         (json-response 200 {:ok true
                             :authenticated false
                             :realtime_tracks (realtime-track-ids config)
                             :realtime_track_capabilities (realtime-track-capabilities config grpc)
                             :features (features/feature-state config)})))))

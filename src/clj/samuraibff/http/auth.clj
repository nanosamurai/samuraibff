(ns samuraibff.http.auth
  "HTTP auth middleware + OIDC login endpoints.

  This namespace implements a hybrid auth model:

  1) **Browser UI** uses server-side OAuth2 Authorization Code + PKCE.
     - `GET /auth/login` redirects to Keycloak
     - `GET /auth/callback` exchanges code for tokens
     - access token is stored in an HttpOnly cookie

  2) **Non-browser clients** (and dev tools) authenticate with an access token
     in the `Authorization: Bearer <token>` header. URL parameters are ignored.

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
   [samuraibff.stream-controls :as stream-controls]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [ring.util.codec :as codec]
   [ring.util.response :as resp]
   [samuraibff.access.policy :as policy]
   [samuraibff.auth.jwks :as jwks]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.db.tenants :as db.tenants]
   [samuraibff.features :as features]
   [samuraibff.observability.diagnostics :as diagnostics]
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

(defn request-origin
  "Determine external origin for redirects and Origin validation.

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
  "Clear a cookie on a Ring response. path defaults to \"/\"."
  ([resp0 k] (clear-cookie resp0 k "/"))
  ([resp0 k path]
   (resp/set-cookie resp0 k "" {:max-age 0 :path path})))

(def ^:private id-token-cookie
  "HttpOnly cookie that keeps the ID token as the logout hint. Sent only to
  /auth/* so it never travels with API or asset requests."
  {:name "id_token_hint" :path "/auth" :max-bytes 3500})

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
        (let [{:keys [user failure]} (try
                                       {:user (oidc/verify-token config token)}
                                       (catch Exception failure
                                         {:failure failure}))]
          (if failure
            (if (and required? (api-path? req))
              (do
                (log/info "Auth failed (token invalid)" (diagnostics/failure-details failure))
                (json-response 401 {:ok false :message "invalid-token"}))
              (do
                (log/warn "Auth failed but ignored" (diagnostics/failure-details failure))
                (handler (anonymous-request))))
            (handler (assoc req
                            :auth/token token
                            :auth/user user
                            :auth/tenant-id (oidc/extract-tenant-from-claims* config user)))))))))

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

(defn- provider-endpoints
  "Return the configured issuer's OIDC discovery endpoints, or nil.

  Login, code exchange and logout use the endpoints the identity provider
  advertises, so any standard OIDC provider works, not only Keycloak."
  [config]
  (try
    (jwks/provider-endpoints! (get-in config [:auth :issuer]) {})
    (catch Exception failure
      (log/warn "OIDC discovery unavailable" (diagnostics/failure-details failure))
      nil)))

(defn login-handler
  "Handler for `GET /auth/login`.

  Redirects to the identity provider's authorization endpoint (from OIDC
  discovery) using PKCE. Returns 503 when discovery is unavailable.

  Cookies set:
  - pkce_verifier (HttpOnly)
  - pkce_state    (HttpOnly)

  Query params:
  - next (optional): where to redirect after login (defaults to /recordings)

  Returns: Ring redirect response."
  [config]
  (fn [{:keys [params] :as req}]
    (let [_ (or (get-in config [:auth :issuer])
                (throw (ex-info "Missing auth issuer" {:type ::missing-issuer})))
          client-id (or (get-in config [:auth :client-id])
                        (get-in config [:auth :audience])
                        (throw (ex-info "Missing auth client id" {:type ::missing-client-id})))
          verifier (pkce-verifier)
          challenge (pkce-challenge verifier)
          state (base64url (random-bytes 18))
          origin (or (request-origin config req)
                     (throw (ex-info "Cannot determine request origin" {:type ::missing-origin})))
          redirect-uri (str origin "/auth/callback")
          next0 (or (get params :next) (get params "next") "/recordings")
          auth-url (:authorization-endpoint (provider-endpoints config))]
      (if-not auth-url
        (json-response 503 {:ok false :message "identity-provider-unavailable"})
        (let [url (str auth-url
                       (if (str/includes? auth-url "?") "&" "?")
                       (codec/form-encode
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
              (set-cookie "post_login_next" (str next0) (assoc common :http-only false))))))))

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

(defn- exchange-token!
  "Exchange a code without exposing upstream bodies or transport/parser errors.

  Returns {:access-token string :id-token string?} on success, {:status int} on an HTTP rejection,
  or {} when the transport or response is invalid. Logs only safe diagnostics."
  [url params]
  (try
    (let [{:keys [status body]} (http-post-form! url params)
          status (diagnostics/http-status status)]
      (if (and status (<= 200 status 299))
        (let [response (json/read-value body (json/object-mapper {:decode-key-fn keyword}))
              token (:access_token response)
              id-token (:id_token response)]
          (when-not (and (string? token) (not (str/blank? token)))
            (throw (ex-info "Invalid token response" {:type ::invalid-token-response})))
          (cond-> {:access-token token}
            (and (string? id-token) (not (str/blank? id-token))) (assoc :id-token id-token)))
        (do
          (log/warn "Token exchange rejected" {:status status})
          {:status status})))
    (catch InterruptedException failure
      (.interrupt (Thread/currentThread))
      (log/warn "Token exchange interrupted" (diagnostics/failure-details failure))
      {})
    (catch Exception failure
      (log/warn "Token exchange failed" (diagnostics/failure-details failure))
      {})))

(defn callback-handler
  "Handler for `GET /auth/callback`.

  Performs PKCE state verification and exchanges `code` for tokens.

  On success:
  - sets HttpOnly cookie with access token
  - keeps the ID token in an HttpOnly cookie scoped to /auth, used only as
    the `id_token_hint` for identity-provider logout
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
        (json-response 400 {:ok false :message "oidc-error"})

        (str/blank? (str code))
        (json-response 400 {:ok false :message "missing-code"})

        (or (str/blank? (str expected-state)) (not= expected-state state))
        (json-response 400 {:ok false :message "state-mismatch"})

        (str/blank? (str verifier))
        (json-response 400 {:ok false :message "missing-pkce-verifier"})

        :else
        (let [client-id (or (get-in config [:auth :client-id])
                            (get-in config [:auth :audience]))
              origin (or (request-origin config req)
                         (throw (ex-info "Cannot determine request origin" {:type ::missing-origin})))
              redirect-uri (str origin "/auth/callback")
              token-url (:token-endpoint (provider-endpoints config))
              {:keys [status access-token id-token]} (if token-url
                                                       (exchange-token!
                                                        token-url
                                                        {:grant_type "authorization_code"
                                                         :client_id client-id
                                                         :code code
                                                         :redirect_uri redirect-uri
                                                         :code_verifier verifier})
                                                       {})]
          (if-not access-token
            (json-response (if status 400 502)
                           (cond-> {:ok false :message "token-exchange-failed"}
                             status (assoc :status status)))
            (let [cookie-name (token-cookie-name config)
                  secure? (cookie-secure? req)
                  resp0 (resp/redirect next0)
                  keep-id-token? (and id-token (<= (count id-token) (:max-bytes id-token-cookie)))]
              (-> resp0
                  (set-cookie cookie-name access-token {:http-only true
                                                        :same-site :lax
                                                        :secure secure?
                                                        :path "/"})
                  (cond-> keep-id-token?
                    (set-cookie (:name id-token-cookie) id-token {:http-only true
                                                                  :same-site :lax
                                                                  :secure secure?
                                                                  :path (:path id-token-cookie)}))
                  (clear-cookie "pkce_verifier")
                  (clear-cookie "pkce_state")
                  (clear-cookie "post_login_next")))))))))

(defn- end-session-url
  "Return the identity provider's RP-initiated logout URL, or nil.

  The endpoint is the `end_session_endpoint` from OIDC discovery; a provider
  without one gets cookie-only logout. The query uses only configured values:
  the browser client id and the BFF's own origin as `post_logout_redirect_uri`.
  The identity provider additionally checks that redirect against the client's
  registered post-logout redirect URIs. The ID token hint, when present, lets
  it end the session without a confirmation prompt."
  [config req id-token]
  (let [client-id (or (get-in config [:auth :client-id])
                      (get-in config [:auth :audience]))
        origin (request-origin config req)
        end-session (when (oidc/auth-required? config)
                      (:end-session-endpoint (provider-endpoints config)))]
    (when (and end-session
               (not (str/blank? (str client-id)))
               origin)
      (str end-session
           (if (str/includes? end-session "?") "&" "?")
           (codec/form-encode
            (cond-> {:client_id client-id
                     :post_logout_redirect_uri (str origin "/")}
              id-token (assoc :id_token_hint id-token)))))))

(defn logout-handler
  "Handler for `POST /auth/logout`.

  Clears the access token and ID token cookies. When authentication is
  required and the identity provider advertises an `end_session_endpoint`,
  also returns `logout_url`: the browser must open it to end the
  identity-provider session. Without that step the provider's single sign-on
  session would silently log the same person in again.

  Returns: 200 {:ok true :logout_url string?}."
  [config]
  (fn [req]
    (let [cookie-name (token-cookie-name config)
          id-token (some-> (get-in req [:cookies (:name id-token-cookie) :value]) str not-empty)
          logout-url (end-session-url config req id-token)]
      (-> (json-response 200 (cond-> {:ok true}
                               logout-url (assoc :logout_url logout-url)))
          (clear-cookie cookie-name)
          (clear-cookie (:name id-token-cookie) (:path id-token-cookie))))))

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
            :session_settings (:session-settings capabilities)
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
           (log/warn "Realtime ASR capability discovery failed"
                     (assoc (diagnostics/failure-details e) :track id))
           {:id id :available false})))
     tracks)))

(defn- access-state
  "Return the /api/me access block for the request principal.

  Inactive states carry no principal, tenant or capability data. A request
  made with an API key also describes that key (`api_key`)."
  [req]
  (let [principal (:auth/principal req)
        state (or (:auth/access-state req) :unavailable)
        credential (:credential principal)]
    (if (policy/active? principal)
      {:state "active"
       :principal (cond-> {:id (str (:id principal))
                           :type (name (:type principal))
                           :role (some-> (:role principal) name)
                           :display_name (or (:name principal) (:email principal))
                           :guest (boolean (:guest? principal))}
                    credential (assoc :api_key (cond-> {:id (str (:id credential))
                                                        :name (:name credential)
                                                        :kind (name (:kind credential))}
                                                 (= :personal (:kind credential))
                                                 (assoc :read_scope (name (:read-scope credential))))))
       :capabilities (policy/capabilities principal)}
      {:state (case state
                :not-provisioned "not_provisioned"
                :pending "pending"
                :suspended "suspended"
                "unavailable")})))

(defn me-handler
  "Handler for `GET /api/me`.

  Requires `wrap-authenticate` and `samuraibff.access.principal/wrap-principal`
  (with inactive states allowed) to be installed.

  Behavior:
  - if authenticated: returns {ok true, authenticated true, user {...},
    access {...}}; tenant_id/tenant_name only for an active principal
  - if not authenticated and auth required: 401
  - if not authenticated and auth not required: the guest principal's access

  `access.capabilities` are presentation hints; every route authorizes again.

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
        (json-response 200 (cond-> {:ok true
                                    :authenticated true
                                    :tenant_id tenant-id-str
                                    :realtime_tracks (realtime-track-ids config)
                                    :default_tracks (or (:default-tracks config) {})
                                    :async_tracks (stream-controls/configured-async-tracks config)
                                    :realtime_track_capabilities (realtime-track-capabilities config grpc)
                                    :features (features/feature-state config)
                                    :access (access-state req)
                                    :user (into {} (remove (comp nil? val))
                                                (select-keys user [:sub :preferred_username :email]))}
                             (nil? tenant-id-str) (dissoc :tenant_id)
                             (some? tenant-name) (assoc :tenant_name tenant-name))))
      (if (oidc/auth-required? config)
        (json-response 401 {:ok false :authenticated false :message "not-authenticated"})
        (json-response 200 {:ok true
                            :authenticated false
                            :realtime_tracks (realtime-track-ids config)
                            :default_tracks (or (:default-tracks config) {})
                            :async_tracks (stream-controls/configured-async-tracks config)
                            :realtime_track_capabilities (realtime-track-capabilities config grpc)
                            :features (features/feature-state config)
                            :access (access-state req)})))))

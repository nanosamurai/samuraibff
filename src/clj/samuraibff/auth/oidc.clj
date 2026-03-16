(ns samuraibff.auth.oidc
  "OIDC (Keycloak) helpers.

  This namespace provides:
  - token extraction from Ring requests (Authorization header / query param / cookie)
  - verification of Keycloak-issued JWT access tokens via OIDC discovery + JWKS
  - helpers to derive tenant identifiers from claims

  The implementation mirrors drsynth's Python BFF behavior:
  - OIDC discovery: GET {issuer}/.well-known/openid-configuration
  - JWKS fetch: GET jwks_uri
  - JWKS is cached in memory (no TTL yet)

  ## Data model

  Verified user map (returned by `verify-token`):

  {:sub                string
   :preferred_username (or string nil)
   :email              (or string nil)
   :raw                map-of-claims}

  ## Config

  This namespace expects the *full* samuraibff config map, specifically:

  [:auth :issuer]        string (e.g. https://auth.nanosamur.ai/realms/nanosamurai)
  [:auth :audience]      string (Keycloak client-id / audience)
  [:auth :cookie-name]   string (defaults to \"access_token\")

  Notes:
  - The JWKS cache is per-process; in prod you typically refresh periodically.
  - For tests, the JWKS fetch can be injected (see `verify-token` arity 2)."
  (:require
    [clojure.string :as str]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log])
  (:import
    (com.nimbusds.jose JWSAlgorithm)
    (com.nimbusds.jose.jwk JWKSet)
    (com.nimbusds.jose.proc SecurityContext)
    (com.nimbusds.jose.jwk.source ImmutableJWKSet)
    (com.nimbusds.jwt.proc BadJWTException ConfigurableJWTProcessor DefaultJWTProcessor)
    (com.nimbusds.jose.proc JWSKeySelector JWSVerificationKeySelector)
    (java.net URI)
    (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
    (java.time Duration)
    (java.util Date)))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword
                       :encode-key-fn name}))

(defrecord OIDCUser [sub preferred_username email raw])

(defn- claim-string
  "Normalize a claim value to a trimmed string.

  Accepts:
  - string claim values
  - single-value sequential claims (common for Keycloak user attributes)

  Returns:
  - non-blank string or nil."
  [v]
  (cond
    (and (string? v) (not (str/blank? v)))
    (str/trim v)

    (and (sequential? v)
         (= 1 (count v))
         (string? (first v))
         (not (str/blank? (first v))))
    (str/trim (first v))

    :else
    nil))

(defn auth-required?
  "Return true when auth is required by configuration.

  Inputs:
  - config: full config map

  Returns: boolean" 
  [config]
  (boolean (get-in config [:auth :required?])))

(defn bearer-token
  "Extract a bearer token from an Authorization header.

  Inputs:
  - auth-header: string

  Returns:
  - token string or nil" 
  [auth-header]
  (when (and auth-header
             (string? auth-header))
    (let [auth-header (str/trim auth-header)
          lower (str/lower-case auth-header)]
      (when (str/starts-with? lower "bearer ")
        (some-> auth-header (subs 7) str/trim not-empty)))))

(defn extract-token
  "Extract an access token from a Ring request.

  Precedence (matches drsynth):
  1) Authorization: Bearer <token>
  2) ?token=<token> query param
  3) cookie named [:auth :cookie-name] (defaults to \"access_token\")

  Inputs:
  - config: full config map
  - req: Ring request map

  Returns:
  - token string or nil" 
  [config {:keys [headers params cookies] :as _req}]
  (let [auth-header (or (get headers "authorization")
                        (get headers "Authorization"))
        by-header (bearer-token auth-header)
        by-query (some-> (or (get params :token) (get params "token")) str not-empty)
        cookie-name (or (get-in config [:auth :cookie-name]) "access_token")
        by-cookie (some-> cookies (get cookie-name) :value str not-empty)]
    (or by-header by-query by-cookie)))

(def ^:private jwks-cache*
  "Process-wide cache for resolved OIDC metadata + JWKS.

  Shape:
  {:issuer <string>
   :jwks-uri <string>
   :jwks <JWKSet>
   :loaded-at <Date>}

  Note: no TTL yet." 
  (atom nil))

(defn- well-known-url
  [issuer]
  (str (str/replace issuer #"/+$" "") "/.well-known/openid-configuration"))

(def ^:private http-client
  "Shared JDK HTTP client used for OIDC discovery/JWKS fetches." 
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- fetch-json!
  "Fetch JSON over HTTP using JDK HttpClient.

  Inputs:
  - url: string

  Returns:
  - parsed JSON value with keyword keys

  Throws:
  - ex-info on non-2xx responses" 
  [url]
  (let [^HttpRequest req (-> (HttpRequest/newBuilder)
                             (.uri (URI/create url))
                             (.timeout (Duration/ofSeconds 5))
                             (.GET)
                             (.build))
        ^java.net.http.HttpResponse resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info "OIDC HTTP fetch failed" {:url url :status status :body body})))
    (json/read-value body json-mapper)))

(defn- ensure-jwks!
  "Ensure JWKS is present in cache for the configured issuer.

  Inputs:
  - config full config map
  - {:keys [fetch-json]} opts where fetch-json is (fn [url] => map)

  Returns: cache map with :jwks (JWKSet)" 
  [config {:keys [fetch-json]}]
  (let [fetch-json (or fetch-json fetch-json!)
        issuer (or (get-in config [:auth :issuer])
                   (throw (ex-info "Missing auth issuer" {:config (select-keys config [:auth])})))
        cached @jwks-cache*]
    (if (and cached (= issuer (:issuer cached)) (:jwks cached))
      cached
      (do
        (log/info "Fetching OIDC configuration" {:issuer issuer
                                                 :well-known (well-known-url issuer)})
        (let [conf (fetch-json (well-known-url issuer))
              jwks-uri (or (:jwks_uri conf)
                           (:jwks-uri conf)
                           (throw (ex-info "OIDC discovery missing jwks_uri" {:conf conf})))
              _ (log/info "Fetching JWKS" {:jwks-uri jwks-uri})
              jwks-json (fetch-json jwks-uri)
              jwks (JWKSet/parse (json/write-value-as-string jwks-json json-mapper))
              cache {:issuer issuer
                     :jwks-uri jwks-uri
                     :jwks jwks
                     :loaded-at (Date.)}]
          (reset! jwks-cache* cache)
          cache)))))

(defn- audience-valid?
  "Return true when token audience matches our expected audience.

  Keycloak can put the client-id either in:
  - `aud` (list)
  - `azp` (authorized party)

  In some setups, access tokens have `aud=[\"account\"]` and `azp=<client-id>`.

  Inputs:
  - expected-audience: string
  - aud: java.util.List (or nil)
  - azp: string (or nil)

  Returns: boolean" 
  [expected-audience aud azp]
  (let [aud (set (map str (or aud [])))
        azp (some-> azp str)]
    (or (contains? aud expected-audience)
        (= azp expected-audience))))

(defn- jwt-processor
  "Build a Nimbus JWT processor for the given issuer/audience and JWKSet.

  Notes:
  - We use an `ImmutableJWKSet` so Nimbus does the heavy lifting of selecting
    the correct key by `kid` / algorithm." 
  [issuer audience ^JWKSet jwks]
  (let [proc (DefaultJWTProcessor.)
        jwk-source (ImmutableJWKSet. jwks)
        key-selector (JWSVerificationKeySelector.
                       (JWSAlgorithm/RS256)
                       jwk-source)]
    (.setJWSKeySelector proc ^JWSKeySelector key-selector)
    (.setJWTClaimsSetVerifier
      proc
      (reify com.nimbusds.jwt.proc.JWTClaimsSetVerifier
        (verify [_ claims _ctx]
          ;; Validate iss/aud similarly to drsynth.
          ;; NOTE: Keycloak sometimes uses `azp` instead of putting the client id into `aud`.
          (let [iss (.getIssuer claims)
                auds (.getAudience claims)
                azp (try (.getStringClaim claims "azp") (catch Exception _ nil))]
            (when-not (= issuer iss)
              (throw (BadJWTException. (str "Invalid issuer: " iss))))
            (when-not (audience-valid? audience auds azp)
              (throw (BadJWTException. (str "Invalid audience: " (pr-str (set (or (map str auds) [])))))))))))
    proc))

(defn verify-token
  "Verify a Keycloak-issued JWT access token.

  Arity:
  - (verify-token config token)
  - (verify-token config token {:keys [fetch-json]})  ; injection for tests

  Inputs:
  - config: full config map
  - token: JWT string

  Output:
  - OIDC user map

  Throws:
  - ex-info with :type :samuraibff.auth/invalid-token on verification failure" 
  ([config token]
   (verify-token config token {}))
  ([config token {:keys [fetch-json]}]
   (try
     (let [{:keys [issuer jwks]} (ensure-jwks! config {:fetch-json fetch-json})
           audience (or (get-in config [:auth :audience])
                        (throw (ex-info "Missing auth audience" {:config (select-keys config [:auth])})))
           ^ConfigurableJWTProcessor proc (jwt-processor issuer audience jwks)
           claims (.process proc token nil)
           ;; Convert claim map to CLJ
           raw (-> claims
                   (.toJSONObject)
                   (json/write-value-as-string json-mapper)
                   (json/read-value json-mapper))
           sub (or (:sub raw) (throw (BadJWTException. "Token missing sub")))]
       {:sub sub
        :preferred_username (:preferred_username raw)
        :email (:email raw)
        :raw raw})
     (catch Exception e
       (log/warn e "JWT verification failed")
       (throw (ex-info "Invalid token"
                       {:type :samuraibff.auth/invalid-token
                        :message (.getMessage e)}
                       e))))))

(defn extract-tenant-from-claims
  "Best-effort tenant extraction from verified claims.

  Mirrors the Python BFF logic.

  Inputs:
  - user: user map from `verify-token` (or nil)

  Returns:
  - tenant-id string or nil" 
  [user]
  (when user
    (let [claims (or (:raw user) {})]
      (or
        ;; Primary: configured claim key. Defaults to tenant_id.
        ;; NOTE: This is not used by call sites yet; they currently call
        ;; extract-tenant-from-claims with only the user map.
        ;; See `extract-tenant-from-claims*` below.
        (some (fn [k]
                (let [v (get claims k)]
                  (claim-string v)))
              [:tenant_id :tenant :org_id :organization_id :company_id])

        ;; role encoding: realm_access.roles contains e.g. "tenant:<uuid>"
        (try
          (let [roles (get-in claims [:realm_access :roles])]
            (some (fn [r]
                    (when (and (string? r) (str/starts-with? r "tenant:"))
                      (let [tid (-> r (subs (count "tenant:")) str/trim)]
                        (when-not (str/blank? tid) tid))))
                  roles))
          (catch Exception _
            nil))))))

(defn extract-tenant-from-claims*
  "Tenant extraction with config.

  This is intended for code paths where tenant claim name is configurable
  (e.g. if you want to standardize on :tenant_id but still support legacy
  claim keys).

  Inputs:
  - config: full config map
  - user: verified OIDC user map

  Returns: tenant-id string or nil" 
  [config user]
  (when user
    (let [claims (or (:raw user) {})
          claim-key (some-> (get-in config [:auth :tenant-claim]) str str/trim not-empty)
          claim-key-kw (when claim-key (keyword claim-key))
          preferred (when claim-key-kw
                      (let [v (get claims claim-key-kw)]
                        (claim-string v)))]
      (or preferred (extract-tenant-from-claims user)))))

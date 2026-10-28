(ns samuraibff.auth.oidc
  "OIDC helpers, for any standards-compliant identity provider.

  This namespace provides:
  - token extraction from Ring requests (Authorization header / cookie)
  - verification of JWT access tokens via OIDC discovery + JWKS
  - helpers to derive tenant identifiers from claims

  Signing keys are resolved through bounded OIDC discovery and JWKS caching:
  - OIDC discovery: GET {issuer}/.well-known/openid-configuration
  - JWKS fetch: GET jwks_uri
  - JWKS refresh is single-flight, rate limited and fails closed

  ## Data model

  Verified user map (returned by `verify-token`):

  {:sub                string
   :preferred_username (or string nil)
   :email              (or string nil)
   :expires-at-ms      integer
   :raw                map-of-claims}

  ## Config

  This namespace expects the *full* samuraibff config map, specifically:

  [:auth :issuer]        string (e.g. https://id.example.com/realms/acme)
  [:auth :audience]      string (browser client id / audience)
  [:auth :cookie-name]   string (defaults to \"access_token\")

  Notes:
  - The JWKS cache is per-process with a five-minute maximum age.
  - Tests may inject the clock, cache and metadata fetch."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [samuraibff.auth.jwks :as jwks])
  (:import
   (com.nimbusds.jose JWSAlgorithm)
   (com.nimbusds.jose.jwk JWKSet)
   (com.nimbusds.jose.jwk.source ImmutableJWKSet)
   (com.nimbusds.jwt JWTClaimsSet JWTClaimsSet$Builder SignedJWT)
   (com.nimbusds.jwt.proc BadJWTException ConfigurableJWTProcessor DefaultJWTProcessor DefaultJWTClaimsVerifier)
   (com.nimbusds.jose.proc JWSKeySelector JWSVerificationKeySelector)
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

  Precedence:
  1) Authorization: Bearer <token>
  2) cookie named [:auth :cookie-name] (defaults to \"access_token\")

  Query, form and body parameters never supply authentication credentials.

  Inputs:
  - config: full config map
  - req: Ring request map

  Returns:
  - token string or nil"
  [config {:keys [headers cookies]}]
  (let [auth-header (or (get headers "authorization")
                        (get headers "Authorization"))
        by-header (bearer-token auth-header)
        cookie-name (or (get-in config [:auth :cookie-name]) "access_token")
        by-cookie (some-> cookies (get cookie-name) :value str not-empty)]
    (or by-header by-cookie)))

(def clock-skew-seconds
  "Fixed, bounded clock tolerance for JWT exp/nbf validation."
  30)

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
  [issuer audience ^JWKSet jwks now]
  (let [proc (DefaultJWTProcessor.)
        required-claims #{"iss" "sub" "exp"}
        expected-claims (.build (.issuer (JWTClaimsSet$Builder.) issuer))
        claims-verifier (proxy [DefaultJWTClaimsVerifier] [expected-claims required-claims]
                          (currentTime [] (Date. (long (now)))))
        jwk-source (ImmutableJWKSet. jwks)
        key-selector (JWSVerificationKeySelector.
                      (JWSAlgorithm/RS256)
                      jwk-source)]
    (.setMaxClockSkew claims-verifier clock-skew-seconds)
    (.setJWSKeySelector proc ^JWSKeySelector key-selector)
    (.setJWTClaimsSetVerifier
     proc
     (reify com.nimbusds.jwt.proc.JWTClaimsSetVerifier
       (verify [_ claims _ctx]
         (.verify claims-verifier claims _ctx)
         (when (str/blank? (.getSubject ^JWTClaimsSet claims))
           (throw (BadJWTException. "Invalid subject")))
         (let [auds (.getAudience ^JWTClaimsSet claims)
               azp (try (.getStringClaim claims "azp") (catch Exception _ nil))]
           (when-not (audience-valid? audience auds azp)
             (throw (BadJWTException. "Invalid audience")))))))
    proc))

(defn- parse-token
  "Reject malformed, oversized or unsupported tokens before any metadata fetch."
  [token]
  (when-not (and (string? token) (<= 1 (count token) 16384))
    (throw (BadJWTException. "Invalid token size")))
  (let [jwt (SignedJWT/parse token)
        header (.getHeader jwt)
        kid (.getKeyID header)]
    (when-not (and (= JWSAlgorithm/RS256 (.getAlgorithm header))
                   (string? kid) (<= 1 (count kid) 256) (not (str/blank? kid)))
      (throw (BadJWTException. "Invalid signing algorithm or key identifier")))
    jwt))

(defn verify-token
  "Verify a JWT access token from the configured issuer.

  Arity:
  - (verify-token config token)
  - (verify-token config token opts) ; isolated fetch/cache/clock injection

  Inputs:
  - config: full config map
  - token: JWT string

  Output:
  - OIDC user map

  Throws:
  - ex-info with :type :samuraibff.auth/invalid-token on verification failure"
  ([config token]
   (verify-token config token {}))
  ([config token {:keys [now] :as opts}]
   (try
     (let [jwt (parse-token token)
           issuer (get-in config [:auth :issuer])
           audience (get-in config [:auth :audience])
           _ (when (or (str/blank? issuer) (str/blank? audience))
               (throw (BadJWTException. "Missing auth configuration")))
           key-set (jwks/resolve-key-set! issuer (.getKeyID (.getHeader jwt)) opts)
           ^ConfigurableJWTProcessor proc (jwt-processor issuer audience key-set
                                                         (or now #(System/currentTimeMillis)))
           claims (.process proc jwt nil)
           raw (-> claims
                   (.toJSONObject)
                   (json/write-value-as-string json-mapper)
                   (json/read-value json-mapper))
           expires-at-ms (.getTime (.getExpirationTime ^JWTClaimsSet claims))]
       {:sub (:sub raw)
        :preferred_username (:preferred_username raw)
        :email (:email raw)
        :expires-at-ms expires-at-ms
        :raw raw})
     (catch Exception _
       (throw (ex-info "Invalid token"
                       {:type :samuraibff.auth/invalid-token}))))))

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

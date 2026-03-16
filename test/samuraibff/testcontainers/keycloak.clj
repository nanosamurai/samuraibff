;; Copyright (c) samuraibff contributors.
(ns samuraibff.testcontainers.keycloak
  "Keycloak Testcontainers helpers.

  Purpose:
  - Start a real Keycloak instance for auth integration tests.
  - Provision a test realm with:
      * end-user client (Direct Access Grants) that emits `tenant_id` claim
      * admin client (service account) with realm-management permissions

  Public API:
  - `with-keycloak`
  - `base-url`
  - `realm-issuer`
  - `token-endpoint`
  - `provision-test-realm!`
  - `password-token!`
  - `client-credentials-token!`

  Notes:
  - We intentionally use Keycloak Admin REST API directly (no extra deps).
  - Keycloak image is pinned to `keycloak/keycloak:26.4.7` by default.
  "
  (:require
    [clojure.string :as str]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log])
  (:import
    (java.net URI)
    (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
    (java.nio.charset StandardCharsets)
    (java.time Duration)
    (org.testcontainers.containers GenericContainer)
    (org.testcontainers.containers.wait.strategy Wait)
    (org.testcontainers.utility DockerImageName)))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword
                       :encode-key-fn name}))

(def ^:private http-client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.connectTimeout (Duration/ofSeconds 7))
      (.build)))

(defn- form-encode
  "x-www-form-urlencoded encoding (small helper, avoids extra deps)."
  [m]
  (->> m
       (map (fn [[k v]]
              (str (java.net.URLEncoder/encode (name k) "UTF-8")
                   "="
                   (java.net.URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn base-url
  "Return the external base URL for a running Keycloak container.

  Returns: string like `http://127.0.0.1:12345`"
  [^GenericContainer c]
  (str "http://" (.getHost c) ":" (.getMappedPort c 8080)))

(defn realm-issuer
  "Return OIDC issuer for a realm.

  IMPORTANT: We derive issuer from Keycloak's discovery endpoint to avoid
  hostname/port mismatch issues.

  Returns issuer string."
  [^GenericContainer c realm]
  (let [url (str (base-url c) "/realms/" realm "/.well-known/openid-configuration")
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create url))
                (.timeout (Duration/ofSeconds 10))
                (.GET)
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak discovery failed" {:status status :url url :body body})))
    (:issuer (json/read-value body json-mapper))))

(defn token-endpoint
  "Return token endpoint for a realm (from discovery)."
  [^GenericContainer c realm]
  (let [url (str (base-url c) "/realms/" realm "/.well-known/openid-configuration")
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create url))
                (.timeout (Duration/ofSeconds 10))
                (.GET)
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak discovery failed" {:status status :url url :body body})))
    (:token_endpoint (json/read-value body json-mapper))))

(defn- http-request-json!
  "Make an HTTP request and parse JSON body.

  Inputs:
  - {:keys [method url headers body]}

  Returns:
  - {:status int :headers map :body any :raw string}"
  [{:keys [method url headers body]}]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.timeout (Duration/ofSeconds 20)))
        builder (reduce (fn [b [k v]] (.header b (str k) (str v))) builder (or headers {}))
        builder (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString (or body "") StandardCharsets/UTF_8))
                  :put (.PUT builder (HttpRequest$BodyPublishers/ofString (or body "") StandardCharsets/UTF_8))
                  :delete (.DELETE builder)
                  (throw (ex-info "Unsupported method" {:method method})))
        req (.build builder)
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body-str (.body resp)
        parsed (try
                 (when (seq (str body-str))
                   (json/read-value body-str json-mapper))
                 (catch Exception _ body-str))
        headers-map (->> (.map (.headers resp))
                         (into {} (map (fn [[k v]] [(str/lower-case (str k)) (vec v)]))))]
    {:status status
     :headers headers-map
     :body parsed
     :raw body-str}))

(defn- master-admin-token!
  "Fetch admin access token for Keycloak master realm using admin-cli + password grant." 
  [^GenericContainer c {:keys [admin-username admin-password]}]
  (let [url (str (base-url c) "/realms/master/protocol/openid-connect/token")
        params {:grant_type "password"
                :client_id "admin-cli"
                :username admin-username
                :password admin-password}
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create url))
                (.timeout (Duration/ofSeconds 20))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString (form-encode params) StandardCharsets/UTF_8))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak admin token fetch failed" {:status status :url url :body body})))
    (let [m (json/read-value body json-mapper)
          tok (:access_token m)]
      (when (str/blank? (str tok))
        (throw (ex-info "Keycloak token response missing access_token" {:resp m})))
      tok)))

(defn start-keycloak!
  "Start a Keycloak testcontainer.

  Returns: GenericContainer"
  ([]
   (start-keycloak! {}))
  ([{:keys [image admin-username admin-password]
     :or {image "keycloak/keycloak:26.4.7"
          admin-username "admin"
          admin-password "admin"}}]
   (let [img (DockerImageName/parse image)
         c (doto (GenericContainer. img)
             (.withExposedPorts (into-array Integer [(int 8080)]))
             (.withEnv "KEYCLOAK_ADMIN" admin-username)
             (.withEnv "KEYCLOAK_ADMIN_PASSWORD" admin-password)
             (.withEnv "KC_HEALTH_ENABLED" "true")
             (.withEnv "KC_METRICS_ENABLED" "false")
             ;; Allow Keycloak to derive issuer/urls from the request Host header.
             (.withCommand (into-array String ["start-dev"
                                               "--http-port=8080"
                                               "--hostname-strict=false"]))
             ;; Keycloak 26 exposes SmallRye health on the management port (9000),
             ;; not on the main HTTP port. For simplicity, we wait for the OIDC
             ;; discovery endpoint on 8080.
             (.waitingFor (-> (Wait/forHttp "/realms/master/.well-known/openid-configuration")
                              (.forPort 8080)
                              (.forStatusCode 200)
                              (.withStartupTimeout (java.time.Duration/ofMinutes 3)))))]
     (.start c)
     (log/info "Keycloak testcontainer started" {:base-url (base-url c)})
     c)))

(defn stop-keycloak!
  "Stop a Keycloak testcontainer." 
  [^GenericContainer c]
  (when c
    (try
      (.stop c)
      (catch Exception _
        nil))))

(defmacro with-keycloak
  "Run body with a running Keycloak testcontainer.

  Binds:
  - container-sym => GenericContainer" 
  [[container-sym] & body]
  `(let [~container-sym (start-keycloak!)]
     (try
       ~@body
       (finally
         (stop-keycloak! ~container-sym)))))

(defn- create-realm!
  [^GenericContainer c admin-token realm]
  (let [url (str (base-url c) "/admin/realms")
        repr {:realm (str realm)
              :enabled true}
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string repr json-mapper)})]
    (when-not (or (= status 201) (= status 204))
      ;; tolerate re-runs locally: Keycloak returns 409 when realm exists
      (when-not (= status 409)
        (throw (ex-info "Keycloak create realm failed" {:status status :url url :body raw :realm realm}))))
    realm))

(defn- list-clients
  [^GenericContainer c admin-token realm client-id]
  (let [url (str (base-url c) "/admin/realms/" realm "/clients?clientId="
                 (java.net.URLEncoder/encode (str client-id) "UTF-8"))
        {:keys [status body raw]} (http-request-json!
                                   {:method :get
                                    :url url
                                    :headers {"Authorization" (str "Bearer " admin-token)}})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak list clients failed" {:status status :url url :body raw})))
    (or body [])))

(defn- client-uuid
  [^GenericContainer c admin-token realm client-id]
  (some-> (list-clients c admin-token realm client-id)
          first
          :id))

(defn- create-client!
  "Create a client in realm.

  Returns client UUID (not clientId)."
  [^GenericContainer c admin-token realm client-repr]
  (let [url (str (base-url c) "/admin/realms/" realm "/clients")
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string client-repr json-mapper)})]
    (when-not (or (= status 201) (= status 204) (= status 409))
      (throw (ex-info "Keycloak create client failed" {:status status :url url :body raw :client-id (:clientId client-repr)})))
    (let [cid (:clientId client-repr)
          uuid (client-uuid c admin-token realm cid)]
      (when (str/blank? (str uuid))
        (throw (ex-info "Keycloak client lookup failed after create" {:client-id cid})))
      uuid)))

(defn- generate-client-secret!
  "Generate (or rotate) a client secret and return it." 
  [^GenericContainer c admin-token realm client-uuid]
  (let [url (str (base-url c) "/admin/realms/" realm "/clients/" client-uuid "/client-secret")
        {:keys [status body raw]} (http-request-json!
                                   {:method :post
                                    :url url
                                    :headers {"Authorization" (str "Bearer " admin-token)}})
        secret (or (:value body) (:clientSecret body))]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak generate secret failed" {:status status :url url :body raw})))
    (when (str/blank? (str secret))
      (throw (ex-info "Keycloak secret missing value" {:resp body})))
    (str secret)))

(defn- add-protocol-mapper!
  "Add a protocol mapper to a client (best effort; throws on failure)." 
  [^GenericContainer c admin-token realm client-uuid mapper]
  (let [url (str (base-url c) "/admin/realms/" realm "/clients/" client-uuid "/protocol-mappers/models")
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string mapper json-mapper)})]
    (when-not (or (= status 201) (= status 204) (= status 409))
      (throw (ex-info "Keycloak create protocol mapper failed" {:status status :url url :body raw :mapper (:name mapper)})))
    true))

(defn- create-user!
  "Create a user and set password.

  Returns user UUID." 
  [^GenericContainer c admin-token realm {:keys [username password email tenant-id]}]
  (let [create-url (str (base-url c) "/admin/realms/" realm "/users")
        repr {:username (str username)
              :enabled true
              :email (or email (str username "@example.com"))
              :emailVerified true
              ;; Some realms / Keycloak defaults require profile fields; populate them
              ;; to avoid UPDATE_PROFILE required-action blocking password grants.
              :firstName "Test"
              :lastName (str (str/upper-case (subs (str username) 0 1))
                             (subs (str username) 1))
              ;; Avoid required actions blocking direct grants.
              :requiredActions []
              ;; Attributes are multi-valued in Keycloak JSON.
              :attributes {"tenant_id" [(str tenant-id)]}}
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url create-url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string repr json-mapper)})]
    (when-not (or (= status 201) (= status 204) (= status 409))
      (throw (ex-info "Keycloak create user failed" {:status status :url create-url :body raw :username username})))
    ;; Lookup user id.
    (let [lookup-url (str (base-url c) "/admin/realms/" realm "/users?username="
                          (java.net.URLEncoder/encode (str username) "UTF-8"))
          {:keys [status body raw]} (http-request-json!
                                     {:method :get
                                      :url lookup-url
                                      :headers {"Authorization" (str "Bearer " admin-token)}})
          _ (when-not (<= 200 status 299)
              (throw (ex-info "Keycloak lookup user failed" {:status status :url lookup-url :body raw})))
          user-id (some-> body first :id)]
      (when (str/blank? (str user-id))
        (throw (ex-info "Keycloak lookup user missing id" {:username username :resp body})))
      ;; Set password.
      (let [pw-url (str (base-url c) "/admin/realms/" realm "/users/" user-id "/reset-password")
            pw-repr {:type "password" :value (str password) :temporary false}
            {:keys [status raw]} (http-request-json!
                                   {:method :put
                                    :url pw-url
                                    :headers {"Authorization" (str "Bearer " admin-token)
                                              "Content-Type" "application/json"}
                                    :body (json/write-value-as-string pw-repr json-mapper)})]
        (when-not (<= 200 status 299)
          (throw (ex-info "Keycloak set password failed" {:status status :url pw-url :body raw :username username}))))

      ;; Ensure requiredActions are cleared (some realm defaults add UPDATE_PROFILE).
      (let [put-url (str (base-url c) "/admin/realms/" realm "/users/" user-id)
            put-repr (assoc repr :id (str user-id))
            {:keys [status raw]} (http-request-json!
                                   {:method :put
                                    :url put-url
                                    :headers {"Authorization" (str "Bearer " admin-token)
                                              "Content-Type" "application/json"}
                                    :body (json/write-value-as-string put-repr json-mapper)})]
        (when-not (<= 200 status 299)
          (throw (ex-info "Keycloak update user failed" {:status status :url put-url :body raw :username username}))))

      user-id)))

(defn- service-account-user-id
  [^GenericContainer c admin-token realm client-uuid]
  (let [url (str (base-url c) "/admin/realms/" realm "/clients/" client-uuid "/service-account-user")
        {:keys [status body raw]} (http-request-json!
                                   {:method :get
                                    :url url
                                    :headers {"Authorization" (str "Bearer " admin-token)}})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak get service account user failed" {:status status :url url :body raw})))
    (let [uid (:id body)]
      (when (str/blank? (str uid))
        (throw (ex-info "Keycloak service account user missing id" {:resp body})))
      uid)))

(defn- realm-management-client-uuid
  [^GenericContainer c admin-token realm]
  (or (client-uuid c admin-token realm "realm-management")
      (throw (ex-info "Missing realm-management client in realm" {:realm realm}))))

(defn- client-role-repr
  [^GenericContainer c admin-token realm client-uuid role-name]
  (let [url (str (base-url c) "/admin/realms/" realm "/clients/" client-uuid "/roles/" role-name)
        {:keys [status body raw]} (http-request-json!
                                   {:method :get
                                    :url url
                                    :headers {"Authorization" (str "Bearer " admin-token)}})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak get client role failed" {:status status :url url :body raw :role role-name})))
    body))

(defn- grant-service-account-role!
  "Grant a client role (in realm-management) to service-account user." 
  [^GenericContainer c admin-token realm service-user-id realm-mgmt-client-uuid role-name]
  (let [role (client-role-repr c admin-token realm realm-mgmt-client-uuid role-name)
        url (str (base-url c)
                 "/admin/realms/" realm
                 "/users/" service-user-id
                 "/role-mappings/clients/" realm-mgmt-client-uuid)
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string [role] json-mapper)})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak grant role failed" {:status status :url url :body raw :role role-name})))
    true))

(defn- create-realm-role!
  "Create a realm role (idempotent).

  Returns role name." 
  [^GenericContainer c admin-token realm role-name]
  (let [url (str (base-url c) "/admin/realms/" realm "/roles")
        repr {:name (str role-name)}
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string repr json-mapper)})]
    (when-not (or (= status 201) (= status 204) (= status 409))
      (throw (ex-info "Keycloak create realm role failed" {:status status :url url :body raw :role role-name})))
    (str role-name)))

(defn- realm-role-repr
  [^GenericContainer c admin-token realm role-name]
  (let [url (str (base-url c) "/admin/realms/" realm "/roles/" (java.net.URLEncoder/encode (str role-name) "UTF-8"))
        {:keys [status body raw]} (http-request-json!
                                   {:method :get
                                    :url url
                                    :headers {"Authorization" (str "Bearer " admin-token)}})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak get realm role failed" {:status status :url url :body raw :role role-name})))
    body))

(defn- grant-realm-role!
  "Grant a realm role to a user." 
  [^GenericContainer c admin-token realm user-id role-name]
  (let [role (realm-role-repr c admin-token realm role-name)
        url (str (base-url c) "/admin/realms/" realm "/users/" user-id "/role-mappings/realm")
        {:keys [status raw]} (http-request-json!
                               {:method :post
                                :url url
                                :headers {"Authorization" (str "Bearer " admin-token)
                                          "Content-Type" "application/json"}
                                :body (json/write-value-as-string [role] json-mapper)})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak grant realm role failed" {:status status :url url :body raw :role role-name :user-id user-id})))
    true))

(defn provision-test-realm!
  "Provision a realm suitable for samuraibff auth integration tests.

  Creates:
  - realm
  - end-user client (Direct Access Grants) `web-client-id`
      * emits claim `tenant_id` from user attribute `tenant_id`
  - admin client (service-account) `admin-client-id` with realm-management manage-clients
  - two users, bound to different tenant ids (user attribute)

  Returns map:
  {:realm string
   :issuer string
   :token-endpoint string
   :web-client-id string
   :admin-client-id string
   :admin-client-secret string
   :users {:alice {:username ... :password ... :tenant-id ...}
           :bob   {...}}}
  "
  [^GenericContainer c
   {:keys [realm web-client-id admin-client-id alice bob admin-username admin-password]
    :or {realm "nanosamurai-test"
         web-client-id "bff-web"
         admin-client-id "bff-admin"
         admin-username "admin"
         admin-password "admin"
         alice {:username "alice" :password "alice" :tenant-id "00000000-0000-0000-0000-000000000000"}
         bob   {:username "bob"   :password "bob"   :tenant-id "00000000-0000-0000-0000-000000000001"}}}]
  (let [admin-token (master-admin-token! c {:admin-username admin-username
                                            :admin-password admin-password})]
    (create-realm! c admin-token realm)

    ;; --- Web client (end-user, password grant) ---
    (let [web-client-uuid (create-client!
                            c admin-token realm
                            {:clientId web-client-id
                             :name "BFF Web (test)"
                             :enabled true
                             :publicClient true
                             :directAccessGrantsEnabled true
                             :standardFlowEnabled false
                             :serviceAccountsEnabled false
                             :protocol "openid-connect"})
          tenant-mapper {:name "tenant_id"
                         :protocol "openid-connect"
                         :protocolMapper "oidc-usermodel-attribute-mapper"
                         :consentRequired false
                         :config {"user.attribute" "tenant_id"
                                  "claim.name" "tenant_id"
                                  "jsonType.label" "String"
                                  "id.token.claim" "false"
                                  "access.token.claim" "true"
                                  "userinfo.token.claim" "true"}}
          _ (add-protocol-mapper! c admin-token realm web-client-uuid tenant-mapper)

          ;; --- Admin client (service account) ---
          admin-client-uuid (create-client!
                              c admin-token realm
                              {:clientId admin-client-id
                               :name "BFF Admin (test)"
                               :enabled true
                               :publicClient false
                               :serviceAccountsEnabled true
                               :directAccessGrantsEnabled false
                               :standardFlowEnabled false
                               :protocol "openid-connect"})
          admin-client-secret (generate-client-secret! c admin-token realm admin-client-uuid)
          sa-user-id (service-account-user-id c admin-token realm admin-client-uuid)
          realm-mgmt-uuid (realm-management-client-uuid c admin-token realm)
          _ (grant-service-account-role! c admin-token realm sa-user-id realm-mgmt-uuid "manage-clients")
          _ (grant-service-account-role! c admin-token realm sa-user-id realm-mgmt-uuid "view-clients")

          ;; --- Users + tenant roles (fallback path used by samuraibff) ---
          alice-role (str "tenant:" (:tenant-id alice))
          bob-role (str "tenant:" (:tenant-id bob))
          _ (create-realm-role! c admin-token realm alice-role)
          _ (create-realm-role! c admin-token realm bob-role)
          alice-id (create-user! c admin-token realm alice)
          bob-id (create-user! c admin-token realm bob)
          _ (grant-realm-role! c admin-token realm alice-id alice-role)
          _ (grant-realm-role! c admin-token realm bob-id bob-role)

          issuer (realm-issuer c realm)
          tok-endpoint (token-endpoint c realm)]
      {:realm realm
       :issuer issuer
       :token-endpoint tok-endpoint
       :web-client-id web-client-id
       :admin-client-id admin-client-id
       :admin-client-secret admin-client-secret
       :users {:alice (assoc alice :id alice-id :tenant-role alice-role)
               :bob   (assoc bob :id bob-id :tenant-role bob-role)}})))

(defn password-token!
  "Mint an access token using Direct Access Grants (password grant).

  Returns access token string." 
  [token-endpoint {:keys [client-id username password]}]
  (let [params {:grant_type "password"
                :client_id (str client-id)
                :username (str username)
                :password (str password)
                :scope "openid"}
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str token-endpoint)))
                (.timeout (Duration/ofSeconds 20))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString (form-encode params) StandardCharsets/UTF_8))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak password token fetch failed" {:status status :url token-endpoint :body body})))
    (let [m (json/read-value body json-mapper)
          tok (:access_token m)]
      (when (str/blank? (str tok))
        (throw (ex-info "Token response missing access_token" {:resp m})))
      tok)))

(defn client-credentials-token!
  "Mint an access token using OAuth2 client_credentials.

  Returns access token string." 
  [token-endpoint {:keys [client-id client-secret]}]
  (let [params {:grant_type "client_credentials"
                :client_id (str client-id)
                :client_secret (str client-secret)}
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str token-endpoint)))
                (.timeout (Duration/ofSeconds 20))
                (.header "Content-Type" "application/x-www-form-urlencoded")
                (.POST (HttpRequest$BodyPublishers/ofString (form-encode params) StandardCharsets/UTF_8))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak client_credentials token fetch failed" {:status status :url token-endpoint :body body})))
    (let [m (json/read-value body json-mapper)
          tok (:access_token m)]
      (when (str/blank? (str tok))
        (throw (ex-info "Token response missing access_token" {:resp m})))
      tok)))

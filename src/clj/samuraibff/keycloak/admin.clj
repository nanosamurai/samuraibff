(ns samuraibff.keycloak.admin
  "Keycloak Admin API client.

  Purpose:
  - Create/rotate/revoke machine-to-machine (M2M) OAuth2 credentials that are
    represented as confidential Keycloak clients with service accounts enabled.
  - Attach tenant identification into issued tokens (via protocol mapper or role).

  This namespace intentionally hides HTTP details behind a small protocol.
  In tests we can replace the implementation with a fake.

  Integrant component:
  - :samuraibff/keycloak-admin

  Config expected under [:keycloak :admin]:
  - :issuer          string  ; realm issuer URL (same shape as :auth :issuer)
  - :realm           string  ; realm name
  - :client-id       string  ; admin service client id
  - :client-secret   string  ; admin service client secret

  Security notes:
  - Admin credentials must be stored securely (env vars / secret manager).
  - We never persist machine client secrets in Postgres.
  - Returned secrets are show-once to the user.
  "
  (:require
    [clojure.string :as str]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log])
  (:import
    (java.net URI)
    (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
    (java.nio.charset StandardCharsets)
    (java.time Duration)
    (java.util UUID)))

(defprotocol KeycloakAdmin
  (create-m2m-client! [this {:keys [tenant-id name]}]
    "Create a new confidential client with service account enabled.

    Inputs:
    - {:tenant-id string(uuid) :name string}

    Returns:
    - {:client-id string :client-secret string}")
  (rotate-client-secret! [this {:keys [client-id]}]
    "Rotate secret for existing client.

    Returns:
    - {:client-secret string}")
  (disable-client! [this {:keys [client-id]}]
    "Disable existing client.

    Returns: nil"))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword
                       :encode-key-fn name}))

(def ^:private http-client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.connectTimeout (Duration/ofSeconds 7))
      (.build)))

(defn- form-encode
  "x-www-form-urlencoded encoding (small local helper to avoid extra deps)."
  [m]
  (->> m
       (map (fn [[k v]]
              (str (java.net.URLEncoder/encode (name k) "UTF-8")
                   "="
                   (java.net.URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn- token-endpoint
  [issuer]
  (str (str/replace issuer #"/+$" "") "/protocol/openid-connect/token"))

(defn- admin-base-url
  "Return Keycloak admin REST base for the realm.

  Keycloak v17+ exposes admin under:
  {base}/admin/realms/{realm}

  For issuer like https://host/realms/myrealm we want base=https://host.
  "
  [issuer realm]
  (let [issuer0 (str/replace (str issuer) #"/+$" "")
        base (str/replace issuer0 #"/realms/[^/]+$" "")]
    (str base "/admin/realms/" realm)))

(defn- http-post-form!
  [url params]
  (let [^HttpRequest req (-> (HttpRequest/newBuilder)
                             (.uri (URI/create url))
                             (.timeout (Duration/ofSeconds 10))
                             (.header "Content-Type" "application/x-www-form-urlencoded")
                             (.POST (HttpRequest$BodyPublishers/ofString (form-encode params) StandardCharsets/UTF_8))
                             (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)}))

(defn- http-request-json!
  "Make an HTTP request and parse JSON body.

  Returns {:status int :body (parsed-json-or-string)}"
  [{:keys [method url headers body]}]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.timeout (Duration/ofSeconds 10)))
        builder (reduce (fn [b [k v]] (.header b (str k) (str v))) builder (or headers {}))
        builder (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString (or body "") StandardCharsets/UTF_8))
                  :put (.PUT builder (HttpRequest$BodyPublishers/ofString (or body "") StandardCharsets/UTF_8))
                  :delete (.DELETE builder)
                  (throw (ex-info "Unsupported method" {:method method})))
        ^HttpRequest req (.build builder)
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body-str (.body resp)
        parsed (try
                 (when (seq (str body-str))
                   (json/read-value body-str json-mapper))
                 (catch Exception _ body-str))]
    {:status status :body parsed :raw body-str}))

(defn- fetch-admin-token!
  "Get an admin access token via client_credentials.

  Returns token string." 
  [{:keys [issuer client-id client-secret]}]
  (when-not (and (seq (str issuer)) (seq (str client-id)) (seq (str client-secret)))
    (throw (ex-info "Keycloak admin config missing" {:issuer issuer :client-id client-id})))
  (let [{:keys [status body]} (http-post-form!
                               (token-endpoint issuer)
                               {:grant_type "client_credentials"
                                :client_id client-id
                                :client_secret client-secret})]
    (when-not (<= 200 status 299)
      (throw (ex-info "Keycloak admin token fetch failed" {:status status :body (subs (str body) 0 (min 2000 (count (str body))))})))
    (let [m (json/read-value body json-mapper)
          tok (:access_token m)]
      (when (str/blank? (str tok))
        (throw (ex-info "Keycloak token response missing access_token" {:resp m})))
      tok)))

(defn- make-client-id
  "Create a user-visible Keycloak clientId.

  We include tenant id so that even without RBAC, credentials remain naturally
  tenant-bound and easy to identify.

  Returns string." 
  [tenant-id name]
  (let [suffix (subs (str (UUID/randomUUID)) 0 8)
        safe-name (-> (or name "")
                      (str/lower-case)
                      (str/replace #"[^a-z0-9_-]+" "-")
                      (str/replace #"(^-+|-+$)" "")
                      (subs 0 (min 32 (count (str (or name ""))))))
        safe-name (if (str/blank? safe-name) "cred" safe-name)]
    (str "m2m-" tenant-id "-" safe-name "-" suffix)))

(defn- ensure-tenant-claim-mapper!
  "Ensure a protocol mapper exists for tenant_id claim.

  For now this is best-effort; if it fails we still create the client.
  We’ll enforce tenant claim presence at the BFF auth layer separately." 
  [_deps token admin-base client-uuid tenant-id]
  (let [tenant-id (str tenant-id)
        url (str admin-base "/clients/" client-uuid "/protocol-mappers/models")
        {:keys [status body raw]} (http-request-json!
                                   {:method :get
                                    :url url
                                    :headers {"Authorization" (str "Bearer " token)}})
        _ (when-not (<= 200 status 299)
            (throw (ex-info "Keycloak list protocol mappers failed" {:status status :body raw :client-uuid client-uuid})))
        existing? (some (fn [m]
                          (and (= "tenant_id" (get m :name))
                               (= "oidc-hardcoded-claim-mapper" (get m :protocolMapper))))
                        (or body []))]
    (when-not existing?
      (let [mapper {:name "tenant_id"
                    :protocol "openid-connect"
                    :protocolMapper "oidc-hardcoded-claim-mapper"
                    :consentRequired false
                    :config {"claim.name" "tenant_id"
                             "claim.value" tenant-id
                             "jsonType.label" "String"
                             "id.token.claim" "false"
                             "access.token.claim" "true"
                             "userinfo.token.claim" "true"}}
            {:keys [status raw]} (http-request-json!
                                  {:method :post
                                   :url url
                                   :headers {"Authorization" (str "Bearer " token)
                                             "Content-Type" "application/json"}
                                   :body (json/write-value-as-string mapper json-mapper)})]
        (when-not (<= 200 status 299)
          (throw (ex-info "Keycloak create protocol mapper failed" {:status status :body raw :client-uuid client-uuid})))))))

(defrecord HttpKeycloakAdmin [deps]
  KeycloakAdmin
  (create-m2m-client! [_this {:keys [tenant-id name]}]
    (let [{:keys [issuer realm]} deps
          token (fetch-admin-token! deps)
          admin-base (admin-base-url issuer realm)
          client-id (make-client-id tenant-id name)
          mapper {:name "tenant_id"
                  :protocol "openid-connect"
                  :protocolMapper "oidc-hardcoded-claim-mapper"
                  :consentRequired false
                  :config {"claim.name" "tenant_id"
                           "claim.value" (str tenant-id)
                           "jsonType.label" "String"
                           "id.token.claim" "false"
                           "access.token.claim" "true"
                           "userinfo.token.claim" "true"}}
          client-repr {:clientId client-id
                       :name (str name)
                       :enabled true
                       :publicClient false
                       :serviceAccountsEnabled true
                       :directAccessGrantsEnabled false
                       :standardFlowEnabled false
                       :protocolMappers [mapper]
                       :protocol "openid-connect"}
          {:keys [status raw]} (http-request-json!
                                {:method :post
                                 :url (str admin-base "/clients")
                                 :headers {"Authorization" (str "Bearer " token)
                                           "Content-Type" "application/json"}
                                 :body (json/write-value-as-string client-repr json-mapper)})]
      (when-not (or (= status 201) (= status 204))
        (throw (ex-info "Keycloak create client failed" {:status status :body raw :client-id client-id})))

      ;; Find the created client's UUID by querying by clientId.
      (let [{:keys [status body raw]} (http-request-json!
                                       {:method :get
                                        :url (str admin-base "/clients?clientId=" (java.net.URLEncoder/encode client-id "UTF-8"))
                                        :headers {"Authorization" (str "Bearer " token)}})
            _ (when-not (<= 200 status 299)
                (throw (ex-info "Keycloak lookup client failed" {:status status :body raw :client-id client-id})))
            client-uuid (some-> body first :id)
            _ (when (str/blank? (str client-uuid))
                (throw (ex-info "Keycloak lookup did not return client UUID" {:client-id client-id :resp body})))

            ;; Create / regenerate secret (Keycloak returns it).
            {:keys [status body raw]} (http-request-json!
                                       {:method :post
                                        :url (str admin-base "/clients/" client-uuid "/client-secret")
                                        :headers {"Authorization" (str "Bearer " token)}})
            _ (when-not (<= 200 status 299)
                (throw (ex-info "Keycloak generate secret failed" {:status status :body raw :client-id client-id})))
            secret (or (:value body) (:clientSecret body))]
        (when (str/blank? (str secret))
          (throw (ex-info "Keycloak secret response missing value" {:client-id client-id :resp body})))

        (try
          (ensure-tenant-claim-mapper! deps token admin-base client-uuid tenant-id)
          (catch Exception e
            (log/warn e "Failed ensuring tenant claim mapper" {:client-id client-id :tenant-id tenant-id})))

        {:client-id client-id
         :client-secret secret})))

  (rotate-client-secret! [_this {:keys [client-id]}]
    (let [{:keys [issuer realm]} deps
          token (fetch-admin-token! deps)
          admin-base (admin-base-url issuer realm)
          ;; Lookup UUID
          {:keys [status body raw]} (http-request-json!
                                     {:method :get
                                      :url (str admin-base "/clients?clientId=" (java.net.URLEncoder/encode (str client-id) "UTF-8"))
                                      :headers {"Authorization" (str "Bearer " token)}})
          _ (when-not (<= 200 status 299)
              (throw (ex-info "Keycloak lookup client failed" {:status status :body raw :client-id client-id})))
          client-uuid (some-> body first :id)
          _ (when (str/blank? (str client-uuid))
              (throw (ex-info "Keycloak lookup did not return client UUID" {:client-id client-id :resp body})))
          {:keys [status body raw]} (http-request-json!
                                     {:method :post
                                      :url (str admin-base "/clients/" client-uuid "/client-secret")
                                      :headers {"Authorization" (str "Bearer " token)}})
          _ (when-not (<= 200 status 299)
              (throw (ex-info "Keycloak rotate secret failed" {:status status :body raw :client-id client-id})))
          secret (or (:value body) (:clientSecret body))]
      (when (str/blank? (str secret))
        (throw (ex-info "Keycloak secret response missing value" {:client-id client-id :resp body})))
      {:client-secret secret}))

  (disable-client! [_this {:keys [client-id]}]
    (let [{:keys [issuer realm]} deps
          token (fetch-admin-token! deps)
          admin-base (admin-base-url issuer realm)
          {:keys [status body raw]} (http-request-json!
                                     {:method :get
                                      :url (str admin-base "/clients?clientId=" (java.net.URLEncoder/encode (str client-id) "UTF-8"))
                                      :headers {"Authorization" (str "Bearer " token)}})
          _ (when-not (<= 200 status 299)
              (throw (ex-info "Keycloak lookup client failed" {:status status :body raw :client-id client-id})))
          client-uuid (some-> body first :id)
          _ (when (str/blank? (str client-uuid))
              (throw (ex-info "Keycloak lookup did not return client UUID" {:client-id client-id :resp body})))
          ;; GET existing repr, then PUT enabled=false
          {:keys [status body raw]} (http-request-json!
                                     {:method :get
                                      :url (str admin-base "/clients/" client-uuid)
                                      :headers {"Authorization" (str "Bearer " token)}})
          _ (when-not (<= 200 status 299)
              (throw (ex-info "Keycloak get client repr failed" {:status status :body raw :client-id client-id})))
          repr (assoc body :enabled false)
          {:keys [status raw]} (http-request-json!
                                {:method :put
                                 :url (str admin-base "/clients/" client-uuid)
                                 :headers {"Authorization" (str "Bearer " token)
                                           "Content-Type" "application/json"}
                                 :body (json/write-value-as-string repr json-mapper)})]
      (when-not (<= 200 status 299)
        (throw (ex-info "Keycloak disable client failed" {:status status :body raw :client-id client-id})))
      nil)))

(defmethod ig/init-key :samuraibff/keycloak-admin
  [_ {:keys [config]}]
  (let [issuer (get-in config [:keycloak :admin :issuer])
        realm (get-in config [:keycloak :admin :realm])
        client-id (get-in config [:keycloak :admin :client-id])
        client-secret (get-in config [:keycloak :admin :client-secret])]
    (if-not (and (seq (str issuer)) (seq (str realm)) (seq (str client-id)) (seq (str client-secret)))
      (do
        (log/warn "Keycloak admin client disabled (missing config)" {:issuer issuer
                                                                     :realm realm
                                                                     :client-id client-id})
        nil)
      (do
        (log/info "Keycloak admin client initialized" {:issuer issuer :realm realm :client-id client-id})
        (->HttpKeycloakAdmin {:issuer issuer
                              :realm realm
                              :client-id client-id
                              :client-secret client-secret})))))

(defmethod ig/halt-key! :samuraibff/keycloak-admin
  [_ _]
  nil)

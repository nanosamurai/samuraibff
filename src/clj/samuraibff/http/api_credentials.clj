(ns samuraibff.http.api-credentials
  "HTTP handlers for tenant-scoped M2M credential management.

  Endpoints (under /api, auth required):
  - GET    /api/api-credentials              ; list
  - POST   /api/api-credentials              ; create (returns secret once)
  - POST   /api/api-credentials/:id/rotate   ; rotate secret (returns secret once)
  - DELETE /api/api-credentials/:id          ; revoke/disable

  Notes:
  - These endpoints are for *human* users managing machine credentials.
  - M2M usage itself uses standard OAuth2 client_credentials against Keycloak,
    then calls BFF with Authorization: Bearer <token>.
  - We never store secrets in Postgres; only Keycloak client id and audit.
  "
  (:require
    [clojure.string :as str]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [samuraibff.db.api-credentials :as db.api-creds]
    [samuraibff.keycloak.admin :as kc.admin]
    [samuraibff.util.uuid :as util.uuid])
  (:import
    (java.util UUID)
    (javax.sql DataSource)))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defn- json-response
  "Return a Ring JSON response.

  Inputs:
  - status: int
  - body: map

  Returns: Ring response map." 
  [status body]
  ;; NOTE: Return a *data* body (map). Muuntaja JSON-encodes it.
  ;; This keeps Reitit response coercion compatible with Malli schemas.
  {:status status
   :body body})

(defn- require-tenant-uuid!
  "Read tenant id from request and coerce into UUID.

  Inputs:
  - req: Ring request (expects :auth/tenant-id)

  Returns:
  - UUID

  Throws ex-info with :type :samuraibff.http/missing-tenant-id or :samuraibff.http/invalid-tenant-id." 
  [req]
  (let [tid (some-> (:auth/tenant-id req) str str/trim)]
    (cond
      (str/blank? tid)
      (throw (ex-info "Missing tenant id" {:type :samuraibff.http/missing-tenant-id}))

      :else
      (try
        (UUID/fromString tid)
        (catch Exception _
          (throw (ex-info "Invalid tenant id" {:type :samuraibff.http/invalid-tenant-id
                                               :tenant-id tid})))))))

(defn- parse-uuid-or-nil
  [s]
  (try
    (UUID/fromString (str s))
    (catch Exception _
      nil)))

(defn list-api-credentials-handler
  "Handler for GET /api/api-credentials.

  Returns:
  - 200 {ok true, items [...]}
  - 403 missing-tenant-id
  - 503 db-unavailable" 
  [{:keys [db]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (get-in db [:ds])]
        (when-not ds
          (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
        (let [items (db.api-creds/list-credentials ds tenant-uuid)
              body {:ok true
                    :tenant_id (str tenant-uuid)
                    :items (mapv (fn [row]
                                  (-> row
                                      (update :id str)
                                      ;; NOTE: list-credentials does not select tenant_id; however the
                                      ;; API response schema (and OpenAPI) includes it per item.
                                      ;; Since this endpoint is tenant-scoped, we can safely attach the
                                      ;; tenant id from the request.
                                      (assoc :tenant_id (str tenant-uuid))
                                      (update :created_at str)
                                      ;; Optional timestamps must stay nil (JSON null) when absent.
                                      ;; Never stringify nil into the literal "nil" string.
                                      (update :last_used_at (fn [v] (some-> v str)))
                                      (update :revoked_at (fn [v] (some-> v str)))))
                                items)}]
          (json-response 200 body)))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            (do
              (log/error e "Failed listing api credentials")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error listing api credentials")
        (json-response 500 {:ok false :message "internal-error"})))))

(defn create-api-credential-handler
  "Handler for POST /api/api-credentials.

  Body (JSON): {\"name\": \"...\"}

  Returns:
  - 200 {ok true, credential_id, client_id, client_secret}
  - 400 missing-name
  - 503 db-unavailable / keycloak-unavailable" 
  [{:keys [config db keycloak-admin]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (get-in db [:ds])
            name (or (get-in req [:body-params :name]) (get-in req [:params :name]))
            name (some-> name str str/trim)
            created-by-sub (get-in req [:auth/user :sub])
            audience-client-id (get-in config [:auth :audience])]
        (when-not ds
          (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
        (when (str/blank? name)
          (throw (ex-info "Missing name" {:type :samuraibff.http/missing-name})))

        (when-not keycloak-admin
          (throw (ex-info "Missing keycloak admin" {:type :samuraibff.http/missing-keycloak-admin})))

        (let [{:keys [client-id client-secret]} (kc.admin/create-m2m-client!
                                                 keycloak-admin
                                                 {:tenant-id (str tenant-uuid)
                                                  :name name
                                                  ;; Ensure M2M tokens include the correct `aud` for the BFF.
                                                  ;; In our setup, the expected audience equals the BFF client-id.
                                                  :audience-client-id audience-client-id})
              id (util.uuid/uuid7)
              _ (db.api-creds/insert-credential!
                  ds
                  {:id id
                   :tenant-id tenant-uuid
                   :name name
                   :keycloak-client-id client-id
                   :created-by-sub created-by-sub})]
          (json-response 200 {:ok true
                              :credential_id (str id)
                              :client_id client-id
                              :client_secret client-secret})))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type] :as data} (ex-data e)
              kc-error? (and (keyword? type) (= "samuraibff.keycloak-admin" (namespace type)))
              safe-kc-data (-> (select-keys data [:type :status :url :body :client-id])
                               (update :body (fn [b]
                                               (when-not (nil? b)
                                                 (let [s (str b)]
                                                   (subs s 0 (min 2000 (count s))))))))]
          (cond
            (= type :samuraibff.http/missing-tenant-id)
            (json-response 403 {:ok false :message "missing-tenant-id"})

            (= type :samuraibff.http/invalid-tenant-id)
            (json-response 400 {:ok false :message "invalid-tenant-id"})

            (= type :samuraibff.http/missing-datasource)
            (json-response 503 {:ok false :message "db-unavailable"})

            (= type :samuraibff.http/missing-keycloak-admin)
            (json-response 503 {:ok false :message "keycloak-admin-unavailable"})

            (= type :samuraibff.http/missing-name)
            (json-response 400 {:ok false :message "missing-name"})

            kc-error?
            (do
              (log/error e "Keycloak admin error creating api credential" {:keycloak safe-kc-data})
              (json-response 502 {:ok false
                                 :message "keycloak-admin-error"
                                 :keycloak safe-kc-data}))

            :else
            (do
              (log/error e "Failed creating api credential")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error creating api credential")
        (json-response 500 {:ok false :message "internal-error"})))))

(defn rotate-api-credential-handler
  "Handler for POST /api/api-credentials/:id/rotate.

  Returns:
  - 200 {ok true, client_secret}
  - 400 invalid-id
  - 404 not-found
  - 503 keycloak-admin-unavailable" 
  [{:keys [db keycloak-admin]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (get-in db [:ds])
            id-str (get-in req [:path-params :id])
            cred-uuid (parse-uuid-or-nil id-str)]
        (when-not ds
          (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
        (when-not cred-uuid
          (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id})))
        (when-not keycloak-admin
          (throw (ex-info "Missing keycloak admin" {:type :samuraibff.http/missing-keycloak-admin})))

        ;; Lookup keycloak client id in DB.
        (let [row (db.api-creds/find-credential ds tenant-uuid cred-uuid)]
          (if-not row
            (json-response 404 {:ok false :message "not-found"})
            (let [{:keys [client-secret]} (kc.admin/rotate-client-secret!
                                           keycloak-admin
                                           {:client-id (:keycloak_client_id row)})]
              (json-response 200 {:ok true
                                  :credential_id (str cred-uuid)
                                  :client_id (:keycloak_client_id row)
                                  :client_secret client-secret})))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            :samuraibff.http/missing-keycloak-admin (json-response 503 {:ok false :message "keycloak-admin-unavailable"})
            :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
            (do
              (log/error e "Failed rotating api credential")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error rotating api credential")
        (json-response 500 {:ok false :message "internal-error"})))))

(defn revoke-api-credential-handler
  "Handler for DELETE /api/api-credentials/:id.

  Returns:
  - 200 {ok true}
  - 400 invalid-id
  - 404 not-found" 
  [{:keys [db keycloak-admin]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (get-in db [:ds])
            id-str (get-in req [:path-params :id])
            cred-uuid (parse-uuid-or-nil id-str)]
        (when-not ds
          (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
        (when-not cred-uuid
          (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id})))

        ;; Find keycloak client id.
        (let [row (db.api-creds/find-credential ds tenant-uuid cred-uuid)]
          (if-not row
            (json-response 404 {:ok false :message "not-found"})
            (do
              (when keycloak-admin
                (try
                  (kc.admin/disable-client! keycloak-admin {:client-id (:keycloak_client_id row)})
                  (catch Exception e
                    (log/warn e "Failed disabling keycloak client" {:client-id (:keycloak_client_id row)}))))
              (let [{:keys [updated?]} (db.api-creds/revoke-credential! ds tenant-uuid cred-uuid)]
                (if-not updated?
                  (json-response 404 {:ok false :message "not-found"})
                  (json-response 200 {:ok true
                                      :credential_id (str cred-uuid)})))))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
            (do
              (log/error e "Failed revoking api credential")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error revoking api credential")
        (json-response 500 {:ok false :message "internal-error"})))))

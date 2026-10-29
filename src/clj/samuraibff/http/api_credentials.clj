(ns samuraibff.http.api-credentials
  "HTTP handlers for tenant-scoped M2M credential management.

  Endpoints (under /api, auth required):
  - GET    /api/api-credentials              ; list
  - POST   /api/api-credentials              ; create (returns secret once)
  - PATCH  /api/api-credentials/:id          ; organization key manager/audience
  - POST   /api/api-credentials/:id/rotate   ; rotate secret (returns secret once)
  - DELETE /api/api-credentials/:id          ; revoke

  Kinds: `personal` (default; acts as its creator, Builder or Admin) and
  `organization` (Admin only; owns its sessions, which start with the key's
  audience and are managed by its manager).

  Access (`samuraibff.access.policy/credential-scope`):
  - Admin lists every tenant credential, revokes any, and configures
    organization keys. Legacy credentials without a manager are Admin-managed.
  - Builder lists and manages only credentials they manage.
  - Only a personal key's owner rotates it (a new secret acts as the owner).
  - Members, services and API keys get 403. A credential the caller may not
    manage is reported as 404.

  Lifecycle rules (service principal, compensation, revocation) live in
  `samuraibff.api-credentials.lifecycle`.

  Notes:
  - M2M usage itself uses standard OAuth2 client_credentials against Keycloak,
    then calls BFF with Authorization: Bearer <token>.
  - We never store secrets in Postgres; only Keycloak client id and audit.

  Public API:
  - the five handlers
  - `revoke-managed-credentials-for-manager!` (People flow hook)"
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.api-credentials.lifecycle :as lifecycle]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.observability.diagnostics :as diagnostics])
  (:import
   (java.util UUID)))

(defn- json-response
  "Return a Ring response with a data body (Muuntaja encodes it)."
  [status body]
  {:status status
   :body body})

(defn- parse-uuid-or-nil
  [s]
  (try
    (UUID/fromString (str s))
    (catch Exception _
      nil)))

(defn- require-credential-manager!
  "Return the request principal when it may manage some credentials.

  Throws :samuraibff.http/forbidden for Members, services and missing
  principals."
  [req]
  (let [principal (:auth/principal req)]
    (if (and (some? (policy/credential-scope principal))
             (instance? UUID (:tenant-id principal)))
      principal
      (throw (ex-info "Forbidden" {:type :samuraibff.http/forbidden})))))

(defn- require-ds!
  [db]
  (or (:ds db)
      (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource}))))

(defn- require-keycloak-admin!
  [keycloak-admin]
  (or keycloak-admin
      (throw (ex-info "Missing keycloak admin" {:type :samuraibff.http/missing-keycloak-admin}))))

(defn- require-path-id!
  [req]
  (or (parse-uuid-or-nil (get-in req [:path-params :id]))
      (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id}))))

(def ^:private fixed-errors
  {:samuraibff.http/forbidden [403 "forbidden"]
   :samuraibff.http/not-found [404 "not-found"]
   :samuraibff.http/invalid-id [400 "invalid-id"]
   :samuraibff.http/missing-name [400 "missing-name"]
   :samuraibff.http/missing-datasource [503 "db-unavailable"]
   :samuraibff.http/missing-keycloak-admin [503 "keycloak-admin-unavailable"]
   :samuraibff.api-credentials/issuer-missing [503 "auth-issuer-not-configured"]
   :samuraibff.api-credentials/revoked [400 "credential-revoked"]
   :samuraibff.api-credentials/invalid-manager [400 "invalid-manager"]
   :samuraibff.api-credentials/rotate-forbidden [403 "owner-only-rotation"]
   :samuraibff.api-credentials/provisioning-failed [502 "credential-provisioning-failed"]})

(defn- failure-response
  "Map a handler failure to a fixed JSON response without upstream payloads.

  Keycloak admin errors return 502 with only a validated upstream status."
  [^Throwable e log-message]
  (let [{:keys [type] :as data} (ex-data e)
        [status message] (get fixed-errors type)
        kc-error? (and (keyword? type) (= "samuraibff.keycloak-admin" (namespace type)))]
    (cond
      status
      (json-response status {:ok false :message message})

      kc-error?
      (let [upstream-status (diagnostics/http-status (:status data))
            safe-kc-data (cond-> {} upstream-status (assoc :status upstream-status))]
        (log/error log-message (assoc (diagnostics/failure-details e) :keycloak safe-kc-data))
        (json-response 502 {:ok false :message "keycloak-admin-error" :keycloak safe-kc-data}))

      :else
      (do (log/error log-message (diagnostics/failure-details e))
          (json-response 500 {:ok false :message "internal-error"})))))

(defn- manager-view
  [principal row]
  (when-let [manager-id (:manager_user_id row)]
    {:id (str manager-id)
     :display_name (or (:manager_name row) (:manager_email row))
     :is_me (= (str manager-id) (str (:id principal)))}))

(defn- credential-row->api-item
  "Convert a credential row into the API item for a manager principal."
  [principal tenant-uuid row]
  {:id (str (:id row))
   :tenant_id (str tenant-uuid)
   :name (:name row)
   :keycloak_client_id (:keycloak_client_id row)
   :created_by_sub (:created_by_sub row)
   :created_at (str (:created_at row))
   :last_used_at (some-> (:last_used_at row) str)
   :revoked_at (some-> (:revoked_at row) str)
   :manager (manager-view principal row)
   :kind (or (:kind row) "organization")
   :read_scope (or (:read_scope row) "credential")
   :audience (or (:default_visibility row) "private")
   :service_principal_registered (some? (:service_user_id row))
   :identity_sync_state (or (:identity_sync_state row) "synced")
   :can_manage (policy/can-manage-credential? principal row)
   :can_rotate (policy/can-rotate-credential? principal row)
   :can_configure (policy/can-configure-credential? principal row)})

(defn list-api-credentials-handler
  "Handler for GET /api/api-credentials.

  Returns:
  - 200 {ok true, tenant_id, items [...]}: all tenant credentials for Admin,
    own credentials for Builder
  - 403 forbidden for Members and services
  - 503 db-unavailable"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (require-credential-manager! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            opts (when (= :own (policy/credential-scope principal))
                   {:manager-id (:id principal)})
            rows (->> (db.api-creds/list-credentials ds tenant-uuid opts)
                      (filter #(policy/can-manage-credential? principal %)))]
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :items (mapv #(credential-row->api-item principal tenant-uuid %) rows)}))
      (catch Exception e
        (failure-response e "Failed listing api credentials")))))

(defn create-api-credential-handler
  "Handler for POST /api/api-credentials.

  Body (JSON): {\"name\": \"...\", \"kind\": \"personal\"|\"organization\",
  \"read_scope\": \"credential\"|\"owner\" (personal), \"audience\":
  \"private\"|\"tenant\" (organization), \"manager_id\": uuid (organization)}.
  Personal keys: Builder or Admin, owned by the caller. Organization keys:
  Admin only.

  Returns:
  - 200 {ok true, credential_id, client_id, client_secret}
  - 400 missing-name / invalid-manager, 403 forbidden
  - 502 keycloak-admin-error / credential-provisioning-failed
  - 503 db-unavailable / keycloak-admin-unavailable / auth-issuer-not-configured"
  [{:keys [config db keycloak-admin]}]
  (fn [req]
    (try
      (let [principal (require-credential-manager! req)
            ds (require-ds! db)
            body (or (get-in req [:parameters :body]) (:body-params req) {})
            name (some-> (or (:name body) (get-in req [:params :name])) str str/trim)
            _ (when (str/blank? name)
                (throw (ex-info "Missing name" {:type :samuraibff.http/missing-name})))
            kind (or (some-> (:kind body) str) "personal")
            _ (when-not (policy/can-create-credential? principal (keyword kind))
                (throw (ex-info "Forbidden" {:type :samuraibff.http/forbidden})))
            manager-id (when (some? (:manager_id body))
                         (or (parse-uuid-or-nil (:manager_id body))
                             (throw (ex-info "Invalid manager" {:type :samuraibff.api-credentials/invalid-manager}))))
            kc (require-keycloak-admin! keycloak-admin)
            {:keys [credential-id client-id client-secret]}
            (lifecycle/create-credential! ds config kc principal
                                          {:name name
                                           :kind kind
                                           :read-scope (or (some-> (:read_scope body) str) "credential")
                                           :default-visibility (or (some-> (:audience body) str) "private")
                                           :manager-id manager-id
                                           :created-by-sub (get-in req [:auth/user :sub])})]
        (json-response 200 {:ok true
                            :credential_id (str credential-id)
                            :client_id client-id
                            :client_secret client-secret}))
      (catch Exception e
        (failure-response e "Failed creating api credential")))))

(defn configure-api-credential-handler
  "Handler for PATCH /api/api-credentials/:id (organization keys, Admin only).

  Body (JSON): {\"manager_id\": uuid|null, \"audience\": \"private\"|\"tenant\"};
  absent keys stay. A null manager hands the key to Admins. The audience
  applies to sessions created afterwards.

  Returns:
  - 200 {ok true, item}
  - 400 invalid-id / invalid-manager, 403 forbidden, 404 not-found"
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (require-credential-manager! req)
            ds (require-ds! db)
            cred-uuid (require-path-id! req)
            body (or (get-in req [:parameters :body]) (:body-params req) {})
            changes (cond-> {}
                      (contains? body :manager_id)
                      (assoc :manager-id (when (some? (:manager_id body))
                                           (or (parse-uuid-or-nil (:manager_id body))
                                               (throw (ex-info "Invalid manager"
                                                               {:type :samuraibff.api-credentials/invalid-manager})))))
                      (contains? body :audience)
                      (assoc :default-visibility (str (:audience body))))
            _ (lifecycle/configure-credential! ds principal cred-uuid changes)
            row (some #(when (= cred-uuid (:id %)) %)
                      (db.api-creds/list-credentials ds (:tenant-id principal) {}))]
        (json-response 200 {:ok true
                            :item (credential-row->api-item principal (:tenant-id principal) row)}))
      (catch Exception e
        (failure-response e "Failed configuring api credential")))))

(defn rotate-api-credential-handler
  "Handler for POST /api/api-credentials/:id/rotate.

  Returns:
  - 200 {ok true, credential_id, client_id, client_secret}
  - 400 invalid-id / credential-revoked, 403 forbidden / owner-only-rotation,
    404 not-found
  - 503 keycloak-admin-unavailable"
  [{:keys [db keycloak-admin]}]
  (fn [req]
    (try
      (let [principal (require-credential-manager! req)
            ds (require-ds! db)
            cred-uuid (require-path-id! req)
            kc (require-keycloak-admin! keycloak-admin)
            {:keys [client-id client-secret]} (lifecycle/rotate-credential! ds kc principal cred-uuid)]
        (json-response 200 {:ok true
                            :credential_id (str cred-uuid)
                            :client_id client-id
                            :client_secret client-secret}))
      (catch Exception e
        (failure-response e "Failed rotating api credential")))))

(defn revoke-api-credential-handler
  "Handler for DELETE /api/api-credentials/:id.

  The credential is revoked and its service principal suspended before the
  Keycloak client is disabled. `upstream` is \"disabled\" when Keycloak
  confirmed, \"pending\" when the disable must be retried (repeat the DELETE).

  Returns:
  - 200 {ok true, credential_id, upstream}
  - 400 invalid-id, 403 forbidden, 404 not-found"
  [{:keys [db keycloak-admin]}]
  (fn [req]
    (try
      (let [principal (require-credential-manager! req)
            ds (require-ds! db)
            cred-uuid (require-path-id! req)
            {:keys [upstream]} (lifecycle/revoke-credential! ds keycloak-admin principal cred-uuid)]
        (json-response 200 {:ok true
                            :credential_id (str cred-uuid)
                            :upstream upstream}))
      (catch Exception e
        (failure-response e "Failed revoking api credential")))))

(defn revoke-managed-credentials-for-manager!
  "Revoke every active credential managed by a user (People flow hook).

  Inputs: router deps map {:db {:ds DataSource} :keycloak-admin KeycloakAdmin?},
  tenant UUID, manager UUID. Call after the demotion or suspension commits.
  Personal keys are revoked; organization keys become Admin-managed.
  Delegates to `samuraibff.api-credentials.lifecycle/revoke-managed-credentials!`
  (audited as system). Upstream failures never throw: they leave
  `disable_pending`. Returns nil. Throws only when the local revocation
  transaction fails, so the caller can report that credentials are still
  active."
  [{:keys [db keycloak-admin]} ^UUID tenant-id ^UUID manager-user-id]
  (try
    (lifecycle/revoke-managed-credentials! (:ds db) keycloak-admin tenant-id manager-user-id nil)
    (catch Exception e
      (log/error "Failed revoking managed api credentials"
                 (assoc (diagnostics/failure-details e)
                        :tenant_id (str tenant-id)
                        :manager_user_id (str manager-user-id)))
      (throw (ex-info "Managed credential revocation failed"
                      {:type :samuraibff.api-credentials/revocation-failed}))))
  nil)

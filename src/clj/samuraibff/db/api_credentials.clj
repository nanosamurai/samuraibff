(ns samuraibff.db.api-credentials
  "DB access for API credential inventory.

  This namespace stores metadata about machine-to-machine credentials.
  Actual secrets are managed in Keycloak and are never stored in Postgres.

  Table:
  - api_credentials

  Each credential has a human manager (`manager_user_id`, NULL for legacy
  Admin-managed rows), an optional bound service principal
  (`service_user_id`, NULL when not provisioned) and an
  `identity_sync_state` (`synced` | `disable_pending`).

  `kind` is `personal` (requests act as the manager, who owns it) or
  `organization` (requests act as the service principal). `read_scope`
  (`credential` | `owner`) limits personal reads; `default_visibility`
  (`private` | `tenant`) is the audience of new organization sessions.

  Public API:
  - `insert-credential!`
  - `find-credential`, `lock-credential`, `find-by-service-user`
  - `list-credentials`, `lock-active-managed-credentials`
  - `update-settings!`, `clear-organization-manager!`
  - `revoke-credential!`, `set-identity-sync-state!`

  All functions are tenant-scoped (tenant-id is always required) and accept
  any next.jdbc connectable."
  (:require
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.util UUID)))

(def ^:private row-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(def ^:private credential-columns
  [:id :tenant_id :name :keycloak_client_id :created_by_sub :created_at
   :last_used_at :revoked_at :manager_user_id :service_user_id :identity_sync_state
   :kind :read_scope :default_visibility])

(defn insert-credential!
  "Insert api_credentials row.

  Inputs:
  - connectable
  - {:keys [id tenant-id name keycloak-client-id created-by-sub
            manager-user-id service-user-id kind read-scope default-visibility]}
    (manager and service ids are UUIDs or nil; kind defaults to
    \"organization\", read-scope to \"credential\", default-visibility to
    \"private\")

  Returns:
  - {:id uuid :keycloak-client-id string}"
  [connectable {:keys [id tenant-id name keycloak-client-id created-by-sub
                       manager-user-id service-user-id kind read-scope default-visibility]
                :or {kind "organization" read-scope "credential" default-visibility "private"}}]
  (when-not (and connectable (instance? UUID id) (instance? UUID tenant-id)
                 (seq (str name)) (seq (str keycloak-client-id)))
    (throw (ex-info "insert-credential! missing required params"
                    {:id id
                     :tenant-id tenant-id})))
  (let [values (cond-> {:id id
                        :tenant_id tenant-id
                        :name (str name)
                        :keycloak_client_id (str keycloak-client-id)
                        :kind (str kind)
                        :read_scope (str read-scope)
                        :default_visibility (str default-visibility)}
                 (some? created-by-sub) (assoc :created_by_sub (str created-by-sub))
                 (some? manager-user-id) (assoc :manager_user_id manager-user-id)
                 (some? service-user-id) (assoc :service_user_id service-user-id))]
    (jdbc/execute-one! connectable (sql/format (-> (h/insert-into :api_credentials)
                                                   (h/values [values]))))
    {:id id :keycloak-client-id (str keycloak-client-id)}))

(defn list-credentials
  "List credentials (including revoked ones) for a tenant, newest first.

  Inputs:
  - connectable
  - tenant-id: UUID
  - opts: {:manager-id UUID?}; when set, only credentials managed by that user

  Returns:
  - vector of maps with keys:
      :id :name :keycloak_client_id :created_by_sub :created_at :last_used_at
      :revoked_at :manager_user_id :service_user_id :identity_sync_state
      :kind :read_scope :default_visibility :manager_name :manager_email"
  [connectable ^UUID tenant-id {:keys [manager-id]}]
  (when-not (and connectable (instance? UUID tenant-id))
    (throw (ex-info "list-credentials missing required params" {:tenant-id tenant-id})))
  (jdbc/execute! connectable
                 (sql/format {:select [:c.id :c.name :c.keycloak_client_id :c.created_by_sub
                                       :c.created_at :c.last_used_at :c.revoked_at
                                       :c.manager_user_id :c.service_user_id :c.identity_sync_state
                                       :c.kind :c.read_scope :c.default_visibility
                                       [:m.name :manager_name] [:m.email :manager_email]]
                              :from [[:api_credentials :c]]
                              :left-join [[:app_users :m] [:and [:= :m.id :c.manager_user_id]
                                                           [:= :m.tenant_id :c.tenant_id]]]
                              :where (cond-> [:and [:= :c.tenant_id tenant-id]]
                                       manager-id (conj [:= :c.manager_user_id manager-id]))
                              :order-by [[:c.created_at :desc]]})
                 row-opts))

(defn find-credential
  "Find a credential row by id within a tenant.

  Inputs:
  - connectable
  - tenant-id: UUID
  - credential-id: UUID

  Returns:
  - row map (unqualified lower keys) or nil"
  [connectable ^UUID tenant-id ^UUID credential-id]
  (when-not (and connectable (instance? UUID tenant-id) (instance? UUID credential-id))
    (throw (ex-info "find-credential missing required params"
                    {:tenant-id tenant-id :credential-id credential-id})))
  (jdbc/execute-one! connectable
                     (sql/format {:select credential-columns
                                  :from [:api_credentials]
                                  :where [:and [:= :tenant_id tenant-id] [:= :id credential-id]]})
                     row-opts))

(defn lock-credential
  "Like `find-credential`, but locks the row until the transaction ends."
  [tx ^UUID tenant-id ^UUID credential-id]
  (jdbc/execute-one! tx
                     (sql/format {:select credential-columns
                                  :from [:api_credentials]
                                  :where [:and [:= :tenant_id tenant-id] [:= :id credential-id]]
                                  :for [:update]})
                     row-opts))

(defn find-by-service-user
  "Find the credential bound to a service principal, or nil.

  Used by principal resolution on every machine request.
  Inputs: connectable, tenant UUID, service principal UUID."
  [connectable ^UUID tenant-id ^UUID service-user-id]
  (jdbc/execute-one! connectable
                     (sql/format {:select credential-columns
                                  :from [:api_credentials]
                                  :where [:and [:= :tenant_id tenant-id]
                                          [:= :service_user_id service-user-id]]})
                     row-opts))

(defn update-settings!
  "Update the manager and/or default audience of one organization credential.

  Inputs: connectable, tenant UUID, credential UUID,
  {:manager-user-id UUID? :default-visibility string?} (absent keys stay).
  Returns: the updated row, or nil when no organization credential matched."
  [connectable ^UUID tenant-id ^UUID credential-id changes]
  (let [set-map (cond-> {}
                  (contains? changes :manager-user-id) (assoc :manager_user_id (:manager-user-id changes))
                  (contains? changes :default-visibility) (assoc :default_visibility (:default-visibility changes)))]
    (when (seq set-map)
      (jdbc/execute-one! connectable
                         (sql/format {:update :api_credentials
                                      :set set-map
                                      :where [:and [:= :tenant_id tenant-id]
                                              [:= :id credential-id]
                                              [:= :kind "organization"]]
                                      :returning credential-columns})
                         row-opts))))

(defn clear-organization-manager!
  "Hand every organization credential managed by one user back to Admins.

  Inputs: connectable, tenant UUID, manager UUID. Returns the number of rows."
  [connectable ^UUID tenant-id ^UUID manager-id]
  (let [res (jdbc/execute-one! connectable
                               (sql/format {:update :api_credentials
                                            :set {:manager_user_id nil}
                                            :where [:and [:= :tenant_id tenant-id]
                                                    [:= :manager_user_id manager-id]
                                                    [:= :kind "organization"]]}))]
    (long (or (:next.jdbc/update-count res) 0))))

(defn lock-active-managed-credentials
  "Lock and return every non-revoked personal credential owned by one user.

  Organization credentials are not returned: they outlive their manager.
  Inputs: transaction connectable, tenant UUID, manager UUID.
  Returns: vector of credential rows."
  [tx ^UUID tenant-id ^UUID manager-id]
  (jdbc/execute! tx
                 (sql/format {:select credential-columns
                              :from [:api_credentials]
                              :where [:and [:= :tenant_id tenant-id]
                                      [:= :manager_user_id manager-id]
                                      [:= :kind "personal"]
                                      [:= :revoked_at nil]]
                              :for [:update]})
                 row-opts))

(defn revoke-credential!
  "Mark a credential revoked (DB only).

  The Keycloak client must be disabled separately.

  Inputs:
  - connectable
  - tenant-id: UUID
  - credential-id: UUID

  Returns:
  - {:updated? boolean} (false when already revoked or absent)"
  [connectable ^UUID tenant-id ^UUID credential-id]
  (when-not (and connectable (instance? UUID tenant-id) (instance? UUID credential-id))
    (throw (ex-info "revoke-credential! missing required params"
                    {:tenant-id tenant-id :credential-id credential-id})))
  (let [res (jdbc/execute-one! connectable
                               (sql/format (-> (h/update :api_credentials)
                                               (h/set {:revoked_at [:now]})
                                               (h/where [:= :tenant_id tenant-id]
                                                        [:= :id credential-id]
                                                        [:is :revoked_at nil]))))]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn set-identity-sync-state!
  "Set `identity_sync_state` of one credential.

  Inputs: connectable, tenant UUID, credential UUID, \"synced\" | \"disable_pending\".
  Returns: nil."
  [connectable ^UUID tenant-id ^UUID credential-id state]
  (jdbc/execute-one! connectable
                     (sql/format {:update :api_credentials
                                  :set {:identity_sync_state state}
                                  :where [:and [:= :tenant_id tenant-id] [:= :id credential-id]]}))
  nil)

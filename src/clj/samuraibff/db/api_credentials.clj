(ns samuraibff.db.api-credentials
  "DB access for API credential inventory.

  This namespace stores metadata about machine-to-machine credentials.
  Actual secrets are managed in Keycloak and are never stored in Postgres.

  Table:
  - api_credentials

  Public API:
  - `insert-credential!`
  - `find-credential`
  - `list-credentials`
  - `revoke-credential!`

  All functions are tenant-scoped (tenant-id is always required)."
  (:require
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.util UUID)
    (javax.sql DataSource)))

(defn insert-credential!
  "Insert api_credentials row.

  Inputs:
  - ds: javax.sql.DataSource
  - {:keys [id tenant-id name keycloak-client-id created-by-sub]}

  Returns:
  - {:id uuid :keycloak-client-id string}"
  [^DataSource ds {:keys [id tenant-id name keycloak-client-id created-by-sub]}]
  (when-not (and ds (instance? UUID id) (instance? UUID tenant-id)
                 (seq (str name)) (seq (str keycloak-client-id)))
    (throw (ex-info "insert-credential! missing required params"
                    {:id id
                     :tenant-id tenant-id
                     :name name
                     :keycloak-client-id keycloak-client-id})))
  (let [values (cond-> {:id id
                        :tenant_id tenant-id
                        :name (str name)
                        :keycloak_client_id (str keycloak-client-id)}
                 (some? created-by-sub) (assoc :created_by_sub (str created-by-sub)))
        q (-> (h/insert-into :api_credentials)
              (h/values [values]))
        sqlvec (sql/format q)]
    (jdbc/execute-one! ds sqlvec)
    {:id id :keycloak-client-id (str keycloak-client-id)}))

(defn list-credentials
  "List non-revoked credentials for a tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID

  Returns:
  - vector of maps with keys:
      :id :name :keycloak_client_id :created_by_sub :created_at :last_used_at"
  [^DataSource ds ^UUID tenant-id]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "list-credentials missing required params" {:tenant-id tenant-id})))
  (let [q (-> (h/select :id :name :keycloak_client_id :created_by_sub :created_at :last_used_at :revoked_at)
              (h/from :api_credentials)
              (h/where [:= :tenant_id tenant-id])
              (h/order-by [:created_at :desc]))
        sqlvec (sql/format q)]
    (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps})))

(defn find-credential
  "Find a credential row by id within a tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - credential-id: UUID

  Returns:
  - row map (unqualified lower keys) or nil" 
  [^DataSource ds ^UUID tenant-id ^UUID credential-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID credential-id))
    (throw (ex-info "find-credential missing required params"
                    {:tenant-id tenant-id :credential-id credential-id})))
  (let [q (-> (h/select :id :tenant_id :name :keycloak_client_id :created_by_sub :created_at :last_used_at :revoked_at)
              (h/from :api_credentials)
              (h/where [:= :tenant_id tenant-id]
                       [:= :id credential-id])
              (h/limit 1))
        sqlvec (sql/format q)]
    (jdbc/execute-one! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps})))

(defn revoke-credential!
  "Mark a credential revoked (DB only).

  The Keycloak client must be disabled/deleted separately (handled by service).

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - credential-id: UUID

  Returns:
  - {:updated? boolean}"
  [^DataSource ds ^UUID tenant-id ^UUID credential-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID credential-id))
    (throw (ex-info "revoke-credential! missing required params"
                    {:tenant-id tenant-id :credential-id credential-id})))
  (let [q (-> (h/update :api_credentials)
              (h/set {:revoked_at [:raw "now()"]})
              (h/where [:= :tenant_id tenant-id]
                       [:= :id credential-id]
                       [:is :revoked_at nil]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

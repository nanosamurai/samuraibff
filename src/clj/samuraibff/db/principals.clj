(ns samuraibff.db.principals
  "DB access for application principals (`app_users`).

  A principal is one verified identity (issuer + subject) bound to exactly one
  tenant. Keycloak owns authentication; this table owns membership, the fixed
  application role and the lifecycle status.

  Row shape returned by every read (unqualified, lower-case keys):

  {:id uuid :tenant_id uuid :external_id string :issuer string?
   :principal_type \"human\"|\"service\" :role \"member\"|\"builder\"|\"admin\"|nil
   :status \"pending\"|\"active\"|\"suspended\" :client_id string?
   :name string? :email string? :setup_state string? :setup_email_sent_at inst?
   :identity_sync_state string :last_seen_at inst? :created_at inst :updated_at inst}

  Public API:
  - `find-by-subject`, `find-by-id`, `find-guest`
  - `bind-issuer!`, `activate-pending!`, `touch-last-seen!`
  - `list-people`, `search-directory`, `active-human-ids`
  - `insert-principal!`, `update-principal!`, `lock-tenant-membership!`
  - `count-active-admins`

  Every query is built with HoneySQL and executed with bound parameters."
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.util UUID)))

(def guest-issuer
  "Issuer marker of the local guest principal. It is not an OIDC issuer, so a
  verified token can never resolve to the guest row."
  "urn:nanosamurai:guest")

(def ^:private principal-columns
  [:id :tenant_id :external_id :issuer :principal_type :role :status :client_id
   :name :email :setup_state :setup_email_sent_at :identity_sync_state
   :last_seen_at :created_at :updated_at])

(def ^:private row-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- execute-one!
  [connectable query]
  (jdbc/execute-one! connectable (sql/format query) row-opts))

(defn- execute!
  [connectable query]
  (jdbc/execute! connectable (sql/format query) row-opts))

(defn find-by-subject
  "Find the principal for a verified issuer and subject.

  Rows created before issuer binding (issuer NULL) still match by their globally
  unique subject. Callers must verify the tenant binding of the returned row.

  Inputs: connectable, issuer string, subject string.
  Returns: principal row map or nil."
  [connectable issuer subject]
  (when (and (string? issuer) (not (str/blank? issuer))
             (string? subject) (not (str/blank? subject)))
    (execute-one! connectable
                  (-> (apply h/select principal-columns)
                      (h/from :app_users)
                      (h/where [:= :external_id subject]
                               [:or [:= :issuer issuer] [:= :issuer nil]])
                      (h/limit 1)))))

(defn find-guest
  "Find the guest principal used only while authentication is disabled.

  Inputs: connectable, guest tenant UUID.
  Returns: principal row map or nil."
  [connectable ^UUID tenant-id]
  (execute-one! connectable
                (-> (apply h/select principal-columns)
                    (h/from :app_users)
                    (h/where [:= :tenant_id tenant-id]
                             [:= :external_id "guest"]
                             [:= :issuer guest-issuer]))))

(defn find-by-id
  "Find a principal by local id within a tenant.

  Inputs: connectable, tenant UUID, principal UUID.
  Returns: principal row map or nil."
  [connectable ^UUID tenant-id ^UUID principal-id]
  (execute-one! connectable
                (-> (apply h/select principal-columns)
                    (h/from :app_users)
                    (h/where [:= :tenant_id tenant-id] [:= :id principal-id]))))

(defn bind-issuer!
  "Record the verified issuer on a legacy row that has none.

  Never changes an issuer that is already set. Returns the update count."
  [connectable ^UUID principal-id issuer]
  (:next.jdbc/update-count
   (jdbc/execute-one! connectable
                      (sql/format (-> (h/update :app_users)
                                      (h/set {:issuer issuer :updated_at [:now]})
                                      (h/where [:= :id principal-id] [:= :issuer nil]))))))

(defn activate-pending!
  "Activate a pending human principal after its first verified login.

  Only a `pending` row changes, so a suspended principal can never reactivate
  through authentication. Returns the updated row or nil."
  [connectable ^UUID principal-id]
  (execute-one! connectable
                (-> (h/update :app_users)
                    (h/set {:status "active" :setup_state "complete" :updated_at [:now]})
                    (h/where [:= :id principal-id] [:= :status "pending"])
                    (merge {:returning principal-columns}))))

(defn touch-last-seen!
  "Refresh last_seen_at at most once per five minutes for a principal.

  Bounded write amplification: busy principals cause one write per interval.
  Returns nil."
  [connectable ^UUID principal-id]
  (jdbc/execute-one! connectable
                     (sql/format (-> (h/update :app_users)
                                     (h/set {:last_seen_at [:now]})
                                     (h/where [:= :id principal-id]
                                              [:or [:= :last_seen_at nil]
                                               [:< :last_seen_at [:- [:now] [:raw "interval '5 minutes'"]]]]))))
  nil)

(defn- search-condition
  "Return a case-insensitive name/email match condition, or nil for blank q."
  [q]
  (when-let [q (some-> q str str/trim not-empty)]
    (let [pattern (str "%" (str/replace q #"[\\%_]" "\\\\$0") "%")]
      [:or [:ilike :name pattern] [:ilike :email pattern]])))

(defn list-people
  "List human principals of a tenant for the People page.

  Inputs: connectable, tenant UUID, {:limit int :offset int :q string?}.
  Returns: {:items [principal rows] :total long}. Ordered by name, then id."
  [connectable ^UUID tenant-id {:keys [limit offset q] :or {limit 50 offset 0}}]
  (let [where (cond-> [:and [:= :tenant_id tenant-id] [:= :principal_type "human"]]
                (search-condition q) (conj (search-condition q)))
        total (:total (execute-one! connectable
                                    {:select [[[:count :*] :total]]
                                     :from [:app_users]
                                     :where where}))
        items (execute! connectable
                        {:select principal-columns
                         :from [:app_users]
                         :where where
                         :order-by [[[:lower [:coalesce :name :email :external_id]] :asc] [:id :asc]]
                         :limit (long limit)
                         :offset (long offset)})]
    {:items (vec items) :total (long (or total 0))}))

(defn search-directory
  "Search active human members of a tenant for sharing.

  Inputs: connectable, tenant UUID, {:q string? :limit int :exclude-id uuid?}.
  Returns: vector of {:id :name :email} maps, at most limit rows."
  [connectable ^UUID tenant-id {:keys [q limit exclude-id] :or {limit 20}}]
  (vec (execute! connectable
                 {:select [:id :name :email]
                  :from [:app_users]
                  :where (cond-> [:and [:= :tenant_id tenant-id]
                                  [:= :principal_type "human"]
                                  [:= :status "active"]]
                           exclude-id (conj [:<> :id exclude-id])
                           (search-condition q) (conj (search-condition q)))
                  :order-by [[[:lower [:coalesce :name :email]] :asc] [:id :asc]]
                  :limit (long limit)})))

(defn active-human-ids
  "Return the subset of ids that are active humans in the tenant.

  Inputs: connectable, tenant UUID, collection of UUIDs.
  Returns: set of UUIDs."
  [connectable ^UUID tenant-id ids]
  (if (empty? ids)
    #{}
    (->> (execute! connectable
                   {:select [:id]
                    :from [:app_users]
                    :where [:and [:= :tenant_id tenant-id]
                            [:= :principal_type "human"]
                            [:= :status "active"]
                            [:in :id (vec ids)]]})
         (map :id)
         set)))

(defn insert-principal!
  "Insert a principal row.

  Inputs: connectable and a map with :id :tenant-id :external-id :issuer
  :principal-type :role :status and optional :client-id :name :email
  :setup-state :provisioning-operation-id.
  Returns: the inserted row."
  [connectable {:keys [id tenant-id external-id issuer principal-type role status
                       client-id name email setup-state provisioning-operation-id]}]
  (execute-one! connectable
                (-> (h/insert-into :app_users)
                    (h/values [(cond-> {:id id
                                        :tenant_id tenant-id
                                        :external_id external-id
                                        :issuer issuer
                                        :principal_type principal-type
                                        :role role
                                        :status status}
                                 client-id (assoc :client_id client-id)
                                 name (assoc :name name)
                                 email (assoc :email email)
                                 setup-state (assoc :setup_state setup-state)
                                 provisioning-operation-id (assoc :provisioning_operation_id provisioning-operation-id))])
                    (merge {:returning principal-columns}))))

(defn update-principal!
  "Update selected mutable columns of a tenant principal.

  Inputs: connectable, tenant UUID, principal UUID, map of column keyword ->
  value limited to :role :status :name :email :setup_state :setup_email_sent_at
  :identity_sync_state.
  Returns: updated row or nil."
  [connectable ^UUID tenant-id ^UUID principal-id changes]
  (let [allowed #{:role :status :name :email :setup_state :setup_email_sent_at :identity_sync_state}
        changes (select-keys changes allowed)]
    (when (seq changes)
      (execute-one! connectable
                    (-> (h/update :app_users)
                        (h/set (assoc changes :updated_at [:now]))
                        (h/where [:= :tenant_id tenant-id] [:= :id principal-id])
                        (merge {:returning principal-columns}))))))

(defn lock-tenant-membership!
  "Serialize membership mutations of one tenant inside the current transaction.

  Uses a transaction-scoped advisory lock keyed by the tenant id, so concurrent
  role changes and suspensions on any BFF replica cannot both pass the
  last-active-Admin check. Must be called with a transaction connectable."
  [tx ^UUID tenant-id]
  (jdbc/execute-one! tx (sql/format {:select [[[:pg_advisory_xact_lock
                                                 [:hashtextextended (str "membership:" tenant-id) 0]]]]}))
  nil)

(defn count-active-admins
  "Count active human Admins of a tenant. Returns a long."
  [connectable ^UUID tenant-id]
  (long (or (:total (execute-one! connectable
                                  {:select [[[:count :*] :total]]
                                   :from [:app_users]
                                   :where [:and [:= :tenant_id tenant-id]
                                           [:= :principal_type "human"]
                                           [:= :role "admin"]
                                           [:= :status "active"]]}))
            0)))

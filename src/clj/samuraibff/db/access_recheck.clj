(ns samuraibff.db.access-recheck
  "Batched reads for re-checking open WebSockets and playback streams.

  One re-check cycle reads all principals, all API credentials and all session
  access rows it needs in a few queries, independent of how many deliveries are
  open. Every query takes a statement timeout so a slow database cannot hold the
  re-check worker indefinitely.

  Public API:
  - `find-principals`, `find-credentials`, `accessible-sessions`"
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.db.session-access :as session-access]))

(def ^:private chunk-size
  "Upper bound of ids per IN list and of principals per UNION ALL query."
  200)

(defn- query-opts
  [timeout-s]
  (cond-> {:builder-fn rs/as-unqualified-lower-maps}
    (pos-int? timeout-s) (assoc :timeout timeout-s)))

(defn- execute-chunked!
  [connectable ids timeout-s ->query]
  (into []
        (mapcat (fn [chunk]
                  (jdbc/execute! connectable (sql/format (->query (vec chunk))) (query-opts timeout-s))))
        (partition-all chunk-size (distinct ids))))

(defn find-principals
  "Return `app_users` rows for the given principal ids.

  Inputs: connectable, collection of UUIDs, statement timeout in seconds."
  [connectable ids timeout-s]
  (execute-chunked! connectable ids timeout-s
                    (fn [chunk]
                      {:select [:id :tenant_id :issuer :principal_type :role :status :client_id
                                :name :email]
                       :from [:app_users]
                       :where [:in :id chunk]})))

(defn find-credentials
  "Return `api_credentials` rows for the given credential ids.

  Inputs: connectable, collection of UUIDs, statement timeout in seconds."
  [connectable ids timeout-s]
  (execute-chunked! connectable ids timeout-s
                    (fn [chunk]
                      {:select [:id :tenant_id :name :kind :read_scope :default_visibility
                                :manager_user_id :service_user_id :revoked_at]
                       :from [:api_credentials]
                       :where [:in :id chunk]})))

(defn- group-query
  [index {:keys [principal session-ids]}]
  {:select [[[:inline index] :grp] :s.id :s.tenant_id :s.user_id :s.created_via_credential_id
            [(session-access/key-manager-expr principal) :key_manager]]
   :from [[:sessions :s]]
   :where [:and [:in :s.id (vec session-ids)]
           (session-access/readable-condition principal)]})

(defn accessible-sessions
  "Return the sessions each principal may still read.

  Inputs: connectable, vector of {:principal map :session-ids [UUID]} groups,
  statement timeout in seconds. Each group applies the same read condition as
  `samuraibff.db.session-access/find-accessible-session`. Groups are combined
  with UNION ALL, so a cycle costs one query per `chunk-size` principals.

  Returns: a vector in group order; each element maps a session id string to
  its readable row (:id :tenant_id :user_id :created_via_credential_id
  :key_manager). Sessions the principal may not read are absent."
  [connectable groups timeout-s]
  (let [groups (vec groups)
        result (vec (repeat (count groups) {}))]
    (reduce
     (fn [acc chunk]
       (let [queries (mapv (fn [[i g]] (group-query i g)) chunk)
             query (if (= 1 (count queries)) (first queries) {:union-all queries})
             rows (jdbc/execute! connectable (sql/format query) (query-opts timeout-s))]
         (reduce (fn [acc row]
                   (assoc-in acc [(int (:grp row)) (str (:id row))] (dissoc row :grp)))
                 acc rows)))
     result
     (partition-all chunk-size (filter (fn [[_ g]] (seq (:session-ids g)))
                                       (map-indexed vector groups))))))

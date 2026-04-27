(ns samuraibff.db.webhook-delivery-outcomes
  "DB access for webhook delivery outcomes (audit log).

  Data source:
  - `webhook_delivery_outcomes` table (written by samuraipersistor).

  The UI needs a *dispatch-oriented* view of this table: each webhook dispatch
  (dispatch_id) may have multiple attempts (attempt_no). We typically want the
  latest attempt per dispatch, plus the total number of attempts.

  Public API:
  - `list-latest-outcomes-for-session`"
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [org.corfield.logging4j2 :as log])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(defn list-latest-outcomes-for-session
  "Return latest webhook delivery outcome per dispatch_id for a session.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: UUID (authenticated tenant)
  - session-id: UUID
  - opts: map of optional keys:
      - :limit int (default 50)

  Output shape:
  - vector of maps with unqualified lower-case keys:
      :id :created_at :tenant_id :session_id :webhook_id :dispatch_id
      :event_id :event_type :attempt_no :status :http_status :error_code
      :error_detail :latency_ms
      :attempts_count

  Notes:
  - Tenant isolation: always constrained by tenant_id.
  - Session_id is nullable in the table; this query only returns rows where
    session_id matches the given session-id.
  - Latest is determined by created_at DESC, attempt_no DESC (best-effort)."
  [^DataSource ds ^UUID tenant-id ^UUID session-id {:keys [limit]
                                                   :or {limit 50}}]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "list-latest-outcomes-for-session missing required params"
                    {:tenant-id tenant-id
                     :session-id session-id})))
  (let [limit (max 1 (long (or limit 50)))
        sqlstr
        (str
         "WITH per_dispatch AS (\n"
         "  SELECT\n"
         "    *,\n"
         "    MAX(attempt_no) OVER (PARTITION BY dispatch_id) AS attempts_count,\n"
         "    ROW_NUMBER() OVER (PARTITION BY dispatch_id ORDER BY created_at DESC, attempt_no DESC) AS rn\n"
         "  FROM webhook_delivery_outcomes\n"
         "  WHERE tenant_id = ? AND session_id = ?\n"
         ")\n"
         "SELECT\n"
         "  id, created_at, tenant_id, session_id, webhook_id, dispatch_id,\n"
         "  event_id, event_type, attempt_no, status, http_status, error_code, error_detail, latency_ms,\n"
         "  attempts_count\n"
         "FROM per_dispatch\n"
         "WHERE rn = 1\n"
         "ORDER BY created_at DESC\n"
         "LIMIT ?")
        sqlvec [sqlstr tenant-id session-id limit]]
    (try
      (vec (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))
      (catch Exception e
        (log/error e "DB query failed (list-latest-outcomes-for-session)"
                   {:tenant-id (str tenant-id)
                    :session-id (str session-id)})
        (throw e)))))

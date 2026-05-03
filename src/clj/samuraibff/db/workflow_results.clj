(ns samuraibff.db.workflow-results
  "DB access for workflow execution results.

  Data source:
  - `workflow_results_latest` table (written by samuraipersistor from workflow-runner Kafka output).

  We primarily use the *latest* view (one row per (session_id, workflow_id)) for UI.

  Public API:
  - `list-latest-results-for-session`

  Security:
  - Every query is scoped by tenant-id.
  - Callers must supply the authenticated tenant-id."
  (:require
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [org.corfield.logging4j2 :as log])
  (:import
   (java.util UUID)
   (javax.sql DataSource)
   (org.postgresql.util PGobject)))

(def ^:private json-mapper
  "JSON mapper used for decoding workflow result render_json payloads."
  (json/object-mapper {:decode-key-fn keyword}))

(defn- jsonb->clj
  "Decode a Postgres json/jsonb column into a Clojure value.

  Inputs:
  - v: nil | map/vector | string | PGobject

  Returns:
  - decoded Clojure value (usually map) or nil.

  Notes:
  - We keywordize keys for internal use."
  [v]
  (cond
    (nil? v) nil
    (or (map? v) (vector? v) (sequential? v)) v
    (string? v) (json/read-value v json-mapper)
    (instance? PGobject v) (some-> v (.getValue) (json/read-value json-mapper))
    :else v))

(defn list-latest-results-for-session
  "List latest workflow results for a session.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: UUID
  - session-id: UUID
  - opts: map of optional keys:
      - :limit int (default 50)

  Returns:
  - vector of maps with unqualified lower-case keys.

  Output keys include:
  - :created_at :workflow_run_id :workflow_id
  - :status
  - :render_markdown
  - :workflow_name (nullable; from join to `workflows`)
  - :trigger_type
  - :provider_type :provider_model_id
  - :error_code :error_detail
  - :render_json (decoded map when present)

  Notes:
  - This is a best-effort read model. We order by created_at DESC."
  [^DataSource ds ^UUID tenant-id ^UUID session-id {:keys [limit]
                                                    :or {limit 50}}]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "list-latest-results-for-session missing required params"
                    {:tenant-id tenant-id
                     :session-id session-id})))
  (let [limit (max 1 (long (or limit 50)))
        sqlstr
        (str
         "SELECT\n"
         "  r.session_id, r.workflow_id, r.created_at, r.workflow_run_id, r.tenant_id,\n"
         "  r.trigger_type, r.trigger_source_event_id, r.status,\n"
         "  r.render_markdown, r.render_json,\n"
         "  r.provider_type, r.provider_model_id,\n"
         "  r.usage_input_tokens, r.usage_output_tokens,\n"
         "  r.stream_source_uri, r.stream_source_node_id,\n"
         "  r.error_code, r.error_detail,\n"
         "  w.name AS workflow_name\n"
         "FROM workflow_results_latest r\n"
         "LEFT JOIN workflows w\n"
         "  ON w.tenant_id = r.tenant_id\n"
         " AND w.id = r.workflow_id\n"
         "WHERE r.tenant_id = ? AND r.session_id = ?\n"
         "ORDER BY r.created_at DESC\n"
         "LIMIT ?")
        sqlvec [sqlstr tenant-id session-id limit]]
    (try
      (->> (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps})
           (mapv (fn [row]
                   (if (contains? row :render_json)
                     (update row :render_json jsonb->clj)
                     row))))
      (catch Exception e
        (log/error e "DB query failed (list-latest-results-for-session)"
                   {:tenant-id (str tenant-id)
                    :session-id (str session-id)})
        (throw e)))))

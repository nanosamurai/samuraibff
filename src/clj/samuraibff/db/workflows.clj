(ns samuraibff.db.workflows
  "DB access for tenant-scoped workflows.

  Tables:
  - workflows
  - workflow_defaults

  Public API:
  - list-workflows
  - find-workflow
  - insert-workflow!
  - update-workflow!
  - delete-workflow!
  - get-defaults
  - set-defaults!

  Notes:
  - All functions are tenant-scoped (require tenant-id).
  - `provider_params` is stored as jsonb (PGobject) and is returned as a plain map.
  "
  (:require
   [cheshire.core :as cheshire]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.util UUID)
   (javax.sql DataSource)
   (org.postgresql.util PGobject)))

(defn- parse-jsonb
  "Parse a Postgres jsonb value returned by next.jdbc into a Clojure value.

  Inputs:
  - x: nil | map | string | PGobject

  Returns:
  - Clojure value (usually map) or nil.

  Notes:
  - We parse with keyword keys for internal use (keyword? = true).
  - If parsing fails, returns nil."
  [x]
  (cond
    (nil? x) nil
    (map? x) x
    (instance? PGobject x)
    (let [v (.getValue ^PGobject x)]
      (when (seq (str v))
        (try
          (cheshire/parse-string (str v) true)
          (catch Exception _ nil))))
    (string? x)
    (when (seq x)
      (try
        (cheshire/parse-string x true)
        (catch Exception _ nil)))
    :else
    (try
      (let [s (str x)]
        (when (seq s)
          (cheshire/parse-string s true)))
      (catch Exception _ nil))))

(defn- ->jsonb-pgobject
  "Convert a Clojure value into a Postgres jsonb PGobject.

  Inputs:
  - x: any JSON-serializable value

  Returns:
  - PGobject with type jsonb"
  ^PGobject
  [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (cheshire/generate-string x))))

(defn- normalize-workflow-row
  "Normalize a workflow row returned from the DB.

  Returns:
  - row with :provider_params parsed into a Clojure map (or nil)."
  [row]
  (cond-> row
    (contains? row :provider_params)
    (update :provider_params parse-jsonb)))

(defn list-workflows
  "List workflows for a tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID

  Returns:
  - vector of workflow maps (unqualified lower keys)."
  [^DataSource ds ^UUID tenant-id]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "list-workflows missing required params" {:tenant-id tenant-id})))
  (->> (jdbc/execute!
        ds
        ["SELECT id, tenant_id, name, enabled,
                 trigger_type,
                 prompt_text,
                 provider_type, provider_model_id, provider_params,
                 incremental_enabled, incremental_min_interval_sec,
                 created_at, updated_at
            FROM workflows
           WHERE tenant_id = ?
           ORDER BY created_at DESC"
         tenant-id]
        {:builder-fn rs/as-unqualified-lower-maps})
       (mapv normalize-workflow-row)))

(defn find-workflow
  "Find a single workflow by id for a tenant.

  Returns:
  - workflow row map or nil."
  [^DataSource ds ^UUID tenant-id ^UUID workflow-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID workflow-id))
    (throw (ex-info "find-workflow missing required params" {:tenant-id tenant-id :workflow-id workflow-id})))
  (some-> (jdbc/execute-one!
           ds
           ["SELECT id, tenant_id, name, enabled,
                    trigger_type,
                    prompt_text,
                    provider_type, provider_model_id, provider_params,
                    incremental_enabled, incremental_min_interval_sec,
                    created_at, updated_at
               FROM workflows
              WHERE tenant_id = ? AND id = ?"
            tenant-id workflow-id]
           {:builder-fn rs/as-unqualified-lower-maps})
          normalize-workflow-row))

(defn insert-workflow!
  "Insert a workflow row.

  Inputs:
  - ds: DataSource
  - workflow map with keys:
      :id :tenant-id :name :enabled
      :trigger-type :prompt-text
      :provider-type :provider-model-id
      and optional:
      :provider-params (map)
      :incremental-enabled :incremental-min-interval-sec

  Returns:
  - {:id uuid}"
  [^DataSource ds {:keys [id tenant-id name enabled trigger-type prompt-text
                          provider-type provider-model-id]
                   :as wf}]
  (when-not (and ds (instance? UUID id) (instance? UUID tenant-id)
                 (seq (str name))
                 (seq (str trigger-type))
                 (seq (str prompt-text))
                 (seq (str provider-type))
                 (seq (str provider-model-id)))
    (throw (ex-info "insert-workflow! missing required params" {:workflow wf})))
  (let [provider-params (:provider_params wf)
        values (cond->
                 {:id id
                  :tenant_id tenant-id
                  :name (str name)
                  :enabled (boolean enabled)
                  :trigger_type (str trigger-type)
                  :prompt_text (str prompt-text)
                  :provider_type (str provider-type)
                  :provider_model_id (str provider-model-id)
                  :incremental_enabled (boolean (or (:incremental_enabled wf) false))
                  :incremental_min_interval_sec (:incremental_min_interval_sec wf)}
                 (some? provider-params) (assoc :provider_params (->jsonb-pgobject provider-params)))
        q (-> (h/insert-into :workflows)
              (h/values [values]))
        sqlvec (sql/format q)]
    (jdbc/execute-one! ds sqlvec)
    {:id id}))

(defn update-workflow!
  "Update a workflow row.

  Inputs:
  - ds: DataSource
  - tenant-id UUID
  - workflow-id UUID
  - patch map with allowed keys (snake_case DB keys):
      :name :enabled
      :trigger_type :prompt_text
      :provider_type :provider_model_id :provider_params
      :incremental_enabled :incremental_min_interval_sec

  Returns:
  - {:updated? boolean}"
  [^DataSource ds ^UUID tenant-id ^UUID workflow-id patch]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID workflow-id))
    (throw (ex-info "update-workflow! missing required params" {:tenant-id tenant-id :workflow-id workflow-id})))
  (let [allowed0 (select-keys patch
                              [:name :enabled
                               :trigger_type :prompt_text
                               :provider_type :provider_model_id :provider_params
                               :incremental_enabled :incremental_min_interval_sec])
        allowed (cond-> allowed0
                  (contains? allowed0 :provider_params)
                  (assoc :provider_params (->jsonb-pgobject (or (:provider_params allowed0) {}))))
        ;; keep updated_at fresh on any patch
        allowed (assoc allowed :updated_at [:raw "now()"])
        q (-> (h/update :workflows)
              (h/set allowed)
              (h/where [:= :tenant_id tenant-id]
                       [:= :id workflow-id]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn delete-workflow!
  "Delete a workflow by id (tenant-scoped).

  Returns:
  - {:deleted? boolean}"
  [^DataSource ds ^UUID tenant-id ^UUID workflow-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID workflow-id))
    (throw (ex-info "delete-workflow! missing required params" {:tenant-id tenant-id :workflow-id workflow-id})))
  (let [res (jdbc/execute-one!
             ds
             ["DELETE FROM workflows WHERE tenant_id=? AND id=?" tenant-id workflow-id])]
    {:deleted? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn get-defaults
  "Get tenant workflow defaults.

  Returns:
  - {:workflow_ids [uuid ...]} (vector, possibly empty)"
  [^DataSource ds ^UUID tenant-id]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "get-defaults missing required params" {:tenant-id tenant-id})))
  (let [row (jdbc/execute-one!
             ds
             ["SELECT workflow_ids FROM workflow_defaults WHERE tenant_id=?" tenant-id]
             {:builder-fn rs/as-unqualified-lower-maps})
        arr (:workflow_ids row)
        xs (cond
             (nil? arr) []
             (instance? java.sql.Array arr) (or (.getArray ^java.sql.Array arr) (object-array 0))
             :else arr)
        ids (->> (seq xs)
                 (keep (fn [x]
                         (try
                           (UUID/fromString (str x))
                           (catch Exception _ nil))))
                 vec)]
    {:workflow_ids ids}))

(defn set-defaults!
  "Set tenant workflow defaults.

  Inputs:
  - workflow-ids: vector of UUID

  Returns:
  - {:ok true}"
  [^DataSource ds ^UUID tenant-id workflow-ids]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "set-defaults! missing required params" {:tenant-id tenant-id})))
  (let [ids (->> (or workflow-ids [])
                 (filter #(instance? UUID %))
                 distinct
                 vec)]
    (jdbc/execute-one!
     ds
     ["INSERT INTO workflow_defaults (tenant_id, workflow_ids, updated_at)
       VALUES (?, ?::uuid[], now())
       ON CONFLICT (tenant_id)
       DO UPDATE SET workflow_ids=EXCLUDED.workflow_ids, updated_at=now()"
      tenant-id (into-array UUID ids)])
    {:ok true}))

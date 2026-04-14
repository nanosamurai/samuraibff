(ns samuraibff.db.webhooks
  "DB access for tenant-scoped webhook configuration.

  Tables:
  - webhooks
  - webhook_subscriptions
  - tenant_webhook_defaults

  Public API:
  - list-webhooks
  - find-webhook
  - insert-webhook!
  - update-webhook!
  - delete-webhook!
  - replace-subscriptions!
  - list-subscriptions
  - get-defaults
  - set-defaults!

  All functions are tenant-scoped: tenant-id must always be provided.
  "
  (:require
    [cheshire.core :as cheshire]
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (org.postgresql.util PGobject)
    (java.util UUID)
    (javax.sql DataSource)))

(defn- parse-jsonb
  "Parse a Postgres jsonb value returned by next.jdbc into a Clojure map.

  Context:
  - next.jdbc returns jsonb columns as `org.postgresql.util.PGobject` by default.
  - HTTP responses (Malli coercion) require `static_headers` to be a plain map
    of string->string (per schemas/StaticHeaders).

  Inputs:
  - x: nil | map | string | PGobject

  Returns:
  - map | nil

  Notes:
  - We intentionally parse with *string* keys (keyword? = false).
  - If parsing fails, returns nil." 
  [x]
  (let [m (cond
            (nil? x) nil
            (map? x) x
            (instance? PGobject x)
            (let [v (.getValue ^PGobject x)]
              (when (seq (str v))
                (cheshire/parse-string (str v) false)))
            (string? x)
            (when (seq x)
              (cheshire/parse-string x false))
            :else
            (try
              (let [s (str x)]
                (when (seq s)
                  (cheshire/parse-string s false)))
              (catch Exception _
                nil)))]
    (when (map? m)
      (into {}
            (keep (fn [[k v]]
                    (let [k (some-> k str)
                          v (some-> v str)]
                      (when (and (seq k) (seq v))
                        [k v]))))
            m))))

(defn- ->jsonb-pgobject
  "Convert a Clojure value into a Postgres jsonb PGobject.

  Inputs:
  - x: any JSON-serializable Clojure value

  Returns:
  - org.postgresql.util.PGobject with type jsonb" 
  ^PGobject
  [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (cheshire/generate-string x))))

(defn- normalize-webhook-row
  "Normalize a webhook row returned from the DB.

  Inputs:
  - row: map from next.jdbc

  Returns:
  - row with :static_headers parsed into a Clojure map (or nil)." 
  [row]
  (cond-> row
    (contains? row :static_headers) (update :static_headers parse-jsonb)))

(defn list-webhooks
  "List webhooks for a tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID

  Returns:
  - vector of webhook maps (unqualified lower keys).

  Note:
  - `static_headers` is returned as a Clojure map (parsed from jsonb)." 
  [^DataSource ds ^UUID tenant-id]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "list-webhooks missing required params" {:tenant-id tenant-id})))
  (->> (jdbc/execute!
        ds
        ["SELECT id, tenant_id, name, url, enabled, auth_type,
                 hmac_secret_ref, oauth_client_secret_ref, api_key_ref,
                 oauth_token_url, oauth_client_id, oauth_scopes,
                 api_key_header_name, api_key_prefix,
                 static_headers, created_at
            FROM webhooks
           WHERE tenant_id = ?
           ORDER BY created_at DESC"
         tenant-id]
        {:builder-fn rs/as-unqualified-lower-maps})
       (mapv normalize-webhook-row)))
(defn find-webhook
  "Find a single webhook by id for a tenant.

  Returns: row map or nil.

  Note:
  - `static_headers` is returned as a Clojure map (parsed from jsonb)." 
  [^DataSource ds ^UUID tenant-id ^UUID webhook-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID webhook-id))
    (throw (ex-info "find-webhook missing required params" {:tenant-id tenant-id :webhook-id webhook-id})))
  (some-> (jdbc/execute-one!
           ds
           ["SELECT id, tenant_id, name, url, enabled, auth_type,
                    hmac_secret_ref, oauth_client_secret_ref, api_key_ref,
                    oauth_token_url, oauth_client_id, oauth_scopes,
                    api_key_header_name, api_key_prefix,
                    static_headers, created_at
               FROM webhooks
              WHERE tenant_id = ? AND id = ?"
            tenant-id webhook-id]
           {:builder-fn rs/as-unqualified-lower-maps})
          normalize-webhook-row))

(defn insert-webhook!
  "Insert a webhook row.

  Inputs:
  - ds: DataSource
  - webhook map with keys:
      :id :tenant-id :name :url :enabled :auth-type
      and optional columns.

  Returns:
  - {:id uuid}" 
  [^DataSource ds {:keys [id tenant-id name url enabled auth-type]
                   :as webhook}]
  (when-not (and ds (instance? UUID id) (instance? UUID tenant-id)
                 (seq (str name)) (seq (str url)) (seq (str auth-type)))
    (throw (ex-info "insert-webhook! missing required params" {:webhook webhook})))
  (let [static-headers (:static_headers webhook)
        static-headers-pg (when (some? static-headers)
                            (->jsonb-pgobject (or static-headers {})))

        ;; IMPORTANT:
        ;; - do NOT use `[:raw "(?::jsonb)"]` + manual param `conj` here.
        ;;   That is fragile because the SQL placeholder order depends on map
        ;;   iteration order (hash-map), which can vary when optional keys exist.
        ;; - Use a PGobject parameter instead.
        values (cond-> {:id id
                        :tenant_id tenant-id
                        :name (str name)
                        :url (str url)
                        :enabled (boolean enabled)
                        :auth_type (str auth-type)}
                 (some? static-headers-pg) (assoc :static_headers static-headers-pg)
                 :always (merge (select-keys webhook
                                             [:hmac_secret_ref
                                              :oauth_client_secret_ref
                                              :api_key_ref
                                              :oauth_token_url
                                              :oauth_client_id
                                              :oauth_scopes
                                              :api_key_header_name
                                              :api_key_prefix])))
        q (-> (h/insert-into :webhooks)
              (h/values [values]))
        sqlvec (sql/format q)]
    (jdbc/execute-one! ds sqlvec)
    {:id id}))

(defn update-webhook!
  "Update a webhook row.

  Inputs:
  - ds: DataSource
  - tenant-id UUID
  - webhook-id UUID
  - patch map with allowed keys

  Returns:
  - {:updated? boolean}" 
  [^DataSource ds ^UUID tenant-id ^UUID webhook-id patch]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID webhook-id))
    (throw (ex-info "update-webhook! missing required params" {:tenant-id tenant-id :webhook-id webhook-id})))
  (let [allowed0 (select-keys patch
                             [:name :url :enabled :auth_type
                              :hmac_secret_ref :oauth_client_secret_ref :api_key_ref
                              :oauth_token_url :oauth_client_id :oauth_scopes
                              :api_key_header_name :api_key_prefix
                              :static_headers])
        allowed (cond-> allowed0
                  (contains? allowed0 :static_headers)
                  (assoc :static_headers (->jsonb-pgobject (or (:static_headers allowed0) {}))))
        q (-> (h/update :webhooks)
              (h/set allowed)
              (h/where [:= :tenant_id tenant-id]
                       [:= :id webhook-id]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn delete-webhook!
  "Delete a webhook by id (tenant-scoped).

  Returns:
  - {:deleted? boolean}" 
  [^DataSource ds ^UUID tenant-id ^UUID webhook-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID webhook-id))
    (throw (ex-info "delete-webhook! missing required params" {:tenant-id tenant-id :webhook-id webhook-id})))
  (let [res (jdbc/execute-one!
             ds
             ["DELETE FROM webhooks WHERE tenant_id=? AND id=?" tenant-id webhook-id])]
    {:deleted? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn list-subscriptions
  "List event_type subscriptions for a webhook.

  Returns vector of strings." 
  [^DataSource ds ^UUID tenant-id ^UUID webhook-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID webhook-id))
    (throw (ex-info "list-subscriptions missing required params" {:tenant-id tenant-id :webhook-id webhook-id})))
  (let [rows (jdbc/execute!
              ds
              ["SELECT event_type
                  FROM webhook_subscriptions
                 WHERE tenant_id=? AND webhook_id=?
                 ORDER BY event_type" tenant-id webhook-id]
              {:builder-fn rs/as-unqualified-lower-maps})]
    (mapv :event_type rows)))

(defn replace-subscriptions!
  "Replace the subscription set for a webhook.

  Inputs:
  - event-types: vector of strings

  Returns:
  - {:ok true}" 
  [^DataSource ds ^UUID tenant-id ^UUID webhook-id event-types]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID webhook-id))
    (throw (ex-info "replace-subscriptions! missing required params" {:tenant-id tenant-id :webhook-id webhook-id})))
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx
                       ["DELETE FROM webhook_subscriptions WHERE tenant_id=? AND webhook_id=?" tenant-id webhook-id])
    (doseq [et (vec (distinct (remove nil? event-types)))]
      (jdbc/execute-one!
        tx
        ["INSERT INTO webhook_subscriptions (tenant_id, webhook_id, event_type)
          VALUES (?, ?, ?)"
         tenant-id webhook-id (str et)])))
  {:ok true})

(defn get-defaults
  "Get tenant webhook defaults.

  Returns:
  - {:webhook_ids [uuid ...]} (vector, possibly empty)" 
  [^DataSource ds ^UUID tenant-id]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "get-defaults missing required params" {:tenant-id tenant-id})))
  (let [row (jdbc/execute-one!
              ds
              ["SELECT webhook_ids FROM tenant_webhook_defaults WHERE tenant_id=?" tenant-id]
              {:builder-fn rs/as-unqualified-lower-maps})]
    {:webhook_ids (vec (or (:webhook_ids row) []))}))

(defn set-defaults!
  "Set tenant webhook defaults.

  Inputs:
  - webhook-ids: vector of UUID

  Returns:
  - {:ok true}" 
  [^DataSource ds ^UUID tenant-id webhook-ids]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "set-defaults! missing required params" {:tenant-id tenant-id})))
  (let [ids (->> (or webhook-ids [])
                 (filter #(instance? UUID %))
                 distinct
                 vec)]
    (jdbc/execute-one!
      ds
      ["INSERT INTO tenant_webhook_defaults (tenant_id, webhook_ids, updated_at)
        VALUES (?, ?::uuid[], now())
        ON CONFLICT (tenant_id)
        DO UPDATE SET webhook_ids=EXCLUDED.webhook_ids, updated_at=now()"
       tenant-id (into-array UUID ids)])
    {:ok true}))

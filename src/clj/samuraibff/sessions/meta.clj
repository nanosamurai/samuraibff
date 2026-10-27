(ns samuraibff.sessions.meta
  "Build the `sessions.meta` Kafka value published at session inception.

  Purpose:
  - Provide a compact, session-scoped configuration snapshot for downstream
    routers/processors (e.g. webhook-router/event-router, future workflows).

  Key properties:
  - JSON-friendly (plain maps/vectors, string UUIDs)
  - May include customer webhook URLs (intended for internal router/dispatcher)
  - Must not include secrets in plaintext

  Access:
  - Webhook routing and workflow targets include only definitions the
    session owner (`sessions.user_id`) may use. Tenant defaults add published
    definitions only.

  Admission freeze:
  - `resolve-admission-meta!` resolves routing and workflow targets once, at
    the first audio start, and stores them in `sessions.admission_snapshot`.
    Later audio starts reuse the stored value, so later definition changes do
    not change a running session. The stored value holds only secret
    references, never secret values.

  Public API:
  - `build-sessions-meta`
  - `resolve-sessions-meta`
  - `resolve-admission-meta!`

  Inputs (high level):
  - tenant-id, session-id: UUID
  - webhook routing snapshot (targets by event type)
  - session settings (from API request + BFF config defaults)

  Returns:
  - map suitable for JSON encoding and publishing to Kafka topic `sessions.meta`"
  (:require
   [cheshire.core :as json]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.integrations :as access.integrations]
   [samuraibff.features :as features]
   [samuraibff.webhooks.routing-snapshot :as webhooks.snapshot]
   [samuraibff.workflows.snapshot :as workflows.snapshot]
   [samuraibff.util.uuid :as util.uuid])
  (:import
   (org.postgresql.util PGobject)))

(def ^:private default-max-bytes
  "Default payload max bytes for consolidated refined transcript tail."
  262144)

(def ^:private default-topic
  "Default Kafka topic name for consolidated refined transcript view."
  "transcripts.refined.consolidated")

(def ^:private default-include-full-text
  "Whether consolidated snapshot should include `full_text` (in addition to segments)."
  true)

(defn build-sessions-meta
  "Build `sessions.meta` value.

  Inputs:
  - config: full BFF config map
  - tenant-id: UUID
  - session-id: UUID
  - webhook-routing: map {:targets_by_event_type {...}}
  - session-settings: map or nil (shape per schemas/CreateSessionRequest :session_settings)
  - workflow-targets: vector of resolved workflow targets (for sessions.meta.workflows.targets)

  Behavior:
  - Publishes both `routing` (legacy) and `webhook_routing` (new) for now.
  - Adds `refined_transcript.consolidation` config.

  Returns:
  - sessions.meta map (JSON-friendly)."
  [config tenant-id session-id webhook-routing session-settings workflow-targets]
  (let [enabled? (boolean (get-in (or session-settings {}) [:refined_transcript :consolidation :enabled]))
        cfg (or (get-in config [:sessions-meta :refined-transcript :consolidation])
                (get-in config [:sessions_meta :refined_transcript :consolidation])
                {})
        max-bytes (or (:max-bytes cfg) (:max_bytes cfg) default-max-bytes)
        topic (or (:topic cfg) default-topic)
        include-full-text (if (contains? cfg :include-full-text)
                            (boolean (:include-full-text cfg))
                            (if (contains? cfg :include_full_text)
                              (boolean (:include_full_text cfg))
                              default-include-full-text))
        runtime-enabled? (features/workflow-webhook-runtime-enabled? config)
        routing-map (or webhook-routing {:targets_by_event_type {}})
        workflow-targets (vec (or workflow-targets []))
        schema-version 2
        base {:session_id (str session-id)
              :tenant_id (str tenant-id)
              :schema_version schema-version
              :event_id (str (util.uuid/uuid7))
              :refined_transcript
              {:consolidation
               {:enabled enabled?
                :max_bytes (long max-bytes)
                :topic (str topic)
                :include_full_text (boolean include-full-text)}}}]
    (cond-> base
      runtime-enabled?
      (assoc :routing routing-map
             :webhook_routing routing-map
             :workflows {:targets workflow-targets}))))

(defn- load-session-row
  "Read the session columns needed to resolve sessions.meta, or nil."
  [ds tenant-id session-id]
  (jdbc/execute-one!
   ds
   (sql/format {:select [:user_id
                         [[:cast :session_settings :text] :session_settings]
                         [[:cast :webhook_overrides :text] :webhook_overrides]
                         [[:cast :workflow_overrides :text] :workflow_overrides]
                         [[:cast :admission_snapshot :text] :admission_snapshot]]
                :from [:sessions]
                :where [:and [:= :tenant_id tenant-id] [:= :id session-id]]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- parse-json
  [s]
  (some-> s (json/parse-string true)))

(defn- resolve-selection
  "Resolve owner-filtered routing and workflow targets for a session row.

  Returns {:routing map-or-nil :workflow_targets [target ...]}."
  [config ds tenant-id session-id row]
  (let [enabled? (features/workflow-webhook-runtime-enabled? config)
        owner (when enabled?
                (access.integrations/owner-principal ds tenant-id (:user_id row)))]
    {:routing (when enabled?
                (webhooks.snapshot/resolve-routing-snapshot
                 ds tenant-id session-id (parse-json (:webhook_overrides row)) owner))
     :workflow_targets (if enabled?
                         (workflows.snapshot/resolve-targets
                          ds tenant-id session-id (parse-json (:workflow_overrides row)) owner)
                         [])}))

(defn- selection->meta
  "Build sessions.meta from a session row and a resolved selection."
  [config tenant-id session-id row {:keys [routing workflow_targets]}]
  (let [targets (vec (or workflow_targets []))
        settings (cond-> (or (parse-json (:session_settings row)) {})
                   (workflows.snapshot/any-target-requires-refined-consolidation? targets)
                   (assoc-in [:refined_transcript :consolidation :enabled] true))]
    (build-sessions-meta config tenant-id session-id routing settings targets)))

(defn resolve-sessions-meta
  "Resolve the current sessions.meta value from tenant-scoped DB settings.

  Inputs: config map, DataSource, tenant UUID, session UUID.
  Returns: Kafka-ready sessions.meta map. Routing and workflow targets are
  filtered for the session owner read from `sessions.user_id`; a session
  without an active human owner gets no webhook or workflow targets.
  Nothing is stored; see `resolve-admission-meta!` for the frozen value."
  [config ds tenant-id session-id]
  (let [row (load-session-row ds tenant-id session-id)]
    (selection->meta config tenant-id session-id row
                     (resolve-selection config ds tenant-id session-id row))))

(defn- ->jsonb
  ^PGobject [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string value))))

(defn- store-admission-snapshot!
  "Store the selection only when the session has none yet. Returns update count."
  [ds tenant-id session-id selection]
  (:next.jdbc/update-count
   (jdbc/execute-one!
    ds
    (sql/format {:update :sessions
                 :set {:admission_snapshot (->jsonb selection)}
                 :where [:and [:= :tenant_id tenant-id]
                         [:= :id session-id]
                         [:= :admission_snapshot nil]]}))))

(defn resolve-admission-meta!
  "Return sessions.meta with routing and workflow targets frozen at first audio start.

  Inputs: config map, DataSource, tenant UUID, session UUID.
  Behavior:
  - When `sessions.admission_snapshot` is NULL, resolve the owner-filtered
    selection, store it with one conditional UPDATE (`WHERE admission_snapshot
    IS NULL`), then read the stored value back. Concurrent replicas therefore
    all use the first stored value.
  - Otherwise reuse the stored selection without reading definitions.
  The stored selection is {:routing map-or-nil :workflow_targets [...]} and
  contains secret references only.
  Returns: Kafka-ready sessions.meta map (fresh event_id per call).
  Throws ex-info {:type :samuraibff.sessions/not-found} when the session does
  not exist in the tenant."
  [config ds tenant-id session-id]
  (let [row (or (load-session-row ds tenant-id session-id)
                (throw (ex-info "Session not found" {:type :samuraibff.sessions/not-found})))
        row (if (some? (:admission_snapshot row))
              row
              (let [selection (resolve-selection config ds tenant-id session-id row)
                    stored (store-admission-snapshot! ds tenant-id session-id selection)]
                (log/info "Resolved session admission snapshot"
                          {:tenant_id (str tenant-id)
                           :session_id (str session-id)
                           :stored (pos? (long (or stored 0)))
                           :workflow_targets_count (count (:workflow_targets selection))})
                (load-session-row ds tenant-id session-id)))]
    (selection->meta config tenant-id session-id row (parse-json (:admission_snapshot row)))))

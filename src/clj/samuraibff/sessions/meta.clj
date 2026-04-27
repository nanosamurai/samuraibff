(ns samuraibff.sessions.meta
  "Build the `sessions.meta` Kafka value published at session inception.

  Purpose:
  - Provide a compact, session-scoped configuration snapshot for downstream
    routers/processors (e.g. webhook-router/event-router, future workflows).

  Key properties:
  - JSON-friendly (plain maps/vectors, string UUIDs)
  - May include customer webhook URLs (intended for internal router/dispatcher)
  - Must not include secrets in plaintext

  Public API:
  - `build-sessions-meta`

  Inputs (high level):
  - tenant-id, session-id: UUID
  - webhook routing snapshot (targets by event type)
  - session settings (from API request + BFF config defaults)

  Returns:
  - map suitable for JSON encoding and publishing to Kafka topic `sessions.meta`"
  (:require
   [samuraibff.util.uuid :as util.uuid]))

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

  Behavior:
  - Publishes both `routing` (legacy) and `webhook_routing` (new) for now.
  - Adds `refined_transcript.consolidation` config.

  Returns:
  - sessions.meta map (JSON-friendly)."
  [config tenant-id session-id webhook-routing session-settings]
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
        routing-map (or webhook-routing {:targets_by_event_type {}})
        schema-version 1]
    {:session_id (str session-id)
     :tenant_id (str tenant-id)
     :schema_version schema-version
     ;; emit event_id too so router/dispatcher can correlate meta revisions
     :event_id (str (util.uuid/uuid7))

     ;; Legacy key (keep for transition)
     :routing routing-map
     ;; New explicit key
     :webhook_routing routing-map

     :refined_transcript
     {:consolidation
      {:enabled enabled?
       :max_bytes (long max-bytes)
       :topic (str topic)
       :include_full_text (boolean include-full-text)}}}))

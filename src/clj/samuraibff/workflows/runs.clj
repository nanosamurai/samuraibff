(ns samuraibff.workflows.runs
  "Workflow execution settings and on-demand workflow jobs.

  Settings (under `:workflows` in the BFF config):
  - `:provider-types`: provider types the editor offers and the API accepts
    for new or changed definitions (SAMURAIBFF_WORKFLOW_PROVIDER_TYPES,
    default [\"bedrock\"]). Stored definitions with other types keep working.
  - `:run-max-bytes`: maximum encoded size of one on-demand job
    (SAMURAIBFF_WORKFLOW_RUN_MAX_BYTES, default 524288).

  On-demand jobs use the `WorkflowDispatchJob` contract of `workflow.dispatch`
  (schema_version 1), the same shape the event router produces. A run reads
  one stored transcript source, given as a stage (`final` or `refined`) and a
  track ID:
  - `final` -> trigger `transcript.final.ready`, input kind `final`
  - `refined` -> trigger `transcript.refined.segment`, input kind
    `refined.consolidated`

  Security:
  - Jobs contain prompt and transcript text. Do not log them.

  Public API:
  - `provider-types`, `provider-type-allowed?`, `run-max-bytes`
  - `select-source`, `transcript-segments`, `build-job`"
  (:require
   [clojure.string :as str]
   [samuraibff.ui.recording-detail :as recording-detail]
   [samuraibff.workflows.snapshot :as workflows.snapshot]))

(def ^:private default-provider-types
  ["bedrock"])

(def ^:private default-run-max-bytes
  524288)

(def ^:private stages
  {"final" {:trigger-type "transcript.final.ready" :kind "final"}
   "refined" {:trigger-type "transcript.refined.segment" :kind "refined.consolidated"}})

(defn provider-types
  "Return the configured workflow provider types.

  Inputs: BFF config map.
  Returns: non-empty vector of type strings."
  [config]
  (vec (or (seq (get-in config [:workflows :provider-types])) default-provider-types)))

(defn provider-type-allowed?
  "True when a definition may be saved with provider type t.

  Inputs: BFF config map, requested type string, stored type string or nil.
  A type outside the allowlist is accepted only when it equals the stored
  type, so existing definitions stay editable."
  [config t stored-type]
  (boolean (or (some #{t} (provider-types config))
               (and (some? stored-type) (= t stored-type)))))

(defn run-max-bytes
  "Return the maximum encoded size in bytes of one on-demand job.

  Inputs: BFF config map. Returns: positive long."
  [config]
  (let [n (get-in config [:workflows :run-max-bytes])]
    (if (and (integer? n) (pos? n)) (long n) default-run-max-bytes)))

(defn- bound-source
  "Return the [stage track] bound by a workflow trigger, or nil for
  `recording.finished`."
  [workflow]
  (case (:trigger_type workflow)
    "transcript.final.ready" ["final" (:trigger_track_id workflow)]
    "transcript.refined.segment" ["refined" (:trigger_track_id workflow)]
    nil))

(defn- default-track
  "Return the deployment default track ID of a stage (see README track defaults)."
  [config stage]
  (if (= stage "final")
    (or (get-in config [:default-tracks :final]) (first (:final-tracks config)) "whisperx")
    (or (get-in config [:default-tracks :refined]) (first (:refinement-tracks config)) "whisperx")))

(defn select-source
  "Choose the stored transcript source of an on-demand run.

  Inputs:
  - config: BFF config map
  - workflow: workflow row (:trigger_type, :trigger_track_id)
  - available: collection of [stage track-id] pairs stored for the session
  - request: {:stage \"final\"|\"refined\"? :track_id string?}

  Order:
  1. stage and track_id given: exactly that source
  2. stage given: the workflow's own track for that stage, the deployment
     default track, then the first stored track of that stage
  3. nothing given: the workflow's own source, then the default final
     track, then the first stored final track

  Returns: [stage track-id] of a stored source, or nil when none matches."
  [config workflow available {:keys [stage track_id]}]
  (let [stored? (set available)
        first-stored (fn [st] (first (sort (keep (fn [[s t]] (when (= s st) t)) available))))
        candidates (fn [st bound]
                     [(when (= st (first bound)) bound)
                      [st (default-track config st)]
                      [st (first-stored st)]])
        bound (bound-source workflow)]
    (some #(when (stored? %) %)
          (cond
            (and stage track_id) [[stage track_id]]
            stage (candidates stage bound)
            :else (cons bound (candidates "final" nil))))))

(defn transcript-segments
  "Convert the stored rows of one transcript source into job segments.

  Inputs:
  - rows: transcript records of one track, ordered by created_at, with
    decoded :segments vectors
  - stage: \"final\" or \"refined\"

  Uses the same selection as the recording page
  (`samuraibff.ui.recording-detail/record-messages`): the latest final row,
  or every refined window ordered by its audio bounds.
  Returns: vector of {:start_s :end_s :speaker :text}; keys without a value
  are omitted and blank segments are dropped."
  [rows stage]
  (into []
        (keep (fn [{:keys [start_s end_s speaker text]}]
                (let [text (str/trim (str (or text "")))]
                  (when (seq text)
                    (cond-> {:text text}
                      (number? start_s) (assoc :start_s (double start_s))
                      (number? end_s) (assoc :end_s (double end_s))
                      (and (string? speaker) (not (str/blank? speaker))) (assoc :speaker speaker))))))
        (recording-detail/record-messages rows (keyword stage))))

(defn build-job
  "Build a WorkflowDispatchJob map for an on-demand run.

  Inputs: map with
  - :workflow-run-id UUID, :tenant-id UUID, :session-id UUID
  - :workflow workflow row (prompt, provider, trigger)
  - :stage \"final\"|\"refined\", :track-id string
  - :segments vector from `transcript-segments`
  - :created-at java.time.Instant

  Returns: JSON-friendly job map, or nil when the workflow has no prompt or
  model ID. `stream` is omitted: results reach the UI through the stored
  latest workflow results, as for automatic runs."
  [{:keys [workflow-run-id tenant-id session-id workflow stage track-id segments created-at]}]
  (when-let [{:keys [workflow_id prompt provider]} (workflows.snapshot/workflow->target workflow)]
    (let [{:keys [trigger-type kind]} (get stages stage)]
      {:schema_version 1
       :workflow_run_id (str workflow-run-id)
       :tenant_id (str tenant-id)
       :session_id (str session-id)
       :workflow_id workflow_id
       :trigger {:type trigger-type
                 :track_id track-id
                 :source_event_id nil
                 :created_at (str created-at)}
       :provider provider
       :prompt prompt
       :input {:transcript {:kind kind :segments segments}}
       :attempt {:no 0}})))

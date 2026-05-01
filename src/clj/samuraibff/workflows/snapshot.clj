(ns samuraibff.workflows.snapshot
  "Resolve session-scoped workflow target snapshot for `sessions.meta`.

  Purpose:
  - Implements RFC-0003 control-plane: resolve tenant defaults + per-session
    overrides to a fully resolved list of workflow targets.

  Outputs:
  - JSON-friendly maps suitable for inclusion under `sessions.meta.workflows.targets`.

  Security:
  - This snapshot contains user prompts (non-secret) and model parameters.
  - Do not log prompt bodies or full params maps at INFO level.
  "
  (:require
   [clojure.set :as set]
   [samuraibff.db.workflows :as db.workflows])
  (:import
   (java.util UUID)))

(def ^:private supported-trigger-types
  #{"transcript.refined.segment"
    "transcript.final.ready"
    "recording.finished"})

(defn- normalize-overrides
  "Normalize :workflow_overrides request payload.

  Input shape (conceptual):
  {:use_defaults boolean?
   :workflow_ids [uuid-string ...]?}

  Returns:
  {:use-defaults? boolean
   :workflow-ids (set UUID)}"
  [overrides]
  (let [use-defaults? (if (contains? overrides :use_defaults)
                        (boolean (:use_defaults overrides))
                        true)
        workflow-ids (->> (or (:workflow_ids overrides) [])
                          (keep (fn [s]
                                  (try
                                    (UUID/fromString (str s))
                                    (catch Exception _ nil))))
                          set)]
    {:use-defaults? use-defaults?
     :workflow-ids workflow-ids}))

(defn- workflow->target
  "Convert a workflow DB row into a sessions.meta workflow target.

  Inputs:
  - row map from db.workflows/list-workflows

  Returns:
  - JSON-friendly target map matching webhook-router's SessionsMeta.Workflows.Target." 
  [w]
  (let [trigger-type (some-> (:trigger_type w) str)
        trigger-type (when (contains? supported-trigger-types trigger-type) trigger-type)
        provider-type (some-> (:provider_type w) str)
        incremental-enabled? (:incremental_enabled w)
        min-interval (some-> (:incremental_min_interval_sec w) long)]
    (when (and trigger-type
               (seq (str (:prompt_text w)))
               (seq (str (:provider_model_id w))))
      {:workflow_id (str (:id w))
       :name (str (or (:name w) ""))
       :enabled (boolean (:enabled w))
       :trigger {:type trigger-type}
       :prompt {:text (str (:prompt_text w))}
       :provider {:type (or provider-type "bedrock")
                  :model_id (str (:provider_model_id w))
                  :params (or (:provider_params w) {})}
       :incremental {:enabled (boolean incremental-enabled?)
                     :min_interval_sec (when (and incremental-enabled?
                                                  (pos? (long (or min-interval 0))))
                                         (long min-interval))}})))

(defn resolve-targets
  "Resolve workflow targets for a newly created session.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID (currently unused; included for parity with webhook snapshot resolver)
  - workflow-overrides: map or nil (shape per schemas/CreateSessionRequest)

  Returns:
  - vector of resolved target maps (JSON-friendly)." 
  [ds ^UUID tenant-id ^UUID _session-id workflow-overrides]
  (let [{:keys [use-defaults? workflow-ids]} (normalize-overrides (or workflow-overrides {}))
        {:keys [workflow_ids]} (if use-defaults?
                                 (db.workflows/get-defaults ds tenant-id)
                                 {:workflow_ids []})
        default-ids (set workflow_ids)
        selected-ids (set/union default-ids workflow-ids)
        rows (if (seq selected-ids)
               (->> (db.workflows/list-workflows ds tenant-id)
                    (filter (fn [w]
                              (contains? selected-ids (:id w))))
                    vec)
               [])]
    (->> rows
         (keep workflow->target)
         vec)))

(ns samuraibff.ui.session-request
  "Pure helpers for building `POST /api/sessions` request bodies.

  Motivation:
  - UI state is stored in CLJS atoms, but we want the request-building logic to
    be unit-tested under the existing CLJ test runner.
  - Therefore this namespace is `.cljc` and all functions are pure.

  Inputs:
  - `session` is expected to be the map stored in `samuraibff.ui.store/session*`.

  Outputs:
  - JSON-friendly maps compatible with `schemas/CreateSessionRequest`.
  "
  (:require
   [clojure.string :as str]))

(defn resolved-webhook-overrides
  "Compute the `webhook_overrides` request body for `POST /api/sessions`.

  Inputs:
  - session: map

  Returns:
  - nil when the UI is at the default state (so we omit the field entirely)
  - otherwise a JSON-friendly map matching schemas/CreateSessionRequest."
  [session]
  (let [ov (or (:webhook_overrides session) {})
        use-defaults? (if (contains? ov :use_defaults)
                        (boolean (:use_defaults ov))
                        true)
        webhook-ids (set (or (:webhook_ids ov) #{}))
        disable-event-types (set (or (:disable_event_types ov) #{}))
        default? (and (true? use-defaults?)
                      (empty? webhook-ids)
                      (empty? disable-event-types))]
    (when-not default?
      (cond-> {:use_defaults use-defaults?
               :webhook_ids (vec (sort webhook-ids))}
        (seq disable-event-types)
        (assoc :disable_event_types (vec (sort disable-event-types)))))))

(defn resolved-workflow-overrides
  "Compute the `workflow_overrides` request body for `POST /api/sessions`.

  Inputs:
  - session: map

  Returns:
  - nil when the UI is at the default state (so we omit the field entirely)
  - otherwise a JSON-friendly map matching schemas/CreateSessionRequest.

  Shape:
  - {:use_defaults <boolean>
     :workflow_ids [<uuid-string> ...]}"
  [session]
  (let [ov (or (:workflow_overrides session) {})
        use-defaults? (if (contains? ov :use_defaults)
                        (boolean (:use_defaults ov))
                        true)
        workflow-ids (set (or (:workflow_ids ov) #{}))
        default? (and (true? use-defaults?)
                      (empty? workflow-ids))]
    (when-not default?
      {:use_defaults use-defaults?
       :workflow_ids (vec (sort workflow-ids))})))

(defn refined-output-enabled?
  "Return true when the refined pipeline output is enabled for the session.

  Inputs:
  - session map

  Returns: boolean."
  [session]
  (true? (get-in session [:controls :refined])))

(defn resolved-session-settings
  "Compute the `session_settings` request body for `POST /api/sessions`.

  Today this controls refined transcript consolidation (rolling tail) used by
  downstream consumers (webhook router, workflows, LLM post-processing, ...).

  Inputs:
  - session map

  Returns:
  - nil when settings are not enabled (so the field can be omitted)
  - otherwise a JSON-friendly map matching schemas/CreateSessionRequest."
  [session]
  (let [enabled? (true? (get-in session [:session_settings :refined_transcript :consolidation :enabled]))]
    (when (and enabled?
               (refined-output-enabled? session))
      {:refined_transcript {:consolidation {:enabled true}}})))

(defn create-session-request-body
  "Build the full request body for `POST /api/sessions`.

  Inputs:
  - session map

  Returns JSON-friendly request map."
  [session]
  (let [session (or session {})
        title (some-> (:title session) str)
        title (when (seq (str/trim (str title))) title)
        webhook-overrides (resolved-webhook-overrides session)
        workflow-overrides (resolved-workflow-overrides session)
        session-settings (resolved-session-settings session)]
    (cond-> {:title (or title "")}
      (some? webhook-overrides) (assoc :webhook_overrides webhook-overrides)
      (some? workflow-overrides) (assoc :workflow_overrides workflow-overrides)
      (some? session-settings) (assoc :session_settings session-settings))))

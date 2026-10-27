(ns samuraibff.ui.destinations
  "Pure helpers that name the workflows and webhooks a new session will use.

  The Record page shows this list next to \"Who can access\" so the recorder
  sees where results go before recording starts. The selection rule matches
  the BFF: organization defaults (when enabled) plus explicitly selected items,
  only for enabled definitions.

  Public API:
  - `effective-items`, `webhook-label`, `workflow-label`"
  (:require
   [samuraibff.ui.access :as access]))

(defn effective-items
  "Return the enabled definitions a new session will use.

  Inputs:
  - items: vector of definition maps with :id and :enabled
  - overrides: session overrides map with :use_defaults (default true) and the ids key
  - default-ids: collection of default id strings
  - ids-key: :webhook_ids or :workflow_ids

  Returns: vector of definition maps in list order."
  [items overrides default-ids ids-key]
  (let [use-defaults? (if (contains? overrides :use_defaults)
                        (boolean (:use_defaults overrides))
                        true)
        ids (cond-> (set (map str (get overrides ids-key)))
              use-defaults? (into (map str default-ids)))]
    (filterv #(and (true? (:enabled %)) (contains? ids (str (:id %))))
             (or items []))))

(defn webhook-label
  "Return \"Name → host\" for a webhook; the host hides path and credentials."
  [{:keys [name url]}]
  (let [host (access/url-host url)]
    (str (or name "Webhook") (when (seq host) (str " → " host)))))

(defn workflow-label
  "Return \"Name · model\" for a workflow."
  [{:keys [name provider]}]
  (let [model (:model_id provider)]
    (str (or name "Workflow") (when (seq (str model)) (str " · " model)))))

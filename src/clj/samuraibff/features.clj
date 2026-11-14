(ns samuraibff.features
  "Centralized feature availability derived from runtime config.

  Each optional capability has its own flag under `:features`:

  - `:workflows-ondemand-enabled?` (SAMURAIBFF_WORKFLOWS_ONDEMAND_ENABLED): workflow
    definitions and on-demand runs on finished sessions.
  - `:workflows-triggered-enabled?` (SAMURAIBFF_WORKFLOWS_TRIGGERED_ENABLED):
    automatic event-triggered workflows. Covers session workflow overrides,
    workflow targets in `sessions.meta`, `/internal/workflow-result` and the
    live workflow results consumer. Also opens workflow definitions.
  - `:webhooks-enabled?` (SAMURAIBFF_WEBHOOKS_ENABLED): webhook definitions,
    session webhook overrides, webhook routing in `sessions.meta` and webhook
    delivery outcomes.

  A capability flag that is unset defaults to false. The deprecated
  `:ce-mode?` (SAMURAIBFF_CE_MODE) only changes that default: when it is
  false, unset capability flags default to true. An explicit capability flag
  always wins.

  Public API:
  - ce-mode?
  - workflows-ondemand-enabled?
  - workflows-triggered-enabled?
  - workflow-definitions-enabled?
  - webhooks-enabled?
  - uploads-max-bytes
  - feature-state
  - enabled?
  - not-enabled-response
  - wrap-enabled"
  (:require
   [clojure.string :as str]))

(defn ce-mode?
  "Return the deprecated Community Edition switch.

  Inputs:
  - config: BFF config map, may contain [:features :ce-mode?]

  Returns: boolean. Defaults to true when config is absent or the key is
  absent. It only sets the default of unset capability flags."
  [config]
  (not (false? (get-in config [:features :ce-mode?] true))))

(defn- capability-enabled?
  "Return the value of capability flag `k`, or its default when unset.

  The default is false, or true when the deprecated CE switch is false."
  [config k]
  (let [v (get-in config [:features k])]
    (if (boolean? v)
      v
      (not (ce-mode? config)))))

(defn workflows-ondemand-enabled?
  "Return true when workflow definitions and on-demand runs are enabled.

  Inputs:
  - config: BFF config map, may contain [:features :workflows-ondemand-enabled?]

  Returns: boolean."
  [config]
  (capability-enabled? config :workflows-ondemand-enabled?))

(defn workflows-triggered-enabled?
  "Return true when automatic event-triggered workflows are enabled.

  Inputs:
  - config: BFF config map, may contain [:features :workflows-triggered-enabled?]

  Returns: boolean. Gates session workflow overrides, workflow targets in
  `sessions.meta`, `/internal/workflow-result` and the workflow results
  consumer."
  [config]
  (capability-enabled? config :workflows-triggered-enabled?))

(defn workflow-definitions-enabled?
  "Return true when workflow definitions and defaults may be managed.

  Inputs:
  - config: BFF config map

  Returns: boolean. Both on-demand runs and automatic triggers need
  definitions."
  [config]
  (or (workflows-ondemand-enabled? config)
      (workflows-triggered-enabled? config)))

(defn webhooks-enabled?
  "Return true when webhooks are enabled.

  Inputs:
  - config: BFF config map, may contain [:features :webhooks-enabled?]

  Returns: boolean."
  [config]
  (capability-enabled? config :webhooks-enabled?))

(defn uploads-max-bytes
  "Return the file upload limit in bytes, or 0 when file uploads are disabled.

  Inputs:
  - config: BFF config map with [:uploads :max-bytes] and
    [:s3 :buckets :recordings :bucket]

  Returns: long. Uploads need a positive limit and a recordings bucket; a
  limit of 0 (or a missing bucket) disables them."
  [config]
  (let [max-bytes (long (or (get-in config [:uploads :max-bytes]) 0))
        bucket (get-in config [:s3 :buckets :recordings :bucket])]
    (if (and (pos? max-bytes) (not (str/blank? (str bucket))))
      max-bytes
      0)))

(defn enabled?
  "Return true when a named feature is enabled.

  Inputs:
  - config: BFF config map
  - feature: keyword, one of :workflows-ondemand,
    :workflow-definitions, :workflows-triggered or :webhooks

  Returns: boolean. Unknown features are not gated."
  [config feature]
  (case feature
    :workflows-ondemand (workflows-ondemand-enabled? config)
    :workflow-definitions (workflow-definitions-enabled? config)
    :workflows-triggered (workflows-triggered-enabled? config)
    :webhooks (webhooks-enabled? config)
    true))

(defn feature-state
  "Return JSON-friendly feature state for clients and logs.

  Inputs:
  - config: BFF config map

  Returns:
  - {:workflows_ondemand_enabled boolean          ; definitions + on-demand runs
     :workflows_triggered_enabled boolean  ; automatic event-triggered runs
     :webhooks_enabled boolean
     :uploads_enabled boolean
     :uploads_max_bytes int              ; 0 when uploads are disabled
     :ce_mode boolean                    ; deprecated: the CE switch
     :workflow_webhook_runtime_enabled boolean} ; deprecated: triggers and webhooks"
  [config]
  (let [triggers? (workflows-triggered-enabled? config)
        webhooks? (webhooks-enabled? config)
        upload-limit (uploads-max-bytes config)]
    {:ce_mode (ce-mode? config)
     :workflow_webhook_runtime_enabled (and triggers? webhooks?)
     :webhooks_enabled webhooks?
     :workflows_ondemand_enabled (workflows-ondemand-enabled? config)
     :workflows_triggered_enabled triggers?
     :uploads_enabled (pos? upload-limit)
     :uploads_max_bytes upload-limit}))

(defn not-enabled-response
  "Return a clear Ring response for a disabled feature.

  Inputs:
  - config: BFF config map
  - feature: keyword

  Returns: Ring response map with status 403."
  [config feature]
  {:status 403
   :body {:ok false
          :message "feature-not-enabled"
          :feature (name feature)
          :features (feature-state config)}})

(defn wrap-enabled
  "Wrap a Ring handler with a feature availability check.

  Inputs:
  - config: BFF config map
  - feature: keyword accepted by `enabled?`
  - handler: Ring handler function

  Returns: Ring handler function."
  [config feature handler]
  (fn [req]
    (if (enabled? config feature)
      (handler req)
      (not-enabled-response config feature))))

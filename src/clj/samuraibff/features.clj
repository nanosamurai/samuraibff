(ns samuraibff.features
  "Centralized feature availability derived from runtime config.

  Each optional capability has its own flag under `:features`:

  - `:workflows-enabled?` (SAMURAIBFF_WORKFLOWS_ENABLED): workflow
    definitions and on-demand runs on finished sessions.
  - `:workflow-triggers-enabled?` (SAMURAIBFF_WORKFLOW_TRIGGERS_ENABLED):
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
  - workflows-enabled?
  - workflow-triggers-enabled?
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

(defn workflows-enabled?
  "Return true when workflow definitions and on-demand runs are enabled.

  Inputs:
  - config: BFF config map, may contain [:features :workflows-enabled?]

  Returns: boolean."
  [config]
  (capability-enabled? config :workflows-enabled?))

(defn workflow-triggers-enabled?
  "Return true when automatic event-triggered workflows are enabled.

  Inputs:
  - config: BFF config map, may contain [:features :workflow-triggers-enabled?]

  Returns: boolean. Gates session workflow overrides, workflow targets in
  `sessions.meta`, `/internal/workflow-result` and the workflow results
  consumer."
  [config]
  (capability-enabled? config :workflow-triggers-enabled?))

(defn workflow-definitions-enabled?
  "Return true when workflow definitions and defaults may be managed.

  Inputs:
  - config: BFF config map

  Returns: boolean. Both on-demand runs and automatic triggers need
  definitions."
  [config]
  (or (workflows-enabled? config)
      (workflow-triggers-enabled? config)))

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
  - feature: keyword, one of :workflows (on-demand runs),
    :workflow-definitions, :workflow-triggers or :webhooks

  Returns: boolean. Unknown features are not gated."
  [config feature]
  (case feature
    :workflows (workflows-enabled? config)
    :workflow-definitions (workflow-definitions-enabled? config)
    :workflow-triggers (workflow-triggers-enabled? config)
    :webhooks (webhooks-enabled? config)
    true))

(defn feature-state
  "Return JSON-friendly feature state for clients and logs.

  Inputs:
  - config: BFF config map

  Returns:
  - {:workflows_enabled boolean          ; definitions + on-demand runs
     :workflow_triggers_enabled boolean  ; automatic event-triggered runs
     :webhooks_enabled boolean
     :uploads_enabled boolean
     :uploads_max_bytes int              ; 0 when uploads are disabled
     :ce_mode boolean                    ; deprecated: the CE switch
     :workflow_webhook_runtime_enabled boolean} ; deprecated: triggers and webhooks"
  [config]
  (let [triggers? (workflow-triggers-enabled? config)
        webhooks? (webhooks-enabled? config)
        upload-limit (uploads-max-bytes config)]
    {:ce_mode (ce-mode? config)
     :workflow_webhook_runtime_enabled (and triggers? webhooks?)
     :webhooks_enabled webhooks?
     :workflows_enabled (workflows-enabled? config)
     :workflow_triggers_enabled triggers?
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

(ns samuraibff.features
  "Centralized feature availability derived from runtime config.

  Community Edition defaults to enabled. In CE mode, workflow/webhook runtime
  features are disabled for product clarity and easier local operation.

  Public API:
  - ce-mode?
  - workflow-webhook-runtime-enabled?
  - feature-state
  - enabled?
  - wrap-enabled")

(defn ce-mode?
  "Return true when Community Edition mode is active.

  Inputs:
  - config: BFF config map, may contain [:features :ce-mode?]

  Returns: boolean. Defaults to true when config is absent or the key is absent."
  [config]
  (not (false? (get-in config [:features :ce-mode?] true))))

(defn workflow-webhook-runtime-enabled?
  "Return true when workflow/webhook runtime features are enabled.

  Inputs:
  - config: BFF config map

  Returns: boolean. This is false in CE mode and true when
  SAMURAIBFF_CE_MODE=false has been parsed into config."
  [config]
  (not (ce-mode? config)))

(defn enabled?
  "Return true when a named feature is enabled.

  Inputs:
  - config: BFF config map
  - feature: keyword, currently :webhooks, :workflows, or
    :workflow-webhook-runtime

  Returns: boolean."
  [config feature]
  (case feature
    (:webhooks :workflows :workflow-webhook-runtime)
    (workflow-webhook-runtime-enabled? config)

    true))

(defn feature-state
  "Return JSON-friendly feature state for clients and logs.

  Inputs:
  - config: BFF config map

  Returns:
  - {:ce_mode boolean
     :workflow_webhook_runtime_enabled boolean
     :webhooks_enabled boolean
     :workflows_enabled boolean}"
  [config]
  (let [runtime? (workflow-webhook-runtime-enabled? config)]
    {:ce_mode (ce-mode? config)
     :workflow_webhook_runtime_enabled runtime?
     :webhooks_enabled runtime?
     :workflows_enabled runtime?}))

(defn not-enabled-response
  "Return a clear Ring response for disabled commercial features.

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
  - feature: keyword
  - handler: Ring handler function

  Returns: Ring handler function."
  [config feature handler]
  (fn [req]
    (if (enabled? config feature)
      (handler req)
      (not-enabled-response config feature))))

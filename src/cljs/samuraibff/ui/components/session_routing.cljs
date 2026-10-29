(ns samuraibff.ui.components.session-routing
  "Record page routing settings: which workflows and webhooks receive a new
  session, and a short summary of those destinations.

  The panels edit `workflow_overrides` and `webhook_overrides` in
  `samuraibff.ui.store/session*`, sent with `POST /api/sessions`.

  Public API:
  - `workflow-routing-panel`, `webhook-routing-panel`, `destinations-summary`"
  (:require
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.destinations :as destinations]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(defn workflow-routing-panel
  "Advanced panel for per-session workflow routing overrides.

  This panel controls `workflow_overrides` sent to `POST /api/sessions`.

  Returns: hiccup."
  []
  (let [session (hooks/use-atom store/session*)

        workflows-st (hooks/use-atom store/workflows*)
        {:keys [items loading? error]} workflows-st

        defaults-st (hooks/use-atom store/workflow-defaults*)
        defaults-ids (set (map str (or (:workflow_ids defaults-st) [])))
        defaults-loading? (true? (:loading? defaults-st))
        defaults-error (:error defaults-st)

        overrides (or (:workflow_overrides session) {})
        use-defaults? (if (contains? overrides :use_defaults)
                        (boolean (:use_defaults overrides))
                        true)
        selected-ids (set (or (:workflow_ids overrides) #{}))

        refresh-workflows! (fn []
                             (store/set-workflows-loading! true)
                             (store/set-workflows-error! nil)
                             (-> (api/list-workflows!)
                                 (.then (fn [resp]
                                          (store/set-workflows-items! (:items resp))))
                                 (.catch (fn [e]
                                           (store/set-workflows-error! (shared/safe-http-error e))))
                                 (.finally (fn []
                                             (store/set-workflows-loading! false)))))

        refresh-defaults! (fn []
                            (store/set-workflow-defaults-loading! true)
                            (store/set-workflow-defaults-error! nil)
                            (-> (api/get-workflow-defaults!)
                                (.then (fn [resp]
                                         (store/set-workflow-defaults-ids! (:workflow_ids resp))))
                                (.catch (fn [e]
                                          (store/set-workflow-defaults-error! (shared/safe-http-error e))))
                                (.finally (fn []
                                            (store/set-workflow-defaults-loading! false)))))

        refresh! (fn []
                   (refresh-workflows!)
                   (refresh-defaults!))

        checkbox-row (fn [{:keys [id label checked disabled? on-change]}]
                       [:div {:class "checkbox-row"}
                        [:input {:id id
                                 :type "checkbox"
                                 :disabled (boolean disabled?)
                                 :checked (boolean checked)
                                 :on-change (fn [e]
                                              (when (fn? on-change)
                                                (on-change (.. e -target -checked))))}]
                        [:label {:htmlFor id} label]])]

    (react/useEffect
     (fn []
       (when (and (empty? (vec (or items [])))
                  (not (true? loading?)))
         (refresh-workflows!))
       (when (and (empty? (or (:workflow_ids defaults-st) []))
                  (not (true? defaults-loading?)))
         (refresh-defaults!))
       js/undefined)
     #js [])

    [:div {:class "stream-controls-body"}
     (when (seq error)
       [:div {:class "error" :style {:marginBottom "8px"}} error])

     (when (seq defaults-error)
       [:div {:class "error" :style {:marginBottom "8px"}} defaults-error])

     [checkbox-row {:id "wf-use-defaults"
                    :label "Use organization default workflows"
                    :checked (true? use-defaults?)
                    :on-change (fn [v] (store/set-session-workflow-overrides-use-defaults! v))}]

     [:div {:class "muted" :style {:marginTop "6px" :marginBottom "8px"}}
      "Select additional workflows for this session (or disable defaults)."]

     [:div {:style {:display "flex" :gap "8px" :marginBottom "8px"}}
      [:button {:class "btn"
                :type "button"
                :disabled (boolean (or loading? defaults-loading?))
                :on-click (fn [_] (refresh!))}
       (if (or loading? defaults-loading?) "Loading…" "Refresh")]]

     (cond
       (true? loading?)
       [:div {:class "muted"} "Loading workflows…"]

       (empty? (vec (or items [])))
       [:div {:class "muted"}
        "No workflows configured yet. Create one under Settings → Workflows."]

       :else
       (let [default-selected? (fn [id]
                                 (and (true? use-defaults?)
                                      (contains? defaults-ids (str id))))
             additional-selected? (fn [id]
                                    (contains? selected-ids (str id)))]
         [:div {:style {:display "flex" :flexDirection "column" :gap "6px"}}
          (for [{:keys [id name enabled trigger]} (vec (or items []))]
            (let [id (str (or id ""))
                  enabled? (true? enabled)
                  checked? (or (default-selected? id)
                               (additional-selected? id))
                  label [:span
                         [:span (or name "")]
                         (when-not enabled?
                           [:span {:class "muted" :style {:marginLeft "6px"}} "(disabled)"])
                         [:span {:class "muted" :style {:marginLeft "8px"}}
                          (str (:type trigger)
                               (when-let [track (:track_id trigger)] (str " / " track)))]]]
              [:div {:key (str "wf-ov-" id)}
               [checkbox-row {:id (str "wf-ov-" id)
                              :label label
                              :disabled? (not enabled?)
                              :checked checked?
                              :on-change (fn [v]
                                           (store/set-session-workflow-id-selected! id v))}]]))]))

     [:div {:class "muted" :style {:marginTop "10px" :fontSize "12px"}}
      "Applies to new sessions. Existing sessions keep their original settings."]]))

(defn webhook-routing-panel
  "Advanced panel for per-session webhook routing overrides.

  This panel matches the styling + minimize behavior of Stream settings.

  It controls `webhook_overrides` sent to `POST /api/sessions`.

  Returns: hiccup."
  []
  (let [session (hooks/use-atom store/session*)
        controls (or (:controls session) {})
        refined-output? (true? (:refined controls))

        webhooks-st (hooks/use-atom store/webhooks*)
        {:keys [items loading? error]} webhooks-st

        defaults-st (hooks/use-atom store/webhook-defaults*)
        defaults-ids (set (map str (or (:webhook_ids defaults-st) [])))
        defaults-loading? (true? (:loading? defaults-st))
        defaults-error (:error defaults-st)

        overrides (or (:webhook_overrides session) {})
        use-defaults? (if (contains? overrides :use_defaults)
                        (boolean (:use_defaults overrides))
                        true)
        selected-ids (set (or (:webhook_ids overrides) #{}))

        effective-ids (cond-> #{}
                        (true? use-defaults?) (into defaults-ids)
                        true (into selected-ids))
        effective-webhooks (->> (vec (or items []))
                                (filter (fn [w]
                                          (and (true? (:enabled w))
                                               (contains? effective-ids (str (:id w))))))
                                vec)
        refined-webhook-selected?
        (boolean
         (some (fn [w]
                 (let [subs (set (map str (or (:subscriptions w) [])))]
                   (contains? subs "transcript.refined.segment")))
               effective-webhooks))
        show-consolidation?
        (and refined-output? refined-webhook-selected?)

        refresh-webhooks! (fn []
                            (store/set-webhooks-loading! true)
                            (store/set-webhooks-error! nil)
                            (-> (api/list-webhooks!)
                                (.then (fn [resp]
                                         (store/set-webhooks-items! (:items resp))))
                                (.catch (fn [e]
                                          (store/set-webhooks-error! (shared/safe-http-error e))))
                                (.finally (fn []
                                            (store/set-webhooks-loading! false)))))
        refresh-defaults! (fn []
                            (store/set-webhook-defaults-loading! true)
                            (store/set-webhook-defaults-error! nil)
                            (-> (api/get-webhook-defaults!)
                                (.then (fn [resp]
                                         (store/set-webhook-defaults-ids! (:webhook_ids resp))))
                                (.catch (fn [e]
                                          (store/set-webhook-defaults-error! (shared/safe-http-error e))))
                                (.finally (fn []
                                            (store/set-webhook-defaults-loading! false)))))
        refresh! (fn []
                   (refresh-webhooks!)
                   (refresh-defaults!))
        checkbox-row (fn [{:keys [id label checked disabled? on-change]}]
                       [:div {:class "checkbox-row"}
                        [:input {:id id
                                 :type "checkbox"
                                 :disabled (boolean disabled?)
                                 :checked (boolean checked)
                                 :on-change (fn [e]
                                              (when (fn? on-change)
                                                (on-change (.. e -target -checked))))}]
                        [:label {:htmlFor id} label]])]

    (react/useEffect
     (fn []
       (when (and (empty? (or items []))
                  (not (true? loading?)))
         (refresh-webhooks!))
       (when (and (empty? (or (:webhook_ids defaults-st) []))
                  (not (true? defaults-loading?)))
         (refresh-defaults!))
       js/undefined)
     #js [])

    ;; If consolidation UI is not applicable, force-disable it in store.
    (react/useEffect
     (fn []
       (when-not show-consolidation?
         (store/set-session-refined-consolidation-enabled! false))
       js/undefined)
     #js [show-consolidation?])

    [:div {:class "stream-controls-body"}
     (when (seq error)
       [:div {:class "error" :style {:marginBottom "8px"}} error])

     (when (seq defaults-error)
       [:div {:class "error" :style {:marginBottom "8px"}} defaults-error])

     [checkbox-row {:id "wh-use-defaults"
                    :label "Use organization default webhooks"
                    :checked (true? use-defaults?)
                    :on-change (fn [v] (store/set-session-webhook-overrides-use-defaults! v))}]

     [:div {:class "muted" :style {:marginTop "6px" :marginBottom "8px"}}
      "Select additional webhooks for this session (or disable defaults)."]

     [:div {:style {:display "flex" :gap "8px" :marginBottom "8px"}}
      [:button {:class "btn"
                :type "button"
                :disabled (boolean (or loading? defaults-loading?))
                :on-click (fn [_] (refresh!))}
       (if (or loading? defaults-loading?) "Loading…" "Refresh")]]

     (cond
       (true? loading?)
       [:div {:class "muted"} "Loading webhooks…"]

       (empty? (vec (or items [])))
       [:div {:class "muted"}
        "No webhooks configured yet. Create one under Settings → Webhooks."]

       :else
       [:div {:style {:display "flex" :flexDirection "column" :gap "6px"}}
        (for [{:keys [id name url enabled]} (vec (or items []))]
          (let [id (str (or id ""))
                enabled? (true? enabled)
                checked? (contains? selected-ids id)
                label [:span
                       [:span (or name "")]
                       (when-not enabled?
                         [:span {:class "muted" :style {:marginLeft "6px"}} "(disabled)"])
                       [:span {:class "muted" :style {:marginLeft "8px"}} (or url "")]]]
            [:div {:key (str "wh-ov-" id)}
             [checkbox-row {:id (str "wh-ov-" id)
                            :label label
                            :disabled? (not enabled?)
                            :checked checked?
                            :on-change (fn [v]
                                         (store/set-session-webhook-id-selected! id v))}]]))])

     (when show-consolidation?
       (let [enabled? (true? (get-in session [:session_settings :refined_transcript :consolidation :enabled]))]
         [:div {:style {:marginTop "14px"}}
          [:div {:class "label" :style {:marginBottom "6px"}}
           "Refined transcript delivery"]
          [checkbox-row {:id "wh-refined-consolidation"
                         :label "Include earlier transcript text in each update"
                         :checked enabled?
                         :on-change (fn [v]
                                      (store/set-session-refined-consolidation-enabled! v))}]
          [:div {:class "hint" :style {:marginTop "6px"}}
           "Only applies to webhooks subscribed to transcript.refined.segment."]]))

     [:div {:class "muted" :style {:marginTop "10px" :fontSize "12px"}}
      "Applies to new sessions. Existing sessions keep their original settings."]]))

(defn- load-routing-lists!
  "Load workflow and webhook lists and defaults that are not loaded yet.

  Inputs: current store maps for webhooks, webhook defaults, workflows and
  workflow defaults. Returns: nil. Errors go to the same store error fields
  the routing panels show."
  [webhooks-st wh-defaults-st workflows-st wf-defaults-st]
  (letfn [(load! [st set-loading! set-error! fetch! on-ok]
            (when (and (not (true? (:loading? st))) (nil? (:error st)))
              (set-loading! true)
              (-> (fetch!)
                  (.then on-ok)
                  (.catch (fn [e] (set-error! (shared/safe-http-error e))))
                  (.finally (fn [] (set-loading! false))))))]
    (when (empty? (:items webhooks-st))
      (load! webhooks-st store/set-webhooks-loading! store/set-webhooks-error!
             api/list-webhooks! #(store/set-webhooks-items! (:items %))))
    (when (empty? (:webhook_ids wh-defaults-st))
      (load! wh-defaults-st store/set-webhook-defaults-loading! store/set-webhook-defaults-error!
             api/get-webhook-defaults! #(store/set-webhook-defaults-ids! (:webhook_ids %))))
    (when (empty? (:items workflows-st))
      (load! workflows-st store/set-workflows-loading! store/set-workflows-error!
             api/list-workflows! #(store/set-workflows-items! (:items %))))
    (when (empty? (:workflow_ids wf-defaults-st))
      (load! wf-defaults-st store/set-workflow-defaults-loading! store/set-workflow-defaults-error!
             api/get-workflow-defaults! #(store/set-workflow-defaults-ids! (:workflow_ids %)))))
  nil)

(defn destinations-summary
  "Show which webhooks and workflows will receive a new session.

  Uses the same rule as session creation: organization defaults (when
  enabled) plus selected items, enabled items only. Shows webhook destination
  hosts and workflow models so the recorder sees where results go without
  opening Session settings. Renders nothing when the runtime is disabled.
  Subscribes to the auth store so it appears once `/api/me` reports the
  runtime, even when it first rendered before that response.

  Returns: hiccup or nil."
  []
  (let [session (hooks/use-atom store/session*)
        webhooks-st (hooks/use-atom store/webhooks*)
        wh-defaults-st (hooks/use-atom store/webhook-defaults*)
        workflows-st (hooks/use-atom store/workflows*)
        wf-defaults-st (hooks/use-atom store/workflow-defaults*)
        auth (hooks/use-atom store/auth*)
        runtime-enabled? (true? (get-in auth [:detail :features :workflow_webhook_runtime_enabled]))
        webhooks (destinations/effective-items (:items webhooks-st) (:webhook_overrides session)
                                               (:webhook_ids wh-defaults-st) :webhook_ids)
        workflows (destinations/effective-items (:items workflows-st) (:workflow_overrides session)
                                                (:workflow_ids wf-defaults-st) :workflow_ids)
        loading? (some #(true? (:loading? %)) [webhooks-st wh-defaults-st workflows-st wf-defaults-st])]
    (react/useEffect
     (fn []
       (when runtime-enabled?
         (load-routing-lists! @store/webhooks* @store/webhook-defaults*
                              @store/workflows* @store/workflow-defaults*))
       js/undefined)
     #js [runtime-enabled?])
    (when runtime-enabled?
      [:div {:class "field" :style {:minWidth 0}}
       [:div {:class "label"} "Results are sent to"]
       (cond
         (or (seq webhooks) (seq workflows))
         [:div {:style {:display "flex" :flexDirection "column" :gap "2px" :fontSize "13px"}}
          (for [w webhooks]
            [:span {:key (str "dest-wh-" (:id w))} (str "Webhook: " (destinations/webhook-label w))])
          (for [w workflows]
            [:span {:key (str "dest-wf-" (:id w))} (str "Workflow: " (destinations/workflow-label w))])]

         loading?
         [:div {:class "muted"} "Checking destinations…"]

         :else
         [:div {:class "muted"} "No webhooks or workflows receive this session."])
       [:div {:class "hint" :style {:marginTop "2px"}}
        "Change this in Session settings → Webhooks / Workflows."]])))

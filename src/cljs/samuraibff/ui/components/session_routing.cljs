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

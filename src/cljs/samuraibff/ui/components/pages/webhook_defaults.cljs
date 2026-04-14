(ns samuraibff.ui.components.pages.webhook-defaults
  "Webhook defaults management page.

  This page configures which webhook IDs are applied by default to newly
  created sessions (when `webhook_overrides.use_defaults` is true or omitted).
  "
  (:require
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(defn- checkbox
  [{:keys [checked? disabled? on-change]}]
  [:input {:type "checkbox"
           :checked (boolean checked?)
           :disabled (boolean disabled?)
           :on-change (fn [e]
                        (when (fn? on-change)
                          (on-change (true? (.. e -target -checked)))))}])

(defn webhook-defaults-page
  "Webhook defaults page." 
  []
  (let [webhooks-st (hooks/use-atom store/webhooks*)
        defaults-st (hooks/use-atom store/webhook-defaults*)
        {:keys [items]} webhooks-st
        items (vec (or items []))

        selected* (react/useState (set (map str (or (:webhook_ids defaults-st) []))))
        selected (aget selected* 0)
        set-selected! (aget selected* 1)

        saving?* (react/useState false)
        saving? (aget saving?* 0)
        set-saving! (aget saving?* 1)

        refresh! (fn []
                   ;; Load defaults + webhooks list in parallel, but keep the UI state simple.
                   (store/set-webhook-defaults-loading! true)
                   (store/set-webhook-defaults-error! nil)
                   (store/set-webhooks-loading! true)
                   (store/set-webhooks-error! nil)

                   (-> (api/get-webhook-defaults!)
                       (.then (fn [resp]
                                (store/set-webhook-defaults-ids! (:webhook_ids resp))
                                (set-selected! (set (map str (or (:webhook_ids resp) []))))))
                       (.catch (fn [e]
                                 (store/set-webhook-defaults-error! (shared/safe-http-error e))))
                       (.finally (fn []
                                   (store/set-webhook-defaults-loading! false))))

                   (-> (api/list-webhooks!)
                       (.then (fn [resp]
                                (store/set-webhooks-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/set-webhooks-error! (shared/safe-http-error e))))
                       (.finally (fn []
                                   (store/set-webhooks-loading! false)))))

        save! (fn []
                (set-saving! true)
                (store/set-webhook-defaults-error! nil)
                (-> (api/set-webhook-defaults! (vec (sort selected)))
                    (.then (fn [_]
                             (store/set-webhook-defaults-ids! (vec (sort selected)))))
                    (.catch (fn [e]
                              (store/set-webhook-defaults-error! (shared/safe-http-error e))))
                    (.finally (fn []
                                (set-saving! false)))))

        toggle! (fn [id checked?]
                  (let [id (str id)]
                    (set-selected!
                     (fn [s]
                       (let [s (set (or s #{}))]
                         (if checked?
                           (conj s id)
                           (disj s id)))))))

        defaults-error (:error defaults-st)
        webhooks-error (:error webhooks-st)
        any-error (or defaults-error webhooks-error)
        loading? (or (:loading? defaults-st) (:loading? webhooks-st))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])

    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Webhook Defaults"]
       [:div {:class "muted"}
        "Choose which webhook endpoints apply to newly created sessions by default."]
       (when (seq any-error)
         [:div {:class "badge bad" :style {:marginTop "10px"}} any-error])]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn primary"
                 :disabled (or saving? (empty? items))
                 :on-click (fn [_] (save!))}
        (if saving? "Saving…" "Save")]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Default webhooks"]
      (if (empty? items)
        [:div {:class "muted"}
         "No webhooks exist yet. Create a webhook first under the Webhooks page."]
        [:div {:style {:display "flex" :flexDirection "column" :gap "8px"}}
         (for [{:keys [id name url enabled]} items]
           ^{:key (str "default-wh-" id)}
           [:label {:class "card"
                    :style {:padding "10px"
                            :display "flex"
                            :gap "10px"
                            :alignItems "flex-start"}}
            [checkbox {:checked? (contains? selected (str id))
                       :on-change (fn [checked?] (toggle! id checked?))}]
            [:div {:style {:flex "1" :minWidth 0}}
             [:div {:style {:display "flex" :gap "10px" :alignItems "center"}}
              [:div {:style {:fontWeight 600}} (or name "")]
              (if enabled
                [:span {:class "badge ok"} "Enabled"]
                [:span {:class "badge muted"} "Disabled"]) ]
             [:div {:class "mono muted" :style {:wordBreak "break-all"}} (or url "")]]])])]

     [:div {:class "hint"}
      "These defaults are resolved at session creation time and published into sessions.meta."
      " Changing defaults does not affect already-started sessions."]]))

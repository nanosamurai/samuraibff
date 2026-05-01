(ns samuraibff.ui.components.pages.workflow-defaults
  "Workflow defaults management page.

  Configures which workflow IDs are applied by default to newly created sessions
  (when `workflow_overrides.use_defaults` is true or omitted)."
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

(defn workflow-defaults-page
  "Workflow defaults page." 
  []
  (let [workflows-st (hooks/use-atom store/workflows*)
        defaults-st (hooks/use-atom store/workflow-defaults*)
        {:keys [items]} workflows-st
        items (vec (or items []))

        selected* (react/useState (set (map str (or (:workflow_ids defaults-st) []))))
        selected (aget selected* 0)
        set-selected! (aget selected* 1)

        saving?* (react/useState false)
        saving? (aget saving?* 0)
        set-saving! (aget saving?* 1)

        refresh! (fn []
                   (store/set-workflow-defaults-loading! true)
                   (store/set-workflow-defaults-error! nil)
                   (store/set-workflows-loading! true)
                   (store/set-workflows-error! nil)

                   (-> (api/get-workflow-defaults!)
                       (.then (fn [resp]
                                (store/set-workflow-defaults-ids! (:workflow_ids resp))
                                (set-selected! (set (map str (or (:workflow_ids resp) []))))))
                       (.catch (fn [e]
                                 (store/set-workflow-defaults-error! (shared/safe-http-error e))))
                       (.finally (fn []
                                   (store/set-workflow-defaults-loading! false))))

                   (-> (api/list-workflows!)
                       (.then (fn [resp]
                                (store/set-workflows-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/set-workflows-error! (shared/safe-http-error e))))
                       (.finally (fn []
                                   (store/set-workflows-loading! false)))))

        save! (fn []
                (set-saving! true)
                (store/set-workflow-defaults-error! nil)
                (-> (api/set-workflow-defaults! (vec (sort selected)))
                    (.then (fn [_]
                             (store/set-workflow-defaults-ids! (vec (sort selected)))))
                    (.catch (fn [e]
                              (store/set-workflow-defaults-error! (shared/safe-http-error e))))
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
        workflows-error (:error workflows-st)
        any-error (or defaults-error workflows-error)
        loading? (or (:loading? defaults-st) (:loading? workflows-st))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])

    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Workflow Defaults"]
       [:div {:class "muted"}
        "Choose which workflows apply to newly created sessions by default."]
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
      [:div {:class "card-title"} "Default workflows"]
      (if (empty? items)
        [:div {:class "muted"}
         "No workflows exist yet. Create a workflow first under the Workflows page."]
        [:div {:style {:display "flex" :flexDirection "column" :gap "8px"}}
         (for [{:keys [id name enabled]} items]
           ^{:key (str "default-wf-" id)}
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
                [:span {:class "badge muted"} "Disabled"])]]])])]

     [:div {:class "hint"}
      "Defaults are resolved at session creation time and published into sessions.meta."
      " Changing defaults does not affect already-started sessions."]]))

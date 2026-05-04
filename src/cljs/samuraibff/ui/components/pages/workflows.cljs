(ns samuraibff.ui.components.pages.workflows
  "Workflows management page (tenant-scoped LLM post-processing workflows)."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(def ^:private trigger-types
  [{:value "transcript.refined.segment" :label "transcript.refined.segment"}
   {:value "transcript.final.ready" :label "transcript.final.ready"}
   {:value "recording.finished" :label "recording.finished"}])

(def ^:private refined-trigger-type
  "Event type representing refined transcript segments (frequent events)."
  "transcript.refined.segment")

(defn- checkbox
  [{:keys [checked? disabled? on-change]}]
  [:input {:type "checkbox"
           :checked (boolean checked?)
           :disabled (boolean disabled?)
           :on-change (fn [e]
                        (when (fn? on-change)
                          (on-change (true? (.. e -target -checked)))))}])

(defn- try-parse-json
  "Parse a JSON string into a JS object; throws on failure."
  [s]
  (let [s0 (some-> s str str/trim)]
    (if (or (nil? s0) (= "" s0))
      #js {}
      (.parse js/JSON s0))))

(defn- workflow-form-modal
  "Create/edit workflow modal.

  Inputs:
  - open?: boolean
  - mode: :create | :edit
  - initial: workflow map or nil
  - on-close: fn
  - on-saved: fn

  Default behavior:
  - default?: boolean (whether current workflow is in tenant defaults)
  - default-disabled?: boolean (disable default checkbox, e.g. while saving)
  - on-toggle-default: (fn [workflow-id checked?])"
  [{:keys [open? mode initial on-close on-saved
           default? default-disabled? on-toggle-default]}]
  (when (true? open?)
    (let [initial (or initial {})
          workflow-id (:id initial)
          can-toggle-default? (and (= mode :edit) (some? workflow-id))

          name* (react/useState (or (:name initial) ""))
          name (aget name* 0)
          set-name! (aget name* 1)

          enabled?* (react/useState (boolean (if (contains? initial :enabled) (:enabled initial) true)))
          enabled? (aget enabled?* 0)
          set-enabled! (aget enabled?* 1)

          trigger* (react/useState (or (get-in initial [:trigger :type]) "transcript.refined.segment"))
          trigger (aget trigger* 0)
          set-trigger! (aget trigger* 1)

          model-id* (react/useState (or (get-in initial [:provider :model_id]) ""))
          model-id (aget model-id* 0)
          set-model-id! (aget model-id* 1)

          params-raw* (react/useState
                       (let [m (or (get-in initial [:provider :params]) {})]
                         (try
                           (.stringify js/JSON (clj->js m) nil 2)
                           (catch :default _ "{}"))))
          params-raw (aget params-raw* 0)
          set-params-raw! (aget params-raw* 1)

          prompt* (react/useState (or (get-in initial [:prompt :text]) ""))
          prompt (aget prompt* 0)
          set-prompt! (aget prompt* 1)

          cumulative-enabled?*
          (react/useState (boolean (get-in initial [:incremental :enabled] false)))
          cumulative-enabled? (aget cumulative-enabled?* 0)
          set-cumulative-enabled! (aget cumulative-enabled?* 1)

          dispatch-throttle-interval-sec-raw*
          (react/useState (str (or (get-in initial [:incremental :min_interval_sec]) "")))
          dispatch-throttle-interval-sec-raw (aget dispatch-throttle-interval-sec-raw* 0)
          set-dispatch-throttle-interval-sec-raw! (aget dispatch-throttle-interval-sec-raw* 1)

          saving?* (react/useState false)
          saving? (aget saving?* 0)
          set-saving! (aget saving?* 1)

          err* (react/useState nil)
          err (aget err* 0)
          set-err! (aget err* 1)

          save! (fn []
                  (set-saving! true)
                  (set-err! nil)
                  (try
                    (let [params-js (try-parse-json params-raw)
                          params (js->clj params-js :keywordize-keys true)
                          min-int (let [s (some-> dispatch-throttle-interval-sec-raw str str/trim)]
                                    (when (seq s)
                                      (js/parseInt s 10)))
                          payload {:name (str/trim (str name))
                                   :enabled (boolean enabled?)
                                   :trigger {:type trigger}
                                   :provider {:type "bedrock"
                                              :model_id (str/trim (str model-id))
                                              :params params}
                                   :prompt {:text (str prompt)}

                                   ;; Note: the field is called :incremental for compatibility with
                                   ;; webhook-router sessions.meta model, but the UI intentionally
                                   ;; presents it as cumulative transcript + dispatch throttling.
                                   :incremental {:enabled (boolean cumulative-enabled?)
                                                 :min_interval_sec (when (and (number? min-int) (<= 0 min-int)) min-int)}}
                          p (if (= mode :edit)
                              (api/update-workflow! (:id initial) payload)
                              (api/create-workflow! payload))]
                      (-> p
                          (.then (fn [_]
                                   (when (fn? on-saved) (on-saved))
                                   (when (fn? on-close) (on-close))))
                          (.catch (fn [e]
                                    (set-err! (shared/safe-http-error e))))
                          (.finally (fn []
                                      (set-saving! false)))))
                    (catch :default e
                      (set-err! (str "Invalid JSON in provider params: " (.-message e)))
                      (set-saving! false))))]
      [:div {:class "modal-overlay"
             :on-click (fn [_] (when (fn? on-close) (on-close)))}
       [:div {:class "modal"
              :style {:maxWidth "860px"}
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "modal-title"}
         (if (= mode :edit) "Edit workflow" "New workflow")]

        (when (seq err)
          [:div {:class "badge bad" :style {:marginBottom "10px"}} err])

         [:div {:class "card"}
          [:div {:class "card-title"} "Basics"]
          [:div {:class "row"}
          [:input {:placeholder "Name"
                   :value name
                   :on-change (fn [e] (set-name! (.. e -target -value)))}]
          [:select {:value trigger
                    :on-change (fn [e] (set-trigger! (.. e -target -value)))}
           (for [{:keys [value label]} trigger-types]
             ^{:key (str "tr-" value)}
             [:option {:value value} label])]]

          (when (= trigger refined-trigger-type)
            [:div {:style {:marginTop "10px"}}
             [:div {:class "label" :style {:marginBottom "6px"}}
              "Refined transcript"]
             [:div {:class "muted" :style {:fontSize "12px" :marginBottom "8px"}}
              "Controls how this workflow is triggered from refined transcript segments."]

             [:label {:class "muted" :style {:display "flex" :gap "8px" :alignItems "center"}}
              [checkbox {:checked? cumulative-enabled? :on-change set-cumulative-enabled!}]
              "Send cumulative transcript (rolling tail)"]

             [:div {:class "row" :style {:marginTop "8px"}}
              [:input {:placeholder "Dispatch throttling interval (sec) (e.g. 15)"
                       :disabled (not cumulative-enabled?)
                       :value dispatch-throttle-interval-sec-raw
                       :on-change (fn [e]
                                    (set-dispatch-throttle-interval-sec-raw! (.. e -target -value)))}]]
             [:div {:class "hint" :style {:marginTop "6px"}}
              "Optional. When set, webhook-router will avoid triggering this workflow more often than the given interval (per session)."]])

         [:div {:class "row" :style {:gap "16px" :alignItems "center" :marginTop "8px"}}
          [:label {:class "muted" :style {:display "flex" :gap "8px" :alignItems "center"}}
           [checkbox {:checked? enabled? :on-change set-enabled!}]
           "Enabled"]

          [:label {:class "muted"
                   :title (when-not can-toggle-default?
                            "Default can be set after the workflow is created")
                   :style {:display "flex" :gap "8px" :alignItems "center"}}
           [checkbox {:checked? (true? default?)
                      :disabled? (or (not can-toggle-default?) (boolean default-disabled?))
                      :on-change (fn [checked?]
                                   (when (and can-toggle-default? (fn? on-toggle-default))
                                     (on-toggle-default workflow-id checked?)))}]
           "Default"]]]

        [:div {:class "card"}
         [:div {:class "card-title"} "Provider (Bedrock)"]
         [:div {:class "row"}
          [:input {:placeholder "Model id (e.g. anthropic.claude-3-5-sonnet-20240620-v1:0)"
                   :value model-id
                   :on-change (fn [e] (set-model-id! (.. e -target -value)))}]]
         [:div {:class "hint" :style {:marginTop "6px"}}
          "Params must be valid JSON object."]
         [:textarea {:rows 8
                     :style {:width "100%"}
                     :value params-raw
                     :on-change (fn [e] (set-params-raw! (.. e -target -value)))}]]

        [:div {:class "card"}
         [:div {:class "card-title"} "Prompt"]
         [:textarea {:rows 8
                     :style {:width "100%"}
                     :placeholder "Prompt text…"
                     :value prompt
                     :on-change (fn [e] (set-prompt! (.. e -target -value)))}]]

        [:div {:class "row" :style {:justifyContent "flex-end" :marginTop "10px"}}
         [:button {:class "btn"
                   :disabled saving?
                   :on-click (fn [_] (when (fn? on-close) (on-close)))}
          "Cancel"]
         [:button {:class "btn primary"
                   :disabled saving?
                   :on-click (fn [_] (save!))}
          (if saving? "Saving…" "Save")]]]])))

(defn workflows-page
  "Workflows management page."
  []
  (let [st (hooks/use-atom store/workflows*)
        defaults-st (hooks/use-atom store/workflow-defaults*)

        {:keys [items loading? error]} st
        defaults-ids (set (map str (or (:workflow_ids defaults-st) [])))
        defaults-loading? (true? (:loading? defaults-st))
        defaults-error (:error defaults-st)

        open?* (react/useState false)
        open? (aget open?* 0)
        set-open! (aget open?* 1)

        mode* (react/useState :create)
        mode (aget mode* 0)
        set-mode! (aget mode* 1)

        editing* (react/useState nil)
        editing (aget editing* 0)
        set-editing! (aget editing* 1)

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

        delete! (fn [workflow-id]
                  (store/set-workflows-loading! true)
                  (store/set-workflows-error! nil)
                  (-> (api/delete-workflow! workflow-id)
                      (.then (fn [_]
                               (store/remove-workflow-item! workflow-id)))
                      (.catch (fn [e]
                                (store/set-workflows-error! (shared/safe-http-error e))))
                      (.finally (fn []
                                  (store/set-workflows-loading! false)))))

        toggle-default! (fn [workflow-id checked?]
                          (let [id (str (or workflow-id ""))
                                prev-ids (vec (or (:workflow_ids defaults-st) []))
                                prev-set (set (map str prev-ids))
                                next-set (if (true? checked?)
                                           (conj prev-set id)
                                           (disj prev-set id))
                                next-ids (vec (sort next-set))]
                            (store/set-workflow-defaults-loading! true)
                            (store/set-workflow-defaults-error! nil)
                            ;; optimistic UI
                            (store/set-workflow-defaults-ids! next-ids)
                            (-> (api/set-workflow-defaults! next-ids)
                                (.catch (fn [e]
                                          (store/set-workflow-defaults-ids! prev-ids)
                                          (store/set-workflow-defaults-error! (shared/safe-http-error e))))
                                (.finally (fn []
                                            (store/set-workflow-defaults-loading! false))))))

        open-create! (fn []
                       (set-mode! :create)
                       (set-editing! nil)
                       (set-open! true))
        open-edit! (fn [wf]
                     (set-mode! :edit)
                     (set-editing! wf)
                     (set-open! true))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])

    [:div {:class "page"}
     [workflow-form-modal {:open? open?
                           :mode mode
                           :initial editing
                            :default? (contains? defaults-ids (str (:id editing)))
                            :default-disabled? defaults-loading?
                            :on-toggle-default toggle-default!
                           :on-close (fn [] (set-open! false))
                           :on-saved refresh!}]
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Workflows"]
       [:div {:class "muted"}
        "Define LLM workflows (prompts + model) that can be triggered from session events."]
        (when (seq (or error defaults-error))
          [:div {:class "badge bad" :style {:marginTop "10px"}} (or error defaults-error)])]
      [:div {:class "row"}
       [:button {:class "btn"
                  :disabled (boolean (or loading? defaults-loading?))
                 :on-click (fn [_] (refresh!))}
         (if (or loading? defaults-loading?) "Refreshing…" "Refresh")]
       [:button {:class "btn primary"
                 :on-click (fn [_] (open-create!))}
        "New workflow"]]]

     (if (empty? (vec (or items [])))
       [:div {:class "muted"} "No workflows configured yet."]
       [:table {:class "table"}
        [:thead
         [:tr
          [:th "Name"]
           [:th "Default"]
          [:th "Enabled"]
          [:th "Trigger"]
          [:th "Model"]
          [:th "Actions"]]]
        [:tbody
         (for [wf (vec (or items []))]
           (let [{:keys [id name enabled trigger provider]} wf]
             ^{:key (str "wf-" id)}
             [:tr
              [:td (or name "")]
               [:td
                [checkbox {:checked? (contains? defaults-ids (str id))
                           :disabled? defaults-loading?
                           :on-change (fn [checked?]
                                        (toggle-default! id checked?))}]]
              [:td (if enabled "Yes" "No")]
              [:td [:span {:class "mono"} (or (get trigger :type) "")]]
              [:td [:span {:class "mono"} (or (get provider :model_id) "")]]
              [:td
               [:div {:class "row"}
                [:button {:class "btn"
                          :on-click (fn [_] (open-edit! wf))}
                 "Edit"]
                [:button {:class "btn danger"
                          :on-click (fn [_] (delete! id))}
                 "Delete"]]]]))]])]))

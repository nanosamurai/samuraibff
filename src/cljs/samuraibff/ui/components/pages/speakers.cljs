(ns samuraibff.ui.components.pages.speakers
  "Speakers (enrollment) management page." 
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(def ^:private mobile-breakpoint-query
  "CSS media query used as the threshold for mobile layout.

  Must match the @media rule in `resources/public/index.html`."
  "(max-width: 768px)")

(defn- speaker-row
  [{:keys [id label created_at created_at_ms]} on-delete]
  [:tr
   [:td {:class "mono"} id]
   [:td label]
   [:td {:class "muted"}
    (or created_at
        (when created_at_ms (.toLocaleString (js/Date. created_at_ms)))
        "")]
   [:td {:style {:textAlign "right"}}
    [:button {:class "btn ghost"
              :on-click (fn [_]
                          (on-delete id))}
     "Delete"]]])

(defn- speaker-card
  "Render a speaker row as a stacked card (mobile layout).

  Inputs:
  - speaker: {:id string :label string :created_at? string :created_at_ms? number}
  - on-delete: (fn [speaker-id])

  Returns: hiccup." 
  [{:keys [id label created_at created_at_ms]} on-delete]
  (let [created (or created_at
                    (when created_at_ms (.toLocaleString (js/Date. created_at_ms)))
                    "")]
    [:div {:class "list-item"}
     [:div {:style {:display "flex" :gap "10px" :alignItems "flex-start"}}
      [:div {:style {:flex "1" :minWidth 0}}
       [:div {:class "list-item-title"} (or label "")]
       [:div {:class "list-item-sub mono"} (or id "")]
       [:div {:class "list-item-meta"}
        [:span (str "Created: " (if (seq created) created "—"))]]]
      [:div {:class "list-item-actions"}
       [:button {:class "btn ghost"
                 :on-click (fn [_]
                             (on-delete id))}
        "Delete"]]]]))

(defn speakers-page
  "Enrolled speakers management page." 
  []
  (let [items (hooks/use-atom store/speakers*)
        mobile? (hooks/use-media-query mobile-breakpoint-query)
        label* (react/useState "")
        file* (react/useState nil)
        label (aget label* 0)
        set-label! (aget label* 1)
        sample (aget file* 0)
        set-sample! (aget file* 1)
        loading?* (react/useState false)
        loading? (aget loading?* 0)
        set-loading! (aget loading?* 1)
        refresh! (fn []
                   (set-loading! true)
                   (-> (api/list-speakers!)
                       (.then (fn [xs]
                                (store/set-speakers! (js->clj xs :keywordize-keys true))))
                       (.catch (fn [e]
                                 (store/append-log! (str "[ui] failed loading speakers: " e))))
                       (.finally (fn [] (set-loading! false)))))
        delete! (fn [sid]
                  (when (js/confirm (str "Delete speaker " sid "?"))
                    (-> (api/delete-speaker! sid)
                        (.then (fn [_]
                                 (store/remove-speaker! sid)))
                        (.catch (fn [e]
                                  (store/append-log! (str "[ui] failed deleting speaker: " e)))))))]
    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Speakers"]
       [:div {:class "muted"} "Manage enrolled speakers for diarization."]]
      [:div {:class "row"}
       [:button {:class "btn"
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Add speaker"]
      [:div {:class "row"}
       [:input {:placeholder "Label (e.g. Dr Novak)"
                :value label
                :on-change (fn [e]
                             (set-label! (.. e -target -value)))}]
       [:input {:type "file"
                :accept "audio/wav"
                :on-change (fn [e]
                             (let [f (aget (.. e -target -files) 0)]
                               (set-sample! f)))}]
       [:button {:class "btn primary"
                 :disabled (or loading? (str/blank? label) (nil? sample))
                 :on-click (fn [_]
                             (set-loading! true)
                             (-> (api/create-speaker! label sample)
                                 (.then (fn [resp]
                                          (store/prepend-speaker!
                                           {:id (aget resp "speaker_id")
                                            :label (aget resp "label")
                                            :created_at_ms (.getTime (js/Date.))})
                                          (set-label! "")
                                          (set-sample! nil)))
                                 (.catch (fn [e]
                                           (store/append-log! (str "[ui] failed creating speaker: " e))))
                                 (.finally (fn [] (set-loading! false))))) }
        "Upload"]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Enrolled speakers"]
      (if (empty? items)
        [:div {:class "muted"} "No speakers enrolled yet."]
        (if mobile?
          [:div {:class "list"}
           (for [item items]
             ^{:key (str "speaker-card-" (:id item))}
             [speaker-card item delete!])]
          [:table {:class "table"}
           [:thead
            [:tr
             [:th "Id"]
             [:th "Label"]
             [:th "Created"]
             [:th {:style {:textAlign "right"}} "Actions"]]]
           [:tbody
            (for [item items]
              ^{:key (str "speaker-" (:id item))}
              [speaker-row item delete!])]]))]]))

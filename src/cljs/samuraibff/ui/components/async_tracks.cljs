(ns samuraibff.ui.components.async-tracks
  "Shared asynchronous selectors and full-width result tabs for live/history."
  (:require [samuraibff.ui.api :as api]
            [samuraibff.ui.track-results :as tracks]
            [samuraibff.ui.components.transcript :as transcript]
            ["react" :as react]))

(defn use-index
  "Poll metadata while mounted, serially and without caching tenant data on disk."
  [session-id]
  (let [[state set-state!] (react/useState nil)]
    (react/useEffect
     (fn []
       (let [cancelled? (atom false) timer (atom nil)]
         (set-state! nil)
         (letfn [(refresh! []
                   (when (seq session-id)
                     (-> (api/get-track-index! session-id)
                         (.then (fn [index]
                                  (when-not @cancelled? (set-state! (assoc index :owner session-id)))))
                         (.catch (fn [_]
                                   (when-not @cancelled?
                                     (set-state! (fn [previous] (assoc previous :owner session-id :read-error true))))))
                         (.finally (fn []
                                     (when-not @cancelled? (reset! timer (js/setTimeout refresh! 2000))))))))]
           (refresh!))
         (fn [] (reset! cancelled? true) (js/clearTimeout @timer))))
     #js [session-id])
    (when (= session-id (:owner state)) state)))

(defn track-body
  "Render one track using its own artifact text/timings and the session audio."
  [{:keys [session-id track has-recording?]}]
  (let [[artifacts set-artifacts!] (react/useState {})
        [retry set-retry!] (react/useState 0)
        [current-time set-current-time!] (react/useState 0)
        [follow? set-follow!] (react/useState false)
        audio-ref (react/useRef nil)
        results (tracks/latest-results (:results track))
        signature (pr-str (mapv :result_id results))
        messages (tracks/messages results artifacts)
        timed? (boolean (some #(seq (:words %)) messages))
        errors (filter #(or (= "failed" (:status %)) (get-in artifacts [(:result_id %) :read-error])) results)
        on-time (fn [event] (set-current-time! (.. event -target -currentTime)))]
    (react/useEffect
     (fn []
       (let [cancelled? (atom false)]
         (reduce
          (fn [chain result]
            (.then chain
                   (fn []
                     (when (and (not @cancelled?) (= "succeeded" (:status result))
                                (not (get-in artifacts [(:result_id result) :transcript])))
                       (-> (api/get-track-result! session-id (:result_id result))
                           (.then (fn [value]
                                    (when-not @cancelled?
                                      (set-artifacts! #(assoc % (:result_id result) value)))))
                           (.catch (fn [_]
                                     (when-not @cancelled?
                                       (set-artifacts! #(assoc % (:result_id result) {:read-error true}))))))))))
          (js/Promise.resolve nil) results)
         (fn [] (reset! cancelled? true))))
     #js [session-id signature retry])
    [:div {:class "async-track-body" :data-testid "track-result-panel" :data-track-id (:track_id track)}
     [:div {:class "row"}
      [:strong (:display_name track)]
      [:span {:class "badge" :role "status"} (tracks/status-label track)]]
     (for [result errors]
       [:div {:key (:result_id result) :class "badge bad" :role "alert"}
        (if (= "failed" (:status result))
          (str "Processing failed: " (:error_code result) " · " (:unit_id result))
          "Transcript could not be loaded.")])
     (when (some #(get-in artifacts [(:result_id %) :read-error]) results)
       [:button {:class "btn" :on-click #(set-retry! inc)} "Retry loading transcript"])
     (for [code (distinct (mapcat :degradations results))]
       [:div {:key code :class "hint"} (str "Reduced output: " code)])
     (if has-recording?
       [:div {:class "card"}
        [:div {:class "card-title"} "Playback"]
        [:audio {:controls true :preload "metadata" :src (api/recording-audio-url session-id)
                 :ref audio-ref :on-time-update on-time :on-seeked on-time :style {:width "100%"}}]
        (when timed?
          [:label {:class "checkbox-row"}
           [:input {:type "checkbox" :checked follow? :on-change #(set-follow! (.. % -target -checked))}]
           "Follow playback"])]
       [:div {:class "hint"} "Playback becomes available when a full recording has been stored."])
     (when (and (seq messages) (not timed?))
       [:div {:class "hint"} "Word timings are unavailable for this output."])
     (if (and has-recording? timed?)
       [transcript/final-transcript-karaoke {:messages messages :audio-ref audio-ref
                                             :current-time-s current-time :follow? follow?}]
       [transcript/transcript-view {:messages messages :auto-scroll? false :initial-scroll :top
                                    :empty-title "Track transcript"
                                    :empty-hint (if (seq results) "No transcript text available yet." "Waiting for this track's results…")}])]))

(defn results-panel
  "Show one full-width tab per selected track in a stage, independent of primary routing."
  [{:keys [session-id stage]}]
  (let [index (use-index session-id)
        [selected set-selected!] (react/useState nil)
        entries (filterv #(= stage (:stage %)) (:tracks index))
        track (or (some #(when (= selected (:track_id %)) %) entries) (first entries))]
    [:section {:class "async-tracks" :data-testid (str stage "-track-results")}
     (when (:read-error index)
       [:div {:class "badge bad" :role "alert"} "Track status could not be refreshed. Retrying…"])
     [:div {:class "tabs" :role "tablist" :aria-label (str stage " tracks")}
      (for [entry entries]
        [:button {:key (:track_id entry) :class (str "tab " (when (= (:track_id track) (:track_id entry)) "active"))
                  :type "button" :role "tab" :aria-selected (= (:track_id track) (:track_id entry))
                  :on-click #(set-selected! (:track_id entry))}
         (:display_name entry)])]
     (if track
       [:div {:role "tabpanel"}
        [track-body {:key (str session-id "/" stage "/" (:track_id track))
                     :session-id session-id :track track :has-recording? (:has_recording index)}]]
       [:div {:class "muted"}
        (if index "No tracks selected for this stage." "Waiting for the session's selected tracks…")])]))

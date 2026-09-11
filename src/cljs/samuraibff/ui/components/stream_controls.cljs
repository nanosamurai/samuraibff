(ns samuraibff.ui.components.stream-controls
  "Session output settings and deployment-owned track choices."
  (:require [clojure.string :as str]
            [samuraibff.ui.hooks :as hooks]
            [samuraibff.ui.store :as store]
            [samuraibff.ui.components.async-tracks :as async-tracks]))
(defn- output-controls
  "Render per-stream output, track selection, and realtime/refined knobs.

  This panel edits `store/session*` fields under `:controls`. Realtime track IDs
  come from `/api/me`; a missing explicit selection means all advertised tracks.

  Notes:
  - defaults are backwards compatible (all enabled)
  - when realtime is disabled, realtime controls are visually disabled
  - planned refinement can retain windows without a full final recording"
  []
  (let [controls (get (hooks/use-atom store/session*) :controls {})
        auth-state (hooks/use-atom store/auth*)
        catalog (get-in auth-state [:detail :async_tracks])
        running? (hooks/use-atom store/running?*)
        available-realtime-tracks (vec (get-in auth-state [:detail :realtime_tracks] []))
        realtime-track-capabilities (vec (get-in auth-state [:detail :realtime_track_capabilities] []))
        capabilities-by-track (into {} (map (juxt :id identity) realtime-track-capabilities))
        requested-realtime-track-set (set (:realtime_tracks controls))
        selected-realtime-tracks (if (seq requested-realtime-track-set)
                                   (filterv requested-realtime-track-set available-realtime-tracks)
                                   available-realtime-tracks)
        selected-realtime-track-set (set selected-realtime-tracks)
        realtime? (true? (:realtime controls))
        track-selection-disabled? (or (not realtime?) (true? running?))
        refined? (true? (:refined controls))
        final? (true? (:final controls))
        retained-refinement? (and refined? (some #(= "refined" (:stage %)) catalog))
        planned? (or retained-refinement? (and final? (some #(= "final" (:stage %)) catalog)))
        outputs-summary (->> [(when realtime? "Real-time")
                              (when refined? "Refined")
                              (when final? "Final")]
                             (remove nil?)
                             (str/join ", "))
        retention-summary (cond
                            (not (:store_recording controls)) "Not stored"
                            final? "Stored"
                            retained-refinement? "Refinement windows retained"
                            :else "Not stored")]
    (letfn [(checkbox-row [{:keys [id label checked disabled? on-change]}]
              [:div {:class "checkbox-row"}
               [:input {:id id
                        :type "checkbox"
                        :disabled (boolean disabled?)
                        :checked (boolean checked)
                        :on-change (fn [e]
                                     (when (fn? on-change)
                                       (on-change (.. e -target -checked))))}]
               [:label {:htmlFor id} label]])

            (number-field [{:keys [label disabled? value placeholder min max step on-change hint]}]
              [:div {:class "field"}
               [:div {:class "label"} label]
               [:input (cond-> {:type "number"
                                :aria-label label
                                :disabled (boolean disabled?)
                                :placeholder (or placeholder "")
                                :value (or value "")
                                :on-change (fn [e]
                                             (let [raw (.. e -target -value)]
                                               (when (fn? on-change)
                                                 (on-change (when (seq raw) (js/parseFloat raw))))))}
                         (some? min) (assoc :min min)
                         (some? max) (assoc :max max)
                         (some? step) (assoc :step step))]
               (when (seq (str hint))
                 [:div {:class "hint"} hint])])

            (set-track-selected! [track-id selected?]
              (let [next-track-set ((if selected? conj disj) selected-realtime-track-set track-id)
                    next-tracks (filterv next-track-set available-realtime-tracks)]
                (when (seq next-tracks)
                  (store/set-session-control! :realtime_tracks next-tracks))))]
      [:div {:class "stream-controls-body"}
       [:div {:class "muted" :style {:fontSize "12px"}}
        (str "Outputs: " (if (seq outputs-summary) outputs-summary "None")
             " • Recording: " retention-summary)]

       [:div {:class "sc-grid"}
        [:div {:class "sc-cell sc-span-2"}
         [:div {:class "label"} "Transcription"]
         [:div {:class "checkbox-group"}
          [checkbox-row {:id "sc-out-realtime"
                         :label "Real-time"
                         :checked realtime?
                         :on-change (fn [v] (store/set-session-control! :realtime v))}]
          [checkbox-row {:id "sc-out-refined"
                         :label "Refined"
                         :checked refined?
                         :on-change (fn [v] (store/set-session-control! :refined v))}]
          [checkbox-row {:id "sc-out-final"
                         :label "Final"
                         :checked final?
                         :on-change (fn [v] (store/set-session-control! :final v))}]]]

        [:div {:class "sc-cell"}
         [:div {:class "field"}
          [:div {:class "label"} "Recording"]
          [:select {:aria-label "Recording retention"
                    :value (if (true? (:store_recording controls)) "store" "delete")
                    :disabled (not (or final? retained-refinement?))
                    :on-change (fn [e]
                                 (store/set-session-control! :store_recording (= "store" (.. e -target -value))))}
           [:option {:value "store"} "Store"]
           [:option {:value "delete" :disabled (boolean planned?)} "Do not store"]]
          (when planned?
            [:div {:class "hint"} "Track processing requires retained audio."])
          (when-not final?
            [:div {:class "hint"} "Enable Final to keep a full recording for playback; refinement alone retains its audio windows."])]]

        [:div {:class "sc-cell"}
         [number-field {:label "Refinement window (sec)"
                        :disabled? (not refined?)
                        :min 10
                        :max 600
                        :step 1
                        :placeholder "Default"
                        :value (:refinement_window_sec controls)
                        :on-change (fn [v] (store/set-session-control! :refinement_window_sec v))
                        :hint (when-not refined?
                                "Enable Refined to adjust this setting.")}]]]

       [:div {:class "sc-divider"}]

       [:div {:class "sc-grid"}
        [:div {:class "sc-cell sc-span-2"}
         [:div {:class "label"} "Realtime tracks"]
         (if (seq available-realtime-tracks)
           [:details {:class (str "track-picker" (when track-selection-disabled? " disabled"))}
            [:summary {:class "track-picker-summary"
                       :aria-disabled track-selection-disabled?
                       :on-click (fn [event]
                                   (when track-selection-disabled?
                                     (.preventDefault event)))}
             [:span (str (count selected-realtime-tracks) " selected")]
             [:span {:class "muted track-picker-selected"}
              (str/join ", " selected-realtime-tracks)]]
            [:div {:class "track-picker-options"}
             (for [track-id available-realtime-tracks]
               (let [selected? (contains? selected-realtime-track-set track-id)
                     capability (get capabilities-by-track track-id)
                     mode (cond
                            (:native_streaming capability) "Native streaming"
                            (:windowed_realtime capability) "Windowed realtime"
                            :else nil)
                     maximum-seconds (:maximum_audio_seconds capability)
                     duration (when (number? maximum-seconds)
                                (cond
                                  (zero? maximum-seconds) "No stream cutoff"
                                  (:windowed_realtime capability) (str maximum-seconds " sec inference window")
                                  :else (str "Up to " maximum-seconds " sec per stream")))
                     timestamps (cond
                                  (:word_timestamps capability) "Word timestamps"
                                  (:segment_timestamps capability) "Segment timestamps"
                                  (true? (:available capability)) "No timestamps"
                                  :else nil)
                     aligned-languages (:aligned_diarized_languages capability)
                     speaker-labels
                     (when (:speaker_labels capability)
                       (if (seq aligned-languages)
                         (str "Speaker labels ("
                              (count aligned-languages)
                              " aligned languages)")
                         "Speaker labels"))
                     concurrency (:maximum_concurrent_sessions capability)
                     capability-summary
                     (if (false? (:available capability))
                       "Capabilities temporarily unavailable"
                       (->> [(:provider_profile_id capability)
                             mode
                             duration
                             timestamps
                             speaker-labels
                             (when (and (number? concurrency) (pos? concurrency))
                               (str concurrency " concurrent"))
                             (when (seq (:supported_languages capability))
                               (str (count (:supported_languages capability)) " languages"))]
                            (remove nil?)
                            (str/join " • ")))]
                 ^{:key (str "track-option-" track-id)}
                 [:div
                  [checkbox-row {:id (str "sc-track-" track-id)
                                 :label track-id
                                 :checked selected?
                                 :disabled? (or track-selection-disabled?
                                                (and selected?
                                                     (= 1 (count selected-realtime-tracks))))
                                 :on-change (fn [checked?]
                                              (set-track-selected! track-id checked?))}]
                  (when (seq capability-summary)
                    [:div {:class "hint" :style {:marginLeft "26px"}}
                     capability-summary])]))]]
           [:div {:class "hint"} "Track configuration is loading."])
         [:div {:class "hint"}
          (if (true? running?)
            "Track selection is locked once recording starts."
            "Select one to four operator-configured providers for this session.")]]

        [:div {:class "sc-cell"}
         [:div {:class "label"} "Real-time"]
         [:div {:class "checkbox-group"}
          [checkbox-row {:id "sc-rt-partials"
                         :label "Show partial text while speaking"
                         :checked (true? (:rt_partial_enable controls))
                         :disabled? (not realtime?)
                         :on-change (fn [v] (store/set-session-control! :rt_partial_enable v))}]]
         (when-not realtime?
           [:div {:class "hint"} "Enable Real-time to adjust these settings."])]

        [:div {:class "sc-cell"}
         [number-field {:label "Update interval (sec)"
                        :disabled? (not realtime?)
                        :min 1
                        :step 0.1
                        :placeholder "Default"
                        :value (:rt_emit_every_sec controls)
                        :on-change (fn [v] (store/set-session-control! :rt_emit_every_sec v))
                        :hint "Minimum 1 second."}]]

        [:div {:class "sc-cell"}
         [number-field {:label "Window (sec)"
                        :disabled? (not realtime?)
                        :min 1
                        :max 30
                        :step 0.1
                        :placeholder "Default"
                        :value (:rt_window_sec controls)
                        :on-change (fn [v] (store/set-session-control! :rt_window_sec v))}]]

        [:div {:class "sc-cell"}
         [number-field {:label "Overlap (sec)"
                        :disabled? (not realtime?)
                        :min 0
                        :step 0.1
                        :placeholder "Default"
                        :value (:rt_overlap_sec controls)
                        :on-change (fn [v] (store/set-session-control! :rt_overlap_sec v))}]]]])))

(defn panel
  "Lock all stream settings after admission; selecting a new session unlocks them."
  []
  (let [session (hooks/use-atom store/session*)
        running? (hooks/use-atom store/running?*)
        locked? (or running? (#{:active :finished :finalized :failed} (:status session)))]
    [:fieldset {:disabled (boolean locked?) :style {:border "none" :padding 0 :margin 0 :minWidth 0}}
     [output-controls]
     [async-tracks/selectors]]))

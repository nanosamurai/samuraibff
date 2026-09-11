(ns samuraibff.ui.components.stream-controls
  "Stage-specific session settings with direct track selection."
  (:require [clojure.string :as str]
            [samuraibff.ui.hooks :as hooks]
            [samuraibff.ui.store :as store]
            [samuraibff.ui.output-settings :as settings]))

(defn locked?
  "Return whether admission has frozen the session settings."
  [session running?]
  (boolean (or running? (#{:active :finished :finalized :failed} (:status session)))))

(defn- update-controls!
  "Apply one pure selection transition to the current session atom."
  [f & args]
  (apply swap! store/session* update :controls f args))

(defn tab-header
  "Render sibling checkbox and tab button in one visual header, without nested controls."
  [{:keys [stage label active? on-select]}]
  (let [session (hooks/use-atom store/session*)
        detail (:detail (hooks/use-atom store/auth*))
        catalog (settings/entries detail stage)
        enabled? (settings/enabled? (:controls session) stage catalog)
        frozen? (locked? session (hooks/use-atom store/running?*))]
    [:div {:class (str "output-tab " (when active? "active")) :role "presentation"}
     [:input {:type "checkbox" :checked enabled? :disabled frozen?
              :aria-label (str "Enable " label)
              :on-change #(update-controls! settings/toggle-stage stage catalog (.. % -target -checked))}]
     [:button {:type "button" :role "tab" :id (str "settings-tab-" (name stage))
               :aria-controls "session-settings-content" :aria-selected active?
               :on-click on-select}
      label
      [:span {:class "output-tab-count"}
       (if enabled? (if (seq catalog) (str (count (settings/selected-ids (:controls session) stage catalog))) "On") "Off")]]]))

(defn- number-field
  "Render one numeric control with its limits and optional explanatory hint."
  [{:keys [control label disabled? min max step hint]}]
  (let [controls (:controls (hooks/use-atom store/session*))]
    [:label {:class "field"}
     [:span {:class "label"} label]
     [:input (cond-> {:type "number" :aria-label label :disabled (boolean disabled?)
                      :placeholder "Default" :value (or (get controls control) "")
                      :on-change #(let [raw (.. % -target -value)]
                                    (store/set-session-control! control (when (seq raw) (js/parseFloat raw))))}
               min (assoc :min min) max (assoc :max max) step (assoc :step step))]
     (when hint [:span {:class "hint"} hint])]))

(defn- capability-summary
  "Describe useful realtime behavior without requiring the user to open a second picker."
  [capability]
  (if (false? (:available capability))
    "Capabilities temporarily unavailable"
    (str/join " · "
              (remove nil?
                      [(cond (:native_streaming capability) "Native streaming"
                             (:windowed_realtime capability) "Windowed realtime")
                       (when-let [seconds (:maximum_audio_seconds capability)]
                         (cond (zero? seconds) "No stream cutoff"
                               (:windowed_realtime capability) (str seconds " sec inference window")
                               :else (str "Up to " seconds " sec per stream")))
                       (cond (:word_timestamps capability) "Word timestamps"
                             (:segment_timestamps capability) "Segment timestamps")
                       (when (:speaker_labels capability) "Speaker labels")
                       (when (seq (:supported_languages capability))
                         (str (count (:supported_languages capability)) " languages"))]))))

(defn- track-choices
  "Render all stage tracks as equal, selectable cards; clearing the last turns the stage off."
  [stage]
  (let [controls (:controls (hooks/use-atom store/session*))
        detail (:detail (hooks/use-atom store/auth*))
        catalog (settings/entries detail stage)
        selected (if (settings/enabled? controls stage catalog) (set (settings/selected-ids controls stage catalog)) #{})
        capabilities (into {} (map (juxt :id identity) (:realtime_track_capabilities detail)))]
    [:div {:class "stage-track-choices" :data-testid (str (name stage) "-track-picker")}
     (if (seq catalog)
       [:div {:class "track-choice-grid"}
        (for [{:keys [track_id display_name]} catalog]
          [:label {:key track_id :class (str "track-choice " (when (contains? selected track_id) "selected"))}
           [:input {:type "checkbox" :checked (contains? selected track_id)
                    :aria-label (str (str/capitalize (name stage)) " track: " display_name)
                    :on-change #(update-controls! settings/select-track stage catalog track_id (.. % -target -checked))}]
           [:span {:class "track-choice-copy"}
            [:strong display_name]
            (when (= stage :realtime)
              (let [summary (capability-summary (get capabilities track_id))]
                (when (seq summary) [:span {:class "hint"} summary])))]])]
       [:p {:class "muted"}
        (if (= stage :realtime) "Track configuration is loading." "This deployment uses its default transcription provider.")])
     (when (seq catalog)
       [:p {:class "hint"} "Select the outputs you want. Clearing all tracks turns this stage off."])]))

(defn- recording-controls
  "Explain retention and enforce the audio requirement for selected asynchronous tracks."
  []
  (let [session (hooks/use-atom store/session*)
        detail (:detail (hooks/use-atom store/auth*))
        controls (settings/effective-controls (:controls session) detail)
        planned? (some #(and (get controls %) (seq (settings/entries detail %))) [:refined :final])]
    [:div {:class "stage-settings"}
     [:div {:class "stage-heading"}
      [:h3 "Recording"]
      [:p {:class "muted"} "Choose whether audio is kept after processing."]]
     [:label {:class "field retention-field"}
      [:span {:class "label"} "Recording retention"]
      [:select {:aria-label "Recording retention"
                :value (if (:store_recording controls) "store" "delete")
                :disabled (boolean (or planned? (not (:final controls))))
                :on-change #(store/set-session-control! :store_recording (= "store" (.. % -target -value)))}
       [:option {:value "store"} "Store"]
       [:option {:value "delete"} "Do not store"]]]
     [:p {:class "hint"}
      (cond planned? (if (:final controls)
                       "Selected tracks need retained audio. A full recording is kept for playback."
                       "Refined tracks retain audio windows. Enable Final to keep a full recording for playback.")
            (not (:final controls)) "Enable Final to store a full recording for playback."
            :else "Stored recordings remain available for playback in session details.")]]))

(defn panel
  "Render only the selected settings stage, locked after audio admission."
  [{:keys [stage]}]
  (let [session (hooks/use-atom store/session*)
        detail (:detail (hooks/use-atom store/auth*))
        frozen? (locked? session (hooks/use-atom store/running?*))
        enabled? (when (not= stage :recording) (settings/enabled? (:controls session) stage (settings/entries detail stage)))]
    [:fieldset {:class "stage-fieldset" :disabled frozen?}
     (if (= stage :recording)
       [recording-controls]
       [:div {:class "stage-settings"}
        [:div {:class "stage-heading"}
         [:h3 (case stage :realtime "Real-time transcription" :refined "Refined transcription" "Final transcription")]
         [:p {:class "muted"}
          (case stage :realtime "Text appears as you speak."
                :refined "More accurate transcripts arrive in regular audio windows."
                "A complete transcript is processed after recording stops.")]]
        [track-choices stage]
        (case stage
          :realtime
          [:div {:class "stage-options"}
           [:label {:class "checkbox-row"}
            [:input {:type "checkbox" :checked (boolean (get-in session [:controls :rt_partial_enable]))
                     :disabled (not enabled?) :on-change #(store/set-session-control! :rt_partial_enable (.. % -target -checked))}]
            "Show partial text while speaking"]
           [:div {:class "stage-number-grid"}
            [number-field {:control :rt_emit_every_sec :label "Update interval (sec)" :disabled? (not enabled?) :min 1 :step 0.1 :hint "Minimum 1 second."}]
            [number-field {:control :rt_window_sec :label "Window (sec)" :disabled? (not enabled?) :min 1 :max 30 :step 0.1}]
            [number-field {:control :rt_overlap_sec :label "Overlap (sec)" :disabled? (not enabled?) :min 0 :step 0.1}]]]
          :refined [number-field {:control :refinement_window_sec :label "Refinement window (sec)" :disabled? (not enabled?) :min 10 :max 600 :step 1}]
          nil)
        (when (and (not= stage :realtime) (seq (settings/entries detail stage)))
          [:p {:class "hint"} "Tracks require retained audio and support sessions up to 10 minutes. Configured tracks may be temporarily unavailable."])])
     (when frozen? [:p {:class "hint" :role "status"} "Settings are locked for this session. Start a new session to make changes."])]))

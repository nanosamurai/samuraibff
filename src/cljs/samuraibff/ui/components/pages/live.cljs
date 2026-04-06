(ns samuraibff.ui.components.pages.live
  "Live Recording page."
  (:require
   [samuraibff.ui.api :as api]
   [samuraibff.ui.audio :as audio]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.transcript :as components.transcript]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.langs :as langs]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]
   [samuraibff.ui.ws :as ws]
   ["react" :as react]))

(defn- status-dot-class
  "Translate websocket status keyword to CSS class name."
  [status]
  (case status
    :connected "dot ok"
    :error "dot bad"
    "dot"))

(defn ws-indicator
  "Render a small status widget for events/audio websockets.

  Used in the right-side panel (debug)."
  []
  (let [ws-status (hooks/use-atom store/ws-status*)
        events-status (get-in ws-status [:events :status])
        audio-status (get-in ws-status [:audio :status])]
    [:div {:class "ws-indicator"}
     [:div {:class "pill"}
      [:span {:class (status-dot-class events-status)}]
      [:span {:class "muted"} "events:"]
      [:span (name events-status)]]
     [:div {:class "pill"}
      [:span {:class (status-dot-class audio-status)}]
      [:span {:class "muted"} "audio:"]
      [:span (name audio-status)]]]))

(defn controls
  "Session controls (create session, set lang, start/stop).

  This is the top strip of the Live Recording page."
  []
  (let [{:keys [id lang]} (hooks/use-atom store/session*)
        running? (hooks/use-atom store/running?*)]
    [:div {:class "controls"}
     [:div {:class "controls-row"}
      [:button {:class "btn"
                :on-click (fn [_]
                            (store/append-log! "[ui] creating session...")
                            (-> (api/create-session! {:title (get @store/session* :title "")})
                                (.then (fn [{:keys [session_id title]}]
                                         (store/set-session-id! session_id)
                                         (store/set-session-title! (or title ""))
                                         (store/add-recording! {:session_id session_id
                                                                :created_at_ms (util/now-ms)
                                                                :status :ready})
                                         (store/append-log! (str "[ui] new session " session_id))))
                                (.catch (fn [e]
                                          (store/append-log! (str "[ui] failed creating session: " e))))))}
       "New session"]

      [:div {:class "field"}
       [:div {:class "label"} "Session name"]
       [:input {:value (or (get @store/session* :title) "")
                :placeholder "Untitled session"
                :on-change (fn [e]
                             (store/set-session-title! (.. e -target -value)))}]
       (when (seq (str id))
         [:div {:class "hint"}
          [:span {:class "mono"} id]])]

      [:div {:class "field"}
       [:div {:class "label"} "Language"]
       [shared/searchable-dropdown
        {:value (or lang "")
         :options (langs/language-options)
         :placeholder "Auto"
         :on-change (fn [new-val]
                      (store/set-lang! new-val))}]]

      [:div {:class "spacer"}]

      [:button {:class "btn primary"
                :disabled (or running? (empty? (str id)))
                :on-click (fn [_]
                            ;; Starting a new capture run; reset transcript time base.
                            (store/clear-segments!)
                            (store/set-running! true)
                            (store/set-recording-status! id :recording)
                            (store/append-log! "[ui] start")
                            (ws/connect-events! id)
                            (-> (audio/start-audio! id lang)
                                (.catch (fn [_]
                                          ;; audio will log; ensure running resets
                                          (store/set-running! false)
                                          (store/set-recording-status! id :ready)))))}
       "Start"]

      [:button {:class "btn"
                :disabled (not running?)
                :on-click (fn [_]
                            (store/append-log! "[ui] stop")
                            (store/set-running! false)
                            (store/set-recording-status! id :stopped)
                            (audio/stop-audio!)
                            (ws/close-events!))}
       "Stop"]

      [:button {:class "btn ghost"
                :on-click (fn [_] (store/clear-segments!))}
       "Clear transcript"]

      [:button {:class "btn ghost"
                :on-click (fn [_] (store/clear-log!))}
       "Clear log"]]]))

(defn- stream-controls-panel
  "Render per-stream output + realtime/refined knobs.

  This panel edits `store/session*` fields under `:controls`.

  Notes:
  - defaults are backwards compatible (all enabled)
  - when realtime disabled, realtime knobs are visually disabled
  - when final disabled, recording retention is forced off on the backend"
  []
  (let [controls (get (hooks/use-atom store/session*) :controls {})
        realtime? (true? (:realtime controls))
        refined? (true? (:refined controls))
        final? (true? (:final controls))]
    [:div {:class "controls"}
     [:div {:class "controls-row"}
      [:div {:class "field"}
       [:div {:class "label"} "Transcription outputs"]
       [:div {:style {:display "flex" :gap "12px" :flexWrap "wrap"}}
        [:label {:class "muted" :style {:display "inline-flex" :gap "6px" :alignItems "center"}}
         [:input {:type "checkbox"
                  :checked (boolean realtime?)
                  :on-change (fn [e]
                               (store/set-session-control! :realtime (.. e -target -checked)))}]
         "Realtime"]
        [:label {:class "muted" :style {:display "inline-flex" :gap "6px" :alignItems "center"}}
         [:input {:type "checkbox"
                  :checked (boolean refined?)
                  :on-change (fn [e]
                               (store/set-session-control! :refined (.. e -target -checked)))}]
         "Refined"]
        [:label {:class "muted" :style {:display "inline-flex" :gap "6px" :alignItems "center"}}
         [:input {:type "checkbox"
                  :checked (boolean final?)
                  :on-change (fn [e]
                               (store/set-session-control! :final (.. e -target -checked)))}]
         "Final"]]]

      (when final?
        [:div {:class "field"}
         [:div {:class "label"} "Recording retention"]
         [:select {:value (if (true? (:store_recording controls)) "store" "delete")
                   :on-change (fn [e]
                                (let [v (.. e -target -value)]
                                  (store/set-session-control! :store_recording (= v "store"))))}
          [:option {:value "store"} "Store recording"]
          [:option {:value "delete"} "Delete after transcription"]]])

      (when refined?
        [:div {:class "field"}
         [:div {:class "label"} "Refinement window (sec)"]
         [:input {:type "number"
                  :min 10
                  :max 600
                  :step 1
                  :placeholder "(default worker setting)"
                  :value (or (:refinement_window_sec controls) "")
                  :on-change (fn [e]
                               (let [raw (.. e -target -value)]
                                 (store/set-session-control!
                                  :refinement_window_sec
                                  (when (seq raw) (js/parseFloat raw)))))}]
         [:div {:class "hint"} "If set, sent as Kafka header x-refinement-window-sec (worker must support)."]])]

     [:div {:class "controls-row"}
      [:div {:class "field"}
       [:div {:class "label"} "Realtime settings"]
       [:label {:class "muted"
                :style {:display "inline-flex" :gap "6px" :alignItems "center"}}
        [:input {:type "checkbox"
                 :disabled (not realtime?)
                 :checked (boolean (:rt_partial_enable controls))
                 :on-change (fn [e]
                              (store/set-session-control! :rt_partial_enable (.. e -target -checked)))}]
        "Emit partials"]]

      [:div {:class "field"}
       [:div {:class "label"} "Emit every (sec)"]
       [:input {:type "number"
                :min 1
                :step 0.1
                :disabled (not realtime?)
                :placeholder "(default)"
                :value (or (:rt_emit_every_sec controls) "")
                :on-change (fn [e]
                             (let [raw (.. e -target -value)]
                               (store/set-session-control!
                                :rt_emit_every_sec
                                (when (seq raw) (js/parseFloat raw)))))}]
       [:div {:class "hint"} "Min 1s (perf safeguard)."]]

      [:div {:class "field"}
       [:div {:class "label"} "Window (sec)"]
       [:input {:type "number"
                :min 1
                :max 30
                :step 0.1
                :disabled (not realtime?)
                :placeholder "(default)"
                :value (or (:rt_window_sec controls) "")
                :on-change (fn [e]
                             (let [raw (.. e -target -value)]
                               (store/set-session-control!
                                :rt_window_sec
                                (when (seq raw) (js/parseFloat raw)))))}]]

      [:div {:class "field"}
       [:div {:class "label"} "Overlap (sec)"]
       [:input {:type "number"
                :min 0
                :step 0.1
                :disabled (not realtime?)
                :placeholder "(default)"
                :value (or (:rt_overlap_sec controls) "")
                :on-change (fn [e]
                             (let [raw (.. e -target -value)]
                               (store/set-session-control!
                                :rt_overlap_sec
                                (when (seq raw) (js/parseFloat raw)))))}]]]]))

(defn- log-view
  "Debug log view."
  []
  (let [lines (->> (hooks/use-atom store/log*)
                   (take-last 160))]
    [:div {:class "log"}
     (if (empty? lines)
       [:span {:class "muted"} "(empty)"]
       (for [[idx line] (map-indexed vector lines)]
         [:div {:class "log-line" :key (str "log-" idx)} line]))]))

(defn right-panel
  "Right-side panel for Live Recording.

  Contains tabs (for now: Log only)."
  []
  (let [active :log
        debug-asr? (hooks/use-atom store/debug-asr-log?*)]
    [:div {:class "right-panel"}
     [:div {:class "tabs"}
      [:button {:class (str "tab " (when (= active :log) "active"))}
       "Log"]]
     [:div {:class "right-panel-body"}
      [ws-indicator]
      [:label {:class "muted"
               :style {:display "inline-flex"
                       :gap "8px"
                       :alignItems "center"
                       :margin "8px 0"}}
       [:input {:type "checkbox"
                :checked (boolean debug-asr?)
                :on-change (fn [e]
                             (store/set-debug-asr-log! (.. e -target -checked)))}]
       "Log ASR events"]
      [log-view]]]))

(defn- live-transcript
  "Transcript component bound to the live session store."
  []
  [components.transcript/transcript-view
   {:messages (hooks/use-atom store/asr-segments*)
    :empty-title "Real-time transcript"
    :empty-hint "No ASR events yet…"}])

(defn- refined-live-transcript
  "Refined realtime transcript component bound to the live session store."
  []
  [components.transcript/transcript-view
   {:messages (hooks/use-atom store/refined-segments*)
    :empty-title "Refined real-time"
    :empty-hint "No refined events yet…"}])

(defn live-recording-page
  "Live Recording page."
  []
  (let [tab* (react/useState :realtime)
        tab (aget tab* 0)
        set-tab! (aget tab* 1)]
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Live Recording"]
       [:div {:class "muted"} "Capture audio and view the live transcript."]]
      [:div {:class "row"}
       [router/link {:route {:page :recordings :params {}}
                     :class "btn"}
        "Recordings"]]]

     [controls]

     [stream-controls-panel]

     [:div {:class "tabs"}
      [:button {:class (str "tab " (when (= tab :realtime) "active"))
                :on-click (fn [_] (set-tab! :realtime))}
       "Real-time transcript"]
      [:button {:class (str "tab " (when (= tab :refined) "active"))
                :on-click (fn [_] (set-tab! :refined))}
       "Refined real-time"]
      [:div {:class "spacer"}]]

     [:div {:class "split"}
      [:div {:class "split-main"}
       (case tab
         :refined [refined-live-transcript]
         [live-transcript])]
      [:div {:class "split-side"}
       [right-panel]]]]))

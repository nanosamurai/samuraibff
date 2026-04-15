(ns samuraibff.ui.components.pages.live
  "Live Recording page."
  (:require
   [clojure.string :as str]
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

(defn- resolved-webhook-overrides
  "Compute the `webhook_overrides` request body for `POST /api/sessions`.

  Inputs:
  - session: map from `store/session*`

  Returns:
  - nil when the UI is at the default state (so we omit the field entirely)
  - otherwise a JSON-friendly map matching schemas/CreateSessionRequest"
  [session]
  (let [ov (or (:webhook_overrides session) {})
        use-defaults? (if (contains? ov :use_defaults)
                        (boolean (:use_defaults ov))
                        true)
        webhook-ids (set (or (:webhook_ids ov) #{}))
        disable-event-types (set (or (:disable_event_types ov) #{}))
        default? (and (true? use-defaults?)
                      (empty? webhook-ids)
                      (empty? disable-event-types))]
    (when-not default?
      (cond-> {:use_defaults use-defaults?
               :webhook_ids (vec (sort webhook-ids))}
        (seq disable-event-types)
        (assoc :disable_event_types (vec (sort disable-event-types)))))))

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
  (let [session (hooks/use-atom store/session*)
        {:keys [id lang]} session
        running? (hooks/use-atom store/running?*)]
    [:div {:class "controls"}
     [:div {:class "controls-row"}
      [:button {:class "btn"
                :on-click (fn [_]
                            (store/append-log! "[ui] creating session...")
                            (let [overrides (resolved-webhook-overrides @store/session*)]
                              (-> (api/create-session! {:title (get @store/session* :title "")
                                                        :webhook_overrides overrides})
                                  (.then (fn [{:keys [session_id title]}]
                                           (store/set-session-id! session_id)
                                           (store/set-session-title! (or title ""))
                                           (store/add-recording! {:session_id session_id
                                                                  :created_at_ms (util/now-ms)
                                                                  :status :ready})
                                           (store/append-log! (str "[ui] new session " session_id))))
                                  (.catch (fn [e]
                                            (store/append-log! (str "[ui] failed creating session: " e)))))))}
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

(defn- webhook-routing-panel
  "Advanced panel for per-session webhook routing overrides.

  This panel matches the styling + minimize behavior of Stream settings.

  It controls `webhook_overrides` sent to `POST /api/sessions`.

  Returns: hiccup."
  []
  (let [event-types [{:value "transcript.refined.segment" :label "transcript.refined.segment"}
                     {:value "recording.finished" :label "recording.finished"}
                     {:value "transcript.final.ready" :label "transcript.final.ready"}]

        session (hooks/use-atom store/session*)
        webhooks-st (hooks/use-atom store/webhooks*)
        {:keys [items loading? error]} webhooks-st
        overrides (or (:webhook_overrides session) {})
        use-defaults? (if (contains? overrides :use_defaults)
                        (boolean (:use_defaults overrides))
                        true)
        selected-ids (set (or (:webhook_ids overrides) #{}))
        disabled-event-types (set (or (:disable_event_types overrides) #{}))
        open?* (react/useState false)
        open? (aget open?* 0)
        set-open! (aget open?* 1)
        summary (str (if use-defaults? "Defaults ON" "Defaults OFF")
                     " • Selected " (count selected-ids))
        refresh! (fn []
                   (store/set-webhooks-loading! true)
                   (store/set-webhooks-error! nil)
                   (-> (api/list-webhooks!)
                       (.then (fn [resp]
                                (store/set-webhooks-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/set-webhooks-error! (shared/safe-http-error e))))
                       (.finally (fn []
                                   (store/set-webhooks-loading! false)))))
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

    ;; Fetch webhooks when opening for the first time (best effort).
    (react/useEffect
     (fn []
       (when (and (true? open?)
                  (empty? (or items []))
                  (not (true? loading?)))
         (refresh!))
       js/undefined)
     #js [open?])

    [:div {:class "controls stream-controls"}
     [:div {:class "stream-controls-header"}
      [:div {:class "stream-controls-title"}
       [:div {:class "stream-controls-title-text"} "Webhook routing"]
       [:div {:class "muted" :style {:fontSize "12px"}} summary]]
      [:button {:class "btn ghost"
                :type "button"
                :aria-expanded (boolean open?)
                :on-click (fn [_] (set-open! (not open?)))}
       (if open? "Hide" "Show")]]

     (when open?
       [:div {:class "stream-controls-body"}
        (when (seq error)
          [:div {:class "error" :style {:marginBottom "8px"}} error])

        [checkbox-row {:id "wh-use-defaults"
                       :label "Use tenant default webhooks"
                       :checked (true? use-defaults?)
                       :on-change (fn [v] (store/set-session-webhook-overrides-use-defaults! v))}]

        [:div {:class "muted" :style {:marginTop "6px" :marginBottom "8px"}}
         "Select additional webhooks for this session (or disable defaults)."]

        [:div {:style {:display "flex" :gap "8px" :marginBottom "8px"}}
         [:button {:class "btn"
                   :type "button"
                   :disabled (boolean loading?)
                   :on-click (fn [_] (refresh!))}
          (if loading? "Loading…" "Refresh")]]

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

        [:div {:class "muted" :style {:marginTop "10px" :fontSize "12px"}}
         "Applies only when creating a new session. Existing sessions keep their routing snapshot."]])

     (comment "Disabling the ability to disable events :) - even if a webhook (or even all available webhooks) does not support (enable) certain event, that event is still there ready to be checked, which is kind of confusing - we would have to know which events actually could be disabled before sensibly conveying that information to the user. Before we do that I am commenting out the next component.")
     #_(when open?
       [:div {:class "stream-controls-body"}
        [:div {:class "label" :style {:marginTop "8px"}} "Disable event types for this session"]
        [:div {:class "muted" :style {:marginTop "4px" :marginBottom "8px"}}
         "These events will not be routed to any webhooks for this session."]
        [:div {:style {:display "flex" :flexDirection "column" :gap "6px"}}
         (for [{:keys [value label]} event-types]
           [:div {:key (str "wh-disable-" value)}
            [checkbox-row {:id (str "wh-disable-" value)
                           :label [:span [:span {:class "mono"} label]]
                           :checked (contains? disabled-event-types value)
                           :on-change (fn [v]
                                        (store/set-session-disable-event-type-selected! value v))}]])]
        [:div {:class "muted" :style {:marginTop "8px" :fontSize "12px"}}
         "Tip: refined segments are high volume; disable them if you only want final/recording events."]])]))

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
        final? (true? (:final controls))
        open?* (react/useState false)
        open? (aget open?* 0)
        set-open! (aget open?* 1)
        outputs-summary (->> [(when realtime? "Real-time")
                              (when refined? "Refined")
                              (when final? "Final")]
                             (remove nil?)
                             (str/join ", "))
        retention-summary (if (and final? (true? (:store_recording controls)))
                            "Stored"
                            "Not stored")]
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
                 [:div {:class "hint"} hint])])]
      [:div {:class "controls stream-controls"}
       [:div {:class "stream-controls-header"}
        [:div {:class "stream-controls-title"}
         [:div {:class "stream-controls-title-text"} "Stream settings"]
         [:div {:class "muted" :style {:fontSize "12px"}}
          (str "Outputs: " (if (seq outputs-summary) outputs-summary "None")
               " • Recording: " retention-summary)]]
        [:button {:class "btn ghost"
                  :type "button"
                  :aria-expanded (boolean open?)
                  :on-click (fn [_] (set-open! (not open?)))}
         (if open? "Hide" "Show")]]

       (when open?
         [:div {:class "stream-controls-body"}
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
             [:select {:value (if (true? (:store_recording controls)) "store" "delete")
                       :disabled (not final?)
                       :on-change (fn [e]
                                    (let [v (.. e -target -value)]
                                      (store/set-session-control! :store_recording (= v "store"))))}
              [:option {:value "store"} "Store"]
              [:option {:value "delete"} "Do not store"]]
             (when-not final?
               [:div {:class "hint"} "Enable Final to store the full recording."])]]

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
                           :on-change (fn [v] (store/set-session-control! :rt_overlap_sec v))}]]]])])))

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

     [webhook-routing-panel]

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

(ns samuraibff.ui.components.pages.live
  "Live Recording page."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.audio :as audio]
   [samuraibff.ui.media-devices :as media.devices]
   [samuraibff.ui.env :as env]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.transcript :as components.transcript]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.langs :as langs]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.session-request :as session.req]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]
   [samuraibff.ui.webhook-delivery-outcomes :as ui.wh.outcomes]
   [samuraibff.ui.ws :as ws]
   [samuraibff.ui.workflow-results :as ui.wf.results]
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
  "Record page controls (quick setup + record now).

      UX goals:
      - Starting a recording should be a single click (create session if needed).
      - Device selection should be visible (not hidden behind settings).

      Inputs:
      - {:keys [settings-open? set-settings-open!]} where:
        - settings-open?: boolean
        - set-settings-open!: (fn [boolean])

      Returns: hiccup."
  [{:keys [settings-open? set-settings-open!]}]
  (let [session (hooks/use-atom store/session*)
        {:keys [id lang]} session
        controls (or (:controls session) {})
        electron? (env/electron?)
        audio-source (or (:audio_source controls) :mic)
        system-name (or (:system_source_name controls) "")
        mic-device-id (str (or (:mic_device_id controls) ""))

        running? (hooks/use-atom store/running?*)
        starting?* (react/useState false)
        starting? (aget starting?* 0)
        set-starting! (aget starting?* 1)

        mic-options* (react/useState [])
        mic-options (aget mic-options* 0)
        set-mic-options! (aget mic-options* 1)
        mic-error* (react/useState nil)
        mic-error (aget mic-error* 0)
        set-mic-error! (aget mic-error* 1)

        set-settings-open! (when (fn? set-settings-open!) set-settings-open!)]

    (react/useEffect
     (fn []
       (-> (media.devices/list-microphones!)
           (.then (fn [xs]
                    (set-mic-options! (vec (or xs [])))))
           (.catch (fn [_]
                                ;; Best-effort; do not block UI.
                     (set-mic-error! "Microphone list not available"))))
       js/undefined)
     #js [])

    (letfn [(start-streaming! [sid]
                                     ;; Starting a new capture run; reset transcript time base.
              (store/clear-segments!)
              (store/set-running! true)
              (store/set-recording-status! sid :recording)
              (store/append-log! (str "[ui] start session=" sid))
              (ws/connect-events! sid)
              (-> (audio/start-audio! sid lang)
                  (.catch (fn [_]
                                                     ;; audio will log; ensure running resets
                            (store/set-running! false)
                            (store/set-recording-status! sid :ready)))))

            (create-session! []
              (store/append-log! "[ui] creating session...")
              (let [req (session.req/create-session-request-body @store/session*)]
                (-> (api/create-session! req)
                    (.then (fn [{:keys [session_id title]}]
                             (store/set-session-id! session_id)
                             (store/set-session-title! (or title ""))
                             (store/add-recording! {:session_id    session_id
                                                    :created_at_ms (util/now-ms)
                                                    :status        :ready})
                             (store/append-log! (str "[ui] new session " session_id))
                             (str session_id)))
                    (.catch (fn [e]
                              (store/append-log! (str "[ui] failed creating session: " (shared/safe-http-error e)))
                              (throw e))))))

            (ensure-system-source! []
              (if (and electron?
                       (not= :mic audio-source)
                       (empty? (str (or (:system_source_id controls) ""))))
                (audio/pick-system-source!)
                (js/Promise.resolve true)))

            (record-now! []
              (when (or (true? running?) (true? starting?))
                (js/Promise.resolve false))
              (set-starting! true)
              (let [existing-id (str (or id ""))
                    sid-promise (if (seq existing-id)
                                  (js/Promise.resolve existing-id)
                                  (create-session!))]
                (-> sid-promise
                    (.then (fn [sid]
                             (-> (ensure-system-source!)
                                 (.then (fn [_]
                                          (start-streaming! sid))))))
                    (.finally (fn []
                                (set-starting! false))))))

            (stop! []
              (store/append-log! "[ui] stop")
              (store/set-running! false)
              (store/set-recording-status! id :stopped)
              (audio/stop-audio!)
              (ws/close-events!))]

      [:div {:class "controls"}
       [:div {:class "controls-row"}
        [:div {:class "field"}
         [:div {:class "label"} "Session name"]
         [:input {:value       (or (get @store/session* :title) "")
                  :placeholder "Untitled session"
                  :on-change   (fn [e]
                                 (store/set-session-title! (.. e -target -value)))}]
         (when (seq (str id))
           [:div {:class "hint"}
            [:span {:class "mono"} id]])]

        [:div {:class "field"}
         [:div {:class "label"} "Language"]
         [shared/searchable-dropdown
          {:value       (or lang "")
           :options     (langs/language-options)
           :placeholder "Auto"
           :on-change   (fn [new-val]
                          (store/set-lang! new-val))}]]

        [:div {:class "field"}
         [:div {:class "label"} "Audio input"]
         [:select {:value     (name audio-source)
                   :on-change (fn [e]
                                (let [v (keyword (.. e -target -value))]
                                  (store/set-session-control! :audio_source v)))}
          [:option {:value "mic"} "Microphone"]
          [:option {:value "system" :disabled (not electron?)} "System output (Electron)"]
          [:option {:value "mix" :disabled (not electron?)} "Mix mic + system (Electron)"]]

         (when (and electron? (not= :mic audio-source))
           [:div {:class "hint" :style {:marginTop "8px"}}
            [:div {:style {:display "flex" :gap "8px" :alignItems "center" :flexWrap "wrap"}}
             [:button {:class    "btn"
                       :type     "button"
                       :on-click (fn [_]
                                   (-> (audio/pick-system-source!)
                                       (.then (fn [{:keys [name]}]
                                                (store/append-log! (str "[ui] picked system source: " (or name "")))))
                                       (.catch (fn [err]
                                                 (store/append-log! (str "[ui] failed to pick system source: " err))))))}
              (if (seq system-name) "Change system source" "Pick system source")]
             (when (seq system-name)
               [:span {:class "muted"} system-name])]])]

        [:div {:class "field"}
         [:div {:class "label"} "Microphone"]
         [:select {:value     mic-device-id
                   :disabled  (= :system audio-source)
                   :on-change (fn [e]
                                (let [v (.. e -target -value)]
                                  (store/set-session-control!
                                   :mic_device_id
                                   (when (seq (str v)) (str v)))))}
          [:option {:value ""} "Default"]
          (for [{:keys [id label]} (vec (or mic-options []))]
            ^{:key (str "mic-" id)}
            [:option {:value id} (or label id)])]
         (when (seq (str mic-error))
           [:div {:class "hint"} mic-error])]

        [:div {:class "spacer"}]

        [:button {:class    "btn primary"
                  :disabled (or running? starting?)
                  :on-click (fn [_] (record-now!))
                  :title    "Start recording"}
         [:span {:style {:color "var(--bad)"}} "●"]
         (if starting? "Starting…" "Record now")]

        [:button {:class    "btn"
                  :disabled (not running?)
                  :on-click (fn [_] (stop!))
                  :title    "Stop recording"}
         [:span {:style {:color "var(--muted)"}} "■"]
         "Stop"]

        [:button {:class      "btn icon"
                  :type       "button"
                  :aria-label "Session settings"
                  :title      "Session settings"
                  :on-click   (fn [_]
                                (when set-settings-open!
                                  (set-settings-open! (not (true? settings-open?)))))}
         "⚙"]]])))

(declare webhook-routing-panel workflow-routing-panel stream-controls-panel)

(defn- session-settings-panel
  "Single settings panel shown from the gear button.

   It contains tabs:
   - Stream settings
   - Webhooks
   - Workflows

  Inputs:
  - {:keys [open? set-open!]} where:
    - open?: boolean
    - set-open!: (fn [boolean])

  Returns: hiccup (or nil when closed)."
  [{:keys [open? set-open!]}]
  (let [tab* (react/useState :stream)
        tab (aget tab* 0)
        set-tab! (aget tab* 1)
        set-open! (when (fn? set-open!) set-open!)]
    (when (true? open?)
      [:div {:class "controls stream-controls"}
       [:div {:class "stream-controls-header"}
        [:div {:class "stream-controls-title"}
         [:div {:class "stream-controls-title-text"} "Session settings"]
         [:div {:class "muted" :style {:fontSize "12px"}}
          "Applies to newly created sessions."]]
        [:button {:class "btn ghost"
                  :type "button"
                  :title "Close"
                  :on-click (fn [_]
                              (when set-open!
                                (set-open! false)))}
         "Close"]]

       [:div {:class "tabs" :style {:marginBottom "0"}}
        [:button {:class (str "tab " (when (= tab :stream) "active"))
                  :type "button"
                  :on-click (fn [_] (set-tab! :stream))}
         "Stream"]
        [:button {:class (str "tab " (when (= tab :webhooks) "active"))
                  :type "button"
                  :on-click (fn [_] (set-tab! :webhooks))}
         "Webhooks"]
        [:button {:class (str "tab " (when (= tab :workflows) "active"))
                  :type "button"
                  :on-click (fn [_] (set-tab! :workflows))}
         "Workflows"]
        [:div {:class "spacer"}]]

       (case tab
         :webhooks [webhook-routing-panel]
         :workflows [workflow-routing-panel]
         [stream-controls-panel])])))

(defn- workflow-routing-panel
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
                    :label "Use tenant default workflows"
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
                          (or (get-in trigger [:type]) "")]]]
              [:div {:key (str "wf-ov-" id)}
               [checkbox-row {:id (str "wf-ov-" id)
                              :label label
                              :disabled? (not enabled?)
                              :checked checked?
                              :on-change (fn [v]
                                           (store/set-session-workflow-id-selected! id v))}]]))]))

     [:div {:class "muted" :style {:marginTop "10px" :fontSize "12px"}}
      "Applies only when creating a new session. Existing sessions keep their routing snapshot."]]))

(defn- webhook-routing-panel
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
                    :label "Use tenant default webhooks"
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
                         :label "Send cumulative refined transcript (rolling tail)"
                         :checked enabled?
                         :on-change (fn [v]
                                      (store/set-session-refined-consolidation-enabled! v))}]
          [:div {:class "hint" :style {:marginTop "6px"}}
           "Only applies to webhooks subscribed to transcript.refined.segment."]]))

     [:div {:class "muted" :style {:marginTop "10px" :fontSize "12px"}}
      "Applies only when creating a new session. Existing sessions keep their routing snapshot."]]))

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
        electron? (env/electron?)
        audio-source (or (:audio_source controls) :mic)
        system-name (or (:system_source_name controls) "")
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
      [:div {:class "stream-controls-body"}
       [:div {:class "muted" :style {:fontSize "12px"}}
        (str "Outputs: " (if (seq outputs-summary) outputs-summary "None")
             " • Recording: " retention-summary)]

       [:div {:class "sc-grid"}
        [:div {:class "sc-cell sc-span-2"}
         [:div {:class "label"} "Audio input"]
         [:div {:class "hint" :style {:marginBottom "6px"}}
          "Mic capture works in all browsers. System/mix requires Electron (Windows-first)."]
         [:select {:value (name audio-source)
                   :on-change (fn [e]
                                (let [v (keyword (.. e -target -value))]
                                  (store/set-session-control! :audio_source v)))}
          [:option {:value "mic"} "Microphone"]
          [:option {:value "system" :disabled (not electron?)} "System output (Electron)"]
          [:option {:value "mix" :disabled (not electron?)} "Mix mic + system (Electron)"]]

         (when (and electron? (not= :mic audio-source))
           [:div {:style {:marginTop "8px" :display "flex" :gap "8px" :alignItems "center" :flexWrap "wrap"}}
            [:button {:class "btn"
                      :type "button"
                      :on-click (fn [_]
                                  (-> (audio/pick-system-source!)
                                      (.then (fn [{:keys [name]}]
                                               (store/append-log! (str "[ui] picked system source: " (or name "")))))
                                      (.catch (fn [err]
                                                (store/append-log! (str "[ui] failed to pick system source: " err))))))}
             (if (seq system-name) "Change system source" "Pick system source")]
            (when (seq system-name)
              [:span {:class "muted"} system-name])])]

        [:div {:class "sc-cell"}
         [:div {:class "label"} "Mic gain"]
         [:input {:type "number"
                  :min 0
                  :max 3
                  :step 0.1
                  :value (or (:mic_gain controls) 1.0)
                  :on-change (fn [e]
                               (let [raw (.. e -target -value)
                                     v (when (seq raw) (js/parseFloat raw))]
                                 (store/set-session-control! :mic_gain v)))}]]

        [:div {:class "sc-cell"}
         [:div {:class "label"} "System gain"]
         [:input {:type "number"
                  :min 0
                  :max 3
                  :step 0.1
                  :disabled (or (not electron?) (= :mic audio-source))
                  :value (or (:system_gain controls) 1.0)
                  :on-change (fn [e]
                               (let [raw (.. e -target -value)
                                     v (when (seq raw) (js/parseFloat raw))]
                                 (store/set-session-control! :system_gain v)))}]]]

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
                        :on-change (fn [v] (store/set-session-control! :rt_overlap_sec v))}]]]])))

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

(defn- webhooks-view
  "Webhook delivery outcomes view (latest per dispatch) for live session.

  Fetches:
  - GET /api/sessions/:session_id/webhook-delivery-outcomes

  Returns: hiccup."
  []
  (let [session (hooks/use-atom store/session*)
        session-id (str (or (:id session) ""))

        loading?* (react/useState false)
        loading? (aget loading?* 0)
        set-loading! (aget loading?* 1)

        error* (react/useState nil)
        error (aget error* 0)
        set-error! (aget error* 1)

        items* (react/useState [])
        items (aget items* 0)
        set-items! (aget items* 1)

        refresh!
        (fn []
          (when (seq session-id)
            (set-loading! true)
            (set-error! nil)
            (-> (api/list-webhook-delivery-outcomes! session-id)
                (.then (fn [resp]
                         (set-items! (vec (or (:items resp) [])))))
                (.catch (fn [e]
                          (set-error! (shared/safe-http-error e))))
                (.finally (fn []
                            (set-loading! false))))))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [session-id])

    [:div {:style {:display "flex" :flexDirection "column" :gap "10px"}}
     [:div {:class "row"}
      [:div {:class "spacer"}]
      [:button {:class "btn ghost"
                :disabled (or loading? (empty? session-id))
                :on-click (fn [_] (refresh!))}
       (if loading? "Refreshing…" "Refresh")]]

     [ui.wh.outcomes/webhook-dispatches-card
      {:items items
       :loading? loading?
       :error error
       :title "Webhook dispatches"}]]))

(defn right-panel
  "Right-side panel for Live Recording.

  Contains tabs (for now: Log only)."
  []
  (let [active* (react/useState :workflows)
        active (aget active* 0)
        set-active! (aget active* 1)
        debug-asr? (hooks/use-atom store/debug-asr-log?*)
        workflow-results (hooks/use-atom store/workflow-results*)]
    [:div {:class "right-panel"}
     [:div {:class "tabs"}
      [:button {:class (str "tab " (when (= active :log) "active"))
                :on-click (fn [_] (set-active! :log))}
       "Log"]
      [:button {:class (str "tab " (when (= active :webhooks) "active"))
                :on-click (fn [_] (set-active! :webhooks))}
       "Webhooks"]
      [:button {:class (str "tab " (when (= active :workflows) "active"))
                :on-click (fn [_] (set-active! :workflows))}
       "Workflows"]]
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

      (case active
        :webhooks [webhooks-view]
        :workflows
        [ui.wf.results/workflow-results-card
         {:items workflow-results
          :title "Workflow results (live)"
          :fill? true
          :empty-hint "No workflow results streamed yet."}]
        [log-view])]]))

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
        set-tab! (aget tab* 1)

        settings-open?* (react/useState false)
        settings-open? (aget settings-open?* 0)
        set-settings-open! (aget settings-open?* 1)]
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Record"]
       [:div {:class "muted"} "Capture audio and view the live transcript."]]
      [:div {:class "row"}
       [router/link {:route {:page :recordings :params {}}
                     :class "btn"}
        "Sessions"]]]

     [controls {:settings-open? settings-open?
                :set-settings-open! set-settings-open!}]

     [session-settings-panel {:open? settings-open?
                              :set-open! set-settings-open!}]

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

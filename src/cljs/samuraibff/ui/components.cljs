(ns samuraibff.ui.components
  "HSX (hiccup-style) React components for the SPA.

  Components here are plain React function components compiled via HSX.
  State is kept in plain CLJS atoms in `samuraibff.ui.store` and consumed
  from React via `samuraibff.ui.hooks`.

  This file intentionally contains only UI rendering logic; side effects
  (ws/audio/fetch) should remain in their dedicated namespaces." 
  (:require
    [io.factorhouse.hsx.core :as hsx]
    [clojure.string :as str]
    [samuraibff.ui.api :as api]
    [samuraibff.ui.audio :as audio]
    [samuraibff.ui.auth :as auth]
    [samuraibff.ui.hooks :as hooks]
    [samuraibff.ui.router :as router]
    [samuraibff.ui.store :as store]
    [samuraibff.ui.api-credentials-store :as api-creds.store]
    [samuraibff.ui.transcript :as transcript]
    [samuraibff.ui.util :as util]
    [samuraibff.ui.ws :as ws]
    ["react" :as react]))

(defn- iso->local
  "Best-effort formatting of an ISO timestamp into a local date/time string." 
  [s]
  (when (seq (str s))
    (try
      (.toLocaleString (js/Date. s))
      (catch :default _
        (str s)))))

(defn- refined-events->messages
  "Convert refined events (with start/end/text) into transcript messages." 
  [events]
  (->> (or events [])
       (mapv transcript/normalize-refined)
       transcript/sort-messages))

(defn- final-segments->messages
  "Convert final transcript segments (from DB json) into transcript messages." 
  [segments]
  (mapv (fn [seg]
          {:kind "final"
           :seq 0
           :ts_ms 0
           :start_s (:start_s seg)
           :end_s (:end_s seg)
           :text (:text seg)
           :speaker (:speaker seg)
           :lang (:lang seg)})
        (vec (or segments []))))

(defn- status-dot-class
  "Translate websocket status keyword to CSS class name." 
  [status]
  (case status
    :connected "dot ok"
    :error "dot bad"
    "dot"))

(defn ws-indicator
  "Render a small status widget for events/audio websockets.

  This is used in the right-side panel (debug)."
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
                            (-> (api/create-session!)
                                (.then (fn [sid]
                                         (store/set-session-id! sid)
                                         (store/add-recording! {:session_id sid
                                                              :created_at_ms (util/now-ms)
                                                              :status :ready})
                                         (store/append-log! (str "[ui] new session " sid))))
                                (.catch (fn [e]
                                          (store/append-log! (str "[ui] failed creating session: " e))))))}
       "New session"]

      [:div {:class "field"}
       [:div {:class "label"} "Session"]
       [:input {:value (or id "")
                :placeholder "uuid"
                :on-change (fn [e]
                             (store/set-session-id! (.. e -target -value)))}]]

      [:div {:class "field"}
       [:div {:class "label"} "Language"]
       [:select {:value (or lang "")
                 :on-change (fn [e]
                              (store/set-lang! (.. e -target -value)))}
        [:option {:value "cs"} "cs"]
        [:option {:value "en"} "en"]
        [:option {:value ""} "auto"]]]

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
                                          (store/set-recording-status! id :ready))))) }
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
       "Clear log"]]

     [:div {:class "hint"}
      "Tip: run backend on port 8000, then start shadow watch so /js/main.js exists."]]))

(defn- message-key
  [idx msg]
  (str "msg-" idx "-" (:seq msg) "-" (:ts_ms msg)))

(defn- badge
  [{:keys [kind final]}]
  (cond
    (= kind "refined")
    [:span {:class "badge refined"} "★ refined"]

    (and (= kind "asr") (false? final))
    [:span {:class "badge muted"} "partial"]

    :else
    nil))

(defn transcript-view
  "Transcript display (Slack-like feed).

  Renders transcript as a message thread:
  - avatar
  - speaker name
  - timestamp
  - message bubble

  Refined messages are visually marked (★ refined) and replace overlapping
  realtime messages (handled in store)." 
  [{:keys [messages empty-title empty-hint]}]
  (let [msgs (vec (or messages []))
        container-ref (react/useRef nil)
        ;; Auto-scroll unless the user scrolled up.
        auto-scroll?* (react/useRef true)]

    (react/useEffect
      (fn []
        (when-let [el (.-current container-ref)]
          (when (true? (.-current auto-scroll?*))
            (set! (.-scrollTop el) (.-scrollHeight el))))
        js/undefined)
      #js [(count msgs)])

    [:div {:class "transcript"}
     (if (empty? msgs)
       [:div {:class "empty"}
        [:div {:class "empty-title"} (or empty-title "Transcript")]
        [:div {:class "muted"} (or empty-hint "No events yet…")]]
       [:div {:class "transcript-feed"
              :ref container-ref
              :on-scroll (fn [e]
                           (let [el (.-target e)
                                 dist (- (.-scrollHeight el)
                                         (.-scrollTop el)
                                         (.-clientHeight el))]
                             (set! (.-current auto-scroll?*) (<= dist 48))))}
        (for [[idx msg] (map-indexed vector msgs)]
          (let [k (message-key idx msg)
                speaker (:speaker msg)
                who (transcript/speaker->display-name speaker)
                avatar (transcript/speaker->avatar-text speaker)
                start-ts (util/fmt-sec (:start_s msg))
                end-ts (util/fmt-sec (:end_s msg))
                bubble-class (str "bubble" (when (and (= "asr" (:kind msg)) (false? (:final msg))) " draft"))]
            [:div {:class "msg" :key k}
             [:div {:class "avatar"} avatar]
             [:div {:class "msgBody"}
              [:div {:class "msgHeader"}
               [:span {:class "who"} who]
               [:span {:class "ts"} (str start-ts " → " end-ts)]
               (badge msg)]
              [:div {:class bubble-class} (:text msg)]]]))])]))

(defn live-transcript
  "Transcript component bound to the live session store." 
  []
  [transcript-view {:messages (hooks/use-atom store/segments*)
                    :empty-title "Live transcript"
                    :empty-hint "No ASR events yet…"}])

(defn log-view
  "Debug log view." 
  []
  (let [lines (->> (hooks/use-atom store/log*)
                   (take-last 160))]
    [:div {:class "log"}
     (if (empty? lines)
       [:span {:class "muted"} "(empty)"]
       (for [[idx line] (map-indexed vector lines)]
         [:div {:class "log-line" :key (str "log-" idx)} line]))]))

(defn- format-status
  [status]
  (case status
    :ready "Ready"
    :recording "Recording"
    :stopped "Stopped"
    (name status)))

(defn- rec->display-status
  "Derive a UI status descriptor for a DB recording row.

  Inputs:
  - rec: map from /api/recordings with keys:
      :status (string)
      :has_recording (boolean)
      :has_final_transcript (boolean)

  Returns:
  - {:label string :badge-class string :icon string :title string}

  Notes:
  - We deliberately fold \"finalized\" into status so the table can stay compact.
  - Sessions without recordings are treated as \"Created\" (draft)."
  [{:keys [status has_recording has_final_transcript]}]
  (let [status (some-> status str)
        has-recording? (true? has_recording)
        has-final? (true? has_final_transcript)]
    (cond
      (not has-recording?)
      {:label "Created"
       :badge-class "muted"
       :icon "○"
       :title "Session created but recording never started"}

      (= status "failed")
      {:label "Failed"
       :badge-class "bad"
       :icon "✗"
       :title "Session failed"}

      has-final?
      {:label "Finalized"
       :badge-class "ok"
       :icon "✓"
       :title "Final transcript is available"}

      (= status "active")
      {:label "Recording"
       :badge-class "warn"
       :icon "●"
       :title "Recording in progress"}

      :else
      {:label "Processing"
       :badge-class "muted"
       :icon "…"
       :title "Recording stopped; final transcript not available yet"})))

(defn- icon
  "Render a lightweight icon glyph (no external dependencies).

  Inputs:
  - s: string
  - opts: optional map {:title string}

  Returns: hiccup" 
  ([s] (icon s nil))
  ([s {:keys [title]}]
   [:span {:class "icon" :title title} (or s "")]))

(defn- recordings-row
  "Render a single recordings table row.

  Inputs:
  - rec: a map from /api/recordings

  Returns: hiccup <tr>" 
  [{:keys [session_id started_at created_at] :as rec}]
  (let [{:keys [label badge-class title]
         icon-glyph :icon} (rec->display-status rec)]
    [:tr
     [:td {:class "mono"} session_id]
     [:td {:class "muted"} (or (iso->local created_at) "")]
     [:td {:class "muted"} (or (iso->local started_at) "")]
     [:td
      [:span {:class (str "badge " badge-class)
              :title title}
       (icon icon-glyph {:title title})
       [:span {:style {:marginLeft "8px"}} label]]]
     [:td {:style {:textAlign "right"}}
      [:div {:class "row"}
       [router/link {:route {:page :recording :params {:session_id session_id}}
                     :class "btn"
                     :title "Open detail"}
        (icon "↗" {:title "Open"})]

       [router/link {:route {:page :live :params {}}
                     :class "btn ghost"
                     :title "Open in Live Recording"
                     :on-click (fn [_]
                                 (store/set-session-id! session_id))}
        (icon "●" {:title "Go live"})]

       [:button {:class "btn ghost"
                 :title "Delete session"
                 :on-click (fn [_]
                             (when (js/confirm (str "Delete session " session_id
                                                    "?\n\nThis will remove recordings and transcripts."))
                               (-> (api/delete-recording! session_id)
                                   (.then (fn [_]
                                            (store/remove-recording-db! session_id)))
                                   (.catch (fn [e]
                                             (store/append-log!
                                               (str "[ui] failed deleting session: " e)))))))}
        (icon "×" {:title "Delete"})]]]]))

(defn recordings-table
  "Table of DB-backed recordings." 
  []
  (let [recs0 (->> (hooks/use-atom store/recordings-db*)
                   (sort-by :created_at)
                   reverse)
        show-drafts?* (react/useState false)
        show-drafts? (aget show-drafts?* 0)
        set-show-drafts! (aget show-drafts?* 1)
        recs (if show-drafts?
               (vec recs0)
               (vec (remove (fn [r] (false? (:has_recording r))) recs0)))
        drafts-count (count (filter (fn [r] (false? (:has_recording r))) recs0))]
    [:div {:class "card"}
     [:div {:class "row" :style {:alignItems "center"}}
      [:div {:class "card-title"} "Recordings"]
      [:div {:class "spacer"}]
      (when (pos? drafts-count)
        [:label {:class "muted"
                 :style {:display "inline-flex" :gap "8px" :alignItems "center"}}
         [:input {:type "checkbox"
                  :checked (boolean show-drafts?)
                  :on-change (fn [e]
                               (set-show-drafts! (.. e -target -checked)))}]
         (str "Show drafts (" drafts-count ")")])]

     (if (empty? recs)
       [:div {:class "muted"} "No recordings yet."]
       [:table {:class "table"}
        [:thead
         [:tr
          [:th "Session"]
          [:th "Created"]
          [:th "Started"]
          [:th "Status"]
          [:th {:style {:textAlign "right"}} "Actions"]]]
        [:tbody
         (for [{:keys [session_id] :as rec} recs]
           ^{:key (str "rec-" session_id)}
           [recordings-row rec])]])]))

(defn recordings-page
  "Recordings page." 
  []
  (let [loading?* (react/useState false)
        loading? (aget loading?* 0)
        set-loading! (aget loading?* 1)
        refresh! (fn []
                   (set-loading! true)
                   (-> (api/list-recordings!)
                       (.then (fn [resp]
                                (store/set-recordings-db! (:items resp))))
                       (.catch (fn [e]
                                 (store/append-log! (str "[ui] failed loading recordings: " e))))
                       (.finally (fn [] (set-loading! false)))))]
    (react/useEffect
      (fn []
        (refresh!)
        js/undefined)
      #js [])
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Recordings"]
       [:div {:class "muted"} "Sessions from database (tenant-scoped)."]]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn primary"
                 :on-click (fn [_]
                             (store/append-log! "[ui] creating session...")
                             (-> (api/create-session!)
                                 (.then (fn [sid]
                                          (store/set-session-id! sid)
                                          (store/add-recording! {:session_id sid
                                                               :created_at_ms (util/now-ms)
                                                               :status :ready})
                                          (router/navigate! {:page :live :params {}})
                                          (store/append-log! (str "[ui] new session " sid))))
                                 (.catch (fn [e]
                                           (store/append-log! (str "[ui] failed creating session: " e))))))}
        "New live session"]]]
     [recordings-table]]))

(defn- speaker-row
  [{:keys [id label created_at created_at_ms]} on-delete]
  [:tr
   [:td {:class "mono"} id]
   [:td label]
   [:td {:class "muted"} (or created_at (when created_at_ms (.toLocaleString (js/Date. created_at_ms))) "")]
   [:td {:style {:textAlign "right"}}
    [:button {:class "btn ghost"
              :on-click (fn [_]
                          (on-delete id))}
     "Delete"]]])

(defn speakers-page
  "Enrolled speakers management page." 
  []
  (let [items (hooks/use-atom store/speakers*)
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
            [speaker-row item delete!])]])]]))

(defn recording-detail-page
  "Recording detail page.

  Features:
  - Preview transcript tab: refined segments from DB, plus cached realtime ASR
    (when available locally)
  - Final transcript tab: final transcript records from DB
  - Hideable log panel (cached locally only)" 
  [session-id]
  (let [tab* (react/useState :preview)
        tab (aget tab* 0)
        set-tab! (aget tab* 1)
        show-log?* (react/useState true)
        show-log? (aget show-log?* 0)
        set-show-log! (aget show-log?* 1)

        loading* (react/useState true)
        loading? (aget loading* 0)
        set-loading! (aget loading* 1)

        detail* (react/useState nil)
        detail (aget detail* 0)
        set-detail! (aget detail* 1)

        cached-asr (store/cached-segments session-id)
        cached-log-lines (store/cached-log session-id)


        refresh! (fn []
                   (set-loading! true)
                   (-> (api/get-recording! session-id)
                       (.then (fn [resp]
                                (set-detail! resp)))
                       (.catch (fn [e]
                                 (store/append-log! (str "[ui] failed loading recording detail: " e))
                                 (set-detail! {:ok false :message "failed"})))
                       (.finally (fn [] (set-loading! false)))))

        db-refined (get-in detail [:transcripts :refined])
        db-final (get-in detail [:transcripts :final])

        ;; Convert DB refined transcript records to "refined" events.
        refined-events
        (let [records (vec (or db-refined []))]
          (reduce
            (fn [events r]
              (let [segments-json (get r :segments "[]")
                    segments (try
                               (js->clj (.parse js/JSON segments-json) :keywordize-keys true)
                               (catch :default _ []))]
                (reduce
                  (fn [events seg]
                    (conj events {:seq (or (:event_created_at_ns r) 0)
                                  :ts_ms 0
                                  :start_s (:start_s seg)
                                  :end_s (:end_s seg)
                                  :text (:text seg)
                                  :speaker (:speaker seg)
                                  :lang (:lang seg)}))
                  events
                  segments)))
            []
            records))]

    (react/useEffect
      (fn []
        (refresh!)
        js/undefined)
      #js [session-id])

    ;; Build preview transcript by applying refined events (from DB) onto cached ASR.
    ;; If no cached ASR exists, show refined events only.
    (let [preview-msgs (if (seq cached-asr)
                         (reduce (fn [msgs ref]
                                   (transcript/apply-refined msgs ref))
                                 (vec cached-asr)
                                 refined-events)
                         (refined-events->messages refined-events))

          ;; Final transcript: take the last record and render its segments (or full_text).
          final-record (last (vec (or db-final [])))
          final-msgs (let [segments-json (get final-record :segments "[]")
                           segments (try
                                      (js->clj (.parse js/JSON segments-json) :keywordize-keys true)
                                      (catch :default _ []))]
                       (final-segments->messages segments))]

      [:div {:class "page"}
       [:div {:class "page-header"}
        [:div
         [:div {:class "page-title"} "Recording"]
         [:div {:class "mono muted"} session-id]
         (when loading?
           [:div {:class "muted"} "Loading…"])]
        [:div {:class "row"}
         [router/link {:route {:page :recordings :params {}}
                       :class "btn"}
          "Back to recordings"]
         [router/link {:route {:page :live :params {}}
                       :class "btn ghost"
                       :on-click (fn [_] (store/set-session-id! session-id))}
          "Open in Live Recording"]
         [:button {:class "btn"
                   :on-click (fn [_] (refresh!))}
          "Refresh"]]]

       [:div {:class "tabs"}
        [:button {:class (str "tab " (when (= tab :preview) "active"))
                  :on-click (fn [_] (set-tab! :preview))}
         "Preview transcript"]
        [:button {:class (str "tab " (when (= tab :final) "active"))
                  :on-click (fn [_] (set-tab! :final))}
         "Final transcript"]
        [:div {:class "spacer"}]
        [:button {:class "btn ghost"
                  :on-click (fn [_] (set-show-log! (not show-log?)))}
         (if show-log? "Hide log" "Show log")]]

       (if show-log?
         [:div {:class "grid-2"}
          [:div {:class "card"}
           [:div {:class "card-title"} (if (= tab :preview) "Preview" "Final")]
           (case tab
             :final [transcript-view {:messages final-msgs
                                      :empty-title "Final transcript"
                                      :empty-hint (if final-record "(no segments)" "No final transcript stored") }]
             [transcript-view {:messages preview-msgs
                               :empty-title "Preview transcript"
                               :empty-hint "No transcript available"}])]

          [:div {:class "card"}
           [:div {:class "card-title"} "Log"]
           (if (seq cached-log-lines)
             [:div {:class "log"}
              (for [[idx line] (map-indexed vector cached-log-lines)]
                [:div {:class "log-line" :key (str "logc-" idx)} line])]
             [:div {:class "muted"} "No log available for this session (not persisted)."])]
          ]
         [:div {:class "card"}
          [:div {:class "card-title"} (if (= tab :preview) "Preview" "Final")]
          (case tab
            :final [transcript-view {:messages final-msgs
                                     :empty-title "Final transcript"
                                     :empty-hint (if final-record "(no segments)" "No final transcript stored") }]
            [transcript-view {:messages preview-msgs
                              :empty-title "Preview transcript"
                              :empty-hint "No transcript available"}])])]))

  )

(defn right-panel
  "Right-side panel for Live Recording.

  Contains tabs (for now: Log only)." 
  []
  (let [active :log]
    [:div {:class "right-panel"}
     [:div {:class "tabs"}
      [:button {:class (str "tab " (when (= active :log) "active"))}
       "Log"]]
     [:div {:class "right-panel-body"}
      [ws-indicator]
      [log-view]]]))

(defn live-recording-page
  "Live Recording page." 
  []
  [:div {:class "page"}
   [:div {:class "page-header"}
    [:div
     [:div {:class "page-title"} "Live Recording"]
     [:div {:class "muted"} "Realtime transcript via /ws/events + /ws/audio."]]
    [:div {:class "row"}
     [router/link {:route {:page :recordings :params {}}
                   :class "btn"}
      "Recordings"]]]

   [controls]

   [:div {:class "split"}
    [:div {:class "split-main"}
     [live-transcript]]
    [:div {:class "split-side"}
     [right-panel]]]])

(defn- sidebar-item
  [{:keys [active? label route]}]
  [router/link {:route route
                :class (str "nav-item" (when active? " active"))}
   [:span {:class "nav-label"} label]])

(defn sidebar
  "Left navigation sidebar." 
  [route]
  (let [page (:page route)]
    [:aside {:class "sidebar"}
     [:div {:class "sidebar-section"}
      [:div {:class "sidebar-title"} "Navigation"]
      [sidebar-item {:label "Recordings"
                     :route {:page :recordings :params {}}
                     :active? (= page :recordings)}]
      [sidebar-item {:label "Live Recording"
                     :route {:page :live :params {}}
                     :active? (= page :live)}]
      [sidebar-item {:label "Speakers"
                     :route {:page :speakers :params {}}
                     :active? (= page :speakers)}]
      [sidebar-item {:label "API Credentials"
                     :route {:page :api-credentials :params {}}
                     :active? (= page :api-credentials)}]]]))

(defn breadcrumbs
  "Breadcrumbs derived from current route." 
  [route]
  (let [{:keys [page params]} route
        crumbs (case page
                 :recordings [{:label "Recordings" :route {:page :recordings :params {}}}]
                 :live [{:label "Recordings" :route {:page :recordings :params {}}}
                        {:label "Live Recording" :route {:page :live :params {}}}]
                 :speakers [{:label "Speakers" :route {:page :speakers :params {}}}]
                 :api-credentials [{:label "API Credentials" :route {:page :api-credentials :params {}}}]
                 :recording [{:label "Recordings" :route {:page :recordings :params {}}}
                             {:label (or (:session_id params) "Recording")
                              :route {:page :recording :params params}}]
                 [{:label "Recordings" :route {:page :recordings :params {}}}])]
    [:div {:class "breadcrumbs"}
     (for [[idx c] (map-indexed vector crumbs)]
       [:span {:class "crumb" :key (str "crumb-" idx "-" (:label c))}
        (when (pos? idx)
          [:span {:class "sep"} "/"])
        [router/link {:route (:route c) :class "crumb-link"}
         (:label c)]])]))

(defn topbar
  "Top application bar (logo + product name + breadcrumbs)." 
  [route]
  (let [{:keys [status detail]} (hooks/use-atom store/auth*)
        user (get detail :user)
        tenant-id (get detail :tenant_id)]
    [:header {:class "topbar"}
     [:div {:class "brand"}
      [:img {:class "logo" :src "/img/nonosamurai_art.jpg" :alt "nanosamur.ai"}]
      [:div {:class "brand-name"} "nanosamur.ai"]]
     [breadcrumbs route]
     [:div {:class "topbar-right"}
      (cond
        (= status :loading)
        [:span {:class "muted"} "auth: loading…"]

        (= status :authenticated)
        [:div {:class "row"}
         [:span {:class "badge ok"}
          (or (:preferred_username user) (:email user) (:sub user) "user")]
         (when tenant-id
           [:span {:class "badge muted"} (str "tenant " tenant-id)])
         [:button {:class "btn"
                   :on-click (fn [_]
                               (-> (auth/logout!)
                                   (.then (fn [_] (auth/fetch-me!)))))}
          "Logout"]]

        :else
        [:div {:class "row"}
         [:span {:class "badge muted"} "anonymous"]
         [:button {:class "btn primary"
                   :on-click (fn [_]
                               (auth/login! (router/route->href route)))}
          "Login"]])]]))

(defn- safe-http-error
  "Return a safe string to show/log for fetch errors.

  Important:
  - never include response bodies (may contain secrets)
  - never include stack traces

  Inputs:
  - e: JS error

  Returns: string." 
  [e]
  (let [msg (some-> e .-message str)]
    (if (seq msg) msg "Request failed")))

(defn- copy-to-clipboard!
  "Copy text to clipboard (best effort).

  Inputs:
  - s: string

  Returns:
  - Promise resolving to true/false." 
  [s]
  (let [s (str (or s ""))]
    (cond
      (and (exists? js/navigator)
           (exists? (.-clipboard js/navigator))
           (exists? (.-writeText (.-clipboard js/navigator))))
      (-> (.writeText (.-clipboard js/navigator) s)
          (.then (fn [_] true))
          (.catch (fn [_] false)))

      :else
      (js/Promise.resolve false))))

(defn api-credentials-secret-modal
  "Modal that shows `client_secret` exactly once.

  Security:
  - The secret is kept only in memory (store atom) and cleared on close." 
  []
  (let [st (hooks/use-atom store/api-credentials*)
        {:keys [open? credential-id client-id client-secret copied?]} (:secret-modal st)]
    (when (true? open?)
      [:div {:class "modal-overlay"
             :on-click (fn [_]
                         (store/api-credentials-close-secret!))}
       [:div {:class "modal"
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "modal-title"} "Client secret"]
        [:div {:class "muted" :style {:marginBottom "10px"}}
         "This secret is shown only once. Copy it now and store it securely."
         (when credential-id
           [:div {:class "muted" :style {:marginTop "6px"}}
            [:span {:class "mono"} credential-id]])]

        [:div {:class "card" :style {:marginBottom "10px"}}
         [:div {:class "muted" :style {:fontSize "12px" :marginBottom "6px"}} "client_id"]
         [:div {:class "mono" :style {:wordBreak "break-all"}} (or client-id "")]]

        [:div {:class "card" :style {:marginBottom "12px"}}
         [:div {:class "muted" :style {:fontSize "12px" :marginBottom "6px"}} "client_secret"]
         [:div {:class "mono" :style {:wordBreak "break-all"}} (or client-secret "")]]

        [:div {:class "row" :style {:justifyContent "flex-end"}}
         [:button {:class (str "btn " (when copied? "primary"))
                   :on-click (fn [_]
                               (-> (copy-to-clipboard! (or client-secret ""))
                                   (.then (fn [ok?]
                                            (store/api-credentials-mark-secret-copied! (true? ok?))))))}
          (if copied? "Copied" "Copy secret")]
         [:button {:class "btn"
                   :on-click (fn [_]
                               (store/api-credentials-close-secret!))}
          "Close"]]]])))

(defn- credential-revoked?
  [cred]
  (some? (or (:revoked_at cred) (:revoked-at cred))))

(defn- api-credentials-row
  [{:keys [id name keycloak_client_id created_at last_used_at revoked_at]} refresh!]
  (let [revoked? (some? revoked_at)
        id (or id "")
        client-id (or keycloak_client_id "")]
    [:tr
     [:td name]
     [:td {:class "mono"} client-id]
     [:td {:class "muted"} (or (iso->local created_at) "")]
     [:td {:class "muted"} (or (iso->local last_used_at) "")]
     [:td
      (if revoked?
        [:span {:class "badge muted"} "Revoked"]
        [:span {:class "badge ok"} "Active"])]
     [:td {:style {:textAlign "right"}}
      [:div {:class "row" :style {:justifyContent "flex-end"}}
       [:button {:class "btn"
                 :disabled revoked?
                 :title "Rotate secret"
                 :on-click (fn [_]
                             (when (js/confirm (str "Rotate secret for " name "?\n\nThe old secret will stop working."))
                               (store/api-credentials-set-loading! true)
                               (store/api-credentials-set-error! nil)
                               (-> (api/rotate-api-credential! id)
                                   (.then (fn [resp]
                                            (store/api-credentials-open-secret!
                                              {:credential-id (:credential_id resp)
                                               :client-id (:client_id resp)
                                               :client-secret (:client_secret resp)})
                                            (refresh!)))
                                   (.catch (fn [e]
                                             (store/api-credentials-set-error! (safe-http-error e))))
                                   (.finally (fn []
                                               (store/api-credentials-set-loading! false))))))}
        "Rotate"]

       [:button {:class "btn ghost"
                 :disabled revoked?
                 :title "Revoke credential"
                 :on-click (fn [_]
                             (when (js/confirm (str "Revoke credential " name "?\n\nThis will disable the Keycloak client."))
                               (store/api-credentials-set-loading! true)
                               (store/api-credentials-set-error! nil)
                               (-> (api/revoke-api-credential! id)
                                   (.then (fn [_]
                                            ;; Optimistic: mark revoked, then refresh.
                                            (store/api-credentials-mark-revoked! id)
                                            (refresh!)))
                                   (.catch (fn [e]
                                             (store/api-credentials-set-error! (safe-http-error e))))
                                   (.finally (fn []
                                               (store/api-credentials-set-loading! false))))))}
        "Revoke"]]]]))

(defn api-credentials-page
  "API credentials management page (tenant-scoped)." 
  []
  (let [st (hooks/use-atom store/api-credentials*)
        items (api-creds.store/visible-items st)
        loading? (:loading? st)
        error (:error st)
        show-revoked? (:show-revoked? st)

        name* (react/useState "")
        name (aget name* 0)
        set-name! (aget name* 1)

        refresh! (fn []
                   (store/api-credentials-set-loading! true)
                   (store/api-credentials-set-error! nil)
                   (-> (api/list-api-credentials!)
                       (.then (fn [resp]
                                (store/api-credentials-set-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/api-credentials-set-error! (safe-http-error e))))
                       (.finally (fn []
                                   (store/api-credentials-set-loading! false)))))

        create! (fn []
                  (store/api-credentials-set-loading! true)
                  (store/api-credentials-set-error! nil)
                  (-> (api/create-api-credential! name)
                      (.then (fn [resp]
                               (set-name! "")
                               (store/api-credentials-open-secret!
                                 {:credential-id (:credential_id resp)
                                  :client-id (:client_id resp)
                                  :client-secret (:client_secret resp)})
                               (refresh!)))
                      (.catch (fn [e]
                                (store/api-credentials-set-error! (safe-http-error e))))
                      (.finally (fn []
                                  (store/api-credentials-set-loading! false)))))]

    (react/useEffect
      (fn []
        (refresh!)
        js/undefined)
      #js [])

    [:div {:class "page"}
     [api-credentials-secret-modal]

     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "API Credentials"]
       [:div {:class "muted"}
        "Tenant-scoped machine-to-machine credentials (Keycloak service accounts)."]
       (when (seq error)
         [:div {:class "badge bad" :style {:marginTop "10px"}} error])]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Create credential"]
      [:div {:class "row"}
       [:input {:placeholder "Name (e.g. my-sdk)"
                :value name
                :on-change (fn [e] (set-name! (.. e -target -value)))}]
       [:button {:class "btn primary"
                 :disabled (or loading? (str/blank? name))
                 :on-click (fn [_] (create!))}
        "Create"]]
      [:div {:class "hint"}
       "The client secret will be shown exactly once. It is never stored by the BFF."]]

     [:div {:class "card"}
      [:div {:class "row" :style {:alignItems "center"}}
       [:div {:class "card-title"} "Credentials"]
       [:div {:class "spacer"}]
       [:label {:class "muted"
                :style {:display "inline-flex" :gap "8px" :alignItems "center"}}
        [:input {:type "checkbox"
                 :checked (boolean show-revoked?)
                 :on-change (fn [_]
                              (store/api-credentials-toggle-show-revoked!))}]
        "Show revoked"]]
      (if (empty? items)
        [:div {:class "muted"} "No API credentials yet."]
        [:table {:class "table"}
         [:thead
          [:tr
           [:th "Name"]
           [:th "Client id"]
           [:th "Created"]
           [:th "Last used"]
           [:th "Status"]
           [:th {:style {:textAlign "right"}} "Actions"]]]
         [:tbody
         (for [c (->> items
                      (sort-by :created_at)
                      reverse)]
           ^{:key (str "cred-" (:id c))}
           [api-credentials-row c refresh!])]])]]))

(defn app
  "Root app component." 
  []
  (let [route (hooks/use-atom store/route*)
        {:keys [status detail]} (hooks/use-atom store/auth*)
        auth-required? (true? (get detail :auth-required?))]
    (react/useEffect
      (fn []
        (when (and (= status :anonymous) auth-required?)
          ;; Keep it harder to poke around: force a full redirect to login.
          (auth/login! (router/route->href route)))
        js/undefined)
      #js [status auth-required? (:page route) (get-in route [:params :session_id])])
    [:div {:class "app"}
     [topbar route]
     [:div {:class "body"}
      [sidebar route]
      [:main {:class "main"}
       (case (:page route)
         :recordings [recordings-page]
         :live [live-recording-page]
         :recording [recording-detail-page (get-in route [:params :session_id])]
         :speakers [speakers-page]
         :api-credentials [api-credentials-page]
         [recordings-page])]]]))

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)." 
  []
  (hsx/memo-clear!))

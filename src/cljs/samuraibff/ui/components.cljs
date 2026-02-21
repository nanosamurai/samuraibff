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
    [samuraibff.ui.transcript :as transcript]
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

(defn transcript
  "Transcript display (Slack-like feed).

  Renders transcript as a message thread:
  - avatar
  - speaker name
  - timestamp
  - message bubble

  Refined messages are visually marked (★ refined) and replace overlapping
  realtime messages (handled in store)." 
  []
  (let [msgs (hooks/use-atom store/segments*)
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
        [:div {:class "empty-title"} "Live transcript"]
        [:div {:class "muted"} "No ASR events yet…"]]
       [:div {:class "transcript-feed"
              :ref container-ref
              :on-scroll (fn [e]
                           (let [el (.-target e)
                                 dist (- (.-scrollHeight el)
                                         (.-scrollTop el)
                                         (.-clientHeight el))]
                             (set! (.-current auto-scroll?*) (<= dist 48))))}
        (for [[idx msg] (map-indexed vector msgs)]
          ^{:key (message-key idx msg)}
          (let [speaker (:speaker msg)
                who (transcript/speaker->display-name speaker)
                avatar (transcript/speaker->avatar-text speaker)
                start-ts (util/fmt-sec (:start_s msg))
                end-ts (util/fmt-sec (:end_s msg))
                bubble-class (str "bubble" (when (and (= "asr" (:kind msg)) (false? (:final msg))) " draft"))]
            [:div {:class "msg"}
             [:div {:class "avatar"} avatar]
             [:div {:class "msgBody"}
              [:div {:class "msgHeader"}
               [:span {:class "who"} who]
               [:span {:class "ts"} (str start-ts " → " end-ts)]
               (badge msg)]
              [:div {:class bubble-class} (:text msg)]]]))])]))

(defn log-view
  "Debug log view." 
  []
  (let [lines (->> (hooks/use-atom store/log*)
                   (take-last 160))]
    [:div {:class "log"}
     (if (empty? lines)
       [:span {:class "muted"} "(empty)"]
       (for [[idx line] (map-indexed vector lines)]
         ^{:key (str "log-" idx)}
         [:div {:class "log-line"} line]))]))

(defn- format-status
  [status]
  (case status
    :ready "Ready"
    :recording "Recording"
    :stopped "Stopped"
    (name status)))

(defn recordings-table
  "Table of in-memory recordings." 
  []
  (let [recs (->> (hooks/use-atom store/recordings*)
                  (sort-by :created_at_ms)
                  reverse)]
    [:div {:class "card"}
     [:div {:class "card-title"} "Recordings"]
     (if (empty? recs)
       [:div {:class "muted"} "No recordings yet. Create a new live session to get started."]
       [:table {:class "table"}
        [:thead
         [:tr
          [:th "Session"]
          [:th "Created"]
          [:th "Status"]
          [:th {:style {:textAlign "right"}} "Actions"]]]
        [:tbody
         (for [{:keys [session_id created_at_ms status]} recs]
           ^{:key (str "rec-" session_id)}
           [:tr
            [:td {:class "mono"} session_id]
            [:td {:class "muted"} (.toLocaleString (js/Date. created_at_ms))]
            [:td
             [:span {:class (str "badge " (case status
                                            :recording "bad"
                                            :stopped "muted"
                                            "ok"))}
              (format-status status)]]
            [:td {:style {:textAlign "right"}}
             [:div {:class "row"}
              [router/link {:route {:page :recording :params {:session_id session_id}}
                            :class "btn"}
               "Open"]
              [router/link {:route {:page :live :params {}}
                            :class "btn ghost"
                            :on-click (fn [_]
                                        (store/set-session-id! session_id))}
               "Go live"]]]])]])]))

(defn recordings-page
  "Recordings page." 
  []
  [:div {:class "page"}
   [:div {:class "page-header"}
    [:div
     [:div {:class "page-title"} "Recordings"]
     [:div {:class "muted"} "Sessions available in this browser (MVP, in-memory)."]]
    [:div {:class "row"}
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
   [recordings-table]])

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
  "Recording detail page (MVP placeholder).

  Today this is mostly a navigation scaffold; later we can show metadata,
  refined transcript, notes, etc." 
  [session-id]
  [:div {:class "page"}
   [:div {:class "page-header"}
    [:div
     [:div {:class "page-title"} "Recording"]
     [:div {:class "mono muted"} session-id]]
    [:div {:class "row"}
     [router/link {:route {:page :recordings :params {}}
                   :class "btn"}
      "Back to recordings"]
     [router/link {:route {:page :live :params {}}
                   :class "btn primary"
                   :on-click (fn [_] (store/set-session-id! session-id))}
      "Open in Live Recording"]]]

   [:div {:class "card"}
    [:div {:class "muted"}
     "Detail view is a placeholder for now. Use \"Open in Live Recording\" to continue streaming." ]]

   [:div {:class "grid-2"}
    [:div {:class "card"}
     [:div {:class "card-title"} "Transcript (preview)"]
     [transcript]]

    [:div {:class "card"}
     [:div {:class "card-title"} "Log (preview)"]
     [log-view]]]])

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
     [transcript]]
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
                     :active? (= page :speakers)}]]]))

(defn breadcrumbs
  "Breadcrumbs derived from current route." 
  [route]
  (let [{:keys [page params]} route
        crumbs (case page
                 :recordings [{:label "Recordings" :route {:page :recordings :params {}}}]
                 :live [{:label "Recordings" :route {:page :recordings :params {}}}
                        {:label "Live Recording" :route {:page :live :params {}}}]
                 :speakers [{:label "Speakers" :route {:page :speakers :params {}}}]
                 :recording [{:label "Recordings" :route {:page :recordings :params {}}}
                             {:label (or (:session_id params) "Recording")
                              :route {:page :recording :params params}}]
                 [{:label "Recordings" :route {:page :recordings :params {}}}])]
    [:div {:class "breadcrumbs"}
     (for [[idx c] (map-indexed vector crumbs)]
       ^{:key (str "crumb-" idx "-" (:label c))}
       [:span {:class "crumb"}
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

(defn app
  "Root app component." 
  []
  (let [route (hooks/use-atom store/route*)]
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
         [recordings-page])]]]))

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)." 
  []
  (hsx/memo-clear!))

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
    [samuraibff.ui.hooks :as hooks]
    [samuraibff.ui.router :as router]
    [samuraibff.ui.store :as store]
    [samuraibff.ui.util :as util]
    [samuraibff.ui.ws :as ws]))

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

(defn transcript
  "Transcript display (Slack-like feed)." 
  []
  (let [segs (hooks/use-atom store/segments*)]
    [:div {:class "transcript"}
     (if (empty? segs)
       [:div {:class "empty"}
        [:div {:class "empty-title"} "Live transcript"]
        [:div {:class "muted"} "No ASR events yet…"]]
       [:div {:class "transcript-feed"}
        (for [[idx s] (map-indexed vector segs)]
          ^{:key (str "seg-" idx "-" (:received_at_ms s))}
          [:div {:class (str "seg" (when-not (:final s) " partial"))}
           [:div {:class "meta"}
            (str (util/fmt-sec (:start_s s)) " → " (util/fmt-sec (:end_s s))
                 (when-let [sp (:speaker s)] (str " · " sp))
                 (when-not (:final s) " · partial"))]
           [:div {:class "text"} (:text s)]])])]))

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
                     :active? (= page :live)}]]]))

(defn breadcrumbs
  "Breadcrumbs derived from current route." 
  [route]
  (let [{:keys [page params]} route
        crumbs (case page
                 :recordings [{:label "Recordings" :route {:page :recordings :params {}}}]
                 :live [{:label "Recordings" :route {:page :recordings :params {}}}
                        {:label "Live Recording" :route {:page :live :params {}}}]
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
  [:header {:class "topbar"}
   [:div {:class "brand"}
    [:img {:class "logo" :src "/img/nonosamurai_art.jpg" :alt "nanosamur.ai"}]
    [:div {:class "brand-name"} "nanosamur.ai"]]
   [breadcrumbs route]
   [:div {:class "topbar-right"}]])

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
         [recordings-page])]]]))

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)." 
  []
  (hsx/memo-clear!))

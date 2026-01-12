(ns samuraibff.ui.components
  "HSX (hiccup-style) React components for the SPA.

  Components here are plain React function components compiled via HSX.
  State is kept in plain CLJS atoms in `samuraibff.ui.store` and consumed
  from React via `samuraibff.ui.hooks`."
  (:require
    [io.factorhouse.hsx.core :as hsx]
    [samuraibff.ui.api :as api]
    [samuraibff.ui.audio :as audio]
    [samuraibff.ui.hooks :as hooks]
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
  "Render a small status widget for events/audio websockets." 
  []
  (let [ws-status (hooks/use-atom store/ws-status*)
        events-status (get-in ws-status [:events :status])
        audio-status (get-in ws-status [:audio :status])]
    [:div {:class "card"}
     [:div {:class "row"}
      [:div {:class "pill"}
       [:span {:class (status-dot-class events-status)}]
       [:span {:class "muted"} "events:"]
       [:span (name events-status)]]
      [:div {:class "pill"}
       [:span {:class (status-dot-class audio-status)}]
       [:span {:class "muted"} "audio:"]
       [:span (name audio-status)]]]]))

(defn controls
  "Session controls (create session, set lang, start/stop)." 
  []
  (let [{:keys [id lang]} (hooks/use-atom store/session*)
        running? (hooks/use-atom store/running?*)]
    [:div {:class "card"}
     [:div {:class "row"}
      [:button {:on-click (fn [_]
                            (store/append-log! "[ui] creating session...")
                            (-> (api/create-session!)
                                (.then (fn [sid]
                                         (store/set-session-id! sid)
                                         (store/append-log! (str "[ui] new session " sid))))
                                (.catch (fn [e]
                                          (store/append-log! (str "[ui] failed creating session: " e))))))}
       "New session"]

      [:span {:class "muted"} "session_id:"]
      [:input {:style {:minWidth "360px"}
               :value (or id "")
               :placeholder "uuid"
               :on-change (fn [e]
                            (store/set-session-id! (.. e -target -value)))}]

      [:span {:class "muted"} "lang:"]
      [:select {:value (or lang "")
                :on-change (fn [e]
                             (store/set-lang! (.. e -target -value)))}
       [:option {:value "cs"} "cs"]
       [:option {:value "en"} "en"]
       [:option {:value ""} "auto"]]

      [:button {:disabled (or running? (empty? (str id)))
                :on-click (fn [_]
                            (store/set-running! true)
                            (store/append-log! "[ui] start")
                            (ws/connect-events! id)
                            (-> (audio/start-audio! id lang)
                                (.catch (fn [_]
                                          ;; audio will log; ensure running resets
                                          (store/set-running! false))))) }
       "Start"]

      [:button {:disabled (not running?)
                :on-click (fn [_]
                            (store/append-log! "[ui] stop")
                            (store/set-running! false)
                            (audio/stop-audio!)
                            (ws/close-events!))}
       "Stop"]

      [:button {:on-click (fn [_] (store/clear-segments!))}
       "Clear transcript"]
      [:button {:on-click (fn [_] (store/clear-log!))}
       "Clear log"]]

     [:div {:class "muted" :style {:marginTop "8px"}}
      "Tip: run backend on port 8000, then start shadow watch so /js/main.js exists."]]))

(defn transcript
  "Transcript display." 
  []
  (let [segs (hooks/use-atom store/segments*)]
    [:div {:class "card"}
     [:div {:style {:fontWeight 600 :marginBottom "8px"}}
      "Transcript"]
     (if (empty? segs)
       [:div {:class "muted"} "No ASR events yet..."]
       [:div
        (for [[idx s] (map-indexed vector segs)]
          ^{:key (str "seg-" idx "-" (:received_at_ms s))}
          [:div {:class (str "seg" (when-not (:final s) " partial"))}
           [:div {:class "meta"}
            (str (util/fmt-sec (:start_s s)) " → " (util/fmt-sec (:end_s s))
                 (when-let [sp (:speaker s)] (str " · " sp))
                 (when-not (:final s) " · partial"))]
           [:div (:text s)]])])]))

(defn log-view
  "Debug log view." 
  []
  (let [lines (->> (hooks/use-atom store/log*)
                   (take-last 80))]
    [:div {:class "card"}
     [:div {:style {:fontWeight 600 :marginBottom "8px"}} "Log"]
     [:div {:class "log"}
      (if (empty? lines)
        [:span {:class "muted"} "(empty)"]
        (for [[idx line] (map-indexed vector lines)]
          ^{:key (str "log-" idx)}
          [:div line]))]]))

(defn app
  "Root app component." 
  []
  [:div
   [:div {:class "card"}
    [:div {:style {:fontWeight 700}} "samuraibff UI"]
    [:div {:class "muted" :style {:marginTop "6px"}}
     "Realtime ASR test harness (MVP) — connects to /ws/events + /ws/audio."]]
   [ws-indicator]
   [controls]
   [transcript]
   [log-view]])

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)." 
  []
  (hsx/memo-clear!))

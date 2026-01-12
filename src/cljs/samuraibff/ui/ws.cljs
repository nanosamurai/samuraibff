(ns samuraibff.ui.ws
  "WebSocket client for samuraibff UI.

  Backend websockets:
  - /ws/events?session_id=... : text frames containing JSON ws event maps
  - /ws/audio?session_id=...&lang=...&sample_rate=16000 : binary frames (PCM16LE)

  This namespace owns the *events* websocket.

  Public API:
  - connect-events!
  - close-events!"
  (:require
    [samuraibff.ui.store :as store]
    [samuraibff.ui.util :as util]))

(defonce ^:private events-ws*
  (atom nil))

(defn close-events!
  "Close the events websocket if open.

  Returns: nil."
  []
  (when-let [ws @events-ws*]
    (try (.close ws) (catch :default _ nil)))
  (reset! events-ws* nil)
  (store/set-ws-status! :events :disconnected nil)
  nil)

(defn- handle-event!
  [ev]
  (case (:type ev)
    "status" (store/append-log!
               (str "[events] status " (:status ev)
                    (when-let [d (:detail ev)] (str " (" d ")"))))
    "error" (store/append-log! (str "[events] error " (:message ev)))
    "asr" (store/upsert-asr! ev)
    (store/append-log! (str "[events] unknown event: " (pr-str ev)))))

(defn connect-events!
  "Connect the events websocket for the given session.

  Inputs:
  - session-id: string

  Returns: nil."
  [session-id]
  (close-events!)
  (if (empty? (str session-id))
    (store/append-log! "[events] cannot connect: empty session id")
    (let [url (util/ws-url "/ws/events" {:session_id session-id})
          ws (js/WebSocket. url)]
      (reset! events-ws* ws)
      (store/set-ws-status! :events :connecting url)

      (set! (.-onopen ws)
            (fn [_]
              (store/set-ws-status! :events :connected nil)
              (store/append-log! (str "[events] connected " url))))

      (set! (.-onclose ws)
            (fn [e]
              (store/set-ws-status! :events :disconnected (str "code=" (.-code e)))
              (store/append-log! (str "[events] closed code=" (.-code e)
                                      " reason=" (.-reason e)))
              (reset! events-ws* nil)))

      (set! (.-onerror ws)
            (fn [_]
              (store/set-ws-status! :events :error "onerror")
              (store/append-log! "[events] websocket error")))

      (set! (.-onmessage ws)
            (fn [msg]
              (let [data (.-data msg)]
                (try
                  (-> data
                      js/JSON.parse
                      (js->clj :keywordize-keys true)
                      handle-event!)
                  (catch :default e
                    (store/append-log! (str "[events] failed to parse message: " e)))))))

      nil)))

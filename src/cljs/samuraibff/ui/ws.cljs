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
    [samuraibff.ui.env :as env]
    [samuraibff.ui.store :as store]
    [samuraibff.ui.util :as util]))

(defonce ^:private events-ws*
  (atom nil))

(defonce ^:private terminal-tracks*
  (atom #{}))

(defonce ^:private graceful-close*
  (atom nil))

(def ^:private graceful-close-timeout-ms 10000)

(defn- clear-graceful-close!
  []
  (when-let [timer (:timer @graceful-close*)]
    (js/clearTimeout timer))
  (reset! graceful-close* nil))

(defn close-events!
  "Close the events websocket if open.

  Returns: nil."
  []
  (clear-graceful-close!)
  (when-let [ws @events-ws*]
    (try (.close ws) (catch :default _ nil)))
  (reset! events-ws* nil)
  (reset! terminal-tracks* #{})
  (store/set-ws-status! :events :disconnected nil)
  nil)

(defn- note-terminal-track!
  [track]
  (when (seq (str track))
    (let [track (str track)]
      (swap! terminal-tracks* conj track)
      (when-let [{:keys [pending]} @graceful-close*]
        (let [remaining (disj pending track)]
          (if (empty? remaining)
            (do
              (store/append-log! "[events] all realtime tracks stopped")
              (close-events!))
            (swap! graceful-close* assoc :pending remaining)))))))

(defn close-events-after-tracks!
  "Keep receiving events until every selected realtime track has terminated.

  This must be armed before closing `/ws/audio`; otherwise the BFF can lose its
  last subscriber and cancel the gRPC streams before their EOF finals arrive."
  [track-ids]
  (clear-graceful-close!)
  (let [expected (set (keep #(let [track (str %)] (when (seq track) track)) track-ids))
        remaining (reduce disj expected @terminal-tracks*)]
    (if (empty? remaining)
      (close-events!)
      (let [timer (js/setTimeout
                   (fn []
                     (when-let [{:keys [pending]} @graceful-close*]
                       (store/append-log!
                        (str "[events] terminal wait timed out tracks=" (pr-str (sort pending))))
                       (close-events!)))
                   graceful-close-timeout-ms)]
        (reset! graceful-close* {:pending remaining :timer timer})
        (store/append-log!
         (str "[events] waiting for terminal tracks=" (pr-str (sort remaining)))))))
  nil)

(defn- handle-event!
  [ev]
  (case (:type ev)
    "status" (do
               (store/append-log!
                (str "[events] status " (:status ev)
                     (when-let [track (:track ev)] (str " track=" track))
                     (when-let [d (:detail ev)] (str " (" d ")"))))
               (when (= "stopped" (:status ev))
                 (note-terminal-track! (:track ev))))
    "error" (do
              (store/append-log! (str "[events] error " (:message ev)))
              (note-terminal-track! (:track ev)))
    "asr" (store/upsert-asr! ev)
    "refined" (do
                ;; Helpful debugging: refined timing must be present and sane.
                (store/append-log!
                  (str "[events] refined raw start=" (pr-str (:start_s ev))
                       " end=" (pr-str (:end_s ev))
                       (when-let [xs (:supersedes_seq ev)] (str " supersedes=" (pr-str xs)))))

                (store/append-refined! ev))
    "workflow_result" (do
                         ;; Do not log markdown body.
                         (store/append-log!
                          (str "[events] workflow_result wf=" (pr-str (:workflow_id ev))
                               " status=" (pr-str (:status ev))
                               (when-let [t (:trigger_type ev)] (str " trigger=" (pr-str t)))))
                         (store/upsert-workflow-result! ev))
    (store/append-log! (str "[events] unknown event: " (pr-str ev)))))

(defn connect-events!
  "Connect the events websocket for the given session.

  Inputs:
  - session-id: string

  Returns: nil."
  [session-id]
  (close-events!)
  (reset! terminal-tracks* #{})
  (if (empty? (str session-id))
    (store/append-log! "[events] cannot connect: empty session id")
    (let [url (util/ws-url "/ws/events" {:session_id session-id}
                           {:backend-base-url (env/backend-base-url)})
          ws (js/WebSocket. url)]
      (reset! events-ws* ws)
      (store/set-ws-status! :events :connecting url)

      (set! (.-onopen ws)
            (fn [_]
              (store/set-ws-status! :events :connected nil)
              (store/append-log! (str "[events] connected " url))))

      (set! (.-onclose ws)
            (fn [e]
              (clear-graceful-close!)
              (reset! terminal-tracks* #{})
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

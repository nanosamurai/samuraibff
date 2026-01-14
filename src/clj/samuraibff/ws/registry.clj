(ns samuraibff.ws.registry
  "In-memory registry for websocket sessions.

  This namespace owns per-session state needed to:
  - accept binary audio frames from `/ws/audio`
  - forward them to rtservice via gRPC
  - broadcast realtime events (status/error/asr) to `/ws/events` subscribers

  The registry is intentionally in-memory only (MVP). Persistence, tenant/user,
  and session metadata will be handled in later PRs.

  ## Session state

  A session entry is a map with keys:

  - `:session-id`   string
  - `:lang`         string (empty string means auto)
  - `:sample-rate`  int

  - `:seq*`         (atom int) monotonic per-session seq for outbound WS events
  - `:chunk-seq*`   (atom int) monotonic per-session seq for outbound gRPC AudioChunk

  - `:audio-ch`     core.async channel of byte-arrays (bounded)
  - `:events-ch`    core.async channel of WS event maps (bounded)
  - `:events-mult`  core.async mult of `:events-ch`

  - `:stop-ch`      core.async channel used as a stop signal
  - `:grpc-stream*` (atom (or nil stream-map))
  - `:running?*`    (atom boolean)

  - `:events-subs*` (atom int) number of connected `/ws/events` sockets
  - `:audio-socks*` (atom int) number of connected `/ws/audio` sockets

  ### Backpressure

  We never block http-kit WS threads. Incoming audio frames are offered into a
  bounded `:audio-ch` via `core.async/offer!`. If the channel buffer is full, we
  drop the frame and log a throttled warning.

  ## Public API

  - `ensure-session!`
  - `get-session`
  - `start-rt!`
  - `offer-audio!`
  - `publish!`
  - `tap-events!` / `untap-events!`
  - `mark-audio-connected!` / `mark-audio-disconnected!`
  - `mark-events-connected!` / `mark-events-disconnected!`
  - `close-session!`

  Integrant key:
  - `:samuraibff/ws-registry`"
  (:require
    [clojure.core.async :as async]
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log]
    [samuraibff.grpc.client :as grpc]
    [samuraibff.kafka.producer :as kafka.producer]
    [samuraibff.schemas :as schemas])
  (:import
    (com.google.protobuf ByteString)
    (samuraibff.proto AsrType AudioChunk RefinedEvent)))

(def ^:private default-sample-rate
  "Default sample rate for PCM16 audio when not specified by the client."
  16000)

(def ^:private default-audio-buffer-size
  "Number of audio frames to buffer before dropping."
  32)

(def ^:private default-events-buffer-size
  "Number of outbound events to buffer before dropping."
  256)

(defn- now-ms
  "Return current epoch milliseconds."
  []
  (System/currentTimeMillis))

(defn- next-seq!
  "Increment the given atom and return the new value."
  [seq*]
  (swap! seq* inc))

(declare publish!)

(defn- status-event
  "Build a status WS event map.

  Note: `:detail` is optional and omitted when nil." 
  [session-id seq* status detail]
  (cond-> {:type "status"
           :session_id session-id
           :seq (next-seq! seq*)
           :ts_ms (now-ms)
           :status status}
    (some? detail) (assoc :detail detail)))

(defn- error-event
  "Build an error WS event map." 
  [session-id seq* message detail]
  {:type "error"
   :session_id session-id
   :seq (next-seq! seq*)
   :ts_ms (now-ms)
   :message message
   :detail detail})

(defn- asr-event->map
  "Convert a gRPC AsrEvent protobuf message into a WS event map.

  Inputs:
  - seq*  atom int (monotonic WS event seq)
  - event protobuf AsrEvent

  Output: map matching `samuraibff.schemas/AsrEvent`."
  [seq* ^samuraibff.proto.AsrEvent event]
  {:type "asr"
   :session_id (.getSessionId event)
   :seq (next-seq! seq*)
   :ts_ms (now-ms)
   :start_s (.getStartS event)
   :end_s (.getEndS event)
   :text (.getText event)
   :lang (.getLang event)
   :speaker (.getSpeaker event)
   :final (= (.getType event) AsrType/FINAL)})

(defn- validate-ws-event!
  "Validate a WS event against `schemas/WsEvent`.

  In dev/test we validate and throw on mismatch.
  In prod we skip validation for performance.

  Inputs:
  - config map (expects `:env`)
  - event map

  Returns: event map."
  [config event]
  (let [env (:env config)]
    (when (or (= env :dev) (= env :test))
      (schemas/validate! schemas/WsEvent event)))
  event)

(defn- build-chunk
  "Build a protobuf AudioChunk message.

  Inputs:
  - session-id      string
  - lang            string
  - sample-rate     int
  - chunk-seq       integer
  - bytes           byte-array
  - bff-origin-uri  string (base URI, e.g. http://127.0.0.1:8000)

  Returns: `samuraibff.proto.AudioChunk`"
  [session-id lang sample-rate chunk-seq bytes bff-origin-uri]
  (cond-> (-> (AudioChunk/newBuilder)
              (.setSessionId session-id)
              (.setSeq (long chunk-seq))
              (.setT0Ns (System/nanoTime))
              (.setSampleRate (int sample-rate))
              (.setLang (or lang ""))
              (.setPcm16Le (ByteString/copyFrom ^bytes bytes)))
    (some? bff-origin-uri) (.setBffOriginUri (str bff-origin-uri))
    :always (.build)))

(defn- resolve-bff-origin-uri
  "Resolve the base URI that this BFF instance should advertise in outgoing
  Kafka messages as `bff_origin_uri`.

  Precedence:
  1) `[:bff :origin-uri]` from config (recommended in production)
  2) derived from `[:http :host]` and `[:http :port]` (dev convenience)

  Returns: string or nil." 
  [config]
  (or (get-in config [:bff :origin-uri])
      (let [port (get-in config [:http :port])
            host0 (or (get-in config [:http :host]) "127.0.0.1")
            host (if (= host0 "0.0.0.0") "127.0.0.1" host0)]
        (when port
          (str "http://" host ":" port)))))

(defn- refined-event->map
  "Convert a protobuf RefinedEvent message into a WS event map.

  Output matches (and is validated against) `samuraibff.schemas/RefinedEvent`." 
  [seq* ^RefinedEvent ev]
  {:type "refined"
   :session_id (.getSessionId ev)
   :seq (next-seq! seq*)
   :ts_ms (now-ms)
   :start_s (.getStartS ev)
   :end_s (.getEndS ev)
   :text (.getText ev)
   :lang (.getLang ev)
   :speaker (.getSpeaker ev)
   :supersedes_seq (mapv long (.getSupersedesSeqList ev))})

(defn publish-refined-proto!
  "Publish a refined transcript event to the local WS session (if present).

  This is used by:
  - the internal callback endpoint (`POST /internal/refined`)
  - the local Kafka refined consumer (fast-path when the consumer happens to be
    on the same instance as the UI session)

  Inputs:
  - ws-registry: registry component
  - ev: protobuf `samuraibff.proto.RefinedEvent`

  Returns: boolean (true if delivered to a local session, false otherwise)." 
  [{:keys [sessions] :as ws-registry} ^RefinedEvent ev]
  (let [session-id (.getSessionId ev)
        session (get @sessions session-id)]
    (when session
      (publish! ws-registry session (refined-event->map (:seq* session) ev)))))

(defn- new-session
  "Create a new in-memory session state entry.

  Arguments:
  - session-id  string
  - config      full config map
  - opts        map with optional keys:
      :lang          string
      :sample-rate   int

  Returns a session map (see namespace docstring)."
  [session-id config {:keys [lang sample-rate]
                      :or {lang ""}}]
  (let [audio-buf-size (or (get-in config [:ws :audio-buffer-size])
                           default-audio-buffer-size)
        events-buf-size (or (get-in config [:ws :events-buffer-size])
                            default-events-buffer-size)]
    {:session-id session-id
     :lang lang
     :sample-rate (or sample-rate default-sample-rate)

     :events-subs* (atom 0)
     :audio-socks* (atom 0)

     :seq* (atom 0)
     :chunk-seq* (atom 0)
     :audio-ch (async/chan (async/buffer audio-buf-size))
     :events-ch (async/chan (async/buffer events-buf-size))
     :events-mult nil
     :stop-ch (async/chan)

     :grpc-stream* (atom nil)
     :running?* (atom false)
     :drops* (atom 0)}))

(defn get-session
  "Get a session state from the registry.

  Inputs:
  - registry  ws-registry component
  - session-id string

  Returns: session map or nil."
  [{:keys [sessions]} session-id]
  (get @sessions session-id))

(defn ensure-session!
  "Ensure a session state exists in the registry.

  Inputs:
  - registry   ws-registry component
  - session-id string
  - opts       map with keys:
      :lang         string (optional)
      :sample-rate  int (optional; defaults to 16000)

  Returns: the current session state map." 
  [{:keys [sessions config]} session-id opts]
  (let [created?* (atom false)
        session
        (get
          (swap! sessions
                 (fn [m]
                   (if (contains? m session-id)
                     m
                     (let [s0 (new-session session-id config opts)
                           s (assoc s0 :events-mult (async/mult (:events-ch s0)))]
                       (reset! created?* true)
                       (assoc m session-id s)))))
          session-id)]
    (when @created?*
      (log/info "Created ws session" {:session-id session-id
                                      :lang (:lang session)
                                      :sample-rate (:sample-rate session)}))
    session))

(defn publish!
  "Publish a WS event into the session's outbound event stream.

  Behavior:
  - validates in :dev/:test (see `schemas/WsEvent`)
  - uses `core.async/offer!` and drops if event buffer is full

  Inputs:
  - registry ws-registry component
  - session  session map
  - event    map

  Returns: boolean, true if enqueued, false if dropped or channel closed." 
  [{:keys [config]} session event]
  (let [ev (validate-ws-event! config event)]
    (try
      (boolean (async/offer! (:events-ch session) ev))
      (catch Exception e
        (log/warn e "Failed to publish ws event" {:session-id (:session-id session)
                                                  :event-type (:type event)})
        false))))

(defn tap-events!
  "Tap the session's event mult to the provided channel.

  Inputs:
  - session  session map
  - out-ch   core.async channel

  Returns: out-ch." 
  [session out-ch]
  (async/tap (:events-mult session) out-ch)
  out-ch)

(defn untap-events!
  "Untap the session's event mult from the provided channel.

  Inputs:
  - session  session map
  - out-ch   core.async channel

  Returns: nil." 
  [session out-ch]
  (async/untap (:events-mult session) out-ch)
  nil)

(defn- maybe-log-drop!
  "Increment the drop counter and log every 100th drop." 
  [session]
  (let [n (swap! (:drops* session) inc)]
    (when (zero? (mod n 100))
      (log/warn "Dropping audio frames due to backpressure" {:session-id (:session-id session)
                                                             :dropped n}))))

(defn offer-audio!
  "Offer a binary audio frame into the session's audio channel.

  This function is non-blocking and safe to call from http-kit WS threads.

  Arity:
  - (offer-audio! session bytes)
  - (offer-audio! registry session bytes)  ; registry ignored for now

  Inputs:
  - session session map
  - bytes   byte-array

  Returns: boolean (true if enqueued; false if dropped)."
  ([session bytes]
   (if (async/offer! (:audio-ch session) bytes)
     true
     (do
       (maybe-log-drop! session)
       false)))
  ([_registry session bytes]
   (offer-audio! session bytes)))

(defn close-session!
  "Close and remove a session from the registry.

  This is safe to call multiple times.

  Inputs:
  - registry    ws-registry component
  - session-id  string
  - reason      string/keyword (for logging)

  Side effects:
  - closes stop/audio/events channels
  - completes gRPC stream (if running)
  - removes session from registry

  Returns: nil." 
  [{:keys [sessions]} session-id reason]
  (when-let [session (get @sessions session-id)]
    (log/info "Closing ws session" {:session-id session-id :reason reason})
    (swap! sessions dissoc session-id)
    (try
      (when-let [stream @(:grpc-stream* session)]
        (grpc/close! stream))
      (catch Exception e
        (log/warn e "Failed to close gRPC stream" {:session-id session-id})))
    (doseq [ch [(:stop-ch session) (:audio-ch session) (:events-ch session)]]
      (try (async/close! ch) (catch Exception _ nil))))
  nil)

(defn- maybe-close-if-unused!
  "Close the session when no WS connections remain." 
  [registry session]
  (when (and (zero? @(:events-subs* session))
             (zero? @(:audio-socks* session)))
    (close-session! registry (:session-id session) "no-active-sockets")))

(defn mark-audio-connected!
  "Increment the count of connected `/ws/audio` sockets for this session." 
  [_registry session]
  (swap! (:audio-socks* session) inc)
  session)

(defn mark-audio-disconnected!
  "Decrement the count of connected `/ws/audio` sockets and close session if unused." 
  [registry session]
  (swap! (:audio-socks* session) (fn [n] (max 0 (dec n))))
  (maybe-close-if-unused! registry session)
  nil)

(defn mark-events-connected!
  "Increment the count of connected `/ws/events` sockets for this session." 
  [_registry session]
  (swap! (:events-subs* session) inc)
  session)

(defn mark-events-disconnected!
  "Decrement the count of connected `/ws/events` sockets and close session if unused." 
  [registry session]
  (swap! (:events-subs* session) (fn [n] (max 0 (dec n))))
  (maybe-close-if-unused! registry session)
  nil)

(defn start-rt!
  "Start the realtime gRPC stream and audio->gRPC loop for the given session.

  This is idempotent; calling it multiple times will only start once.

  Inputs:
  - registry     ws-registry component
  - grpc-client  gRPC client component (`:samuraibff/grpc-client`)
  - session      session map (from `ensure-session!`)

  Side effects:
  - opens gRPC bidirectional stream
  - starts a go-loop sending AudioChunk for each byte-array read from :audio-ch
  - publishes ASR events into :events-ch

  Returns: session map." 
  [registry grpc-client session]
  (if (compare-and-set! (:running?* session) false true)
    (do
      (let [session-id (:session-id session)]
        (log/info "Starting realtime gRPC stream" {:session-id session-id})
        (publish! registry session (status-event session-id (:seq* session) "started" nil))
        (let [stream (grpc/start-stream!
                      grpc-client
                      {:on-next (fn [event]
                                  (publish! registry session (asr-event->map (:seq* session) event)))
                       :on-error (fn [t]
                                   (log/error t "gRPC stream error" {:session-id session-id})
                                   (publish! registry session
                                             (error-event session-id (:seq* session) "grpc-error" (.getMessage t)))
                                   (close-session! registry session-id "grpc-error"))
                       :on-complete (fn []
                                      (log/info "gRPC stream completed" {:session-id session-id})
                                      (publish! registry session
                                                (status-event session-id (:seq* session)
                                                              "stopped" "grpc-stream-completed")))})]
          (reset! (:grpc-stream* session) stream)
          (async/go-loop []
            (let [[v ch] (async/alts! [(:stop-ch session) (:audio-ch session)] :priority true)]
              (cond
                (= ch (:stop-ch session))
                (do
                  (log/info "Stopping audio->gRPC loop" {:session-id session-id})
                  (grpc/close! stream))

                (nil? v)
                (do
                  (log/info "Audio channel closed" {:session-id session-id})
                  (grpc/close! stream))

                :else
                (let [chunk-id (next-seq! (:chunk-seq* session))
                      bff-origin-uri (resolve-bff-origin-uri (:config registry))
                      audio-chunk (build-chunk
                                    session-id
                                    (:lang session)
                                    (:sample-rate session)
                                    chunk-id
                                    v
                                    bff-origin-uri)]
                  ;; Publish to Kafka for near-realtime refinement workers.
                  (when-let [kafka-producer (:kafka-producer registry)]
                    (kafka.producer/send-audio-chunk! kafka-producer session-id audio-chunk))

                  ;; Forward to realtime gRPC ASR service.
                  (try
                    ((:send! stream) audio-chunk)
                    (catch Exception e
                      (log/error e "gRPC send failed" {:session-id session-id})
                      (publish! registry session
                                (error-event session-id (:seq* session) "grpc-send-failed" (.getMessage e)))
                      (close-session! registry session-id "grpc-send-failed")))
                  (recur))))))
        session))
    session))

(defmethod ig/init-key :samuraibff/ws-registry
  [_ {:keys [config kafka-producer]}]
  {:config config
   :kafka-producer kafka-producer
   :sessions (atom {})})

(defmethod ig/halt-key! :samuraibff/ws-registry
  [_ {:keys [sessions] :as registry}]
  (when (instance? clojure.lang.IAtom sessions)
    (doseq [session-id (keys @sessions)]
      (close-session! registry session-id "integrant-halt"))
    (reset! sessions {}))
  nil)

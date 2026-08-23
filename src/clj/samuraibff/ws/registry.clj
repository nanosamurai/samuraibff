(ns samuraibff.ws.registry
  "In-memory registry for websocket sessions.

  This namespace owns per-session state needed to:
  - accept binary audio frames from `/ws/audio`
  - forward them to rtservice via gRPC
  - broadcast realtime events (status/error/asr) to `/ws/events` subscribers

  The registry is intentionally in-memory only (MVP). Persistence, tenant/user,
  and session metadata will be handled in later PRs.

  ## Multi-tenant model

  Sessions are stored *per tenant*:

  `tenant-id -> session-id -> session`.

  This allows all UI/WS entry points to look up sessions only within the
  authenticated tenant, making cross-tenant access impossible by construction.

  IMPORTANT:
  - When auth is required, `tenant-id` must be non-nil.
  - Internal refined delivery (`/internal/refined` and refined Kafka consumer)
    relies on `RefinedEvent.tenant_id` being present.

  ## Session state

  A session entry is a map with keys:

  - `:session-id`   string
  - `:tenant-id`    (or string nil) ; tenant ownership / isolation boundary
  - `:lang`         string (empty string means auto)
  - `:sample-rate`  int

  - `:rt-window-sec`     double? ; optional rtservice override
  - `:rt-overlap-sec`    double? ; optional rtservice override
  - `:rt-emit-every-sec` double? ; optional rtservice override

  - `:rt-partial-enable?` boolean? ; optional rtservice control

  - `:want-realtime?` boolean ; whether BFF starts rtservice gRPC
  - `:realtime-track-ids` vector of selected operator-configured track IDs
  - `:want-refined?` boolean  ; whether BFF publishes to Kafka for refined pipeline
  - `:want-final?` boolean    ; whether BFF publishes to Kafka for final pipeline
  - `:store-recording?` boolean ; forwarded via Kafka header

  - `:kafka-headers` map string->bytes ; precomputed stream headers for audio.raw

  - `:seq*`         (atom int) monotonic per-session seq for outbound WS events
  - `:chunk-seq*`   (atom int) monotonic per-session seq for outbound gRPC AudioChunk

  - `:audio-ch`     core.async channel of byte-arrays (bounded)
  - `:events-ch`    core.async channel of WS event maps (bounded)
  - `:events-mult`  core.async mult of `:events-ch`

  - `:stop-ch`      core.async channel used as a stop signal
  - `:realtime-fanout*` (atom (or nil fan-out-map))
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
   [clojure.string :as str]
   [integrant.core :as ig]
   [org.corfield.logging4j2 :as log]
   [samuraibff.session-trace :as session-trace]
   [samuraibff.grpc.fanout :as grpc.fanout]
   [samuraibff.grpc.metadata :as grpc.metadata]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.schemas :as schemas])
  (:import
   (com.google.protobuf ByteString)
    (samuraibff.proto AsrType AudioChunk RefinedEvent SessionTranscriptSegment)))

(defn- ensure-bytes-header-map
  "Ensure a kafka header map has string keys and byte[] values.

  Returns: map."
  [m]
  (into {}
        (keep (fn [[k v]]
                (when (and (string? k) (bytes? v))
                  [k v])))
        (or m {})))

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
  - seq*                atom int (monotonic WS event seq)
  - track               configured BFF track ID
  - primary?            whether this is the compatibility/default track
  - provider-profile-id capability-handshake fallback profile ID
  - event               protobuf AsrEvent

  Output: map matching `samuraibff.schemas/AsrEvent`."
  [seq* track primary? provider-profile-id ^samuraibff.proto.AsrEvent event]
  {:type "asr"
   :session_id (.getSessionId event)
   :track track
   :primary_track (boolean primary?)
   :provider_profile_id (or (not-empty (.getProviderProfileId event))
                            (not-empty provider-profile-id)
                            track)
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
  - tenant-id       string (or nil in dev)
  - lang            string
  - sample-rate     int
  - chunk-seq       integer
  - bytes           byte-array
  - bff-origin-uri  string (base URI, e.g. http://127.0.0.1:8000)

  Returns: `samuraibff.proto.AudioChunk`"
  [session-id tenant-id lang sample-rate chunk-seq bytes bff-origin-uri]
  (cond-> (-> (AudioChunk/newBuilder)
              (.setSessionId session-id)
              (.setSeq (long chunk-seq))
              (.setT0Ns (System/nanoTime))
              (.setSampleRate (int sample-rate))
              (.setLang (or lang ""))
              (.setPcm16Le (ByteString/copyFrom ^bytes bytes)))
    (some? tenant-id) (.setTenantId (str tenant-id))
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

(defn- warn-suspicious-refined-times!
  "Log a warning when a refined segment has suspicious start/end timings.

  Inputs:
  - ctx: map of extra log context
  - start: double
  - end: double

  Returns: nil."
  [ctx start end]
  (when (and (number? start) (number? end) (<= (double end) (double start)))
    (log/warn "Suspicious refined times (start/end)" (assoc ctx :start_s start :end_s end)))
  nil)

(defn- refined-scalar->ws-event
  "Convert a protobuf RefinedEvent scalar payload into a single WS event map.

  This is a backwards-compat path. New workers SHOULD populate `segments`, but
  older workers may still send only start/end/text/speaker.

  Output matches (and is validated against) `samuraibff.schemas/RefinedEvent`.

  Debugging note:
  - In protobuf3, missing doubles default to 0.0.
  - If the worker forgets to populate start/end, the UI will see 0..0."
  [seq* ts-ms ^RefinedEvent ev]
  (let [start (.getStartS ev)
        end (.getEndS ev)
        supersedes (mapv long (.getSupersedesSeqList ev))]
    (warn-suspicious-refined-times!
     {:session-id (.getSessionId ev)
      :lang (.getLang ev)
      :supersedes_seq supersedes
      :text-len (count (str (.getText ev)))}
     start
     end)
    {:type "refined"
     :session_id (.getSessionId ev)
     :seq (next-seq! seq*)
     :ts_ms ts-ms
     :start_s start
     :end_s end
     :text (.getText ev)
     :lang (.getLang ev)
     :speaker (.getSpeaker ev)
     :supersedes_seq supersedes}))

(defn- segment->ws-event
  "Convert a `SessionTranscriptSegment` into a WS refined event map.

  Inputs:
  - seq*: atom int (session WS sequence)
  - ts-ms: epoch milliseconds (shared across all derived segments)
  - session-id: string
  - lang: string
  - supersedes: vector<long>
  - seg: protobuf `samuraibff.proto.SessionTranscriptSegment`

  Returns: map matching `samuraibff.schemas/RefinedEvent`."
  [seq* ts-ms session-id lang supersedes ^SessionTranscriptSegment seg]
  (let [start (.getStartS seg)
        end (.getEndS seg)
        text (.getText seg)
        speaker (.getSpeaker seg)]
    (warn-suspicious-refined-times!
     {:session-id session-id
      :lang lang
      :supersedes_seq supersedes
      :speaker speaker
      :text-len (count (str text))}
     start
     end)
    {:type "refined"
     :session_id session-id
     :seq (next-seq! seq*)
     :ts_ms ts-ms
     :start_s start
     :end_s end
     :text text
     :lang lang
     :speaker speaker
     :supersedes_seq supersedes}))

(defn- refined-event->ws-events
  "Convert a protobuf `RefinedEvent` into one or more WS refined events.

  New semantics:
  - `RefinedEvent` represents a refinement window slice.
  - The worker may emit multiple speaker turns as `segments`.
  - The BFF fans out a single protobuf event into N WS events (one per segment)
    so the UI can keep its existing message-per-segment model.

  Backwards compatibility:
  - If `segments` is empty, we fall back to the legacy scalar fields.

  Returns: vector of event maps compatible with `samuraibff.schemas/RefinedEvent`."
  [seq* ^RefinedEvent ev]
  (let [ts-ms (now-ms)
        session-id (.getSessionId ev)
        supersedes (mapv long (.getSupersedesSeqList ev))]
    (if (pos? (.getSegmentsCount ev))
      (let [segments (->> (.getSegmentsList ev)
                          (sort-by (fn [^SessionTranscriptSegment s] (double (.getStartS s))))
                          vec)]
        (when (and (seq (str (.getText ev)))
                   (seq segments))
          (log/info "RefinedEvent contains segments; legacy scalar fields will be ignored" {:session-id session-id
                                                                                           :segments (count segments)
                                                                                           :slice_index (.getSliceIndex ev)
                                                                                           :window_sec (.getWindowSec ev)
                                                                                           :flush_reason (.getFlushReason ev)}))
        (mapv (fn [seg]
                (segment->ws-event seq* ts-ms session-id (.getLang ev) supersedes seg))
              segments))
      [(refined-scalar->ws-event seq* ts-ms ev)])))

(defn publish-refined-proto!
  "Publish a refined transcript event to the local WS session (if present).

  This is used by:
  - the internal callback endpoint (`POST /internal/refined`)
  - the local Kafka refined consumer (fast-path when the consumer happens to be
    on the same instance as the UI session)

  Inputs:
  - ws-registry: registry component
  - ev: protobuf `samuraibff.proto.RefinedEvent`

  Behavior:
  - requires `RefinedEvent.tenant_id` to be non-blank

  Returns: boolean.
  - true if the session exists locally (even if some events are dropped due to backpressure)
  - false if the session is not present locally or tenant_id is missing."
  [{:keys [sessions] :as ws-registry} ^RefinedEvent ev]
  (let [session-id (.getSessionId ev)
        tenant-id (let [t (.getTenantId ev)]
                    (when (and t (not (str/blank? t))) t))]
    (if-not tenant-id
      (do
        (log/warn "RefinedEvent missing tenant_id; dropping" {:session-id session-id})
        false)
      (let [session (get-in @sessions [tenant-id session-id])]
        (when session
          (let [events (refined-event->ws-events (:seq* session) ev)
                results (mapv (fn [event]
                                (publish! ws-registry session event))
                              events)
                dropped (count (remove true? results))]
            (when (pos? dropped)
              (log/warn "Dropped refined WS events due to backpressure" {:session-id session-id
                                                                         :tenant-id tenant-id
                                                                         :dropped dropped
                                                                         :total (count events)}))
            true))))))

(defn- new-session
  "Create a new in-memory session state entry.

  Arguments:
  - tenant-id   string or nil
  - session-id  string
  - config      full config map
  - opts        map with optional keys:
      :lang         string
      :sample-rate  int

      ; Optional rtservice per-session override knobs.
      ; When present, these are attached as gRPC metadata headers.
      :rt-window-sec     double
      :rt-overlap-sec    double
      :rt-emit-every-sec double

      ; Optional realtime control.
      :rt-partial-enable? boolean

      ; Output selection snapshot.
      :want-realtime? boolean
      :want-refined? boolean
      :want-final? boolean
      :store-recording? boolean

      ; Precomputed Kafka headers for this session.
      :kafka-headers {string bytes}

  Returns a session map (see namespace docstring)."
  [tenant-id session-id config {:keys [lang sample-rate
                                       rt-window-sec rt-overlap-sec rt-emit-every-sec
                                       rt-partial-enable?
                                       want-realtime? want-refined? want-final?
                                       realtime-track-ids
                                       store-recording?
                                       kafka-headers]
                                :or {lang ""}}]
  (let [audio-buf-size (or (get-in config [:ws :audio-buffer-size])
                           default-audio-buffer-size)
        events-buf-size (or (get-in config [:ws :events-buffer-size])
                            default-events-buffer-size)]
    {:session-id session-id
     :tenant-id tenant-id
     :lang lang
     :sample-rate (or sample-rate default-sample-rate)

     ;; Optional per-session rtservice override knobs.
     ;; NOTE: BFF does not enforce business limits; rtservice does.
     ;; We only ensure the values are numeric so we can serialize them.
     :rt-window-sec (when (number? rt-window-sec) (double rt-window-sec))
     :rt-overlap-sec (when (number? rt-overlap-sec) (double rt-overlap-sec))
     :rt-emit-every-sec (when (number? rt-emit-every-sec) (double rt-emit-every-sec))

     :rt-partial-enable? (when (some? rt-partial-enable?) (boolean rt-partial-enable?))

     :want-realtime? (boolean (if (some? want-realtime?) want-realtime? true))
     :realtime-track-ids (when (some? realtime-track-ids) (vec realtime-track-ids))
     :want-refined? (boolean (if (some? want-refined?) want-refined? true))
     :want-final? (boolean (if (some? want-final?) want-final? true))
     :store-recording? (boolean (if (some? store-recording?) store-recording? true))

     :kafka-headers (ensure-bytes-header-map kafka-headers)

     :events-subs* (atom 0)
     :audio-socks* (atom 0)

     :seq* (atom 0)
     :chunk-seq* (atom 0)
     :audio-ch (async/chan (async/buffer audio-buf-size))
     :events-ch (async/chan (async/buffer events-buf-size))
     :events-mult nil
     :stop-ch (async/chan)

     :realtime-fanout* (atom nil)
     :running?* (atom false)
     :drops* (atom 0)}))

(defn get-session
  "Get a session state from the registry.

  Inputs:
  - registry   ws-registry component
  - tenant-id  string (or nil in dev)
  - session-id string

  Returns: session map or nil."
  [{:keys [sessions]} tenant-id session-id]
  (get-in @sessions [tenant-id session-id]))

(defn ensure-session!
  "Ensure a session state exists in the registry.

  Inputs:
  - registry   ws-registry component
  - tenant-id  string (or nil in dev)
  - session-id string
  - opts       map with keys:
      :lang         string (optional)
      :sample-rate  int (optional; defaults to 16000)

  Returns: the current session state map."
  [{:keys [sessions config]} tenant-id session-id opts]
  (let [created?* (atom false)
        session
        (get-in
         (swap! sessions
                (fn [m]
                  (if (get-in m [tenant-id session-id])
                    m
                    (let [s0 (new-session tenant-id session-id config opts)
                          s (assoc s0 :events-mult (async/mult (:events-ch s0)))]
                      (reset! created?* true)
                      (assoc-in m [tenant-id session-id] s)))))
         [tenant-id session-id])]
    (when @created?*
      (log/info "Created ws session" {:session-id session-id
                                      :tenant-id tenant-id
                                      :lang (:lang session)
                                      :sample-rate (:sample-rate session)}))
    session))

(defn update-session-controls!
  "Update session control fields (:lang / :sample-rate) for an existing session.

  This is used primarily by `/ws/audio`, because sessions are often created first
  via `POST /api/sessions` (without lang), and only later the UI connects audio
  with `?lang=...&sample_rate=...`.

  Safety:
  - If the realtime stream is already running (`@(:running?* session)`), controls
    are not modified.

  Inputs:
  - ws-registry: registry component
  - tenant-id: string or nil
  - session-id: string
  - opts: map with optional keys:
      :lang string
      :sample-rate int

  Returns:
  - the (possibly updated) session map, or nil if session not found."
  [{:keys [sessions]} tenant-id session-id {:keys [lang sample-rate
                                                   rt-window-sec rt-overlap-sec rt-emit-every-sec
                                                    rt-partial-enable?
                                                    want-realtime? want-refined? want-final?
                                                    realtime-track-ids
                                                    store-recording?
                                                   kafka-headers]}]
  (let [updated* (atom nil)]
    (swap! sessions
           (fn [m]
             (if-let [session (get-in m [tenant-id session-id])]
               (if @(:running?* session)
                 (do
                   (reset! updated* session)
                   m)
                 (let [session' (cond-> session
                                  (some? lang) (assoc :lang (str lang))
                                  (some? sample-rate) (assoc :sample-rate (int sample-rate))
                                  (some? rt-window-sec) (assoc :rt-window-sec (when (number? rt-window-sec) (double rt-window-sec)))
                                  (some? rt-overlap-sec) (assoc :rt-overlap-sec (when (number? rt-overlap-sec) (double rt-overlap-sec)))
                                  (some? rt-emit-every-sec) (assoc :rt-emit-every-sec (when (number? rt-emit-every-sec) (double rt-emit-every-sec)))

                                   (some? rt-partial-enable?) (assoc :rt-partial-enable? (boolean rt-partial-enable?))
                                   (some? want-realtime?) (assoc :want-realtime? (boolean want-realtime?))
                                   (some? realtime-track-ids) (assoc :realtime-track-ids (vec realtime-track-ids))
                                   (some? want-refined?) (assoc :want-refined? (boolean want-refined?))
                                  (some? want-final?) (assoc :want-final? (boolean want-final?))
                                  (some? store-recording?) (assoc :store-recording? (boolean store-recording?))
                                  (some? kafka-headers) (assoc :kafka-headers (ensure-bytes-header-map kafka-headers)))]
                   (reset! updated* session')
                   (assoc-in m [tenant-id session-id] session')))
               (do
                 (reset! updated* nil)
                 m))))
    @updated*))

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

(def ^:private max-workflow-markdown-chars
  "Maximum number of markdown characters to include in a workflow_result WS event.

  Purpose:
  - Prevent huge websocket frames from causing memory pressure or UI freezes.

  Notes:
  - We keep this relatively small; the full result is always persisted and can be
    fetched from DB in the recordings detail page." 
  8000)

(defn- truncate-markdown
  "Truncate markdown string to a safe maximum.

  Inputs:
  - s: string?

  Returns:
  - string? (possibly truncated)" 
  [s]
  (let [s (when (some? s) (str s))]
    (when (seq (str s))
      (if (> (count s) max-workflow-markdown-chars)
        (str (subs s 0 max-workflow-markdown-chars) "\n\n…")
        s))))

(defn publish-workflow-result!
  "Publish a workflow result WS event to the local WS session (if present).

  This is used by:
  - the internal callback endpoint (`POST /internal/workflow-result`)
  - the Kafka consumer for workflow.result

  Inputs:
  - ws-registry: registry component
  - payload: map with required keys:
      :tenant_id string/uuid
      :session_id string/uuid
      :workflow_id string/uuid
      :status string
    optional:
      :workflow_name, :workflow_run_id, :created_at, :trigger_type,
      :render_markdown, :error_code, :error_detail

  Behavior:
  - requires tenant_id to be non-blank
  - publishes a single `workflow_result` WS event

  Returns: boolean.
  - true if the session exists locally (even if event is dropped due to backpressure)
  - false if the session is not present locally or tenant_id is missing." 
  [{:keys [sessions] :as ws-registry} {:keys [tenant_id tenant-id session_id session-id workflow_id workflow-id] :as payload}]
  (let [tenant-id (or tenant-id tenant_id)
        tenant-id (when (and tenant-id (not (str/blank? (str tenant-id)))) (str tenant-id))
        session-id (str (or session-id session_id ""))
        workflow-id (str (or workflow-id workflow_id ""))]
    (cond
      (str/blank? tenant-id)
      (do
        (log/warn "Workflow result missing tenant_id; dropping" {:session-id session-id
                                                                 :workflow-id workflow-id})
        false)

      (or (str/blank? session-id) (str/blank? workflow-id))
      (do
        (log/warn "Workflow result missing ids; dropping" {:tenant-id tenant-id
                                                           :session-id session-id
                                                           :workflow-id workflow-id})
        false)

      :else
      (let [session (get-in @sessions [tenant-id session-id])]
        (when session
          (let [event {:type "workflow_result"
                       :session_id session-id
                       :seq (next-seq! (:seq* session))
                       :ts_ms (now-ms)
                       :workflow_id workflow-id
                       :workflow_name (:workflow_name payload)
                       :workflow_run_id (:workflow_run_id payload)
                       :created_at (:created_at payload)
                       :trigger_type (:trigger_type payload)
                       :status (str (or (:status payload) ""))
                       :render_markdown (truncate-markdown (:render_markdown payload))
                       :error_code (:error_code payload)
                       :error_detail (:error_detail payload)}
                event (into {} (remove (fn [[_k v]] (nil? v)) event))]
            ;; Do NOT log the markdown body.
            (log/info "Publishing workflow result to WS" {:tenant-id tenant-id
                                                          :session-id session-id
                                                          :workflow-id workflow-id
                                                          :status (:status event)})
            ;; even when dropped, session exists locally, so we return true
            (publish! ws-registry session event)
            true))))))

(defn- maybe-log-drop!
  "Increment the drop counter and log every 100th drop."
  [session]
  (let [n (swap! (:drops* session) inc)]
    (when (zero? (mod n 100))
      (log/warn "Dropping audio frames due to backpressure" {:session-id (:session-id session)
                                                             :tenant-id (:tenant-id session)
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
  - tenant-id   string (or nil)
  - session-id  string
  - reason      string/keyword (for logging)

  Side effects:
  - closes stop/audio/events channels
  - cancels active realtime tracks (if running)
  - removes session from registry

  Returns: nil."
  [{:keys [sessions]} tenant-id session-id reason]
  (when-let [session (get-in @sessions [tenant-id session-id])]
    (log/info "Closing ws session" {:session-id session-id :tenant-id tenant-id :reason reason})
    (swap! sessions update tenant-id dissoc session-id)
    (try
      (when-let [fanout @(:realtime-fanout* session)]
        (grpc.fanout/cancel! fanout "BFF session closed"))
      (catch Exception e
        (log/warn e "Failed to cancel realtime tracks" {:session-id session-id :tenant-id tenant-id})))
    (doseq [ch [(:stop-ch session) (:audio-ch session) (:events-ch session)]]
      (try (async/close! ch) (catch Exception _ nil))))
  nil)

(defn- maybe-close-if-unused!
  "Close the session when no WS connections remain."
  [registry session]
  (when (and (zero? @(:events-subs* session))
             (zero? @(:audio-socks* session)))
    (close-session! registry (:tenant-id session) (:session-id session) "no-active-sockets")))

(defn mark-audio-connected!
  "Increment the count of connected `/ws/audio` sockets for this session."
  [_registry session]
  (swap! (:audio-socks* session) inc)
  session)

(defn mark-audio-disconnected!
  "Decrement connected `/ws/audio` sockets and finish input after the last one.

  Closing `:audio-ch` drains its buffered frames before the forwarding loop
  half-closes realtime gRPC. The events side remains open until its subscribers
  disconnect, allowing rtservice to deliver a terminal ASR event.

  Inputs:
  - registry ws-registry component
  - session  session map

  Returns: nil."
  [registry session]
  (let [remaining (swap! (:audio-socks* session) (fn [n] (max 0 (dec n))))]
    (when (zero? remaining)
      (log/info "Finishing audio input" {:session-id (:session-id session)
                                          :tenant-id (:tenant-id session)})
      (async/close! (:audio-ch session))))
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
  "Start the selected realtime tracks and the shared audio dispatch loop for a session.

  This is idempotent; calling it multiple times will only start once.

  Inputs:
  - registry     ws-registry component
  - grpc-client  configured peer clients (`:samuraibff/grpc-client`)
  - session      session map (from `ensure-session!`)

  Side effects:
  - opens one independently buffered gRPC stream per selected registered track
  - publishes each AudioChunk to Kafka once and offers it to every active track
  - publishes track-labelled ASR events into :events-ch

  Returns: session map."
  [registry grpc-client session]
  (if (compare-and-set! (:running?* session) false true)
    (let [session-id (:session-id session)
          tenant-id (:tenant-id session)]
      (publish! registry session (status-event session-id (:seq* session) "started" nil))

      (when-not (:want-realtime? session)
        (log/info "Realtime output disabled; not starting gRPC" {:session-id session-id
                                                                 :tenant-id tenant-id}))

      (let [metadata (cond-> {}
                       (some? (:rt-window-sec session))
                       (assoc "x-rt-window-sec" (grpc.metadata/header-double (:rt-window-sec session)))

                       (some? (:rt-overlap-sec session))
                       (assoc "x-rt-overlap-sec" (grpc.metadata/header-double (:rt-overlap-sec session)))

                       (some? (:rt-emit-every-sec session))
                       (assoc "x-rt-emit-every-sec" (grpc.metadata/header-double (:rt-emit-every-sec session)))

                       (some? (:rt-partial-enable? session))
                       (assoc "x-rt-partial-enable" (if (:rt-partial-enable? session) "true" "false")))
            metadata (into {} (remove (fn [[_k v]] (nil? v)) metadata))
            fanout
            (when (:want-realtime? session)
              (log/info "Starting realtime ASR tracks" {:session-id session-id
                                                         :tenant-id tenant-id
                                                         :tracks (:realtime-track-ids session)})
              (try
                (grpc.fanout/start!
                 grpc-client
                 {:on-next
                  (fn [{:keys [track primary? provider-profile-id event]}]
                    (publish! registry session
                              (asr-event->map (:seq* session) track primary? provider-profile-id event)))
                  :on-error
                  (fn [track error]
                    (log/error error "Realtime ASR track failed"
                               {:session-id session-id :tenant-id tenant-id :track track})
                    (publish! registry session
                              (assoc (error-event session-id (:seq* session)
                                                  "realtime-track-failed" (str "track=" track))
                                     :track track)))
                  :on-complete
                  (fn [track]
                    (log/info "Realtime ASR track completed"
                              {:session-id session-id :tenant-id tenant-id :track track})
                    (publish! registry session
                              (assoc (status-event session-id (:seq* session)
                                                   "stopped" "grpc-stream-completed")
                                     :track track)))}
                 {:buffer-size (or (get-in (:config registry) [:grpc :track-buffer-size])
                                   default-audio-buffer-size)
                  :metadata metadata
                  :track-ids (:realtime-track-ids session)})
                (catch Throwable t
                  (reset! (:running?* session) false)
                  (log/error t "Failed to start realtime ASR tracks"
                             {:session-id session-id :tenant-id tenant-id})
                  (publish! registry session
                            (error-event session-id (:seq* session)
                                         "grpc-start-failed" "realtime tracks could not start"))
                  (throw t))))]
        (reset! (:realtime-fanout* session) fanout)
        (async/go-loop []
          (let [[v ch] (async/alts! [(:stop-ch session) (:audio-ch session)] :priority true)]
            (cond
              (= ch (:stop-ch session))
              (do
                (log/info "Stopping audio forward loop" {:session-id session-id :tenant-id tenant-id})
                (when fanout
                  (grpc.fanout/cancel! fanout "audio forward loop stopped")))

              (nil? v)
              (do
                (log/info "Audio channel closed" {:session-id session-id :tenant-id tenant-id})
                (when fanout
                  (grpc.fanout/complete! fanout)))

              :else
              (let [chunk-id (next-seq! (:chunk-seq* session))
                    bff-origin-uri (resolve-bff-origin-uri (:config registry))
                    audio-chunk (build-chunk
                                 session-id
                                 tenant-id
                                 (:lang session)
                                 (:sample-rate session)
                                 chunk-id
                                 v
                                 bff-origin-uri)]
                ;; Ensure a stable trace context (trace_id == normalized session_id)
                ;; is current for both Kafka and gRPC. The OTEL Java agent will
                ;; inject W3C trace context automatically.
                (session-trace/with-session-trace session-id
                  ;; Publish to Kafka only when refined/final are requested.
                  (when (and (or (:want-refined? session) (:want-final? session))
                             (some? (:kafka-producer registry)))
                    (kafka.producer/send-audio-chunk!
                     (:kafka-producer registry)
                     session-id
                     audio-chunk
                     {:tenant-id tenant-id
                      :headers (:kafka-headers session)}))

                  (when fanout
                    (doseq [track (grpc.fanout/offer! fanout audio-chunk)]
                      (log/warn "Realtime track dropped for backpressure"
                                {:session-id session-id :tenant-id tenant-id :track track})
                      (publish! registry session
                                (assoc (error-event session-id (:seq* session)
                                                    "realtime-track-overloaded" (str "track=" track))
                                       :track track)))))
                (recur)))))
        session))
    session))

(defmethod ig/init-key :samuraibff/ws-registry
  [_ {:keys [config kafka-producer]}]
  {:config config
   :kafka-producer kafka-producer
   ;; tenant-id -> session-id -> session
   :sessions (atom {})})

(defmethod ig/halt-key! :samuraibff/ws-registry
  [_ {:keys [sessions] :as registry}]
  (when (instance? clojure.lang.IAtom sessions)
    (doseq [[tenant-id sessions-by-id] @sessions
            session-id (keys sessions-by-id)]
      (close-session! registry tenant-id session-id "integrant-halt"))
    (reset! sessions {}))
  nil)

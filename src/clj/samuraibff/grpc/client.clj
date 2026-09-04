(ns samuraibff.grpc.client
  "gRPC client component for an allowlisted set of peer realtime ASR tracks.

  Public API:
  - `start-stream!` – open a bidirectional stream and provide callbacks
    for inbound ASR events / lifecycle notifications.
  - `close!` – helper to close a started stream (completes outbound side).

  The component is registered under the `:samuraibff/grpc-client` Integrant
  key. Each configured track owns an independent channel and RealtimeASR stub.
  Channels use plaintext transport inside the workload network for now."
  (:require
   [clojure.string :as str]
   [integrant.core :as ig]
   [org.corfield.logging4j2 :as log])
  (:import
   (io.grpc ClientInterceptor ManagedChannel ManagedChannelBuilder Metadata Metadata$Key Status)
   (io.grpc.stub MetadataUtils StreamObserver)
   (java.util.concurrent TimeUnit)
   (samuraibff.proto AsrType RealtimeASRGrpc RealtimeCapabilitiesRequest)))

(def ^:private default-admission-timeout-ms 3000)
(def ^:private default-admission-max-attempts 8)

(defn- map->metadata
  "Convert a Clojure map of header-name -> string into gRPC Metadata.

  Inputs:
  - m: map (string->string)

  Returns: io.grpc.Metadata." 
  ^Metadata [m]
  (let [md (Metadata.)]
    (doseq [[k v] (or m {})]
      (when (and (string? k) (string? v))
        (let [^Metadata$Key key (Metadata$Key/of k Metadata/ASCII_STRING_MARSHALLER)]
          (.put md key v))))
    md))

(defn- build-channel
  "Create a DNS-resolving, round-robin channel for an rtservice address.

  addr - host:port string.

  Returns an open ManagedChannel instance."
  [addr]
  (let [target (if (str/includes? addr ":///") addr (str "dns:///" addr))]
    (-> (ManagedChannelBuilder/forTarget target)
      (.defaultLoadBalancingPolicy "round_robin")
      (.usePlaintext)
      (.build))))

(defn- configured-tracks
  "Return validated realtime track definitions from global configuration.

  Inputs:
  - config: global BFF configuration map

  Returns a non-empty vector of `{:id string :address string}` maps. The legacy
  `:rtservice-addr` setting remains a single-track compatibility fallback."
  [config]
  (let [configured (get-in config [:grpc :realtime-tracks])
        legacy-address (get-in config [:grpc :rtservice-addr])
        tracks (if (seq configured)
                 (vec configured)
                 (when (seq (str legacy-address))
                   [{:id "default" :address (str legacy-address)}]))
        ids (mapv :id tracks)]
    (when-not (seq tracks)
      (throw (ex-info "At least one realtime ASR track must be configured" {})))
    (when (or (> (count tracks) 4)
              (not= (count ids) (count (distinct ids)))
              (some #(or (not (seq (str (:id %))))
                         (not (seq (str (:address %)))))
                    tracks))
      (throw (ex-info "Realtime ASR tracks must have unique IDs and addresses" {:tracks tracks})))
    tracks))

(defn- capabilities->map
  "Convert a RealtimeCapabilities protobuf into a Clojure data map."
  [capabilities]
  {:provider-profile-id (.getProviderProfileId capabilities)
   :windowed-realtime? (.getWindowedRealtime capabilities)
   :native-streaming? (.getNativeStreaming capabilities)
   :batch? (.getBatch capabilities)
   :segment-timestamps? (.getSegmentTimestamps capabilities)
   :word-timestamps? (.getWordTimestamps capabilities)
   :language-detection? (.getLanguageDetection capabilities)
   :supported-languages (vec (.getSupportedLanguagesList capabilities))
   :stateful? (.getStateful capabilities)
   :preferred-sample-rate (.getPreferredSampleRate capabilities)
   :maximum-audio-seconds (.getMaximumAudioSeconds capabilities)
   :maximum-concurrent-sessions (.getMaximumConcurrentSessions capabilities)
   :runtime (.getRuntime capabilities)
   :model-revision (.getModelRevision capabilities)
   :model-digest (.getModelDigest capabilities)
   :implementation-revision (.getImplementationRevision capabilities)
   :speaker-labels? (.getSpeakerLabels capabilities)
   :aligned-diarized-languages
   (vec (.getAlignedDiarizedLanguagesList capabilities))})

(defmethod ig/init-key :samuraibff/grpc-client [_ {:keys [config]}]
  "Initialize one independent gRPC client for each configured realtime track.

  Configuration should contain `[:grpc :realtime-tracks]`; the legacy
  `[:grpc :rtservice-addr]` is accepted as a one-track fallback.

  Returns `{:tracks [...]}` where each track contains its ID, address, channel,
  asynchronous stub, and blocking capability-discovery stub."
  (let [track-definitions (configured-tracks config)
        track-clients
        (mapv
         (fn [{:keys [id address]}]
           (log/info "Registering realtime ASR track" {:track id :address address})
           (let [channel (build-channel address)]
             {:id id
              :address address
              :channel channel
              :stub (RealtimeASRGrpc/newStub channel)
              :blocking-stub (RealtimeASRGrpc/newBlockingStub channel)}))
         track-definitions)]
    {:tracks track-clients}))

(defmethod ig/halt-key! :samuraibff/grpc-client [_ {:keys [tracks]}]
  (doseq [{:keys [id channel]} tracks]
    (when (instance? ManagedChannel channel)
      (try
        (.shutdown channel)
        (.awaitTermination channel 5 TimeUnit/SECONDS)
        (catch InterruptedException _
          (.shutdownNow channel)
          (.interrupt (Thread/currentThread)))
        (catch Exception e
          (log/error e "Failed to shutdown realtime gRPC channel" {:track id}))))))

(defn tracks
  "Return the ordered vector of configured realtime track clients.

  Inputs:
  - component: map returned by the Integrant component

  Returns a vector of track client maps."
  [component]
  (vec (:tracks component)))

(defn get-capabilities
  "Fetch current capabilities from one realtime service.

  Inputs:
  - track-client: one map returned by `tracks`
  - timeout-ms: positive deadline in milliseconds

  Returns a Clojure capability map or throws the underlying gRPC exception."
  [{:keys [blocking-stub]} timeout-ms]
  (when-not blocking-stub
    (throw (ex-info "Realtime capability stub missing" {})))
  (-> blocking-stub
      (.withDeadlineAfter (long timeout-ms) TimeUnit/MILLISECONDS)
      (.getCapabilities (RealtimeCapabilitiesRequest/getDefaultInstance))
      capabilities->map))

(defn- replica-full?
  "Return true only for the pod admission rejection that is safe to retry."
  [throwable]
  (let [status (Status/fromThrowable throwable)]
    (and (= io.grpc.Status$Code/RESOURCE_EXHAUSTED (.getCode status))
         (= "REPLICA_FULL" (.getDescription status)))))

(defn- open-stream-once!
  "Open one RPC and wait until a specific serving replica admits it."
  [{:keys [id stub]} {:keys [on-next on-error on-complete metadata admission-timeout-ms]}]
  (let [closed?* (atom false)
        admitted?* (atom false)
        admission (promise)
        ^Metadata md (map->metadata metadata)
        ^ClientInterceptor interceptor (MetadataUtils/newAttachHeadersInterceptor md)
        stub' (.withInterceptors stub (into-array ClientInterceptor [interceptor]))
        response-observer
        (reify StreamObserver
          (onNext [_ msg]
            (if (= AsrType/SESSION_ACCEPTED (.getType msg))
              (when (compare-and-set! admitted?* false true)
                (deliver admission {:accepted msg}))
              (when (and @admitted?* on-next)
                (try
                  (on-next msg)
                  (catch Exception e
                    (log/error e "RealtimeASR onNext handler failed"))))))
          (onError [_ throwable]
            (reset! closed?* true)
            (if @admitted?*
              (if on-error
                (try
                  (on-error throwable)
                  (catch Exception e
                    (log/error e "RealtimeASR onError handler failed")))
                (log/error throwable "RealtimeASR stream failed"))
              (deliver admission {:error throwable})))
          (onCompleted [_]
            (reset! closed?* true)
            (if @admitted?*
              (if on-complete
                (try
                  (on-complete)
                  (catch Exception e
                    (log/error e "RealtimeASR onComplete handler failed")))
                (log/info "RealtimeASR stream completed"))
              (deliver admission
                       {:error (ex-info "RealtimeASR closed before admission" {:track id})}))))
        request-observer (.stream stub' response-observer)
        timeout-ms (long (max 1 (or admission-timeout-ms default-admission-timeout-ms)))
        result (deref admission timeout-ms ::timeout)]
    (when (= ::timeout result)
      (reset! closed?* true)
      (.onError request-observer
                (-> Status/DEADLINE_EXCEEDED
                    (.withDescription "realtime admission timed out")
                    (.asRuntimeException))))
    (cond
      (= ::timeout result)
      (throw (ex-info "Timed out waiting for realtime admission"
                      {:track id :timeout-ms timeout-ms}))

      (:error result)
      (throw (:error result))

      :else
      (let [accepted (:accepted result)]
        {:track-id id
         :serving-instance-id (.getServingInstanceId accepted)
         :send! (fn [audio-chunk]
                  (when-not @closed?*
                    (.onNext request-observer audio-chunk)))
         :complete! (fn []
                      (when (compare-and-set! closed?* false true)
                        (try
                          (.onCompleted request-observer)
                          (catch Exception e
                            (log/warn e "Attempted to complete already closed stream")))))
         :error! (fn [throwable]
                   (when (compare-and-set! closed?* false true)
                     (try
                       (.onError request-observer throwable)
                       (catch Exception e
                         (log/warn e "Attempted to error already closed stream")))))}))))

(defn start-stream!
  "Open a bidirectional gRPC stream using the provided client component.

  Arguments:
  - client   – map returned by the Integrant component (expects :stub)
  - handlers – map with optional keys:
      :on-next     (fn [asr-event])        invoked for every incoming AsrEvent
      :on-error    (fn [Throwable])        invoked on error
      :on-complete (fn [])                invoked when server closes stream
      :metadata    must contain the session-specific `x-session-id`
      :admission-timeout-ms  optional wait for one pod to accept
      :admission-max-attempts optional bounded `REPLICA_FULL` attempts

  Returns a map with operations:
  - :send!     (fn [audio-chunk])         push AudioChunk to rtservice
  - :complete! (fn [])                    close outbound stream politely
  - :error!    (fn [Throwable])           signal an error downstream

  Notes:
  - The returned operations are safe to call multiple times. In particular,
    `:complete!` is idempotent to avoid noisy `call already half-closed`
    exceptions during cleanup." 
  [{:keys [id stub] :as client}
   {:keys [metadata admission-max-attempts] :as handlers}]
  (when-not stub
    (throw (ex-info "gRPC stub missing" {})))
  (when (str/blank? (get metadata "x-session-id"))
    (throw (ex-info "x-session-id metadata is required" {:track id})))
  (let [maximum (max 1 (int (or admission-max-attempts
                                default-admission-max-attempts)))]
    (loop [attempt 1]
      (let [result (try
                     {:stream (open-stream-once! client handlers)}
                     (catch Throwable throwable
                       {:error throwable}))]
        (if-let [stream (:stream result)]
          (assoc stream :admission-attempts attempt)
          (let [throwable (:error result)]
            (if (and (< attempt maximum) (replica-full? throwable))
              (do
                (log/info "Realtime replica full; trying next resolved endpoint"
                          {:track id :attempt attempt})
                (recur (inc attempt)))
              (throw throwable))))))))

(defn close!
  "Helper to close a previously opened realtime stream map returned by
  `start-stream!`. Safe to call multiple times."
  [{:keys [complete!]}]
  (when complete!
    (complete!)))

(defn cancel!
  "Cancel a previously opened realtime stream without requesting a terminal result.

  Inputs:
  - stream: map returned by `start-stream!`
  - reason: non-sensitive operator-facing cancellation description

  Returns: nil."
  [{:keys [error!]} reason]
  (when error!
    (error! (-> Status/CANCELLED
                (.withDescription (str reason))
                (.asRuntimeException))))
  nil)

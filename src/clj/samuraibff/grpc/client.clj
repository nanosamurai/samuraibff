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
   [integrant.core :as ig]
   [org.corfield.logging4j2 :as log])
  (:import
   (io.grpc ClientInterceptor ManagedChannel ManagedChannelBuilder Metadata Metadata$Key Status)
   (io.grpc.stub MetadataUtils StreamObserver)
   (java.util.concurrent TimeUnit)
   (samuraibff.proto RealtimeASRGrpc RealtimeCapabilitiesRequest)))

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
  "Create a ManagedChannel for the configured rtservice address.

  addr - host:port string.

  Returns an open ManagedChannel instance."
  [addr]
  (-> (ManagedChannelBuilder/forTarget addr)
      (.usePlaintext)
      (.build)))

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

(defn start-stream!
  "Open a bidirectional gRPC stream using the provided client component.

  Arguments:
  - client   – map returned by the Integrant component (expects :stub)
  - handlers – map with optional keys:
      :on-next     (fn [asr-event])        invoked for every incoming AsrEvent
      :on-error    (fn [Throwable])        invoked on error
      :on-complete (fn [])                invoked when server closes stream
      :metadata    {header-name header-value ...} optional gRPC metadata to attach

  Returns a map with operations:
  - :send!     (fn [audio-chunk])         push AudioChunk to rtservice
  - :complete! (fn [])                    close outbound stream politely
  - :error!    (fn [Throwable])           signal an error downstream

  Notes:
  - The returned operations are safe to call multiple times. In particular,
    `:complete!` is idempotent to avoid noisy `call already half-closed`
    exceptions during cleanup." 
  [{:keys [id stub]} {:keys [on-next on-error on-complete metadata]}]
  (when-not stub
    (throw (ex-info "gRPC stub missing" {})))
  (let [closed?* (atom false)
        stub' (if (seq metadata)
                (let [^Metadata md (map->metadata metadata)
                      ^ClientInterceptor interceptor (MetadataUtils/newAttachHeadersInterceptor md)]
                  (.withInterceptors stub (into-array ClientInterceptor [interceptor])))
                stub)
        response-observer
        (reify StreamObserver
          (onNext [_ msg]
            (when on-next
              (try
                (on-next msg)
                (catch Exception e
                  (log/error e "RealtimeASR onNext handler failed")))))
          (onError [_ t]
            (reset! closed?* true)
            (if on-error
              (try
                (on-error t)
                (catch Exception e
                  (log/error e "RealtimeASR onError handler failed")))
              (log/error t "RealtimeASR stream failed")))
          (onCompleted [_]
            (reset! closed?* true)
            (if on-complete
              (try
                (on-complete)
                (catch Exception e
                  (log/error e "RealtimeASR onComplete handler failed")))
              (log/info "RealtimeASR stream completed"))))
        request-observer (.stream stub' response-observer)]
    {:track-id id
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
                     (log/warn e "Attempted to error already closed stream")))))}))

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

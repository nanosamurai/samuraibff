(ns samuraibff.grpc.client
  "gRPC client component responsible for managing the channel to rtservice
  and exposing helpers to open realtime audio streams.

  Public API:
  - `start-stream!` – open a bidirectional stream and provide callbacks
    for inbound ASR events / lifecycle notifications.
  - `close!` – helper to close a started stream (completes outbound side).

  The component is registered under the `:samuraibff/grpc-client` Integrant
  key and expects the global config map to contain `:grpc {:rtservice-addr ..}`.
  Channels are created with plaintext transport for now (local dev)."
  (:require
   [integrant.core :as ig]
   [org.corfield.logging4j2 :as log])
  (:import
   (io.grpc ManagedChannel ManagedChannelBuilder StatusRuntimeException)
   (io.grpc.stub StreamObserver)
   (java.util.concurrent TimeUnit)
   (samuraibff.proto RealtimeASRGrpc)))

(defn- build-channel
  "Create a ManagedChannel for the configured rtservice address.

  addr - host:port string.

  Returns an open ManagedChannel instance."
  [addr]
  (-> (ManagedChannelBuilder/forTarget addr)
      (.usePlaintext)
      (.build)))

(defmethod ig/init-key :samuraibff/grpc-client [_ {:keys [config]}]
  "Initialize the gRPC client Integrant component.

  Configuration map must contain `[:grpc :rtservice-addr]`.

  Returns a map with:
  - `:channel` – the ManagedChannel instance
  - `:stub` – an async RealtimeASR stub bound to the channel"
  (let [addr (or (get-in config [:grpc :rtservice-addr])
                 (throw (ex-info "Missing rtservice address" {:config config})))]
    (log/info "Connecting gRPC client to rtservice at %s" addr)
    (let [channel (build-channel addr)
          stub (RealtimeASRGrpc/newStub channel)]
      {:channel channel
       :stub stub})))

(defmethod ig/halt-key! :samuraibff/grpc-client [_ {:keys [channel]}]
  "Shutdown the ManagedChannel gracefully when Integrant halts the component."
  (when (instance? ManagedChannel channel)
    (try
      (.shutdown channel)
      (.awaitTermination channel 5 TimeUnit/SECONDS)
      (catch InterruptedException _
        (.shutdownNow channel))
      (catch Exception e
        (log/error e "Failed to shutdown gRPC channel cleanly")))))

(defn start-stream!
  "Open a bidirectional gRPC stream using the provided client component.

  Arguments:
  - client   – map returned by the Integrant component (expects :stub)
  - handlers – map with optional keys:
      :on-next     (fn [asr-event])        invoked for every incoming AsrEvent
      :on-error    (fn [Throwable])        invoked on error
      :on-complete (fn [])                invoked when server closes stream

  Returns a map with operations:
  - :send!     (fn [audio-chunk])         push AudioChunk to rtservice
  - :complete! (fn [])                    close outbound stream politely
  - :error!    (fn [Throwable])           signal an error downstream"
  [{:keys [stub]} {:keys [on-next on-error on-complete]}]
  (when-not stub
    (throw (ex-info "gRPC stub missing" {})))
  (let [response-observer
        (reify StreamObserver
          (onNext [_ msg]
            (when on-next
              (try
                (on-next msg)
                (catch Exception e
                  (log/error e "RealtimeASR onNext handler failed")))))
          (onError [_ t]
            (if on-error
              (try
                (on-error t)
                (catch Exception e
                  (log/error e "RealtimeASR onError handler failed")))
              (log/error t "RealtimeASR stream failed")))
          (onCompleted [_]
            (if on-complete
              (try
                (on-complete)
                (catch Exception e
                  (log/error e "RealtimeASR onComplete handler failed")))
              (log/info "RealtimeASR stream completed"))))
        request-observer (.stream stub response-observer)]
    {:send! (fn [audio-chunk]
              (.onNext request-observer audio-chunk))
     :complete! (fn []
                  (try
                    (.onCompleted request-observer)
                    (catch StatusRuntimeException e
                      (log/warn e "Attempted to complete already closed stream"))))
     :error! (fn [throwable]
               (.onError request-observer throwable))}))

(defn close!
  "Helper to close a previously opened realtime stream map returned by
  `start-stream!`. Safe to call multiple times."
  [{:keys [complete!]}]
  (when complete!
    (complete!)))

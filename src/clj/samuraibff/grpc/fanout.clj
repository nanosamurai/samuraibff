(ns samuraibff.grpc.fanout
  "Bounded, failure-isolated fan-out to peer RealtimeASR services."
  (:require
   [clojure.core.async :as async]
   [org.corfield.logging4j2 :as log]
   [samuraibff.grpc.client :as grpc]))

(defn- discover-capabilities
  "Best-effort capability handshake for one configured realtime track."
  [track-client]
  (try
    (grpc/get-capabilities track-client 2000)
    (catch Exception e
      (log/warn e "Realtime capability handshake failed" {:track (:id track-client)})
      nil)))

(defn- start-track!
  "Start one realtime stream and its independent bounded forwarding thread."
  [track-client primary? buffer-size metadata handlers]
  (let [track-id (:id track-client)
        capabilities (discover-capabilities track-client)
        provider-profile-id (:provider-profile-id capabilities)
        input (async/chan (async/buffer buffer-size))
        active? (atom true)
        stream* (atom nil)
        on-next (:on-next handlers)
        on-error (:on-error handlers)
        on-complete (:on-complete handlers)
        stream
        (grpc/start-stream!
         track-client
         {:metadata metadata
          :on-next (fn [event]
                     (when on-next
                       (on-next {:track track-id
                                 :primary? primary?
                                 :provider-profile-id provider-profile-id
                                 :event event})))
          :on-error (fn [error]
                      (when (compare-and-set! active? true false)
                        (async/close! input))
                      (when on-error
                        (on-error track-id error)))
          :on-complete (fn []
                         (reset! active? false)
                         (async/close! input)
                         (when on-complete
                           (on-complete track-id)))})]
    (reset! stream* stream)
    (async/thread
      (loop []
        (if-let [audio-chunk (async/<!! input)]
          (do
            (try
              ((:send! stream) audio-chunk)
              (catch Exception e
                (when (compare-and-set! active? true false)
                  (grpc/cancel! stream "realtime track send failed")
                  (async/close! input)
                  (when on-error
                    (on-error track-id e)))))
            (when @active?
              (recur)))
          (when @active?
            (grpc/close! stream)))))
    {:id track-id
     :primary? primary?
     :capabilities capabilities
     :input input
     :active? active?
     :stream stream*}))

(defn start!
  "Start all configured realtime tracks.

  Inputs:
  - grpc-component: configured peer clients from `samuraibff.grpc.client`
  - handlers: `:on-next`, `:on-error`, and `:on-complete` callbacks
  - options: `:buffer-size` positive integer and optional gRPC `:metadata`

  Returns a fan-out map accepted by `offer!`, `complete!`, and `cancel!`."
  [grpc-component handlers {:keys [buffer-size metadata]}]
  (let [track-clients (grpc/tracks grpc-component)
        size (max 1 (int (or buffer-size 8)))]
    (when-not (seq track-clients)
      (throw (ex-info "No realtime ASR tracks are registered" {})))
    {:tracks
     (mapv
      (fn [index track-client]
        (start-track! track-client (zero? index) size metadata handlers))
      (range)
      track-clients)}))

(defn offer!
  "Offer one AudioChunk independently to every active realtime track.

  A full per-track queue cancels only that track. Returns the IDs of tracks
  dropped for backpressure; healthy tracks continue receiving the chunk."
  [{:keys [tracks]} audio-chunk]
  (->> tracks
       (keep
        (fn [{:keys [id input active? stream]}]
          (when (and @active? (not (async/offer! input audio-chunk)))
            (when (compare-and-set! active? true false)
              (grpc/cancel! @stream "realtime track input queue is full")
              (async/close! input))
            id)))
       vec))

(defn complete!
  "Gracefully drain and half-close every active realtime request stream."
  [{:keys [tracks]}]
  (doseq [{:keys [input]} tracks]
    (async/close! input))
  nil)

(defn cancel!
  "Cancel every active realtime track immediately during session teardown."
  [{:keys [tracks]} reason]
  (doseq [{:keys [input active? stream]} tracks]
    (when (compare-and-set! active? true false)
      (grpc/cancel! @stream reason))
    (async/close! input))
  nil)

(ns samuraibff.grpc.transport-test
  "Exercise the packaged gRPC transport and generated stubs over loopback TCP."
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [samuraibff.grpc.client :as grpc])
  (:import (io.grpc EquivalentAddressGroup Metadata$Key Metadata NameResolver
                    NameResolver$ResolutionResult NameResolverProvider
                    NameResolverRegistry ServerInterceptor ServerInterceptors Status Status$Code)
           (io.grpc.netty NettyServerBuilder)
           (io.grpc.stub StreamObserver)
           (java.net InetSocketAddress)
           (java.util.concurrent TimeUnit)
           (samuraibff.proto AsrEvent AsrType AudioChunk RealtimeASRGrpc$RealtimeASRImplBase
                             RealtimeCapabilities)))

(defn- start-server!
  "Start a synthetic ASR server on loopback; record session headers and cancellation.
  When stall? is true, capability requests wait for their client deadline."
  [id headers cancelled stall?]
  (let [service
        (proxy [RealtimeASRGrpc$RealtimeASRImplBase] []
          (getCapabilities [_ response]
            (when-not stall?
              (.onNext response (-> (RealtimeCapabilities/newBuilder)
                                    (.setProviderProfileId id)
                                    (.setSessionSettingsJson "{}")
                                    (.build)))
              (.onCompleted response)))
          (stream [response]
            (.onNext response (-> (AsrEvent/newBuilder)
                                  (.setType AsrType/SESSION_ACCEPTED)
                                  (.setServingInstanceId id)
                                  (.build)))
            (reify StreamObserver
              (onNext [_ chunk]
                (.onNext response (-> (AsrEvent/newBuilder)
                                      (.setSessionId (.getSessionId chunk))
                                      (.setText (str (.getSeq chunk)))
                                      (.setType AsrType/FINAL)
                                      (.build))))
              (onError [_ error] (deliver cancelled (.getCode (Status/fromThrowable error))))
              (onCompleted [_] (.onCompleted response)))))
        interceptor
        (reify ServerInterceptor
          (interceptCall [_ call metadata next-handler]
            (swap! headers conj (.get metadata (Metadata$Key/of "x-session-id" Metadata/ASCII_STRING_MARSHALLER)))
            (.startCall next-handler call metadata)))]
    (-> (NettyServerBuilder/forAddress (InetSocketAddress. "127.0.0.1" 0))
        (.addService (ServerInterceptors/intercept service (into-array ServerInterceptor [interceptor])))
        (.build)
        (.start))))

(defn- stop-server!
  "Stop a test server and wait at most five seconds for its transport threads."
  [server]
  (.shutdownNow server)
  (.awaitTermination server 5 TimeUnit/SECONDS))

(defn- component
  "Create the real BFF client component for a synthetic local service address."
  [address]
  (ig/init-key :samuraibff/grpc-client {:config {:grpc {:rtservice-addr address}}}))

(deftest dns-streaming-metadata-cancellation-and-cleanup-test
  (let [headers (atom [])
        cancelled (promise)
        server (start-server! "local-asr" headers cancelled false)
        client (component (str "localhost:" (.getPort server)))
        track (first (grpc/tracks client))]
    (try
      (is (= "local-asr" (:provider-profile-id (grpc/get-capabilities track 3000))))
      (let [events (atom [])
            completed (promise)
            stream (grpc/start-stream! track {:metadata {"x-session-id" "transport-test"}
                                              :on-next #(swap! events conj %)
                                              :on-complete #(deliver completed true)})]
        (is (= "local-asr" (:serving-instance-id stream)))
        ((:send! stream) (-> (AudioChunk/newBuilder)
                             (.setSessionId "transport-test") (.setSeq 7) (.build)))
        (grpc/close! stream)
        (is (= true (deref completed 3000 ::timeout)))
        (is (= [["transport-test" "7"]]
               (mapv (juxt #(.getSessionId %) #(.getText %)) @events)))
        (is (some #{"transport-test"} @headers)))
      (let [stream (grpc/start-stream! track {:metadata {"x-session-id" "cancel-test"}
                                              :on-error (fn [_] nil)})]
        (grpc/cancel! stream "test cancellation")
        (is (= Status$Code/CANCELLED (deref cancelled 3000 ::timeout))))
      (finally
        (ig/halt-key! :samuraibff/grpc-client client)
        (stop-server! server)))
    (is (.isTerminated (:channel track)))))

(deftest capability-deadline-test
  (let [server (start-server! "stalled" (atom []) (promise) true)
        client (component (str "127.0.0.1:" (.getPort server)))]
    (try
      (is (= Status$Code/DEADLINE_EXCEEDED
             (try
               (grpc/get-capabilities (first (grpc/tracks client)) 150)
               ::unexpected-success
               (catch Exception error (.getCode (Status/fromThrowable error))))))
      (finally
        (ig/halt-key! :samuraibff/grpc-client client)
        (stop-server! server)))))

(defn- resolver-provider
  "Return a test-only resolver exposing both loopback replicas to round_robin."
  [servers]
  (let [addresses (mapv #(EquivalentAddressGroup.
                          (InetSocketAddress. "127.0.0.1" (.getPort %))) servers)]
    (proxy [NameResolverProvider] []
      (isAvailable [] true)
      (priority [] 5)
      (getDefaultScheme [] "transport-test")
      (newNameResolver [uri _]
        (when (= "transport-test" (.getScheme uri))
          (proxy [NameResolver] []
            (getServiceAuthority [] "loopback")
            (start [listener]
              (.onResult listener (-> (NameResolver$ResolutionResult/newBuilder)
                                      (.setAddresses addresses) (.build))))
            (shutdown [] nil)))))))

(deftest round-robin-service-provider-test
  (let [servers (mapv #(start-server! % (atom []) (promise) false) ["replica-a" "replica-b"])
        registry (NameResolverRegistry/getDefaultRegistry)
        provider (resolver-provider servers)]
    (.register registry provider)
    (try
      (let [client (component "transport-test:///replicas")
            track (first (grpc/tracks client))]
        (try
          (is (= #{"replica-a" "replica-b"}
                 (loop [attempt 0 seen #{}]
                   (if (or (= 2 (count seen)) (= attempt 40))
                     seen
                     (let [id (:provider-profile-id (grpc/get-capabilities track 3000))]
                       (Thread/sleep 25)
                       (recur (inc attempt) (conj seen id)))))))
          (finally (ig/halt-key! :samuraibff/grpc-client client))))
      (finally
        (.deregister registry provider)
        (doseq [server servers] (stop-server! server))))))

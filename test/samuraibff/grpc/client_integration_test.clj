(ns samuraibff.grpc.client-integration-test
  "Integration tests for the gRPC client component. These tests assume the
  rtservice gRPC server is reachable at localhost:50052."
  (:require
   [clojure.test :refer :all]
   [integrant.core :as ig]
   [samuraibff.grpc.client :as grpc])
  (:import
   (com.google.protobuf ByteString)
   (samuraibff.proto AudioChunk)))

(defn- tcp-connectable?
  "Return true if a TCP connection can be established to host:port within timeout." 
  [^String host ^long port ^long timeout-ms]
  (try
    (with-open [sock (java.net.Socket.)]
      (.connect sock (java.net.InetSocketAddress. host (int port)) (int timeout-ms)))
    true
    (catch Exception _
      false)))

(def ^:private test-config
  {:config {:grpc {:rtservice-addr "localhost:50052"}}})

(deftest realtime-stream-happy-path-test
  (let [rt-host "localhost"
        rt-port 50052]
    (if-not (tcp-connectable? rt-host rt-port 200)
      (is true (str "Skipping: rtservice not reachable at " rt-host ":" rt-port))
      (testing "gRPC stream can be opened, sent a chunk, and closed cleanly"
        (let [component (ig/init-key :samuraibff/grpc-client test-config)
              completed? (promise)
              errors (promise)]
          (try
            (let [stream (grpc/start-stream!
                           component
                           {:on-complete #(deliver completed? true)
                            :on-error #(deliver errors %)
                            :on-next (fn [_] nil)})
                  audio (-> (AudioChunk/newBuilder)
                            (.setSessionId "integration-session")
                            (.setSeq 1)
                            (.setT0Ns (System/nanoTime))
                            (.setSampleRate 16000)
                            (.setLang "en")
                            (.setPcm16Le (ByteString/copyFrom (byte-array 320)))
                            (.build))]
              ((:send! stream) audio)
              (grpc/close! stream)
              (is (= true (deref completed? 5000 ::timeout))
                  "Stream should complete after close!" )
              (is (nil? (deref errors 100 nil))
                  "No asynchronous gRPC errors expected"))
            (finally
              (ig/halt-key! :samuraibff/grpc-client component))))))))

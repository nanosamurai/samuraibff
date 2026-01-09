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

(def ^:private test-config
  {:config {:grpc {:rtservice-addr "localhost:50052"}}})

(deftest realtime-stream-happy-path-test
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
          (ig/halt-key! :samuraibff/grpc-client component))))))

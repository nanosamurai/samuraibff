(ns samuraibff.grpc.fanout-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.grpc.client :as grpc]
   [samuraibff.grpc.fanout :as fanout])
  (:import
   (java.util.concurrent CountDownLatch TimeUnit)))

(deftest fanout-labels-and-completes-independent-tracks-test
  (let [clients {:tracks [{:id "faster"} {:id "qwen"}]}
        observed (atom [])
        completed (CountDownLatch. 2)]
    (with-redefs [grpc/get-capabilities
                  (fn [client _]
                    {:provider-profile-id (str (:id client) "-profile")})
                  grpc/start-stream!
                  (fn [client handlers]
                    {:track-id (:id client)
                     :send! (fn [chunk]
                              ((:on-next handlers) {:source (:id client) :chunk chunk}))
                     :complete! (fn []
                                  ((:on-complete handlers)))
                     :error! (fn [error]
                               ((:on-error handlers) error))})]
      (let [running (fanout/start!
                     clients
                     {:on-next #(swap! observed conj %)
                      :on-error (fn [_ _] nil)
                      :on-complete (fn [_] (.countDown completed))}
                     {:buffer-size 2})]
        (is (= [] (fanout/offer! running :audio)))
        (fanout/complete! running)
        (is (.await completed 2 TimeUnit/SECONDS))
        (is (= #{["faster" true "faster-profile"]
                 ["qwen" false "qwen-profile"]}
               (->> @observed
                    (map (juxt :track :primary? :provider-profile-id))
                    set)))))))

(deftest full-track-queue-does-not-block-healthy-track-test
  (testing "one stalled downstream track is canceled without dropping the peer"
    (let [clients {:tracks [{:id "faster"} {:id "qwen"}]}
          qwen-entered (CountDownLatch. 1)
          release-qwen (CountDownLatch. 1)
          faster-sent (CountDownLatch. 3)
          canceled (atom [])]
      (with-redefs [grpc/get-capabilities (fn [client _] {:provider-profile-id (:id client)})
                    grpc/start-stream!
                    (fn [client _handlers]
                      {:track-id (:id client)
                       :send! (fn [_chunk]
                                (if (= "qwen" (:id client))
                                  (do
                                    (.countDown qwen-entered)
                                    (.await release-qwen 2 TimeUnit/SECONDS))
                                  (.countDown faster-sent)))
                       :complete! (fn [] nil)
                       :error! (fn [_] nil)})
                    grpc/cancel! (fn [stream _reason]
                                   (swap! canceled conj (:track-id stream)))]
        (let [running (fanout/start! clients {} {:buffer-size 1})]
          (is (= [] (fanout/offer! running :first)))
          (is (.await qwen-entered 1 TimeUnit/SECONDS))
          (is (= [] (fanout/offer! running :second)))
          (Thread/sleep 50)
          (is (= ["qwen"] (fanout/offer! running :third)))
          (.countDown release-qwen)
          (is (.await faster-sent 2 TimeUnit/SECONDS))
          (is (= ["qwen"] @canceled))
          (fanout/cancel! running "test complete"))))))

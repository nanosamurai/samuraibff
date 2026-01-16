(ns samuraibff.ws.registry-test
  "Unit tests for `samuraibff.ws.registry`."
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [samuraibff.ws.registry :as reg]))

(deftest ensure-session-idempotent-test
  (testing "ensure-session! creates once and returns the same session on subsequent calls"
    (let [registry {:config {:env :test}
                    :sessions (atom {})}
          s1 (reg/ensure-session! registry "t-1" "s-1" {:lang "en" :sample-rate 16000})
          s2 (reg/ensure-session! registry "t-1" "s-1" {:lang "cs" :sample-rate 8000})]
      (is (= "s-1" (:session-id s1)))
      (is (= "t-1" (:tenant-id s1)))
      (is (identical? s1 s2) "Should return the same map instance from registry")
      (is (= "en" (:lang s1)) "First creation should win")
      (is (= 16000 (:sample-rate s1))))))

(deftest publish-and-tap-test
  (testing "publish! pushes events to tapped channels"
    (let [registry {:config {:env :test}
                    :sessions (atom {})}
          session (reg/ensure-session! registry "t-1" "s-2" {})
          out (async/chan 10)]
      (reg/tap-events! session out)
      (is (true? (reg/publish! registry session
                               {:type "status"
                                :session_id "s-2"
                                :seq 1
                                :ts_ms 1
                                :status "connected"})))
      (is (= "status" (:type (async/<!! out))))
      (reg/untap-events! session out)
      (async/close! out))))

(deftest offer-audio-nonblocking-test
  (testing "offer-audio! is non-blocking and returns boolean"
    (let [registry {:config {:env :test}
                    :sessions (atom {})}
          session (reg/ensure-session! registry "t-1" "s-3" {})]
      (is (true? (reg/offer-audio! registry session (byte-array 10))))
      ;; drain
      (is (bytes? (async/<!! (:audio-ch session)))))))

(deftest update-session-controls-test
  (testing "update-session-controls! updates lang/sample-rate when stream not running"
    (let [registry {:config {:env :test}
                    :sessions (atom {})}
          _ (reg/ensure-session! registry "t-1" "s-uc" {})
          updated (reg/update-session-controls! registry "t-1" "s-uc" {:lang "cs"
                                                                       :sample-rate 8000})]
      (is (= "cs" (:lang updated)))
      (is (= 8000 (:sample-rate updated)))
      ;; also persisted into registry
      (is (= "cs" (:lang (reg/get-session registry "t-1" "s-uc"))))))

  (testing "update-session-controls! does not update controls when stream already running"
    (let [registry {:config {:env :test}
                    :sessions (atom {})}
          s (reg/ensure-session! registry "t-1" "s-running" {:lang "en" :sample-rate 16000})]
      (reset! (:running?* s) true)
      (let [updated (reg/update-session-controls! registry "t-1" "s-running" {:lang "cs"
                                                                              :sample-rate 8000})]
        (is (= "en" (:lang updated)))
        (is (= 16000 (:sample-rate updated)))))))

(deftest close-session-removes-test
  (testing "close-session! removes session from registry and closes channels"
    (let [registry {:config {:env :test}
                    :sessions (atom {})}
          session (reg/ensure-session! registry "t-1" "s-4" {})]
      (reg/close-session! registry "t-1" "s-4" "test")
      (is (nil? (reg/get-session registry "t-1" "s-4"))))))

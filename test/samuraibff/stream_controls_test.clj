(ns samuraibff.stream-controls-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.stream-controls :as stream-controls]))

(deftest parse-and-validate-defaults-test
  (testing "defaults are backwards compatible"
    (is (= {:realtime true
            :refined true
            :final true
            :store_recording true
            :rt_partial_enable true}
           (stream-controls/parse-and-validate {})))))

(deftest parse-and-validate-outputs-test
  (testing "at least one output must be enabled"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"At least one output"
         (stream-controls/parse-and-validate {:realtime "false"}
                                             :refined "false"
                                             :final "false"))))

  (testing "store_recording is forced off when final=false"
    (is (= false
           (:store_recording
            (stream-controls/parse-and-validate {:final "false"}
                                                :store_recording "true"))))))

(deftest parse-and-validate-clamps-test
  (testing "emit_every has a hard minimum 1s"
    (is (= 1.0
           (:rt_emit_every_sec
            (stream-controls/parse-and-validate {:rt_emit_every_sec "0.1"})))))

  (testing "window clamps to [1,30]"
    (is (= 30.0
           (:rt_window_sec
            (stream-controls/parse-and-validate {:rt_window_sec "100"})))))

  (testing "overlap clamps to <= window"
    (is (= 5.0
           (:rt_overlap_sec
            (stream-controls/parse-and-validate {:rt_window_sec "5"}
                                                :rt_overlap_sec "999")))))

  (testing "refinement_window_sec clamps to [10,600] when refined enabled"
    (is (= 10.0
           (:refinement_window_sec
            (stream-controls/parse-and-validate {:refinement_window_sec "1"}))))
    (is (= 600.0
           (:refinement_window_sec
            (stream-controls/parse-and-validate {:refinement_window_sec "9999"}))))))

(deftest kafka-headers-test
  (testing "x-outputs and x-store-recording headers are always present"
    (let [h (stream-controls/kafka-headers (stream-controls/parse-and-validate {}))]
      (is (contains? h "x-outputs"))
      (is (contains? h "x-store-recording"))
      (is (bytes? (get h "x-outputs")))))

  (testing "x-refinement-window-sec is included only when refined + window specified"
    (let [h0 (stream-controls/kafka-headers (stream-controls/parse-and-validate {}))
          h1 (stream-controls/kafka-headers (stream-controls/parse-and-validate {:refinement_window_sec "60"}))
          h2 (stream-controls/kafka-headers (stream-controls/parse-and-validate {:refined "false"}
                                                                                :refinement_window_sec "60"))]
      (is (not (contains? h0 "x-refinement-window-sec")))
      (is (contains? h1 "x-refinement-window-sec"))
      (is (not (contains? h2 "x-refinement-window-sec"))))))

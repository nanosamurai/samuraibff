(ns samuraibff.stream-controls-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.stream-controls :as stream-controls]))

(deftest parse-and-validate-defaults-test
  (testing "defaults are backwards compatible"
    (is (= {:realtime true
            :refined true
            :final true
            :final_tracks ["whisperx"]
            :refinement_tracks ["whisperx"]
            :store_recording true
            :realtime_settings {}}
           (stream-controls/parse-and-validate {})))))

(deftest parse-and-validate-outputs-test
  (testing "at least one output must be enabled"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"At least one output"
         (stream-controls/parse-and-validate {:realtime "false"
                                              :refined "false"
                                              :final "false"}))))

  (testing "store_recording is forced off when final=false"
    (is (= false
           (:store_recording
            (stream-controls/parse-and-validate {:final "false"
                                                 :store_recording "true"}))))))

(deftest parse-and-validate-realtime-tracks-test
  (testing "an omitted selection resolves to every configured track"
    (is (= ["faster" "qwen"]
           (:realtime_tracks
            (stream-controls/parse-and-validate {} ["faster" "qwen"])))))

  (testing "an explicit selection is returned in operator-configured order"
    (is (= ["faster" "qwen"]
           (:realtime_tracks
            (stream-controls/parse-and-validate
             {:realtime_tracks "qwen,faster"}
             ["faster" "qwen"])))))

  (testing "empty, duplicate, and unconfigured track selections are rejected"
    (doseq [selection ["" "qwen,qwen" "unconfigured"]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"subset of configured tracks"
           (stream-controls/parse-and-validate
            {:realtime_tracks selection}
            ["faster" "qwen"]))))))

(deftest parse-and-validate-clamps-test
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
          h2 (stream-controls/kafka-headers (stream-controls/parse-and-validate {:refined "false"
                                                                                 :refinement_window_sec "60"}))]
      (is (not (contains? h0 "x-refinement-window-sec")))
      (is (contains? h1 "x-refinement-window-sec"))
      (is (not (contains? h2 "x-refinement-window-sec"))))))

(deftest configured-track-labels-test
  (let [config {:final-tracks ["alternative"] :refinement-tracks ["alternative" "whisperx"]
                :track-labels {:final {:alternative "Alternative model"}}}
        selected (stream-controls/parse-and-validate {} nil ["alternative"] ["alternative"])
        labeled (stream-controls/with-track-labels selected config)]
    (is (= ["alternative"] (:final_tracks selected) (:refinement_tracks selected)))
    (is (= {:final {:alternative "Alternative model"} :refined {:alternative "alternative"}}
           (:track_labels labeled)))
    (is (= [true false true] (mapv :default_selected (stream-controls/configured-async-tracks config))))
    (is (= {} (:track_labels (stream-controls/with-track-labels
                               (assoc selected :final false :refined false) config))))))

(deftest final-tracks-validation-test
  (let [parse #(stream-controls/parse-and-validate % nil ["whisperx" "test-shadow"])]
    (is (= ["test-shadow" "whisperx"] (:final_tracks (parse {:final_tracks "test-shadow,whisperx"}))))
    (doseq [value ["" "unknown" "whisperx,whisperx" "whisperx,"]]
      (is (thrown? clojure.lang.ExceptionInfo (parse {:final_tracks value}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require store_recording"
                          (parse {:final_tracks "whisperx,test-shadow" :store_recording "false"})))
    (is (false? (:store_recording (parse {:final_tracks "test-shadow" :store_recording "false"}))))
    (is (= "test-shadow,whisperx"
           (String. ^bytes (get (stream-controls/kafka-headers
                                 (parse {:final_tracks "test-shadow,whisperx"})) "x-final-tracks") "UTF-8"))))) (deftest refinement-tracks-validation-test
                                                                                                                  (let [parse #(stream-controls/parse-and-validate % nil ["whisperx"] ["whisperx" "test-shadow"])]
                                                                                                                    (is (= ["test-shadow" "whisperx"] (:refinement_tracks (parse {:refinement_tracks "test-shadow,whisperx"}))))
                                                                                                                    (is (= ["test-shadow"] (:refinement_tracks (parse {:refinement_tracks "test-shadow"}))))
                                                                                                                    (doseq [value ["" "unknown" "whisperx,whisperx" "whisperx,"]]
                                                                                                                      (is (thrown? clojure.lang.ExceptionInfo (parse {:refinement_tracks value}))))
                                                                                                                    (is (= "test-shadow,whisperx"
                                                                                                                           (String. ^bytes (get (stream-controls/kafka-headers
                                                                                                                                                 (parse {:refinement_tracks "test-shadow,whisperx"}))
                                                                                                                                                "x-refinement-tracks") "UTF-8")))))

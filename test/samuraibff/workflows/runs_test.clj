(ns samuraibff.workflows.runs-test
  "Pure source selection and segment rules of on-demand workflow runs."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.workflows.runs :as runs]))

(def ^:private stored
  [["final" "whisperx"] ["final" "voxtral"] ["refined" "parakeet"]])

(deftest select-source-order
  (let [final-wf {:trigger_type "transcript.final.ready" :trigger_track_id "voxtral"}
        refined-wf {:trigger_type "transcript.refined.segment" :trigger_track_id "parakeet"}
        recording-wf {:trigger_type "recording.finished"}]
    (testing "explicit stage and track"
      (is (= ["final" "whisperx"] (runs/select-source {} final-wf stored {:stage "final" :track_id "whisperx"})))
      (is (nil? (runs/select-source {} final-wf stored {:stage "refined" :track_id "whisperx"}))))
    (testing "stage only prefers the workflow track, then the default, then the first stored"
      (is (= ["final" "voxtral"] (runs/select-source {} final-wf stored {:stage "final"})))
      (is (= ["final" "whisperx"] (runs/select-source {} refined-wf stored {:stage "final"})))
      (is (= ["final" "voxtral"] (runs/select-source {} refined-wf [["final" "voxtral"]] {:stage "final"})))
      (is (= ["refined" "parakeet"] (runs/select-source {} final-wf stored {:stage "refined"}))))
    (testing "no request uses the workflow source, else the default final track"
      (is (= ["refined" "parakeet"] (runs/select-source {} refined-wf stored {})))
      (is (= ["final" "whisperx"] (runs/select-source {} recording-wf stored {})))
      (is (= ["final" "voxtral"] (runs/select-source {:default-tracks {:final "voxtral"}} recording-wf stored {})))
      (is (nil? (runs/select-source {} recording-wf [["refined" "parakeet"]] {}))))))

(deftest transcript-segments-shape
  (is (= [{:text "a" :start_s 0.0 :end_s 1.0 :speaker "S1"} {:text "b"}]
         (runs/transcript-segments [{:segments [{:start_s 0 :end_s 1 :speaker "S1" :text " a "}
                                                {:start_s 1 :end_s 2 :speaker "" :text ""}]}
                                    {:segments [] :full_text "b"}]
                                   "refined")))
  (is (= ["new"] (mapv :text (runs/transcript-segments [{:segments [{:text "old"}]} {:segments [{:text "new"}]}]
                                                       "final")))))

(deftest provider-types-and-limits
  (is (= ["bedrock"] (runs/provider-types {})))
  (is (true? (runs/provider-type-allowed? {} "bedrock" nil)))
  (is (false? (runs/provider-type-allowed? {} "openai_compatible" nil)))
  (is (true? (runs/provider-type-allowed? {} "openai_compatible" "openai_compatible")))
  (is (= 524288 (runs/run-max-bytes {})))
  (is (= 1000 (runs/run-max-bytes {:workflows {:run-max-bytes 1000}}))))

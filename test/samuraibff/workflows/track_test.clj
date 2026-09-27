(ns samuraibff.workflows.track-test
  "Track binding validation and sessions.meta contract tests."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [samuraibff.db.workflows :as db.workflows]
   [samuraibff.schemas :as schemas]
   [samuraibff.workflows.snapshot :as snapshot])
  (:import (java.util UUID)))

(deftest transcript-triggers-require-one-explicit-track
  (doseq [type ["transcript.refined.segment" "transcript.final.ready"]]
    (is (m/validate schemas/WorkflowTrigger {:type type :track_id "parakeet"}))
    (doseq [trigger [{:type type} {:type type :track_id ""} {:type type :track_id " "}
                     {:type type :track_id " parakeet"} {:type type :track_id ["whisperx" "parakeet"]}]]
      (is (not (m/validate schemas/WorkflowTrigger trigger)))))
  (testing "Recording completion is session scoped"
    (is (m/validate schemas/WorkflowTrigger {:type "recording.finished"}))
    (is (not (m/validate schemas/WorkflowTrigger {:type "recording.finished" :track_id "parakeet"})))))

(deftest snapshot-preserves-each-workflows-source-track
  (let [tenant (UUID/randomUUID)
        a (UUID/randomUUID)
        b (UUID/randomUUID)
        row {:enabled true :trigger_type "transcript.refined.segment"
             :prompt_text "Summarize" :provider_type "bedrock" :provider_model_id "model"}
        rows [(assoc row :id a :trigger_track_id "whisperx")
              (assoc row :id b :trigger_track_id "parakeet")]]
    (with-redefs [db.workflows/get-defaults (fn [_ tid] (is (= tenant tid)) {:workflow_ids [a b]})
                  db.workflows/list-workflows (fn [_ tid] (is (= tenant tid)) rows)]
      (let [targets (snapshot/resolve-targets nil tenant (UUID/randomUUID) nil)]
        (is (= ["whisperx" "parakeet"] (mapv #(get-in % [:trigger :track_id]) targets))))
      (let [targets (snapshot/resolve-targets nil tenant (UUID/randomUUID)
                                              {:use_defaults false :workflow_ids [(str b)]})]
        (is (= ["parakeet"] (mapv #(get-in % [:trigger :track_id]) targets)))))))

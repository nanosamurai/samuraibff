(ns samuraibff.ui.recording-detail-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.recording-detail :as recording-detail]))

(deftest flat-track-results-test
  (let [detail {:session {:stream_controls {:final true :refined false
                                            :final_tracks ["alternative" "whisperx"]
                                            :track_labels {:final {:alternative "Original label"}}}}
                :transcripts {:final [{:track_id nil :full_text "Plain text" :segments []}]}}
        tabs (recording-detail/track-tabs detail)]
    (is (= [[:final "alternative"] [:final "whisperx"]] (mapv :id tabs)))
    (is (= "Final Transcript (Original label)" (:label (first tabs))))
    (is (empty? (:rows (first tabs))))
    (is (= [{:kind "final" :seq 0 :lang nil :text "Plain text"}]
           (recording-detail/record-messages (:rows (second tabs)) :final)))
    (is (= [] (recording-detail/record-messages [{:full_text "" :segments []}] :final)))
    (is (= ["latest"] (mapv :text (recording-detail/record-messages
                                   [{:full_text "older"} {:full_text "latest"}] :final))))
    (is (= ["first" "second"] (mapv :text (recording-detail/record-messages
                                           [{:segment_start_s 10 :full_text "second"}
                                            {:segment_start_s 0 :full_text "first"}] :refined))))
    (let [segment {:start_s 1 :end_s 2 :text "Timed" :speaker "Speaker"
                   :words [{:start_s 1 :end_s 2 :word "Timed"}]}]
      (is (= [(assoc segment :kind "final" :seq 0 :lang "en")]
             (recording-detail/record-messages [{:lang "en" :segments [segment]}] :final))))))

(deftest transcript-labels-test
  (testing "A single transcript per stage does not expose provider IDs"
    (let [tabs (recording-detail/track-tabs
                {:session {:stream_controls {:refined true :final true
                                             :refinement_tracks ["internal-refiner"]
                                             :final_tracks ["internal-finalizer"]}}})]
      (is (= ["Refined Transcript" "Final Transcript"] (mapv :label tabs)))
      (is (= [[:refined "internal-refiner"] [:final "internal-finalizer"]]
             (mapv :id tabs)))
      (is (every? #(empty? (:rows %)) tabs))))
  (testing "Multiple transcripts retain saved labels and independent availability"
    (let [tabs (recording-detail/track-tabs
                {:session {:status "finished"
                           :stream_controls {:refined true
                                             :refinement_tracks ["first" "second"]
                                             :track_labels {:refined {:first "Meeting notes"
                                                                      :second "Alternative"}}}}
                 :transcripts {:refined [{:track_id "second" :full_text "Hello"}]}})]
      (is (= ["Refined Transcript (Meeting notes)" "Refined Transcript (Alternative)"]
             (mapv :label tabs)))
      (is (empty? (:rows (first tabs))))
      (is (= ["Hello"] (mapv :full_text (:rows (second tabs)))))))
  (testing "Legacy results without explicit selections stay readable"
    (is (= ["Final Transcript"]
           (mapv :label (recording-detail/track-tabs
                         {:transcripts {:final [{:track_id nil :full_text "Hello"}]}}))))))

(ns samuraibff.transcripts.corrections-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.transcripts.corrections :as corrections]))

(def segments
  [{:start_s 0.0 :end_s 3.0 :speaker "SPEAKER_00" :text "Let's go through the Q-four numbers"
    :words [{:start_s 0.0 :end_s 0.4 :text "Let's"}
            {:start_s 0.4 :end_s 0.6 :text "go"}
            {:start_s 0.6 :end_s 1.0 :text "through"}
            {:start_s 1.0 :end_s 1.2 :text "the"}
            {:start_s 1.2 :end_s 2.0 :text "Q-four"}
            {:start_s 2.0 :end_s 3.0 :text "numbers"}]}
   {:start_s 3.0 :end_s 5.0 :speaker "Anna" :text "Churn worries me"}
   {:start_s 5.0 :end_s 6.0 :speaker "SPEAKER_00" :text "Agreed"}
   {:start_s 6.0 :end_s 7.0 :text "Thanks"}])

(deftest word-diff-test
  (is (= [[:same "Let's go through the"] [:del "Q-four"] [:ins "Q4"] [:same "numbers"]]
         (corrections/word-diff "Let's go through the Q-four numbers" "Let's go through the Q4 numbers")))
  (is (= [[:same "a b"]] (corrections/word-diff "a  b" " a b ")))
  (is (= [[:del "old words"]] (corrections/word-diff "old words" "")))
  (is (= [[:ins "new"]] (corrections/word-diff nil "new")))
  (testing "very long texts fall back to a common prefix and suffix"
    (let [old (apply str (interpose " " (concat ["start"] (repeat 600 "x") ["end"])))
          new (apply str (interpose " " (concat ["start"] (repeat 600 "y") ["end"])))
          diff (corrections/word-diff old new)]
      (is (= [:same "start"] (first diff)))
      (is (= [:same "end"] (last diff))))))

(deftest retime-words-test
  (let [words (:words (first segments))]
    (testing "matched words keep timing and new words share the gap"
      (is (= [{:start_s 0.0 :end_s 0.4 :text "Let's"}
              {:start_s 0.4 :end_s 0.6 :text "go"}
              {:start_s 0.6 :end_s 1.0 :text "through"}
              {:start_s 1.0 :end_s 1.2 :text "the"}
              {:start_s 1.2 :end_s 2.0 :text "Q4"}
              {:start_s 2.0 :end_s 3.0 :text "numbers"}]
             (corrections/retime-words words "Let's go through the Q4 numbers" 0.0 3.0))))
    (testing "an unmatched tail uses the segment end"
      (is (= [{:start_s 0.0 :end_s 0.4 :text "Let's"}
              {:start_s 0.4 :end_s 1.7 :text "stop"}
              {:start_s 1.7 :end_s 3.0 :text "here"}]
             (corrections/retime-words words "Let's stop here" 0.0 3.0))))
    (is (nil? (corrections/retime-words [] "text" 0.0 1.0)))
    (is (nil? (corrections/retime-words words "" 0.0 3.0)))))

(deftest corrected-segments-test
  (let [log [{:id "1" :seq 1 :kind "speaker_label" :speaker_label "SPEAKER_00"
              :new_value {:speaker "Anna" :speaker_id "e1"}}
             {:id "2" :seq 2 :kind "segment_text" :segment_index 0
              :new_value {:text "Let's go through the Q4 numbers"}}
             {:id "3" :seq 3 :kind "segment_speaker" :segment_index 1 :new_value {:speaker "Petr"}}
             {:id "4" :seq 4 :kind "segment_text" :segment_index 2 :new_value {:text "Agreed, fully"}}
             {:id "5" :seq 5 :kind "undo" :undoes_id "4"}]
        result (corrections/corrected-segments segments log)]
    (is (= 4 (count result)))
    (is (= {:speaker "Anna" :speaker_id "e1" :text "Let's go through the Q4 numbers"}
           (select-keys (nth result 0) [:speaker :speaker_id :text])))
    (is (= "Q4" (:text (nth (:words (nth result 0)) 4))))
    (testing "a segment override wins over the original enrolled label"
      (is (= "Petr" (:speaker (nth result 1))))
      (is (nil? (:speaker_id (nth result 1)))))
    (testing "an undone text edit has no effect; the label map still applies"
      (is (= {:speaker "Anna" :text "Agreed"} (select-keys (nth result 2) [:speaker :text]))))
    (is (= (nth segments 3) (nth result 3)))
    (testing "undoing the latest text edit restores the previous one"
      (let [log [{:id "a" :seq 1 :kind "segment_text" :segment_index 3 :new_value {:text "Thank you"}}
                 {:id "b" :seq 2 :kind "segment_text" :segment_index 3 :new_value {:text "Thanks a lot"}}
                 {:id "c" :seq 3 :kind "undo" :undoes_id "b"}]]
        (is (= "Thank you" (:text (nth (corrections/corrected-segments segments log) 3))))))))

(deftest segment-changes-test
  (let [log [{:id "1" :seq 1 :kind "speaker_label" :speaker_label "SPEAKER_00" :new_value {:speaker "Anna"}}
             {:id "2" :seq 2 :kind "segment_text" :segment_index 2 :new_value {:text "Agreed, fully"}}
             {:id "3" :seq 3 :kind "undo" :undoes_id "2"}]
        changes (corrections/segment-changes segments log)
        third (nth changes 2)]
    (is (= 3 (corrections/head-seq log)))
    (is (nil? (:text-edit third)))
    (is (= "1" (:id (:speaker-edit third))))
    (is (= ["2" "1"] (mapv :id (:history third))))
    (is (true? (:undone (first (:history third)))))
    (is (= "3" (:id (:undone_by (first (:history third))))))
    (is (empty? (:history (nth changes 1))))
    (is (= ["SPEAKER_00" "Anna"] (corrections/machine-labels segments)))))

(deftest segment-target-test
  (is (= [:text 2] (corrections/segment-target {:kind "segment_text" :segment_index 2})))
  (is (= [:speaker-label "SPEAKER_01"] (corrections/segment-target {:kind "speaker_label" :speaker_label "SPEAKER_01"})))
  (is (nil? (corrections/segment-target {:kind "undo"}))))

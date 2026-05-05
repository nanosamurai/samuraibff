(ns samuraibff.ui.recording-detail-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.recording-detail :as recording-detail]
   [samuraibff.ui.transcript :as transcript]))

(deftest available-transcript-tabs-hides-empty-feeds
  (testing "Only transcript feeds with messages yield visible tabs"
    (is (= []
           (recording-detail/available-transcript-tabs
            {:realtime-msgs []
             :refined-msgs nil
             :final-msgs []})))

    (is (= [:realtime]
           (recording-detail/available-transcript-tabs
            {:realtime-msgs [{:kind "asr" :start_s 0 :end_s 1 :text "hi"}]
             :refined-msgs []
             :final-msgs []})))

    ;; Display order is fixed: realtime, refined, final.
    (is (= [:realtime :final]
           (recording-detail/available-transcript-tabs
            {:realtime-msgs [{:kind "asr" :start_s 0 :end_s 1 :text "hi"}]
             :refined-msgs []
             :final-msgs [{:kind "final" :start_s 0 :end_s 1 :text "done"}]})))))

(deftest default-transcript-tab-prefers-final
  (testing "Default transcript tab prefers final over refined and realtime"
    (is (= nil (recording-detail/default-transcript-tab [])))
    (is (= :final (recording-detail/default-transcript-tab [:realtime :final])))
    (is (= :refined (recording-detail/default-transcript-tab [:realtime :refined])))
    (is (= :realtime (recording-detail/default-transcript-tab [:realtime])))))

(deftest db-refined-records->events-inherits-lang-from-record
  (testing "DB refined record lang is inherited by per-segment events when segment omits :lang"
    (let [records [{:event_created_at_ns nil
                    :lang "en"
                    :segments [{:start_s 0.031
                               :end_s 19.977
                               :text "hello"
                               :speaker "SPEAKER_00"}]}]
          events (recording-detail/db-refined-records->events records)
          msgs (recording-detail/refined-events->messages events)
          msg (first msgs)
          cached (transcript/normalize-refined {:seq 1
                                               :ts_ms 1
                                               :start_s 0.031
                                               :end_s 19.977
                                               :text "hello"
                                               :speaker "SPEAKER_00"
                                               :lang "en"})]
      (is (= 1 (count msgs)))
      (is (= "en" (:lang msg)))
      ;; This is the key property that prevents duplicates in Recording detail.
      (is (= (transcript/refined-dedupe-key msg)
             (transcript/refined-dedupe-key cached))))))

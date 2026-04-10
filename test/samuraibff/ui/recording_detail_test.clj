(ns samuraibff.ui.recording-detail-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.recording-detail :as recording-detail]
   [samuraibff.ui.transcript :as transcript]))

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

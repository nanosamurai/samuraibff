(ns samuraibff.ui.transcript-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.ui.transcript :as transcript]))

(deftest upsert-asr-partial-replaces-last-partial
  (testing "Partial ASR updates replace the partial for the same window"
    (let [msgs []
          msgs (transcript/upsert-asr msgs {:seq 1 :ts_ms 10 :start_s 0.0 :end_s 1.0 :text "hel" :final false})
          msgs (transcript/upsert-asr msgs {:seq 2 :ts_ms 11 :start_s 0.0 :end_s 1.2 :text "hello" :final false})]
      (is (= 1 (count msgs)))
      (is (= "hello" (:text (first msgs))))
      (is (= 2 (:seq (first msgs)))))))

(deftest upsert-asr-final-commits-last-partial
  (testing "FINAL ASR replaces (commits) the partial for the same window"
    (let [msgs []
          msgs (transcript/upsert-asr msgs {:seq 1 :ts_ms 10 :start_s 0.0 :end_s 1.0 :text "hel" :final false})
          msgs (transcript/upsert-asr msgs {:seq 2 :ts_ms 11 :start_s 0.0 :end_s 1.2 :text "hello" :final false})
          msgs (transcript/upsert-asr msgs {:seq 3 :ts_ms 12 :start_s 0.0 :end_s 1.5 :text "hello!" :final true})]
      (is (= 1 (count msgs)))
      (is (= "hello!" (:text (first msgs))))
      (is (= 3 (:seq (first msgs))))
      (is (true? (:final (first msgs)))))))

(deftest upsert-asr-interleaved-windows-do-not-clobber
  (testing "Interleaved partials for adjacent windows do not overwrite each other"
    ;; Simulate a sliding window (window=5s overlap=0.5s => stride ~4.5s)
    ;; where rtservice may emit partials for next window while still updating
    ;; the previous window.
    (let [msgs []
          ;; Window A (start ~4.5)
          msgs (transcript/upsert-asr msgs {:seq 10 :ts_ms 10 :start_s 4.53 :end_s 7.00 :text "A-partial-1" :final false})
          ;; Window B begins (start ~9.0)
          msgs (transcript/upsert-asr msgs {:seq 11 :ts_ms 11 :start_s 9.03 :end_s 10.00 :text "B-partial-1" :final false})
          ;; Window A partial updates again
          msgs (transcript/upsert-asr msgs {:seq 12 :ts_ms 12 :start_s 4.53 :end_s 8.50 :text "A-partial-2" :final false})]
      (is (= 2 (count msgs)))
      (is (= #{"A-partial-2" "B-partial-1"} (set (map :text msgs)))))))

(deftest upsert-asr-partial-after-final-ignored
  (testing "Late PARTIAL after a FINAL for the same window is ignored"
    (let [msgs []
          msgs (transcript/upsert-asr msgs {:seq 1 :ts_ms 1 :start_s 4.53 :end_s 9.20 :text "A-final" :final true})
          msgs (transcript/upsert-asr msgs {:seq 2 :ts_ms 2 :start_s 4.53 :end_s 9.50 :text "A-late-partial" :final false})]
      (is (= 1 (count msgs)))
      (is (= "A-final" (:text (first msgs))))
      (is (true? (:final (first msgs)))))))

(deftest upsert-asr-final-pairs-with-partial-even-when-start-shifts
  (testing "FINAL replaces PARTIAL even if diarization shifts start_s and adds speaker"
    ;; This reproduces the observed UI issue:
    ;; - PARTIAL arrives early with speaker empty (Unknown) and start at 0
    ;; - FINAL arrives later with speaker assigned and start shifted (e.g. 1.09)
    ;; If we match only by start_s epsilon, we'd fail to pair and leave an orphan partial.
    (let [msgs []
          msgs (transcript/upsert-asr msgs {:seq 1 :ts_ms 1 :start_s 0.00 :end_s 9.50 :text "Okay good afternoon ..." :speaker "" :final false})
          msgs (transcript/upsert-asr msgs {:seq 2 :ts_ms 2 :start_s 1.09 :end_s 9.97 :text "Okay good afternoon ... UI" :speaker "SPEAKER_00" :final true})]
      (is (= 1 (count msgs)) (str "Expected FINAL to replace the PARTIAL, got: " (pr-str msgs)))
      (is (true? (:final (first msgs))))
      (is (= "SPEAKER_00" (:speaker (first msgs)))))))

(deftest apply-refined-removes-contained-asr-only
  (testing "Refined removes ASR messages fully contained within refined window (inclusive)"
    (let [asr1 {:kind "asr" :seq 1 :ts_ms 1 :start_s 0.0 :end_s 2.0 :text "a" :final true}
          asr2 {:kind "asr" :seq 2 :ts_ms 2 :start_s 2.0 :end_s 4.0 :text "b" :final true}
          asr3 {:kind "asr" :seq 3 :ts_ms 3 :start_s 4.0 :end_s 6.0 :text "c" :final true}
          old-ref {:kind "refined" :seq 9 :ts_ms 9 :start_s 2.0 :end_s 4.0 :text "old"}
          msgs [asr1 asr2 asr3 old-ref]

          ;; refined window fully contains asr2 (2.0..4.0), but not asr1/asr3.
          msgs' (transcript/apply-refined msgs {:seq 10 :ts_ms 10 :start_s 2.0 :end_s 4.0 :text "ref"})
          kept-seqs (mapv :seq msgs')]
      ;; asr2 is removed, refined inserted, other refined kept
      (is (not (some #{2} kept-seqs)))
      (is (some #{9} kept-seqs))
      (is (some #{10} kept-seqs)))))

(deftest apply-refined-keeps-overlapping-but-not-contained
  (testing "Overlapping-but-not-contained ASR segments are kept"
    (let [asr {:kind "asr" :seq 1 :ts_ms 1 :start_s 1.0 :end_s 3.0 :text "x" :final true}
          msgs [asr]
          ;; refined window overlaps but does not fully contain (end smaller)
          msgs' (transcript/apply-refined msgs {:seq 10 :ts_ms 10 :start_s 0.0 :end_s 2.0 :text "ref"})]
      (is (= [10 1] (mapv :seq msgs'))))))

(deftest apply-refined-sorts-by-start
  (testing "Result is sorted by start_s then seq"
    (let [msgs []
          msgs (transcript/upsert-asr msgs {:seq 2 :ts_ms 2 :start_s 10 :end_s 11 :text "late" :final true})
          msgs (transcript/upsert-asr msgs {:seq 1 :ts_ms 1 :start_s 0 :end_s 1 :text "early" :final true})
          ;; upsert-asr appends; ordering is currently insertion order, apply-refined should sort.
          msgs' (transcript/apply-refined msgs {:seq 3 :ts_ms 3 :start_s 5 :end_s 6 :text "mid"})]
      (is (= [1 3 2] (mapv :seq msgs'))))))

(ns samuraibff.ui.transcript-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.ui.transcript :as transcript]))

(deftest upsert-asr-partial-replaces-last-partial
  (testing "Partial ASR updates replace the last partial"
    (let [msgs []
          msgs (transcript/upsert-asr msgs {:seq 1 :ts_ms 10 :start_s 0.0 :end_s 1.0 :text "hel" :final false})
          msgs (transcript/upsert-asr msgs {:seq 2 :ts_ms 11 :start_s 0.0 :end_s 1.2 :text "hello" :final false})]
      (is (= 1 (count msgs)))
      (is (= "hello" (:text (first msgs))))
      (is (= 2 (:seq (first msgs)))))))

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

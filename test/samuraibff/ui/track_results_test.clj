(ns samuraibff.ui.track-results-test
  (:require [clojure.test :refer [deftest is]]
            [samuraibff.ui.track-results :as tracks]))

(deftest ordered-independent-windows-with-text-only-fallback
  (let [first-window {:run_id "one" :unit_id "fixed-160000:0:160000" :revision 1 :result_id "a" :status "succeeded"}
        second-window {:run_id "one" :unit_id "fixed-160000:160000:320000" :revision 1 :result_id "b" :status "succeeded"}
        newer (assoc first-window :revision 2 :result_id "c")
        failed {:run_id "one" :unit_id "fixed-160000:320000:480000" :revision 1 :result_id "d" :status "failed"}
        results [second-window first-window failed newer first-window]
        artifacts {"c" {:transcript {:full_text "First" :segments []}}
                   "b" {:transcript {:segments [{:text "Second" :words [{:text "Second" :start_s 10.5 :end_s 11.0}]}]}}}
        messages (tracks/messages results artifacts)]
    (is (= ["c" "b" "d"] (mapv :result_id (tracks/latest-results results))))
    (is (= ["First" "Second"] (mapv :text messages)))
    (is (not (contains? (first messages) :start_s)))
    (is (= 10.5 (get-in messages [1 :words 0 :start_s])))
    (is (= "1 failed · 2 succeeded" (tracks/status-label {:stage "refined" :results results})))
    (is (= 2 (count (tracks/latest-results [first-window (assoc first-window :run_id "two")]))))))

(deftest defaults-and-primary-are-visible-before-audio
  (let [entries [{:track_id "primary" :primary true :default_selected true}
                 {:track_id "comparison" :primary false :default_selected false}]]
    (is (= ["primary"] (tracks/selected-ids entries nil)))
    (is (= ["primary" "comparison"] (tracks/selected-ids entries ["comparison"])))
    (is (= "Waiting for results" (tracks/status-label {:stage "final" :results []})))
    (is (= "Succeeded" (tracks/status-label {:stage "final" :results [{:unit_id "recording" :revision 1 :status "succeeded"}]})))))

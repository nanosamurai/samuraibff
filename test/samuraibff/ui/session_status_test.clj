(ns samuraibff.ui.session-status-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.session-status :as session-status]))

(deftest display-status-test
  (testing "a finished session with a final transcript is Finalized"
    (is (= ["Finalized" "ok" :circle-check]
           ((juxt :label :badge-class :icon)
            (session-status/display-status {:status "finished" :has_recording true :has_final_transcript true})))))
  (testing "a finished session without a final transcript is Finished"
    (is (= "Finished" (:label (session-status/display-status {:status "finished" :has_final_transcript false})))))
  (testing "other states"
    (is (= "Recording" (:label (session-status/display-status {:status "active"}))))
    (is (= "Failed" (:label (session-status/display-status {:status "failed" :has_final_transcript true}))))
    (is (= "Created" (:label (session-status/display-status {:status "created"}))))
    (is (= "Processing" (:label (session-status/display-status {:status "stopping" :has_recording true}))))
    (is (= "Created" (:label (session-status/display-status nil)))))
  (testing "every icon exists"
    (doseq [s [{:status "failed"} {:status "created"} {:status "finished" :has_final_transcript true}
               {:status "finished"} {:status "x" :has_recording true}]]
      (is (contains? icons/names (:icon (session-status/display-status s))) (pr-str s)))))

(ns samuraibff.ui.destinations-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.destinations :as destinations]))

(def ^:private items
  [{:id "a" :name "CRM" :enabled true :url "https://hooks.acme.example/crm"}
   {:id "b" :name "Archive" :enabled true :url "https://archive.example/in"}
   {:id "c" :name "Off" :enabled false :url "https://off.example"}])

(deftest effective-items-test
  (testing "defaults are used unless turned off"
    (is (= ["a"] (mapv :id (destinations/effective-items items {} ["a"] :webhook_ids))))
    (is (= [] (mapv :id (destinations/effective-items items {:use_defaults false} ["a"] :webhook_ids)))))
  (testing "explicit selections add to defaults; disabled items never count"
    (is (= ["a" "b"] (mapv :id (destinations/effective-items
                                items {:use_defaults true :webhook_ids #{"b" "c"}} ["a"] :webhook_ids))))))

(deftest labels-test
  (testing "webhook labels show only the destination host"
    (is (= "CRM → hooks.acme.example" (destinations/webhook-label (first items)))))
  (testing "workflow labels show the model"
    (is (= "Notes · model-x" (destinations/workflow-label {:name "Notes" :provider {:model_id "model-x"}})))
    (is (= "Notes" (destinations/workflow-label {:name "Notes"})))))

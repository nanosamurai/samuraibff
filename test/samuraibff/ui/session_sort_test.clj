(ns samuraibff.ui.session-sort-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.session-sort :as session-sort]))

(deftest columns-test
  (testing "the five sortable columns use the API sort values; Actions is not one of them"
    (is (= ["title" "created_at" "started_at" "duration" "status"]
           (mapv :key session-sort/columns)))
    (is (= ["Session" "Created" "Started" "Duration" "Status"]
           (mapv :label session-sort/columns)))
    (is (not-any? #(= "Actions" (:label %)) session-sort/columns)))
  (testing "the default sort is newest first, as before"
    (is (= {:sort "created_at" :order "desc"} session-sort/default-sort))))

(deftest next-sort-test
  (testing "a click on the active column reverses the order"
    (is (= {:sort "created_at" :order "asc"}
           (session-sort/next-sort session-sort/default-sort "created_at")))
    (is (= {:sort "created_at" :order "desc"}
           (session-sort/next-sort {:sort "created_at" :order "asc"} "created_at"))))
  (testing "a click on another column starts with its first order"
    (is (= {:sort "title" :order "asc"} (session-sort/next-sort session-sort/default-sort "title")))
    (is (= {:sort "duration" :order "desc"} (session-sort/next-sort session-sort/default-sort "duration")))
    (is (= {:sort "status" :order "asc"} (session-sort/next-sort {:sort "title" :order "desc"} "status")))
    (is (= {:sort "started_at" :order "desc"} (session-sort/next-sort session-sort/default-sort "started_at")))))

(deftest header-state-test
  (let [current {:sort "duration" :order "asc"}]
    (testing "only the active header has aria-sort"
      (is (= "ascending" (session-sort/aria-sort current "duration")))
      (is (= "descending" (session-sort/aria-sort {:sort "duration" :order "desc"} "duration")))
      (doseq [other ["title" "created_at" "started_at" "status"]]
        (is (nil? (session-sort/aria-sort current other)) other)))
    (testing "the indicator shows the direction of the active column"
      (is (= :chevron-up (session-sort/indicator-icon current "duration")))
      (is (= :chevron-down (session-sort/indicator-icon {:sort "duration" :order "desc"} "duration")))
      (is (= :chevrons-up-down (session-sort/indicator-icon current "title"))))
    (testing "every indicator icon exists"
      (doseq [icon-name [:chevron-up :chevron-down :chevrons-up-down]]
        (is (contains? icons/names icon-name) (str icon-name))))
    (testing "the header tooltip says what a click does"
      (is (= "Sort by Duration, longest first"
             (session-sort/header-title current (nth session-sort/columns 3))))
      (is (= "Sort by Session, A to Z"
             (session-sort/header-title current (first session-sort/columns)))))))

(deftest select-options-test
  (testing "the mobile select offers every column in both orders"
    (is (= 10 (count session-sort/select-options)))
    (is (= ["title:asc" "Session, A to Z"] (first session-sort/select-options))))
  (testing "option values round-trip"
    (doseq [[value _] session-sort/select-options]
      (is (= value (session-sort/option-value (session-sort/parse-option-value value))))))
  (testing "an unknown option value falls back to the default sort"
    (is (= session-sort/default-sort (session-sort/parse-option-value "actions:asc")))
    (is (= session-sort/default-sort (session-sort/parse-option-value nil)))))

(deftest format-duration-test
  (testing "minutes and seconds under one hour, hours from one hour"
    (is (= "0:00" (session-sort/format-duration 0)))
    (is (= "0:42" (session-sort/format-duration 42.4)))
    (is (= "1:00" (session-sort/format-duration 59.6)))
    (is (= "12:05" (session-sort/format-duration 725)))
    (is (= "1:00:00" (session-sort/format-duration 3600)))
    (is (= "2:03:09" (session-sort/format-duration 7389.2))))
  (testing "no value shows a dash"
    (is (= "—" (session-sort/format-duration nil)))
    (is (= "—" (session-sort/format-duration -1)))))

(deftest duration-text-test
  (testing "a finished session shows its duration"
    (is (= "1:30" (session-sort/duration-text {:status "finished" :duration_s 90.0}))))
  (testing "drafts and sessions without a duration show a dash"
    (is (= "—" (session-sort/duration-text {:status "created" :duration_s nil})))
    (is (= "—" (session-sort/duration-text {:status "finished"}))))
  (testing "a session that is recording shows nothing; its status says Recording"
    (is (= "" (session-sort/duration-text {:status "active" :duration_s nil})))))

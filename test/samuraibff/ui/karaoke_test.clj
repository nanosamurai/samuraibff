(ns samuraibff.ui.karaoke-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.ui.karaoke :as karaoke]))

(deftest active-word-idx-basic
  (testing "Returns nil when empty or no match"
    (is (nil? (karaoke/active-word-idx [] 0.0)))
    (is (nil? (karaoke/active-word-idx [{:start_s 1.0 :end_s 2.0 :text "a"}] 0.5))))

  (testing "Finds active word inclusively"
    (let [words [{:start_s 0.0 :end_s 0.5 :text "a"}
                 {:start_s 0.5 :end_s 1.0 :text "b"}
                 {:start_s 1.0 :end_s 2.0 :text "c"}]]
      (is (= 0 (karaoke/active-word-idx words 0.0)))
      (is (= 0 (karaoke/active-word-idx words 0.49)))
      ;; boundary belongs to previous by our definition (last start<=t)
      (is (= 1 (karaoke/active-word-idx words 0.5)))
      (is (= 2 (karaoke/active-word-idx words 1.5)))
      (is (= 2 (karaoke/active-word-idx words 2.0)))
      (is (nil? (karaoke/active-word-idx words 2.01))))))

(deftest word-text-trims
  (is (= "hello" (karaoke/word-text {:text "  hello  "})))
  (is (= "" (karaoke/word-text {:text nil}))))

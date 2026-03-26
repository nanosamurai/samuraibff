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

(deftest active-word-idx-normalized-behaves-like-active-word-idx
  (testing "Normalized variant matches the behavior"
    (let [words [{:start_s 0.0 :end_s 0.5 :text "a"}
                 {:start_s 0.5 :end_s 1.0 :text "b"}
                 {:start_s 1.0 :end_s 2.0 :text "c"}]
          ws (karaoke/normalize-words words)]
      (doseq [t [0.0 0.49 0.5 1.5 2.0 2.01]]
        (is (= (karaoke/active-word-idx words t)
               (karaoke/active-word-idx-normalized ws t))
            (str "t=" t))))))

(deftest build-word-index-flattens-and-sorts
  (testing "Flattened index contains msg + word position and is sorted"
    (let [messages [{:text "seg1"
                     :words [{:start_s 1.0 :end_s 1.2 :text "b"}
                             {:start_s 0.5 :end_s 0.7 :text "a"}]}
                    {:text "seg2"
                     :words [{:start_s 2.0 :end_s 2.1 :text "c"}]}]
          idx (karaoke/build-word-index messages)]
      ;; sorted by :start_s
      (is (= [0.5 1.0 2.0] (mapv :start_s idx)))
      ;; msg index + word index preserved
      (is (= [{:msg-idx 0 :word-idx 1}
              {:msg-idx 0 :word-idx 0}
              {:msg-idx 1 :word-idx 0}]
             (mapv (fn [w] (select-keys w [:msg-idx :word-idx])) idx))))))

(deftest word-text-trims
  (is (= "hello" (karaoke/word-text {:text "  hello  "})))
  (is (= "" (karaoke/word-text {:text nil}))))

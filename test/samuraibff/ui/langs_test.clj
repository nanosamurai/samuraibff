(ns samuraibff.ui.langs-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [samuraibff.ui.langs :as langs]))

(deftest iso-639-1-codes-basic-shape
  (testing "Dataset is non-empty and stable-ish"
    (is (pos? (count langs/iso-639-1-codes)))
    ;; Our UI intentionally filters to Whisper-supported languages.
    (is (<= 80 (count langs/iso-639-1-codes)))
    (is (>= 120 (count langs/iso-639-1-codes)))))

(deftest iso-639-1-codes-are-lowercase-two-letter
  (testing "All codes are lowercase and length=2"
    (doseq [c langs/iso-639-1-codes]
      (is (string? c))
      (is (= 2 (count c)) c)
      (is (= c (str/lower-case c)) c)
      (is (re-matches #"[a-z]{2}" c) c))))

(deftest iso-639-1-codes-are-unique-and-sorted
  (testing "Codes are unique"
    (is (= (count langs/iso-639-1-codes)
           (count (distinct langs/iso-639-1-codes)))))
  (testing "Codes are sorted"
    (is (= langs/iso-639-1-codes
           (vec (sort langs/iso-639-1-codes))))))

(deftest expected-codes-present
  (testing "Sanity: common languages exist"
    (is (some #{"en"} langs/iso-639-1-codes))
    (is (some #{"cs"} langs/iso-639-1-codes))
    (is (some #{"de"} langs/iso-639-1-codes))
    (is (some #{"fr"} langs/iso-639-1-codes))))

(deftest deprecated-aliases-are-not-present
  (testing "Deprecated alias codes are not exposed"
    (doseq [c ["iw" "in" "ji" "jw" "mo" "sh"]]
      (is (not (some #{c} langs/iso-639-1-codes)) c))))

(deftest valid-lang-code-behavior
  (testing "Empty/blank means auto"
    (is (true? (langs/valid-lang-code? "")))
    (is (true? (langs/valid-lang-code? " ")))
    (is (true? (langs/valid-lang-code? nil))))

  (testing "Known codes are valid"
    (is (true? (langs/valid-lang-code? "cs")))
    (is (true? (langs/valid-lang-code? "en"))))

  (testing "Deprecated aliases are invalid"
    (is (false? (langs/valid-lang-code? "iw")))
    (is (false? (langs/valid-lang-code? "in"))))

  (testing "Unknown strings are invalid"
    (is (false? (langs/valid-lang-code? "xx")))
    (is (false? (langs/valid-lang-code? "en-US")))))

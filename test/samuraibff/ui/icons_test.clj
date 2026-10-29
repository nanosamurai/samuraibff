(ns samuraibff.ui.icons-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.icons :as icons]))

(deftest icon-test
  (testing "every navigation icon renders a decorative 24x24 SVG"
    (doseq [icon-name [:sessions :record :webhooks :workflows :speakers :api-credentials :people]]
      (let [[tag attrs & children] (icons/icon icon-name)]
        (is (= :svg tag) (str icon-name))
        (is (= "0 0 24 24" (:viewBox attrs)))
        (is (true? (:aria-hidden attrs)))
        (is (= "false" (:focusable attrs)))
        (is (seq children)))))
  (testing "icons draw in the current text color"
    (doseq [icon-name icons/names]
      (let [[_ attrs] (icons/icon icon-name)]
        (is (some #{"currentColor"} [(:stroke attrs) (:fill attrs)]) (str icon-name)))))
  (testing "size and extra class"
    (let [[_ attrs] (icons/icon :record {:class "nav-icon" :size 20})]
      (is (= "icon-svg nav-icon" (:class attrs)))
      (is (= 20 (:width attrs) (:height attrs)))))
  (testing "an unknown name renders nothing"
    (is (nil? (icons/icon :missing)))
    (is (nil? (icons/icon nil)))))

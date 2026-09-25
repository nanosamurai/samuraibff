(ns samuraibff.ui.pagination-test
  "Check page bounds as sessions are added, filtered, and deleted."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.pagination :as pagination]))

(deftest page-ranges-and-navigation
  (doseq [size pagination/page-sizes]
    (testing (str "Page size " size)
      (let [first-page (pagination/page-info (inc (* 2 size)) 0 size)
            middle-page (pagination/page-info (inc (* 2 size)) 1 size)
            last-page (pagination/page-info (inc (* 2 size)) 2 size)]
        (is (= [1 size false true] ((juxt :from :to :previous? :next?) first-page)))
        (is (= [(inc size) (* 2 size) true true]
               ((juxt :from :to :previous? :next?) middle-page)))
        (is (= [(inc (* 2 size)) (inc (* 2 size)) true false]
               ((juxt :from :to :previous? :next?) last-page)))
        (is (= 3 (:pages last-page)))
        (is (= (* 2 size) (:offset last-page)))))))

(deftest empty-exact-and-shrinking-pages
  (is (= {:page 0 :page-size 20 :pages 1 :offset 0 :from 0 :to 0 :previous? false :next? false}
         (pagination/page-info 0 4 20)))
  (is (= [1 20 40 false] ((juxt :page :offset :to :next?) (pagination/page-info 40 2 20))))
  (is (= [0 1 20 false] ((juxt :page :from :to :next?) (pagination/page-info 20 1 20))))
  (is (= 0 (:page (pagination/page-info 100 -1 20))))
  (is (= 20 (:page-size (pagination/page-info 100 0 0)))))

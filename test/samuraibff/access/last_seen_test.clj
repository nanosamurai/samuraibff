(ns samuraibff.access.last-seen-test
  "Principal resolution writes last_seen_at at most once per interval per process."
  (:require
   [clojure.test :refer [deftest is]]
   [samuraibff.db.principals :as db.principals]))

(deftest last-seen-skips-the-database-inside-the-interval
  (let [writes (atom 0)
        touch! @#'samuraibff.access.principal/touch-last-seen!
        id (random-uuid)]
    (with-redefs [db.principals/touch-last-seen! (fn [_ _] (swap! writes inc))]
      (dotimes [_ 100] (touch! :ds id))
      (is (= 1 @writes) "one UPDATE for many requests")
      (touch! :ds (random-uuid))
      (is (= 2 @writes) "each principal is tracked on its own"))))

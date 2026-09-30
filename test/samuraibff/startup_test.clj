(ns samuraibff.startup-test
  "Keep resolved credentials out of failed startup diagnostics."
  (:require [clojure.test :refer [deftest is]]
            [samuraibff.startup :as startup]))

(deftest nested-configuration-and-messages-are-excluded
  (let [secret "synthetic-secret-must-not-be-logged"
        failure (ex-info (str "configuration failed " secret)
                         {:value {:db {:password secret}}}
                         (ex-info secret {:client-secret secret}))
        details (startup/failure-details failure)]
    (is (= :startup-failed (:event details)))
    (is (= 2 (count (:causes details))))
    (is (every? #(= "clojure.lang.ExceptionInfo" (:exception-class %)) (:causes details)))
    (is (every? seq (map :locations (:causes details))))
    (is (not (.contains (pr-str details) secret)))))

(deftest bounded-cause-chain
  (let [failure (reduce (fn [cause _] (ex-info "private context" {} cause))
                        (RuntimeException. "private context") (range 30))]
    (is (= 8 (count (:causes (startup/failure-details failure)))))))

(deftest startup-success-and-failure
  (let [calls (atom 0)]
    (is (true? (startup/start! #(swap! calls inc))))
    (is (= 1 @calls))
    (is (false? (startup/start! #(throw (ex-info "synthetic secret" {:password "synthetic secret"})))))))

(ns samuraibff.observability.diagnostics-test
  "Ensure shared diagnostic fields cannot contain exception or request payloads."
  (:require [clojure.test :refer [deftest is]]
            [samuraibff.observability.diagnostics :as diagnostics]))

(deftest exceptions-retain-bounded-code-locations-only
  (let [marker "synthetic-private-exception"
        failure (reduce (fn [cause _] (ex-info marker {:authorization marker} cause))
                        (RuntimeException. marker) (range 20))
        details (diagnostics/failure-details failure)]
    (is (= 8 (count (:causes details))))
    (is (every? #(<= 1 (count (:locations %)) 12) (:causes details)))
    (is (not (.contains (pr-str details) marker)))
    (is (every? #(= #{:exception-class :locations} (set (keys %))) (:causes details)))))

(deftest status-and-session-fields-exclude-arbitrary-values
  (doseq [value [nil "synthetic-private-value" 99 600 200.0 {}]]
    (is (nil? (diagnostics/http-status value))))
  (is (= 503 (diagnostics/http-status 503)))
  (doseq [value [nil "synthetic-private-value" "1-1-1-1-1" {} 100]]
    (is (nil? (diagnostics/session-id value))))
  (is (= "abcdef00-1111-2222-3333-444444444444"
         (diagnostics/session-id "ABCDEF00-1111-2222-3333-444444444444"))))

(ns samuraibff.startup-test
  "Keep resolved credentials out of failed startup diagnostics."
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [org.corfield.logging4j2 :as log]
            [org.corfield.logging4j2.impl :as logging]
            [samuraibff.startup :as startup]))

(deftest integrant-failure-does-not-log-resolved-secrets
  (let [secret "synthetic-integrant-credential"
        config {::credentials {:password secret}
                ::failing-component {:credentials (ig/ref ::credentials)}}
        failure (with-redefs [ig/init-key
                              (fn [key value]
                                (if (= key ::failing-component)
                                  (throw (ex-info (str "Connection failed: " secret)
                                                  {:client-secret secret}))
                                  value))]
                  (try (ig/init config)
                       (catch Throwable error error)))
        emitted (atom nil)
        started? (with-redefs [logging/log* (fn [_logger _level & args]
                                              (reset! emitted args))]
                   (startup/start! #(throw failure)))
        output (.getFormattedMessage (apply log/as-message @emitted))]
    (is (= ::ig/build-threw-exception (:reason (ex-data failure))))
    (is (= secret (get-in (ex-data failure) [:value :credentials :password])))
    (is (= secret (get-in (ex-data failure) [:system ::credentials :password])))
    (is (false? started?))
    (is (not-any? #(instance? Throwable %) @emitted))
    (is (.contains output "Application startup failed"))
    (is (.contains output "clojure.lang.ExceptionInfo"))
    (is (not (.contains output secret)))))

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

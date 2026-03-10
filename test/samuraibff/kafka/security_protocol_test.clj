(ns samuraibff.kafka.security-protocol-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.system :as system]
    [samuraibff.kafka.producer :as kafka.producer]
    [samuraibff.kafka.refined-consumer :as refined-consumer])
  (:import
    (java.util Properties)))

(deftest env-overlay-reads-kafka-security-protocol-test
  (testing "apply-env-overrides reads SAMURAIBFF_KAFKA_SECURITY_PROTOCOL into config"
    (with-redefs-fn {#'system/getenv (fn [k]
                                      (case k
                                        "SAMURAIBFF_KAFKA_SECURITY_PROTOCOL" "SSL"
                                        nil))}
      (fn []
        (let [cfg {:samuraibff/config {:kafka {}}}
              ;; call private fn via var
              cfg' (#'system/apply-env-overrides cfg)]
          (is (= "SSL" (get-in cfg' [:samuraibff/config :kafka :security-protocol])))))))

  (testing "apply-env-overrides falls back to generic KAFKA_SECURITY_PROTOCOL"
    (with-redefs-fn {#'system/getenv (fn [k]
                                      (case k
                                        "KAFKA_SECURITY_PROTOCOL" "SSL"
                                        nil))}
      (fn []
        (let [cfg {:samuraibff/config {:kafka {}}}
              cfg' (#'system/apply-env-overrides cfg)]
          (is (= "SSL" (get-in cfg' [:samuraibff/config :kafka :security-protocol]))))))))

(deftest producer-props-sets-security-protocol-test
  (testing "Kafka producer Properties include security.protocol"
    (let [^Properties p (#'kafka.producer/props {:security-protocol "SSL"})]
      (is (= "SSL" (.getProperty p "security.protocol")))))

  (testing "Kafka producer defaults security.protocol to PLAINTEXT"
    (let [^Properties p (#'kafka.producer/props {})]
      (is (= "PLAINTEXT" (.getProperty p "security.protocol"))))))

(deftest refined-consumer-props-sets-security-protocol-test
  (testing "Kafka refined consumer Properties include security.protocol"
    (let [^Properties p (#'refined-consumer/consumer-props {:security-protocol "SSL"})]
      (is (= "SSL" (.getProperty p "security.protocol")))))

  (testing "Kafka refined consumer defaults security.protocol to PLAINTEXT"
    (let [^Properties p (#'refined-consumer/consumer-props {})]
      (is (= "PLAINTEXT" (.getProperty p "security.protocol"))))))

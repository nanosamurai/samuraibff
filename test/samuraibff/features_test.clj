(ns samuraibff.features-test
  "Tests for per-capability feature state."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.features :as features]
   [samuraibff.system :as system]))

(def ^:private all-off
  {:ce_mode true
   :workflow_webhook_runtime_enabled false
   :webhooks_enabled false
   :workflows_ondemand_enabled false
   :workflows_triggered_enabled false
   :uploads_enabled false
   :uploads_max_bytes 0})

(defn- env-config
  "Parse env vars the way the BFF does at startup."
  [env]
  (:samuraibff/config (#'system/apply-env-overrides {:samuraibff/config {}} env)))

(defn- capabilities
  [cfg]
  {:workflows-ondemand (features/workflows-ondemand-enabled? cfg)
   :workflow-definitions (features/workflow-definitions-enabled? cfg)
   :workflows-triggered (features/workflows-triggered-enabled? cfg)
   :webhooks (features/webhooks-enabled? cfg)})

(deftest feature-state-defaults-to-all-off
  (testing "missing config turns every optional capability off"
    (is (true? (features/ce-mode? {})))
    (is (= {:workflows-ondemand false :workflow-definitions false :workflows-triggered false :webhooks false}
           (capabilities {})))
    (is (= all-off (features/feature-state {}))))
  (testing "the bundled system.edn defaults turn every optional capability off"
    (is (= all-off (features/feature-state {:features {:ce-mode? true}})))))

(deftest uploads-need-a-limit-and-a-bucket
  (let [bucket {:s3 {:buckets {:recordings {:bucket "recordings"}}}}]
    (testing "a positive limit with a recordings bucket enables uploads"
      (is (= 134217728 (features/uploads-max-bytes (assoc bucket :uploads {:max-bytes 134217728}))))
      (is (= {:uploads_enabled true :uploads_max_bytes 1024}
             (select-keys (features/feature-state (assoc bucket :uploads {:max-bytes 1024}))
                          [:uploads_enabled :uploads_max_bytes]))))
    (testing "a zero limit or a missing bucket disables uploads"
      (is (zero? (features/uploads-max-bytes (assoc bucket :uploads {:max-bytes 0}))))
      (is (zero? (features/uploads-max-bytes bucket)))
      (is (zero? (features/uploads-max-bytes {:uploads {:max-bytes 1024}})))
      (is (zero? (features/uploads-max-bytes {:uploads {:max-bytes 1024}
                                              :s3 {:buckets {:recordings {:bucket " "}}}}))))))

(deftest each-capability-flag-enables-only-its-capability
  (testing "SAMURAIBFF_WORKFLOWS_ONDEMAND_ENABLED=true enables definitions and on-demand runs"
    (let [cfg (env-config {"SAMURAIBFF_WORKFLOWS_ONDEMAND_ENABLED" "true"})]
      (is (= {:workflows-ondemand true :workflow-definitions true :workflows-triggered false :webhooks false}
             (capabilities cfg)))
      (is (= (assoc all-off :workflows_ondemand_enabled true) (features/feature-state cfg)))))
  (testing "SAMURAIBFF_WORKFLOWS_TRIGGERED_ENABLED=true enables triggers and definitions"
    (let [cfg (env-config {"SAMURAIBFF_WORKFLOWS_TRIGGERED_ENABLED" "true"})]
      (is (= {:workflows-ondemand false :workflow-definitions true :workflows-triggered true :webhooks false}
             (capabilities cfg)))
      (is (= (assoc all-off :workflows_triggered_enabled true) (features/feature-state cfg)))))
  (testing "SAMURAIBFF_WEBHOOKS_ENABLED=true enables webhooks only"
    (let [cfg (env-config {"SAMURAIBFF_WEBHOOKS_ENABLED" "true"})]
      (is (= {:workflows-ondemand false :workflow-definitions false :workflows-triggered false :webhooks true}
             (capabilities cfg)))
      (is (= (assoc all-off :webhooks_enabled true) (features/feature-state cfg)))))
  (testing "triggers and webhooks together report the deprecated runtime key"
    (let [cfg (env-config {"SAMURAIBFF_WORKFLOWS_TRIGGERED_ENABLED" "true"
                           "SAMURAIBFF_WEBHOOKS_ENABLED" "true"})]
      (is (true? (:workflow_webhook_runtime_enabled (features/feature-state cfg))))))
  (testing "an unparseable value leaves the capability off"
    (is (false? (features/webhooks-enabled? (env-config {"SAMURAIBFF_WEBHOOKS_ENABLED" "maybe"}))))))

(deftest deprecated-ce-mode-false-enables-every-capability
  (testing "SAMURAIBFF_CE_MODE=false alone turns all capabilities on, as before"
    (let [cfg (env-config {"SAMURAIBFF_CE_MODE" "false"})]
      (is (= false (get-in cfg [:features :ce-mode?])))
      (is (false? (features/ce-mode? cfg)))
      (is (= {:workflows-ondemand true :workflow-definitions true :workflows-triggered true :webhooks true}
             (capabilities cfg)))
      (is (= {:ce_mode false
              :workflow_webhook_runtime_enabled true
              :webhooks_enabled true
              :workflows_ondemand_enabled true
              :workflows_triggered_enabled true
              :uploads_enabled false
              :uploads_max_bytes 0}
             (features/feature-state cfg)))))
  (testing "an explicit capability flag wins over SAMURAIBFF_CE_MODE=false"
    (let [cfg (env-config {"SAMURAIBFF_CE_MODE" "false"
                           "SAMURAIBFF_WEBHOOKS_ENABLED" "false"})]
      (is (= {:workflows-ondemand true :workflow-definitions true :workflows-triggered true :webhooks false}
             (capabilities cfg)))
      (is (false? (:workflow_webhook_runtime_enabled (features/feature-state cfg))))))
  (testing "SAMURAIBFF_CE_MODE=true does not override an explicit capability flag"
    (is (true? (features/workflows-ondemand-enabled?
                (env-config {"SAMURAIBFF_CE_MODE" "true"
                             "SAMURAIBFF_WORKFLOWS_ONDEMAND_ENABLED" "true"}))))))

(deftest renamed-workflows-flag-is-ignored-with-a-warning
  (testing "the old SAMURAIBFF_WORKFLOWS_ENABLED name does not enable anything"
    (let [cfg (env-config {"SAMURAIBFF_WORKFLOWS_ENABLED" "true"})]
      (is (= {:workflows-ondemand false :workflow-definitions false :workflows-triggered false :webhooks false}
             (capabilities cfg)))))
  (testing "startup reports the old name and its replacement"
    (is (= [["SAMURAIBFF_WORKFLOWS_ENABLED" "SAMURAIBFF_WORKFLOWS_ONDEMAND_ENABLED"]]
           (#'system/renamed-env-in-use {"SAMURAIBFF_WORKFLOWS_ENABLED" "true"})))
    (is (empty? (#'system/renamed-env-in-use {"SAMURAIBFF_WORKFLOWS_ENABLED" " "})))
    (is (empty? (#'system/renamed-env-in-use {})))))

(deftest enabled?-maps-route-features
  (let [cfg {:features {:workflows-triggered-enabled? true}}]
    (is (false? (features/enabled? cfg :workflows-ondemand)))
    (is (true? (features/enabled? cfg :workflow-definitions)))
    (is (true? (features/enabled? cfg :workflows-triggered)))
    (is (false? (features/enabled? cfg :webhooks)))
    (is (true? (features/enabled? cfg :not-gated)))))

(deftest workflow-provider-types-env-is-validated
  (testing "SAMURAIBFF_WORKFLOW_PROVIDER_TYPES parses a comma list"
    (is (= ["bedrock" "openai_compatible"]
           (get-in (#'system/apply-env-overrides
                    {:samuraibff/config {}}
                    {"SAMURAIBFF_WORKFLOW_PROVIDER_TYPES" " bedrock, openai_compatible "})
                   [:samuraibff/config :workflows :provider-types]))))
  (testing "malformed or duplicate types fail at startup"
    (doseq [raw ["Bedrock" "bedrock,bedrock" "bedrock,,x" "a b"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'system/apply-env-overrides {:samuraibff/config {}}
                                                 {"SAMURAIBFF_WORKFLOW_PROVIDER_TYPES" raw}))))))

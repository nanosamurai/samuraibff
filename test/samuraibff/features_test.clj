(ns samuraibff.features-test
  "Tests for edition-derived feature state."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.features :as features]
   [samuraibff.system :as system]))

(deftest feature-state-defaults-to-ce
  (testing "missing config defaults to CE and disables workflow/webhook runtime"
    (is (true? (features/ce-mode? {})))
    (is (false? (features/workflow-webhook-runtime-enabled? {})))
    (is (= {:ce_mode true
            :workflow_webhook_runtime_enabled false
            :webhooks_enabled false
            :workflows_enabled false
            :uploads_enabled false
            :uploads_max_bytes 0}
           (features/feature-state {})))))

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

(deftest samuraibff-ce-mode-env-false-enables-runtime
  (testing "SAMURAIBFF_CE_MODE=false parses into commercial compatibility mode"
    (let [cfg (#'system/apply-env-overrides
               {:samuraibff/config {}}
               (fn [k]
                 (when (= "SAMURAIBFF_CE_MODE" k)
                   "false")))
          app-cfg (:samuraibff/config cfg)]
      (is (= false (get-in app-cfg [:features :ce-mode?])))
      (is (false? (features/ce-mode? app-cfg)))
      (is (true? (features/workflow-webhook-runtime-enabled? app-cfg)))
      (is (true? (features/workflows-enabled? app-cfg))))))

(deftest samuraibff-workflows-enabled-env-enables-workflows-in-ce
  (testing "SAMURAIBFF_WORKFLOWS_ENABLED=true enables workflows only, not the runtime"
    (let [cfg (:samuraibff/config
               (#'system/apply-env-overrides
                {:samuraibff/config {}}
                {"SAMURAIBFF_WORKFLOWS_ENABLED" "true"}))]
      (is (true? (features/ce-mode? cfg)))
      (is (true? (features/workflows-enabled? cfg)))
      (is (true? (features/enabled? cfg :workflows)))
      (is (false? (features/enabled? cfg :webhooks)))
      (is (false? (features/enabled? cfg :workflow-webhook-runtime)))
      (is (= {:ce_mode true
              :workflow_webhook_runtime_enabled false
              :webhooks_enabled false
              :workflows_enabled true}
             (features/feature-state cfg))))))

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

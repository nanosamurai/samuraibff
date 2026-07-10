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
            :workflows_enabled false}
           (features/feature-state {})))))

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
      (is (true? (features/workflow-webhook-runtime-enabled? app-cfg))))))

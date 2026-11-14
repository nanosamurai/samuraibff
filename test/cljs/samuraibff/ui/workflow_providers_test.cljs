(ns samuraibff.ui.workflow-providers-test
  "Unit tests for workflow provider names and the /api/me detail the editor reads."
  (:require [cljs.test :refer [deftest is testing]]
            [samuraibff.ui.auth :as auth]
            [samuraibff.ui.workflow-providers :as workflow-providers]))

(deftest label-test
  (is (= "AWS Bedrock" (workflow-providers/label "bedrock")))
  (is (= "Self-hosted LLM (OpenAI-compatible API)" (workflow-providers/label "openai_compatible")))
  (testing "an unknown type keeps its ID"
    (is (= "vertex" (workflow-providers/label "vertex")))))

(deftest select-options-test
  (testing "a new workflow offers the allowed types in order"
    (is (= [{:value "openai_compatible" :label "Self-hosted LLM (OpenAI-compatible API)"}
            {:value "bedrock" :label "AWS Bedrock"}]
           (workflow-providers/select-options ["openai_compatible" "bedrock"] nil))))
  (testing "an allowed saved type is not repeated or marked"
    (is (= [{:value "openai_compatible" :label "Self-hosted LLM (OpenAI-compatible API)"}]
           (workflow-providers/select-options ["openai_compatible"] "openai_compatible"))))
  (testing "a saved type this server does not allow stays selectable and is marked"
    (is (= [{:value "bedrock" :label "AWS Bedrock (not enabled on this server)"}
            {:value "openai_compatible" :label "Self-hosted LLM (OpenAI-compatible API)"}]
           (workflow-providers/select-options ["openai_compatible"] "bedrock")))))

(deftest me-detail-keeps-provider-types-test
  (let [body #js {:authenticated true
                  :user #js {:sub "u-1"}
                  :tenant_id "t-1"
                  :workflow_provider_types #js ["openai_compatible"]
                  :features #js {:workflows_ondemand_enabled true}}]
    (testing "a signed-in user"
      (let [detail (auth/me-detail body)]
        (is (= ["openai_compatible"] (:workflow_provider_types detail)))
        (is (= "t-1" (:tenant_id detail)))
        (is (= {:sub "u-1"} (:user detail)))
        (is (= {:workflows_ondemand_enabled true} (:features detail)))))
    (testing "an anonymous visitor keeps the list but gets no user fields"
      (aset body "authenticated" false)
      (let [detail (auth/me-detail body)]
        (is (= ["openai_compatible"] (:workflow_provider_types detail)))
        (is (not (contains? detail :user)))
        (is (not (contains? detail :tenant_id)))))))

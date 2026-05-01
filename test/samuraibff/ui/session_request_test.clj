(ns samuraibff.ui.session-request-test
  "Unit tests for UI session request body helpers.

  These tests run under the CLJ test runner against `.cljc` pure functions so
  we can validate the `POST /api/sessions` request construction logic without
  a browser/JS runtime.
  "
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.session-request :as session.req]))

(deftest resolved-webhook-overrides-defaults-to-nil
  (testing "Default webhook_overrides state returns nil (field can be omitted)"
    (is (nil?
         (session.req/resolved-webhook-overrides
          {:webhook_overrides {:use_defaults true
                               :webhook_ids #{}
                               :disable_event_types #{}}})))))

(deftest resolved-session-settings-gated-by-refined-output
  (testing "session_settings are emitted only when enabled and refined output is enabled"
    (is (nil?
         (session.req/resolved-session-settings
          {:controls {:refined false}
           :session_settings {:refined_transcript {:consolidation {:enabled true}}}})))

    (is (= {:refined_transcript {:consolidation {:enabled true}}}
           (session.req/resolved-session-settings
            {:controls {:refined true}
             :session_settings {:refined_transcript {:consolidation {:enabled true}}}})))))

(deftest resolved-workflow-overrides-defaults-to-nil
  (testing "Default workflow_overrides state returns nil (field can be omitted)"
    (is (nil?
         (session.req/resolved-workflow-overrides
          {:workflow_overrides {:use_defaults true
                                :workflow_ids #{}}})))))

(deftest create-session-request-body-includes-workflow-overrides-when-non-default
  (testing "workflow_overrides is included when non-default"
    (let [body (session.req/create-session-request-body
                {:title "t"
                 :controls {:refined true}
                 :workflow_overrides {:use_defaults false
                                      :workflow_ids #{"11111111-1111-1111-1111-111111111111"}}})]
      (is (= {:use_defaults false
              :workflow_ids ["11111111-1111-1111-1111-111111111111"]}
             (:workflow_overrides body))))))

(deftest create-session-request-body-includes-settings-when-enabled
  (testing "create-session-request-body includes webhook_overrides and session_settings when non-default"
    (let [body (session.req/create-session-request-body
                {:title "t"
                 :controls {:refined true}
                 :webhook_overrides {:use_defaults false
                                     :webhook_ids #{"11111111-1111-1111-1111-111111111111"}
                                     :disable_event_types #{}}
                 :session_settings {:refined_transcript {:consolidation {:enabled true}}}})]
      (is (= "t" (:title body)))
      (is (= false (get-in body [:webhook_overrides :use_defaults])))
      (is (= ["11111111-1111-1111-1111-111111111111"] (get-in body [:webhook_overrides :webhook_ids])))
      (is (= true (get-in body [:session_settings :refined_transcript :consolidation :enabled]))))))

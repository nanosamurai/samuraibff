(ns samuraibff.workflow-trigger-gates-test
  "Live workflow results follow SAMURAIBFF_WORKFLOW_TRIGGERS_ENABLED only.

  On-demand runs store their results through samuraipersistor, so neither
  SAMURAIBFF_WORKFLOWS_ENABLED nor SAMURAIBFF_WEBHOOKS_ENABLED opens the
  `/internal/workflow-result` callback or starts the results consumer."
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [samuraibff.http.internal :as http.internal]
   [samuraibff.kafka.workflow-results-consumer]))

(def ^:private without-triggers
  {:features {:workflows-enabled? true
              :webhooks-enabled? true}})

(deftest workflow-result-callback-needs-triggers
  (testing "the callback is gated without workflow triggers"
    (let [handler (http.internal/workflow-result-callback-handler {:config without-triggers
                                                                  :ws-registry nil})
          resp (handler {:request-method :post
                         :uri "/internal/workflow-result"
                         :body-params {}})]
      (is (= 403 (:status resp)))
      (is (= "workflow-triggers" (get-in resp [:body :feature])))))
  (testing "the callback reaches its handler with workflow triggers"
    (let [handler (http.internal/workflow-result-callback-handler
                   {:config {:features {:workflow-triggers-enabled? true}}
                    :ws-registry nil})
          resp (handler {:request-method :post
                         :uri "/internal/workflow-result"
                         :body-params {}})]
      (is (not= 403 (:status resp))))))

(deftest workflow-results-consumer-needs-triggers
  (testing "the consumer does not start without workflow triggers"
    (let [state (ig/init-key :samuraibff/workflow-results-consumer {:config without-triggers
                                                                    :ws-registry nil})]
      (is (true? (:disabled? state)))
      (is (nil? (:thread state)))
      (ig/halt-key! :samuraibff/workflow-results-consumer state))))

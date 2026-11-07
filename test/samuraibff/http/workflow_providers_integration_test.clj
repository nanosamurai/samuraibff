(ns samuraibff.http.workflow-providers-integration-test
  "Workflow provider types are limited to the deployment allowlist."
  (:require
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [samuraibff.db.workflows :as db.workflows]
   [samuraibff.http.workflows :as workflows]
   [samuraibff.test-support.principals :as principals]
   [samuraibff.testcontainers.postgres :as pg])
  (:import (java.util UUID)))

(def ^:private payload
  {:name "Summary"
   :trigger {:type "transcript.final.ready" :track_id "whisperx"}
   :provider {:type "bedrock" :model_id "model"}
   :prompt {:text "Summarize"}})

(deftest provider-type-allowlist
  (pg/with-postgres [container]
    (let [ds (pg/datasource (pg/jdbc-url container) "drsynth" "drsynth")
          _ (pg/apply-schema! ds)
          tenant (UUID/randomUUID)
          _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, 'tenant')" tenant])
          admin (principals/insert-principal! ds {:tenant-id tenant :role :admin})
          request (principals/request-for admin)
          default-deps {:db {:ds ds}}
          local-deps {:db {:ds ds} :config {:workflows {:provider-types ["openai_compatible"]}}}
          create (fn [deps body] ((workflows/create-workflow-handler deps) (assoc request :body-params body)))
          update! (fn [deps id body] ((workflows/update-workflow-handler deps)
                                      (assoc request :path-params {:id id} :body-params body)))
          local (assoc payload :provider {:type "openai_compatible" :model_id "llama3.1:8b"})]
      (testing "the default allowlist accepts bedrock only"
        (is (= 200 (:status (create default-deps payload))))
        (is (= {:status 400 :body {:ok false :message "provider-not-allowed"}}
               (create default-deps (assoc local :name "Local")))))
      (testing "a configured type is accepted and stored"
        (let [id (get-in (create local-deps (assoc local :name "Local")) [:body :workflow_id])]
          (is (string? id))
          (is (= "openai_compatible" (:provider_type (db.workflows/find-workflow ds tenant (UUID/fromString id)))))))
      (testing "a stored type outside the allowlist stays editable, a change to it is rejected"
        (let [bedrock-id (get-in (create default-deps (assoc payload :name "Cloud")) [:body :workflow_id])]
          (is (= 200 (:status (update! local-deps bedrock-id {:name "Cloud v2" :provider (:provider payload)}))))
          (is (= 400 (:status (update! local-deps bedrock-id {:provider {:type "other_type" :model_id "m"}}))))
          (is (= 200 (:status (update! local-deps bedrock-id {:provider (:provider local)}))))))
      (testing "malformed types fail schema validation"
        (is (= 400 (:status (create local-deps (assoc payload :name "Bad"
                                                      :provider {:type "Not A Type" :model_id "m"})))))))))

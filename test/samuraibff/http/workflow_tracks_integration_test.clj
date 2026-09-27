(ns samuraibff.http.workflow-tracks-integration-test
  "Exercise workflow track bindings through HTTP handlers, Postgres and session snapshots."
  (:require
   [clojure.test :refer [deftest is]]
   [next.jdbc :as jdbc]
   [samuraibff.http.workflows :as workflows]
   [samuraibff.schemas :as schemas]
   [samuraibff.testcontainers.postgres :as pg]
   [samuraibff.workflows.snapshot :as snapshot])
  (:import (java.util UUID)))

(deftest workflow-track-binding-round-trip
  (pg/with-postgres [container]
    (let [ds (pg/datasource (pg/jdbc-url container) "drsynth" "drsynth")
          _ (pg/apply-schema! ds)
          tenant (UUID/randomUUID)
          other (UUID/randomUUID)
          _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, 'tenant'), (?, 'other')" tenant other])
          deps {:db {:ds ds}}
          request {:auth/tenant-id (str tenant)}
          payload {:name "Summary" :trigger {:type "transcript.refined.segment" :track_id "parakeet"}
                   :provider {:type "bedrock" :model_id "model"} :prompt {:text "Summarize"}}
          created ((workflows/create-workflow-handler deps) (assoc request :body-params payload))
          id (get-in created [:body :workflow_id])
          item-request (assoc request :path-params {:id id})]
      (is (= 200 (:status created)))
      (let [listed ((workflows/list-workflows-handler deps) request)
            body (:body listed)]
        (is (= body (schemas/validate! schemas/WorkflowsListResponse body)))
        (is (= (:trigger payload) (get-in body [:items 0 :trigger]))))
      (is (= "parakeet" (get-in (snapshot/resolve-targets ds tenant (UUID/randomUUID)
                                                        {:use_defaults false :workflow_ids [id]})
                                [0 :trigger :track_id])))
      (is (= 400 (:status ((workflows/create-workflow-handler deps)
                          (assoc request :body-params (assoc payload :trigger {:type "transcript.refined.segment"}))))))
      (is (= 404 (:status ((workflows/update-workflow-handler deps)
                          (assoc item-request :auth/tenant-id (str other) :body-params
                                 {:trigger {:type "transcript.final.ready" :track_id "whisperx"}})))))
      (is (= 200 (:status ((workflows/update-workflow-handler deps)
                          (assoc item-request :body-params {:trigger {:type "transcript.final.ready" :track_id "whisperx"}})))))
      (is (= {:type "transcript.final.ready" :track_id "whisperx"}
             (get-in ((workflows/list-workflows-handler deps) request) [:body :items 0 :trigger])))
      (is (= 200 (:status ((workflows/update-workflow-handler deps)
                          (assoc item-request :body-params {:trigger {:type "recording.finished"}})))))
      (is (= {:type "recording.finished"}
             (get-in ((workflows/list-workflows-handler deps) request) [:body :items 0 :trigger]))))))

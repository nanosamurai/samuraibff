(ns samuraibff.http.workflow-runs-integration-test
  "On-demand workflow runs: access, source selection, job shape and limits.

  Uses a Postgres testcontainer and a Kafka MockProducer (no broker)."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.http.workflow-runs :as workflow-runs]
   [samuraibff.http.workflows :as http.workflows]
   [samuraibff.schemas :as schemas]
   [samuraibff.test-support.principals :as p]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)
   (org.apache.kafka.clients.producer MockProducer ProducerRecord)
   (org.apache.kafka.common.serialization ByteArraySerializer StringSerializer)))

(def ^:private prompt-text
  "Summarize the visit for the clinician")

(defn- insert-session!
  [ds tenant owner-id status visibility]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, user_id, session_key, status, visibility, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, now())"
                       id tenant owner-id (str id) status visibility])
    id))

(defn- insert-transcript!
  [ds tenant session-id {:keys [type track-id segments start-s]}]
  (jdbc/execute! ds ["INSERT INTO session_transcripts
                        (id, tenant_id, session_id, type, source, model, track_id, full_text, segments, segment_start_s, created_at)
                      VALUES (?, ?, ?, ?, 'worker', 'model', ?, ?, ?::jsonb, ?, now())"
                     (UUID/randomUUID) tenant session-id type track-id
                     (str/join " " (map :text segments)) (cheshire/generate-string segments) start-s]))

(defn- create-workflow!
  [ds principal body]
  (let [resp ((http.workflows/create-workflow-handler {:db {:ds ds}})
              (p/request-for principal {:body-params (update body :name str " " (subs (str (UUID/randomUUID)) 0 8))}))]
    (or (get-in resp [:body :workflow_id])
        (throw (ex-info "workflow not created" {:resp resp})))))

(def ^:private final-workflow
  {:name "Summary"
   :trigger {:type "transcript.final.ready" :track_id "whisperx"}
   :provider {:type "bedrock" :model_id "model-a" :params {:temperature 0.1}}
   :prompt {:text prompt-text}})

(defn- sent-jobs
  [^MockProducer producer]
  (mapv (fn [^ProducerRecord r]
          {:key (.key r)
           :topic (.topic r)
           :job (cheshire/parse-string (String. ^bytes (.value r) "UTF-8") true)})
        (.history producer)))

(defn- audit-events
  [ds tenant]
  (jdbc/execute! ds ["SELECT actor_kind, target_id, details::text AS details FROM access_audit_events
                       WHERE tenant_id = ? AND action = 'workflow.run.requested' ORDER BY created_at"
                     tenant]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(deftest run-workflow-on-demand-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant (p/insert-tenant! ds (UUID/randomUUID) "acme")
          foreign-tenant (p/insert-tenant! ds (UUID/randomUUID) "other")
          owner (p/insert-principal! ds {:tenant-id tenant :role :builder})
          colleague (p/insert-principal! ds {:tenant-id tenant :role :member})
          other-builder (p/insert-principal! ds {:tenant-id tenant :role :builder})
          admin (p/insert-principal! ds {:tenant-id tenant :role :admin})
          service (p/insert-principal! ds {:tenant-id tenant :type :service})
          foreign (p/insert-principal! ds {:tenant-id foreign-tenant :role :admin})
          producer (MockProducer. true nil (StringSerializer.) (ByteArraySerializer.))
          deps {:db {:ds ds}
                :config {:env :test}
                :kafka-producer {:producer producer :topic-workflow-dispatch "workflow.dispatch"}}
          run (fn run
                ([principal session-id workflow-id] (run principal session-id workflow-id nil deps))
                ([principal session-id workflow-id body] (run principal session-id workflow-id body deps))
                ([principal session-id workflow-id body deps]
                 ((workflow-runs/run-workflow-handler deps)
                  (p/request-for principal {:path-params {:session_id (str session-id) :workflow_id (str workflow-id)}
                                            :body-params body}))))
          private-session (insert-session! ds tenant (:id owner) "finished" "private")
          shared-session (insert-session! ds tenant (:id owner) "finished" "tenant")
          active-session (insert-session! ds tenant (:id owner) "active" "private")
          empty-session (insert-session! ds tenant (:id owner) "finished" "private")
          service-session (insert-session! ds tenant (:id service) "finished" "private")
          final-segments [{:start_s 0.0 :end_s 1.5 :speaker "SPEAKER_00" :text "Hello there"}
                          {:start_s 1.5 :end_s 3.0 :text "  "}
                          {:start_s 3.0 :end_s 4.0 :text "General Kenobi"}]
          _ (doseq [s [private-session shared-session active-session service-session]]
              (insert-transcript! ds tenant s {:type "final" :track-id nil :segments final-segments}))
          _ (insert-transcript! ds tenant private-session {:type "refined" :track-id "parakeet" :start-s 10.0
                                                           :segments [{:start_s 10.0 :end_s 11.0 :text "later"}]})
          _ (insert-transcript! ds tenant private-session {:type "refined" :track-id "parakeet" :start-s 0.0
                                                           :segments [{:start_s 0.0 :end_s 1.0 :text "earlier"}]})
          personal-id (create-workflow! ds owner final-workflow)
          other-personal-id (create-workflow! ds other-builder final-workflow)
          published-id (create-workflow! ds admin final-workflow)
          refined-id (create-workflow! ds owner (assoc final-workflow :trigger {:type "transcript.refined.segment"
                                                                                :track_id "parakeet"}))
          recording-id (create-workflow! ds owner (assoc final-workflow :trigger {:type "recording.finished"}))
          disabled-id (create-workflow! ds owner (assoc final-workflow :enabled false))]

      (testing "the owner runs a personal workflow on the stored final transcript"
        (let [resp (run owner private-session personal-id)
              {:keys [key topic job]} (last (sent-jobs producer))]
          (is (= 202 (:status resp)))
          (is (= (:body resp) (schemas/validate! schemas/RunWorkflowResponse (:body resp))))
          (is (= {:workflow_id personal-id :stage "final" :track_id "whisperx"}
                 (select-keys (:body resp) [:workflow_id :stage :track_id])))
          (is (= ["workflow.dispatch" (str private-session)] [topic key]))
          (is (= {:schema_version 1
                  :workflow_run_id (get-in resp [:body :workflow_run_id])
                  :tenant_id (str tenant)
                  :session_id (str private-session)
                  :workflow_id personal-id
                  :provider {:type "bedrock" :model_id "model-a" :params {:temperature 0.1}}
                  :prompt {:text prompt-text}
                  :input {:transcript {:kind "final"
                                       :segments [{:start_s 0.0 :end_s 1.5 :speaker "SPEAKER_00" :text "Hello there"}
                                                  {:start_s 3.0 :end_s 4.0 :text "General Kenobi"}]}}
                  :attempt {:no 0}}
                 (dissoc job :trigger)))
          (is (= {:type "transcript.final.ready" :track_id "whisperx" :source_event_id nil}
                 (dissoc (:trigger job) :created_at)))
          (is (string? (get-in job [:trigger :created_at])))
          (is (not (contains? job :stream)))))

      (testing "the caller can choose the refined source of a final workflow"
        (let [resp (run owner private-session personal-id {:stage "refined"})
              {:keys [job]} (last (sent-jobs producer))]
          (is (= ["refined" "parakeet"] ((juxt :stage :track_id) (:body resp))))
          (is (= {:type "transcript.refined.segment" :track_id "parakeet"} (select-keys (:trigger job) [:type :track_id])))
          (is (= "refined.consolidated" (get-in job [:input :transcript :kind])))
          (is (= ["earlier" "later"] (mapv :text (get-in job [:input :transcript :segments]))))))

      (testing "without a body a workflow uses its own stored source"
        (is (= ["refined" "parakeet"] ((juxt :stage :track_id) (:body (run owner private-session refined-id)))))
        (is (= ["final" "whisperx"] ((juxt :stage :track_id) (:body (run owner shared-session refined-id))))))

      (testing "a recording.finished workflow runs on the default final transcript"
        (let [resp (run owner private-session recording-id)]
          (is (= ["final" "whisperx"] ((juxt :stage :track_id) (:body resp))))
          (is (= "transcript.final.ready" (get-in (last (sent-jobs producer)) [:job :trigger :type])))))

      (testing "unreadable sessions are not found"
        (is (= {:status 404 :body {:ok false :message "not-found"}} (run colleague private-session published-id)))
        (is (= 404 (:status (run foreign private-session published-id))))
        (is (= 404 (:status (run admin private-session published-id)))))

      (testing "readers run only workflows they may use"
        (is (= 202 (:status (run colleague shared-session published-id))))
        (is (= 404 (:status (run colleague shared-session personal-id))))
        (is (= 404 (:status (run other-builder shared-session personal-id))))
        (is (= 404 (:status (run admin shared-session personal-id))))
        (is (= 404 (:status (run owner shared-session other-personal-id))))
        (is (= 404 (:status (run owner shared-session (UUID/randomUUID))))))

      (testing "organization keys run published workflows on their own sessions"
        (is (= 202 (:status (run service service-session published-id))))
        (is (= 404 (:status (run service service-session personal-id))))
        (is (= "service" (:actor_kind (last (audit-events ds tenant))))))

      (testing "preconditions return conflicts"
        (is (= {:status 409 :body {:ok false :message "workflow-disabled"}} (run owner private-session disabled-id)))
        (is (= {:status 409 :body {:ok false :message "session-not-finished"}} (run owner active-session personal-id)))
        (is (= {:status 409 :body {:ok false :message "transcript-not-available"}} (run owner empty-session personal-id)))
        (is (= 409 (:status (run owner private-session personal-id {:stage "final" :track_id "parakeet"}))))
        (is (= 409 (:status (run owner shared-session personal-id {:stage "refined"})))))

      (testing "invalid requests"
        (is (= 400 (:status (run owner private-session personal-id {:track_id "whisperx"}))))
        (is (= 400 (:status (run owner private-session personal-id {:stage "realtime"}))))
        (is (= 400 (:status (run owner private-session personal-id {:stage "final" :extra true}))))
        (is (= 400 (:status (run owner private-session "not-a-uuid")))))

      (testing "jobs above the size limit are rejected before dispatch"
        (let [before (count (.history producer))]
          (is (= {:status 413 :body {:ok false :message "transcript-too-large"}}
                 (run owner private-session personal-id nil (assoc-in deps [:config :workflows :run-max-bytes] 200))))
          (is (= before (count (.history producer))))))

      (testing "without Kafka nothing is dispatched or audited"
        (let [before (count (audit-events ds tenant))]
          (is (= {:status 503 :body {:ok false :message "dispatch-unavailable"}}
                 (run owner private-session personal-id nil (assoc deps :kafka-producer nil))))
          (is (= before (count (audit-events ds tenant))))))

      (testing "audit events hold ids only"
        (let [events (audit-events ds tenant)]
          (is (= (count (.history producer)) (count events)))
          (is (every? #(not (str/includes? (:details %) "Summarize")) events))
          (is (every? #(not (str/includes? (:details %) "Hello")) events))
          (is (= #{"workflow_id" "workflow_run_id" "stage" "track_id"}
                 (set (map name (keys (cheshire/parse-string (:details (first events)))))))))))))

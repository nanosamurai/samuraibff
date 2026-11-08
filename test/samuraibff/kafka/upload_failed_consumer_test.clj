(ns samuraibff.kafka.upload-failed-consumer-test
  "Tests for the `recordings.upload_failed` consumer.

  Unit tests use Kafka's MockConsumer and stub the database update. The
  database test checks `fail-upload-session!` against PostgreSQL. The end to
  end test runs the Integrant component against Kafka and PostgreSQL
  testcontainers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.kafka.upload-failed-consumer :as consumer]
   [samuraibff.system]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.time Duration)
   (java.util Properties UUID)
   (java.util.concurrent TimeUnit)
   (org.apache.kafka.clients.admin AdminClient NewTopic)
   (org.apache.kafka.clients.consumer ConsumerRecord MockConsumer)
   (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
   (org.apache.kafka.common TopicPartition)
   (org.testcontainers.containers KafkaContainer)
   (org.testcontainers.utility DockerImageName)))

(def ^:private topic "recordings.upload_failed")

(defn- event-bytes
  "Return JSON bytes of an upload failure event; `overrides` replace fields."
  ^bytes [session-id tenant-id & {:as overrides}]
  (json/write-value-as-bytes
   (merge {:schema_version 1
           :session_id (str session-id)
           :tenant_id (str tenant-id)
           :reason "decode-failed"
           :created_at "2026-11-08T10:00:00.000Z"}
          overrides)))

(deftest parse-event-test
  (let [sid (UUID/randomUUID)
        tid (UUID/randomUUID)]
    (testing "a version 1 event gives the IDs and the reason"
      (is (= {:session-id sid :tenant-id tid :reason "decode-failed"}
             (consumer/parse-event (event-bytes sid tid)))))
    (testing "a reason that is not a short code is not logged as such"
      (is (= "unknown" (:reason (consumer/parse-event (event-bytes sid tid :reason "bad\nline")))))
      (is (= "unknown" (:reason (consumer/parse-event (event-bytes sid tid :reason nil))))))
    (testing "invalid events are rejected without throwing"
      (doseq [value [(event-bytes sid tid :schema_version 2)
                     (event-bytes sid tid :session_id "../x")
                     (event-bytes sid tid :tenant_id "tenant-a")
                     (event-bytes sid tid :session_id nil)
                     (.getBytes "not json" "UTF-8")
                     (.getBytes "[1,2]" "UTF-8")
                     (byte-array 0)]]
        (is (nil? (consumer/parse-event value)) (String. ^bytes value "UTF-8"))))))

(defn- mock-consumer
  "Return a MockConsumer assigned to partition 0 of the topic, holding `values`."
  [values]
  (let [tp (TopicPartition. topic 0)
        mock (MockConsumer. "earliest")]
    (.assign mock [tp])
    (.updateBeginningOffsets mock {tp 0})
    (doseq [[offset value] (map-indexed vector values)]
      (.addRecord mock (ConsumerRecord. topic 0 (long offset) "key" value)))
    mock))

(defn- committed-offset
  "Return the committed offset of partition 0, or nil."
  [^MockConsumer mock]
  (some-> (.committed mock #{(TopicPartition. topic 0)}) (get (TopicPartition. topic 0)) .offset))

(deftest process-batch-test
  (let [tid (UUID/randomUUID)
        [a b c] (repeatedly 3 #(UUID/randomUUID))]
    (testing "each event is applied in order and committed; invalid events are skipped"
      (let [mock (mock-consumer [(event-bytes a tid) (.getBytes "junk" "UTF-8") (event-bytes b tid)])
            calls (atom [])]
        (with-redefs [db.sessions/fail-upload-session! (fn [_ tenant session]
                                                         (swap! calls conj [tenant session])
                                                         {:updated? (= session a)})]
          (is (true? (consumer/process-batch! mock :ds (.poll mock (Duration/ofMillis 10))))))
        (is (= [[tid a] [tid b]] @calls))
        (is (= 3 (committed-offset mock)))))
    (testing "a database error rewinds to the failed event and keeps it uncommitted"
      (let [mock (mock-consumer [(event-bytes a tid) (event-bytes b tid) (event-bytes c tid)])
            calls (atom [])]
        (with-redefs [db.sessions/fail-upload-session! (fn [_ _ session]
                                                         (swap! calls conj session)
                                                         (when (= session b)
                                                           (throw (RuntimeException. "db down")))
                                                         {:updated? true})]
          (is (false? (consumer/process-batch! mock :ds (.poll mock (Duration/ofMillis 10)))))
          (is (= 1 (committed-offset mock)) "the event before the error is committed")
          (is (= 1 (.position mock (TopicPartition. topic 0))) "the next poll returns the failed event")
          (is (= [a b] @calls) "events after the failed one wait"))))))

(deftest consumer-props-test
  (let [^Properties props (#'consumer/consumer-props {:security-protocol "SSL"})]
    (is (= "SSL" (.getProperty props "security.protocol")))
    (is (= "samuraibff-upload-failed" (.getProperty props "group.id")) "one group for all replicas")
    (is (= "false" (.getProperty props "enable.auto.commit")))
    (is (= "earliest" (.getProperty props "auto.offset.reset")))))

(deftest env-overrides-test
  (let [cfg (:samuraibff/config (#'samuraibff.system/apply-env-overrides
                                 {:samuraibff/config {}}
                                 {"SAMURAIBFF_KAFKA_TOPIC_RECORDINGS_UPLOAD_FAILED" "custom.failed"
                                  "SAMURAIBFF_KAFKA_CONSUMER_GROUP_ID_UPLOAD_FAILED" "custom-group"}))]
    (is (= "custom.failed" (get-in cfg [:kafka :topics :recordings-upload-failed])))
    (is (= "custom-group" (get-in cfg [:kafka :consumer-group-id-upload-failed])))))

(deftest component-stays-off-without-uploads-test
  (is (:disabled? (ig/init-key :samuraibff/upload-failed-consumer
                               {:config {:uploads {:max-bytes 0}} :db {:ds :ds}})))
  (is (:disabled? (ig/init-key :samuraibff/upload-failed-consumer
                               {:config {:uploads {:max-bytes 10}
                                         :s3 {:buckets {:recordings {:bucket "b"}}}}
                                :db {}}))))

(defn- insert-session!
  "Insert a finished session; :upload? marks it as an upload, :final? adds a
  final transcript. Returns its UUID."
  [ds tenant owner {:keys [upload? final? status] :or {status "finished"}}]
  (let [id (UUID/randomUUID)]
    (db.sessions/insert-session! ds (cond-> {:id id :tenant-id tenant :user-id (:id owner)
                                             :session-key (str id) :status status}
                                      upload? (assoc :session-settings {:source "upload"})))
    (when final?
      (jdbc/execute! ds ["INSERT INTO session_transcripts (id, session_id, tenant_id, full_text, segments, source, type)
                          VALUES (?, ?, ?, 'Final', '[]'::jsonb, 'whisperx', 'final')"
                         (UUID/randomUUID) id tenant]))
    id))

(defn- status-of
  [ds id]
  (:sessions/status (jdbc/execute-one! ds ["SELECT status FROM sessions WHERE id = ?" id])))

(deftest fail-upload-session-integration-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant (UUID/randomUUID)
          other (UUID/randomUUID)
          _ (tp/insert-tenant! ds tenant "Acme")
          _ (tp/insert-tenant! ds other "Other")
          owner (tp/insert-principal! ds {:tenant-id tenant})
          upload (insert-session! ds tenant owner {:upload? true})]
      (testing "a finished upload without a final transcript fails once"
        (is (= {:updated? true} (db.sessions/fail-upload-session! ds tenant upload)))
        (is (= "failed" (status-of ds upload)))
        (is (= {:updated? false} (db.sessions/fail-upload-session! ds tenant upload)) "a repeat changes nothing"))
      (testing "other sessions keep their status"
        (doseq [[label opts expected] [["finalized upload" {:upload? true :final? true} "finished"]
                                       ["live session" {} "finished"]
                                       ["running upload" {:upload? true :status "active"} "active"]]]
          (let [id (insert-session! ds tenant owner opts)]
            (is (= {:updated? false} (db.sessions/fail-upload-session! ds tenant id)) label)
            (is (= expected (status-of ds id)) label)))
        (let [id (insert-session! ds tenant owner {:upload? true})]
          (is (= {:updated? false} (db.sessions/fail-upload-session! ds other id)) "another tenant")
          (is (= "finished" (status-of ds id))))))))

(def ^:private kafka-image "confluentinc/cp-kafka:7.6.1")

(defn- create-topic!
  [bootstrap]
  (with-open [admin (AdminClient/create {"bootstrap.servers" bootstrap})]
    (-> (.createTopics admin [(NewTopic. ^String topic (int 1) (short 1))]) .all (.get 30 TimeUnit/SECONDS))))

(defn- publish!
  [bootstrap ^String key ^bytes value]
  (with-open [producer (KafkaProducer. ^java.util.Map {"bootstrap.servers" bootstrap
                                                       "acks" "all"
                                                       "key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
                                                       "value.serializer" "org.apache.kafka.common.serialization.ByteArraySerializer"})]
    (.get (.send producer (ProducerRecord. topic key value)) 30 TimeUnit/SECONDS)))

(defn- await-status
  "Poll the session status until it equals `expected` or 30 s pass."
  [ds id expected]
  (loop [deadline (+ (System/currentTimeMillis) 30000)]
    (let [status (status-of ds id)]
      (if (or (= expected status) (> (System/currentTimeMillis) deadline))
        status
        (do (Thread/sleep 200) (recur deadline))))))

(deftest upload-failed-consumer-end-to-end-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant (UUID/randomUUID)
          _ (tp/insert-tenant! ds tenant "Acme")
          owner (tp/insert-principal! ds {:tenant-id tenant})
          finalized (insert-session! ds tenant owner {:upload? true :final? true})
          upload (insert-session! ds tenant owner {:upload? true})
          container (KafkaContainer. (DockerImageName/parse kafka-image))]
      (try
        (.setPortBindings container ["127.0.0.1::9093"])
        (.start container)
        (let [bootstrap (.getBootstrapServers container)
              _ (create-topic! bootstrap)
              system (ig/init {:samuraibff/upload-failed-consumer
                               {:config {:uploads {:max-bytes 1024}
                                         :s3 {:buckets {:recordings {:bucket "recordings"}}}
                                         :kafka {:bootstrap-servers bootstrap
                                                 :consumer-group-id-upload-failed (str "test-" (UUID/randomUUID))
                                                 :poll-ms 100}}
                                :db {:ds ds}}})]
          (try
            (publish! bootstrap (str finalized) (event-bytes finalized tenant))
            (publish! bootstrap (str upload) (event-bytes upload tenant :reason "no-audio"))
            (is (= "failed" (await-status ds upload "failed")))
            (is (= "finished" (status-of ds finalized)) "a late failure never hides a final transcript")
            (finally
              (ig/halt! system))))
        (finally
          (.stop container))))))

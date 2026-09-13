(ns samuraibff.kafka.workflow-results-forwarding-test
  "Kafka + multi-instance integration test for workflow result forwarding.

  Mirrors the refined forwarding tests, but for workflow results.

  Scenario:
  - Start Kafka via Testcontainers
  - Start 2 BFF instances:
      * BFF-A: origin instance (HTTP + ws-registry)
      * BFF-B: non-origin instance running workflow-results consumer
  - Produce a workflow result JSON to Kafka with stream.source_uri pointing to
    BFF-A's ws/events URL.
  - Assert that BFF-B consumes it and forwards it to BFF-A via
    POST /internal/workflow-result.
  - Assert the workflow_result event arrives in BFF-A's ws-registry event stream.

  Notes:
  - We do not open real browser websockets; we tap ws-registry mult directly.
  - The result is streamed only for refined-trigger workflows." 
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer :all]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.config]
   [samuraibff.http.router]
   [samuraibff.http.server]
   [samuraibff.kafka.workflow-results-consumer]
   [samuraibff.ws.registry])
  (:import
   (java.util Properties UUID)
   (java.util.concurrent TimeUnit ExecutionException)
   (org.apache.kafka.clients.admin AdminClient NewTopic)
   (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
   (org.apache.kafka.common.errors TopicExistsException)
   (org.testcontainers.containers KafkaContainer)
   (org.testcontainers.utility DockerImageName)))

(def ^:private kafka-image
  "confluentinc/cp-kafka:7.6.1")

(defn- admin-client
  [bootstrap]
  (let [p (doto (Properties.)
            (.put "bootstrap.servers" bootstrap))]
    (AdminClient/create p)))

(defn- create-topics!
  [bootstrap topics]
  (with-open [admin (admin-client bootstrap)]
    (let [new-topics (mapv (fn [[topic partitions]]
                             (NewTopic. ^String topic (int partitions) (short 1)))
                           topics)
          result (.createTopics admin new-topics)]
      (try
        (-> result .all (.get 30 TimeUnit/SECONDS))
        (catch ExecutionException e
          (let [cause (.getCause e)]
            (when-not (instance? TopicExistsException cause)
              (throw e)))))))
  nil)

(defn- producer
  [bootstrap]
  (let [p (doto (Properties.)
            (.put "bootstrap.servers" bootstrap)
            (.put "acks" "all")
            (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
            (.put "value.serializer" "org.apache.kafka.common.serialization.ByteArraySerializer"))]
    (KafkaProducer. p)))

(defn- start-bff-a!
  [{:keys [port bootstrap]}]
  (let [cfg {:samuraibff/config {:env :test
                                 :features {:ce-mode? false}
                                 :http {:host "127.0.0.1" :port port}
                                 :kafka {:bootstrap-servers bootstrap
                                         :topics {:workflow-result "workflow.result"}
                                         :auto-offset-reset "earliest"}
                                 :bff {:origin-uri (str "http://127.0.0.1:" port)
                                       :callback-path-workflow-result "/internal/workflow-result"}}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc nil}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}]
    (ig/init cfg)))

(defn- start-bff-b!
  [{:keys [bootstrap]}]
  (let [cfg {:samuraibff/config {:env :test
                                 :features {:ce-mode? false}
                                 :http {:host "127.0.0.1" :port 0}
                                 :kafka {:bootstrap-servers bootstrap
                                         :consumer-group-id-workflow-results (str "samuraibff-wf-test-" (UUID/randomUUID))
                                         :auto-offset-reset "earliest"
                                         :poll-ms 100
                                         :topics {:workflow-result "workflow.result"}}
                                 :bff {:callback-path-workflow-result "/internal/workflow-result"}}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/workflow-results-consumer {:config (ig/ref :samuraibff/config)
                                                    :ws-registry (ig/ref :samuraibff/ws-registry)}}]
    (ig/init cfg)))

(deftest workflow-result-is-forwarded-to-origin-bff
  (let [topic "workflow.result"
        port-a 8111
        session-id (str (UUID/randomUUID))
        wf-id (str (UUID/randomUUID))
        wf-run-id (str (UUID/randomUUID))
        container (KafkaContainer. (DockerImageName/parse kafka-image))]
    (try
      (.start container)
      (let [bootstrap (.getBootstrapServers container)]
        (create-topics! bootstrap [[topic 1]])

        (let [sys-a (start-bff-a! {:port port-a :bootstrap bootstrap})
              sys-b (start-bff-b! {:bootstrap bootstrap})]
          (try
            ;; Create local session on A and tap events BEFORE producing.
            (samuraibff.ws.registry/ensure-session!
             (get sys-a :samuraibff/ws-registry)
             "tenant-a"
             session-id
             {:lang "en" :sample-rate 16000})

            (let [registry-a (get sys-a :samuraibff/ws-registry)
                  session-a (samuraibff.ws.registry/get-session registry-a "tenant-a" session-id)
                  out (async/chan 8)
                  payload-bytes
                  (-> {:tenant_id "tenant-a"
                       :session_id session-id
                       :workflow_id wf-id
                       :workflow_run_id wf-run-id
                       :created_at "2026-01-01T00:00:00Z"
                       :status "ok"
                       :trigger {:type "transcript.refined.segment"}
                       :render {:markdown "# Hello\n\nWorkflow output"}
                       :stream {:source_uri (str "ws://127.0.0.1:" port-a "/ws/events?session_id=" session-id)}}
                      (json/write-value-as-bytes (json/object-mapper {:encode-key-fn name})))]
              (samuraibff.ws.registry/tap-events! session-a out)
              (try
                (with-open [p (producer bootstrap)]
                  (let [record (ProducerRecord. topic session-id payload-bytes)]
                    (.send p record)
                    (.flush p)))

                ;; Wait for the workflow result event to appear on A.
                (let [[m ch] (async/alts!! [out (async/timeout 12000)] :priority true)]
                  (is (= out ch) "Expected workflow_result event before timeout")
                  (is (= "workflow_result" (:type m)))
                  (is (= session-id (:session_id m)))
                  (is (= wf-id (:workflow_id m)))
                  (is (= "ok" (:status m))))

                (finally
                  (samuraibff.ws.registry/untap-events! session-a out)
                  (async/close! out))))

            (finally
              (ig/halt! sys-b)
              (ig/halt! sys-a)))))
      (finally
        (try
          (.stop container)
          (catch Exception e
            (log/warn e "Failed stopping Kafka container")))))))

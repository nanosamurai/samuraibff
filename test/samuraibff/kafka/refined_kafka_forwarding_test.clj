(ns samuraibff.kafka.refined-kafka-forwarding-test
  "Kafka + multi-instance integration test for refined forwarding.

  Goal:
  - Start Kafka via Testcontainers
  - Start 2 BFF instances:
      * BFF-A: origin instance (has HTTP server + ws registry)
      * BFF-B: non-origin instance running the refined consumer
  - Produce a RefinedEvent to Kafka with bff_origin_uri pointing at BFF-A
  - Assert that BFF-B consumes it and forwards it to BFF-A via POST /internal/refined
  - Assert the refined event arrives in BFF-A's ws-registry event stream.

  Notes:
  - This test does not require rtservice (we don't open WS endpoints).
  - Uses `org.testcontainers/kafka` directly (no wrapper lib).
  - All waits have timeouts to avoid hanging CI." 
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log]
    [samuraibff.config]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.kafka.refined-consumer]
    [samuraibff.ws.registry])
  (:import
    (java.util Properties UUID)
    (java.util.concurrent TimeUnit ExecutionException)
    (org.apache.kafka.clients.admin AdminClient NewTopic)
    (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
    (org.apache.kafka.common.errors TopicExistsException)
    (org.testcontainers.containers KafkaContainer)
    (org.testcontainers.utility DockerImageName)
    (samuraibff.proto RefinedEvent)))

(def ^:private kafka-image
  ;; Pin a reasonably recent CP Kafka image.
  "confluentinc/cp-kafka:7.6.1")

(defn- admin-client
  "Create an AdminClient for the given bootstrap servers." 
  [bootstrap]
  (let [p (doto (Properties.)
            (.put "bootstrap.servers" bootstrap))]
    (AdminClient/create p)))

(defn- create-topics!
  "Create required topics for the test.

  Inputs:
  - bootstrap: string
  - topics: vector of [topic-name partitions]

  Returns: nil." 
  [bootstrap topics]
  (with-open [admin (admin-client bootstrap)]
    (let [new-topics (mapv (fn [[topic partitions]]
                             (NewTopic. ^String topic (int partitions) (short 1)))
                           topics)
          result (.createTopics admin new-topics)]
      (try
        (-> result .all (.get 30 TimeUnit/SECONDS))
        (catch ExecutionException e
          ;; tolerate "already exists" for local reruns
          (let [cause (.getCause e)]
            (when-not (instance? TopicExistsException cause)
              (throw e)))))))
  nil)

(defn- producer
  "Create a Kafka producer for (string key, bytes value)." 
  [bootstrap]
  (let [p (doto (Properties.)
            (.put "bootstrap.servers" bootstrap)
            (.put "acks" "all")
            (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
            (.put "value.serializer" "org.apache.kafka.common.serialization.ByteArraySerializer"))]
    (KafkaProducer. p)))

(defn- start-bff-a!
  "Start origin BFF instance (HTTP + ws-registry).

  Returns: Integrant system map." 
  [{:keys [port bootstrap]}]
  (let [cfg {:samuraibff/config {:env :test
                                 :http {:host "127.0.0.1" :port port}
                                 :kafka {:bootstrap-servers bootstrap
                                         :topics {:refined "transcripts.refined"}
                                         :auto-offset-reset "earliest"}
                                 :bff {:origin-uri (str "http://127.0.0.1:" port)
                                       :callback-path "/internal/refined"}}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc nil}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}]
    (ig/init cfg)))

(defn- start-bff-b!
  "Start non-origin BFF instance running only ws-registry + refined-consumer.

  Returns: Integrant system map." 
  [{:keys [bootstrap]}]
  (let [cfg {:samuraibff/config {:env :test
                                 :http {:host "127.0.0.1" :port 0}
                                 :kafka {:bootstrap-servers bootstrap
                                         ;; unique group-id so we always see the produced event
                                         :consumer-group-id (str "samuraibff-refined-test-" (UUID/randomUUID))
                                         :auto-offset-reset "earliest"
                                         :poll-ms 100
                                         :topics {:refined "transcripts.refined"}}
                                 :bff {:callback-path "/internal/refined"}}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/refined-consumer {:config (ig/ref :samuraibff/config)
                                           :ws-registry (ig/ref :samuraibff/ws-registry)}}]
    (ig/init cfg)))

(deftest refined-event-is-forwarded-to-origin-bff
  (let [topic "transcripts.refined"
        port-a 8101
        session-id (str (UUID/randomUUID))
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
                  out (async/chan 8)]
              (samuraibff.ws.registry/tap-events! session-a out)
              (try
                (with-open [p (producer bootstrap)]
                  (let [ev (-> (RefinedEvent/newBuilder)
                               (.setSessionId session-id)
                               (.setTenantId "tenant-a")
                               (.setStartS 0.0)
                               (.setEndS 1.0)
                               (.setText "hello from kafka")
                               (.setLang "en")
                               (.setBffOriginUri (str "http://127.0.0.1:" port-a))
                               (.build))
                        record (ProducerRecord. topic session-id (.toByteArray ev))]
                    (.send p record)
                    (.flush p)))

                ;; Wait for the forwarded refined event to appear on A.
                (let [[msg ch] (async/alts!! [out (async/timeout 12000)] :priority true)]
                  (is (= out ch) "Expected refined event before timeout")
                  (is (= "refined" (:type msg)))
                  (is (= session-id (:session_id msg)))
                  (is (= "en" (:lang msg)))
                  (is (= "hello from kafka" (:text msg))))

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

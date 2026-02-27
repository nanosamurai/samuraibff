(ns samuraibff.kafka.producer
  "Kafka producer component for publishing protobuf messages.

  This namespace currently focuses on publishing raw audio frames as
  `samuraibff.proto.AudioChunk` into Kafka topic `audio.raw`.

  The producer is used by the websocket ingestion pipeline:
  - browser -> /ws/audio (binary PCM16)
  - BFF builds an AudioChunk protobuf per frame
  - BFF publishes the message to Kafka (keyed by session_id)

  Integrant key:
  - `:samuraibff/kafka-producer`

  Config (under `:kafka` in the global config map):
  - :bootstrap-servers  string (host:port)
  - :client-id          string
  - :acks               string (e.g. all)
  - :compression-type   string (e.g. zstd)
  - :topics {:audio-raw string}

  Public API:
  - `send-audio-chunk!`"
  (:require
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log]
    [samuraibff.session-trace :as session-trace])
  (:import
    (java.util Properties)
    (org.apache.kafka.clients.producer KafkaProducer ProducerRecord)
    (samuraibff.proto AudioChunk)))

(defn- props
  "Build Java Properties for KafkaProducer.

  Inputs:
  - kafka-config: map with keys described in namespace docstring

  Returns: java.util.Properties"
  [kafka-config]
  (doto (Properties.)
    (.put "bootstrap.servers" (or (:bootstrap-servers kafka-config) "localhost:9092"))
    (.put "client.id" (or (:client-id kafka-config) "samuraibff"))
    (.put "acks" (or (:acks kafka-config) "all"))
    (.put "compression.type" (or (:compression-type kafka-config) "zstd"))

    ;; We publish (string key, bytes value)
    (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
    (.put "value.serializer" "org.apache.kafka.common.serialization.ByteArraySerializer")))

(defn send-audio-chunk!
  "Publish an AudioChunk protobuf message to Kafka.

  Inputs:
  - producer: component map returned by `:samuraibff/kafka-producer`
  - session-id: string
  - chunk: protobuf AudioChunk
  - opts: map with optional keys:
      - :tenant-id string (added as Kafka header `tenant_id`)

  Behavior:
  - sends asynchronously (does not block for ack)
  - logs a warning on callback error

  Returns: nil." 
  ([producer session-id chunk]
   (send-audio-chunk! producer session-id chunk {}))
  ([{:keys [^KafkaProducer producer topic-audio-raw]} session-id ^AudioChunk chunk {:keys [tenant-id]}]
   (when (and producer topic-audio-raw)
     ;; Make a deterministic trace context (trace_id == normalized session_id)
     ;; current for this send so the OTEL Java agent injects `traceparent`.
     (session-trace/with-session-trace session-id
       (let [record (doto (ProducerRecord. topic-audio-raw session-id (.toByteArray chunk))
                      (cond-> tenant-id
                        (-> (.headers) (.add "tenant_id" (.getBytes (str tenant-id) "UTF-8")))))]
         (.send
           producer
           record
           (reify org.apache.kafka.clients.producer.Callback
             (onCompletion [_ metadata exception]
               (when exception
                 (log/warn exception "Kafka send failed" {:topic topic-audio-raw
                                                          :session-id session-id
                                                          :partition (when metadata (.partition metadata))
                                                          :offset (when metadata (.offset metadata))}))))))))
   nil))

(defmethod ig/init-key :samuraibff/kafka-producer
  [_ {:keys [config]}]
  (let [kafka-cfg (:kafka config)
        topic-audio-raw (get-in kafka-cfg [:topics :audio-raw] "audio.raw")]
    (log/info "Starting Kafka producer" {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                                         :topic-audio-raw topic-audio-raw})
    (try
      {:producer (KafkaProducer. (props kafka-cfg))
       :topic-audio-raw topic-audio-raw
       :config config}
      (catch Exception e
        ;; HA behavior: treat Kafka as optional at process startup.
        ;; If Kafka is down, we still want the HTTP/WS server to come up.
        ;; Readiness should report Kafka unavailable.
        (log/error e "Kafka producer failed to start; continuing without Kafka" {:bootstrap-servers (:bootstrap-servers kafka-cfg)})
        {:producer nil
         :topic-audio-raw topic-audio-raw
         :config config}))))

(defmethod ig/halt-key! :samuraibff/kafka-producer
  [_ {:keys [^KafkaProducer producer]}]
  (when producer
    (try
      (.flush producer)
      (.close producer)
      (catch Exception e
        (log/warn e "Failed closing Kafka producer"))))
  nil)

(ns samuraibff.kafka.producer
  "Kafka producer component for publishing protobuf messages.

  This namespace currently focuses on publishing raw audio frames as
  `samuraibff.proto.AudioChunk` into Kafka topic `audio.raw`.

  Integrant key:
  - `:samuraibff/kafka-producer`

  Public API:
  - `send-audio-chunk!`

  Security / safety:
  - values are sent as byte[] and keys must be strings
  - trace context is propagated via `traceparent` Kafka header"
  (:require
   [integrant.core :as ig]
   [org.corfield.logging4j2 :as log]
   [samuraibff.otel.kafka :as otel.kafka]
   [samuraibff.session-trace :as session-trace])
  (:import
   (java.util Properties)
   (org.apache.kafka.clients.producer Callback KafkaProducer ProducerRecord)
   (samuraibff.proto AudioChunk)))

(defn- props
  "Build Java Properties for KafkaProducer.

  Inputs:
  - kafka-config: map (typically config under :kafka)

  Returns: java.util.Properties"
  [kafka-config]
  (doto (Properties.)
    (.put "bootstrap.servers" (or (:bootstrap-servers kafka-config) "localhost:9092"))
    (.put "client.id" (or (:client-id kafka-config) "samuraibff"))
    (.put "acks" (or (:acks kafka-config) "all"))
    (.put "compression.type" (or (:compression-type kafka-config) "zstd"))
    (.put "security.protocol" (or (:security-protocol kafka-config) "PLAINTEXT"))
    (.put "key.serializer" "org.apache.kafka.common.serialization.StringSerializer")
    (.put "value.serializer" "org.apache.kafka.common.serialization.ByteArraySerializer")))

(defn send-audio-chunk!
  "Publish an AudioChunk protobuf message to Kafka.

  Inputs:
  - producer: component map returned by `:samuraibff/kafka-producer`
  - session-id: string
  - chunk: protobuf AudioChunk
  - opts: map with optional keys:
      - :tenant-id string (Kafka header `tenant_id`)
      - :headers map of {header-name string -> header-value bytes}

  Returns: nil."
  ([producer session-id chunk]
   (send-audio-chunk! producer session-id chunk {}))
  ([{:keys [^KafkaProducer producer topic-audio-raw]}
    session-id
    ^AudioChunk chunk
    {:keys [tenant-id headers]}]
   (when (and producer topic-audio-raw)
     (session-trace/with-session-trace session-id
       (let [span (otel.kafka/start-audio-raw-produce-span!)
             scope (when span (.makeCurrent span))
             record (ProducerRecord. topic-audio-raw session-id (.toByteArray chunk))
             hdrs (.headers record)
             tp (otel.kafka/traceparent-for-audio-raw session-id span)]
         (when tenant-id
           (.add hdrs "tenant_id" (.getBytes (str tenant-id) "UTF-8")))
         (when tp
           (.add hdrs "traceparent" (.getBytes ^String tp "UTF-8")))
         (doseq [[k v] (or headers {})]
           (when (and (string? k) (bytes? v))
             (.add hdrs k ^bytes v)))
         (try
           (.send
            producer
            record
            (reify Callback
              (onCompletion [_ metadata exception]
                (otel.kafka/end-produce-span! span metadata exception)
                (when exception
                  (log/warn exception "Kafka send failed" {:topic topic-audio-raw
                                                           :session-id session-id})))))
           (catch Exception e
             (otel.kafka/end-produce-span! span nil e)
             (throw e))
           (finally
             (when scope
               (try (.close scope) (catch Exception _ nil))))))))
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

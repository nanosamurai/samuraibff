(ns samuraibff.kafka.refined-consumer
  "Kafka consumer for refined transcript events.

  Every samuraibff instance runs a consumer in the same group.
  Because Kafka partitions can route a refined message to *any* instance,
  this consumer must forward refined events to the originating BFF instance.

  Routing mechanism:
  - WhisperX worker copies `AudioChunk.bff_origin_uri` -> `RefinedEvent.bff_origin_uri`.
  - Consumer checks whether the session exists locally (ws-registry).
    - if yes: publish refined event to local WS session
    - if no: POST protobuf bytes to `<bff_origin_uri>/internal/refined`

  Integrant key:
  - `:samuraibff/refined-consumer`

  Config (under `:kafka` in global config map):
  - :bootstrap-servers
  - :topics {:refined 'transcripts.refined'}
  - :consumer-group-id (optional, default 'samuraibff-refined')
  - :poll-ms (optional, default 250)

  Public API:
  - none (component runs in background thread)"
  (:require
    [clojure.string :as str]
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log]
    [org.httpkit.client :as http]
    [samuraibff.ws.registry :as ws.registry])
  (:import
    (java.time Duration)
    (java.util Collections Properties)
    (org.apache.kafka.clients.consumer ConsumerRecord KafkaConsumer)
    (samuraibff.proto RefinedEvent)))

(defn- consumer-props
  "Build Kafka consumer properties.

  Inputs:
  - {:keys [bootstrap-servers consumer-group-id auto-offset-reset]}

  Returns: java.util.Properties" 
  [{:keys [bootstrap-servers consumer-group-id auto-offset-reset]}]
  (doto (Properties.)
    (.put "bootstrap.servers" (or bootstrap-servers "localhost:9092"))
    (.put "group.id" (or consumer-group-id "samuraibff-refined"))

    ;; We consume key as string, value as bytes.
    (.put "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer")
    (.put "value.deserializer" "org.apache.kafka.common.serialization.ByteArrayDeserializer")

    ;; Prefer explicit commits after processing.
    (.put "enable.auto.commit" "false")
    (.put "auto.offset.reset" (or auto-offset-reset "latest"))))

(defn- normalize-base-uri
  "Normalize a base URI by removing a trailing slash.

  Returns: string or nil." 
  [s]
  (let [s (when (and s (not (str/blank? (str s)))) (str s))]
    (when s
      (str/replace s #"/+$" ""))))

(defn- callback-url
  "Build callback URL for the origin BFF.

  Inputs:
  - origin-uri: base URI string
  - path: string (must start with /)

  Returns: string." 
  [origin-uri path]
  (str (normalize-base-uri origin-uri) path))

(defn- forward-to-origin!
  "Forward a RefinedEvent protobuf bytes to the origin BFF via HTTP.

  Returns: boolean (true if HTTP status is 2xx)." 
  [origin-uri callback-path bytes]
  (let [url (callback-url origin-uri callback-path)
        {:keys [status error]} @(http/post url
                                           {:headers {"content-type" "application/x-protobuf"}
                                            :body bytes
                                            :timeout 2000})]
    (cond
      error (do
              (log/warn error "Refined forward failed" {:url url})
              false)
      (and status (<= 200 status 299)) true
      :else (do
              (log/debug "Refined forward non-2xx" {:url url :status status})
              false))))

(defn- process-record!
  "Process a single Kafka refined record.

  Inputs:
  - ws-registry: ws registry component
  - callback-path: string (e.g. '/internal/refined')
  - ^ConsumerRecord rec

  Returns: boolean delivered?" 
  [ws-registry callback-path ^ConsumerRecord rec]
  (let [^bytes bytes (.value rec)
        ^RefinedEvent ev (RefinedEvent/parseFrom bytes)
        session-id (.getSessionId ev)
        origin-uri (normalize-base-uri (.getBffOriginUri ev))]
    (if (ws.registry/publish-refined-proto! ws-registry ev)
      true
      (if origin-uri
        (do
          (forward-to-origin! origin-uri callback-path bytes)
          false)
        (do
          (log/debug "Refined event missing bff_origin_uri; dropping" {:session-id session-id})
          false)))))

(defn- run-loop!
  "Main polling loop for refined consumer.

  Side effects:
  - polls Kafka
  - processes messages
  - commits offsets after processing batch" 
  [{:keys [^KafkaConsumer consumer running?* ws-registry callback-path poll-ms]}]
  (log/info "Refined consumer loop started")
  (try
    (while @running?*
      (let [records (.poll consumer (Duration/ofMillis (long poll-ms)))]
        (when (pos? (.count records))
          (doseq [rec records]
            (try
              (process-record! ws-registry callback-path rec)
              (catch Exception e
                (log/warn e "Failed processing refined record" {:topic (.topic ^ConsumerRecord rec)
                                                               :partition (.partition ^ConsumerRecord rec)
                                                               :offset (.offset ^ConsumerRecord rec)}))))
          (try
            (.commitSync consumer)
            (catch org.apache.kafka.common.errors.WakeupException _
              ;; Normal during shutdown.
              nil)
            (catch Exception e
              (log/warn e "Kafka commitSync failed"))))))
    (catch Exception e
      (log/error e "Refined consumer loop crashed"))
    (finally
      (try
        (.close consumer)
        (catch Exception e
          (log/warn e "Failed closing refined Kafka consumer")))
      (log/info "Refined consumer loop stopped"))))

(defmethod ig/init-key :samuraibff/refined-consumer
  [_ {:keys [config ws-registry]}]
  (let [kafka-cfg (:kafka config)
        topic (get-in kafka-cfg [:topics :refined] "transcripts.refined")
        callback-path (or (get-in config [:bff :callback-path]) "/internal/refined")
        poll-ms (or (get-in kafka-cfg [:poll-ms]) 250)
        consumer (KafkaConsumer. (consumer-props {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                                                 :consumer-group-id (or (:consumer-group-id kafka-cfg)
                                                                        "samuraibff-refined")
                                                 :auto-offset-reset (or (:auto-offset-reset kafka-cfg)
                                                                        "latest")}))
        _ (.subscribe consumer (Collections/singletonList topic))
        running?* (atom true)
        thread (doto (Thread. #(run-loop! {:consumer consumer
                                           :running?* running?*
                                           :ws-registry ws-registry
                                           :callback-path callback-path
                                           :poll-ms poll-ms})
                              "samuraibff-refined-consumer")
                 (.setDaemon true)
                 (.start))]
    (log/info "Started refined Kafka consumer" {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                                               :topic topic
                                               :group (or (:consumer-group-id kafka-cfg) "samuraibff-refined")
                                               :callback-path callback-path})
    {:thread thread
     :running?* running?*
     :consumer consumer
     :ws-registry ws-registry
     :config config}))

(defmethod ig/halt-key! :samuraibff/refined-consumer
  [_ {:keys [running?* ^Thread thread ^KafkaConsumer consumer]}]
  (when running?*
    (reset! running?* false))
  (when consumer
    (try
      (.wakeup consumer)
      (catch Exception _ nil)))
  (when thread
    (try
      (.join thread 2000)
      (catch Exception _ nil)))
  nil)

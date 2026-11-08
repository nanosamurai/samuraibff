(ns samuraibff.kafka.upload-failed-consumer
  "Kafka consumer for uploaded files that could not be converted
  (topic `recordings.upload_failed`).

  recorder_worker (xamurai) publishes one JSON event, keyed by session ID, when
  it permanently rejects an uploaded file:

    {\"schema_version\": 1, \"session_id\": \"<uuid>\", \"tenant_id\": \"<uuid>\",
     \"reason\": \"decode-failed\", \"created_at\": \"<ISO-8601>\"}

  The consumer sets the session to `failed` with
  `samuraibff.db.sessions/fail-upload-session!`. Only a finished upload of the
  event's tenant without a final transcript changes, so a late or repeated
  event never hides a transcript and never touches a live session.

  Delivery:
  - All BFF replicas share one consumer group, so one replica handles each
    message. The update is idempotent, so a redelivery is harmless.
  - The offset is committed after each message.
  - A database error rewinds to the failed message and retries it after a
    backoff. An invalid event is logged without its content and skipped.
  - A new consumer group starts at the earliest retained event, so failures
    published before the first BFF start are not lost.

  Integrant key: `:samuraibff/upload-failed-consumer` with :config and :db.
  The consumer does not start when file uploads are disabled (see
  `samuraibff.features/uploads-max-bytes`) or there is no datasource.

  Config (under `:kafka`):
  - :bootstrap-servers, :security-protocol
  - :topics {:recordings-upload-failed \"recordings.upload_failed\"}
  - :consumer-group-id-upload-failed (default \"samuraibff-upload-failed\")
  - :poll-ms (default 250), :restart-backoff-ms (default 2000)

  Security: the event holds IDs and a reason code only. Logs contain the IDs
  and the reason when it is a short lower-case code."
  (:require
   [integrant.core :as ig]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.features :as features])
  (:import
   (java.time Duration)
   (java.util Collections Properties UUID)
   (org.apache.kafka.clients.consumer Consumer ConsumerRecord KafkaConsumer OffsetAndMetadata)
   (org.apache.kafka.common TopicPartition)))

(def ^:private default-topic "recordings.upload_failed")

(def ^:private default-group-id "samuraibff-upload-failed")

(def ^:private default-backoff-ms 2000)

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn- consumer-props
  "Build Kafka consumer properties.

  Inputs: {:keys [bootstrap-servers consumer-group-id security-protocol]}.
  Returns: java.util.Properties with manual commits and earliest offset reset."
  ^Properties [{:keys [bootstrap-servers consumer-group-id security-protocol]}]
  (doto (Properties.)
    (.put "bootstrap.servers" (or bootstrap-servers "localhost:9092"))
    (.put "group.id" (or consumer-group-id default-group-id))
    (.put "security.protocol" (or security-protocol "PLAINTEXT"))
    (.put "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer")
    (.put "value.deserializer" "org.apache.kafka.common.serialization.ByteArrayDeserializer")
    (.put "enable.auto.commit" "false")
    (.put "auto.offset.reset" "earliest")))

(defn- parse-uuid-str
  "Return the UUID of a canonical UUID string, or nil."
  [s]
  (when (string? s)
    (try
      (let [id (UUID/fromString s)]
        (when (= (str id) (.toLowerCase ^String s)) id))
      (catch IllegalArgumentException _ nil))))

(defn parse-event
  "Parse and validate a `recordings.upload_failed` event.

  Inputs: JSON bytes.
  Returns: {:session-id UUID :tenant-id UUID :reason string} or nil when the
  bytes are not a valid version 1 event. A reason that is not a short
  lower-case code becomes \"unknown\". Never throws."
  [^bytes value]
  (try
    (let [{:keys [schema_version session_id tenant_id reason]} (json/read-value value json-mapper)
          session-id (parse-uuid-str session_id)
          tenant-id (parse-uuid-str tenant_id)]
      (when (and (= 1 schema_version) session-id tenant-id)
        {:session-id session-id
         :tenant-id tenant-id
         :reason (if (and (string? reason) (re-matches #"[a-z0-9][a-z0-9-]{0,63}" reason))
                   reason
                   "unknown")}))
    (catch Exception _ nil)))

(defn handle-record!
  "Apply one consumed record to the database.

  Inputs: DataSource, ConsumerRecord with JSON bytes.
  Returns: :failed when the session was set to failed, :ignored when no
  finished upload session without a final transcript matched, :invalid for an
  invalid event. Throws on database errors, so the caller retries."
  [ds ^ConsumerRecord rec]
  (if-let [{:keys [session-id tenant-id reason]} (parse-event (.value rec))]
    (let [log-ctx {:session_id (str session-id) :tenant_id (str tenant-id) :reason reason}]
      (if (:updated? (db.sessions/fail-upload-session! ds tenant-id session-id))
        (do (log/info "Uploaded file could not be converted; session marked failed" log-ctx)
            :failed)
        (do (log/info "Upload failure ignored; no finished upload session without a final transcript"
                      log-ctx)
            :ignored)))
    (do (log/warn "Skipping invalid upload failure event" {:topic (.topic rec)
                                                           :partition (.partition rec)
                                                           :offset (.offset rec)})
        :invalid)))

(defn- record-partition
  "Return the TopicPartition of a record."
  ^TopicPartition [^ConsumerRecord rec]
  (TopicPartition. (.topic rec) (.partition rec)))

(defn- commit-record!
  "Commit the offset after `rec` synchronously."
  [^Consumer consumer ^ConsumerRecord rec]
  (.commitSync consumer (Collections/singletonMap (record-partition rec)
                                                  (OffsetAndMetadata. (inc (.offset rec))))))

(defn- rewind!
  "Seek every partition in `records` back to its first record, so the next
  poll returns those records again. `records` is in consumption order."
  [^Consumer consumer records]
  (let [first-offsets (reduce (fn [acc ^ConsumerRecord rec]
                                (let [tp (record-partition rec)]
                                  (if (contains? acc tp) acc (assoc acc tp (.offset rec)))))
                              {}
                              records)]
    (doseq [[^TopicPartition tp offset] first-offsets]
      (.seek consumer tp (long offset)))))

(defn process-batch!
  "Handle the records of one poll in order and commit each handled record.

  Inputs: Consumer, DataSource, sequence of ConsumerRecord.
  Returns: true when every record was handled; false when a record failed.
  Then that record and the rest of the batch are rewound for the next poll."
  [^Consumer consumer ds records]
  (loop [remaining (seq records)]
    (if-let [rec (first remaining)]
      (if (try
            (handle-record! ds rec)
            true
            (catch Exception e
              (log/warn e "Failed to apply upload failure event; retrying"
                        {:topic (.topic ^ConsumerRecord rec)
                         :partition (.partition ^ConsumerRecord rec)
                         :offset (.offset ^ConsumerRecord rec)})
              false))
        (do (commit-record! consumer rec)
            (recur (next remaining)))
        (do (rewind! consumer remaining)
            false))
      true)))

(defn- sleep-backoff!
  "Sleep up to `backoff-ms` while `running?*` is true; stop the loop when
  interrupted."
  [running?* backoff-ms]
  (try
    (loop [left (long backoff-ms)]
      (when (and @running?* (pos? left))
        (Thread/sleep (min left 100))
        (recur (- left 100))))
    (catch InterruptedException _
      (reset! running?* false))))

(defn- run-loop!
  "Poll and handle records until `running?*` is false. Closes the consumer."
  [{:keys [^Consumer consumer ds running?* poll-ms backoff-ms]}]
  (log/info "Upload failure consumer loop started")
  (try
    (while @running?*
      (let [records (.poll consumer (Duration/ofMillis (long poll-ms)))]
        (when (and (pos? (.count records))
                   (not (process-batch! consumer ds records)))
          (sleep-backoff! running?* backoff-ms))))
    (finally
      (try
        (.close consumer)
        (catch Exception e
          (log/warn e "Failed closing upload failure Kafka consumer")))
      (log/info "Upload failure consumer loop stopped"))))

(defn- run-supervised!
  "Run the consumer loop and restart it after a backoff when Kafka fails.

  Inputs: {:keys [config ds running?*]}. Returns nil."
  [{:keys [config ds running?*]}]
  (let [kafka-cfg (:kafka config)
        topic (get-in kafka-cfg [:topics :recordings-upload-failed] default-topic)
        group-id (or (:consumer-group-id-upload-failed kafka-cfg) default-group-id)
        poll-ms (or (:poll-ms kafka-cfg) 250)
        backoff-ms (or (:restart-backoff-ms kafka-cfg) default-backoff-ms)]
    (while @running?*
      (try
        (log/info "Starting upload failure Kafka consumer" {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                                                            :topic topic
                                                            :group group-id})
        (let [consumer (KafkaConsumer. (consumer-props {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                                                        :consumer-group-id group-id
                                                        :security-protocol (:security-protocol kafka-cfg)}))]
          (.subscribe consumer (Collections/singletonList topic))
          (run-loop! {:consumer consumer
                      :ds ds
                      :running?* running?*
                      :poll-ms poll-ms
                      :backoff-ms backoff-ms}))
        (catch Exception e
          (when @running?*
            (log/error e "Upload failure consumer crashed or failed to start; will retry"
                       {:topic topic :group group-id :backoff-ms backoff-ms})
            (sleep-backoff! running?* backoff-ms)))))))

(defmethod ig/init-key :samuraibff/upload-failed-consumer
  [_ {:keys [config db]}]
  (let [ds (:ds db)]
    (if-not (and ds (pos? (features/uploads-max-bytes config)))
      (do (log/info "Upload failure Kafka consumer disabled (file uploads off or no database)")
          {:disabled? true})
      (let [running?* (atom true)
            thread (doto (Thread. #(run-supervised! {:config config :ds ds :running?* running?*})
                                  "samuraibff-upload-failed-consumer")
                     (.setDaemon true)
                     (.start))]
        {:thread thread
         :running?* running?*}))))

(defmethod ig/halt-key! :samuraibff/upload-failed-consumer
  [_ {:keys [running?* ^Thread thread]}]
  (when running?*
    (reset! running?* false))
  (when thread
    (try
      (.join thread (+ default-backoff-ms 1000))
      (when (.isAlive thread)
        (.interrupt thread)
        (.join thread 2000))
      (catch Exception _ nil)))
  nil)

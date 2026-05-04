(ns samuraibff.kafka.workflow-results-consumer
  "Kafka consumer for workflow result events (topic `workflow.result`).

  Every samuraibff instance runs a consumer in the same group.
  Because Kafka partitions can route a workflow.result message to *any* instance,
  this consumer must forward workflow results to the originating BFF instance.

  Origin routing mechanism:
  - workflow-runner includes `stream.source_uri` in result payload (JSON).
  - webhook-router (producer side) is expected to set this URI to a stable, node-local
    websocket URL (host pointing at the origin BFF instance).
  - We derive origin HTTP base URI from this websocket URI by:
      ws://host[:port]/...  -> http://host[:port]
      wss://host[:port]/... -> https://host[:port]
  - When the local instance does not hold the websocket session in-memory,
    we POST the workflow result JSON to `<origin>/internal/workflow-result`.

  Integrant key:
  - `:samuraibff/workflow-results-consumer`

  Config (under `:kafka` in global config map):
  - :bootstrap-servers
  - :topics {:workflow-result workflow.result}
  - :consumer-group-id-workflow-results (optional, default 'samuraibff-workflow-results')
  - :poll-ms (optional, default 250)

  Security:
  - We do not log markdown bodies.
  - Forwarding is done over HTTP to internal callbacks only."
  (:require
   [clojure.string :as str]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [org.httpkit.client :as http]
   [samuraibff.ws.registry :as ws.registry])
  (:import
   (java.net URI)
   (java.time Duration)
   (java.util Collections Properties)
   (org.apache.kafka.clients.consumer ConsumerRecord KafkaConsumer)))

(def ^:private default-restart-backoff-ms
  2000)

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn- consumer-props
  [{:keys [bootstrap-servers consumer-group-id auto-offset-reset security-protocol]}]
  (doto (Properties.)
    (.put "bootstrap.servers" (or bootstrap-servers "localhost:9092"))
    (.put "group.id" (or consumer-group-id "samuraibff-workflow-results"))
    (.put "security.protocol" (or security-protocol "PLAINTEXT"))
    (.put "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer")
    (.put "value.deserializer" "org.apache.kafka.common.serialization.ByteArrayDeserializer")
    (.put "enable.auto.commit" "false")
    (.put "auto.offset.reset" (or auto-offset-reset "latest"))))

(defn- normalize-base-uri
  "Normalize a base URI by removing a trailing slash."
  [s]
  (let [s (some-> s str str/trim not-empty)]
    (when s
      (str/replace s #"/+$" ""))))

(defn- ws-uri->http-origin
  "Derive origin HTTP base URL from a websocket URL string.

  Inputs:
  - source-uri: string (expected ws://... or wss://...)

  Returns:
  - string base URI (http/https) or nil."
  [source-uri]
  (try
    (let [^URI u (URI/create (str source-uri))
          scheme (.getScheme u)
          host (.getHost u)
          port (.getPort u)
          scheme' (case (some-> scheme str/lower-case)
                    "wss" "https"
                    "ws" "http"
                    nil)]
      (when (and scheme' (seq (str host)))
        (normalize-base-uri
         (if (pos? (int port))
           (str scheme' "://" host ":" port)
           (str scheme' "://" host)))))
    (catch Exception _
      nil)))

(defn- callback-url
  [origin-uri callback-path]
  (str (normalize-base-uri origin-uri) callback-path))

(defn- forward-to-origin!
  "Forward workflow result JSON bytes to the origin BFF via HTTP.

  Returns: boolean (true if status is 2xx)."
  [origin-uri callback-path bytes]
  (let [url (callback-url origin-uri callback-path)
        {:keys [status error]} @(http/post url
                                           {:headers {"content-type" "application/json"}
                                            :body bytes
                                            :timeout 2500})]
    (cond
      error (do
              (log/warn error "Workflow result forward failed" {:url url})
              false)
      (and status (<= 200 status 299)) true
      :else (do
              (log/debug "Workflow result forward non-2xx" {:url url :status status})
              false))))

(defn- parse-workflow-result
  "Parse JSON bytes into a normalized map for ws.registry publishing.

  Returns a map with keys expected by `ws.registry/publish-workflow-result!`."
  [^bytes bytes]
  (let [m (json/read-value bytes json-mapper)
        trigger-type (or (get-in m [:trigger :type]) (:trigger_type m))
        render-md (or (get-in m [:render :markdown]) (:render_markdown m))]
    {:tenant_id (:tenant_id m)
     :session_id (:session_id m)
     :workflow_id (:workflow_id m)
     ;; optional
     :workflow_name (:workflow_name m)
     :workflow_run_id (:workflow_run_id m)
     :created_at (some-> (:created_at m) str)
     :trigger_type (some-> trigger-type str)
     :status (:status m)
     :render_markdown render-md
     :error_code (:error_code m)
     :error_detail (:error_detail m)
     :stream_source_uri (get-in m [:stream :source_uri])}))

(defn- should-stream-to-ws?
  "Return true when a workflow result should be streamed to /ws/events.

  We only stream results for refined-trigger workflows. (Final-trigger workflow
  results are not relevant for the live page.)"
  [payload]
  (let [t (some-> (:trigger_type payload) str)]
    (boolean
     (and (seq t)
          (str/starts-with? t "transcript.refined")))))

(defn- process-record!
  [{:keys [ws-registry callback-path]} ^ConsumerRecord rec]
  (let [^bytes bytes (.value rec)
        payload (parse-workflow-result bytes)
        session-id (:session_id payload)
        workflow-id (:workflow_id payload)
        source-uri (:stream_source_uri payload)
        origin-uri (ws-uri->http-origin source-uri)]
    (cond
      (not (should-stream-to-ws? payload))
      (do
        (log/debug "Skipping workflow result (not refined trigger)" {:session-id (str session-id)
                                                                     :workflow-id (str workflow-id)
                                                                     :trigger-type (:trigger_type payload)})
        false)

      (ws.registry/publish-workflow-result! ws-registry payload)
      true

      origin-uri
      (do
        (forward-to-origin! origin-uri callback-path bytes)
        false)

      :else
      (do
        (log/debug "Workflow result missing stream.source_uri; dropping" {:session-id (str session-id)
                                                                          :workflow-id (str workflow-id)})
        false))))

(defn- run-loop!
  [{:keys [^KafkaConsumer consumer running?* ws-registry callback-path poll-ms]}]
  (log/info "Workflow results consumer loop started")
  (try
    (while @running?*
      (let [records (.poll consumer (Duration/ofMillis (long poll-ms)))]
        (when (pos? (.count records))
          (doseq [rec records]
            (try
              (process-record! {:ws-registry ws-registry
                                :callback-path callback-path}
                               rec)
              (catch InterruptedException _ nil)
              (catch Exception e
                (log/warn e "Failed processing workflow result record" {:topic (.topic ^ConsumerRecord rec)
                                                                        :partition (.partition ^ConsumerRecord rec)
                                                                        :offset (.offset ^ConsumerRecord rec)}))))
          (try
            (.commitSync consumer)
            (catch org.apache.kafka.common.errors.WakeupException _ nil)
            (catch Exception e
              (log/warn e "Kafka commitSync failed (workflow results consumer)"))))))
    (catch Exception e
      (log/error e "Workflow results consumer loop crashed"))
    (finally
      (try
        (.close consumer)
        (catch Exception e
          (log/warn e "Failed closing workflow results Kafka consumer")))
      (log/info "Workflow results consumer loop stopped"))))

(defn- start-consumer
  [kafka-cfg topic group-id]
  (let [props (consumer-props {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                               :consumer-group-id group-id
                               :security-protocol (:security-protocol kafka-cfg)
                               :auto-offset-reset (or (:auto-offset-reset kafka-cfg) "latest")})
        consumer (KafkaConsumer. props)]
    (.subscribe consumer (Collections/singletonList topic))
    consumer))

(defn- run-supervised!
  [{:keys [config ws-registry running?*]}]
  (let [kafka-cfg (:kafka config)
        topic (get-in kafka-cfg [:topics :workflow-result] "workflow.result")
        callback-path (or (get-in config [:bff :callback-path-workflow-result])
                          (get-in config [:bff :callback-path])
                          "/internal/workflow-result")
        poll-ms (or (get-in kafka-cfg [:poll-ms]) 250)
        group-id (or (get-in kafka-cfg [:consumer-group-id-workflow-results])
                     "samuraibff-workflow-results")
        backoff-ms (or (get-in kafka-cfg [:restart-backoff-ms]) default-restart-backoff-ms)]
    (while @running?*
      (try
        (log/info "Starting workflow results Kafka consumer" {:bootstrap-servers (:bootstrap-servers kafka-cfg)
                                                              :topic topic
                                                              :group group-id
                                                              :callback-path callback-path})
        (let [consumer (start-consumer kafka-cfg topic group-id)]
          (run-loop! {:consumer consumer
                      :running?* running?*
                      :ws-registry ws-registry
                      :callback-path callback-path
                      :poll-ms poll-ms}))
        (catch Exception e
          (when @running?*
            (log/error e "Workflow results consumer crashed / failed to start; will retry" {:topic topic}
                       :group group-id
                       :backoff-ms backoff-ms)
            (try
              (Thread/sleep (long backoff-ms))
              (catch InterruptedException _
                (reset! running?* false)))))))))

(defmethod ig/init-key :samuraibff/workflow-results-consumer
  [_ {:keys [config ws-registry]}]
  (let [running?* (atom true)
        thread (doto (Thread. #(run-supervised! {:config config
                                                 :ws-registry ws-registry
                                                 :running?* running?*})
                              "samuraibff-workflow-results-consumer")
                 (.setDaemon true)
                 (.start))]
    {:thread thread
     :running?* running?*
     :ws-registry ws-registry
     :config config}))

(defmethod ig/halt-key! :samuraibff/workflow-results-consumer
  [_ {:keys [running?* ^Thread thread]}]
  (when running?*
    (reset! running?* false))
  (when thread
    (try
      (.interrupt thread)
      (catch Exception _ nil)))
  (when thread
    (try
      (.join thread 2000)
      (catch Exception _ nil)))
  nil)

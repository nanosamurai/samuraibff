(ns samuraibff.http.internal
  "Internal (non-UI) HTTP endpoints.

  Currently implemented:
  - `POST /internal/refined` – used by other BFF instances to forward
    Kafka-consumed `RefinedEvent` messages to the originating BFF.

  The endpoint accepts a protobuf-serialized `samuraibff.proto.RefinedEvent`
  in the request body (`application/x-protobuf`).

  Important semantics:
  - This endpoint MUST NOT create sessions.
  - It only delivers the refined event if the session exists in the local
    `samuraibff.ws.registry` (meaning the UI/WS is connected to this BFF).

  Public API:
  - `refined-callback-handler`"
  (:require
   [clojure.java.io :as io]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.ws.registry :as ws.registry])
  (:import
   (java.io ByteArrayOutputStream)
   (samuraibff.proto RefinedEvent)))

(def ^:private json-mapper
  "JSON mapper used for decoding internal workflow result callback payloads.

  We must keywordize keys (including nested keys) so we can reliably access
  `:trigger {:type ...}` and `:render {:markdown ...}`." 
  (json/object-mapper {:decode-key-fn keyword}))

(defn- json-response
  "Return a response with a data body.

  We intentionally do not JSON-encode the body here. Muuntaja (installed in the
  HTTP router) will encode the response to JSON.

  Inputs:
  - status int
  - body map

  Returns: Ring response map."
  [status body]
  {:status status
   :body body})

(defn- read-all-bytes
  "Read an InputStream fully into a byte-array."
  [in]
  (with-open [^java.io.InputStream in in
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn refined-callback-handler
  "Create a Ring handler for `POST /internal/refined`.

  Dependencies:
  - `:ws-registry` (required)

  Request:
  - body: protobuf bytes of `samuraibff.proto.RefinedEvent`

  Response:
  - 200 JSON when delivered
  - 404 JSON when session not found locally
  - 400 JSON on parse failure

  Returns: Ring handler fn."
  [{:keys [ws-registry]}]
  (fn [{:keys [body] :as _request}]
    (try
      (let [bytes (if (bytes? body) body (read-all-bytes body))
            ^RefinedEvent ev (RefinedEvent/parseFrom ^bytes bytes)
            session-id (.getSessionId ev)
            delivered? (ws.registry/publish-refined-proto! ws-registry ev)]
        (if delivered?
          (json-response 200 {:ok true
                              :session_id session-id})
          (do
            (log/debug "Refined callback: session not found locally" {:session-id session-id})
            (json-response 404 {:ok false
                                :session_id session-id
                                :message "session-not-found"}))))
      (catch Exception e
        (log/warn e "Failed to process refined callback")
        (json-response 400 {:ok false
                            :message "invalid-protobuf"})))))

(def ^:private max-internal-workflow-result-bytes
  "Maximum size of a workflow result JSON payload accepted by /internal/workflow-result.

  This is a safety valve; workflow results can include large markdown bodies."
  (* 1024 1024 2))

(defn- read-all-bytes-bounded
  "Read InputStream into byte[] with an upper bound.

  Throws:
  - ex-info {:type :samuraibff.http/payload-too-large} when limit exceeded."
  [in max-bytes]
  (with-open [^java.io.InputStream in in
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toByteArray out)
            (> (+ total n) (long max-bytes))
            (throw (ex-info "payload-too-large" {:type :samuraibff.http/payload-too-large
                                                 :max-bytes max-bytes
                                                 :read-bytes (+ total n)}))
            :else
            (do
              (.write out buf 0 n)
              (recur (+ total n)))))))))

(defn workflow-result-callback-handler
  "Create a Ring handler for `POST /internal/workflow-result`.

  Purpose:
  - BFF-to-BFF forwarding when Kafka workflow.result is consumed by a non-origin instance.

  Dependencies:
  - `:ws-registry` (required)

  Request:
  - body: JSON payload (workflow-runner WorkflowResult shape, best-effort)

  Response:
  - 200 JSON when delivered
  - 404 JSON when session not found locally
  - 400 JSON on parse/validation failure
  - 413 JSON when payload exceeds size limit

  Returns: Ring handler fn."
  [{:keys [ws-registry]}]
  (fn [{:keys [body body-params] :as _request}]
    (try
      (let [payload
            (cond
              ;; When Muuntaja is installed in the router and content-type is JSON,
              ;; Ring may hand us an already-decoded map.
              (map? body-params) body-params

              (map? body) body

              (bytes? body)
              (try
                (json/read-value ^bytes body json-mapper)
                (catch Exception e
                  (throw (ex-info "invalid-json" {:type :samuraibff.http/invalid-json} e))))

              (string? body)
              (try
                (json/read-value (.getBytes ^String body "UTF-8") json-mapper)
                (catch Exception e
                  (throw (ex-info "invalid-json" {:type :samuraibff.http/invalid-json} e))))

              :else
              (let [bytes (read-all-bytes-bounded body max-internal-workflow-result-bytes)]
                (try
                  (json/read-value bytes json-mapper)
                  (catch Exception e
                    (throw (ex-info "invalid-json" {:type :samuraibff.http/invalid-json} e))))) )
            ;; accept both snake_case (runner) and keyword keys; normalize to keywords
            m (if (map? payload)
                (into {}
                      (map (fn [[k v]]
                             (let [k (if (keyword? k) k (keyword (str k)))]
                               [k v])))
                      payload)
                {})
            ;; Extract nested trigger/render to match ws.registry payload expectations.
            trigger-type (or (get-in m [:trigger :type]) (get m :trigger_type))
            render-md (or (get-in m [:render :markdown]) (get m :render_markdown))
            session-id (or (get m :session_id) (get m :session-id))
            delivered?
            (ws.registry/publish-workflow-result!
             ws-registry
             {:tenant_id (or (get m :tenant_id) (get m :tenant-id))
              :session_id session-id
              :workflow_id (or (get m :workflow_id) (get m :workflow-id))
              :workflow_name (get m :workflow_name)
              :workflow_run_id (or (get m :workflow_run_id) (get m :workflow-run-id))
              :created_at (some-> (or (get m :created_at) (get m :created-at)) str)
              :trigger_type (some-> trigger-type str)
              :status (get m :status)
              :render_markdown render-md
              :error_code (or (get m :error_code) (get m :error-code))
              :error_detail (or (get m :error_detail) (get m :error-detail))})]
        (if delivered?
          (json-response 200 {:ok true
                              :session_id (str session-id)})
          (do
            (log/debug "Workflow result callback: session not found locally" {:session-id (str session-id)})
            (json-response 404 {:ok false
                                :session_id (str session-id)
                                :message "session-not-found"}))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/payload-too-large
            (json-response 413 {:ok false :message "payload-too-large"})

            :samuraibff.http/invalid-json
            (json-response 400 {:ok false :message "invalid-json"})

            (do
              (log/warn e "Failed to process workflow result callback")
              (json-response 400 {:ok false :message "invalid-payload"})))))
      (catch Exception e
        (log/warn e "Failed to process workflow result callback")
        (json-response 400 {:ok false :message "invalid-payload"})))))

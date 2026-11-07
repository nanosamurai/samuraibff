(ns samuraibff.http.workflow-runs
  "HTTP handler for on-demand workflow runs on a finished session.

  Endpoint (under /api, auth required):
  - POST /api/recordings/:session_id/workflows/:workflow_id/runs

  The BFF builds a `WorkflowDispatchJob` from the workflow definition and one
  stored transcript source, and produces it to Kafka topic `workflow.dispatch`
  keyed by session_id. An external workflow runner executes it; results come
  back through the stored latest workflow results of the session.

  Access:
  - The caller must be able to read the session (same rule as the recording
    detail). An unreadable session is 404.
  - The caller must be able to use the workflow
    (`samuraibff.access.policy/can-use-definition?`): own personal definitions
    and published ones; organization API keys use published definitions only.
    An unknown or unusable workflow is 404. A disabled workflow is 409.
  - Every accepted run writes an access audit event with ids only.

  Security:
  - Never log prompt or transcript text.
  - The job size is capped (`samuraibff.workflows.runs/run-max-bytes`)."
  (:require
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.integrations :as access.integrations]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.db.workflows :as db.workflows]
   [samuraibff.http.definition-access :as definition-access]
   [samuraibff.http.recordings :as http.recordings]
   [samuraibff.http.session-guard :as session-guard]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.observability.diagnostics :as diagnostics]
   [samuraibff.schemas :as schemas]
   [samuraibff.util.uuid :as util.uuid]
   [samuraibff.workflows.runs :as workflows.runs])
  (:import
   (java.time Instant)
   (java.util UUID)))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(def ^:private transcript-row-limit
  "Upper bound of stored rows read for one source (same as the recording detail)."
  2000)

(defn- fail!
  "Throw a handler failure with a fixed :type. Never returns."
  [type]
  (throw (ex-info (name type) {:type type})))

(defn- path-uuid!
  "Parse a UUID path parameter or throw the given failure type."
  [req k type]
  (try
    (UUID/fromString (str (or (get-in req [:path-params k]) (get-in req [:path-params (name k)]))))
    (catch Exception _
      (fail! type))))

(defn- parse-request!
  "Validate the optional body. Throws :samuraibff.http/invalid-request."
  [req]
  (let [body (or (:body-params req) {})
        parsed (try
                 (schemas/decode-and-validate! schemas/RunWorkflowRequest body)
                 (catch Exception _
                   (fail! :samuraibff.http/invalid-request)))]
    (when (and (:track_id parsed) (nil? (:stage parsed)))
      (fail! :samuraibff.http/invalid-request))
    parsed))

(defn- require-usable-workflow!
  "Return the workflow row when the principal may use and run it.

  Throws :samuraibff.http/not-found when absent or unusable and
  :samuraibff.http/workflow-disabled when disabled."
  [ds principal workflow-id]
  (let [workflow (db.workflows/find-workflow ds (:tenant-id principal) workflow-id)]
    (when-not (and workflow ((access.integrations/usable-predicate principal) workflow))
      (fail! :samuraibff.http/not-found))
    (when-not (:enabled workflow)
      (fail! :samuraibff.http/workflow-disabled))
    workflow))

(defn- source-segments
  "Read the stored segments of one source. Returns a vector (maybe empty)."
  [ds tenant-id session-id [stage track-id]]
  (let [rows (db.recordings/list-transcript-records ds tenant-id session-id
                                                    {:type stage :track-id track-id :limit transcript-row-limit})]
    (workflows.runs/transcript-segments
     (mapv #(update % :segments http.recordings/segments-jsonb->segments) rows)
     stage)))

(defn- prepare-run!
  "Authorize the request and build the encoded job.

  Returns {:principal :session-id :workflow-id :workflow-run-id :stage
  :track-id :segments-count :value bytes}. Throws typed ex-info failures."
  [{:keys [db config]} req]
  (let [ds (or (:ds db) (fail! :samuraibff.http/missing-datasource))
        principal (session-guard/require-principal! req)
        session-id (path-uuid! req :session_id :samuraibff.http/invalid-session-id)
        workflow-id (path-uuid! req :workflow_id :samuraibff.http/invalid-id)
        request (parse-request! req)
        session (session-guard/require-readable-session! ds req session-id)
        workflow (require-usable-workflow! ds principal workflow-id)
        _ (when-not (= "finished" (:status session))
            (fail! :samuraibff.http/session-not-finished))
        tenant-id (:tenant-id principal)
        [stage track-id :as source] (or (workflows.runs/select-source
                                         config workflow
                                         (db.recordings/list-transcript-sources ds tenant-id session-id)
                                         request)
                                        (fail! :samuraibff.http/transcript-not-available))
        segments (source-segments ds tenant-id session-id source)
        _ (when (empty? segments) (fail! :samuraibff.http/transcript-not-available))
        workflow-run-id (util.uuid/uuid7)
        job (or (workflows.runs/build-job {:workflow-run-id workflow-run-id
                                           :tenant-id tenant-id
                                           :session-id session-id
                                           :workflow workflow
                                           :stage stage
                                           :track-id track-id
                                           :segments segments
                                           :created-at (Instant/now)})
                (fail! :samuraibff.http/workflow-not-runnable))
        value (json/write-value-as-bytes job json-mapper)]
    (when (> (alength ^bytes value) (workflows.runs/run-max-bytes config))
      (log/warn "On-demand workflow run exceeds the size limit"
                {:tenant_id (str tenant-id) :session_id (str session-id) :workflow_id (str workflow-id)
                 :bytes (alength ^bytes value) :max_bytes (workflows.runs/run-max-bytes config)})
      (fail! :samuraibff.http/payload-too-large))
    {:principal principal
     :session-id session-id
     :workflow-id workflow-id
     :workflow-run-id workflow-run-id
     :stage stage
     :track-id track-id
     :segments-count (count segments)
     :value value}))

(defn- dispatch!
  "Record the audit event and produce the job in one DB transaction.

  The audit row commits only after the broker acknowledged the job. Throws
  :samuraibff.http/dispatch-unavailable when Kafka fails."
  [{:keys [db kafka-producer]} {:keys [principal session-id workflow-id workflow-run-id stage track-id value]}]
  (definition-access/in-transaction
   (:ds db)
   (fn [tx]
     (db.audit/record! tx {:tenant-id (:tenant-id principal)
                           :actor-id (:id principal)
                           :actor-kind (if (policy/service? principal) "service" "user")
                           :action "workflow.run.requested"
                           :target-type "session"
                           :target-id session-id
                           :outcome "succeeded"
                           :details {:workflow_id (str workflow-id)
                                     :workflow_run_id (str workflow-run-id)
                                     :stage stage
                                     :track_id track-id}})
     (try
       (kafka.producer/send-workflow-dispatch! kafka-producer (str session-id) value
                                               {:tenant-id (:tenant-id principal)})
       (catch Exception e
         (log/warn "On-demand workflow dispatch failed" (diagnostics/failure-details e))
         (throw (ex-info "dispatch-unavailable" {:type :samuraibff.http/dispatch-unavailable})))))))

(defn- error-response
  "Map a typed failure to a fixed JSON response, or nil when unknown."
  [type]
  (let [error (fn [status message] {:status status :body {:ok false :message message}})]
    (or (session-guard/error-response type)
        (case type
          :samuraibff.http/invalid-session-id (error 400 "invalid-session-id")
          :samuraibff.http/invalid-id (error 400 "invalid-id")
          :samuraibff.http/invalid-request (error 400 "invalid-request")
          :samuraibff.http/session-not-finished (error 409 "session-not-finished")
          :samuraibff.http/workflow-disabled (error 409 "workflow-disabled")
          :samuraibff.http/workflow-not-runnable (error 409 "workflow-not-runnable")
          :samuraibff.http/transcript-not-available (error 409 "transcript-not-available")
          :samuraibff.http/payload-too-large (error 413 "transcript-too-large")
          :samuraibff.http/missing-datasource (error 503 "db-unavailable")
          :samuraibff.http/dispatch-unavailable (error 503 "dispatch-unavailable")
          nil))))

(defn run-workflow-handler
  "Handler for POST /api/recordings/:session_id/workflows/:workflow_id/runs.

  Inputs: deps map with :db {:ds DataSource}, :config and :kafka-producer.
  Request body (optional): {:stage \"final\"|\"refined\" :track_id string}
  (see `schemas/RunWorkflowRequest` and `workflows.runs/select-source`).

  Returns 202 {:ok true :workflow_run_id :workflow_id :stage :track_id} after
  the broker acknowledged the job; 400 invalid ids or body; 404 unreadable
  session or unusable workflow; 409 `session-not-finished`,
  `workflow-disabled`, `workflow-not-runnable` or `transcript-not-available`;
  413 `transcript-too-large`; 503 `db-unavailable` or `dispatch-unavailable`."
  [deps]
  (fn [req]
    (try
      (let [{:keys [principal session-id workflow-id workflow-run-id stage track-id segments-count value]
             :as run} (prepare-run! deps req)
            ids {:tenant_id (str (:tenant-id principal))
                 :principal_id (str (:id principal))
                 :session_id (str session-id)
                 :workflow_id (str workflow-id)
                 :workflow_run_id (str workflow-run-id)}]
        (log/info "Dispatching on-demand workflow run"
                  (assoc ids :stage stage :track_id track-id
                         :segments_count segments-count :bytes (alength ^bytes value)))
        (dispatch! deps run)
        (log/info "Dispatched on-demand workflow run" ids)
        {:status 202
         :body {:ok true
                :workflow_run_id (str workflow-run-id)
                :workflow_id (str workflow-id)
                :stage stage
                :track_id track-id}})
      (catch Exception e
        (or (error-response (:type (ex-data e)))
            (do (log/error "Failed running workflow on demand" (diagnostics/failure-details e))
                {:status 500 :body {:ok false :message "internal-error"}}))))))

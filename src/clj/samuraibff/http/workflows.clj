(ns samuraibff.http.workflows
  "HTTP handlers for tenant-scoped workflow configuration.

  Endpoints (under /api, auth required):
  - GET    /api/workflows
  - POST   /api/workflows
  - PUT    /api/workflows/:id
  - DELETE /api/workflows/:id
  - GET    /api/workflows/defaults
  - PUT    /api/workflows/defaults

  Access (see `samuraibff.http.definition-access`):
  - Every active human lists the definitions they may view. Prompt text,
    provider parameters and incremental settings are returned only for
    definitions the caller may manage.
  - Builders and Admins create. Builders create personal definitions only.
  - Admins publish, unpublish and set defaults. Defaults hold published
    definitions only. Unpublishing or deleting a definition removes it from
    the defaults in the same transaction.

  Security:
  - Workflow prompts may contain sensitive content. Do not log prompt text.
  "
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.workflows :as db.workflows]
   [samuraibff.http.definition-access :as access]
   [samuraibff.observability.diagnostics :as diagnostics]
   [samuraibff.schemas :as schemas]
   [samuraibff.util.uuid :as util.uuid])
  (:import
   (java.util UUID)))

(defn- json-response
  [status body]
  {:status status
   :body body})

(defn- require-ds!
  [db]
  (or (:ds db)
      (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource}))))

(defn- parse-uuid-or-nil
  [s]
  (try
    (UUID/fromString (str s))
    (catch Exception _
      nil)))

(defn- require-path-id!
  [req]
  (or (parse-uuid-or-nil (or (get-in req [:path-params :id]) (get-in req [:path-params "id"])))
      (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id}))))

(defn- failure-response
  "Map a handler failure to a fixed JSON response.

  Known ex-info types map to fixed statuses; other ex-info failures use
  fallback-status; other exceptions return 500. Logs never include payloads."
  [^Throwable e log-message fallback-status]
  (let [type (:type (ex-data e))]
    (or (access/error-response type)
        (case type
          :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
          :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
          (if (instance? clojure.lang.ExceptionInfo e)
            (do (log/warn log-message (diagnostics/failure-details e))
                (json-response fallback-status {:ok false :message (if (= 400 fallback-status)
                                                                     "invalid-request"
                                                                     "internal-error")}))
            (do (log/error log-message (diagnostics/failure-details e))
                (json-response 500 {:ok false :message "internal-error"})))))))

(defn- workflow-row->api-item
  "Convert a DB workflow row into an API item for the principal.

  Every viewer gets name, trigger, provider type/model, enabled, availability,
  owner and `can_manage`. Prompt, provider params and incremental settings are
  added only when the principal may manage the definition."
  [principal owners row]
  (let [manage? (policy/can-manage-definition? principal row)
        base {:id (str (:id row))
              :tenant_id (str (:tenant_id row))
              :name (:name row)
              :enabled (boolean (:enabled row))
              :trigger (cond-> {:type (:trigger_type row)}
                         (some? (:trigger_track_id row)) (assoc :track_id (:trigger_track_id row)))
              :provider {:type (:provider_type row)
                         :model_id (:provider_model_id row)}
              :availability (:availability row)
              :owner (access/owner-view principal owners (:owner_user_id row))
              :can_manage manage?
              :created_at (some-> (:created_at row) str)
              :updated_at (some-> (:updated_at row) str)}]
    (if manage?
      (assoc base
             :provider (assoc (:provider base) :params (or (:provider_params row) {}))
             :prompt {:text (:prompt_text row)}
             :incremental {:enabled (boolean (:incremental_enabled row))
                           :min_interval_sec (:incremental_min_interval_sec row)})
      base)))

(defn list-workflows-handler
  "Handler for GET /api/workflows.

  Returns 200 {:ok true :tenant_id :items [WorkflowItem]} with only the
  definitions the principal may view, 403 for non-human principals."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (access/require-human! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            _ (log/info "Listing workflows" {:tenant_id (str tenant-uuid)
                                             :principal_id (str (:id principal))})
            rows (filterv #(policy/can-view-definition? principal %)
                          (db.workflows/list-workflows ds tenant-uuid))
            owners (access/owner-views ds tenant-uuid rows)]
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :items (mapv #(workflow-row->api-item principal owners %) rows)}))
      (catch Exception e
        (failure-response e "Failed listing workflows" 500)))))

(defn create-workflow-handler
  "Handler for POST /api/workflows.

  Builders create personal definitions owned by themselves; a Builder request
  for `published` returns 403. Admins may pass `availability` (default
  `published`). Members and services get 403."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [principal (access/require-human! req)
              _ (when-not (policy/can-author-integrations? principal) (access/forbidden!))
              tenant-uuid (:tenant-id principal)
              ds (require-ds! db)
              {:keys [name enabled trigger provider prompt incremental availability]}
              (schemas/decode-and-validate! schemas/CreateWorkflowRequest body)
              availability (access/creation-availability principal availability)
              workflow-id (util.uuid/uuid7)
              enabled? (boolean (if (contains? body :enabled) enabled true))
              row {:id workflow-id
                   :tenant-id tenant-uuid
                   :name (str/trim (str name))
                   :enabled enabled?
                   :trigger-type (get trigger :type)
                   :trigger-track-id (:track_id trigger)
                   :prompt-text (get prompt :text)
                   :provider-type (get provider :type)
                   :provider-model-id (get provider :model_id)
                   :provider_params (or (get provider :params) {})
                   :incremental_enabled (boolean (get incremental :enabled))
                   :incremental_min_interval_sec (get incremental :min_interval_sec)
                   :owner_user_id (:id principal)
                   :availability availability}]
          (log/info "Creating workflow" {:tenant_id (str tenant-uuid)
                                         :principal_id (str (:id principal))
                                         :workflow_id (str workflow-id)
                                         :availability availability
                                         :enabled enabled?
                                         :trigger_type (get trigger :type)
                                         :provider_model_id (get provider :model_id)
                                         :incremental_enabled (boolean (get incremental :enabled))})
          (access/in-transaction
           ds
           (fn [tx]
             (db.workflows/insert-workflow! tx row)
             (access/record-audit! tx principal "workflow.create" "workflow" workflow-id
                                   {:name (:name row) :availability availability})))
          (log/info "Created workflow" {:tenant_id (str tenant-uuid) :workflow_id (str workflow-id)})
          (json-response 200 {:ok true :workflow_id (str workflow-id)}))
        (catch Exception e
          (failure-response e "Failed creating workflow" 400))))))

(defn- workflow-patch
  "Build the DB patch from a validated UpdateWorkflowRequest (without availability)."
  [{:keys [name enabled trigger provider prompt incremental]}]
  (cond-> {}
    (some? name) (assoc :name (str/trim (str name)))
    (some? enabled) (assoc :enabled (boolean enabled))
    (some? trigger) (assoc :trigger_type (get trigger :type)
                           :trigger_track_id (:track_id trigger))
    (some? prompt) (assoc :prompt_text (get prompt :text))
    (some? provider) (assoc :provider_type (get provider :type)
                            :provider_model_id (get provider :model_id)
                            :provider_params (or (get provider :params) {}))
    (some? incremental) (assoc :incremental_enabled (boolean (get incremental :enabled))
                               :incremental_min_interval_sec (get incremental :min_interval_sec))))

(defn- availability-action
  [availability-patch]
  (case (:availability availability-patch)
    "published" "workflow.publish"
    "personal" "workflow.unpublish"
    "workflow.update"))

(defn update-workflow-handler
  "Handler for PUT /api/workflows/:id.

  Requires `can-manage-definition?`: 404 when the definition is not visible,
  403 when it is visible but not manageable. Changing `availability` requires
  Admin. Unpublishing removes the definition from the tenant defaults."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [principal (access/require-human! req)
              tenant-uuid (:tenant-id principal)
              ds (require-ds! db)
              workflow-id (require-path-id! req)
              parsed (schemas/decode-and-validate! schemas/UpdateWorkflowRequest body)
              field-patch (workflow-patch parsed)]
          (access/in-transaction
           ds
           (fn [tx]
             (let [existing (access/authorize-manage!
                             principal (db.workflows/lock-workflow tx tenant-uuid workflow-id))
                   availability-patch (access/availability-change principal existing (:availability parsed))
                   patch (merge field-patch availability-patch)]
               (when (seq patch)
                 (db.workflows/update-workflow! tx tenant-uuid workflow-id patch)
                 (when (= "personal" (:availability availability-patch))
                   (db.workflows/remove-default! tx tenant-uuid workflow-id))
                 (access/record-audit! tx principal (availability-action availability-patch) "workflow" workflow-id
                                       (cond-> {:changed_fields (vec (sort (map name (keys field-patch))))}
                                         availability-patch (assoc :availability (:availability availability-patch))))))))
          (log/info "Updated workflow" {:tenant_id (str tenant-uuid)
                                        :principal_id (str (:id principal))
                                        :workflow_id (str workflow-id)})
          (json-response 200 {:ok true}))
        (catch Exception e
          (failure-response e "Failed updating workflow" 400))))))

(defn delete-workflow-handler
  "Handler for DELETE /api/workflows/:id.

  Same access rules as update. Deleting removes the definition from the
  tenant defaults in the same transaction."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (access/require-human! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            workflow-id (require-path-id! req)]
        (log/info "Deleting workflow" {:tenant_id (str tenant-uuid)
                                       :principal_id (str (:id principal))
                                       :workflow_id (str workflow-id)})
        (access/in-transaction
         ds
         (fn [tx]
           (let [existing (access/authorize-manage!
                           principal (db.workflows/lock-workflow tx tenant-uuid workflow-id))]
             (db.workflows/delete-workflow! tx tenant-uuid workflow-id)
             (db.workflows/remove-default! tx tenant-uuid workflow-id)
             (access/record-audit! tx principal "workflow.delete" "workflow" workflow-id
                                   {:name (:name existing) :availability (:availability existing)}))))
        (json-response 200 {:ok true}))
      (catch Exception e
        (failure-response e "Failed deleting workflow" 500)))))

(defn get-defaults-handler
  "Handler for GET /api/workflows/defaults. Any active human may read."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [principal (access/require-human! req)
            tenant-uuid (:tenant-id principal)
            ds (require-ds! db)
            {:keys [workflow_ids]} (db.workflows/get-defaults ds tenant-uuid)]
        (log/info "Reading workflow defaults" {:tenant_id (str tenant-uuid)
                                               :workflow_ids_count (count workflow_ids)})
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :workflow_ids (mapv str workflow_ids)}))
      (catch Exception e
        (failure-response e "Failed reading workflow defaults" 500)))))

(defn- assert-published-defaults!
  "Lock each requested workflow and require it to be a published tenant definition.

  Throws :samuraibff.http/invalid-default otherwise."
  [tx tenant-uuid ids]
  (doseq [id ids]
    (let [row (db.workflows/lock-workflow tx tenant-uuid id)]
      (when-not (= "published" (:availability row))
        (throw (ex-info "Invalid default" {:type :samuraibff.http/invalid-default}))))))

(defn set-defaults-handler
  "Handler for PUT /api/workflows/defaults.

  Admin only (403 otherwise). Every id must be a published workflow of the
  tenant, else 400 `invalid-default`."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [principal (access/require-human! req)
              _ (when-not (policy/can-manage-defaults? principal) (access/forbidden!))
              tenant-uuid (:tenant-id principal)
              ds (require-ds! db)
              {:keys [workflow_ids]} (schemas/decode-and-validate! schemas/WorkflowDefaultsRequest body)
              ids (->> workflow_ids (keep parse-uuid-or-nil) distinct vec)]
          (log/info "Setting workflow defaults" {:tenant_id (str tenant-uuid)
                                                 :principal_id (str (:id principal))
                                                 :workflow_ids_count (count ids)})
          (access/in-transaction
           ds
           (fn [tx]
             (assert-published-defaults! tx tenant-uuid ids)
             (db.workflows/set-defaults! tx tenant-uuid ids)
             (access/record-audit! tx principal "workflow.defaults.set" "workflow_defaults" tenant-uuid
                                   {:workflow_ids (mapv str ids)})))
          (json-response 200 {:ok true}))
        (catch Exception e
          (failure-response e "Failed setting workflow defaults" 400))))))

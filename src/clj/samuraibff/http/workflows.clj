(ns samuraibff.http.workflows
  "HTTP handlers for tenant-scoped workflow configuration.

  Endpoints (under /api, auth required):
  - GET    /api/workflows
  - POST   /api/workflows
  - PUT    /api/workflows/:id
  - DELETE /api/workflows/:id
  - GET    /api/workflows/defaults
  - PUT    /api/workflows/defaults

  Security:
  - Workflow prompts may contain sensitive content. Do not log prompt text.
  "
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.workflows :as db.workflows]
   [samuraibff.schemas :as schemas]
   [samuraibff.util.uuid :as util.uuid])
  (:import
   (java.util UUID)))

(defn- json-response
  [status body]
  {:status status
   :body body})

(defn- require-tenant-uuid!
  [req]
  (let [tid (some-> (:auth/tenant-id req) str str/trim)]
    (cond
      (str/blank? tid)
      (throw (ex-info "Missing tenant id" {:type :samuraibff.http/missing-tenant-id}))

      :else
      (try
        (UUID/fromString tid)
        (catch Exception _
          (throw (ex-info "Invalid tenant id"
                          {:type :samuraibff.http/invalid-tenant-id
                           :tenant-id tid})))))))

(defn- parse-uuid-or-nil
  [s]
  (try
    (UUID/fromString (str s))
    (catch Exception _
      nil)))

(defn- workflow-row->api-item
  "Convert DB workflow row into API response item (string UUIDs + nested maps)."
  [row]
  (let [incremental {:enabled (boolean (:incremental_enabled row))
                     :min_interval_sec (:incremental_min_interval_sec row)}]
    {:id (str (:id row))
     :tenant_id (str (:tenant_id row))
     :name (:name row)
     :enabled (boolean (:enabled row))
     :trigger {:type (:trigger_type row)}
     :provider {:type (:provider_type row)
                :model_id (:provider_model_id row)
                :params (or (:provider_params row) {})}
     :prompt {:text (:prompt_text row)}
     :incremental incremental
     :created_at (some-> (:created_at row) str)
     :updated_at (some-> (:updated_at row) str)}))

(defn list-workflows-handler
  "Handler for GET /api/workflows."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (:ds db)]
        (when-not ds
          (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
        (log/info "Listing workflows" {:tenant_id (str tenant-uuid)})
        (let [rows (db.workflows/list-workflows ds tenant-uuid)
              items (mapv workflow-row->api-item rows)
              body {:ok true
                    :tenant_id (str tenant-uuid)
                    :items items}]
          (json-response 200 body)))

      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            (do
              (log/error e "Failed listing workflows")
              (json-response 500 {:ok false :message "internal-error"})))))

      (catch Exception e
        (log/error e "Unexpected error listing workflows")
        (json-response 500 {:ok false :message "internal-error"})))))

(defn create-workflow-handler
  "Handler for POST /api/workflows."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              {:keys [name enabled trigger provider prompt incremental]} (schemas/decode-and-validate! schemas/CreateWorkflowRequest body)
              workflow-id (util.uuid/uuid7)
              row {:id workflow-id
                   :tenant-id tenant-uuid
                   :name (str/trim (str name))
                   :enabled (boolean (if (contains? body :enabled) enabled true))
                   :trigger-type (get trigger :type)
                   :prompt-text (get prompt :text)
                   :provider-type (get provider :type)
                   :provider-model-id (get provider :model_id)
                   :provider_params (or (get provider :params) {})
                   :incremental_enabled (boolean (get incremental :enabled))
                   :incremental_min_interval_sec (get incremental :min_interval_sec)}]
          (log/info "Creating workflow" {:tenant_id (str tenant-uuid)
                                         :workflow_id (str workflow-id)
                                         :name (str name)
                                         :enabled (boolean (if (contains? body :enabled) enabled true))
                                         :trigger_type (get trigger :type)
                                         :provider_model_id (get provider :model_id)
                                         :incremental_enabled (boolean (get incremental :enabled))})
          (db.workflows/insert-workflow! ds row)
          (json-response 200 {:ok true :workflow_id (str workflow-id)}))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed creating workflow")
                (json-response 400 {:ok false :message "invalid-request"})))))

        (catch Exception e
          (log/error e "Unexpected error creating workflow")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn update-workflow-handler
  "Handler for PUT /api/workflows/:id."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})
          id-str (or (get-in req [:path-params :id]) (get-in req [:path-params "id"]))]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              workflow-id (or (parse-uuid-or-nil id-str)
                              (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id})))
              {:keys [name enabled trigger provider prompt incremental]} (schemas/decode-and-validate! schemas/UpdateWorkflowRequest body)
              patch (cond-> {}
                      (some? name) (assoc :name (str/trim (str name)))
                      (some? enabled) (assoc :enabled (boolean enabled))
                      (some? trigger) (assoc :trigger_type (get trigger :type))
                      (some? prompt) (assoc :prompt_text (get prompt :text))
                      (some? provider) (assoc :provider_type (get provider :type)
                                              :provider_model_id (get provider :model_id)
                                              :provider_params (or (get provider :params) {}))
                      (some? incremental) (assoc :incremental_enabled (boolean (get incremental :enabled))
                                                 :incremental_min_interval_sec (get incremental :min_interval_sec)))
              existing (db.workflows/find-workflow ds tenant-uuid workflow-id)]
          (if-not existing
            (json-response 404 {:ok false :message "not-found"})
            (do
              (when (seq patch)
                (db.workflows/update-workflow! ds tenant-uuid workflow-id patch))
              (json-response 200 {:ok true}))))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
              (do
                (log/error e "Failed updating workflow")
                (json-response 400 {:ok false :message "invalid-request"})))))

        (catch Exception e
          (log/error e "Unexpected error updating workflow")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn delete-workflow-handler
  "Handler for DELETE /api/workflows/:id."
  [{:keys [db]}]
  (fn [req]
    (let [id-str (or (get-in req [:path-params :id]) (get-in req [:path-params "id"]))]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              workflow-id (or (parse-uuid-or-nil id-str)
                              (throw (ex-info "Invalid id" {:type :samuraibff.http/invalid-id})))
              _ (log/info "Deleting workflow" {:tenant_id (str tenant-uuid)
                                               :workflow_id (str workflow-id)})
              {:keys [deleted?]} (db.workflows/delete-workflow! ds tenant-uuid workflow-id)]
          (if deleted?
            (json-response 200 {:ok true})
            (json-response 404 {:ok false :message "not-found"})))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              :samuraibff.http/invalid-id (json-response 400 {:ok false :message "invalid-id"})
              (do
                (log/error e "Failed deleting workflow")
                (json-response 500 {:ok false :message "internal-error"})))))

        (catch Exception e
          (log/error e "Unexpected error deleting workflow")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn get-defaults-handler
  "Handler for GET /api/workflows/defaults."
  [{:keys [db]}]
  (fn [req]
    (try
      (let [tenant-uuid (require-tenant-uuid! req)
            ds (:ds db)
            _ (when-not ds
                (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
            {:keys [workflow_ids]} (db.workflows/get-defaults ds tenant-uuid)]
        (log/info "Reading workflow defaults" {:tenant_id (str tenant-uuid)
                                               :workflow_ids_count (count workflow_ids)})
        (json-response 200 {:ok true
                            :tenant_id (str tenant-uuid)
                            :workflow_ids (mapv str workflow_ids)}))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type]} (ex-data e)]
          (case type
            :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
            :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
            :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
            (do
              (log/error e "Failed reading workflow defaults")
              (json-response 500 {:ok false :message "internal-error"})))))
      (catch Exception e
        (log/error e "Unexpected error reading workflow defaults")
        (json-response 500 {:ok false :message "internal-error"})))))

(defn set-defaults-handler
  "Handler for PUT /api/workflows/defaults."
  [{:keys [db]}]
  (fn [req]
    (let [body (or (:body-params req) (:body req) {})]
      (try
        (let [tenant-uuid (require-tenant-uuid! req)
              ds (:ds db)
              _ (when-not ds
                  (throw (ex-info "Missing datasource" {:type :samuraibff.http/missing-datasource})))
              {:keys [workflow_ids]} (schemas/decode-and-validate! schemas/WorkflowDefaultsRequest body)
              ids (->> workflow_ids
                       (keep parse-uuid-or-nil)
                       vec)]
          (log/info "Setting workflow defaults" {:tenant_id (str tenant-uuid)
                                                 :workflow_ids_count (count ids)})
          (db.workflows/set-defaults! ds tenant-uuid ids)
          (json-response 200 {:ok true}))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed setting workflow defaults")
                (json-response 400 {:ok false :message "invalid-request"})))))

        (catch Exception e
          (log/error e "Unexpected error setting workflow defaults")
          (json-response 500 {:ok false :message "internal-error"}))))))

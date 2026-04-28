(ns samuraibff.http.webhook-delivery-outcomes
  "HTTP handlers for webhook delivery outcomes.

  Endpoints (auth required):
  - GET /api/sessions/:session_id/webhook-delivery-outcomes

  This endpoint exists primarily for the Live Recording right-side panel.
  The Recording Detail page also embeds these outcomes inside
  `GET /api/recordings/:session_id`."
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.db.webhook-delivery-outcomes :as db.wh.outcomes]
   [samuraibff.schemas :as schemas])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(defn- json-response
  [status body]
  {:status status
   :body body})

(defn- tenant-id-uuid
  "Extract tenant id from request and convert to UUID.

  Throws:
  - ex-info with :type :samuraibff.http/missing-tenant-id
  - ex-info with :type :samuraibff.http/invalid-tenant-id"
  [req]
  (let [tid (or (:auth/tenant-id req)
                (get-in req [:auth :tenant-id]))]
    (when (str/blank? (str tid))
      (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id})))
    (try
      (UUID/fromString (str tid))
      (catch Exception e
        (throw (ex-info "invalid-tenant-id"
                        {:type :samuraibff.http/invalid-tenant-id
                         :tenant-id tid}
                        e))))))

(defn- parse-session-uuid
  "Parse :session_id path parameter into UUID.

  Throws ex-info with :type :samuraibff.http/invalid-session-id."
  [req]
  (let [sid-str (or (get-in req [:path-params :session_id])
                    (get-in req [:path-params "session_id"]))]
    (try
      (UUID/fromString (str sid-str))
      (catch Exception _
        (throw (ex-info "invalid-session-id"
                        {:type :samuraibff.http/invalid-session-id
                         :session-id sid-str}))))))

(defn list-webhook-delivery-outcomes-handler
  "Handler for GET /api/sessions/:session_id/webhook-delivery-outcomes.

  Dependencies:
  - {:db {:ds javax.sql.DataSource} :config <config-map>}

  Returns:
  - 200 {:ok true :tenant_id ... :session_id ... :items [...]}
  - 403/400 on tenant issues
  - 400 on invalid session_id
  - 503 when datasource missing"
  [{:keys [db config]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot serve webhook delivery outcomes" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              session-uuid (parse-session-uuid req)
              session (db.recordings/find-session-by-id ds tenant-uuid session-uuid)]
          (if-not session
            (json-response 404 {:ok false :message "not-found"})
            (let [rows (db.wh.outcomes/list-latest-outcomes-for-session ds tenant-uuid session-uuid {:limit 50})
                  normalize
                  (fn [o]
                    {:id (str (:id o))
                     :created_at (some-> (:created_at o) str)
                     :webhook_id (:webhook_id o)
                     :dispatch_id (str (:dispatch_id o))
                     :event_id (:event_id o)
                     :event_type (:event_type o)
                     :attempt_no (long (or (:attempt_no o) 0))
                     :attempts_count (long (or (:attempts_count o) 0))
                     :status (:status o)
                     :http_status (:http_status o)
                     :error_code (:error_code o)
                     :error_detail (:error_detail o)
                     :latency_ms (:latency_ms o)})
                  body {:ok true
                        :tenant_id (str tenant-uuid)
                        :session_id (str session-uuid)
                        :items (mapv normalize rows)}]
              (when (#{:dev :test} (:env config))
                (schemas/validate! schemas/WebhookDeliveryOutcomesResponse body))
              (json-response 200 body))))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed to list webhook delivery outcomes")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error listing webhook delivery outcomes")
          (json-response 500 {:ok false :message "db-error"}))))))

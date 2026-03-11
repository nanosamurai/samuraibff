(ns samuraibff.http.recordings
  "HTTP handlers for DB-backed recordings/sessions.

  Endpoints (all secured by auth middleware in router):
  - GET /api/recordings
  - GET /api/recordings/:session_id

  Auth:
  - Requires `wrap-authenticate` + `wrap-require-auth` in router.
  - Uses `:auth/tenant-id` from request and scopes all DB reads by tenant.

  Returns JSON only (Muuntaja handles content negotiation)." 
  (:require
    [cheshire.core :as cheshire]
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [samuraibff.db.recordings :as db.recordings]
    [samuraibff.schemas :as schemas])
  (:import
    (java.util UUID)
    (javax.sql DataSource)))

(defn- jsonb->json-string
  "Normalize a Postgres json/jsonb value into a JSON string.

  next.jdbc + the Postgres driver typically returns jsonb columns as
  `org.postgresql.util.PGobject` with `.getValue` returning a JSON string.

  Inputs:
  - v: value returned from JDBC

  Returns:
  - JSON string (or "[]" when nil).

  Notes:
  - We intentionally return a string because it is the most portable shape
    for the CLJS UI (which can JSON.parse it)." 
  [v]
  (cond
    (nil? v) "[]"
    (string? v) v

    ;; Postgres driver jsonb
    (and (some? v) (= "org.postgresql.util.PGobject" (.getName (class v))))
    (or (some-> v (.getValue) str) "[]")

    :else
    (cheshire/generate-string v)))

(defn- json-response
  "Return a Ring JSON response.

  Inputs:
  - status int
  - body map

  Returns Ring response map." 
  [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (cheshire/generate-string body)})

(defn- tenant-id-uuid
  "Extract tenant id from req and convert to UUID.

  Inputs:
  - req: Ring request map

  Returns:
  - UUID

  Throws:
  - ex-info if missing/invalid." 
  [req]
  (let [tid (or (:auth/tenant-id req)
                (get-in req [:auth :tenant-id]))]
    (when (str/blank? (str tid))
      (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id})))
    (try
      (UUID/fromString (str tid))
      (catch Exception e
        (throw (ex-info "invalid-tenant-id" {:type :samuraibff.http/invalid-tenant-id
                                              :tenant-id tid}
                        e))))))

(defn- parse-int
  [s default]
  (try
    (let [n (Long/parseLong (str s))]
      (if (neg? n) default n))
    (catch Exception _
      default)))

(defn list-recordings-handler
  "Handler for `GET /api/recordings`.

  Query params:
  - limit (optional, default 200)
  - offset (optional, default 0)

  Response body:
  - {:items [ ... ]}

  Each item contains session metadata and best-effort recording flags.

  Dependencies:
  - deps: {:db {:ds DataSource}}

  Returns Ring handler fn." 
  [{:keys [db config]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot serve recordings" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              limit (parse-int (or (get-in req [:params :limit]) (get-in req [:params "limit"])) 200)
              offset (parse-int (or (get-in req [:params :offset]) (get-in req [:params "offset"])) 0)
              rows (db.recordings/list-sessions-for-tenant ds tenant-uuid {:limit limit :offset offset})
              items (mapv (fn [r]
                            {:session_id (str (:id r))
                             :session_key (:session_key r)
                             :status (:status r)
                             :started_at (some-> (:started_at r) str)
                             :ended_at (some-> (:ended_at r) str)
                             :created_at (some-> (:created_at r) str)
                             :has_recording (boolean (:has_recording r))
                             :has_final_transcript (boolean (:has_final_transcript r))
                             :recording {:created_at (some-> (:recording_created_at r) str)
                                         :duration_s (:duration_s r)
                                         :sample_rate (:sample_rate r)
                                         :lang (:lang r)
                                         :url (:recording_url r)}})
                          rows)
              body {:ok true
                    :tenant_id (str tenant-uuid)
                    :items items}]
          ;; Validate in dev/test.
          (when (#{:dev :test} (:env config))
            (schemas/validate! [:map [:ok :boolean] [:tenant_id schemas/Uuid] [:items :any]] body))
          (json-response 200 body))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed to list recordings")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error listing recordings")
          (json-response 500 {:ok false :message "db-error"}))))))

(defn get-recording-handler
  "Handler for `GET /api/recordings/:session_id`.

  Returns metadata + transcript records.

  Response body:
  {:ok true
   :tenant_id <uuid>
   :session {...}
   :transcripts {:refined [...records...] :final [...records...]}}

  Notes:
  - refined is append-only; we return all refined records ordered by created_at.
  - final may have multiple entries; UI will pick the latest.

  Returns 404 if the session is not found within tenant." 
  [{:keys [db]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot serve recording detail" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              sid-str (or (get-in req [:path-params :session_id])
                          (get-in req [:path-params "session_id"]))
              session-uuid (try
                             (UUID/fromString (str sid-str))
                             (catch Exception _
                               (throw (ex-info "invalid-session-id"
                                               {:type :samuraibff.http/invalid-session-id
                                                :session-id sid-str}))))
              session (db.recordings/find-session-by-id ds tenant-uuid session-uuid)]
          (if-not session
            (json-response 404 {:ok false :message "not-found"})
            (let [refined (db.recordings/list-transcript-records ds tenant-uuid session-uuid {:type "refined" :limit 2000})
                  final (db.recordings/list-transcript-records ds tenant-uuid session-uuid {:type "final" :limit 20})
                  ;; Normalize keys for UI JSON.
                  normalize-record
                  (fn [r]
                    {:id (str (:id r))
                     :type (:type r)
                     :source (:source r)
                     :model (:model r)
                     :window_length (:window_length r)
                     :segment_start_s (:segment_start_s r)
                     :segment_end_s (:segment_end_s r)
                     :event_created_at_ns (:event_created_at_ns r)
                     :created_at (some-> (:created_at r) str)
                     :full_text (:full_text r)
                     ;; segments is jsonb; next.jdbc returns PGobject/string depending on driver.
                     :segments (jsonb->json-string (:segments r))})
                  body {:ok true
                        :tenant_id (str tenant-uuid)
                        :session {:id (str (:id session))
                                  :session_key (:session_key session)
                                  :title (:title session)
                                  :status (:status session)
                                  :started_at (some-> (:started_at session) str)
                                  :ended_at (some-> (:ended_at session) str)
                                  :created_at (some-> (:created_at session) str)}
                        :transcripts {:refined (mapv normalize-record refined)
                                      :final (mapv normalize-record final)}}]
              (json-response 200 body))))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed to load recording")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error loading recording")
          (json-response 500 {:ok false :message "db-error"}))))))

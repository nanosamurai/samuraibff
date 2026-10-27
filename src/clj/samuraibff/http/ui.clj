(ns samuraibff.http.ui
  "HTTP handlers for serving the SPA assets and small UI helper endpoints.

  Endpoints:
  - GET /              -> resources/public/index.html
  - GET /js/*          -> compiled shadow-cljs output (resources/public/js)
  - POST /api/sessions -> create a new session id (uuid)

  Security notes:
  - When auth is required, sessions are created *server-side* and bound to the
    authenticated tenant (`:auth/tenant-id`).
  - WebSocket handlers should reject cross-tenant access based on this binding.

  Ownership:
  - Every new session is owned by the active request principal and starts
    Private. Without a database the endpoint fails; it never creates an
    ownerless session.
  - Rename and finish are owner-only mutations."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [ring.util.response :as resp]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.access.integrations :as access.integrations]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.http.session-guard :as session-guard]
   [samuraibff.features :as features]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.schemas :as schemas]
   [samuraibff.sessions.meta :as sessions.meta]
   [samuraibff.util.uuid :as uuid]
   [samuraibff.ws.registry :as ws.registry])
  (:import
   (java.util UUID)))

(defn- json-response
  "Return a response with a data body.

  We intentionally do not JSON-encode the body here. Muuntaja (installed in the
  HTTP router) will encode the response to JSON.

  This is necessary so Reitit response coercion (Malli) can validate response
  bodies against the declared schemas.

  Inputs:
  - status int
  - body map

  Returns: Ring response map."
  [status body]
  {:status status
   :body body})

(defn index-handler
  "Serve the SPA index.html from classpath (resources/public/index.html).

  Inputs:
  - req: Ring request map (ignored)

  Returns:
  - Ring response (200 text/html) or (500 text/plain if resource missing)."
  [_req]
  (if-let [_res (io/resource "public/index.html")]
    (-> (resp/resource-response "public/index.html")
        (resp/content-type "text/html; charset=utf-8")
        ;; Ensure UI changes (CSS/layout) are always picked up during dev.
        ;; The JS bundle is not content-hashed, so caching can be confusing.
        (resp/header "Cache-Control" "no-store, max-age=0")
        (resp/header "Pragma" "no-cache"))
    (do
      (log/error "Missing public/index.html on classpath")
      {:status 500
       :headers {"content-type" "text/plain"}
       :body "Missing UI index.html"})))

(defn- request-contains-key?
  "Return true when a request body contains a key as keyword or string.

  Inputs:
  - body: request body map
  - k: keyword

  Returns: boolean."
  [body k]
  (let [k-str (name k)]
    (or (contains? body k)
        (contains? body k-str))))

(defn create-session-handler
  "Create a new session id, persist it to Postgres, and bind it to the ws registry.

  Dependencies:
  - deps map containing:
    - :config      full config map
    - :ws-registry ws registry component
    - :db          db component {:ds ...}

  Auth behavior:
  - Expects `wrap-principal` to have attached an active principal. The session
    is owned by that principal and bound to its tenant. With authentication
    disabled this is the explicit guest principal.
  - Requested workflow/webhook overrides must be usable by the owner.

  Returns:
  - Ring response (200 application/json) with body:
    `{ :session_id \"<uuid>\" }`.
  - 403 JSON when the principal may not record or use a requested definition.
  - 503 JSON when the database is unavailable; 500 JSON on DB failure."
  [{:keys [config ws-registry db kafka-producer]}]
  (fn [req]
    (let [principal (:auth/principal req)]
      (try
        (when-not (:record (policy/capabilities principal))
          (throw (ex-info "forbidden" {:type :samuraibff.http/forbidden})))
        (let [body (or (:body-params req) (:body req) {})
              feature-enabled? (features/workflow-webhook-runtime-enabled? config)
              webhook-overrides-present? (request-contains-key? body :webhook_overrides)
              workflow-overrides-present? (request-contains-key? body :workflow_overrides)
              _ (when (and (not feature-enabled?)
                           (or webhook-overrides-present? workflow-overrides-present?))
                  (throw (ex-info "workflow-webhook-overrides-disabled"
                                  {:type :samuraibff.http/feature-not-enabled
                                   :feature :workflow-webhook-runtime})))
              ;; Request body is optional for backward compatibility.
              {:keys [title webhook_overrides session_settings workflow_overrides]} (try
                                                                                      (schemas/decode-and-validate! schemas/CreateSessionRequest body)
                                                                                      (catch Exception _
                                                                                        {}))
              title (some-> title str str/trim)
              title (when-not (str/blank? (str title)) title)
              default-title (format "Session %1$tF %1$tR" (java.time.ZonedDateTime/now))
              title' (or title default-title)

              tenant-id-uuid (:tenant-id principal)
              session-uuid (uuid/uuid7)
              session-id (str session-uuid)
              session-key session-id
              ds (get db :ds)]
          (log/info "Creating session" {:tenant_id (str tenant-id-uuid)
                                        :principal_id (str (:id principal))
                                        :session_id session-id})
          (when-not ds
            (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
          (when feature-enabled?
            (access.integrations/assert-overrides-usable!
             ds principal webhook_overrides workflow_overrides))
          (db.sessions/insert-session!
           ds
           {:id session-uuid
            :tenant-id tenant-id-uuid
            :user-id (:id principal)
            :session-key session-key
            :title title'
            :status "created"
            :webhook-overrides webhook_overrides
            :session-settings session_settings
            :workflow-overrides workflow_overrides})

          ;; Create/bind session in registry immediately so WS endpoints can be
          ;; strict and disallow session creation via WS when auth is required.
          ;;
          ;; IMPORTANT: even when auth is disabled, we bind the in-memory session
          ;; under the same tenant UUID that we persisted into Postgres.
          (ws.registry/ensure-session!
           ws-registry
           (str tenant-id-uuid)
           session-id
           (cond-> {}
             feature-enabled? (assoc :webhook_overrides webhook_overrides
                                     :workflow_overrides workflow_overrides)))

          ;; Publish session routing snapshot (compacted topic) best-effort.
          (when (and ds kafka-producer)
            (try
              (log/info "Resolving sessions.meta routing snapshot" {:tenant_id (str tenant-id-uuid)
                                                                    :session_id session-id
                                                                    :webhook_overrides_present? (and feature-enabled? (some? webhook_overrides))
                                                                    :workflow_webhook_runtime_enabled feature-enabled?})
              (let [meta (sessions.meta/resolve-sessions-meta config ds tenant-id-uuid session-uuid)
                    targets (or (get-in meta [:webhook_routing :targets_by_event_type]) {})
                    wf-targets (get-in meta [:workflows :targets])
                    targets-count (when (map? targets)
                                    (reduce + 0 (map (comp count val) targets)))]
                (log/info "Publishing sessions.meta" {:tenant_id (str tenant-id-uuid)
                                                      :session_id session-id
                                                      :schema_version (:schema_version meta)
                                                      :refined_consolidation_enabled (boolean (get-in meta [:refined_transcript :consolidation :enabled]))
                                                      :event_types_count (count (keys (or targets {})))
                                                      :targets_count (or targets-count 0)
                                                      :workflow_targets_count (count (or wf-targets []))})
                (kafka.producer/send-sessions-meta! kafka-producer session-id meta {:tenant-id (str tenant-id-uuid)}))
              (catch Exception e
                (log/warn e "Failed publishing sessions.meta" {:tenant_id (str tenant-id-uuid)
                                                               :session_id session-id}))))

          (log/info "Session created" {:tenant_id (str tenant-id-uuid)
                                       :principal_id (str (:id principal))
                                       :session_id session-id})

          (json-response 200 {:session_id session-id
                              :title title'}))
        (catch clojure.lang.ExceptionInfo e
          (case (:type (ex-data e))
            :samuraibff.http/forbidden
            (json-response 403 {:ok false :message "forbidden"})

            :samuraibff.access/definition-not-usable
            (json-response 403 {:ok false :message "definition-not-usable"})

            :samuraibff.http/missing-datasource
            (json-response 503 {:ok false :message "db-unavailable"})

            :samuraibff.http/feature-not-enabled
            (features/not-enabled-response config (or (:feature (ex-data e)) :workflow-webhook-runtime))

            (do
              (log/error e "Failed to create session" {:uri (:uri req)})
              (json-response 500 {:ok false :message "session-create-failed"}))))
        (catch Exception e
          (log/error e "DB error while creating session" {:uri (:uri req)})
          (json-response 500 {:ok false :message "db-error"}))))))

(defn rename-session-handler
  "Rename a session owned by the request principal.

  Endpoint:
  - PATCH /api/sessions/:session_id

  Dependencies:
  - :config full config map
  - :db {:ds DataSource}

  Request body:
  - schemas/UpdateSessionTitleRequest ({:title string?})

  Returns:
  - 200 {:ok true :session_id <uuid> :title <string?>}
  - 404 {:ok false :message not-found}
  - 400 {:ok false :message invalid-session-id}
  - 403 {:ok false :message missing-tenant-id}
  - 503 {:ok false :message db-unavailable}"
  [{:keys [config db]}]
  (fn [req]
    (let [ds (:ds db)
          sid-str (or (get-in req [:path-params :session_id])
                      (get-in req [:path-params "session_id"]))
          body (or (:body-params req) (:body req) {})]
      (try
        (when-not ds
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))

        (let [session-uuid (try
                             (UUID/fromString (str sid-str))
                             (catch Exception _
                               (throw (ex-info "invalid-session-id"
                                               {:type :samuraibff.http/invalid-session-id
                                                :session-id sid-str}))))
              tenant-id-uuid (:tenant_id (session-guard/require-managed-session! ds req session-uuid))
              {:keys [title]} (schemas/decode-and-validate! schemas/UpdateSessionTitleRequest body)
              title (some-> title str str/trim)
              title (when-not (str/blank? (str title)) title)
              {:keys [updated?]} (db.sessions/update-session-title! ds tenant-id-uuid session-uuid title)]
          (if updated?
            (json-response 200 {:ok true :session_id (str session-uuid) :title title})
            (json-response 404 {:ok false :message "not-found"})))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (:samuraibff.http/not-found :samuraibff.http/read-only :samuraibff.http/forbidden)
              (session-guard/error-response type)
              (do
                (log/error e "Failed to rename session" {:uri (:uri req)})
                (json-response 500 {:ok false :message "internal-error"})))))

        (catch Exception e
          (log/error e "Unexpected error renaming session" {:uri (:uri req)})
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn finish-session-handler
  "Finish a session owned by the request principal (state machine transition).

  Endpoint:
  - POST /api/sessions/:session_id/finish

  Dependencies:
  - :config full config map
  - :db {:ds DataSource}

  Returns:
  - 200 {:ok true :session_id <uuid> :status \"finished\"}
  - 404 {:ok false :message not-found}
  - 400 {:ok false :message invalid-session-id}
  - 403 {:ok false :message missing-tenant-id}
  - 503 {:ok false :message db-unavailable}"
  [{:keys [config db]}]
  (fn [req]
    (let [ds (:ds db)
          sid-str (or (get-in req [:path-params :session_id])
                      (get-in req [:path-params "session_id"]))]
      (try
        (when-not ds
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [session-uuid (try
                             (UUID/fromString (str sid-str))
                             (catch Exception _
                               (throw (ex-info "invalid-session-id"
                                               {:type :samuraibff.http/invalid-session-id
                                                :session-id sid-str}))))
              tenant-id-uuid (:tenant_id (session-guard/require-managed-session! ds req session-uuid))
              {:keys [updated?]} (db.sessions/finish-session! ds tenant-id-uuid session-uuid)]
          (if updated?
            (json-response 200 {:ok true
                                :session_id (str session-uuid)
                                :status "finished"})
            (json-response 404 {:ok false :message "not-found"})))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (:samuraibff.http/not-found :samuraibff.http/read-only :samuraibff.http/forbidden)
              (session-guard/error-response type)
              (do
                (log/error e "Failed to finish session" {:uri (:uri req) :session-id sid-str})
                (json-response 500 {:ok false :message "internal-error"})))))

        (catch Exception e
          (log/error e "Unexpected error finishing session" {:uri (:uri req) :session-id sid-str})
          (json-response 500 {:ok false :message "internal-error"}))))))

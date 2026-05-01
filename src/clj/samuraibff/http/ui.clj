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

  Note: for MVP, sessions are stored only in memory (ws registry)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [ring.util.response :as resp]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.kafka.producer :as kafka.producer]
   [samuraibff.schemas :as schemas]
   [samuraibff.sessions.meta :as sessions.meta]
   [samuraibff.util.uuid :as uuid]
    [samuraibff.webhooks.routing-snapshot :as webhooks.snapshot]
    [samuraibff.workflows.snapshot :as workflows.snapshot]
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

(def ^:private guest-tenant-id
  "Tenant UUID used when auth is disabled.

  This is a pragmatic dev-only fallback so we can still persist sessions even
  without authentication.

  Note: your DB must contain a row in `tenants` with this id if you want
  persistence to work in unauthenticated mode."
  (UUID/fromString "00000000-0000-0000-0000-000000000000"))

(def ^:private guest-user-external-id
  "External id used when auth is disabled."
  "guest")

(defn- tenant-uuid
  "Return a tenant UUID.

  Inputs:
  - config: full config map
  - tenant-id: string or nil from request

  Returns:
  - java.util.UUID

  Throws:
  - ex-info when auth required and tenant-id missing."
  [config tenant-id]
  (cond
    (and (oidc/auth-required? config) (nil? tenant-id))
    (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id}))

    (some? tenant-id)
    (UUID/fromString (str tenant-id))

    :else
    guest-tenant-id))

(defn- resolve-user-id
  "Resolve `app_users.id` for the current request.

  Behavior:
  - when authenticated: uses Keycloak `sub` as `app_users.external_id`
  - when unauthenticated: uses external-id guest
  - if no user record exists, returns nil (session.user_id is nullable)

  Inputs:
  - ds: datasource
  - tenant-id-uuid: UUID
  - req: ring request

  Returns:
  - UUID or nil"
  [ds tenant-id-uuid req]
  (let [external-id (or (some-> req :auth/user :sub)
                        guest-user-external-id)]
    (db.sessions/find-user-id-by-external-id ds tenant-id-uuid external-id)))

(defn create-session-handler
  "Create a new session id, persist it to Postgres, and bind it to the ws registry.

  Dependencies:
  - deps map containing:
    - :config      full config map
    - :ws-registry ws registry component
    - :db          db component {:ds ...}

  Auth behavior:
  - If auth is required, this endpoint expects `wrap-authenticate` +
    `wrap-require-auth` to have run and will reject missing `:auth/tenant-id`.
  - If auth is not required, we persist sessions under a dev fallback tenant id
    (all-zero UUID) so the `sessions.tenant_id` NOT NULL constraint is satisfied.

  Returns:
  - Ring response (200 application/json) with body:
    `{ :session_id \"<uuid>\" }`.
  - On missing tenant-id when auth required: 403 JSON.
  - On DB failure: 500 JSON."
  [{:keys [config ws-registry db kafka-producer]}]
  (fn [req]
    (let [tenant-id (:auth/tenant-id req)]
      (try
         (let [body (or (:body-params req) (:body req) {})
              ;; Request body is optional for backward compatibility.
               {:keys [title webhook_overrides session_settings workflow_overrides]} (try
                                                                   (schemas/decode-and-validate! schemas/CreateSessionRequest body)
                                                                   (catch Exception _
                                                                     {}))
              title (some-> title str str/trim)
              title (when-not (str/blank? (str title)) title)
              default-title (format "Session %1$tF %1$tR" (java.time.ZonedDateTime/now))
              title' (or title default-title)

              tenant-id-uuid (tenant-uuid config tenant-id)
              session-uuid (uuid/uuid7)
              session-id (str session-uuid)
              session-key session-id
              ds (get db :ds)]
          (log/info "Creating session" {:tenant_id (str tenant-id-uuid)
                                        :user_id (some-> req :auth/user :sub str)
                                        :session_id session-id})
          (when-not ds
            (log/warn "DB datasource missing; creating session without persistence" {:uri (:uri req)}))
          (when ds
            (let [user-id (resolve-user-id ds tenant-id-uuid req)]
              (db.sessions/insert-session!
               ds
               {:id session-uuid
                :tenant-id tenant-id-uuid
                :user-id user-id
                :session-key session-key
                :title title'
                :status "created"
                :webhook-overrides webhook_overrides
                  :session-settings session_settings
                  :workflow-overrides workflow_overrides})))

          ;; Create/bind session in registry immediately so WS endpoints can be
          ;; strict and disallow session creation via WS when auth is required.
          ;;
          ;; IMPORTANT: even when auth is disabled, we bind the in-memory session
          ;; under the same tenant UUID that we persisted into Postgres.
           (ws.registry/ensure-session! ws-registry (str tenant-id-uuid) session-id {:webhook_overrides webhook_overrides
                                                                                    :workflow_overrides workflow_overrides})

          ;; Publish session routing snapshot (compacted topic) best-effort.
          (when (and ds kafka-producer)
            (try
              (log/info "Resolving sessions.meta routing snapshot" {:tenant_id (str tenant-id-uuid)
                                                                    :session_id session-id
                                                                    :webhook_overrides_present? (some? webhook_overrides)})
               (let [routing (webhooks.snapshot/resolve-routing-snapshot ds tenant-id-uuid session-uuid webhook_overrides)
                     wf-targets (workflows.snapshot/resolve-targets ds tenant-id-uuid session-uuid workflow_overrides)
                     meta (sessions.meta/build-sessions-meta config tenant-id-uuid session-uuid routing session_settings wf-targets)
                    targets (or (get-in routing [:targets_by_event_type]) {})
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
                                       :user_id (some-> req :auth/user :sub str)
                                       :session_id session-id})

          (json-response 200 {:session_id session-id
                              :title title'}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :samuraibff.http/missing-tenant-id (:type (ex-data e)))
            (do
              (log/warn "Refusing to create session without tenant-id" {:uri (:uri req)})
              (json-response 403 {:ok false :message "missing-tenant-id"}))
            (do
              (log/error e "Failed to create session" {:uri (:uri req)})
              (json-response 500 {:ok false :message "session-create-failed"}))))
        (catch Exception e
          (log/error e "DB error while creating session" {:uri (:uri req)})
          (json-response 500 {:ok false :message "db-error"}))))))

(defn rename-session-handler
  "Rename a tenant-scoped session.

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
    (let [tenant-id (:auth/tenant-id req)
          ds (:ds db)
          sid-str (or (get-in req [:path-params :session_id])
                      (get-in req [:path-params "session_id"]))
          body (or (:body-params req) (:body req) {})]
      (try
        (when-not ds
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))

        (let [tenant-id-uuid (tenant-uuid config tenant-id)
              session-uuid (try
                             (UUID/fromString (str sid-str))
                             (catch Exception _
                               (throw (ex-info "invalid-session-id"
                                               {:type :samuraibff.http/invalid-session-id
                                                :session-id sid-str}))))
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
              (do
                (log/error e "Failed to rename session" {:uri (:uri req)})
                (json-response 500 {:ok false :message "internal-error"})))))

        (catch Exception e
          (log/error e "Unexpected error renaming session" {:uri (:uri req)})
          (json-response 500 {:ok false :message "internal-error"}))))))

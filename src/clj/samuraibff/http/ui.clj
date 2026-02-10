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
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [ring.util.response :as resp]
    [samuraibff.auth.oidc :as oidc]
    [samuraibff.db.sessions :as db.sessions]
    [samuraibff.util.uuid :as uuid]
    [samuraibff.ws.registry :as ws.registry])
  (:import
    (java.util UUID)))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

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
  [{:keys [config ws-registry db]}]
  (fn [req]
    (let [tenant-id (:auth/tenant-id req)]
      (try
        (let [tenant-id-uuid (tenant-uuid config tenant-id)
              session-uuid (uuid/uuid7)
              session-id (str session-uuid)
              session-key session-id
              ds (get db :ds)]
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
                 :status "active"})))

          ;; Create/bind session in registry immediately so WS endpoints can be
          ;; strict and disallow session creation via WS when auth is required.
          (ws.registry/ensure-session! ws-registry tenant-id session-id {})

          {:status 200
           :headers {"content-type" "application/json"}
           :body (json/write-value-as-string {:session_id session-id} json-mapper)})
        (catch clojure.lang.ExceptionInfo e
          (if (= :samuraibff.http/missing-tenant-id (:type (ex-data e)))
            (do
              (log/warn "Refusing to create session without tenant-id" {:uri (:uri req)})
              {:status 403
               :headers {"content-type" "application/json"}
               :body (json/write-value-as-string {:ok false :message "missing-tenant-id"} json-mapper)})
            (do
              (log/error e "Failed to create session" {:uri (:uri req)})
              {:status 500
               :headers {"content-type" "application/json"}
               :body (json/write-value-as-string {:ok false :message "session-create-failed"} json-mapper)})))
        (catch Exception e
          (log/error e "DB error while creating session" {:uri (:uri req)})
          {:status 500
           :headers {"content-type" "application/json"}
           :body (json/write-value-as-string {:ok false :message "db-error"} json-mapper)})))))

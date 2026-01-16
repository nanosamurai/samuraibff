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
    [samuraibff.ws.registry :as ws.registry]))

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

(defn create-session-handler
  "Create a new session id and bind it to the authenticated tenant.

  Dependencies:
  - deps map containing:
    - :config      full config map
    - :ws-registry ws registry component

  Auth behavior:
  - If auth is required, this endpoint expects `wrap-authenticate` +
    `wrap-require-auth` to have run and will reject missing `:auth/tenant-id`.
  - If auth is not required, `:auth/tenant-id` may be nil and the session is
    created without tenant binding.

  Returns:
  - Ring response (200 application/json) with body:
    `{ :session_id \"<uuid>\" }`.
  - On missing tenant-id when auth required: 403 JSON."
  [{:keys [config ws-registry]}]
  (fn [req]
    (let [tenant-id (:auth/tenant-id req)]
      (if (and (oidc/auth-required? config) (nil? tenant-id))
        (do
          (log/warn "Refusing to create session without tenant-id" {:uri (:uri req)})
          {:status 403
           :headers {"content-type" "application/json"}
           :body (json/write-value-as-string {:ok false :message "missing-tenant-id"} json-mapper)})
        (let [session-id (str (java.util.UUID/randomUUID))]
          ;; Create/bind session in registry immediately so WS endpoints can be
          ;; strict and disallow session creation via WS when auth is required.
          (ws.registry/ensure-session! ws-registry tenant-id session-id {})
          {:status 200
           :headers {"content-type" "application/json"}
           :body (json/write-value-as-string {:session_id session-id} json-mapper)})))))

(ns samuraibff.ws.auth
  "Auth helpers for WebSocket endpoints.

  http-kit WS endpoints are regular Ring handlers that either:
  - return a normal Ring response (no upgrade), or
  - call `http-kit/as-channel` to upgrade.

  This namespace provides a small, reusable helper to enforce auth *before*
  the upgrade happens. The token is not needed after the upgrade: open sockets
  are watched by `samuraibff.ws.access`.

  Public API:
  - `require-auth-or-continue`, `authenticated-now?`, `wrap-rejected-upgrade`"
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.auth.oidc :as oidc]))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defn authenticated-now?
  "Return whether a verified token may open a WebSocket now.

  Admission has no clock-skew extension beyond the token's exp.
  Development guest sockets retain their existing behavior."
  [config user]
  (or (not (oidc/auth-required? config))
      (let [deadline (:expires-at-ms user)]
        (and (integer? deadline) (< (System/currentTimeMillis) deadline)))))

(defn wrap-rejected-upgrade
  "Mark rejected WebSocket HTTP connections as non-reusable for proxies.

  Accepts a Ring handler and returns one that adds Connection: close to failed
  upgrade responses. http-kit has already switched its decoder for an Upgrade
  request, so another HTTP request on that socket would be read as WS frames.
  Successful upgrades and ordinary HTTP responses are preserved."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (= "websocket" (some-> (get-in request [:headers "upgrade"]) str/lower-case))
               (number? (:status response))
               (>= (:status response) 400))
        (assoc-in response [:headers "connection"] "close")
        response))))

(defn- guest-tenant-id
  "Return configured guest tenant id when auth is disabled.

  Behavior:
  - when auth is required: returns nil
  - when auth is disabled: returns configured [:auth :guest-tenant-id] or nil

  This is intentionally opt-in: we do not default to any tenant id unless the
  operator explicitly configures it."
  [config]
  (when-not (oidc/auth-required? config)
    (let [tid (some-> (get-in config [:auth :guest-tenant-id]) str str/trim)]
      ;; Backward compatible default: use all-zero UUID when not configured.
      ;; This matches the HTTP fallback used in `samuraibff.http.ui`.
      (if (str/blank? tid)
        "00000000-0000-0000-0000-000000000000"
        tid))))

(defn require-auth-or-continue
  "Authenticate a websocket request before upgrade.

  Inputs:
  - config: full samuraibff config
  - req: Ring request

  Behavior:
  - if auth is required:
      - missing token => returns {:ok? false :response <403>}
      - invalid token  => returns {:ok? false :response <403>}
      - valid token    => returns {:ok? true :user <user-map>}
  - if auth is not required:
      - missing/invalid token => {:ok? true :user nil :tenant-id <guest?>}
      - valid token           => {:ok? true :user <user-map> :tenant-id (<claims> or <guest?>)}

  Returns:
  - {:ok? boolean
     :response (optional Ring response)
     :user (optional user map)
     :tenant-id (optional string)}"
  [config req]
  (let [token (oidc/extract-token config req)
        required? (oidc/auth-required? config)
        guest-tid (guest-tenant-id config)]
    (if-not token
      (if required?
        {:ok? false
         :response {:status 403
                    :headers {"content-type" "application/json"}
                    :body (json/write-value-as-string {:ok false :message "missing-token"} json-mapper)}}
        (do
          (when-not guest-tid
            (log/warn "Auth disabled but :auth :guest-tenant-id not configured; tenant-id will be nil"))
          {:ok? true :user nil :tenant-id guest-tid}))
      (try
        (let [user (oidc/verify-token config token)
              tenant-id0 (oidc/extract-tenant-from-claims* config user)
              tenant-id (or tenant-id0 guest-tid)]
          (when-not (authenticated-now? config user)
            (throw (ex-info "Expired WebSocket authentication" {})))
          {:ok? true :user user :tenant-id tenant-id})
        (catch Exception _
          (if required?
            (do
              (log/info "WS auth failed")
              {:ok? false
               :response {:status 403
                          :headers {"content-type" "application/json"}
                          :body (json/write-value-as-string {:ok false :message "invalid-token"} json-mapper)}})
            (do
              (log/warn "WS auth failed but ignored (auth not required)")
              (when-not guest-tid
                (log/warn "Auth disabled but :auth :guest-tenant-id not configured; tenant-id will be nil"))
              {:ok? true :user nil :tenant-id guest-tid})))))))

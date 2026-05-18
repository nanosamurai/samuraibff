(ns samuraibff.ws.auth
  "Auth helpers for WebSocket endpoints.

  http-kit WS endpoints are regular Ring handlers that either:
  - return a normal Ring response (no upgrade), or
  - call `http-kit/as-channel` to upgrade.

  This namespace provides a small, reusable helper to enforce auth *before*
  the upgrade happens, mirroring the behavior in drsynth's Python BFF.

  Public API:
  - `require-auth-or-continue`"
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [org.corfield.logging4j2 :as log]
   [samuraibff.auth.oidc :as oidc]))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

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
              tenant-id0 (oidc/extract-tenant-from-claims user)
              tenant-id (or tenant-id0 guest-tid)]
          {:ok? true :user user :tenant-id tenant-id})
        (catch Exception e
          (if required?
            (do
              (log/info "WS auth failed" {:message (.getMessage e)})
              {:ok? false
               :response {:status 403
                          :headers {"content-type" "application/json"}
                          :body (json/write-value-as-string {:ok false :message "invalid-token"} json-mapper)}})
            (do
              (log/warn e "WS auth failed but ignored (auth not required)")
              (when-not guest-tid
                (log/warn "Auth disabled but :auth :guest-tenant-id not configured; tenant-id will be nil"))
              {:ok? true :user nil :tenant-id guest-tid})))))))

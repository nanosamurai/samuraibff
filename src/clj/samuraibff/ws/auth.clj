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
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [samuraibff.auth.oidc :as oidc]))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

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
      - missing/invalid token => {:ok? true :user nil}
      - valid token           => {:ok? true :user <user-map>}

  Returns:
  - {:ok? boolean
     :response (optional Ring response)
     :user (optional user map)
     :tenant-id (optional string)}" 
  [config req]
  (let [token (oidc/extract-token config req)
        required? (oidc/auth-required? config)]
    (if-not token
      (if required?
        {:ok? false
         :response {:status 403
                    :headers {"content-type" "application/json"}
                    :body (json/write-value-as-string {:ok false :message "missing-token"} json-mapper)}}
        {:ok? true :user nil :tenant-id nil})
      (try
        (let [user (oidc/verify-token config token)
              tenant-id (oidc/extract-tenant-from-claims user)]
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
              {:ok? true :user nil :tenant-id nil})))))))

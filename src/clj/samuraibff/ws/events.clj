(ns samuraibff.ws.events
  "WebSocket handler for streaming session events to the frontend.

  This endpoint:
  - upgrades a connection to WS at `/ws/events`
  - subscribes to the per-session event stream in `samuraibff.ws.registry`
  - sends WS event maps as JSON strings

  Query parameters:
  - session_id  (required) string

  Note: OIDC auth is intentionally not implemented yet." 
  (:require
    [clojure.core.async :as async]
    [clojure.string :as str]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [org.httpkit.server :as http]
    [samuraibff.ws.auth :as ws.auth]
    [samuraibff.ws.registry :as ws.registry]
    [samuraibff.ws.tenant :as ws.tenant]))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defn- write-json
  "Serialize an event map to a JSON string.

  Inputs:
  - value: map (event)

  Returns: string (JSON)." 
  [value]
  (json/write-value-as-string value json-mapper))


(defn handler
  "Return a Ring handler that upgrades `/ws/events` and streams JSON events.

  Auth:
  - If [:auth :required?] is true, the request must include a valid access token
    (Authorization header, ?token=..., or auth cookie) or it is rejected with 403.

  Dependencies:
  - `:config`      (required)
  - `:ws-registry` (required)
  - `:grpc`        (required)" 
  [{:keys [config ws-registry grpc]}]
  (fn [{:keys [params] :as request}]
    (let [session-id (let [val (or (get params :session_id) (get params "session_id"))]
                       (when (and val (not (str/blank? (str val)))) (str val)))]
      (if-not session-id
        {:status 400
         :headers {"content-type" "application/json"}
         :body "{\"error\":\"session_id is required\"}"}
        (let [{:keys [ok? response tenant-id]} (ws.auth/require-auth-or-continue config request)]
          (if-not ok?
            response
            (try
              (let [session (ws.tenant/assert-tenant-match! config ws-registry session-id tenant-id)
                    out-ch (async/chan 64)
                    stop?* (atom false)]
              (ws.registry/tap-events! session out-ch)
              ;; IMPORTANT: return the AsyncChannel from `http/as-channel`.
              ;; Returning nil can cause some Ring stacks/middlewares to close the
              ;; connection immediately even though `as-channel` was called.
              (http/as-channel
                request
                {:on-open (fn [ch]
                            (ws.registry/mark-events-connected! ws-registry session)
                            (log/info "WS /ws/events connected" {:session-id session-id})

                            ;; Send events coming from `out-ch` to this websocket.
                            ;; This runs on a core.async thread pool, not the WS thread.
                            (async/go-loop []
                              (when-not @stop?*
                                (if-let [event (async/<! out-ch)]
                                  (do
                                    (try
                                      (http/send! ch (write-json event))
                                      (catch Exception e
                                        (log/warn e "Failed sending ws event" {:session-id session-id
                                                                              :event-type (:type event)})))
                                    (recur))
                                  ;; channel closed
                                  (reset! stop?* true))))

                            ;; Publish a monotonic status event for UI.
                            (ws.registry/publish! ws-registry session
                                                  {:type "status"
                                                   :session_id session-id
                                                   :seq (swap! (:seq* session) inc)
                                                   :ts_ms (System/currentTimeMillis)
                                                   :status "connected"
                                                   :detail "events-subscribed"})

                            ;; Ensure gRPC stream is running once events are subscribed.
                            (ws.registry/start-rt! ws-registry grpc session))
                 :on-close (fn [_ch status]
                             (reset! stop?* true)
                             (ws.registry/untap-events! session out-ch)
                             (async/close! out-ch)
                             (ws.registry/mark-events-disconnected! ws-registry session)
                             (log/info "WS /ws/events closed" {:session-id session-id
                                                               :tenant-id (:tenant-id session)
                                                               :status status}))}))
              (catch clojure.lang.ExceptionInfo e
                (let [{:keys [type]} (ex-data e)]
                  (case type
                    :samuraibff.ws/missing-tenant-id (ws.tenant/forbidden-response "missing-tenant-id")
                    :samuraibff.ws/unknown-session (ws.tenant/forbidden-response "unknown-session")
                    :samuraibff.ws/tenant-mismatch (ws.tenant/forbidden-response "tenant-mismatch")
                    (throw e)))))))))))

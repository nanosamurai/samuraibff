(ns samuraibff.ws.audio
  "WebSocket handler for ingesting audio frames from the browser.

  This endpoint is responsible only for:
  - upgrading a connection to WS at `/ws/audio`
  - receiving *binary* frames (PCM16LE mono)
  - pushing frames into the per-session `ws.registry` audio channel

  The corresponding `/ws/events` endpoint is responsible for streaming JSON
  events to the frontend.

  Tenant isolation:
  - When auth is required, the session must already exist (created via `POST /api/sessions`)
    and must be owned by the authenticated tenant.
  - Cross-tenant access is rejected **before WS upgrade** with a 403 response."
  (:require
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [org.httpkit.server :as http]
    [samuraibff.db.sessions :as db.sessions]
    [samuraibff.ws.auth :as ws.auth]
    [samuraibff.ws.registry :as ws.registry]
    [samuraibff.ws.tenant :as ws.tenant])
  (:import
    (java.nio ByteBuffer)))

(def ^:private default-sample-rate
  "Default sample rate for PCM16 audio when not specified by the client."
  16000)

(defn- parse-int
  "Parse integer from string-like input.

  Returns default on parse failure."
  [s default]
  (if (some? s)
    (try (Integer/parseInt (str s))
         (catch Exception _ default))
    default))

(defn handler
  "Return a Ring handler that upgrades `/ws/audio` connections and ingests binary
  audio frames.

  Auth:
  - If [:auth :required?] is true, the request must include a valid access token
    (Authorization header, ?token=..., or auth cookie) or it is rejected with 403.

  Query parameters:
  - session_id   (required) string
  - lang         (optional) string
  - sample_rate  (optional) integer (defaults to 16000)

  Dependencies:
  - `:config`      (required)
  - `:ws-registry` (required)
  - `:grpc`        (required) – passed through to start the gRPC stream"
  [{:keys [config ws-registry grpc db]}]
  (fn [{:keys [params] :as request}]
    (let [session-id (let [val (or (get params :session_id) (get params "session_id"))]
                       (when (and val (not (str/blank? (str val)))) (str val)))
          lang (str (or (get params :lang) (get params "lang") ""))
          sample-rate (parse-int (or (get params :sample_rate) (get params "sample_rate"))
                                 default-sample-rate)]
      (if-not session-id
        {:status 400
         :headers {"content-type" "application/json"}
         :body "{\"error\":\"session_id is required\"}"}
        (let [{:keys [ok? response tenant-id]} (ws.auth/require-auth-or-continue config request)]
          (if-not ok?
            response
            (try
              (let [session (ws.tenant/assert-session-access!
                              config ws-registry tenant-id session-id
                              {:lang lang :sample-rate sample-rate})]
                ;; Update persisted session lifecycle once we know audio actually started.
                ;; This is best-effort; WS must continue even if DB is unavailable.
                (when-let [ds (get db :ds)]
                  (try
                    (db.sessions/mark-session-started!
                      ds
                      (java.util.UUID/fromString (str tenant-id))
                      (java.util.UUID/fromString (str session-id)))
                    (db.sessions/update-session-status!
                      ds
                      (java.util.UUID/fromString (str tenant-id))
                      (java.util.UUID/fromString (str session-id))
                      "active")
                    (catch Exception e
                      (log/warn e "Failed to mark session active" {:session-id session-id
                                                                   :tenant-id tenant-id}))))
                ;; Ensure gRPC stream is running once audio is connected.
                (ws.registry/start-rt! ws-registry grpc session)

                ;; IMPORTANT: return the AsyncChannel from `http/as-channel`.
                ;; Returning nil can cause some Ring stacks/middlewares to close the
                ;; connection immediately even though `as-channel` was called.
                (http/as-channel
                  request
                  {:on-open (fn [_ch]
                              (ws.registry/mark-audio-connected! ws-registry session)
                              (log/info "WS /ws/audio connected" {:session-id session-id
                                                                  :tenant-id (:tenant-id session)}))
                   :on-receive (fn [_ payload]
                                (cond
                                  (instance? ByteBuffer payload)
                                  (let [buf ^ByteBuffer payload
                                        arr (byte-array (.remaining buf))]
                                    (.get buf arr)
                                    (ws.registry/offer-audio! ws-registry session arr))

                                  (bytes? payload)
                                  (ws.registry/offer-audio! ws-registry session payload)

                                  :else
                                  (do
                                    (log/warn "Non-binary frame received on /ws/audio" {:session-id session-id
                                                                                        :received (str (type payload))})
                                    ;; no close: keep socket open; client bug should be visible via logs
                                    false)))
                   :on-close (fn [_ch status]
                              (log/info "WS /ws/audio closed" {:session-id session-id
                                                               :tenant-id (:tenant-id session)
                                                               :status status})
                              (ws.registry/mark-audio-disconnected! ws-registry session))}))
              (catch clojure.lang.ExceptionInfo e
                (let [{:keys [type]} (ex-data e)]
                  (case type
                    :samuraibff.ws/missing-tenant-id (ws.tenant/forbidden-response "missing-tenant-id")
                    :samuraibff.ws/unknown-session (ws.tenant/forbidden-response "unknown-session")
                    (throw e)))))))))))

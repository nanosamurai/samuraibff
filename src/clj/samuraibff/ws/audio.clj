(ns samuraibff.ws.audio
  "WebSocket handler for ingesting audio frames from the browser.

  This endpoint is responsible only for:
  - upgrading a connection to WS at `/ws/audio`
  - receiving *binary* frames (PCM16LE mono)
  - pushing frames into the per-session `ws.registry` audio channel

  The corresponding `/ws/events` endpoint is responsible for streaming JSON
  events to the frontend.

  Note: OIDC auth + Kafka publishing are intentionally not implemented yet." 
  (:require
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [org.httpkit.server :as http]
    [samuraibff.ws.registry :as ws.registry])
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

(defn- ->bytes [payload]
  (cond
    (instance? ByteBuffer payload)
    (let [buf ^ByteBuffer payload
          arr (byte-array (.remaining buf))]
      (.get buf arr)
      arr)

    (bytes? payload) payload

    :else nil))


(defn handler
  "Return a Ring handler that upgrades `/ws/audio` connections and ingests binary
  audio frames.

  Query parameters:
  - session_id   (required) string
  - lang         (optional) string
  - sample_rate  (optional) integer (defaults to 16000)

  Dependencies:
  - `:ws-registry` (required)
  - `:grpc`        (required) – passed through to start the gRPC stream" 
  [{:keys [ws-registry grpc]}]
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
        (do
          (let [session (ws.registry/ensure-session! ws-registry session-id {:lang lang
                                                                            :sample-rate sample-rate})]
            (ws.registry/start-rt! ws-registry grpc session)
            ;; IMPORTANT: return the AsyncChannel from `http/as-channel`.
            ;; Returning nil can cause some Ring stacks/middlewares to close the
            ;; connection immediately even though `as-channel` was called.
            (http/as-channel
              request
              {:on-open (fn [_ch]
                          (ws.registry/mark-audio-connected! ws-registry session)
                          (log/info "WS /ws/audio connected" {:session-id session-id}))
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
                                                            :status status})
                           (ws.registry/mark-audio-disconnected! ws-registry session))})))))))

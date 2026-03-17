(ns samuraibff.http.internal
  "Internal (non-UI) HTTP endpoints.

  Currently implemented:
  - `POST /internal/refined` – used by other BFF instances to forward
    Kafka-consumed `RefinedEvent` messages to the originating BFF.

  The endpoint accepts a protobuf-serialized `samuraibff.proto.RefinedEvent`
  in the request body (`application/x-protobuf`).

  Important semantics:
  - This endpoint MUST NOT create sessions.
  - It only delivers the refined event if the session exists in the local
    `samuraibff.ws.registry` (meaning the UI/WS is connected to this BFF).

  Public API:
  - `refined-callback-handler`"
  (:require
    [clojure.java.io :as io]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log]
    [samuraibff.ws.registry :as ws.registry])
  (:import
    (java.io ByteArrayOutputStream)
    (samuraibff.proto RefinedEvent)))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name}))

(defn- json-response
  "Return a response with a data body.

  We intentionally do not JSON-encode the body here. Muuntaja (installed in the
  HTTP router) will encode the response to JSON.

  Inputs:
  - status int
  - body map

  Returns: Ring response map." 
  [status body]
  {:status status
   :body body})

(defn- read-all-bytes
  "Read an InputStream fully into a byte-array." 
  [in]
  (with-open [^java.io.InputStream in in
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn refined-callback-handler
  "Create a Ring handler for `POST /internal/refined`.

  Dependencies:
  - `:ws-registry` (required)

  Request:
  - body: protobuf bytes of `samuraibff.proto.RefinedEvent`

  Response:
  - 200 JSON when delivered
  - 404 JSON when session not found locally
  - 400 JSON on parse failure

  Returns: Ring handler fn." 
  [{:keys [ws-registry]}]
  (fn [{:keys [body] :as _request}]
    (try
      (let [bytes (if (bytes? body) body (read-all-bytes body))
            ^RefinedEvent ev (RefinedEvent/parseFrom ^bytes bytes)
            session-id (.getSessionId ev)
            delivered? (ws.registry/publish-refined-proto! ws-registry ev)]
        (if delivered?
          (json-response 200 {:ok true
                              :session_id session-id})
          (do
            (log/debug "Refined callback: session not found locally" {:session-id session-id})
            (json-response 404 {:ok false
                                :session_id session-id
                                :message "session-not-found"}))))
      (catch Exception e
        (log/warn e "Failed to process refined callback")
        (json-response 400 {:ok false
                            :message "invalid-protobuf"})))))

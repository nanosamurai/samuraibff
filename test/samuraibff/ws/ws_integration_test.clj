(ns samuraibff.ws.ws-integration-test
  "Integration test for HTTP server + /ws/audio + /ws/events + gRPC (rtservice).

  Assumes rtservice is reachable at localhost:50052.

  It starts an Integrant system with:
  - http-kit server
  - reitit router
  - ws-registry
  - gRPC client

  Then it:
  - opens /ws/events and collects messages
  - opens /ws/audio and sends a few binary frames
  - asserts at least one `status` event arrives on /ws/events.

  If rtservice is not reachable, the test is skipped.

  Note on WebSocket client:
  - We intentionally use `com.neovisionaries/nv-websocket-client` here because
    the JDK `java.net.http.WebSocket` client has shown ProtocolExceptions against
    http-kit in this repo's tests." 
  (:require
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [samuraibff.config]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry :as reg])
  (:import
    (com.neovisionaries.ws.client WebSocket WebSocketAdapter WebSocketFactory)
    (java.net InetSocketAddress Socket)
    (java.util UUID)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn- ws-url
  "Build a ws:// URL.

  Inputs:
  - port: integer
  - path: string
  - query: string (already url-encoded)

  Returns: string" 
  [port path query]
  (str "ws://localhost:" port path "?" query))

(defn- connect-ws!
  "Connect to a WebSocket URL using nv-websocket-client.

  Inputs:
  - url: string
  - {:keys [on-text on-binary on-close]}: callback fns

  Callbacks:
  - on-text: (fn [text-string])
  - on-binary: (fn [^bytes byte-array])
  - on-close: (fn [status reason])

  Returns: com.neovisionaries.ws.client.WebSocket" 
  [^String url {:keys [on-text on-binary on-close]}]
  (let [ws (-> (WebSocketFactory.)
               (.createSocket url))]
    (.addListener
      ws
      (proxy [WebSocketAdapter] []
        ;; Implement only the String overload. WebSocketAdapter already provides
        ;; a default implementation for the byte[] overload, and Clojure cannot
        ;; define two same-arity overloads in a proxy.
        (onTextMessage [_ws ^String message]
          (when on-text
            (on-text message)))

        (onBinaryMessage [_ws payload]
          (when on-binary
            (on-binary payload)))

        (onDisconnected [_websocket server-close-frame _client-close-frame _closed-by-server]
          (when on-close
            (on-close (when server-close-frame (.getCloseCode server-close-frame))
                      "disconnected")))

        (onError [error]
          (when on-close
            (on-close 1011 (str error))))))
    ;; Logging in tests: if connect fails, it tends to fail silently otherwise.
    (try
      (.connect ws)
      (catch Exception e
        (when on-close
          (on-close 1011 (str "connect-failed: " (.getMessage e))))
        (throw e)))
    ws))

(defn- drain-queue
  "Drain up to `max-wait-ms` from a LinkedBlockingQueue.

  Inputs:
  - q: java.util.concurrent.LinkedBlockingQueue
  - max-wait-ms: integer

  Returns: vector of collected elements" 
  [^LinkedBlockingQueue q max-wait-ms]
  (loop [acc []
         deadline (+ (System/currentTimeMillis) max-wait-ms)]
    (if (>= (System/currentTimeMillis) deadline)
      acc
      (if-let [m (.poll q 250 TimeUnit/MILLISECONDS)]
        (recur (conj acc m) deadline)
        (recur acc deadline)))))

(defn- tcp-connectable?
  "Return true if a TCP connection can be established to host:port within timeout.

  Inputs:
  - host: string
  - port: integer
  - timeout-ms: integer

  Returns: boolean" 
  [^String host ^long port ^long timeout-ms]
  (try
    (with-open [sock (Socket.)]
      (.connect sock (InetSocketAddress. host (int port)) (int timeout-ms)))
    true
    (catch Exception _
      false)))

(deftest ws-audio-to-events-roundtrip-test
  (let [rt-host "localhost"
        rt-port 50052
        rt-timeout-ms 200
        port 8090
        session-id (str (UUID/randomUUID))]
    (if-not (tcp-connectable? rt-host rt-port rt-timeout-ms)
      (is true (str "Skipping: rtservice not reachable at " rt-host ":" rt-port))
      (let [cfg {:samuraibff/config {:env :test
                                     :http {:port port}
                                     :grpc {:rtservice-addr (str rt-host ":" rt-port)}}
                 :samuraibff/grpc-client {:config (ig/ref :samuraibff/config)}
                 :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)}
                 :samuraibff/router {:config (ig/ref :samuraibff/config)
                                     :ws-registry (ig/ref :samuraibff/ws-registry)
                                     :grpc (ig/ref :samuraibff/grpc-client)}
                 :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                          :handler (ig/ref :samuraibff/router)}}
            system (ig/init cfg)
            q (LinkedBlockingQueue.)
            events* (atom nil)
            audio* (atom nil)]
        (try
          (reset! events*
                  (connect-ws!
                    (ws-url port "/ws/events" (str "session_id=" session-id))
                    {:on-text (fn [msg] (.offer q msg))
                     :on-close (fn [status reason]
                                 (.offer q (str "CLOSED:" status ":" reason)))}))

          (reset! audio*
                  (connect-ws!
                    (ws-url port "/ws/audio" (str "session_id=" session-id "&lang=en&sample_rate=16000"))
                    {:on-close (fn [_ _] nil)}))

          ;; Ensure that /ws/audio control params (lang) were applied to the session
          ;; before the realtime stream starts.
          (let [ws-registry (get system :samuraibff/ws-registry)
                session (reg/get-session ws-registry nil session-id)]
            (is (= "en" (:lang session)) (str "Expected :lang to be updated, got " (pr-str (select-keys session [:lang :sample-rate])))))

          ;; Send a few dummy frames (rtservice should handle silence)
          (dotimes [_ 3]
            (.sendBinary ^WebSocket @audio* (byte-array 320)))

          (let [msgs (drain-queue q 4000)
                decoded (->> msgs
                             (keep (fn [m]
                                     (when (and (string? m)
                                                (not (.startsWith ^String m "CLOSED:")))
                                       (try
                                         (json/read-value m mapper)
                                         (catch Exception _
                                           nil))))))]
            (is (some #(= "status" (:type %)) decoded)
                (str "Expected at least one status event, got: " msgs)))

          (finally
            (when-let [^WebSocket a @audio*] (.disconnect a))
            (when-let [^WebSocket e @events*] (.disconnect e))
            (ig/halt! system)))))))

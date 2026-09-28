(ns samuraibff.ws.ws-auth-required-integration-test
  "Integration test proving WS endpoints reject connections when auth is required.

  This test does *not* require Keycloak:
  - we set [:auth :required?] true
  - we do NOT provide any token
  - we assert the connection fails quickly

  Note: nv-websocket-client hides HTTP status details; we treat failure to
  connect / immediate disconnect as success." 
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [integrant.core :as ig]
    [samuraibff.config]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.auth :as ws.auth]
    [samuraibff.ws.registry])
  (:import
    (java.io BufferedReader InputStreamReader)
    (java.net Socket)
    (java.nio.charset StandardCharsets)
    (java.security SecureRandom)
    (com.neovisionaries.ws.client WebSocketAdapter WebSocketException WebSocketFactory)
    (java.util Base64 UUID)
    (java.util.concurrent CountDownLatch TimeUnit)))

(defn- ws-url
  [port path query]
  (str "ws://localhost:" port path "?" query))

(defn- connect-fails?
  "Return true if connecting to the given ws URL fails or disconnects quickly." 
  [^String url]
  (let [latch (CountDownLatch. 1)
        closed?* (atom false)
        ws (-> (WebSocketFactory.)
               (.createSocket url))]
    (.addListener
      ws
      (proxy [WebSocketAdapter] []
        (onConnected [_ws _headers]
          ;; If server upgrades anyway, consider this a failure for this test.
          (reset! closed?* false))
        (onDisconnected [_ws _server-close _client-close _closed-by-server]
          (reset! closed?* true)
          (.countDown latch))
        (onError [error]
          (reset! closed?* true)
          (.countDown latch))))

    (try
      (.connect ws)
      ;; wait a bit for server to potentially close
      (.await latch 800 TimeUnit/MILLISECONDS)
      @closed?*
      (catch WebSocketException _
        true)
      (catch Exception _
        true)
      (finally
        (try (.disconnect ws) (catch Exception _ nil))))))

(deftest ws-auth-required-rejects-missing-token
  (let [port 8091
        session-id (str (UUID/randomUUID))
        nonce (byte-array 16)
        _ (.nextBytes (SecureRandom.) nonce)
        handshake-key (.encodeToString (Base64/getEncoder) nonce)
        cfg {:samuraibff/config {:env :test
                                 :http {:host "127.0.0.1" :port port}
                                 :auth {:required? true
                                        :issuer "http://example.invalid/issuer"
                                        :audience "bff-web"}
                                 :grpc {:rtservice-addr "localhost:50052"}}
             :samuraibff/grpc-client {:config (ig/ref :samuraibff/config)}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc (ig/ref :samuraibff/grpc-client)}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}
        system (ig/init cfg)]
    (try
      (with-open [socket (Socket. "127.0.0.1" port)]
        (.setSoTimeout socket 2000)
        (.write (.getOutputStream socket)
                (.getBytes (str "GET /ws/events?session_id=" session-id " HTTP/1.1\r\n"
                                "Host: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
                                "Sec-WebSocket-Version: 13\r\n"
                                "Sec-WebSocket-Key: " handshake-key "\r\n\r\n")
                           StandardCharsets/US_ASCII))
        (let [reader (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
              headers (loop [lines []]
                        (let [line (.readLine reader)]
                          (if (str/blank? line) lines (recur (conj lines line)))))]
          (is (str/starts-with? (first headers) "HTTP/1.1 403"))
          (is (some #(= "connection: close" (str/lower-case %)) headers))))

      (is (true?
            (connect-fails? (ws-url port "/ws/events" (str "session_id=" session-id))))
          "Expected /ws/events to reject missing auth token")

      (is (true?
            (connect-fails? (ws-url port "/ws/audio" (str "session_id=" session-id "&lang=en&sample_rate=16000"))))
          "Expected /ws/audio to reject missing auth token")

      (finally
        (ig/halt! system)))))

(deftest rejected-upgrade-middleware-preserves-other-responses
  (doseq [status [400 403 503]]
    (let [response {:status status :body "rejected"}
          handler (ws.auth/wrap-rejected-upgrade (constantly response))]
      (is (= "close" (get-in (handler {:headers {"upgrade" "WebSocket"}})
                              [:headers "connection"])))
      (is (= response (handler {:headers {}})))))
  (let [response {:status 101 :body :channel}]
    (is (= response ((ws.auth/wrap-rejected-upgrade (constantly response))
                     {:headers {"upgrade" "websocket"}})))))

(ns samuraibff.ws.ws-auth-required-integration-test
  "Integration test proving WS endpoints reject connections when auth is required.

  This test does *not* require Keycloak:
  - we set [:auth :required?] true
  - we do NOT provide any token
  - we assert the connection fails quickly

  Note: nv-websocket-client hides HTTP status details; we treat failure to
  connect / immediate disconnect as success." 
  (:require
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [samuraibff.config]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry])
  (:import
    (com.neovisionaries.ws.client WebSocketAdapter WebSocketException WebSocketFactory)
    (java.util UUID)
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
        cfg {:samuraibff/config {:env :test
                                 :http {:port port}
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
      (is (true?
            (connect-fails? (ws-url port "/ws/events" (str "session_id=" session-id))))
          "Expected /ws/events to reject missing auth token")

      (is (true?
            (connect-fails? (ws-url port "/ws/audio" (str "session_id=" session-id "&lang=en&sample_rate=16000"))))
          "Expected /ws/audio to reject missing auth token")

      (finally
        (ig/halt! system)))))

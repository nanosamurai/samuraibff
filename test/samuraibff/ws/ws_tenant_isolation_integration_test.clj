(ns samuraibff.ws.ws-tenant-isolation-integration-test
  "Integration test proving WS endpoints enforce tenant/session ownership.

  This test does not require real Keycloak:
  - auth is required
  - we bypass JWT verification by passing `?token=<anything>` and stubbing oidc/verify-token
    at the ws.auth layer via `with-redefs`.

  We mint a session for tenant A and then try to connect as tenant B.
  Expected: connection fails quickly (403 before upgrade)."
  (:require
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [samuraibff.auth.oidc :as oidc]
    [samuraibff.config]
    [samuraibff.grpc.client]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry :as ws.registry])
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
        (onError [_error]
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

(deftest ws-rejects-cross-tenant-session
  (let [port 8092
        tenant-a "tenant-a"
        tenant-b "tenant-b"
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
                                      :handler (ig/ref :samuraibff/router)}}]
    (with-redefs [oidc/verify-token (fn [_cfg token]
                                     ;; token itself encodes tenant for this test
                                     {:sub "u" :raw {:tenant_id (if (= token "a") tenant-a tenant-b)}})
                  oidc/extract-tenant-from-claims (fn [user] (get-in user [:raw :tenant_id]))]
      (let [system (ig/init cfg)
            ws-registry (get system :samuraibff/ws-registry)]
        (try
          ;; Pre-create the session bound to tenant A.
          (ws.registry/ensure-session! ws-registry session-id {:tenant-id tenant-a})

          (is (true?
                (connect-fails? (ws-url port "/ws/events" (str "session_id=" session-id "&token=b"))))
              "Expected /ws/events to reject cross-tenant session")

          (is (true?
                (connect-fails? (ws-url port "/ws/audio" (str "session_id=" session-id "&lang=en&sample_rate=16000&token=b"))))
              "Expected /ws/audio to reject cross-tenant session")

          (finally
            (ig/halt! system)))))))

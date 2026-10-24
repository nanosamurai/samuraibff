(ns samuraibff.ws.token-lifetime-integration-test
  "Real RSA verification through REST middleware and both WS handlers on loopback."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [org.httpkit.server :as http]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.auth.signed-tokens :as signed]
            [samuraibff.db.sessions :as db.sessions]
            [samuraibff.grpc.client :as grpc]
            [samuraibff.http.auth :as auth]
            [samuraibff.kafka.producer :as kafka]
            [samuraibff.sessions.meta :as sessions.meta]
            [samuraibff.ws.audio :as audio]
            [samuraibff.ws.auth :as ws.auth]
            [samuraibff.ws.events :as events]
            [samuraibff.ws.registry :as registry])
  (:import (com.neovisionaries.ws.client OpeningHandshakeException WebSocketAdapter WebSocketFactory)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util UUID)
           (java.util.concurrent CountDownLatch TimeUnit)))

(defn- socket
  "Create a loopback WS client authenticated through a header or browser cookie."
  [base path session-id token transport]
  (doto (.createSocket (WebSocketFactory.)
                       (str base path "?session_id=" session-id) 2000)
    (.addHeader (if (= transport :cookie) "Cookie" "Authorization")
                (if (= transport :cookie) (str "ns_access_token=" token) (str "Bearer " token)))))

(defn- handshake-status
  "Return the real HTTP status for a rejected upgrade, closing any accepted socket."
  [ws]
  (try
    (.connect ws)
    101
    (catch OpeningHandshakeException e
      (.getStatusCode (.getStatusLine e)))
    (finally (.disconnect ws))))

(defn- rest-status
  "Call the authenticated REST test route with a signed bearer token."
  [base token]
  (.statusCode (.send (HttpClient/newHttpClient)
                      (-> (HttpRequest/newBuilder (URI/create (str base "/api/me")))
                          (.timeout (Duration/ofSeconds 3))
                          (.header "Authorization" (str "Bearer " token))
                          (.GET) (.build))
                      (HttpResponse$BodyHandlers/discarding))))

(deftest real-signatures-protect-rest-and-websocket-lifetimes
  (let [config (assoc-in signed/config [:auth :cookie-name] "ns_access_token")
        ws-registry (ig/init-key :samuraibff/ws-registry {:config config})
        session-id (str (UUID/randomUUID))
        _ (registry/ensure-session! ws-registry signed/tenant-id session-id {})
        opts {:cache (atom nil)
              :fetch-json (signed/metadata-fetch (atom [@signed/key-a]) (atom []))}
        verify oidc/verify-token
        audio-received (promise)
        deps {:config config :ws-registry ws-registry :db {:ds :test-database}}
        audio-handler (audio/handler deps)
        events-handler (events/handler deps)
        rest-handler (auth/wrap-authenticate
                      (auth/wrap-require-auth (constantly {:status 200 :body "ok"}) config) config)
        handler (-> (fn [request]
                      (case (:uri request)
                        "/api/me" (rest-handler request)
                        "/ws/audio" (audio-handler request)
                        "/ws/events" (events-handler request)
                        {:status 404}))
                    ws.auth/wrap-rejected-upgrade wrap-cookies wrap-params)
        server (http/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        http-base (str "http://127.0.0.1:" (http/server-port server))
        ws-base (str "ws://127.0.0.1:" (http/server-port server))]
    (try
      (with-redefs [oidc/verify-token (fn [cfg token] (verify cfg token opts))
                    grpc/tracks (constantly [])
                    db.sessions/activate-session-on-audio-start-with-controls! (fn [_ _ _ controls] controls)
                    sessions.meta/resolve-sessions-meta (constantly {})
                    kafka/send-sessions-meta! (constantly nil)
                    registry/start-rt! (fn [_ _ session] session)
                    registry/offer-audio! (fn [_ _ bytes] (deliver audio-received (alength bytes)) true)]
        (let [claims (signed/claims (System/currentTimeMillis))
              now-s (quot (System/currentTimeMillis) 1000)]
          (is (= 200 (rest-status http-base (signed/token claims))))
          (doseq [token (conj (mapv signed/token
                                    [(assoc claims :exp (- now-s 60))
                                     (assoc claims :nbf (+ now-s 60))
                                     (dissoc claims :exp)
                                     (assoc claims :iss "https://other.example.test")
                                     (assoc claims :aud ["another-service"])
                                     (dissoc claims :sub)
                                     (assoc claims :sub " ")])
                              (signed/token claims {:key @signed/key-b :kid "key-a"}))]
            (is (= 401 (rest-status http-base token)))
            (doseq [path ["/ws/audio" "/ws/events"]]
              (is (= 403 (handshake-status (socket ws-base path session-id token :header))))))
          (testing "WS admission never extends past exp, including REST clock skew"
            (let [token (signed/token (assoc claims :exp (dec now-s)))]
              (is (= 200 (rest-status http-base token)))
              (is (= 403 (handshake-status (socket ws-base "/ws/events" session-id token :header)))))))
        (testing "authenticated idle audio and events sockets close at token expiry"
          (let [expiry (+ 3 (quot (System/currentTimeMillis) 1000))
                token (signed/token (assoc (signed/claims (System/currentTimeMillis)) :exp expiry))
                closed (CountDownLatch. 2)
                close-codes (atom [])
                sockets [(socket ws-base "/ws/audio" session-id token :header)
                         (socket ws-base "/ws/events" session-id token :cookie)]]
            (try
              (doseq [ws sockets]
                (.addListener ws (proxy [WebSocketAdapter] []
                                   (onDisconnected [_ server-close _ _]
                                     (swap! close-codes conj (some-> server-close .getCloseCode))
                                     (.countDown closed))))
                (.connect ws))
              (.sendBinary (first sockets) (byte-array [0 1 2 3]))
              (is (= 4 (deref audio-received 1000 :timeout)))
              (is (.await closed 6 TimeUnit/SECONDS) "Idle connections must not outlive their token")
              (is (= [1008 1008] @close-codes))
              (is (>= (System/currentTimeMillis) (* expiry 1000)))
              (finally (doseq [ws sockets] (.disconnect ws)))))))
      (finally
        @(http/server-stop! server)
        (ig/halt-key! :samuraibff/ws-registry ws-registry)))))

(deftest late-audio-is-rejected-even-before-the-expiry-timer-runs
  (let [received (atom 0)
        closed (atom 0)
        callbacks {:on-receive (fn [_ _] (swap! received inc))}
        user {:expires-at-ms (dec (System/currentTimeMillis))}]
    (with-redefs-fn {#'samuraibff.ws.auth/close-expired! (fn [_] (swap! closed inc))}
      #(do
         ((:on-receive (ws.auth/expiry-callbacks signed/config user callbacks)) :channel :audio)
         (is (zero? @received))
         (is (= 1 @closed))))
    (is (identical? callbacks
                    (ws.auth/expiry-callbacks {:auth {:required? false}} nil callbacks)))))

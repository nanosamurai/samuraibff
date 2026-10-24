(ns samuraibff.ws.ws-tenant-isolation-integration-test
  "Prove authenticated cross-tenant WS requests fail ownership checks before upgrade."
  (:require [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.config]
            [samuraibff.grpc.client]
            [samuraibff.http.router]
            [samuraibff.http.server]
            [samuraibff.ws.registry :as registry])
  (:import (com.neovisionaries.ws.client OpeningHandshakeException WebSocketFactory)
           (java.util UUID)))

(defn- handshake-response
  "Return the exact upgrade rejection from a header-authenticated WS request."
  [port path session-id token]
  (let [ws (doto (.createSocket (WebSocketFactory.)
                                (str "ws://127.0.0.1:" port path "?session_id=" session-id) 2000)
             (.addHeader "Authorization" (str "Bearer " token)))]
    (try
      (.connect ws)
      {:status 101}
      (catch OpeningHandshakeException error
        {:status (.getStatusCode (.getStatusLine error))
         :body (json/read-value (.getBody error) (json/object-mapper {:decode-key-fn keyword}))})
      (finally (.disconnect ws)))))

(deftest ws-rejects-cross-tenant-session
  (let [port 8092
        tenant-a "tenant-a"
        tenant-b "tenant-b"
        session-id (str (UUID/randomUUID))
        verified (atom [])
        config {:samuraibff/config {:env :test
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
                                         :handler (ig/ref :samuraibff/router)}}]
    (with-redefs [oidc/verify-token (fn [_ token]
                                      (swap! verified conj token)
                                      {:sub "u" :expires-at-ms (+ 60000 (System/currentTimeMillis))
                                       :raw {:tenant_id (if (= token "a") tenant-a tenant-b)}})]
      (let [system (ig/init config)
            ws-registry (:samuraibff/ws-registry system)]
        (try
          (registry/ensure-session! ws-registry tenant-a session-id {})
          (doseq [path ["/ws/events" "/ws/audio"]]
            (is (= {:status 403 :body {:ok false :message "unknown-session"}}
                   (handshake-response port path session-id "b"))
                "The token authenticates; the session is outside the caller's tenant"))
          (is (= #{"b"} (set @verified)))
          (is (>= (count @verified) 2))
          (finally (ig/halt! system)))))))

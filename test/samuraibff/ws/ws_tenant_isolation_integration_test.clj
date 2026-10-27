(ns samuraibff.ws.ws-tenant-isolation-integration-test
  "Prove authenticated cross-tenant WS requests fail ownership checks before upgrade.

  WebSockets resolve the principal (issuer + subject, tenant claim) and the
  session access from the database before upgrade. This PR-gate test stays
  Docker-free: `samuraibff.test-support.principal-store` serves the principal
  and session rows while the production resolution and policy code runs.
  The stubbed verifier maps each token to [subject tenant-claim]; the
  \"forged\" token presents the owner's subject with another tenant's claim."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.config]
            [samuraibff.grpc.client]
            [samuraibff.http.router]
            [samuraibff.http.server]
            [samuraibff.test-support.principal-store :as store]
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
        issuer "http://example.invalid/issuer"
        tenant-a (UUID/randomUUID)
        tenant-b (UUID/randomUUID)
        owner-a (store/principal-row {:tenant-id tenant-a :external-id "owner-a" :issuer issuer})
        colleague-a (store/principal-row {:tenant-id tenant-a :external-id "colleague-a" :issuer issuer})
        admin-b (store/principal-row {:tenant-id tenant-b :external-id "admin-b" :issuer issuer :role :admin})
        tokens {"b" ["admin-b" tenant-b]
                "colleague" ["colleague-a" tenant-a]
                "forged" ["owner-a" tenant-b]}
        session-id (str (UUID/randomUUID))
        store* (atom {:principals [owner-a colleague-a admin-b]})
        verified (atom [])
        config {:samuraibff/config {:env :test
                                    :http {:host "127.0.0.1" :port port}
                                    :auth {:required? true
                                           :issuer issuer
                                           :audience "bff-web"}
                                    :grpc {:rtservice-addr "localhost:50052"}}
                :samuraibff/grpc-client {:config (ig/ref :samuraibff/config)}
                :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)}
                :samuraibff/router {:config (ig/ref :samuraibff/config)
                                    :db {:ds :principal-store}
                                    :ws-registry (ig/ref :samuraibff/ws-registry)
                                    :grpc (ig/ref :samuraibff/grpc-client)}
                :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                         :handler (ig/ref :samuraibff/router)}}]
    (store/add-session! store* session-id owner-a)
    (with-redefs [oidc/verify-token (fn [_ token]
                                      (swap! verified conj token)
                                      (let [[sub tenant] (get tokens token)]
                                        {:sub sub :expires-at-ms (+ 60000 (System/currentTimeMillis))
                                         :raw {:tenant_id (str tenant)}}))]
      (store/with-store
        store*
        (fn []
          (let [system (ig/init config)
                ws-registry (:samuraibff/ws-registry system)]
            (try
              (registry/ensure-session! ws-registry (str tenant-a) session-id {})
              (doseq [path ["/ws/events" "/ws/audio"]]
                (is (= {:status 403 :body {:ok false :message "unknown-session"}}
                       (handshake-response port path session-id "b"))
                    "The token authenticates; the session is outside the caller's tenant")
                (testing "a same-tenant non-owner cannot open a private session"
                  (is (= {:status 403 :body {:ok false :message "unknown-session"}}
                         (handshake-response port path session-id "colleague"))))
                (testing "a forged tenant claim does not resolve the owner's principal"
                  (is (= {:status 403 :body {:ok false :message "access-not-provisioned"}}
                         (handshake-response port path session-id "forged")))))
              (is (= #{"b" "colleague" "forged"} (set @verified)))
              (is (>= (count @verified) 6))
              (finally (ig/halt! system)))))))))

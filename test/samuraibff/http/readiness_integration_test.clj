(ns samuraibff.http.readiness-integration-test
  "Integration test for HA-friendly startup when Postgres is unavailable.

  Success criteria:
  - The Integrant system starts even if Postgres is down.
  - `/health` stays 200 (liveness).
  - `/ready` returns 503 when DB is unreachable (readiness).

  This test intentionally points JDBC at a local closed port so connections are
  refused quickly." 
  (:require
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [jsonista.core :as json]
    [org.httpkit.client :as http]
    ;; Ensure Integrant init methods are loaded.
    [samuraibff.config]
    [samuraibff.db.core]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry]))

(defn- free-port
  "Return an available local TCP port by binding ServerSocket(0)." 
  []
  (with-open [sock (java.net.ServerSocket. 0)]
    (.getLocalPort sock)))

(deftest starts-with-db-down-and_reports-not-ready
  (let [port (free-port)
        cfg {:samuraibff/config {:env :test
                                 :http {:host "127.0.0.1" :port port}
                                 ;; Closed local port: should refuse connections.
                                 :db {:jdbc-url "jdbc:postgresql://127.0.0.1:1/drsynth"
                                      :username "drsynth"
                                      :password "drsynth"
                                      :maximum-pool-size 2}
                                 ;; Closed local port: Kafka readiness should be down.
                                 :kafka {:bootstrap-servers "127.0.0.1:1"}
                                 ;; Closed local port: rtservice readiness should be down.
                                 :grpc {:rtservice-addr "127.0.0.1:1"}
                                 ;; Disable auth in this test so /health and /ready
                                 ;; are simple unauthenticated requests.
                                 :auth {:required? false}}

             :samuraibff/db {:config (ig/ref :samuraibff/config)}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :db (ig/ref :samuraibff/db)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc nil}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}
        sys (ig/init cfg)]
    (try
      (testing "system started"
        (is (contains? sys :samuraibff/http-server))
        (is (fn? (get-in sys [:samuraibff/http-server :server]))))

      (testing "liveness is OK"
        (let [resp @(http/get (format "http://127.0.0.1:%d/health" port) {:timeout 2000 :as :text})]
          (is (= 200 (:status resp)))))

      (testing "readiness is 503 when dependencies are unreachable"
        ;; Note: readiness may block until Hikari's connectionTimeout elapses
        ;; (configured in samuraibff.db.core). Give it enough time to return.
        (let [resp @(http/get (format "http://127.0.0.1:%d/ready" port) {:timeout 8000 :as :text})]
          (when-let [err (:error resp)]
            (is false (str "Unexpected HTTP client error calling /ready: " err)))
          (is (= 503 (:status resp)))
          (let [body (json/read-value (:body resp) (json/object-mapper {:decode-key-fn keyword}))]
            (is (= false (get-in body [:db :up?])))
            (is (= false (get-in body [:kafka :up?])))
            (is (= false (get-in body [:grpc :up?]))))))

      (finally
        (ig/halt! sys)))))

(ns samuraibff.http.readiness-integration-test
  "Integration tests for readiness and HA-friendly startup during outages.

  Success criteria:
  - The Integrant system starts even if Postgres is down.
  - `/health` stays 200 (liveness).
  - `/ready` returns 503 when DB is unreachable (readiness).
  - Realtime track outages do not affect readiness when DB and Kafka are up.

  This test intentionally points JDBC at a local closed port so connections are
  refused quickly."
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [org.httpkit.client :as http]
    ;; Ensure Integrant init methods are loaded.
   [samuraibff.config]
   [samuraibff.db.core]
   [samuraibff.grpc.client :as grpc.client]
   [samuraibff.http.router :as router]
   [samuraibff.http.server]
   [samuraibff.ws.registry]))

(deftest readiness-depends-on-db-and-kafka-not-realtime-tracks
  (with-open [kafka-socket (java.net.ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress))]
    (doseq [db-up? [true false]
            kafka-up? [true false]
            unavailable [#{} #{"nemotron"} #{"faster-whisper"} #{"faster-whisper" "nemotron"}]]
      (testing (str "DB=" db-up? ", Kafka=" kafka-up? ", unavailable tracks=" unavailable)
        (with-redefs [jdbc/execute-one! (fn [& _]
                                          (if db-up?
                                            {:ok 1}
                                            (throw (ex-info "DB unavailable" {}))))
                      grpc.client/get-capabilities
                      (fn [{:keys [id]} _timeout-ms]
                        (if (contains? unavailable id)
                          (throw (ex-info "Provider unavailable" {}))
                          {:provider-profile-id id}))]
          (let [handler (router/create-router
                         {:config {:auth {:required? false}
                                   :kafka {:bootstrap-servers
                                           (str "127.0.0.1:" (if kafka-up? (.getLocalPort kafka-socket) 1))}}
                          :db {:ds ::datasource}
                          :grpc {:tracks [{:id "faster-whisper"} {:id "nemotron"}]}})
                response (handler {:request-method :get :uri "/ready"})
                body (json/read-value (:body response) (json/object-mapper {:decode-key-fn keyword}))
                ready? (and db-up? kafka-up?)]
            (is (= (if ready? 200 503) (:status response)))
            (is (= (if ready? "ok" "degraded") (:status body)))
            (is (= db-up? (get-in body [:db :up?])))
            (is (= kafka-up? (get-in body [:kafka :up?])))
            (is (= (empty? unavailable) (get-in body [:grpc :up?])))
            (is (= 200 (:status (handler {:request-method :get :uri "/health"}))))))))))

(defn- free-port
  "Return an available local TCP port by binding ServerSocket(0)."
  []
  (with-open [sock (java.net.ServerSocket. 0 0 (java.net.InetAddress/getLoopbackAddress))]
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

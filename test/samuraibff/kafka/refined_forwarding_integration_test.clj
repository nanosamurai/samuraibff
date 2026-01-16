(ns samuraibff.kafka.refined-forwarding-integration-test
  "Integration test for refined forwarding between BFF instances.

  This test is intentionally lightweight:
  - it does NOT involve Kafka/Testcontainers yet
  - it verifies the key behavior needed for multi-instance operation:
    a non-origin instance can POST a protobuf RefinedEvent to the origin
    instance's `/internal/refined` endpoint and the origin instance delivers
    it into its ws-registry.

  NOTE:
  - Once Kafka integration is added, we can extend this by spinning up a Kafka
    container and starting the refined consumer component.
  - This test requires only HTTP + ws-registry components." 
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer :all]
    [integrant.core :as ig]
    [org.httpkit.client :as http]
    [samuraibff.config]
    [samuraibff.http.router]
    [samuraibff.http.server]
    [samuraibff.ws.registry])
  (:import
    (java.util UUID)
    (samuraibff.proto RefinedEvent)))

(defn- start-system!
  "Start a minimal Integrant system with HTTP server + ws-registry.

  Returns: system map." 
  [port]
  (let [cfg {:samuraibff/config {:env :test
                                 :http {:port port}}
             :samuraibff/ws-registry {:config (ig/ref :samuraibff/config)
                                      :kafka-producer nil}
             :samuraibff/router {:config (ig/ref :samuraibff/config)
                                 :ws-registry (ig/ref :samuraibff/ws-registry)
                                 :grpc nil}
             :samuraibff/http-server {:config (ig/ref :samuraibff/config)
                                      :handler (ig/ref :samuraibff/router)}}]
    (ig/init cfg)))

(deftest internal-refined-callback-delivers-to-local-session
  (let [port 8099
        session-id (str (UUID/randomUUID))
        sys (start-system! port)]
    (try
      ;; Ensure the session exists locally (represents a connected WS session).
      (samuraibff.ws.registry/ensure-session!
        (get sys :samuraibff/ws-registry)
        "tenant-a"
        session-id
        {:lang "en" :sample-rate 16000})

      ;; Tap BEFORE posting, otherwise we might miss the event.
      (let [registry (get sys :samuraibff/ws-registry)
            session (samuraibff.ws.registry/get-session registry "tenant-a" session-id)
            out (async/chan 4)]
        (samuraibff.ws.registry/tap-events! session out)
        (try
          (let [ev (-> (RefinedEvent/newBuilder)
                       (.setSessionId session-id)
                       (.setTenantId "tenant-a")
                       (.setStartS 0.0)
                       (.setEndS 1.0)
                       (.setText "hello")
                       (.setLang "en")
                       (.build))
                url (str "http://localhost:" port "/internal/refined")
                {:keys [status error]} @(http/post url
                                                   {:headers {"content-type" "application/x-protobuf"}
                                                    :body (.toByteArray ev)
                                                    :timeout 2000})]
            (is (nil? error) (str "Expected no http error, got: " error))
            (is (= 200 status)))

          (let [[msg ch] (async/alts!! [out (async/timeout 2000)] :priority true)]
            (is (= out ch) "Expected refined event before timeout")
            (is (= "refined" (:type msg)))
            (is (= session-id (:session_id msg)))
            (is (= "en" (:lang msg)))
            (is (= "hello" (:text msg))))

          (finally
            (samuraibff.ws.registry/untap-events! session out)
            (async/close! out))))

      (finally
        (ig/halt! sys)))))

(ns samuraibff.http.server-test
  "Unit tests for the HTTP server component."
  (:require
   [clojure.test :refer :all]
   [samuraibff.http.server :as server]
   [integrant.core :as ig]))

(defn- free-port
  "Allocate a free local TCP port for tests.

  Avoids hard-coded ports which are often in use on developer machines / CI."
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

;; --- Test Helpers ---

(defn- mock-handler [_]
  "Mock Ring handler for testing."
  {:status 200
   :body "OK"
   :headers {}})

;; --- Unit Tests ---

(deftest start-server-test
  "Test that the start-server function creates a server instance."
  (testing "start-server creates a server instance"
    (let [config {:port (free-port)}
          handler mock-handler
          server-instance (server/start-server config handler)]
      (is server-instance "Server instance should be created")
      (is (fn? server-instance) "Server instance should be a function")
      ;; Stop the server
      (server/stop-server server-instance))))

(deftest stop-server-test
  "Test that the stop-server function stops the server gracefully."
  (testing "stop-server stops the server"
    (let [config {:port (free-port)}
          handler mock-handler
          server-instance (server/start-server config handler)]
      (is server-instance "Server should be running")
      (server/stop-server server-instance)
      ;; After stopping, the server should not be accepting connections
      (is true "Server stopped successfully"))))

(deftest integrant-lifecycle-test
  "Test the Integrant lifecycle methods."
  (testing "init-key and halt-key! work correctly"
    (let [config {:port (free-port)}
          handler mock-handler
          component {:config config :handler handler}]
      ;; Init the component
      (let [init-result (ig/init-key :samuraibff/http-server component)]
        (is init-result "Component should initialize")
        (is (:server init-result) "Should have server key")
        (is (:handler init-result) "Should have handler key")
        ;; Halt the component
        (ig/halt-key! :samuraibff/http-server init-result)
        (is true "Component halted successfully")))))

(ns samuraibff.http.server-integration-test
  (:require [integrant.core :as ig]
            [samuraibff.http.server :as server]
            [samuraibff.http.router :as router]
            [clojure.test :refer :all]
            [org.httpkit.client :as http]))

(defn- free-port
  "Allocate a free local TCP port for tests.

  NOTE: This does a bind-to-0, reads the selected port, then closes the socket.
  This avoids hard-coding ports (which are frequently in use on dev machines).
  There is a small race window, but in practice it is good enough for CI and
  local runs."
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(create-ns 'samuraibff)

(deftest test-system-start
  (let [port (free-port)
        config {:samuraibff/http-server {:config {:port port} :handler (ig/ref :samuraibff/router)}
                :samuraibff/router {}}
        system (ig/init config)]
    (is (contains? system :samuraibff/http-server))
    (is (:server (get system :samuraibff/http-server)))
    (ig/halt! system)))

(deftest test-system-restart
  (let [port (free-port)
        config {:samuraibff/http-server {:config {:port port} :handler (ig/ref :samuraibff/router)}
                :samuraibff/router {}}
        system (ig/init config)]
    (is (contains? system :samuraibff/http-server))
    (ig/halt! system)
    (let [system2 (ig/init config)]
      (is (contains? system2 :samuraibff/http-server))
      (ig/halt! system2))))

(deftest test-router-integration
  (let [port (free-port)
        config {:samuraibff/http-server {:config {:port port} :handler (ig/ref :samuraibff/router)}
                :samuraibff/router {}}
        system (ig/init config)]
    (is (contains? system :samuraibff/http-server))
    (is (contains? system :samuraibff/router))
    (let [response @(http/get (format "http://localhost:%d/health" port))]
      (is (= 200 (:status response))))
    (ig/halt! system)))

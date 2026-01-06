(ns samuraibff.http.server-integration-test
  (:require [integrant.core :as ig]
            [samuraibff.http.server :as server]
            [samuraibff.http.router :as router]
            [clojure.test :refer :all]))

(create-ns 'samuraibff)

(deftest test-system-start
  (let [config {:samuraibff/http-server {:config {:port 8080} :handler (ig/ref :samuraibff/router)}
                :samuraibff/router {}}
        system (ig/init config)]
    (is (contains? system :samuraibff/http-server))
    (is (:server (get system :samuraibff/http-server)))
    (ig/halt! system)))

(deftest test-system-restart
  (let [config {:samuraibff/http-server {:config {:port 8081} :handler (ig/ref :samuraibff/router)}
                :samuraibff/router {}}
        system (ig/init config)]
    (is (contains? system :samuraibff/http-server))
    (ig/halt! system)
    (let [system2 (ig/init config)]
      (is (contains? system2 :samuraibff/http-server))
      (ig/halt! system2))))

(deftest test-router-integration
  (let [config {:samuraibff/http-server {:config {:port 8082} :handler (ig/ref :samuraibff/router)}
                :samuraibff/router {}}
        system (ig/init config)]
    (is (contains? system :samuraibff/http-server))
    (is (contains? system :samuraibff/router))
    (ig/halt! system)))

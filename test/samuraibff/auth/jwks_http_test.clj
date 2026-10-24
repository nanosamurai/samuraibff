(ns samuraibff.auth.jwks-http-test
  "Exercise metadata transport limits against an ephemeral loopback server."
  (:require [clojure.test :refer [deftest is]]
            [org.httpkit.server :as http]
            [samuraibff.auth.jwks :as jwks]))

(defn- rejected-fetch?
  "Check that remote errors do not retain response text or nested exceptions."
  [url]
  (try
    (jwks/fetch-json! url)
    false
    (catch clojure.lang.ExceptionInfo e
      (and (= "OIDC metadata unavailable" (.getMessage e))
           (empty? (ex-data e)) (nil? (.getCause e))))))

(deftest metadata-http-is-bounded-and-does-not-follow-redirects
  (let [redirect-targets (atom 0)
        server (http/run-server
                (fn [request]
                  (case (:uri request)
                    "/ok" {:status 200 :body "{\"ok\":true}"}
                    "/large" {:status 200 :body (apply str (repeat 262145 "x"))}
                    "/redirect" {:status 302 :headers {"location" "/target"}}
                    "/target" (do (swap! redirect-targets inc) {:status 200 :body "{}"})
                    "/slow" (http/as-channel request
                                             {:on-open #(http/send! % {:status 200 :body "{"} false)})
                    {:status 500 :body "response must not escape"}))
                {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        base (str "http://127.0.0.1:" (http/server-port server))]
    (try
      (is (= {:ok true} (jwks/fetch-json! (str base "/ok"))))
      (doseq [path ["/large" "/redirect" "/error"]]
        (is (rejected-fetch? (str base path))))
      (is (zero? @redirect-targets))
      (let [started (System/nanoTime)]
        (is (rejected-fetch? (str base "/slow")))
        (is (< (/ (- (System/nanoTime) started) 1000000) 7000)
            "The total deadline also applies after response headers arrive"))
      (finally @(http/server-stop! server)))))

(ns samuraibff.http.feature-gates-test
  "HTTP route tests for CE workflow/webhook feature gates."
  (:require
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [samuraibff.http.router :as http.router]))

(defn- request
  [handler method uri]
  (handler {:request-method method
            :uri uri
            :headers {}
            :remote-addr "127.0.0.1"
            :auth/tenant-id "00000000-0000-0000-0000-000000000000"}))

(defn- parse-json-body
  [resp]
  (let [body (:body resp)]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (cheshire/parse-string body true)
      (instance? java.io.InputStream body) (cheshire/parse-stream (io/reader body) true)
      :else (cheshire/parse-string (str body) true))))

(deftest ce-mode-gates-workflow-webhook-api-routes
  (testing "default CE mode returns a clear not-enabled response"
    (let [handler (http.router/create-router {:config {:auth {:required? false}}
                                              :db {:ds nil}})]
      (doseq [uri ["/api/webhooks"
                   "/api/webhooks/defaults"
                   "/api/workflows"
                   "/api/workflows/defaults"]]
        (let [resp (request handler :get uri)
              body (parse-json-body resp)]
          (is (= 403 (:status resp)) uri)
          (is (= false (:ok body)) uri)
          (is (= "feature-not-enabled" (:message body)) uri))))))

(deftest commercial-mode-leaves-routes-enabled
  (testing "SAMURAIBFF_CE_MODE=false compatibility reaches underlying handlers"
    (let [handler (http.router/create-router {:config {:auth {:required? false}
                                                       :features {:ce-mode? false}}
                                              :db {:ds nil}})]
      (doseq [uri ["/api/webhooks"
                   "/api/webhooks/defaults"
                   "/api/workflows"
                   "/api/workflows/defaults"]]
        (let [resp (request handler :get uri)
              body (parse-json-body resp)]
          (is (= 403 (:status resp)) uri)
          (is (= "missing-tenant-id" (:message body)) uri)
          (is (not= "feature-not-enabled" (:message body)) uri))))))

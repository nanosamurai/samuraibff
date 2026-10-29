(ns samuraibff.http.feature-gates-test
  "HTTP route tests for CE workflow/webhook feature gates.

  `/api/*` resolves the request principal from Postgres, so the router runs
  against a Postgres testcontainer with the guest principal seeded
  (authentication disabled)."
  (:require
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [samuraibff.http.router :as http.router]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg]))

(def ^:private gated-uris
  ["/api/webhooks"
   "/api/webhooks/defaults"
   "/api/workflows"
   "/api/workflows/defaults"])

(defn- request
  [handler method uri]
  (handler {:request-method method
            :uri uri
            :headers {}
            :remote-addr "127.0.0.1"}))

(defn- parse-json-body
  [resp]
  (let [body (:body resp)]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (cheshire/parse-string body true)
      (instance? java.io.InputStream body) (cheshire/parse-stream (io/reader body) true)
      :else (cheshire/parse-string (str body) true))))

(defn- with-guest-db
  "Call f with a datasource whose schema and guest principal are ready."
  [f]
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")]
      (tc.pg/apply-schema! ds)
      (tp/insert-guest! ds)
      (f ds))))

(deftest ce-mode-gates-workflow-webhook-api-routes
  (with-guest-db
    (fn [ds]
      (testing "default CE mode returns a clear not-enabled response"
        (let [handler (http.router/create-router {:config {:auth {:required? false}}
                                                  :db {:ds ds}})]
          (doseq [uri gated-uris]
            (let [resp (request handler :get uri)
                  body (parse-json-body resp)]
              (is (= 403 (:status resp)) uri)
              (is (= false (:ok body)) uri)
              (is (= "feature-not-enabled" (:message body)) uri))))))))

(deftest commercial-mode-leaves-routes-enabled
  (with-guest-db
    (fn [ds]
      (testing "SAMURAIBFF_CE_MODE=false compatibility reaches underlying handlers"
        (let [handler (http.router/create-router {:config {:auth {:required? false}
                                                           :features {:ce-mode? false}}
                                                  :db {:ds ds}})]
          (doseq [uri gated-uris]
            (let [resp (request handler :get uri)
                  body (parse-json-body resp)]
              (is (= 200 (:status resp)) uri)
              (is (= true (:ok body)) uri)
              (is (not= "feature-not-enabled" (:message body)) uri)))))
      (testing "without a provisioned principal the gated routes stay closed"
        (let [handler (http.router/create-router {:config {:auth {:required? false
                                                                  :guest-tenant-id "11111111-1111-1111-1111-111111111111"}
                                                           :features {:ce-mode? false}}
                                                  :db {:ds ds}})
              resp (request handler :get "/api/webhooks")]
          (is (= 403 (:status resp)))
          (is (= "access-not-provisioned" (:message (parse-json-body resp)))))))))

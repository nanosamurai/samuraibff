(ns samuraibff.http.feature-gates-test
  "HTTP route tests for workflow/webhook capability gates.

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

(deftest ce-workflows-flag-enables-workflows-only
  (with-guest-db
    (fn [ds]
      (let [run-uri (str "/api/recordings/" (random-uuid) "/workflows/" (random-uuid) "/runs")
            ce (http.router/create-router {:config {:auth {:required? false}} :db {:ds ds}})
            enabled (http.router/create-router {:config {:auth {:required? false}
                                                         :features {:workflows-enabled? true}}
                                                :db {:ds ds}})]
        (testing "default CE mode gates on-demand runs"
          (is (= "feature-not-enabled" (:message (parse-json-body (request ce :post run-uri))))))
        (testing "SAMURAIBFF_WORKFLOWS_ENABLED opens workflow routes, not webhook routes"
          (doseq [uri ["/api/workflows" "/api/workflows/defaults"]]
            (is (= 200 (:status (request enabled :get uri))) uri))
          (doseq [uri ["/api/webhooks" "/api/webhooks/defaults"]]
            (is (= "feature-not-enabled" (:message (parse-json-body (request enabled :get uri)))) uri))
          (let [resp (request enabled :post run-uri)]
            (is (= 404 (:status resp)))
            (is (= "not-found" (:message (parse-json-body resp))))))))))

(deftest each-flag-opens-only-its-routes
  (with-guest-db
    (fn [ds]
      (let [run-uri (str "/api/recordings/" (random-uuid) "/workflows/" (random-uuid) "/runs")
            router (fn [features]
                     (http.router/create-router {:config {:auth {:required? false}
                                                          :features features}
                                                 :db {:ds ds}}))
            gated? (fn [handler method uri]
                     (= "feature-not-enabled" (:message (parse-json-body (request handler method uri)))))]
        (testing "SAMURAIBFF_WORKFLOW_TRIGGERS_ENABLED opens workflow definitions, not runs or webhooks"
          (let [handler (router {:workflow-triggers-enabled? true})]
            (doseq [uri ["/api/workflows" "/api/workflows/defaults"]]
              (is (= 200 (:status (request handler :get uri))) uri))
            (is (gated? handler :post run-uri))
            (doseq [uri ["/api/webhooks" "/api/webhooks/defaults"]]
              (is (gated? handler :get uri) uri))))
        (testing "SAMURAIBFF_WEBHOOKS_ENABLED opens webhook routes only"
          (let [handler (router {:webhooks-enabled? true})]
            (doseq [uri ["/api/webhooks" "/api/webhooks/defaults"]]
              (is (= 200 (:status (request handler :get uri))) uri))
            (doseq [uri ["/api/workflows" "/api/workflows/defaults"]]
              (is (gated? handler :get uri) uri))
            (is (gated? handler :post run-uri))))
        (testing "an explicit false closes routes despite SAMURAIBFF_CE_MODE=false"
          (let [handler (router {:ce-mode? false :webhooks-enabled? false})]
            (is (gated? handler :get "/api/webhooks"))
            (is (= 200 (:status (request handler :get "/api/workflows"))))))))))

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

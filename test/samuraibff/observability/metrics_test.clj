;; Copyright (c) samuraibff contributors.
(ns samuraibff.observability.metrics-test
  (:require
    [clojure.test :refer :all]
    [samuraibff.http.router :as http.router]))

(defn- handler
  []
  (http.router/create-router
    {:config {:env :test
              ;; keep auth disabled so internal endpoint is easy to call in tests
              :auth {:required? false}}
     :db {:ds nil}
     :grpc nil
     :ws-registry nil
     :keycloak-admin nil}))

(deftest metrics-endpoint-exposes-prometheus-format
  (testing "GET /internal/metrics returns Prometheus exposition"
    (let [h (handler)
          ;; also call /health so we know our http metrics are incremented
          _ (h {:request-method :get :uri "/health" :headers {}})
          resp (h {:request-method :get :uri "/internal/metrics" :headers {}})]
      (is (= 200 (:status resp)))
      (is (re-find #"(?m)^# HELP samuraibff_http_requests_total" (str (:body resp))))
      ;; We should have at least one observation for /health
      (is (re-find #"samuraibff_http_requests_total\{" (str (:body resp)))))))

;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.openapi-visibility-test
  "Integration-ish tests for OpenAPI visibility rules.

  Goal:
  - `/openapi/public.json` is always reachable and contains only /auth/*
  - `/openapi/private.json` is protected by auth middleware and rejects missing token

  These tests avoid Keycloak/Testcontainers by validating only the *missing token*
  path. Full auth + tenant behavior is covered by the heavy Keycloak tests.
  "
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer :all]
    [clojure.java.io :as io]
    [samuraibff.http.router :as http.router]))

(defn- parse-json
  [body]
  (cond
    (string? body)
    (cheshire/parse-string body true)

    ;; Muuntaja can encode bodies as streams.
    (instance? java.io.InputStream body)
    (cheshire/parse-stream (io/reader body) true)

    :else
    (cheshire/parse-string (str body) true)))

(defn- handler
  "Create a router handler with auth required enabled.

  We don't start an HTTP server; we call the Ring handler directly." 
  []
  (http.router/create-router
    {:config {:env :test
              :auth {:required? true
                     :cookie-name "access_token"}}
     ;; keep deps present but unused for these doc endpoints
     :db {:ds nil}
     :grpc nil
     :ws-registry nil
     :keycloak-admin nil}))

(deftest public-openapi-contains-auth-only
  (testing "public OpenAPI is reachable without auth and includes /auth/*"
    (let [h (handler)
          resp (h {:request-method :get
                   :uri "/openapi/public.json"
                   :headers {}})]
      (is (= 200 (:status resp)))
      (let [spec (parse-json (:body resp))
            paths (->> (keys (:paths spec))
                       (map (fn [k] (str "/" (name k))))
                       set)]
        (is (contains? paths "/auth/login"))
        (is (contains? paths "/auth/callback"))
        (is (contains? paths "/auth/logout"))
        (is (not (contains? paths "/api/recordings")))))))

(deftest private-openapi-rejects-missing-token
  (testing "private OpenAPI is protected by auth middleware"
    (let [h (handler)
          resp (h {:request-method :get
                   :uri "/openapi/private.json"
                   :headers {}})]
      (is (= 403 (:status resp)))
      (is (= "missing-token" (:message (parse-json (:body resp))))))))

(deftest swagger-ui-public-is-accessible
  (testing "Swagger UI public index is reachable (outside router mounting)"
    (let [h (handler)
          resp (h {:request-method :get
                   :uri "/docs/public/"
                   :headers {}})]
      (is (= 200 (:status resp)))
      (is (some? (:body resp))))))

(deftest swagger-ui-private-is-protected
  (testing "Swagger UI private index is protected by auth middleware"
    (let [h (handler)
          resp (h {:request-method :get
                   :uri "/docs/private/"
                   :headers {}})]
      (is (= 403 (:status resp))))))

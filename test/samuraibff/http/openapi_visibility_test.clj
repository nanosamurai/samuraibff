;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.openapi-visibility-test
  "Integration-ish tests for OpenAPI visibility rules.

  Goal:
  - `/openapi.json` is always reachable (currently public) and contains all HTTP endpoints
  - `/docs/` serves Swagger UI assets without interfering with app static assets

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
  (testing "OpenAPI is reachable without auth and includes auth + api endpoints"
    (let [h (handler)
          resp (h {:request-method :get
                   :uri "/openapi.json"
                   :headers {}})]
      (is (= 200 (:status resp)))
      (let [spec (parse-json (:body resp))
            paths (->> (keys (:paths spec))
                       (map (fn [k] (str "/" (name k))))
                       set)]
        (is (contains? paths "/auth/login"))
        (is (contains? paths "/auth/callback"))
        (is (contains? paths "/auth/logout"))
        (is (contains? paths "/api/recordings"))))))

(deftest docs-do-not-break-static-assets
  (testing "Swagger UI assets are reachable and do not block /js/main.js"
    (let [h (handler)
          css-resp (h {:request-method :get :uri "/docs/swagger-ui.css" :headers {}})
          js-resp (h {:request-method :get :uri "/js/main.js" :headers {}})]
      (is (= 200 (:status css-resp)))
      ;; NOTE: we don't validate JS content, only that it is not intercepted.
      ;; The handler should return nil for /js/main.js so that the resource handler can serve it.
      (is (= 200 (:status js-resp))))))

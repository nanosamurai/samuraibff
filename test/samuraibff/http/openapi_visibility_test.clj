;; Copyright (c) samuraibff contributors.
(ns samuraibff.http.openapi-visibility-test
  "Integration-ish tests for OpenAPI visibility rules.

  Goal:
  - `/openapi.json` is always reachable and contains only customer-facing HTTP endpoints
    (`/auth/*` and `/api/*`).
  - `/docs/` serves Swagger UI assets without interfering with app static assets.

  These tests avoid Keycloak/Testcontainers by validating only the *missing token*
  path. Full auth + tenant behavior is covered by the heavy Keycloak tests." 
  (:require
    [cheshire.core :as cheshire]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
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

(defn- normalize-openapi-path-key
  "Normalize OpenAPI path key into a plain string like `/api/me`.

  Depending on JSON parsing and OpenAPI generation, path keys can be strings,
  keywords, or other types." 
  [k]
  (cond
    (string? k) k
    (keyword? k) (str "/" (name k))
    :else (str k)))

(deftest openapi-contains-only-api-and-auth
  (testing "OpenAPI is reachable without auth and contains only /auth + /api endpoints"
    (let [h (handler)
          resp (h {:request-method :get
                   :uri "/openapi.json"
                   :headers {}})]
      (is (= 200 (:status resp)))
      (let [spec (parse-json (:body resp))
            paths (->> (keys (:paths spec))
                       (map normalize-openapi-path-key)
                       set)]
        ;; Must contain some known customer endpoints.
        (is (contains? paths "/auth/login"))
        (is (contains? paths "/auth/callback"))
        (is (contains? paths "/auth/logout"))
        (is (contains? paths "/api/recordings"))
        (is (contains? paths "/api/recordings/{session_id}"))
        (is (contains? paths "/api/me"))

        ;; Must not contain UI or internal endpoints.
        (is (not (contains? paths "/")))
        (is (not (contains? paths "/recordings")))
        (is (not (contains? paths "/live")))
        (is (not (contains? paths "/internal/refined")))
        (is (not (contains? paths "/health")))
        (is (not (contains? paths "/ready")))))))

(deftest docs-do-not-break-static-assets
  (testing "Swagger UI and application static assets are reachable"
    (let [h (handler)
          css-resp (h {:request-method :get :uri "/docs/swagger-ui.css" :headers {}})
          js-resp (h {:request-method :get :uri "/js/main.js" :headers {}})
          favicon-resp (h {:request-method :get
                           :uri "/img/nanosamurai_logo_finished_shoulders.svg"
                           :headers {}})
          index-resp (h {:request-method :get :uri "/" :headers {}})
          index-html (slurp (:body index-resp))]
      (is (= 200 (:status css-resp)))
      ;; NOTE: we don't validate JS content, only that it is not intercepted.
      ;; The handler should return nil for /js/main.js so that the resource handler can serve it.
      (is (= 200 (:status js-resp)))
      (is (= 200 (:status favicon-resp)))
      (is (= "image/svg+xml" (get-in favicon-resp [:headers "Content-Type"])))
      (is (= 200 (:status index-resp)))
      (is (= 1
             (count
              (re-seq #"(?s)<link(?=[^>]*\bid=\"app-favicon\")(?=[^>]*\brel=\"icon\")(?=[^>]*\bhref=\"/img/nanosamurai_logo_finished_shoulders\.svg\")[^>]*/>"
                      index-html))))
      (is (= 1
             (count
              (re-seq #"document\.getElementById\('app-favicon'\)\.href = 'img/nanosamurai_logo_finished_shoulders\.svg';"
                      index-html)))))))

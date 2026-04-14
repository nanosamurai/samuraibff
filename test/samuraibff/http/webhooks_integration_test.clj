(ns samuraibff.http.webhooks-integration-test
  "HTTP integration tests for `/api/webhooks` handlers.

  These tests exist primarily to ensure Ring responses conform to Malli schemas
  (Reitit response coercion) so we don't ship endpoints that return 500 while
  carrying a seemingly valid JSON payload.
  "
  (:require
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.http.router :as http.router]
   [samuraibff.http.webhooks :as http.webhooks]
   [samuraibff.schemas :as schemas]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(defn- router-handler
  "Build a full Ring router handler with auth disabled.

  We test the router path-param coercion here (the exact failure you hit in the browser)
  and we still get Malli response coercion on responses." 
  [ds]
  (http.router/create-router
    {:config {:env :test
              :auth {:required? true
                     :cookie-name "access_token"}}
     :db {:ds ds}
     :grpc nil
     :ws-registry nil
     :kafka-producer nil
     :secrets {:store nil}
     :keycloak-admin nil}))

(defn- parse-json-body
  "Parse a Ring response body (map/string/InputStream) into a Clojure map." 
  [resp]
  (let [body (:body resp)]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (cheshire/parse-string body true)
      (instance? java.io.InputStream body) (cheshire/parse-stream (io/reader body) true)
      :else (cheshire/parse-string (str body) true))))

(deftest list-webhooks-response-conforms-to-schema-integration-test
  (testing "GET /api/webhooks response body conforms to schemas/WebhooksListResponse (static_headers is a map)"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-uuid (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-uuid "Guest"])

            ;; Insert one webhook row with empty jsonb map for static_headers.
            webhook-id (UUID/randomUUID)
            _ (jdbc/execute!
               ds
               ["INSERT INTO webhooks (id, tenant_id, name, url, enabled, auth_type, static_headers)
                 VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)"
                webhook-id tenant-uuid "cpt-hook" "http://127.0.0.1:8989" true "none" "{}"])
            _ (jdbc/execute!
               ds
               ["INSERT INTO webhook_subscriptions (tenant_id, webhook_id, event_type)
                 VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)"
                tenant-uuid webhook-id "recording.finished"
                tenant-uuid webhook-id "transcript.final.ready"
                tenant-uuid webhook-id "transcript.refined.segment"])

            handler (http.webhooks/list-webhooks-handler {:db {:ds ds}})
            resp (handler {:auth/tenant-id (str tenant-uuid)})
            body (:body resp)]
        (is (= 200 (:status resp)))
        ;; critical: response must satisfy the schema, or the real router would throw
        ;; (=> 500 response-coercion).
        (is (= body (schemas/validate! schemas/WebhooksListResponse body)))
        (is (= (str tenant-uuid) (:tenant_id body)))
        (is (= 1 (count (:items body))))
        (is (= ["recording.finished"
                "transcript.final.ready"
                "transcript.refined.segment"]
               (get-in body [:items 0 :subscriptions])))
        (is (= {} (get-in body [:items 0 :static_headers])))))))

(deftest put-webhook-route-path-param-is-id-integration-test
  (testing "PUT /api/webhooks/{id} uses :id path-param (route regex constraint must not rename param key)"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-uuid (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-uuid "Guest"])

            webhook-id (UUID/randomUUID)
            _ (jdbc/execute!
               ds
               ["INSERT INTO webhooks (id, tenant_id, name, url, enabled, auth_type, static_headers)
                 VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)"
                webhook-id tenant-uuid "cpt-hook" "http://127.0.0.1:8989" true "none" "{}"])

            handler (router-handler ds)
            body {:name "cpt-hook"
                  :url "http://127.0.0.1:8989"
                  :enabled true
                  :auth {:type "none"}
                  :subscriptions ["recording.finished"
                                  "transcript.final.ready"
                                  "transcript.refined.segment"]
                  :static_headers {}}
            resp (with-redefs [oidc/extract-token (fn [_config _req] "test-token")
                               oidc/verify-token (fn [_config _token] {:sub "u"})
                               oidc/extract-tenant-from-claims* (fn [_config _claims] (str tenant-uuid))]
                   (handler {:request-method :put
                             :uri (str "/api/webhooks/" (str webhook-id))
                             :headers {"content-type" "application/json"
                                       "authorization" "Bearer test-token"}
                             :body-params body}))
            resp-body (parse-json-body resp)]
        ;; If the route param is wrong, coercion fails and you get 400 with "missing required key".
        (is (= 200 (:status resp)))
        (is (= resp-body (schemas/validate! schemas/ApiOkResponse resp-body)))))))

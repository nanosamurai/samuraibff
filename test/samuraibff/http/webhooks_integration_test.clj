(ns samuraibff.http.webhooks-integration-test
  "HTTP integration tests for `/api/webhooks` handlers.

  These tests exist primarily to ensure Ring responses conform to Malli schemas
  (Reitit response coercion) so we don't ship endpoints that return 500 while
  carrying a seemingly valid JSON payload.
  "
  (:require
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [samuraibff.http.webhooks :as http.webhooks]
   [samuraibff.schemas :as schemas]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

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
        (is (= {} (get-in body [:items 0 :static_headers])))))))

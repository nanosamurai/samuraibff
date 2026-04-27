(ns samuraibff.http.webhook-delivery-outcomes-integration-test
  "Integration test for /api/sessions/:session_id/webhook-delivery-outcomes." 
  (:require
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [samuraibff.http.webhook-delivery-outcomes :as http.wh.outcomes]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.time Instant)
   (java.util UUID)))

(deftest list-webhook-delivery-outcomes-handler-tenant-scoped-test
  (tc.pg/with-postgres [pg]
    (let [jdbc-url (tc.pg/jdbc-url pg)
          ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
          tenant-b (UUID/fromString "00000000-0000-0000-0000-000000000001")
          _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?), (?, ?)" tenant-a "A" tenant-b "B"])
          session-a (UUID/randomUUID)
          _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key) VALUES (?, ?, ?)" session-a tenant-a (str session-a)])
          dispatch-id (UUID/randomUUID)
          _ (jdbc/execute! ds ["INSERT INTO webhook_delivery_outcomes (
                                id, created_at, tenant_id, session_id, webhook_id, dispatch_id,
                                event_id, event_type, attempt_no, status, http_status,
                                error_code, error_detail, latency_ms,
                                kafka_topic, kafka_partition, kafka_offset
                              ) VALUES (?, ?, ?, ?, 'wh-1', ?, NULL, 'recording.finished', 1, 'delivered', 200,
                                       NULL, NULL, NULL,
                                       't', 0, 0)"
                               (UUID/randomUUID) (Instant/parse "2026-01-01T00:00:00Z") tenant-a session-a dispatch-id])
          deps {:db {:ds ds}
                :config {:env :test}}
          handler (http.wh.outcomes/list-webhook-delivery-outcomes-handler deps)
          resp-ok (handler {:auth/tenant-id (str tenant-a)
                            :path-params {:session_id (str session-a)}})
          resp-cross (handler {:auth/tenant-id (str tenant-b)
                               :path-params {:session_id (str session-a)}})]
      (is (= 200 (:status resp-ok)))
      (is (= true (get-in resp-ok [:body :ok])))
      (is (= 1 (count (get-in resp-ok [:body :items]))))
      (is (= 200 (get-in resp-ok [:body :items 0 :http_status])))

      ;; Tenant scoping: session doesn't exist for tenant-b.
      (is (= 404 (:status resp-cross)))
      (is (= "not-found" (get-in resp-cross [:body :message]))))))

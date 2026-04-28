(ns samuraibff.db.webhook-delivery-outcomes-test
  "Integration-style tests for webhook delivery outcome DB queries."
  (:require
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [samuraibff.db.webhook-delivery-outcomes :as db.wh.outcomes]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.time Instant)
   (java.sql Timestamp)
   (java.util UUID)))

(defn- instant->timestamp
  "Convert a java.time.Instant (or nil) to java.sql.Timestamp.

  Inputs:
  - inst: java.time.Instant?

  Returns:
  - java.sql.Timestamp?"
  [inst]
  (when inst (Timestamp/from inst)))

(defn- insert-outcome!
  [ds {:keys [id created_at tenant_id session_id webhook_id dispatch_id event_type attempt_no status http_status]
       :or {id (UUID/randomUUID)}}]
  (jdbc/execute!
   ds
   ["INSERT INTO webhook_delivery_outcomes (
        id, created_at, tenant_id, session_id, webhook_id, dispatch_id,
        event_id, event_type, attempt_no, status, http_status,
        error_code, error_detail, latency_ms,
        kafka_topic, kafka_partition, kafka_offset
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    id
    (instant->timestamp created_at)
    tenant_id
    session_id
    webhook_id
    dispatch_id
    nil
    event_type
    (int attempt_no)
    status
    http_status
    nil
    nil
    nil
    "t"
    (int 0)
    (long 0)])
  id)

(deftest list-latest-outcomes-for-session-returns-latest-per-dispatch-test
  (tc.pg/with-postgres [pg]
    (let [jdbc-url (tc.pg/jdbc-url pg)
          ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)

          tenant-a (UUID/fromString "00000000-0000-0000-0000-000000000000")
          tenant-b (UUID/fromString "11111111-1111-1111-1111-111111111111")
          _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?), (?, ?)" tenant-a "A" tenant-b "B"])

          session-a (UUID/randomUUID)
          session-b (UUID/randomUUID)
          _ (jdbc/execute! ds ["INSERT INTO sessions (id, tenant_id, session_key) VALUES (?, ?, ?), (?, ?, ?)"
                               session-a tenant-a (str "sa-" session-a)
                               session-b tenant-b (str "sb-" session-b)])

          dispatch-1 (UUID/randomUUID)
          dispatch-2 (UUID/randomUUID)
          t0 (Instant/parse "2026-01-01T00:00:00Z")
          t1 (Instant/parse "2026-01-01T00:00:01Z")
          t2 (Instant/parse "2026-01-01T00:00:02Z")]

      ;; dispatch-1 has two attempts; the later one should be returned.
      (insert-outcome! ds {:created_at t0
                           :tenant_id tenant-a
                           :session_id session-a
                           :webhook_id "wh-1"
                           :dispatch_id dispatch-1
                           :event_type "recording.finished"
                           :attempt_no 1
                           :status "failed"
                           :http_status 500})
      (insert-outcome! ds {:created_at t1
                           :tenant_id tenant-a
                           :session_id session-a
                           :webhook_id "wh-1"
                           :dispatch_id dispatch-1
                           :event_type "recording.finished"
                           :attempt_no 2
                           :status "delivered"
                           :http_status 200})

      ;; dispatch-2 has one attempt.
      (insert-outcome! ds {:created_at t2
                           :tenant_id tenant-a
                           :session_id session-a
                           :webhook_id "wh-2"
                           :dispatch_id dispatch-2
                           :event_type "transcript.final.ready"
                           :attempt_no 1
                           :status "delivered"
                           :http_status 200})

      ;; other tenant/session should not leak.
      (insert-outcome! ds {:created_at t2
                           :tenant_id tenant-b
                           :session_id session-b
                           :webhook_id "wh-x"
                           :dispatch_id (UUID/randomUUID)
                           :event_type "recording.finished"
                           :attempt_no 1
                           :status "delivered"
                           :http_status 200})

      (let [rows (db.wh.outcomes/list-latest-outcomes-for-session ds tenant-a session-a {:limit 50})
            by-dispatch (into {} (map (juxt :dispatch_id identity) rows))
            row1 (get by-dispatch dispatch-1)
            row2 (get by-dispatch dispatch-2)]
        (is (= 2 (count rows)))
        (is (= 2 (:attempt_no row1)))
        (is (= 2 (:attempts_count row1)))
        (is (= "delivered" (:status row1)))
        (is (= 200 (:http_status row1)))
        (is (= 1 (:attempt_no row2)))
        (is (= 1 (:attempts_count row2)))))))

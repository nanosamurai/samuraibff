(ns samuraibff.db.webhooks-test
  "Integration-style tests for webhook DB functions.

  We specifically test cases that are easy to get wrong with SQL formatting
  (e.g. empty JSONB maps) and ensure tenant-scoped semantics."
  (:require
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [samuraibff.db.webhooks :as db.webhooks]
   [samuraibff.testcontainers.postgres :as tc.pg])
  (:import
   (java.util UUID)))

(deftest insert-webhook-allows-empty-static-headers-test
  (testing "insert-webhook! handles empty static_headers without SQL errors"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-id "Guest"])

            webhook-id (UUID/randomUUID)
            res (db.webhooks/insert-webhook!
                 ds
                 {:id webhook-id
                  :tenant-id tenant-id
                  :name "cpt-hook"
                  :url "http://127.0.0.1:8989"
                  :enabled true
                  :auth-type "none"
                  ;; this used to break honey.sql formatting ("()")
                  :static_headers {}})
            row (db.webhooks/find-webhook ds tenant-id webhook-id)]
        (is (= {:id webhook-id} res))
        (is (= webhook-id (:id row)))
        (is (= tenant-id (:tenant_id row)))
        ;; We store empty maps as '{}'::jsonb (stored as PGobject).
        (is (some? (:static_headers row)))
        (is (re-find #"\{\}" (str (:static_headers row))))))))

(deftest insert-webhook-persists-nonempty-static-headers-test
  (testing "insert-webhook! persists jsonb when static_headers is non-empty"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-id "Guest"])

            webhook-id (UUID/randomUUID)
            _ (db.webhooks/insert-webhook!
               ds
               {:id webhook-id
                :tenant-id tenant-id
                :name "cpt-hook"
                :url "http://127.0.0.1:8989"
                :enabled true
                :auth-type "none"
                :static_headers {"X-Test" "a"}})
            row (db.webhooks/find-webhook ds tenant-id webhook-id)]
        (is (= webhook-id (:id row)))
        ;; next.jdbc returns PGobject for jsonb
        (is (string? (some-> (:static_headers row) str)))
        (is (re-find #"X-Test" (str (:static_headers row))))))))

(deftest insert-webhook-with-extra-fields-keeps-tenant-id-uuid-test
  (testing "insert-webhook! with optional keys still binds tenant_id as UUID (no uuid/varchar mismatch)"
    (tc.pg/with-postgres [pg]
      (let [jdbc-url (tc.pg/jdbc-url pg)
            ds (tc.pg/datasource jdbc-url "drsynth" "drsynth")
            _ (tc.pg/apply-schema! ds)

            tenant-id (UUID/fromString "00000000-0000-0000-0000-000000000000")
            _ (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, ?)" tenant-id "Guest"])

            webhook-id (UUID/randomUUID)
            ;; Add extra optional keys to mimic the real HTTP handler (secret refs etc.)
            _ (db.webhooks/insert-webhook!
               ds
               {:id webhook-id
                :tenant-id tenant-id
                :name "cpt-hook"
                :url "http://127.0.0.1:8989"
                :enabled true
                :auth-type "none"
                :hmac_secret_ref nil
                :api_key_ref nil
                :oauth_client_secret_ref nil
                :oauth_token_url nil
                :oauth_client_id nil
                :oauth_scopes nil
                :api_key_header_name nil
                :api_key_prefix nil
                :static_headers {}})
            row (db.webhooks/find-webhook ds tenant-id webhook-id)]
        (is (= webhook-id (:id row)))
        (is (= tenant-id (:tenant_id row)))))))

(ns samuraibff.http.api-credentials-handler-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [jsonista.core :as json]
    [samuraibff.schemas :as schemas]
    [samuraibff.http.api-credentials :as http.api-creds]
    [samuraibff.util.uuid :as util.uuid])
  (:import
    (java.util UUID)))

(defn- ok-json
  [resp]
  (and (= 200 (:status resp))
       (or (string? (:body resp))
           (map? (:body resp))
           (instance? java.io.InputStream (:body resp)))))

(defn- parse-json-body
  [resp]
  (let [body (:body resp)
        mapper (json/object-mapper {:decode-key-fn keyword})]
    (cond
      (nil? body) nil
      (map? body) body
      (string? body) (json/read-value body mapper)
      (instance? java.io.InputStream body) (json/read-value body mapper)
      :else (json/read-value (str body) mapper))))

(deftest create-api-credential-missing-name
  (testing "missing name returns 400"
    (let [h ((http.api-creds/create-api-credential-handler
               {:db {:ds ::fake}
                :keycloak-admin ::fake})
             {:auth/tenant-id (str (UUID/randomUUID))
              :body-params {}})]
      (is (= 400 (:status h)))
      (is (= "missing-name" (:message (:body h)))))))

(deftest rotate-api-credential-invalid-id
  (testing "invalid uuid in path returns 400"
    (let [h ((http.api-creds/rotate-api-credential-handler
               {:db {:ds ::fake}
                :keycloak-admin ::fake})
             {:auth/tenant-id (str (UUID/randomUUID))
              :path-params {:id "not-a-uuid"}})]
      (is (= 400 (:status h)))
      (is (= "invalid-id" (:message (:body h)))))))

(deftest list-api-credentials-nil-revoked-at-remains-null
  (testing "revoked_at should be null (not \"nil\") when not revoked"
    ;; Patch the DB call via with-redefs so we control the returned row.
    (with-redefs [samuraibff.db.api-credentials/list-credentials
                  (fn [_ds _tenant]
                    [{:id (UUID/randomUUID)
                      :tenant_id (UUID/randomUUID)
                      :name "cred"
                      :keycloak_client_id "kc"
                      :created_by_sub "sub"
                      :created_at (java.time.Instant/parse "2025-01-01T00:00:00Z")
                      :last_used_at nil
                      :revoked_at nil}])]
      (let [resp ((http.api-creds/list-api-credentials-handler {:db {:ds ::fake}})
                  {:auth/tenant-id (str (UUID/randomUUID))})]
        (is (ok-json resp))
        (let [body (parse-json-body resp)
              item (first (:items body))]
          (is (nil? (:revoked_at item)))
          (is (nil? (:last_used_at item))))))))

(deftest list-api-credentials-response-matches-openapi-schema
  (testing "GET /api/api-credentials response matches schemas/ApiCredentialsListResponse"
    (let [tenant-id (UUID/randomUUID)]
      (with-redefs [samuraibff.db.api-credentials/list-credentials
                    (fn [_ds _tenant]
                      [{:id (UUID/randomUUID)
                        ;; Intentionally omit tenant_id to emulate the real DB query.
                        :name "cred"
                        :keycloak_client_id "kc"
                        :created_by_sub "sub"
                        :created_at (java.time.Instant/parse "2025-01-01T00:00:00Z")
                        :last_used_at nil
                        :revoked_at nil}])]
        (let [resp ((http.api-creds/list-api-credentials-handler {:db {:ds ::fake}})
                    {:auth/tenant-id (str tenant-id)})
              body (parse-json-body resp)]
          (is (= 200 (:status resp)))
          ;; This assertion is the important one: it would fail if we returned a
          ;; body that makes Reitit response coercion throw (=> HTTP 500).
          (is (= body (schemas/validate! schemas/ApiCredentialsListResponse body)))
          (is (= (str tenant-id) (:tenant_id body)))
          (is (= (str tenant-id) (get-in body [:items 0 :tenant_id]))))))))

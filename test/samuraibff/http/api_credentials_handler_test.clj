(ns samuraibff.http.api-credentials-handler-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.http.api-credentials :as http.api-creds]
    [samuraibff.util.uuid :as util.uuid])
  (:import
    (java.util UUID)))

(defn- ok-json
  [resp]
  (and (= 200 (:status resp))
       (string? (:body resp))))

(deftest create-api-credential-missing-name
  (testing "missing name returns 400"
    (let [h ((http.api-creds/create-api-credential-handler
               {:db {:ds ::fake}
                :keycloak-admin ::fake})
             {:auth/tenant-id (str (UUID/randomUUID))
              :body-params {}})]
      (is (= 400 (:status h)))
      (is (re-find #"missing-name" (:body h))))))

(deftest rotate-api-credential-invalid-id
  (testing "invalid uuid in path returns 400"
    (let [h ((http.api-creds/rotate-api-credential-handler
               {:db {:ds ::fake}
                :keycloak-admin ::fake})
             {:auth/tenant-id (str (UUID/randomUUID))
              :path-params {:id "not-a-uuid"}})]
      (is (= 400 (:status h)))
      (is (re-find #"invalid-id" (:body h))))))

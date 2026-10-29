(ns samuraibff.tools.principals-integration-test
  "Postgres integration tests of the operator `register-key` command.

  A machine client created at any identity provider becomes an organization
  API key: tokens resolve to its service principal with the key attached, and
  Admins see it in the credential list."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.api-credentials :as db.api-creds]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as tc.pg]
   [samuraibff.tools.principals :as tools]))

(def tenant #uuid "11111111-1111-1111-1111-111111111111")

(def ^:private config
  {:auth {:required? true :issuer tp/test-issuer :tenant-claim "tenant_id" :client-claim "cid"}})

(defn- options
  [overrides]
  (merge {:tenant (str tenant) :subject "external-client-sub" :issuer tp/test-issuer
          :client-id "reporting-sdk" :name "Reporting"}
         overrides))

(deftest register-key-test
  (tc.pg/with-postgres [pg]
    (let [ds (tc.pg/datasource (tc.pg/jdbc-url pg) "drsynth" "drsynth")
          _ (tc.pg/apply-schema! ds)
          _ (tp/insert-tenant! ds tenant "Acme")
          bob (tp/insert-principal! ds {:tenant-id tenant :name "Bob"})
          carol (tp/insert-principal! ds {:tenant-id tenant :name "Carol" :role :builder})
          row (tools/register-key! ds (options {:manager (str (:id carol))}))
          credential (db.api-creds/find-by-service-user ds tenant (:id row))]
      (testing "creates the service principal and an organization key"
        (is (= "service" (:principal_type row)))
        (is (= "organization" (:kind credential)))
        (is (= "tenant" (:default_visibility credential)))
        (is (= (:id carol) (:manager_user_id credential))))
      (testing "a token with the configured client claim acts as the key"
        (let [{:keys [state principal]} (access.principal/resolve-principal
                                         config ds {:sub "external-client-sub"
                                                    :raw {:tenant_id (str tenant) :cid "reporting-sdk"}})]
          (is (= :active state))
          (is (= :service (:type principal)))
          (is (= :organization (get-in principal [:credential :kind])))
          (is (= "tenant" (get-in principal [:credential :default-visibility])))))
      (testing "Admins manage it when no manager is given"
        (let [admin-row (tools/register-key! ds (options {:subject "s2" :client-id "c2" :audience "private"}))]
          (is (nil? (:manager_user_id (db.api-creds/find-by-service-user ds tenant (:id admin-row)))))))
      (testing "invalid input is refused"
        (is (thrown? Exception (tools/register-key! ds (options {:subject "s3" :client-id "c3" :manager (str (:id bob))}))))
        (is (thrown? Exception (tools/register-key! ds (options {:subject "s4" :client-id "c4" :audience "selected"}))))
        (is (thrown? Exception (tools/register-key! ds (options {:subject "s5" :client-id "c5" :name ""}))))))))

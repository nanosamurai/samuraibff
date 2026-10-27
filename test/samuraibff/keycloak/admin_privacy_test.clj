(ns samuraibff.keycloak.admin-privacy-test
  "Exercise credential error boundaries without sending network requests."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [next.jdbc]
            [samuraibff.api-credentials.lifecycle :as lifecycle]
            [samuraibff.db.api-credentials :as db]
            [samuraibff.db.audit :as db.audit]
            [samuraibff.http.api-credentials :as api]
            [samuraibff.keycloak.admin :as admin]
            [samuraibff.test-support.log-capture :as logs])
  (:import (java.io IOException)
           (java.net.http HttpClient HttpResponse)
           (java.util UUID)))

(def ^:private marker "synthetic-private-admin-credential")
(def ^:private tenant-id "1ee835b3-82db-42b9-9dfe-6205e1dd07d1")
(def ^:private credential-id "41df6712-b403-4f13-9360-13d66e0c60a0")
(def ^:private config
  {:issuer "https://identity.example.test/realms/example"
   :realm "example" :client-id "test-admin" :client-secret marker})
(def ^:private token-response {:status 200 :body "{\"access_token\":\"test-token\"}"})
(def ^:private lookup-response {:status 200 :body "[{\"id\":\"client-uuid\"}]"})
(def ^:private api-config
  {:auth {:issuer "https://identity.example.test/realms/example"}})
(def ^:private api-request
  {:auth/tenant-id tenant-id :auth/user {:sub "test-user"}
   :auth/principal {:id (UUID/randomUUID) :tenant-id (UUID/fromString tenant-id)
                    :type :human :role :admin :status :active}
   :body-params {:name "test-credential"} :path-params {:id credential-id}})

(defn- json-response
  "Make a synthetic JSON response for the in-memory HTTP client."
  [status body]
  {:status status :body (json/write-value-as-string body)})

(defn- with-responses
  "Run a thunk against queued HTTP responses or exceptions, capturing logs."
  [responses f]
  (let [pending (atom (vec responses))
        client (proxy [HttpClient] []
                 (send [_request _handler]
                   (let [response (first @pending)]
                     (swap! pending subvec 1)
                     (if (instance? Throwable response)
                       (throw response)
                       (reify HttpResponse
                         (statusCode [_] (int (:status response)))
                         (body [_] (:body response)))))))]
    (with-redefs-fn {(ns-resolve 'samuraibff.keycloak.admin 'http-client) client}
      #(logs/capture! (fn [] (try (f) (catch Exception failure failure)))))))

(defn- assert-private-logs
  "Check actual formatted messages, arguments and MDC for credential data."
  [events]
  (is (not (.contains (pr-str events) marker)))
  (is (every? #(not-any? (fn [arg] (instance? Throwable arg)) (:arguments %)) events)))

(defn- assert-private-failure
  "Require a safe exception with no retained payload or original cause."
  [{:keys [value events]}]
  (is (instance? clojure.lang.ExceptionInfo value))
  (is (nil? (ex-cause value)))
  (is (not (.contains (pr-str value) marker)))
  (assert-private-logs events))

(deftest token-response-and-transport-errors-are-private
  (doseq [response [(json-response 401 {:error marker :client_secret marker})
                    (json-response 200 {:refresh_token marker})
                    (json-response 200 {:access_token [marker]})
                    {:status 200 :body (str "{bad-json:" marker)}
                    (IOException. marker)]]
    (assert-private-failure
     (with-responses [response]
       #((ns-resolve 'samuraibff.keycloak.admin 'fetch-admin-token!) config))))
  (let [result (with-responses [(json-response 200 {:access_token marker})]
                 #((ns-resolve 'samuraibff.keycloak.admin 'fetch-admin-token!) config))]
    (is (= marker (:value result)))
    (assert-private-logs (:events result))))

(deftest json-request-boundary-discards-errors-and-malformed-payloads
  (let [request! (ns-resolve 'samuraibff.keycloak.admin 'http-request-json!)
        request {:method :get :url "https://identity.example.test/admin"
                 :headers {"Authorization" (str "Bearer " marker)}}
        result (with-responses [(json-response 500 {:body marker})] #(request! request))]
    (is (= {:status 500 :body nil} (:value result)))
    (assert-private-logs (:events result))
    (assert-private-failure
     (with-responses [{:status 200 :body (str "{bad-json:" marker)}] #(request! request)))
    (assert-private-failure
     (with-responses [] #(request! (assoc request :url (str "bad uri " marker)))))
    (assert-private-failure
     (with-responses [(IOException. marker)] #(request! request)))))

(deftest client-operation-failures-discard-upstream-payloads
  (let [client (admin/->HttpKeycloakAdmin config)
        create! #(admin/create-m2m-client! client {:tenant-id tenant-id :name "test"})]
    (doseq [responses [[token-response (json-response 400 {:error marker})]
                       [token-response {:status 201 :body ""}
                        (json-response 200 [{:secret marker}])]
                       [token-response {:status 201 :body ""} lookup-response
                        (json-response 200 {:refresh_token marker})]]]
      (assert-private-failure (with-responses responses create!)))
    (doseq [operation [admin/rotate-client-secret! admin/disable-client!]]
      (assert-private-failure
       (with-responses [token-response (json-response 500 {:error marker})]
         #(operation client {:client-id "test-client"}))))))

(deftest successful-admin-client-keeps-show-once-secrets-private-in-logs
  (let [client (admin/->HttpKeycloakAdmin config)
        secret-response (json-response 200 {:value marker})
        created (with-responses [token-response {:status 201 :body ""} lookup-response
                                 secret-response (json-response 200 []) {:status 201 :body ""}]
                  #(admin/create-m2m-client! client {:tenant-id tenant-id :name "test"}))
        rotated (with-responses [token-response lookup-response secret-response]
                  #(admin/rotate-client-secret! client {:client-id "test-client"}))]
    (is (= marker (get-in created [:value :client-secret])))
    (is (string? (get-in created [:value :client-id])))
    (is (= marker (get-in rotated [:value :client-secret])))
    (assert-private-logs (:events created))
    (assert-private-logs (:events rotated))))

(defn- fake-admin
  "Return a protocol implementation using one supplied function for all calls."
  [f]
  (reify admin/KeycloakAdmin
    (create-m2m-client! [_ params] (f params))
    (rotate-client-secret! [_ params] (f params))
    (disable-client! [_ params] (f params))
    (service-account-user-id [_ params] (f params) "test-service-account")
    (delete-client! [_ params] (f params))))

(deftest credential-api-errors-omit-keycloak-payloads-and-causes
  (doseq [status [401 marker]]
    (let [failure (ex-info marker {:type :samuraibff.keycloak-admin/token-fetch-failed
                                   :status status :body marker :url marker :client-id marker}
                           (IOException. marker))
          deps {:config api-config :db {:ds :fake} :keycloak-admin (fake-admin (fn [_] (throw failure)))}
          result (logs/capture! #((api/create-api-credential-handler deps) api-request))]
      (is (= 502 (get-in result [:value :status])))
      (is (= (if (integer? status) {:status status} {}) (get-in result [:value :body :keycloak])))
      (is (not (.contains (pr-str (:value result)) marker)))
      (assert-private-logs (:events result))))
  (with-redefs [db/find-credential (fn [& _] {:keycloak_client_id "test-client"})
                db/lock-credential (fn [& _] {:id (UUID/fromString credential-id)
                                              :tenant_id (UUID/fromString tenant-id)
                                              :keycloak_client_id "test-client"
                                              :identity_sync_state "synced"})
                db/revoke-credential! (fn [& _] {:updated? true})
                db/set-identity-sync-state! (fn [& _] nil)
                db.audit/record! (fn [& _] nil)
                next.jdbc/transact (fn [_ body-fn _] (body-fn :fake))]
    (doseq [handler [api/rotate-api-credential-handler api/revoke-api-credential-handler]]
      (let [deps {:db {:ds :fake}
                  :keycloak-admin (fake-admin (fn [_] (throw (IOException. marker))))}
            result (logs/capture! #((handler deps) api-request))]
        (is (not (.contains (pr-str (:value result)) marker)))
        (assert-private-logs (:events result))))))

(deftest credential-api-success-keeps-show-once-secrets
  (let [client (fake-admin (fn [_] {:client-id "test-client" :client-secret marker}))
        deps {:config api-config :db {:ds :fake} :keycloak-admin client}]
    (with-redefs [lifecycle/register-credential! (fn [& _] nil)
                  db.audit/record! (fn [& _] nil)
                  db/find-credential (fn [& _] {:keycloak_client_id "test-client"})]
      (doseq [handler [api/create-api-credential-handler api/rotate-api-credential-handler]]
        (testing "the intended success response contains the secret, diagnostics do not"
          (let [result (logs/capture! #((handler deps) api-request))]
            (is (= 200 (get-in result [:value :status])))
            (is (= marker (get-in result [:value :body :client_secret])))
            (assert-private-logs (:events result))))))))

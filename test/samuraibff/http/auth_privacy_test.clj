(ns samuraibff.http.auth-privacy-test
  "Keep credentials out of login errors and diagnostics without changing success."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.http.auth :as auth]
            [samuraibff.test-support.log-capture :as logs]))

(def ^:private marker "synthetic-private-auth-credential")
(def ^:private config
  {:auth {:issuer "https://identity.example.test/realms/example" :client-id "browser"}
   :bff {:public-origin-uri "https://app.example.test"}})
(def ^:private callback-request
  {:uri "/auth/callback" :scheme :https
   :params {:code marker :state "test-state"}
   :headers {"authorization" (str "Bearer " marker) "cookie" marker}
   :cookies {"pkce_state" {:value "test-state"}
             "pkce_verifier" {:value marker}}})

(defn- callback-with-response
  "Invoke the callback using an isolated token endpoint stub and capture logs."
  [send-response]
  (with-redefs-fn {(ns-resolve 'samuraibff.http.auth 'http-post-form!) send-response}
    #(logs/capture! (fn [] ((auth/callback-handler config) callback-request)))))

(defn- assert-private-errors
  "Check logged messages, arguments, MDC and error response for the canary."
  [{:keys [value events]}]
  (is (not (.contains (pr-str {:response value :logs events}) marker)))
  (is (every? #(not-any? (fn [arg] (instance? Throwable arg)) (:arguments %)) events)))

(deftest token-response-failures-do-not-expose-credentials
  (doseq [[upstream expected]
          [[{:status 400 :body (json/write-value-as-string {:error marker})} 400]
           [{:status 200 :body (json/write-value-as-string {:refresh_token marker :id_token marker})} 502]
           [{:status 200 :body (str "{invalid-json:" marker)} 502]
           [{:status 200 :body (json/write-value-as-string {:access_token [marker]})} 502]]]
    (let [result (callback-with-response (fn [_ _] upstream))]
      (is (= expected (get-in result [:value :status])))
      (is (nil? (get-in result [:value :cookies])))
      (is (= "token-exchange-failed" (get-in result [:value :body :message])))
      (assert-private-errors result)))
  (let [result (callback-with-response
                (fn [_ _] (throw (ex-info marker {:body marker}
                                          (java.io.IOException. marker)))))]
    (is (= 502 (get-in result [:value :status])))
    (assert-private-errors result)))

(deftest successful-login-keeps-its-http-only-cookie
  (let [result (callback-with-response
                (fn [_ _] {:status 200 :body (json/write-value-as-string
                                              {:access_token marker :refresh_token marker})}))
        cookie (get-in result [:value :cookies "access_token"])]
    (is (= 302 (get-in result [:value :status])))
    (is (= marker (:value cookie)))
    (is (:http-only cookie))
    (is (:secure cookie))
    (is (= :lax (:same-site cookie)))
    (is (not (.contains (pr-str (:events result)) marker)))))

(deftest configuration-and-origin-errors-contain-no-request-data
  (doseq [[handler cfg request]
          [[auth/login-handler {:auth {:audience marker}} callback-request]
           [auth/login-handler {:auth {:issuer "https://identity.example.test"}}
            callback-request]
           [auth/login-handler {:auth {:issuer "https://identity.example.test" :client-id "browser"}}
            (dissoc callback-request :uri)]
           [auth/callback-handler {:auth {:issuer "https://identity.example.test" :client-id "browser"}}
            callback-request]]]
    (let [failure (try ((handler cfg) request) (catch Exception error error))]
      (is (instance? clojure.lang.ExceptionInfo failure))
      (is (nil? (ex-cause failure)))
      (is (not (.contains (pr-str (ex-data failure)) marker)))
      (is (not (.contains (.getMessage ^Throwable failure) marker))))))

(deftest provider-errors-return-a-fixed-message
  (let [result (logs/capture! #((auth/callback-handler config)
                                {:params {:error marker :error_description marker}}))]
    (is (= {:ok false :message "oidc-error"} (get-in result [:value :body])))
    (assert-private-errors result)))

(deftest invalid-token-logs-omit-claims-and-credentials
  (doseq [required? [true false]]
    (with-redefs [oidc/verify-token (fn [_ _] (throw (ex-info marker {:token marker})))]
      (let [handler (auth/wrap-authenticate (constantly {:status 200})
                                            {:auth {:required? required?}})
            result (logs/capture! #(handler {:uri (str "/api/" marker)
                                             :headers {"authorization" (str "Bearer " marker)}}))]
        (is (= (if required? 401 200) (get-in result [:value :status])))
        (assert-private-errors result)))))

(deftest downstream-failures-are-not-retried-or-misclassified-as-authentication
  (doseq [required? [true false]]
    (testing (str "auth required " required?)
      (let [calls (atom 0)
            failure (ex-info "handler failure" {})
            handler (auth/wrap-authenticate (fn [_] (swap! calls inc) (throw failure))
                                            {:auth {:required? required?}})]
        (with-redefs [oidc/verify-token (fn [_ _] {:sub "test-user"})]
          (let [result (logs/capture!
                        #(try (handler {:uri "/api/me" :headers {"authorization" "Bearer test"}})
                              (catch Exception error error)))]
            (is (identical? failure (:value result)))
            (is (= 1 @calls))
            (is (empty? (:events result)))))))))

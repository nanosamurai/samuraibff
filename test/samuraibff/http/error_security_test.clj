(ns samuraibff.http.error-security-test
  "Exercise real route coercion and outer error boundaries with synthetic credentials."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [ring.middleware.resource :as resource]
            [samuraibff.http.api-credentials :as credentials]
            [samuraibff.http.auth :as auth]
            [samuraibff.http.middleware.observability :as observability]
            [samuraibff.http.router :as router]
            [samuraibff.test-support.log-capture :as logs])
  (:import (java.io ByteArrayInputStream IOException)
           (java.nio.charset StandardCharsets)
           (org.apache.logging.log4j ThreadContext)))

(def ^:private markers
  {:authorization "canary-authorization-credential"
   :cookie "canary-cookie-credential"
   :query "canary-query-credential"
   :refresh_token "canary-refresh-credential"
   :client_secret "canary-client-secret"
   :body "canary-body-credential"})
(def ^:private session-id "019d85d7-5915-7a8f-a263-ca04cf3c4b5d")
(def ^:private config {:auth {:required? false :guest-tenant-id session-id}})
(def ^:private mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- private-failure
  "Create a failure with synthetic credentials in its message, data and cause."
  []
  (ex-info (:body markers) markers (IOException. (:refresh_token markers))))

(defn- request
  "Build a real JSON Ring request containing synthetic credential canaries."
  [body]
  {:request-method :post :uri "/api/api-credentials"
   :query-string (str "session_id=" session-id "&token=" (:query markers)
                      "&refresh_token=" (:refresh_token markers))
   :headers {"content-type" "application/json" "accept" "application/json"
             "authorization" (str "Bearer " (:authorization markers))
             "cookie" (str "access_token=" (:cookie markers))}
   :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))})

(defn- response-body
  "Decode a JSON Ring response without changing the successful response contract."
  [response]
  (let [body (:body response)]
    (if (map? body) body (json/read-value body mapper))))

(defn- assert-no-secrets
  "Check a response or captured logs, including every original logging argument."
  [value]
  (let [text (pr-str value)]
    (doseq [marker (vals markers)]
      (is (not (str/includes? text marker)) "Synthetic credential escaped the boundary")))
  (is (not-any? #(instance? Throwable %) (tree-seq coll? seq value))
      "Logging must never receive a Throwable at any nesting level"))

(defn- call-credential-route
  "Call the actual router with only the credential business operation replaced."
  [operation body]
  (with-redefs [credentials/create-api-credential-handler (fn [_] operation)]
    (let [handler (router/create-router {:config config})]
      (logs/capture! #(handler (request body))))))

(defn- assert-error
  "Assert the stable error shape and absence of secrets from response and logs."
  [{:keys [value events]} status message]
  (is (= status (:status value)))
  (is (= {:ok false :message message} (response-body value)))
  (assert-no-secrets {:response value :events events}))

(deftest coercion-and-json-errors-do-not-echo-credentials
  (testing "an invalid successful response must not reveal its show-once secret"
    (let [result (call-credential-route
                  (constantly {:status 200
                               :body {:ok true :credential_id "invalid-uuid" :client_id "client"
                                      :client_secret (:client_secret markers)}})
                  (json/write-value-as-string {:name "test credential"}))]
      (assert-error result 500 "internal-error")
      (is (some #(= session-id (get-in % [:mdc "session_id"])) (:events result)))
      (is (some #(= "/api/api-credentials" (get-in % [:mdc "http_route"])) (:events result)))))
  (testing "request validation keeps HTTP 400 without returning submitted values"
    (let [called (atom 0)
          result (call-credential-route
                  (fn [_] (swap! called inc) {:status 200})
                  (json/write-value-as-string (assoc markers :name [(:body markers)])))]
      (assert-error result 400 "invalid-request")
      (is (zero? @called))))
  (testing "JSON decoding is inside the safe boundary and stays HTTP 400"
    (assert-error (call-credential-route (constantly {:status 200})
                                         (str "{invalid-json:" (:body markers)))
                  400 "invalid-request")))

(deftest handler-and-outer-middleware-errors-are-safe
  (testing "a handler exception is logged once plus the existing HTTP status summary"
    (let [result (call-credential-route (fn [_] (throw (private-failure)))
                                        (json/write-value-as-string {:name "test credential"}))]
      (assert-error result 500 "internal-error")
      (is (= 1 (count (filter #(str/includes? (:text %) "HTTP handler failed") (:events result)))))
      (is (= 1 (count (filter #(str/includes? (:text %) "HTTP request failed") (:events result)))))))
  (testing "authentication middleware failures outside coercion are contained"
    (with-redefs [auth/wrap-authenticate (fn [_ _] (fn [_] (throw (private-failure))))]
      (let [handler (router/create-router {:config config})]
        (assert-error (logs/capture! #(handler (request "{}"))) 500 "internal-error"))))
  (testing "unmatched/static failures cannot leak raw URI or query data"
    (with-redefs [resource/wrap-resource (fn [& _] (fn [_] (throw (private-failure))))]
      (let [handler (router/create-router {:config config})
            req (assoc (request "{}") :uri (str "/" (:query markers)))
            result (logs/capture! #(handler req))]
        (assert-error result 500 "internal-error")
        (is (some #(str/includes? (:text %) "<unmatched>") (:events result)))))))

(deftest successful-show-once-secret-is-preserved
  (let [body {:ok true :credential_id session-id :client_id "client"
              :client_secret (:client_secret markers)}
        {:keys [value events]} (call-credential-route
                                (constantly {:status 200 :body body})
                                (json/write-value-as-string {:name "test credential"}))]
    (is (= 200 (:status value)))
    (is (= body (response-body value)))
    (assert-no-secrets events)))

(deftest observability-keeps-correlation-without-logging-error-payloads
  (let [req {:request-method :get :uri (str "/" (:query markers))
             :reitit.core/match {:template "/api/sessions/:session_id"}
             :path-params {:session_id session-id}
             :headers {"authorization" (:authorization markers) "cookie" (:cookie markers)}
             :params markers}
        response {:status 400 :body markers :headers {"set-cookie" (:cookie markers)}}
        {:keys [value events]} (logs/capture!
                                #((observability/wrap-observability (constantly response)) req))]
    (is (identical? response value) "Explicit endpoint responses retain their contract")
    (assert-no-secrets events)
    (is (= session-id (get-in (first events) [:mdc "session_id"])))
    (is (= (str/replace session-id "-" "") (get-in (first events) [:mdc "trace_id"])))
    (is (= "/api/sessions/:session_id" (get-in (first events) [:mdc "http_route"])))
    (is (nil? (ThreadContext/get "session_id")))))

(deftest observability-rethrows-only-a-constant-cause-free-error
  (doseq [operation [(fn [_] (throw (private-failure)))
                     (constantly {:status (:body markers) :body markers})]]
    (let [req {:request-method {:untrusted (:body markers)}
               :uri (str "/" (:query markers)) :params {:session_id (:cookie markers)}}
          {:keys [value events]}
          (logs/capture!
           #(try ((observability/wrap-observability operation) req)
                 (catch Throwable failure
                   {:message (.getMessage failure) :data (ex-data failure)
                    :cause (.getCause failure)})))]
      (is (= {:message "HTTP request failed"
              :data {:type :samuraibff.http/internal-error} :cause nil} value))
      (assert-no-secrets {:error value :events events})
      (is (= "<unmatched>" (get-in (first events) [:mdc "http_route"])))
      (is (nil? (get-in (first events) [:mdc "session_id"])))
      (is (some #(str/includes? (:text %) "OTHER") events)))))

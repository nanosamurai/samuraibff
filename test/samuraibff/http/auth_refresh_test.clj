(ns samuraibff.http.auth-refresh-test
  "Browser session refresh: HttpOnly refresh cookie, Origin check, rotation,
  fail-closed handling and no tokens in responses or logs."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [samuraibff.auth.jwks :as jwks]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.http.auth :as auth]
            [samuraibff.test-support.log-capture :as logs]))

(def ^:private config
  {:auth {:required? true :issuer "https://identity.example.test/realms/example" :client-id "browser"}
   :bff {:public-origin-uri "https://app.example.test"}})

(def ^:private endpoints
  {:authorization-endpoint "https://identity.example.test/oauth2/authorize"
   :token-endpoint "https://identity.example.test/oauth2/token"
   :end-session-endpoint "https://identity.example.test/oauth2/logout"})

(def ^:private request
  {:uri "/auth/refresh" :scheme :https :request-method :post
   :headers {"origin" "https://app.example.test" "host" "app.example.test"}
   :cookies {"refresh_token" {:value "old-refresh-token"}}})

(defn- refresh
  "Call the refresh handler with a stubbed token endpoint and verifier.

  Returns {:value response :events logs :posts [[url params]]}."
  ([upstream] (refresh upstream request))
  ([upstream req]
   (let [posts (atom [])]
     (with-redefs-fn {(ns-resolve 'samuraibff.http.auth 'http-post-form!)
                      (fn [url params]
                        (swap! posts conj [url params])
                        (if (fn? upstream) (upstream) upstream))
                      #'jwks/provider-endpoints! (fn [_ _] endpoints)
                      #'oidc/verify-token (fn [_ token]
                                            (if (= "new-access-token" token)
                                              {:sub "user" :expires-at-ms 1800000300000}
                                              (throw (ex-info "invalid" {}))))}
       #(assoc (logs/capture! (fn [] ((auth/refresh-handler config) req)))
               :posts @posts)))))

(defn- token-response
  [m]
  {:status 200 :body (json/write-value-as-string m)})

(defn- no-tokens?
  [result]
  (not-any? #(.contains (pr-str (select-keys result [:events])) %)
            ["old-refresh-token" "new-refresh-token" "new-access-token"]))

(deftest refresh-rotates-the-session-cookies
  (let [result (refresh (token-response {:access_token "new-access-token"
                                         :refresh_token "new-refresh-token"
                                         :id_token "new-id-token"}))
        {:keys [status body cookies]} (:value result)]
    (is (= 200 status))
    (is (= {:ok true :access_expires_at 1800000300000} body))
    (is (not (.contains (pr-str body) "token\"")) "tokens never appear in the response body")
    (testing "the provider receives a standard refresh_token grant"
      (is (= [[(:token-endpoint endpoints)
               {:grant_type "refresh_token" :client_id "browser" :refresh_token "old-refresh-token"}]]
             (:posts result))))
    (testing "cookies"
      (is (= "new-access-token" (get-in cookies ["access_token" :value])))
      (is (= {:value "new-refresh-token" :http-only true :same-site :strict :secure true :path "/auth/refresh"}
             (get cookies "refresh_token")))
      (is (= "/auth" (get-in cookies ["id_token_hint" :path]))))
    (is (no-tokens? result))))

(deftest refresh-keeps-the-old-refresh-token-without-rotation
  (let [{:keys [value]} (refresh (token-response {:access_token "new-access-token"}))]
    (is (= 200 (:status value)))
    (is (nil? (get-in value [:cookies "refresh_token"])))))

(deftest refresh-fails-closed
  (testing "a rejected refresh token is cleared"
    (let [result (refresh {:status 400 :body "{\"error\":\"invalid_grant\"}"})]
      (is (= 401 (get-in result [:value :status])))
      (is (= "refresh-failed" (get-in result [:value :body :message])))
      (is (= 0 (get-in result [:value :cookies "refresh_token" :max-age])))
      (is (nil? (get-in result [:value :cookies "access_token"])))
      (is (no-tokens? result))))
  (testing "a refreshed token that does not verify is not set"
    (let [result (refresh (token-response {:access_token "unexpected-token"}))]
      (is (= 401 (get-in result [:value :status])))
      (is (nil? (get-in result [:value :cookies "access_token"])))))
  (testing "a transport failure keeps the refresh cookie for a later retry"
    (let [result (refresh #(throw (java.io.IOException. "connection reset")))]
      (is (= 502 (get-in result [:value :status])))
      (is (nil? (get-in result [:value :cookies])))))
  (testing "no refresh cookie means no upstream call"
    (let [result (refresh (token-response {}) (dissoc request :cookies))]
      (is (= 401 (get-in result [:value :status])))
      (is (= "refresh-unavailable" (get-in result [:value :body :message])))
      (is (empty? (:posts result)))))
  (testing "discovery down"
    (with-redefs [jwks/provider-endpoints! (fn [_ _] (throw (ex-info "down" {})))]
      (is (= 503 (:status ((auth/refresh-handler config) request)))))))

(deftest refresh-requires-an-allowed-origin
  (doseq [req [(assoc-in request [:headers "origin"] "https://evil.example.test")
               (update request :headers dissoc "origin")]]
    (let [result (refresh (token-response {:access_token "new-access-token"}) req)]
      (is (= 403 (get-in result [:value :status])))
      (is (empty? (:posts result)) "no upstream call for a foreign or missing Origin"))))

(deftest login-stores-and-logout-clears-the-refresh-token
  (let [callback {:uri "/auth/callback" :scheme :https
                  :params {:code "code" :state "state"}
                  :cookies {"pkce_state" {:value "state"} "pkce_verifier" {:value "verifier"}}}
        resp (with-redefs-fn {(ns-resolve 'samuraibff.http.auth 'http-post-form!)
                              (fn [_ _] (token-response {:access_token "access" :refresh_token "refresh"}))
                              #'jwks/provider-endpoints! (fn [_ _] endpoints)}
               #((auth/callback-handler config) callback))]
    (is (= 302 (:status resp)))
    (is (= {:value "refresh" :http-only true :same-site :strict :secure true :path "/auth/refresh"}
           (get-in resp [:cookies "refresh_token"]))))
  (let [resp (with-redefs [jwks/provider-endpoints! (fn [_ _] endpoints)]
               ((auth/logout-handler config) {:uri "/auth/logout" :scheme :https :headers {}}))]
    (is (= {:value "" :max-age 0 :path "/auth/refresh"} (get-in resp [:cookies "refresh_token"])))))

(ns samuraibff.http.auth-test
  "Unit tests for HTTP authentication middleware."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.http.auth :as auth]))

(def ^:private guest-tenant-id
  "Guest tenant used by auth-disabled test configurations."
  "00000000-0000-0000-0000-000000000000")

(defn- captured-request
  "Run the authentication middleware and return the request seen by its handler.

  Inputs:
  - config: SamuraiBFF configuration map
  - request: Ring request map

  Returns: Ring request map enriched by authentication middleware."
  [config request]
  ((auth/wrap-authenticate identity config) request))

(deftest auth-disabled-attaches-guest-tenant
  (testing "anonymous requests receive the configured guest tenant"
    (let [request (captured-request
                   {:auth {:required? false
                           :guest-tenant-id guest-tenant-id}}
                   {:uri "/api/recordings"})]
      (is (nil? (:auth/token request)))
      (is (nil? (:auth/user request)))
      (is (= guest-tenant-id (:auth/tenant-id request))))))

(deftest auth-disabled-invalid-token-falls-back-to-guest
  (testing "invalid stale tokens do not remove the guest tenant in quickstart mode"
    (with-redefs [oidc/verify-token (fn [_config _token]
                                      (throw (ex-info "invalid-token" {})))]
      (let [request (captured-request
                     {:auth {:required? false
                             :guest-tenant-id guest-tenant-id}}
                     {:uri "/api/recordings"
                      :headers {"authorization" "Bearer stale"}})]
        (is (= "stale" (:auth/token request)))
        (is (nil? (:auth/user request)))
        (is (= guest-tenant-id (:auth/tenant-id request)))))))

(deftest auth-required-does-not-attach-guest-tenant
  (testing "required-auth mode never grants the development guest tenant"
    (let [request (captured-request
                   {:auth {:required? true
                           :guest-tenant-id guest-tenant-id}}
                   {:uri "/api/recordings"})]
      (is (nil? (:auth/tenant-id request))))))

(deftest auth-disabled-without-guest-tenant-remains-unscoped
  (testing "a missing guest tenant remains visible as configuration failure"
    (let [request (captured-request
                   {:auth {:required? false}}
                   {:uri "/api/recordings"})]
      (is (nil? (:auth/tenant-id request))))))

(deftest me-handler-exposes-only-realtime-track-ids-test
  (let [handler (auth/me-handler {:auth {:required? false}
                                  :grpc {:realtime-tracks [{:id "faster" :address "rtservice:50052"}
                                                           {:id "qwen" :address "qwen-rtservice:50052"}]}})
        response (handler {})
        body (:body response)]
    (is (= 200 (:status response)))
    (is (= ["faster" "qwen"] (:realtime_tracks body)))
    (is (not (contains? body :grpc)))))

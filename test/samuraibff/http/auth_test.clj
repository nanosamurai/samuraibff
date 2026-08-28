(ns samuraibff.http.auth-test
  "Unit tests for HTTP authentication middleware."
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.auth.oidc :as oidc]
   [samuraibff.grpc.client :as grpc.client]
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

(deftest me-handler-exposes-sanitized-realtime-track-capabilities-test
  (let [config {:auth {:required? false}
                :grpc {:realtime-tracks [{:id "faster" :address "rtservice:50052"}
                                         {:id "qwen" :address "qwen-rtservice:50052"}]}}
        grpc {:tracks (get-in config [:grpc :realtime-tracks])}]
    (with-redefs [grpc.client/get-capabilities
                  (fn [{:keys [id]} _timeout-ms]
                    {:provider-profile-id (str id "-profile")
                     :windowed-realtime? (= id "faster")
                     :native-streaming? (= id "qwen")
                     :segment-timestamps? (= id "faster")
                     :word-timestamps? false
                     :language-detection? true
                     :supported-languages ["en" "cs"]
                     :preferred-sample-rate 16000
                     :maximum-audio-seconds 0.0
                     :maximum-concurrent-sessions (if (= id "qwen") 1 0)
                     :runtime "secret-runtime"
                     :model-digest "secret-digest"})]
      (let [response ((auth/me-handler config grpc) {})
            body (:body response)
            qwen (second (:realtime_track_capabilities body))]
        (is (= 200 (:status response)))
        (is (= ["faster" "qwen"] (:realtime_tracks body)))
        (is (= {:id "qwen"
                :available true
                :provider_profile_id "qwen-profile"
                :windowed_realtime false
                :native_streaming true
                :segment_timestamps false
                :word_timestamps false
                :language_detection true
                :supported_languages ["en" "cs"]
                :preferred_sample_rate 16000
                :maximum_audio_seconds 0.0
                :maximum_concurrent_sessions 1}
               qwen))
        (is (not (contains? qwen :address)))
        (is (not (contains? qwen :runtime)))
        (is (not (contains? qwen :model_digest)))))
    (testing "provider discovery failure remains visible without failing /api/me"
      (with-redefs [grpc.client/get-capabilities
                    (fn [_track _timeout-ms]
                      (throw (ex-info "provider unavailable" {})))]
        (let [response ((auth/me-handler config grpc) {})]
          (is (= 200 (:status response)))
          (is (= [{:id "faster" :available false}
                  {:id "qwen" :available false}]
                 (get-in response [:body :realtime_track_capabilities]))))))))

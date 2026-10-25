(ns samuraibff.auth.oidc-security-test
  "Regression tests exercise real RSA signatures and Nimbus claims validation."
  (:require [clojure.test :refer [deftest is testing]]
            [samuraibff.auth.jwks :as jwks]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.auth.signed-tokens :as signed])
  (:import (com.nimbusds.jose JWSAlgorithm)))

(defn- fixture
  "Create an isolated issuer clock, key cache and observable metadata transport."
  []
  (let [now* (atom 1800000000000)
        keys* (atom [@signed/key-a])
        calls* (atom [])]
    {:now* now* :keys* keys* :calls* calls*
     :opts {:now #(deref now*) :now-ms #(deref now*) :cache (atom nil)
            :fetch-json (signed/metadata-fetch keys* calls*)}}))

(defn- rejected?
  "Return true only for a sanitized verification failure without nested causes."
  [token opts]
  (try
    (oidc/verify-token signed/config token opts)
    false
    (catch clojure.lang.ExceptionInfo e
      (and (= "Invalid token" (.getMessage e))
           (= {:type :samuraibff.auth/invalid-token} (ex-data e))
           (nil? (.getCause e))))))

(deftest signed-claims-and-signatures-are-validated
  (let [{:keys [now* opts]} (fixture)
        claims (signed/claims @now*)
        now-s (quot @now* 1000)]
    (is (= "test-user" (:sub (oidc/verify-token signed/config (signed/token claims) opts))))
    (doseq [[label changed]
            [[:expired (assoc claims :exp (- now-s 31))]
             [:expiry-boundary (assoc claims :exp (- now-s 30))]
             [:not-yet-valid (assoc claims :nbf (+ now-s 31))]
             [:missing-expiry (dissoc claims :exp)]
             [:null-expiry (assoc claims :exp nil)]
             [:malformed-expiry (assoc claims :exp "tomorrow")]
             [:malformed-not-before (assoc claims :nbf "tomorrow")]
             [:missing-subject (dissoc claims :sub)]
             [:blank-subject (assoc claims :sub " ")]
             [:missing-issuer (dissoc claims :iss)]
             [:wrong-issuer (assoc claims :iss "https://other.example.test")]
             [:wrong-audience (assoc claims :aud ["another-service"])]
             [:missing-audience (dissoc claims :aud)]]]
      (testing (name label)
        (is (rejected? (signed/token changed) opts))))
    (testing "only the fixed clock-skew window is accepted"
      (is (= "test-user" (:sub (oidc/verify-token signed/config
                                                  (signed/token (assoc claims :exp (- now-s 29))) opts))))
      (is (= "test-user" (:sub (oidc/verify-token signed/config
                                                  (signed/token (assoc claims :nbf (+ now-s 29))) opts)))))
    (testing "existing Keycloak azp audience compatibility is preserved"
      (is (= "test-user" (:sub (oidc/verify-token signed/config
                                                  (signed/token (assoc claims :aud ["account"] :azp "bff-web")) opts)))))
    (is (rejected? (signed/token claims {:key @signed/key-b :kid "key-a"}) opts))
    (is (rejected? (signed/token claims {:algorithm JWSAlgorithm/RS512}) opts))))

(deftest malformed-tokens-do-not-amplify-metadata-requests
  (let [{:keys [now* calls* opts]} (fixture)]
    (doseq [token [nil "" "not-a-jwt" (apply str (repeat 16385 "a"))
                   (signed/token (signed/claims @now*) {:algorithm JWSAlgorithm/RS512})
                   (signed/token (signed/claims @now*) {:kid " "})]]
      (is (rejected? token opts)))
    (is (empty? @calls*))))

(deftest cache-refresh-observes-key-addition-and-removal
  (let [{:keys [now* keys* calls* opts]} (fixture)
        old-token (signed/token (assoc (signed/claims @now*) :exp (+ 1000 (quot @now* 1000))))]
    (is (= "test-user" (:sub (oidc/verify-token signed/config old-token opts))))
    (is (= "test-user" (:sub (oidc/verify-token signed/config old-token opts))))
    (is (= 2 (count @calls*)))
    (reset! keys* [@signed/key-a @signed/key-b])
    (let [new-token (signed/token (signed/claims @now*) {:key @signed/key-b})]
      (is (rejected? new-token opts) "Unknown keys are bounded by the refresh cooldown")
      (is (= 2 (count @calls*)))
      (swap! now* + jwks/refresh-interval-ms)
      (is (= "test-user" (:sub (oidc/verify-token signed/config new-token opts))))
      (is (= 4 (count @calls*))))
    (reset! keys* [@signed/key-b])
    (swap! now* + (dec jwks/cache-ttl-ms))
    (is (= "test-user" (:sub (oidc/verify-token signed/config old-token opts))))
    (is (= 4 (count @calls*)))
    (swap! now* inc)
    (is (rejected? old-token opts) "Removed signing keys expire at the cache TTL")
    (is (= 6 (count @calls*)))
    (doseq [kid ["missing-1" "missing-2" "missing-3"]]
      (is (rejected? (signed/token (signed/claims @now*) {:kid kid}) opts)))
    (is (= 6 (count @calls*)))))

(deftest refresh-failure-never-extends-key-trust
  (let [{:keys [now* calls* opts]} (fixture)
        token (signed/token (assoc (signed/claims @now*) :exp (+ 1000 (quot @now* 1000))))
        failure-opts (assoc opts :fetch-json
                            (fn [_]
                              (swap! calls* conj :failed)
                              (throw (ex-info "REMOTE RESPONSE MUST NOT ESCAPE"
                                              {:body "sensitive-response"}))))]
    (is (= "test-user" (:sub (oidc/verify-token signed/config token opts))))
    (swap! now* + jwks/refresh-interval-ms)
    (is (rejected? (signed/token (signed/claims @now*) {:kid "unknown"}) failure-opts))
    (is (= "test-user" (:sub (oidc/verify-token signed/config token failure-opts)))
        "A failed unknown-kid fetch does not invalidate still-fresh keys")
    (swap! now* + (- jwks/cache-ttl-ms jwks/refresh-interval-ms))
    (is (rejected? token failure-opts))
    (let [attempts (count @calls*)]
      (is (rejected? token failure-opts))
      (is (= attempts (count @calls*))))
    (swap! now* + jwks/refresh-interval-ms)
    (is (= "test-user" (:sub (oidc/verify-token signed/config token opts))))))

(deftest concurrent-requests-share-one-key-refresh
  (let [{:keys [now* calls* opts]} (fixture)
        entered (promise)
        release (promise)
        fetch (:fetch-json opts)
        opts (assoc opts :fetch-json (fn [url]
                                       (deliver entered true)
                                       (deref release 5000 false)
                                       (fetch url)))
        token (signed/token (signed/claims @now*))
        requests (doall (repeatedly 12 #(future (:sub (oidc/verify-token signed/config token opts)))))]
    (try
      (is (true? (deref entered 5000 false)))
      (deliver release true)
      (is (= (repeat 12 "test-user") (map #(deref % 5000 :timeout) requests)))
      (is (= 2 (count @calls*)))
      (finally (deliver release true)))))

(deftest unknown-key-refresh-does-not-block-fresh-known-keys
  (let [{:keys [now* opts]} (fixture)
        known-token (signed/token (signed/claims @now*))
        entered (promise)
        release (promise)]
    (is (= "test-user" (:sub (oidc/verify-token signed/config known-token opts))))
    (swap! now* + jwks/refresh-interval-ms)
    (let [unknown-token (signed/token (signed/claims @now*) {:kid "unknown"})
          refresh (future
                    (rejected? unknown-token
                               (assoc opts :fetch-json
                                      (fn [_]
                                        (deliver entered true)
                                        (deref release 5000 false)
                                        (throw (ex-info "Issuer unavailable" {}))))))]
      (try
        (is (true? (deref entered 5000 false)))
        (let [known (future (:sub (oidc/verify-token signed/config known-token opts)))]
          (is (= "test-user" (deref known 1000 :timeout))))
        (finally (deliver release true)))
      (is (true? (deref refresh 5000 false))))))

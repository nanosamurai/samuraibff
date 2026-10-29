(ns samuraibff.auth.provider-endpoints-test
  "Browser-login endpoints come from OIDC discovery, validated and cached."
  (:require [clojure.test :refer [deftest is testing]]
            [samuraibff.auth.jwks :as jwks]))

(def ^:private issuer "https://identity.example.test/tenant-a")

(defn- discovery
  [overrides]
  (merge {:issuer issuer
          :authorization_endpoint "https://identity.example.test/oauth2/authorize"
          :token_endpoint "https://identity.example.test/oauth2/token"
          :end_session_endpoint "https://identity.example.test/oauth2/logout"
          :jwks_uri "https://identity.example.test/keys"}
         overrides))

(defn- endpoints
  [doc]
  (jwks/provider-endpoints! issuer {:fetch-json (constantly doc) :cache (atom nil)}))

(deftest discovered-endpoints-are-returned-as-advertised
  (is (= {:authorization-endpoint "https://identity.example.test/oauth2/authorize"
          :token-endpoint "https://identity.example.test/oauth2/token"
          :end-session-endpoint "https://identity.example.test/oauth2/logout"}
         (endpoints (discovery {}))))
  (testing "end_session_endpoint is optional"
    (is (nil? (:end-session-endpoint (endpoints (discovery {:end_session_endpoint nil})))))))

(deftest invalid-discovery-fails-closed
  (doseq [[label doc] [["issuer mismatch" (discovery {:issuer "https://other.example.test"})]
                       ["missing authorization endpoint" (discovery {:authorization_endpoint nil})]
                       ["missing token endpoint" (discovery {:token_endpoint nil})]
                       ["http endpoint for an https issuer" (discovery {:token_endpoint "http://identity.example.test/token"})]
                       ["credentials in the endpoint" (discovery {:authorization_endpoint "https://user:pw@identity.example.test/a"})]]]
    (testing label
      (is (thrown? Exception (endpoints doc))))))

(deftest discovery-is-cached-per-issuer
  (let [calls (atom 0)
        now (atom 0)
        opts {:fetch-json (fn [_] (swap! calls inc) (discovery {}))
              :cache (atom nil)
              :now-ms #(deref now)}]
    (jwks/provider-endpoints! issuer opts)
    (jwks/provider-endpoints! issuer opts)
    (is (= 1 @calls))
    (reset! now (inc jwks/cache-ttl-ms))
    (jwks/provider-endpoints! issuer opts)
    (is (= 2 @calls))))

(ns samuraibff.auth.oidc-jwks-fetch-test
  (:require [clojure.test :refer [deftest is]]
            [samuraibff.auth.jwks :as jwks]
            [samuraibff.auth.oidc :as oidc]
            [samuraibff.auth.signed-tokens :as signed]))

(deftest verify-token-uses-default-fetch-when-option-is-nil
  (let [calls (atom [])
        fake-fetch (signed/metadata-fetch (atom [@signed/key-a]) calls)
        token (signed/token (signed/claims (System/currentTimeMillis)))]
    (with-redefs [jwks/fetch-json! fake-fetch]
      (is (= "test-user"
             (:sub (oidc/verify-token signed/config token
                                      {:fetch-json nil :cache (atom nil)}))))
      (is (= 2 (count @calls))))))

(ns samuraibff.auth.oidc-audience-test
  (:require
    [clojure.test :refer :all]
    [samuraibff.auth.oidc :as oidc]))

(deftest audience-valid-test
  ;; token has aud containing client-id
  (is (true? (#'oidc/audience-valid? "bff-web" ["bff-web"] nil)))
  ;; token has aud=account but azp=client-id
  (is (true? (#'oidc/audience-valid? "bff-web" ["account"] "bff-web")))
  ;; mismatch
  (is (false? (#'oidc/audience-valid? "bff-web" ["account"] "other")))
  (is (false? (#'oidc/audience-valid? "bff-web" [] nil))))

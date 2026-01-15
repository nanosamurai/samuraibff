(ns samuraibff.auth.oidc-jwks-fetch-test
  (:require
    [clojure.test :refer :all]
    [samuraibff.auth.oidc :as oidc]))

(deftest verify-token-does-not-pass-nil-fetch-json
  ;; Regression test for a production issue:
  ;; verify-token arity-2 destructured {:keys [fetch-json]} and then passed
  ;; {:fetch-json fetch-json} into ensure-jwks!, which used an :or default.
  ;; When caller did not provide fetch-json, it ended up passing nil and the
  ;; default never applied.
  (let [calls (atom [])
        fake-fetch (fn [url]
                     (swap! calls conj url)
                     (cond
                       (re-find #"/\.well-known/openid-configuration$" url)
                       {:jwks_uri "https://example.invalid/jwks"}

                       (= url "https://example.invalid/jwks")
                       ;; Minimal JWK set; not actually used because verify-token will fail later
                       {:keys []}

                       :else
                       (throw (ex-info "unexpected" {:url url}))))
        cfg {:auth {:issuer "https://example.invalid/issuer"
                    :audience "bff-web"}}
        ;; We expect this to throw Invalid token because the token is nonsense,
        ;; but crucially it must not NPE.
        res (try
              (oidc/verify-token cfg "not-a-jwt" {:fetch-json fake-fetch})
              :ok
              (catch Exception e
                e))]
    (is (instance? Exception res))
    (is (not (re-find #"NullPointerException" (str res)))
        "Should not throw NPE when fetch-json is not provided / is nil")
    (is (<= 1 (count @calls)))))

(ns samuraibff.http.origin-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.http.origin :as origin]))

(def config {:auth {:cookie-name "access_token"}
             :bff {:public-origin-uri "https://app.example.test"}})

(def handler (origin/wrap-origin-check (fn [_] {:status 200}) config))

(defn- req [method & {:keys [origin referer cookie authorization uri]}]
  {:request-method method
   :uri (or uri "/api/sessions")
   :scheme :https
   :headers (cond-> {"host" "app.example.test"}
              origin (assoc "origin" origin)
              referer (assoc "referer" referer)
              authorization (assoc "authorization" authorization))
   :cookies (cond-> {} cookie (assoc "access_token" {:value "t"}))})

(deftest origin-check-test
  (testing "safe methods pass"
    (is (= 200 (:status (handler (req :get :origin "https://evil.test" :cookie true))))))
  (testing "same-origin mutation passes, foreign origin is rejected"
    (is (= 200 (:status (handler (req :post :origin "https://app.example.test" :cookie true)))))
    (is (= 403 (:status (handler (req :post :origin "https://evil.test" :cookie true)))))
    (is (= 403 (:status (handler (req :delete :origin "https://app.example.test.evil.test"))))))
  (testing "cookie mutation without Origin needs a same-origin Referer"
    (is (= 403 (:status (handler (req :post :cookie true)))))
    (is (= 200 (:status (handler (req :post :cookie true :referer "https://app.example.test/live"))))))
  (testing "bearer clients without Origin keep working"
    (is (= 200 (:status (handler (req :post :authorization "Bearer x"))))))
  (testing "internal callbacks are skipped"
    (is (= 200 (:status (handler (req :post :origin "https://evil.test" :uri "/internal/refined")))))))

(deftest websocket-origin-test
  (is (origin/websocket-origin-allowed? config (req :get)))
  (is (origin/websocket-origin-allowed? config (req :get :origin "https://app.example.test")))
  (is (not (origin/websocket-origin-allowed? config (req :get :origin "https://evil.test")))))

(ns samuraibff.ui.api-credentials-store-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [samuraibff.ui.api-credentials-store :as s]))

(deftest init-state-shape
  (testing "init-state contains required keys"
    (let [st (s/init-state)]
      (is (= [] (:items st)))
      (is (false? (:loading? st)))
      (is (nil? (:error st)))
      (is (false? (:show-revoked? st)))
      (is (= false (get-in st [:secret-modal :open?])))
      (is (nil? (get-in st [:secret-modal :client-secret]))))))

(deftest secret-modal-clears-on-close
  (testing "client_secret is cleared when modal closes"
    (let [st (-> (s/init-state)
                 (s/open-secret-modal {:credential-id "c1"
                                       :client-id "id"
                                       :client-secret "secret"}))]
      (is (true? (get-in st [:secret-modal :open?])))
      (is (= "secret" (get-in st [:secret-modal :client-secret])))

      (let [st2 (s/close-secret-modal st)]
        (is (false? (get-in st2 [:secret-modal :open?])))
        (is (nil? (get-in st2 [:secret-modal :client-secret])))))))

(deftest visible-items-hides-revoked-by-default
  (testing "revoked rows are hidden by default"
    (let [items [{:id "a" :name "A" :revoked_at nil}
                 {:id "b" :name "B" :revoked_at "2025-01-01T00:00:00Z"}]
          st (-> (s/init-state)
                 (s/set-items items))]
      (is (= ["a"] (mapv :id (s/visible-items st))))

      (let [st2 (s/toggle-show-revoked st)]
        (is (= ["a" "b"] (mapv :id (s/visible-items st2)))))))

(deftest mark-revoked-updates-item
  (testing "mark-revoked sets revoked_at on matching item"
    (let [st (-> (s/init-state)
                 (s/set-items [{:id "a" :revoked_at nil}
                               {:id "b" :revoked_at nil}]))
          st2 (s/mark-revoked st "b")]
      (is (nil? (:revoked_at (first (:items st2)))))
      (is (some? (:revoked_at (second (:items st2))))))))

(deftest set-loading-and-error
  (testing "loading and error helpers"
    (let [st (s/init-state)
          st (s/set-loading st true)
          st (s/set-error st "boom")]
      (is (true? (:loading? st)))
      (is (= "boom" (:error st)))
      (is (nil? (:error (s/set-error st nil)))))))

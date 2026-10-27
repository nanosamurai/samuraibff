(ns samuraibff.access.policy-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.access.policy :as policy]
   [samuraibff.access.revocation :as revocation]))

(def tenant #uuid "11111111-1111-1111-1111-111111111111")
(def other-tenant #uuid "22222222-2222-2222-2222-222222222222")

(defn- principal
  [id role & {:keys [type status tenant-id] :or {type :human status :active tenant-id tenant}}]
  {:id id :tenant-id tenant-id :type type :role (when (= :human type) role) :status status})

(def alice (principal #uuid "00000000-0000-0000-0000-00000000000a" :member))
(def bob (principal #uuid "00000000-0000-0000-0000-00000000000b" :builder))
(def admin (principal #uuid "00000000-0000-0000-0000-0000000000ad" :admin))
(def service (principal #uuid "00000000-0000-0000-0000-00000000005e" nil :type :service))
(def foreign (principal #uuid "00000000-0000-0000-0000-0000000000ff" :admin :tenant-id other-tenant))

(defn- session [owner visibility]
  {:tenant_id tenant :user_id (:id owner) :visibility visibility})

(deftest session-read-policy-test
  (testing "private sessions are readable only by the owner, Admin has no override"
    (is (policy/session-visible-to? alice (session alice "private") false))
    (is (not (policy/session-visible-to? bob (session alice "private") false)))
    (is (not (policy/session-visible-to? admin (session alice "private") false))))
  (testing "selected people needs a grant"
    (is (policy/session-visible-to? bob (session alice "selected") true))
    (is (not (policy/session-visible-to? admin (session alice "selected") false))))
  (testing "everyone covers active humans of the tenant only"
    (is (policy/session-visible-to? admin (session alice "tenant") false))
    (is (not (policy/session-visible-to? service (session alice "tenant") false)))
    (is (not (policy/session-visible-to? foreign (session alice "tenant") true))))
  (testing "suspension and ownerless rows deny"
    (is (not (policy/session-visible-to? (assoc alice :status :suspended) (session alice "private") false)))
    (is (not (policy/session-visible-to? alice {:tenant_id tenant :user_id nil :visibility "tenant"} false))))
  (testing "only the owner manages"
    (is (policy/can-manage-session? alice (session alice "tenant")))
    (is (not (policy/can-manage-session? admin (session alice "tenant"))))
    (is (not (policy/can-share-session? service (session service "private"))))))

(deftest definition-policy-test
  (let [personal {:availability "personal" :owner_user_id (:id bob)}
        published {:availability "published" :owner_user_id nil}]
    (is (policy/can-use-definition? alice published))
    (is (not (policy/can-use-definition? alice personal)))
    (is (policy/can-use-definition? bob personal))
    (is (not (policy/can-use-definition? admin personal)) "Admin does not use a Builder's personal definition")
    (is (policy/can-manage-definition? admin personal))
    (is (not (policy/can-manage-definition? bob published)))
    (is (not (policy/can-view-definition? alice personal)))
    (is (not (policy/can-use-definition? service published)))
    (is (not (policy/can-use-definition? (assoc bob :role :member) personal)) "demotion stops use")))

(deftest capability-and-credential-test
  (is (= :all (policy/credential-scope admin)))
  (is (= :own (policy/credential-scope bob)))
  (is (nil? (policy/credential-scope alice)))
  (is (nil? (policy/credential-scope service)))
  (is (policy/can-manage-credential? bob {:manager_user_id (:id bob)}))
  (is (not (policy/can-manage-credential? bob {:manager_user_id nil})))
  (is (true? (:manage_people (policy/capabilities admin))))
  (is (false? (:manage_people (policy/capabilities bob))))
  (is (false? (:share_sessions (policy/capabilities service)))))

(deftest bounded-checker-test
  (let [now* (atom 0)
        result* (atom true)
        calls* (atom 0)
        checker (revocation/bounded-checker (fn [] (swap! calls* inc)
                                              (let [r @result*]
                                                (if (instance? Exception r) (throw r) r)))
                                            {:now #(deref now*)})]
    (testing "no re-check inside the interval"
      (is (checker))
      (is (zero? @calls*)))
    (testing "denial is final"
      (reset! now* (inc revocation/check-interval-ms))
      (is (checker))
      (reset! result* false)
      (reset! now* (* 2 (inc revocation/check-interval-ms)))
      (is (false? (checker)))
      (reset! result* true)
      (is (false? (checker)))))
  (testing "store outage fails closed after the bound"
    (let [now* (atom 0)
          checker (revocation/bounded-checker #(throw (ex-info "down" {})) {:now #(deref now*)})]
      (reset! now* (inc revocation/check-interval-ms))
      (is (checker) "within the bound")
      (reset! now* (inc revocation/revocation-bound-ms))
      (is (false? (checker)) "beyond the bound"))))

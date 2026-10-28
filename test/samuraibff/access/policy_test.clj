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
    (is (policy/can-use-definition? service published) "organization keys run published definitions")
    (is (not (policy/can-use-definition? service personal)))
    (is (not (policy/can-use-definition? (assoc bob :role :member) personal)) "demotion stops use")))

(def ^:private key-id #uuid "00000000-0000-0000-0000-0000000000c1")
(def ^:private other-key-id #uuid "00000000-0000-0000-0000-0000000000c2")

(defn- personal-key [owner read-scope]
  (assoc owner :credential {:id key-id :name "sdk" :kind :personal :read-scope read-scope}))

(deftest personal-key-policy-test
  (let [restricted (personal-key admin :credential)
        broad (personal-key admin :owner)
        via-key (assoc (session admin "private") :created_via_credential_id key-id)
        via-other (assoc (session admin "private") :created_via_credential_id other-key-id)
        ui-session (session admin "private")
        colleague (session alice "tenant")]
    (testing "a restricted key reads and manages only sessions it created"
      (is (policy/session-visible-to? restricted via-key false))
      (is (policy/can-manage-session? restricted via-key))
      (is (not (policy/session-visible-to? restricted via-other false)))
      (is (not (policy/session-visible-to? restricted ui-session false)))
      (is (not (policy/can-manage-session? restricted ui-session)))
      (is (not (policy/session-visible-to? restricted colleague false))))
    (testing "an owner-scope key reads what its owner reads"
      (is (policy/session-visible-to? broad ui-session false))
      (is (policy/session-visible-to? broad colleague false))
      (is (policy/can-manage-session? broad ui-session)))
    (testing "keys never administer, even for an Admin owner"
      (doseq [k [restricted broad]]
        (is (not (policy/can-manage-people? k)))
        (is (nil? (policy/credential-scope k)))
        (is (not (policy/can-create-credential? k :personal)))
        (is (not (policy/can-author-integrations? k)))
        (is (not (policy/can-publish-definitions? k)))
        (is (not (policy/can-manage-defaults? k)))
        (is (not (policy/can-manage-definition? k {:availability "published"})))))
    (testing "the owner's definitions still run for key sessions"
      (is (policy/can-use-definition? restricted {:availability "personal" :owner_user_id (:id admin)})))))

(deftest organization-key-policy-test
  (let [org-key (assoc service :credential {:id key-id :kind :organization :default-visibility "tenant"})
        key-session (session service "private")
        managed (assoc key-session :key_manager true)]
    (testing "the service principal owns its sessions"
      (is (policy/can-manage-session? org-key key-session)))
    (testing "the key manager manages key sessions; others only read by audience"
      (is (policy/can-manage-session? bob managed))
      (is (policy/session-visible-to? bob managed false))
      (is (not (policy/can-manage-session? alice key-session)))
      (is (not (policy/session-visible-to? alice key-session false))))
    (testing "manager rights never flow through an API key"
      (is (not (policy/can-manage-session? (personal-key bob :owner) managed))))
    (testing "manager rights stay within the tenant"
      (is (not (policy/can-manage-session? foreign managed))))))

(deftest credential-rights-test
  (let [bob-personal {:kind "personal" :manager_user_id (:id bob)}
        org {:kind "organization" :manager_user_id (:id bob)}]
    (is (policy/can-create-credential? bob :personal))
    (is (not (policy/can-create-credential? bob :organization)))
    (is (policy/can-create-credential? admin :organization))
    (is (not (policy/can-create-credential? alice :personal)) "Members do not create keys")
    (is (policy/can-rotate-credential? bob bob-personal))
    (is (policy/can-manage-credential? admin bob-personal) "Admins may revoke any key")
    (is (not (policy/can-rotate-credential? admin bob-personal))
        "a rotated personal secret would act as its owner")
    (is (policy/can-rotate-credential? bob org))
    (is (policy/can-configure-credential? admin org))
    (is (not (policy/can-configure-credential? bob org)))
    (is (not (policy/can-configure-credential? admin bob-personal)))))

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

(deftest guest-policy-test
  (let [guest (assoc (principal #uuid "00000000-0000-0000-0000-000000000001" :admin) :guest? true)
        own (session guest "private")
        caps (policy/capabilities guest)]
    (testing "the single-user guest keeps recording, ownership and authoring"
      (is (policy/can-manage-session? guest own))
      (is (policy/session-visible-to? guest own false))
      (is (policy/can-publish-definitions? guest))
      (is (true? (:record caps)))
      (is (true? (:author_integrations caps))))
    (testing "sharing, the directory and People are off without login"
      (is (not (policy/can-share-session? guest own)))
      (is (not (policy/can-use-directory? guest)))
      (is (not (policy/can-manage-people? guest)))
      (is (false? (:share_sessions caps)))
      (is (false? (:manage_people caps))))
    (testing "real Admins are unaffected"
      (is (policy/can-share-session? admin (session admin "private")))
      (is (true? (:share_sessions (policy/capabilities admin)))))))

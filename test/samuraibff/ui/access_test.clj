(ns samuraibff.ui.access-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [samuraibff.ui.access :as access]))

(deftest access-state-test
  (testing "a missing access block keeps the legacy UI active"
    (is (= :active (access/access-state nil)))
    (is (access/active? nil)))
  (testing "server states map to keywords"
    (is (= :not-provisioned (access/access-state {:state "not_provisioned"})))
    (is (= :pending (access/access-state {:state "pending"})))
    (is (= :suspended (access/access-state {:state "suspended"})))
    (is (= :unavailable (access/access-state {:state "unavailable"})))
    (is (= :unavailable (access/access-state {:state "something-new"})))
    (is (not (access/active? {:state "suspended"})))))

(deftest capability-test
  (testing "capabilities from the server are used as given"
    (let [member {:state "active" :capabilities {:record true :manage_people false}}]
      (is (access/capability? member :record))
      (is (not (access/capability? member :manage_people)))
      (is (not (access/capability? member :author_integrations)))))
  (testing "inactive states grant nothing"
    (is (not (access/capability? {:state "suspended"} :record))))
  (testing "an older BFF without access keeps previous pages but hides People"
    (is (access/capability? nil :author_integrations))
    (is (not (access/capability? nil :manage_people)))))

(deftest error-mapping-test
  (testing "only short lowercase codes are accepted"
    (is (= "read-only-access" (access/error-code {:ok false :message "read-only-access"})))
    (is (nil? (access/error-code {:message "Some <b>server</b> text"})))
    (is (nil? (access/error-code nil))))
  (testing "account errors switch to an access state"
    (is (= :suspended (access/error-access-state 403 "account-suspended")))
    (is (= :unavailable (access/error-access-state 503 "authorization-unavailable")))
    (is (nil? (access/error-access-state 403 "read-only-access")))
    (is (nil? (access/error-access-state 500 "account-suspended"))))
  (testing "unknown codes use the fallback"
    (is (= "Try again." (access/error-message "weird" "Try again.")))
    (is (= "You have read-only access to this session."
           (access/error-message "read-only-access" "x")))))

(deftest labels-test
  (testing "audience labels"
    (is (= "Private" (access/audience-label "private" "Acme")))
    (is (= "Selected people" (access/audience-label "selected" "Acme")))
    (is (= "Everyone in Acme" (access/audience-label "tenant" "Acme")))
    (is (= "Everyone here" (access/audience-label "tenant" nil))))
  (testing "Sessions row labels"
    (is (= "By you · Private"
           (access/row-access-label {:visibility "private" :owner {:is_me true}} "Acme")))
    (is (= "By Alice · Shared"
           (access/row-access-label {:visibility "selected"
                                     :owner {:is_me false :display_name "Alice"}} "Acme")))
    (is (= "By Alice · Everyone in Acme"
           (access/row-access-label {:visibility "tenant"
                                     :owner {:is_me false :display_name "Alice"}} "Acme")))
    (is (nil? (access/row-access-label nil "Acme"))))
  (testing "detail labels"
    (is (= "By you · Selected people"
           (access/detail-access-label {:is_owner true :visibility "selected"} nil)))
    (is (= "By Bob · Shared with you · read-only"
           (access/detail-access-label {:is_owner false :visibility "selected"
                                        :owner {:display_name "Bob"}} nil)))))

(deftest share-request-body-test
  (testing "selected sends the complete recipient list"
    (is (= {:visibility "selected" :revision 3 :recipient_ids ["a" "b"]}
           (access/share-request-body {:visibility "selected" :recipient-ids ["a" "b" "a"]
                                       :revision 3}))))
  (testing "other choices never send recipients"
    (is (= {:visibility "tenant" :revision 0}
           (access/share-request-body {:visibility "tenant" :recipient-ids ["a"]})))))

(deftest recipients-test
  (testing "toggling keeps order and removes duplicates"
    (let [a {:id "a"} b {:id "b"}]
      (is (= [a b] (access/toggle-recipient [a] b true)))
      (is (= [a b] (access/toggle-recipient [a b] b true)))
      (is (= [b] (access/toggle-recipient [a b] a false)))))
  (testing "switching away from selected warns only with recipients"
    (is (access/clear-warning? "private" [{:id "a"}]))
    (is (not (access/clear-warning? "selected" [{:id "a"}])))
    (is (not (access/clear-warning? "tenant" [])))))

(deftest misc-labels-test
  (testing "URL host hides path and credentials"
    (is (= "hooks.acme.example" (access/url-host "https://hooks.acme.example/a?b=1")))
    (is (= "hooks.acme.example" (access/url-host "https://user:pw@hooks.acme.example:8443/x")))
    (is (= "not a url" (access/url-host "not a url"))))
  (testing "definition and owner labels"
    (is (= "Published" (access/definition-badge "published")))
    (is (nil? (access/definition-badge nil)))
    (is (= "You" (access/owner-name {:is_me true :display_name "Bob"})))
    (is (= "Alice" (access/owner-name {:is_me false :display_name "Alice"})))
    (is (nil? (access/owner-name nil)))))

(deftest people-labels-test
  (testing "status, setup and sync labels"
    (is (= {:label "Invited" :kind :muted} (access/person-status-label "pending")))
    (is (= :bad (:kind (access/person-status-label "suspended"))))
    (is (= "Setup email failed" (access/setup-label "email_failed")))
    (is (nil? (access/setup-label "complete")))
    (is (= "Sign-in disable pending — retry" (access/sync-pending-label "disable_pending")))
    (is (nil? (access/sync-pending-label "synced"))))
  (testing "roles are offered from least to most rights"
    (is (= ["member" "builder" "admin"] (mapv :value access/role-descriptions)))))

(deftest account-block-test
  (testing "the provisioned display name wins over token claims"
    (is (= "Bob Novak" (access/account-name {:access {:principal {:display_name "Bob Novak"}}
                                             :user {:preferred_username "bob"}})))
    (is (= "bob" (access/account-name {:user {:preferred_username "bob" :email "b@x.test"}})))
    (is (= "Signed in" (access/account-name {}))))
  (testing "initials use the first two words or the email local part"
    (is (= "BN" (access/initials "Bob Novak")))
    (is (= "A" (access/initials "alice")))
    (is (= "JD" (access/initials "jane.doe@acme.example")))
    (is (= "ÉL" (access/initials "élise laurent")))
    (is (= "?" (access/initials "")))
    (is (= "?" (access/initials nil)))))

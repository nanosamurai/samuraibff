(ns samuraibff.auth.oidc-test
  (:require
    [clojure.test :refer :all]
    [samuraibff.auth.oidc :as oidc]))

(deftest bearer-token-test
  (is (= "abc" (oidc/bearer-token "Bearer abc")))
  (is (= "abc" (oidc/bearer-token "bearer abc")))
  (is (= "abc" (oidc/bearer-token "  Bearer abc  ")))
  (is (nil? (oidc/bearer-token "Basic xyz")))
  (is (nil? (oidc/bearer-token nil))))

(deftest extract-token-precedence-test
  (let [config {:auth {:cookie-name "access_token"}}
        req {:headers {"authorization" "Bearer hdr"}
             :params {:token "q"}
             :cookies {"access_token" {:value "c"}}}]
    (is (= "hdr" (oidc/extract-token config req))))

  (let [config {:auth {:cookie-name "access_token"}}
        req {:headers {}
             :params {:token "q"}
             :cookies {"access_token" {:value "c"}}}]
    (is (= "q" (oidc/extract-token config req))))

  (let [config {:auth {:cookie-name "access_token"}}
        req {:headers {}
             :params {}
             :cookies {"access_token" {:value "c"}}}]
    (is (= "c" (oidc/extract-token config req))))

  (let [config {:auth {:cookie-name "t"}}
        req {:headers {} :params {} :cookies {"t" {:value "c"}}}]
    (is (= "c" (oidc/extract-token config req)))))

(deftest extract-tenant-from-claims-test
  (is (= "tenant-1" (oidc/extract-tenant-from-claims {:raw {:tenant_id "tenant-1"}})))
  (is (= "tenant-1" (oidc/extract-tenant-from-claims {:raw {:tenant_id ["tenant-1"]}}))
      "Keycloak user attributes often arrive as single-value lists")
  (is (= "org-2" (oidc/extract-tenant-from-claims {:raw {:org_id "org-2"}})))
  (is (= "abc" (oidc/extract-tenant-from-claims {:raw {:realm_access {:roles ["foo" "tenant:abc"]}}})))
  (is (nil? (oidc/extract-tenant-from-claims {:raw {}})))
  (is (nil? (oidc/extract-tenant-from-claims nil))))

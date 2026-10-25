(ns samuraibff.auth.oidc-test
  (:require
   [clojure.test :refer [deftest is]]
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
    (is (= "c" (oidc/extract-token config req))))

  (let [config {:auth {:cookie-name "access_token"}}
        req {:headers {}
             :params {}
             :cookies {"access_token" {:value "c"}}}]
    (is (= "c" (oidc/extract-token config req))))

  (let [config {:auth {:cookie-name "t"}}
        req {:headers {} :params {} :cookies {"t" {:value "c"}}}]
    (is (= "c" (oidc/extract-token config req)))))

(deftest request-parameters-never-authenticate
  (doseq [field [:params :query-params :form-params :body-params]
          token-key [:token "token" :access_token "access_token"]]
    (let [request {field {token-key "parameter-credential"}}]
      (is (nil? (oidc/extract-token {} request)))
      (is (= "cookie" (oidc/extract-token {}
                                          (assoc request :cookies {"access_token" {:value "cookie"}}))))
      (is (= "header" (oidc/extract-token {}
                                          (assoc request :headers {"Authorization" "Bearer header"}
                                                 :cookies {"access_token" {:value "cookie"}})))))))

(deftest extract-tenant-from-claims-test
  (is (= "tenant-1" (oidc/extract-tenant-from-claims {:raw {:tenant_id "tenant-1"}})))
  (is (= "tenant-1" (oidc/extract-tenant-from-claims {:raw {:tenant_id ["tenant-1"]}}))
      "Keycloak user attributes often arrive as single-value lists")
  (is (= "org-2" (oidc/extract-tenant-from-claims {:raw {:org_id "org-2"}})))
  (is (= "abc" (oidc/extract-tenant-from-claims {:raw {:realm_access {:roles ["foo" "tenant:abc"]}}})))
  (is (nil? (oidc/extract-tenant-from-claims {:raw {}})))
  (is (nil? (oidc/extract-tenant-from-claims nil))))

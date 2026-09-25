(ns samuraibff.http.recordings-pagination-integration-test
  "Exercise tenant-scoped pagination against PostgreSQL, including tied dates."
  (:require
   [clojure.test :refer [deftest is testing]]
   [next.jdbc :as jdbc]
   [samuraibff.http.recordings :as recordings]
   [samuraibff.testcontainers.postgres :as pg])
  (:import (java.util UUID)))

(deftest paginate-sessions-with-draft-filter-and-tenant-counts
  (pg/with-postgres [container]
    (let [ds (pg/datasource (pg/jdbc-url container) "drsynth" "drsynth")
          tenant (UUID/randomUUID)
          other-tenant (UUID/randomUUID)
          empty-tenant (UUID/randomUUID)
          session-ids (mapv #(UUID. 0 %) (range 1 206))
          handler (recordings/list-recordings-handler {:db {:ds ds} :config {:env :test}})
          list-page (fn [tenant-id params]
                      (let [response (handler {:auth/tenant-id (str tenant-id) :params params})]
                        (is (= 200 (:status response)))
                        (:body response)))]
      (pg/apply-schema! ds)
      (doseq [tenant-id [tenant other-tenant empty-tenant]]
        (jdbc/execute! ds ["INSERT INTO tenants (id, name) VALUES (?, 'Pagination test')" tenant-id]))
      (doseq [session-id session-ids]
        (jdbc/execute! ds
                       ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at)
                         VALUES (?, ?, ?, 'finished', '2026-01-01T00:00:00Z')"
                        session-id tenant session-id]))
      (doseq [[tenant-id status] [[tenant "created"] [other-tenant "created"] [other-tenant "finished"]]]
        (let [session-id (UUID/randomUUID)]
          (jdbc/execute! ds
                         ["INSERT INTO sessions (id, tenant_id, session_key, status, created_at)
                           VALUES (?, ?, ?, ?, '2026-01-02T00:00:00Z')"
                          session-id tenant-id session-id status])))
      (testing "Defaults preserve the existing 200-item limit and include drafts"
        (let [body (list-page tenant {})]
          (is (= 206 (:total body)))
          (is (= 1 (:drafts_count body)))
          (is (= 200 (count (:items body))))
          (is (= "created" (-> body :items first :status)))))
      (testing "Every page size traverses beyond 200 without duplicates, gaps, or draft rows"
        (doseq [page-size [20 30 50]]
          (let [pages (mapv #(list-page tenant {:limit page-size :offset % :show_drafts "false"})
                            (range 0 205 page-size))
                items (mapcat :items pages)]
            (is (every? #(= 205 (:total %)) pages))
            (is (every? #(= 1 (:drafts_count %)) pages))
            (is (= (mapv str (reverse session-ids)) (mapv :session_id items)))
            (is (every? #(false? (:has_recording %)) items))
            (is (every? #(nil? (get-in % [:recording :url])) items)))))
      (testing "Boolean and string query representations exclude drafts consistently"
        (doseq [params [{:show_drafts false} {"show_drafts" "false"}]]
          (is (= 205 (:total (list-page tenant params))))))
      (testing "Out-of-range and empty tenant pages retain correct scoped counts"
        (is (= {:items [] :total 205 :drafts_count 1}
               (select-keys (list-page tenant {:offset 1000 :show_drafts false})
                            [:items :total :drafts_count])))
        (is (= {:items [] :total 0 :drafts_count 0}
               (select-keys (list-page empty-tenant {}) [:items :total :drafts_count])))
        (is (= {:total 2 :drafts_count 1}
               (select-keys (list-page other-tenant {}) [:total :drafts_count]))))
      (testing "Deleting the final page's only row updates counts and leaves an empty page"
        (jdbc/execute! ds ["DELETE FROM sessions WHERE id = ?" (first session-ids)])
        (is (= {:items [] :total 204 :drafts_count 1}
               (select-keys (list-page tenant {:limit 1 :offset 204 :show_drafts false})
                            [:items :total :drafts_count])))))))

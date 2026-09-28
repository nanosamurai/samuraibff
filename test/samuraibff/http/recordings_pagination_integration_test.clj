(ns samuraibff.http.recordings-pagination-integration-test
  "Exercise tenant-scoped pagination against PostgreSQL, including tied dates."
  (:require
   [clojure.test :refer [deftest is testing]]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [samuraibff.http.recordings :as recordings]
   [samuraibff.testcontainers.postgres :as pg])
  (:import (java.sql Timestamp)
           (java.time Instant)
           (java.util UUID)))

(deftest paginate-sessions-with-draft-filter-and-tenant-counts
  (pg/with-postgres [container]
    (let [ds (pg/datasource (pg/jdbc-url container) "drsynth" "drsynth")
          tenant (UUID/randomUUID)
          other-tenant (UUID/randomUUID)
          empty-tenant (UUID/randomUUID)
          draft-only-tenant (UUID/randomUUID)
          session-ids (mapv #(UUID. 0 %) (range 1 206))
          created-at (Timestamp/from (Instant/parse "2026-01-01T00:00:00Z"))
          later (Timestamp/from (Instant/parse "2026-01-02T00:00:00Z"))
          insert! (fn [table rows]
                    (jdbc/execute! ds (sql/format {:insert-into table :values rows})))
          handler (recordings/list-recordings-handler {:db {:ds ds} :config {:env :test}})
          execute! jdbc/execute!
          execute-one! jdbc/execute-one!
          list-page (fn [tenant-id params]
                      (let [statements (atom 0)
                            response (with-redefs [jdbc/execute! (fn [& args]
                                                                   (swap! statements inc)
                                                                   (apply execute! args))
                                                   jdbc/execute-one! (fn [& args]
                                                                       (swap! statements inc)
                                                                       (apply execute-one! args))]
                                       (handler {:auth/tenant-id (str tenant-id) :params params}))]
                        (is (= 200 (:status response)))
                        (is (= 1 @statements) "Return items and both counts in one database statement")
                        (:body response)))]
      (pg/apply-schema! ds)
      (insert! :tenants (mapv (fn [id] {:id id :name "Pagination test"})
                              [tenant other-tenant empty-tenant draft-only-tenant]))
      (insert! :sessions (mapv (fn [id] {:id id :tenant_id tenant :session_key (str id)
                                         :status "finished" :created_at created-at}) session-ids))
      (insert! :sessions
               (mapv (fn [[tenant-id status]]
                       (let [id (UUID/randomUUID)]
                         {:id id :tenant_id tenant-id :session_key (str id)
                          :status status :created_at later}))
                     [[tenant "created"] [other-tenant "created"] [other-tenant "finished"]
                      [draft-only-tenant "created"]]))
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
      (testing "Empty, zero-limit, draft-only, and out-of-range pages retain scoped counts"
        (doseq [params [{:offset 1000 :show_drafts false} {:limit 0 :show_drafts false}]]
          (is (= {:items [] :total 205 :drafts_count 1}
                 (select-keys (list-page tenant params) [:items :total :drafts_count]))))
        (is (= {:items [] :total 0 :drafts_count 0}
               (select-keys (list-page empty-tenant {}) [:items :total :drafts_count])))
        (is (= {:items [] :total 0 :drafts_count 1}
               (select-keys (list-page draft-only-tenant {:show_drafts false})
                            [:items :total :drafts_count])))
        (is (= {:total 2 :drafts_count 1}
               (select-keys (list-page other-tenant {}) [:total :drafts_count]))))
      (testing "Metadata joins keep one row per session and choose the latest recording deterministically"
        (let [newest-id (last session-ids)
              second-id (nth session-ids 203)]
          (insert! :recordings
                   (mapv (fn [[id timestamp lang]]
                           {:id (UUID. 1 id) :session_id newest-id
                            :recording_url (str "s3://private/" id) :duration_s 60.0
                            :sample_rate 16000 :lang lang :created_at timestamp})
                         [[1 later "en"] [2 later "cs"] [3 created-at "de"]]))
          (insert! :session_transcripts
                   (mapv (fn [[session-id tenant-id type]]
                           {:id (UUID/randomUUID) :session_id session-id :tenant_id tenant-id
                            :full_text "Private transcript" :segments [:cast "[]" :jsonb]
                            :source "whisperx" :type type})
                         [[newest-id tenant "refined"] [newest-id tenant "final"]
                          [newest-id tenant "final"] [second-id tenant "refined"]
                          [second-id other-tenant "final"]]))
          (let [body (list-page tenant {:limit 2 :show_drafts false})
                [newest second-newest] (:items body)]
            (is (= 205 (:total body)))
            (is (= [(str newest-id) (str second-id)] (mapv :session_id (:items body))))
            (is (true? (:has_recording newest)))
            (is (= "cs" (get-in newest [:recording :lang])))
            (is (true? (:has_final_transcript newest)))
            (is (false? (:has_recording second-newest)))
            (is (false? (:has_final_transcript second-newest)) "Ignore a final transcript from another tenant")
            (is (nil? (get-in newest [:recording :url])))
            (is (not (.contains (pr-str body) "s3://private"))))))
      (testing "Deleting the final page's only row updates counts and leaves an empty page"
        (jdbc/execute! ds (sql/format {:delete-from :sessions :where [:= :id (first session-ids)]}))
        (is (= {:items [] :total 204 :drafts_count 1}
               (select-keys (list-page tenant {:limit 1 :offset 204 :show_drafts false})
                            [:items :total :drafts_count])))))))

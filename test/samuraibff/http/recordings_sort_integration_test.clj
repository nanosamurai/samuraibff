(ns samuraibff.http.recordings-sort-integration-test
  "Exercise the Sessions list sort against PostgreSQL.

  Two colleagues of one tenant own private and shared sessions; a third
  tenant owns a shared session. For every reader, scope, draft filter, sort
  key and direction the test checks the row order across pages, empty values
  last, the duration of each row, and that the rows and counts are the same
  as with the default sort."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [samuraibff.http.recordings :as recordings]
   [samuraibff.test-support.principals :as tp]
   [samuraibff.testcontainers.postgres :as pg]
   [samuraibff.ui.session-status :as session-status])
  (:import (java.sql Timestamp)
           (java.time Instant)
           (java.util UUID)))

(def ^:private base-instant (Instant/parse "2026-01-01T00:00:00Z"))

(defn- at
  "Return a Timestamp `seconds` after the base instant, or nil."
  [seconds]
  (when seconds
    (Timestamp/from (.plusSeconds ^Instant base-instant (long seconds)))))

(def ^:private fixtures
  "Sessions of the test. :created, :started and :ended are seconds after the
  base instant; :recording is the latest recording's duration_s (nil: no
  recording); :final? adds a final transcript."
  [{:n 1 :owner :alice :visibility "private" :status "finished" :title "alpha"
    :created 600 :started 600 :ended 690 :recording 88.0 :final? true}
   {:n 2 :owner :alice :visibility "private" :status "finished" :title "Bravo"
    :created 1200 :started 1200 :ended 1500}
   {:n 3 :owner :alice :visibility "tenant" :status "finished" :title "alpha"
    :created 1200 :started 1210 :ended 1300 :recording 90.0}
   {:n 4 :owner :alice :visibility "private" :status "created" :title nil :created 1800}
   {:n 5 :owner :alice :visibility "tenant" :status "active" :title "  delta "
    :created 2400 :started 2400}
   {:n 6 :owner :alice :visibility "private" :status "failed" :title "charlie"
    :created 3000 :started 3000 :ended 3060}
   {:n 7 :owner :alice :visibility "private" :status "finished" :title ""
    :created 3600 :recording 42.5}
   {:n 8 :owner :alice :visibility "private" :status "stopping" :title "echo"
    :created 4200 :started 4200 :recording 30.0}
   {:n 9 :owner :alice :visibility "private" :status "finished" :title "foxtrot"
    :created 4800 :started 4800 :ended 4700}
   {:n 10 :owner :alice :visibility "tenant" :status "created" :title "golf" :created 5400}
   {:n 11 :owner :bob :visibility "private" :status "finished" :title "hotel"
    :created 900 :started 900 :ended 1020}
   {:n 12 :owner :bob :visibility "tenant" :status "finished" :title "india"
    :created 1500 :started 1500 :ended 1520 :recording 20.0 :final? true}
   {:n 13 :owner :bob :visibility "private" :status "created" :title nil :created 2100}
   {:n 14 :owner :carol :visibility "tenant" :status "finished" :title "alpha"
    :created 300 :started 300 :ended 400}])

(defn- session-id [{:keys [n]}] (UUID. 0 (long n)))

(defn- expected-duration
  "Duration rule of the API: none for drafts and running sessions, else
  ended - started, else the latest recording's duration."
  [{:keys [status started ended recording]}]
  (cond
    (#{"created" "active"} status) nil
    (and started ended (>= ended started)) (double (- ended started))
    :else recording))

(def ^:private sort-value
  "Expected sort value of a fixture per API sort key."
  {"title" (fn [{:keys [title]}] (some-> title str/trim str/lower-case not-empty))
   "created_at" :created
   "started_at" :started
   "duration" expected-duration
   "status" (fn [{:keys [status recording final?]}]
              (session-status/label-sort-rank
               (:label (session-status/display-status {:status status
                                                       :has_recording (some? recording)
                                                       :has_final_transcript (boolean final?)}))))})

(defn- expected-order
  "Return fixture ids in the expected order: values by direction with the id
  as tie-breaker, then empty values by id in the same direction."
  [rows sort-key order]
  (let [value (sort-value sort-key)
        sign (if (= "asc" order) 1 -1)
        {empty-rows true valued-rows false} (group-by #(nil? (value %)) rows)]
    (mapv (comp str session-id)
          (concat (sort (fn [a b] (* sign (compare [(value a) (:n a)] [(value b) (:n b)]))) valued-rows)
                  (sort (fn [a b] (* sign (compare (:n a) (:n b)))) empty-rows)))))

(defn- readable-by
  "Fixtures that `reader` may read in `scope` (owner or tenant-wide audience
  in the reader's tenant)."
  [reader scope]
  (let [same-tenant? #(not= :carol (:owner %))
        mine? #(= reader (:owner %))
        readable? #(and (same-tenant? %) (or (mine? %) (= "tenant" (:visibility %))))]
    (filter (case scope
              "mine" #(and (readable? %) (mine? %))
              "shared" #(and (readable? %) (not (mine? %)))
              readable?)
            fixtures)))

(deftest sort-sessions-across-pages-scopes-and-drafts
  (pg/with-postgres [container]
    (let [ds (pg/datasource (pg/jdbc-url container) "drsynth" "drsynth")
          tenant (UUID/randomUUID)
          other-tenant (UUID/randomUUID)
          insert! (fn [table rows]
                    (when (seq rows)
                      (jdbc/execute! ds (sql/format {:insert-into table :values rows}))))
          handler (recordings/list-recordings-handler {:db {:ds ds} :config {:env :test}})]
      (pg/apply-schema! ds)
      (insert! :tenants [{:id tenant :name "Sort test"} {:id other-tenant :name "Other"}])
      (let [principals {:alice (tp/insert-principal! ds {:tenant-id tenant})
                        :bob (tp/insert-principal! ds {:tenant-id tenant})
                        :carol (tp/insert-principal! ds {:tenant-id other-tenant})}
            list-page (fn [reader params]
                        (let [response (handler (tp/request-for (get principals reader) {:params params}))]
                          (is (= 200 (:status response)) (pr-str reader params))
                          (:body response)))]
        (insert! :sessions
                 (mapv (fn [{:keys [owner visibility status title created started ended] :as row}]
                         {:id (session-id row)
                          :tenant_id (:tenant-id (get principals owner))
                          :user_id (:id (get principals owner))
                          :session_key (str (session-id row))
                          :title title :status status :visibility visibility
                          :created_at (at created) :started_at (at started) :ended_at (at ended)})
                       fixtures))
        (insert! :recordings
                 (for [{:keys [recording created] :as row} fixtures
                       :when recording
                       [offset duration] [[0 999.0] [60 recording]]]
                   {:id (UUID/randomUUID) :session_id (session-id row)
                    :recording_url "s3://private/test" :duration_s duration
                    :sample_rate 16000 :lang "en" :created_at (at (+ created offset))}))
        (insert! :session_transcripts
                 (for [{:keys [owner final?] :as row} fixtures
                       :when final?]
                   {:id (UUID/randomUUID) :session_id (session-id row)
                    :tenant_id (:tenant-id (get principals owner))
                    :full_text "Final" :segments [:cast "[]" :jsonb]
                    :source "whisperx" :type "final"}))
        (doseq [reader [:alice :bob]
                scope ["all" "mine" "shared"]
                show-drafts? [true false]]
          (let [base {:scope scope :show_drafts (str show-drafts?) :limit 1000}
                default-body (list-page reader base)
                readable (readable-by reader scope)
                listed (cond->> readable
                         (not show-drafts?) (remove #(= "created" (:status %))))
                context (pr-str {:reader reader :scope scope :show-drafts? show-drafts?})]
            (testing (str "default sort and access rules are unchanged " context)
              (is (= (set (map (comp str session-id) listed))
                     (set (map :session_id (:items default-body)))))
              (is (= (expected-order listed "created_at" "desc")
                     (mapv :session_id (:items default-body))))
              (is (= (count listed) (:total default-body)))
              (is (= (count (filter #(= "created" (:status %)) readable)) (:drafts_count default-body))))
            (testing (str "each row carries the session duration " context)
              (let [by-id (into {} (map (juxt (comp str session-id) identity)) listed)]
                (doseq [{:keys [session_id duration_s]} (:items default-body)]
                  (is (= (expected-duration (by-id session_id)) duration_s) session_id))))
            (doseq [sort-key ["title" "created_at" "started_at" "duration" "status"]
                    order ["asc" "desc"]]
              (let [params (assoc base :sort sort-key :order order)
                    body (list-page reader params)
                    expected (expected-order listed sort-key order)
                    pages (mapv #(list-page reader (assoc params :limit 2 :offset %))
                                (range 0 (max 1 (count listed)) 2))]
                (testing (str "sort " sort-key " " order " " context)
                  (is (= expected (mapv :session_id (:items body))))
                  (is (= [sort-key order] ((juxt :sort :order) body)))
                  (is (= (select-keys default-body [:total :drafts_count :scope])
                         (select-keys body [:total :drafts_count :scope]))
                      "Sorting does not change the counts")
                  (is (= expected (vec (mapcat #(map :session_id (:items %)) pages)))
                      "Pages of two follow the same order without gaps or duplicates")
                  (is (every? #(= (:total default-body) (:total %)) pages)))))))
        (testing "a session of another tenant never appears in any sort"
          (doseq [sort-key ["title" "status" "duration"]]
            (is (not-any? #(= (str (session-id (last fixtures))) (:session_id %))
                          (:items (list-page :alice {:sort sort-key :order "asc" :limit 1000}))))))
        (testing "the other tenant reads only its own session"
          (is (= [(str (session-id (last fixtures)))]
                 (mapv :session_id (:items (list-page :carol {:sort "title" :order "desc"}))))))))))

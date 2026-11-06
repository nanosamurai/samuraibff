(ns samuraibff.http.recordings-sort-test
  "Docker-free checks of the Sessions list sort parameters: handler
  validation and the fixed SQL that each sort key produces."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [honey.sql :as sql]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.http.recordings :as recordings])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(def ^:private principal
  {:id (UUID/randomUUID) :tenant-id (UUID/randomUUID) :type :human :role :member :status :active})

(defn- list-with
  "Call the list handler with query params; capture the options passed to
  the DB layer. Returns {:status int :body map :opts map-or-nil}."
  [params]
  (let [captured (atom nil)
        handler (recordings/list-recordings-handler {:db {:ds (reify DataSource)} :config {:env :test}})
        response (with-redefs [db.recordings/list-sessions-for-principal
                               (fn [_ _ opts]
                                 (reset! captured opts)
                                 {:items [] :total 0 :drafts_count 0})]
                   (handler {:auth/principal principal
                             :auth/access-state :active
                             :auth/tenant-id (str (:tenant-id principal))
                             :params params}))]
    {:status (:status response)
     :body (:body response)
     :opts @captured}))

(deftest list-sort-parameters-test
  (testing "without sort parameters the list keeps the newest-first order"
    (let [{:keys [status body opts]} (list-with {})]
      (is (= 200 status))
      (is (= {:sort :created_at :order :desc} (select-keys opts [:sort :order])))
      (is (= ["created_at" "desc"] ((juxt :sort :order) body)))))
  (testing "every allowed sort and order reaches the query as a fixed keyword"
    (doseq [sort-param ["title" "created_at" "started_at" "duration" "status"]
            order-param ["asc" "desc"]
            params [{:sort sort-param :order order-param} {"sort" sort-param "order" order-param}]]
      (let [{:keys [status body opts]} (list-with params)]
        (is (= 200 status) (pr-str params))
        (is (= {:sort (keyword sort-param) :order (keyword order-param)}
               (select-keys opts [:sort :order])))
        (is (= [sort-param order-param] ((juxt :sort :order) body))))))
  (testing "other values return 400 and never reach the database"
    (doseq [params [{:sort "actions"} {:sort "s.id; DROP TABLE sessions"} {:sort ""}
                    {:sort "TITLE"} {:order "up"} {:order "asc nulls first"}
                    {"sort" "user_id"} {"order" ""} {:sort "title" :order "sideways"}]]
      (let [{:keys [status body opts]} (list-with params)]
        (is (= 400 status) (pr-str params))
        (is (= {:ok false :message "invalid-sort"} body))
        (is (nil? opts) "No query runs for an invalid sort")))))

(deftest sessions-page-query-sort-test
  (testing "the default order is created_at desc with an id tie-breaker on the page and the outer select"
    (let [[sql-text] (sql/format (db.recordings/sessions-page-query principal {}))]
      (is (str/includes? sql-text "s.created_at AS sort_value"))
      (is (str/includes? sql-text "ORDER BY sort_value DESC NULLS LAST, s.id DESC LIMIT"))
      (is (str/ends-with? sql-text "ORDER BY p.sort_value DESC NULLS LAST, p.id DESC"))))
  (testing "a duration sort uses the selected duration column"
    (let [[sql-text] (sql/format (db.recordings/sessions-page-query principal {:sort :duration :order :asc}))]
      (is (not (str/includes? sql-text "sort_value")))
      (is (str/includes? sql-text "ORDER BY session_duration_s ASC NULLS LAST, s.id ASC LIMIT"))
      (is (str/ends-with? sql-text "ORDER BY p.session_duration_s ASC NULLS LAST, p.id ASC"))))
  (testing "sort keys and orders outside the allow-list are rejected"
    (is (= #{:title :created_at :started_at :duration :status} db.recordings/sort-keys))
    (is (= #{:asc :desc} db.recordings/sort-orders))
    (doseq [opts [{:sort :user_id} {:sort "title"} {:order :sideways} {:order "asc"}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (db.recordings/sessions-page-query principal opts))
          (pr-str opts)))))

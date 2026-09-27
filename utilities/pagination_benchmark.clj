(require '[honey.sql :as sql]
         '[jsonista.core :as json]
         '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as rs]
         '[samuraibff.db.recordings :as recordings]
         '[samuraibff.testcontainers.postgres :as pg])
(import '(org.testcontainers.containers PostgreSQLContainer))

(load-file "target/pagination-original-recordings.clj")
(def original-list recordings/list-sessions-for-tenant)
(def original-count (resolve 'samuraibff.db.recordings/count-sessions-for-tenant))
(def original-count-fn @original-count)
(require 'samuraibff.db.recordings :reload)

(defn original-queries
  "Capture the original two parameterized statements without executing them."
  [ds tenant opts]
  (let [queries (atom [])
        capture (fn [_ query _] (swap! queries conj query) [])]
    (with-redefs [jdbc/execute! capture jdbc/execute-one! capture]
      (original-count-fn ds tenant (:show-drafts? opts))
      (original-list ds tenant opts))
    @queries))

(defn window-query
  "Build the one-statement window alternative with the same page-local joins.
  Both window counts precede the draft filter so the draft count stays correct.
  Empty pages require a fallback not included in these timings."
  [tenant opts]
  (let [aggregate (recordings/sessions-page-query tenant opts)
        counts (ffirst (:from aggregate))
        page (ffirst (:left-join aggregate))
        windows (mapv (fn [[expression alias]] [[:over [expression {}]] alias])
                      (:select counts))
        counted (assoc counts :select (into (:select page) windows))
        window-page (-> page
                        (assoc :from [[counted :counted]])
                        (update :select into [:total :drafts_count])
                        (assoc :where (if (:show-drafts? opts)
                                        true
                                        [:is-distinct-from :status "created"])))]
    (-> aggregate
        (assoc :from [[window-page :s]])
        (update :select #(into [:s.total :s.drafts_count] (drop 2 %)))
        (update :left-join #(vec (drop 2 %))))))

(defn filtered-window-query
  "Build COUNT(*) OVER() after filtering, with a separate draft aggregate
  inside the same statement. Empty pages still need an untimed fallback."
  [tenant opts]
  (let [aggregate (recordings/sessions-page-query tenant opts)
        page (-> (ffirst (:left-join aggregate))
                 (update :select conj [[:over [[:count :*] {}]] :total]))
        drafts {:select [[[:count :*] :drafts_count]] :from [:sessions]
                :where [:and [:= :tenant_id tenant] [:= :status "created"]]}]
    (-> aggregate
        (assoc :from [[page :s]] :cross-join [[drafts :counts]])
        (update :select #(into [:s.total :counts.drafts_count] (drop 2 %)))
        (update :left-join #(vec (drop 2 %))))))

(defn explain!
  "Return a PostgreSQL JSON execution plan for a parameterized statement."
  [connection [statement & params]]
  (-> (jdbc/execute-one! connection
                         (into [(str "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " statement)] params)
                         {:builder-fn rs/as-unqualified-lower-maps})
      vals first str json/read-value first))

(defn seed!
  "Seed only a disposable database with four tenants, three recordings and
  two transcripts per session, and ten percent drafts per tenant."
  [connection total]
  (jdbc/execute! connection ["TRUNCATE tenants CASCADE"])
  (jdbc/execute! connection ["INSERT INTO tenants(id,name)
                              SELECT md5('tenant-' || n)::uuid, 'Benchmark'
                              FROM generate_series(0,3) n"])
  (jdbc/execute! connection
                 ["INSERT INTO sessions(id,tenant_id,session_key,title,status,created_at)
                   SELECT md5('session-' || n)::uuid, md5('tenant-' || (n%4))::uuid,
                     'session-' || n, repeat('Benchmark title ', 4),
                     CASE WHEN (n/4)%10=0 THEN 'created' ELSE 'finished' END,
                     '2026-01-01'::timestamptz + n * interval '1 second'
                   FROM generate_series(0,? - 1) n" total])
  (jdbc/execute! connection
                 ["INSERT INTO recordings(id,session_id,recording_url,duration_s,sample_rate,lang,created_at)
                   SELECT md5(s.id::text || '-' || n)::uuid,s.id,'s3://benchmark/' || n,
                     60,16000,'en',s.created_at + n * interval '1 second'
                   FROM sessions s CROSS JOIN generate_series(1,3) n"])
  (jdbc/execute! connection
                 ["INSERT INTO session_transcripts(id,session_id,tenant_id,full_text,segments,source,type,created_at)
                   SELECT md5(s.id::text || '-' || n)::uuid,s.id,s.tenant_id,
                     repeat('Benchmark transcript ',50),'[]'::jsonb,'whisperx',
                     CASE WHEN n=1 THEN 'refined' ELSE 'final' END,s.created_at
                   FROM sessions s CROSS JOIN generate_series(1,2) n"])
  (doseq [table ["sessions" "recordings" "session_transcripts"]]
    (jdbc/execute! connection [(str "VACUUM (ANALYZE) " table)])))

(defn measure!
  "Warm each variant once, then interleave seven measured executions.
  Report median server execution time and save complete sample plans."
  [connection ds tenant label opts]
  (let [variants (array-map
                  :original (original-queries ds tenant opts)
                  :window [(sql/format (window-query tenant opts))]
                  :filtered-window [(sql/format (filtered-window-query tenant opts))]
                  :aggregate [(sql/format (recordings/sessions-page-query tenant opts))])
        samples (atom {})]
    (doseq [iteration (range 8)
            [variant queries] variants]
      (let [plans (mapv #(explain! connection %) queries)
            elapsed (reduce + (map #(get % "Execution Time") plans))]
        (when (zero? iteration)
          (spit (str "target/pagination-plan-" label "-" (name variant) ".json")
                (json/write-value-as-string plans)))
        (when (pos? iteration)
          (swap! samples update variant (fnil conj []) elapsed))))
    (let [result {:case label :options opts
                  :median-ms (update-vals @samples #(nth (vec (sort %)) 3))}]
      (prn result)
      result)))

(let [container (doto (PostgreSQLContainer. "postgres:18.1-alpine")
                  (.withDatabaseName "pagination_benchmark")
                  (.withUsername "benchmark")
                  (.withPassword "benchmark")
                  (.setPortBindings ["127.0.0.1::5432"])
                  (.start))]
  (try
    (let [ds (pg/datasource (pg/jdbc-url container) "benchmark" "benchmark")
          results (atom [])]
      (pg/apply-schema! ds)
      (with-open [connection (jdbc/get-connection ds)]
        (let [tenant (-> (jdbc/execute-one! connection ["SELECT md5('tenant-0')::uuid AS id"]
                                            {:builder-fn rs/as-unqualified-lower-maps}) :id)]
          (doseq [total [1240 80000]]
            (seed! connection total)
            (doseq [show-drafts? [false true]
                    offset [0 (- (if show-drafts? (quot total 4) (* 9 (quot total 40))) 20)]]
              (swap! results conj
                     (measure! connection ds tenant
                               (str total "-" show-drafts? "-" offset)
                               {:limit 20 :offset offset :show-drafts? show-drafts?}))))
          (jdbc/execute! connection
                         ["CREATE INDEX benchmark_sessions_page ON sessions
                           (tenant_id, created_at DESC, id DESC) INCLUDE (status)"])
          (jdbc/execute! connection ["VACUUM (ANALYZE) sessions"])
          (doseq [offset [0 17980]]
            (swap! results conj
                   (measure! connection ds tenant (str "80000-indexed-false-" offset)
                             {:limit 20 :offset offset :show-drafts? false})))
          (spit "target/pagination-benchmark-results.json" (json/write-value-as-string @results)))))
    (finally (.stop container))))

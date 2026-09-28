(ns samuraibff.db.recordings
  "DB access for recordings/sessions/transcripts used by the UI.

  This namespace provides tenant-scoped queries for the Recordings UI.

  Tables (see migrations 0001 + persistor 0002):
  - sessions
  - recordings
  - session_transcripts (append-only transcript records)

  Public API:
  - `sessions-page-query`
  - `list-sessions-for-tenant`
  - `find-session-by-id`
  - `list-transcript-records`
  - `delete-session!`

  Query execution functions accept a next.jdbc datasource, typically provided by the
  Integrant `:samuraibff/db` component as `(:ds db)`.

  Security:
  - Every query is scoped by tenant-id.
  - Callers must supply the authenticated tenant-id."
  (:require
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [org.corfield.logging4j2 :as log])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(defn sessions-page-query
  "Build a parameterized HoneySQL page query for an authenticated tenant UUID.

  Options are :limit (integer, default 200), :offset (integer, default 0), and
  :show-drafts? (boolean, default true). Returns a HoneySQL map. Counts and rows
  share one statement snapshot. A counts-only row survives an empty page;
  recording and transcript lookups run only for sessions on the selected page."
  [tenant-id {:keys [limit offset show-drafts?]
              :or {limit 200 offset 0 show-drafts? true}}]
  (let [visible-status [:is-distinct-from :status "created"]
        counts {:select [[(if show-drafts?
                            [:count :*]
                            [:filter [:count :*] {:where visible-status}]) :total]
                         [[:filter [:count :*] {:where [:= :status "created"]}] :drafts_count]]
                :from [:sessions]
                :where [:= :tenant_id tenant-id]}
        page {:select [:id :tenant_id :session_key :title :status :started_at :ended_at :created_at]
              :from [:sessions]
              :where (cond-> [:and [:= :tenant_id tenant-id]]
                       (not show-drafts?) (conj visible-status))
              :order-by [[:created_at :desc] [:id :desc]]
              :limit (long limit)
              :offset (long offset)}
        recording {:select [:session_id [:created_at :recording_created_at] :duration_s :sample_rate :lang]
                   :from [:recordings]
                   :where [:= :session_id :s.id]
                   :order-by [[:created_at :desc] [:id :desc]]
                   :limit 1}
        final-transcript {:select [:session_id]
                          :from [:session_transcripts]
                          :where [:and [:= :session_id :s.id]
                                  [:= :tenant_id tenant-id]
                                  [:= :type "final"]]
                          :limit 1}]
    {:select [:counts.total :counts.drafts_count
              :s.id :s.session_key :s.title :s.status :s.started_at :s.ended_at :s.created_at
              :lr.recording_created_at :lr.duration_s :lr.sample_rate :lr.lang
              [[:is-not :lr.session_id nil] :has_recording]
              [[:is-not :ft.session_id nil] :has_final_transcript]]
     :from [[counts :counts]]
     :left-join [[page :s] true
                 [[:lateral recording] :lr] true
                 [[:lateral final-transcript] :ft] true]
     :order-by [[:s.created_at :desc] [:s.id :desc]]}))

(defn list-sessions-for-tenant
  "Fetch one page and both counts for an authenticated tenant UUID.

  Accepts a DataSource and options documented by sessions-page-query. Returns
  {:items [session maps], :total integer, :drafts_count integer}, with unqualified
  lower-case keys. Items include session fields, latest recording metadata, and
  :has_recording / :has_final_transcript booleans. Empty pages retain counts.
  Throws for a missing datasource, invalid tenant UUID, or database errors."
  [^DataSource ds ^UUID tenant-id opts]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "list-sessions-for-tenant missing required params"
                    {:tenant-id tenant-id})))
  (try
    (let [rows (jdbc/execute! ds (sql/format (sessions-page-query tenant-id opts))
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (assoc (select-keys (first rows) [:total :drafts_count])
             :items (into [] (comp (filter :id)
                                   (map #(dissoc % :total :drafts_count))) rows)))
    (catch Exception e
      (log/error e "DB query failed (list-sessions-for-tenant)" {:tenant-id (str tenant-id)})
      (throw e))))

(defn find-session-by-id
  "Find a session row by id, scoped to tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID

  Returns:
  - map (unqualified keys) or nil."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "find-session-by-id missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (jdbc/execute-one!
   ds
   ["SELECT id, session_key, tenant_id, user_id, title, status, started_at, ended_at, stream_controls, created_at\n      FROM sessions\n      WHERE tenant_id=? AND id=?"
    tenant-id session-id]
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn find-latest-recording
  "Find the latest recording row for a session, scoped to tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID

  Returns:
  - map with keys (unqualified):
      :id :session_id :recording_url :duration_s :sample_rate :lang :created_at
    or nil when no recording exists."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "find-latest-recording missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (jdbc/execute-one!
   ds
   ["SELECT r.id, r.session_id, r.recording_url, r.duration_s, r.sample_rate, r.lang, r.created_at\n      FROM recordings r\n      JOIN sessions s ON s.id = r.session_id\n      WHERE s.tenant_id = ? AND s.id = ?\n      ORDER BY r.created_at DESC\n      LIMIT 1"
    tenant-id session-id]
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn list-transcript-records
  "List transcript records for a session, scoped to tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID
  - opts: map
      :type (string) optional, e.g. \"refined\" or \"final\"
      :limit int (default 500)

  Returns:
  - vector of transcript record maps (unqualified keys)."
  [^DataSource ds ^UUID tenant-id ^UUID session-id {:keys [type limit track-id]
                                                    :or {limit 500}}]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "list-transcript-records missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (let [base-q (-> (h/select :id
                             :session_id
                             :recording_id
                             :tenant_id
                             :user_id
                             :full_text
                             :lang
                             :duration_s
                             :segments
                             :created_at
                             :source
                             :type
                             :model
                             [[:coalesce :track_id "whisperx"] :track_id]
                             :window_length
                             :segment_start_s
                             :segment_end_s
                             :supersedes_seq
                             :event_created_at_ns)
                   (h/from :session_transcripts)
                   (h/where [:= :tenant_id tenant-id]
                            [:= :session_id session-id])
                   (h/order-by [:created_at :asc])
                   (h/limit (long limit)))
        q (cond-> base-q
            (some? type) (h/where [:= :type (str type)])
            (some? track-id) (h/where [:= [:coalesce :track_id "whisperx"] track-id]))
        sqlvec (sql/format q)]
    (vec (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))))

(defn delete-session!
  "Delete a session (and cascaded recordings/transcripts), scoped to tenant.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID

  Returns:
  - {:deleted? boolean}

  Notes:
  - Relies on FK ON DELETE CASCADE from recordings/session_transcripts to sessions.
  - If the session does not exist for tenant, returns {:deleted? false}."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "delete-session! missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (let [res (jdbc/execute-one!
             ds
             ["DELETE FROM sessions WHERE tenant_id=? AND id=?" tenant-id session-id])]
    {:deleted? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

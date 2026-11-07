(ns samuraibff.db.recordings
  "DB access for recordings/sessions/transcripts used by the UI.

  This namespace provides tenant-scoped queries for the Recordings UI.

  Tables (see migrations 0001 + persistor 0002):
  - sessions
  - recordings
  - session_transcripts (append-only transcript records)

  Public API:
  - `sort-keys`, `sort-orders`
  - `sessions-page-query`
  - `list-sessions-for-principal`
  - `find-session-by-id`
  - `list-transcript-records`
  - `list-transcript-sources`
  - `delete-session!`

  Query execution functions accept a next.jdbc datasource, typically provided by the
  Integrant `:samuraibff/db` component as `(:ds db)`.

  Security:
  - Every query is scoped by tenant-id.
  - Session lists apply the principal read policy from
    `samuraibff.db.session-access`. Single-session reads are tenant-scoped
    helpers; HTTP handlers authorize the session first."
  (:require
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.db.sessions :as db.sessions]
   [samuraibff.ui.session-status :as session-status])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(def sort-keys
  "Sort keys that `sessions-page-query` accepts. Each maps to a fixed
  HoneySQL expression; no caller value goes into SQL."
  #{:title :created_at :started_at :duration :status})

(def sort-orders
  "Sort directions that `sessions-page-query` accepts."
  #{:asc :desc})

(def ^:private latest-recording-duration
  "HoneySQL subquery: `duration_s` of the latest recording of session `s`.
  Uses the same order as the latest-recording lookup of the page."
  {:select [:r.duration_s]
   :from [[:recordings :r]]
   :where [:= :r.session_id :s.id]
   :order-by [[:r.created_at :desc] [:r.id :desc]]
   :limit 1})

(def ^:private session-duration-expr
  "HoneySQL expression: duration of session `s` in seconds (double), or NULL.

  Drafts (`created`) and running sessions (`active`) have no duration. A
  session from a stored file (`db.sessions/file-sources`: an upload or a
  re-transcription) starts and ends when it is created, so it uses the latest
  recording's `duration_s`: NULL until the recorder has converted the file.
  Other sessions use `ended_at - started_at`. When the session times are
  missing or out of order, the latest recording's `duration_s` is used."
  [:case
   [:in :s.status ["created" "active"]] nil
   [:in (db.sessions/session-source-expr :s.session_settings) (sort db.sessions/file-sources)]
   latest-recording-duration
   [:>= :s.ended_at :s.started_at] [:date_part [:inline "epoch"] [:- :s.ended_at :s.started_at]]
   :else latest-recording-duration])

(defn- status-rank-expr
  "Return a HoneySQL expression: the sort rank of the status label of session
  `s`, as `samuraibff.ui.session-status/display-status` derives it.

  Each branch mirrors one branch of `display-status` and takes its rank from
  the label that `display-status` returns for an example session, so labels
  and ranks have one source. Inputs: tenant UUID for the final transcript
  lookup. Returns: CASE expression with inline integer ranks."
  [tenant-id]
  (let [rank-of (fn [session]
                  [:inline (session-status/label-sort-rank
                            (:label (session-status/display-status session)))])
        has-final [:exists {:select [1]
                            :from [[:session_transcripts :st]]
                            :where [:and [:= :st.session_id :s.id]
                                    [:= :st.tenant_id tenant-id]
                                    [:= :st.type "final"]]}]
        has-recording [:exists {:select [1]
                                :from [[:recordings :r]]
                                :where [:= :r.session_id :s.id]}]
        file-source [:in (db.sessions/session-source-expr :s.session_settings)
                     (sort db.sessions/file-sources)]]
    [:case
     [:= :s.status "failed"] (rank-of {:status "failed"})
     [:= :s.status "active"] (rank-of {:status "active"})
     [:= :s.status "created"] (rank-of {:status "created"})
     [:and [:= :s.status "finished"] has-final] (rank-of {:status "finished" :has_final_transcript true})
     [:and [:= :s.status "finished"] file-source] (rank-of {:status "finished" :source db.sessions/upload-source})
     [:= :s.status "finished"] (rank-of {:status "finished"})
     has-recording (rank-of {:status "other" :has_recording true})
     :else (rank-of {:status "other"})]))

(defn- sort-expr
  "Return the fixed HoneySQL sort expression on sessions alias `s`.

  Inputs: sort key from `sort-keys` and the tenant UUID. Title sorts ignore
  case and treat a blank title as empty. Returns nil for :duration: the page
  then sorts by its `session_duration_s` column, so the duration is computed
  once. Throws ex-info for an unknown key."
  [sort-key tenant-id]
  (case sort-key
    :title [:nullif [:lower [:trim :s.title]] ""]
    :created_at :s.created_at
    :started_at :s.started_at
    :duration nil
    :status (status-rank-expr tenant-id)
    (throw (ex-info "Unsupported sessions sort key" {:sort sort-key}))))

(defn- order-by-clauses
  "Return HoneySQL :order-by clauses for a sort value column and an id column.

  Empty sort values come last in both directions. The id is the tie-breaker,
  so pages stay stable. Throws ex-info for an unknown direction."
  [sort-column id-column order]
  (case order
    :asc [[sort-column :asc-nulls-last] [id-column :asc]]
    :desc [[sort-column :desc-nulls-last] [id-column :desc]]
    (throw (ex-info "Unsupported sessions sort order" {:order order}))))

(defn sessions-page-query
  "Build a parameterized HoneySQL page query of the sessions a principal may read.

  Inputs: principal map (see `samuraibff.access.policy`) and options :limit
  (integer, default 200), :offset (integer, default 0), :show-drafts?
  (boolean, default true), :scope (:all | :mine | :shared, default :all),
  :sort (a `sort-keys` keyword, default :created_at) and :order (:asc | :desc,
  default :desc). Returns a HoneySQL map. The access policy and scope apply
  before counts and pagination; the sort changes only the row order. Rows
  include :session_duration_s (see `session-duration-expr`) and :source
  (the `session_settings.source` text, such as `db.sessions/upload-source`,
  else nil). Throws ex-info for
  an unknown sort key or order. Counts and rows share one statement snapshot.
  A counts-only row survives an empty page; recording and transcript lookups
  run only for sessions on the selected page."
  [principal {:keys [limit offset show-drafts? scope sort order]
              :or {limit 200 offset 0 show-drafts? true scope :all
                   sort :created_at order :desc}}]
  (let [tenant-id (:tenant-id principal)
        sort-value (sort-expr sort tenant-id)
        [page-sort-column outer-sort-column] (if sort-value
                                               [:sort_value :p.sort_value]
                                               [:session_duration_s :p.session_duration_s])
        page-order (order-by-clauses page-sort-column :s.id order)
        outer-order (order-by-clauses outer-sort-column :p.id order)
        accessible (session-access/scope-condition principal scope)
        visible-status [:is-distinct-from :s.status "created"]
        counts {:select [[(if show-drafts?
                            [:count :*]
                            [:filter [:count :*] {:where visible-status}]) :total]
                         [[:filter [:count :*] {:where [:= :s.status "created"]}] :drafts_count]]
                :from [[:sessions :s]]
                :where accessible}
        page {:select (cond-> [:s.id :s.tenant_id :s.user_id :s.session_key :s.title :s.status
                               :s.started_at :s.ended_at :s.created_at :s.visibility
                               :s.created_via_credential_id
                               [:o.name :owner_name] [:o.email :owner_email]
                               [:o.principal_type :owner_type] [:cv.name :created_via_name]
                               [(session-access/key-manager-expr principal) :key_manager]
                               [(db.sessions/session-source-expr :s.session_settings) :source]
                               [session-duration-expr :session_duration_s]]
                        sort-value (conj [sort-value :sort_value]))
              :from [[:sessions :s]]
              :left-join [[:app_users :o] [:= :o.id :s.user_id]
                          [:api_credentials :cv] [:and [:= :cv.id :s.created_via_credential_id]
                                                  [:= :cv.tenant_id :s.tenant_id]]]
              :where (cond-> [:and accessible]
                       (not show-drafts?) (conj visible-status))
              :order-by page-order
              :limit (long limit)
              :offset (long offset)}
        recording {:select [:session_id [:created_at :recording_created_at] :duration_s :sample_rate :lang]
                   :from [:recordings]
                   :where [:= :session_id :p.id]
                   :order-by [[:created_at :desc] [:id :desc]]
                   :limit 1}
        final-transcript {:select [:session_id]
                          :from [:session_transcripts]
                          :where [:and [:= :session_id :p.id]
                                  [:= :tenant_id tenant-id]
                                  [:= :type "final"]]
                          :limit 1}]
    {:select [:counts.total :counts.drafts_count
              :p.id :p.user_id :p.session_key :p.title :p.status :p.started_at :p.ended_at :p.created_at
              :p.visibility :p.owner_name :p.owner_email
              :p.created_via_credential_id :p.owner_type :p.created_via_name :p.key_manager
              :p.session_duration_s :p.source
              :lr.recording_created_at :lr.duration_s :lr.sample_rate :lr.lang
              [[:is-not :lr.session_id nil] :has_recording]
              [[:is-not :ft.session_id nil] :has_final_transcript]]
     :from [[counts :counts]]
     :left-join [[page :p] true
                 [[:lateral recording] :lr] true
                 [[:lateral final-transcript] :ft] true]
     :order-by outer-order}))

(defn list-sessions-for-principal
  "Fetch one page and both counts of the sessions a principal may read.

  Accepts a DataSource, an active principal map and options documented by
  sessions-page-query. Returns {:items [session maps], :total integer,
  :drafts_count integer}, with unqualified lower-case keys. Items include
  session fields, owner display fields, the audience, latest recording metadata
  and :has_recording / :has_final_transcript booleans. Empty pages retain
  counts. Throws for a missing datasource, invalid principal or database errors."
  [^DataSource ds principal opts]
  (when-not (and ds (instance? UUID (:tenant-id principal)) (instance? UUID (:id principal)))
    (throw (ex-info "list-sessions-for-principal missing required params"
                    {:tenant-id (:tenant-id principal)})))
  (try
    (let [rows (jdbc/execute! ds (sql/format (sessions-page-query principal opts))
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (assoc (select-keys (first rows) [:total :drafts_count])
             :items (into [] (comp (filter :id)
                                   (map #(dissoc % :total :drafts_count))) rows)))
    (catch Exception e
      (log/error e "DB query failed (list-sessions-for-principal)"
                 {:tenant-id (str (:tenant-id principal))})
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

(defn list-transcript-sources
  "List the stored transcript sources of a session, scoped to tenant.

  Inputs: DataSource, tenant UUID, session UUID.
  Returns: vector of [stage track-id] string pairs for stored `final` and
  `refined` records, e.g. [[\"final\" \"whisperx\"]]. NULL track IDs mean
  `whisperx`."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "list-transcript-sources missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (->> (jdbc/execute! ds
                      (sql/format {:select-distinct [:type [[:coalesce :track_id "whisperx"] :track_id]]
                                   :from [:session_transcripts]
                                   :where [:and [:= :tenant_id tenant-id]
                                           [:= :session_id session-id]
                                           [:in :type ["final" "refined"]]]})
                      {:builder-fn rs/as-unqualified-lower-maps})
       (mapv (juxt :type :track_id))))

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

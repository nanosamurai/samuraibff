(ns samuraibff.db.recordings
  "DB access for recordings/sessions/transcripts used by the UI.

  This namespace provides tenant-scoped queries for the Recordings UI.

  Tables (see migrations 0001 + persistor 0002):
  - sessions
  - recordings
  - session_transcripts (append-only transcript records)

  Public API:
  - `list-sessions-for-tenant`
  - `find-session-by-id`
  - `list-transcript-records`

  All functions accept a next.jdbc datasource, typically provided by the
  Integrant `:samuraibff/db` component as `(:ds db)`.

  Security:
  - Every query is scoped by tenant-id.
  - Callers must supply the authenticated tenant-id."
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [org.corfield.logging4j2 :as log])
  (:import
    (java.util UUID)
    (javax.sql DataSource)))

(defn list-sessions-for-tenant
  "List sessions for a tenant, newest first.

  Output items include:
  - session fields (id, status, started_at, ended_at, created_at)
  - best-effort recording metadata (recording_created_at, duration_s, lang)
  - boolean flags:
      :has_recording
      :has_final_transcript

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: UUID
  - opts: map of optional keys:
      :limit int (default 200)
      :offset int (default 0)

  Returns:
  - vector of maps with unqualified lower-case keys." 
  [^DataSource ds ^UUID tenant-id {:keys [limit offset]
                                  :or {limit 200 offset 0}}]
  (when-not (and ds (instance? UUID tenant-id))
    (throw (ex-info "list-sessions-for-tenant missing required params"
                    {:tenant-id tenant-id})))
  ;; Strategy:
  ;; - left join latest recording per session (if any)
  ;; - left join existence of final transcript per session (if any)
  ;;
  ;; We use DISTINCT ON for latest recording selection.
  (let [sqlstr
        (str
          "WITH latest_recording AS (\n"
          "  SELECT DISTINCT ON (session_id)\n"
          "    session_id, created_at AS recording_created_at, duration_s, sample_rate, lang, recording_url\n"
          "  FROM recordings\n"
          "  ORDER BY session_id, created_at DESC\n"
          ")\n"
          "SELECT\n"
          "  s.id, s.session_key, s.status, s.started_at, s.ended_at, s.created_at,\n"
          "  lr.recording_created_at, lr.duration_s, lr.sample_rate, lr.lang, lr.recording_url,\n"
          "  (lr.session_id IS NOT NULL) AS has_recording,\n"
          "  EXISTS (SELECT 1 FROM session_transcripts st\n"
          "          WHERE st.session_id = s.id\n"
          "            AND st.tenant_id = s.tenant_id\n"
          "            AND st.type = 'final') AS has_final_transcript\n"
          "FROM sessions s\n"
          "LEFT JOIN latest_recording lr ON lr.session_id = s.id\n"
          "WHERE s.tenant_id = ?\n"
          "ORDER BY s.created_at DESC\n"
          "LIMIT ? OFFSET ?")
        sqlvec [sqlstr tenant-id (long limit) (long offset)]]
    (try
      (vec (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))
      (catch Exception e
        (log/error e "DB query failed (list-sessions-for-tenant)" {:tenant-id (str tenant-id)})
        (throw e)))))

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
    ["SELECT id, session_key, tenant_id, user_id, title, status, started_at, ended_at, created_at\n      FROM sessions\n      WHERE tenant_id=? AND id=?"
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
  [^DataSource ds ^UUID tenant-id ^UUID session-id {:keys [type limit]
                                                   :or {limit 500}}]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "list-transcript-records missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  ;; NOTE:
  ;; We intentionally use a plain SQL string here (instead of HoneySQL) to keep
  ;; the test runner / classloading path extremely predictable.
  (let [sql-base
        (str "SELECT id, session_id, recording_id, tenant_id, user_id, full_text, lang, duration_s, segments, created_at,\n"
             "       source, type, model, window_length, segment_start_s, segment_end_s, supersedes_seq, event_created_at_ns\n"
             "  FROM session_transcripts\n"
             " WHERE tenant_id = ?\n"
             "   AND session_id = ?\n")
        {:keys [sqlvec]}
        (if (some? type)
          {:sqlvec [(str sql-base
                         "   AND type = ?\n"
                         " ORDER BY created_at ASC\n"
                         " LIMIT ?")
                    tenant-id
                    session-id
                    (str type)
                    (long limit)]}
          {:sqlvec [(str sql-base
                         " ORDER BY created_at ASC\n"
                         " LIMIT ?")
                    tenant-id
                    session-id
                    (long limit)]})]
    (vec (jdbc/execute! ds sqlvec {:builder-fn rs/as-unqualified-lower-maps}))))

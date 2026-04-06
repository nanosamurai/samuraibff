(ns samuraibff.db.sessions
  "DB access for sessions.

  This namespace contains the minimal DB operations we need for session creation.

  Tables (see drsynth migration 0001):
  - tenants
  - app_users
  - sessions

  Public API:
  - `find-user-id-by-external-id`
  - `insert-session!`
  - `update-session-status!`
  - `activate-session-on-audio-start!`
  - `update-session-stream-controls!`

  All functions accept a next.jdbc datasource, typically provided by the
  Integrant `:samuraibff/db` component as `(:ds db)`.

  Note: we use HoneySQL to build SQL and next.jdbc to execute it."
  (:require
   [cheshire.core :as cheshire]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(defn find-user-id-by-external-id
  "Find app_users.id for a given tenant + external id.

  Inputs:
  - ds: javax.sql.DataSource
  - tenant-id: java.util.UUID
  - external-id: string (Keycloak user sub)

  Returns:
  - java.util.UUID or nil"
  ^UUID
  [^DataSource ds ^UUID tenant-id ^String external-id]
  (when (and ds tenant-id (seq (str external-id)))
    (let [q (-> (h/select :id)
                (h/from :app_users)
                (h/where [:= :tenant_id tenant-id]
                         [:= :external_id external-id])
                (h/limit 1))
          sqlvec (sql/format q)
          rows (jdbc/execute! ds sqlvec
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (:id (first rows)))))

(defn insert-session!
  "Insert a new session row.

  Inputs:
  - ds: javax.sql.DataSource
  - {:keys [id tenant-id user-id session-key status title]}
      id          => java.util.UUID
      tenant-id   => java.util.UUID
      user-id     => java.util.UUID or nil
      session-key => string
      title       => string or nil
      status      => string (defaults to active)

  Side effects:
  - INSERT into sessions

  Returns:
  - map with inserted identifiers:
      {:id <uuid> :session-key <string>}"
  [^DataSource ds {:keys [id tenant-id user-id session-key status title]
                   :or {status "active"}}]
  (when-not (and ds (instance? UUID id) (instance? UUID tenant-id) (seq (str session-key)))
    (throw (ex-info "insert-session! missing required params"
                    {:id id :tenant-id tenant-id :session-key session-key})))
  (let [values (cond-> {:id id
                        :tenant_id tenant-id
                        :session_key (str session-key)
                        :status (str status)}
                 (some? user-id) (assoc :user_id user-id))
        values (cond-> values
                 (some? title) (assoc :title (str title)))
        q (-> (h/insert-into :sessions)
              (h/values [values]))
        sqlvec (sql/format q)]
    (jdbc/execute-one! ds sqlvec)
    {:id id :session-key (str session-key)}))

(defn update-session-title!
  "Update the session title for a tenant-scoped session.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID
  - title: string or nil

  Returns:
  - {:updated? boolean}

  Notes:
  - Title may be nil (clears the title).
  - Scopes by tenant-id (prevents cross-tenant writes)."
  [^DataSource ds ^UUID tenant-id ^UUID session-id title]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "update-session-title! missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (let [q (-> (h/update :sessions)
              (h/set {:title title})
              (h/where [:= :tenant_id tenant-id]
                       [:= :id session-id]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn update-session-status!
  "Update a session status for a tenant-scoped session.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID
  - status: string (e.g. \"created\" | \"active\" | \"finished\" | \"failed\")

  Returns:
  - {:updated? boolean}

  Notes:
  - This does not change timestamps (started_at/ended_at) yet.
  - If the row does not exist for the given tenant, returns {:updated? false}."
  [^DataSource ds ^UUID tenant-id ^UUID session-id ^String status]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id) (seq (str status)))
    (throw (ex-info "update-session-status! missing required params"
                    {:tenant-id tenant-id :session-id session-id :status status})))
  (let [q (-> (h/update :sessions)
              (h/set {:status (str status)})
              (h/where [:= :tenant_id tenant-id]
                       [:= :id session-id]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn activate-session-on-audio-start!
  "Mark a session as active and set started_at when audio recording begins.

  This is used by the `/ws/audio` handler.

  Semantics:
  - status is set to \"active\"
  - started_at is set to `now()` if not already set

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID

  Returns:
  - {:updated? boolean}

  Notes:
  - Uses `COALESCE(started_at, now())` to avoid shifting start time on reconnects.
  - Implemented via HoneySQL (no raw SQL strings)."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "activate-session-on-audio-start! missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (let [q (-> (h/update :sessions)
              (h/set {:status "active"
                      :started_at [:coalesce :started_at [:raw "now()"]]})
              (h/where [:= :tenant_id tenant-id]
                       [:= :id session-id]))
        sqlvec (sql/format q)
        res (jdbc/execute-one! ds sqlvec)]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

(defn update-session-stream-controls!
  "Persist stream-level controls for a tenant-scoped session.

  This stores the controls as JSON in `sessions.stream_controls` for later UI
  session detail replay.

  Inputs:
  - ds: DataSource
  - tenant-id: UUID
  - session-id: UUID
  - controls: map (typically from `samuraibff.stream-controls/parse-and-validate`)

  Returns:
  - {:updated? boolean}

  Notes:
  - Uses a raw SQL fragment for `(?::jsonb)` casting (keeps it simple).
  - Callers typically treat this as best-effort and should not fail WS.
  - Requires migration adding sessions.stream_controls jsonb."
  [^DataSource ds ^UUID tenant-id ^UUID session-id controls]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "update-session-stream-controls! missing required params"
                    {:tenant-id tenant-id :session-id session-id})))
  (let [json (cheshire/generate-string (or controls {}))
        ;; Use SQL cast to jsonb (Postgres). next.jdbc uses prepared statements.
        res (jdbc/execute-one!
             ds
             ["UPDATE sessions\n     SET stream_controls = (?::jsonb)\n   WHERE tenant_id=? AND id=?"
              json tenant-id session-id])]
    {:updated? (pos? (long (or (:next.jdbc/update-count res) 0)))}))

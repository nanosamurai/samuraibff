(ns samuraibff.db.retranscriptions
  "Read the links between a session and its re-transcriptions.

  A re-transcription is a session with
  `session_settings = {\"source\": \"retranscription\", \"parent_session_id\": <uuid>}`.
  Both lookups apply the principal read policy from
  `samuraibff.db.session-access/readable-condition`, so a caller sees only
  linked sessions that it may read. Both are tenant-scoped through that
  condition.

  Public API:
  - `find-readable-parent`
  - `list-readable-children`
  - `max-children`"
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.db.session-access :as session-access]
   [samuraibff.db.sessions :as db.sessions])
  (:import
   (java.util UUID)))

(def max-children
  "Most re-transcriptions that a session detail response lists."
  20)

(def ^:private row-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn find-readable-parent
  "Return {:id UUID :title string?} of a session that the principal may read,
  or nil when it does not exist or is not readable.

  Inputs: connectable, principal map, parent session id (UUID, or a UUID
  string from session settings; an invalid string returns nil)."
  [connectable principal parent-id]
  (when-let [parent-uuid (cond
                           (instance? UUID parent-id) parent-id
                           (string? parent-id) (try (UUID/fromString parent-id)
                                                    (catch IllegalArgumentException _ nil)))]
    (jdbc/execute-one!
     connectable
     (sql/format {:select [:s.id :s.title]
                  :from [[:sessions :s]]
                  :where [:and [:= :s.id parent-uuid]
                          (session-access/readable-condition principal)]})
     row-opts)))

(defn list-readable-children
  "List the re-transcriptions of a session that the principal may read.

  Inputs: connectable, principal map, parent session UUID.
  Returns: vector of {:id UUID :title string? :status string
  :created_at Timestamp :source string :has_final_transcript boolean},
  newest first, at most `max-children` rows."
  [connectable principal ^UUID session-id]
  (vec (jdbc/execute!
        connectable
        (sql/format {:select [:s.id :s.title :s.status :s.created_at
                              [(db.sessions/session-source-expr :s.session_settings) :source]
                              [[:exists {:select [1]
                                         :from [[:session_transcripts :st]]
                                         :where [:and [:= :st.session_id :s.id]
                                                 [:= :st.tenant_id :s.tenant_id]
                                                 [:= :st.type "final"]]}]
                               :has_final_transcript]]
                     :from [[:sessions :s]]
                     :where [:and
                             (session-access/readable-condition principal)
                             [:= (db.sessions/session-source-expr :s.session_settings)
                              db.sessions/retranscription-source]
                             [:= (db.sessions/parent-session-id-expr :s.session_settings) (str session-id)]]
                     :order-by [[:s.created_at :desc] [:s.id :desc]]
                     :limit max-children})
        row-opts)))

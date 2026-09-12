(ns samuraibff.db.track-results
  "Authorized reads of frozen session selections and their existing outcome index."
  (:require [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(defn decode
  "Decode JDBC JSONB into keyword-keyed data."
  [value]
  (cond
    (instance? PGobject value) (json/parse-string (.getValue ^PGobject value) true)
    (string? value) (json/parse-string value true)
    :else value))

(defn session
  "Read one tenant-owned session, including its historical presentation snapshot."
  [ds tenant-id session-id]
  (when-let [row (jdbc/execute-one!
                  ds ["SELECT s.status, s.stream_controls, s.asr_meta_snapshot, EXISTS (SELECT 1 FROM recordings r WHERE r.session_id=s.id) AS has_recording FROM sessions s WHERE s.tenant_id=? AND s.id=?"
                      tenant-id session-id] {:builder-fn rs/as-unqualified-lower-maps})]
    (-> row (update :stream_controls decode) (update :asr_meta_snapshot decode))))

(defn selected-result?
  "Check a persisted outcome against this session's frozen worker contract."
  [plan row]
  (boolean
   (and (= (str (:tenant_id row)) (:tenant_id plan))
        (= (str (:session_id row)) (:session_id plan))
        (= (str (:plan_id row)) (:plan_id plan))
        (some #(and (= (:track_id row) (:track_id %))
                    (= (:profile_id row) (:profile_id %))
                    (= (:is_primary row) (:primary %)))
              (case (:stage row) "refined" (:refinement_tracks plan) "final" (:final_tracks plan) [])))))

(defn results
  "Read bounded metadata for the spike's four tracks and at most sixty windows."
  [ds tenant-id session-id]
  (jdbc/execute!
   ds ["SELECT result_id, tenant_id, session_id, plan_id, track_id, profile_id, run_id, stage, unit_id, revision, status, is_primary, error_code, capabilities, degradations FROM transcript_track_results WHERE tenant_id=? AND session_id=? ORDER BY event_created_at_ns, result_id LIMIT 245"
       tenant-id session-id] {:builder-fn rs/as-unqualified-lower-maps}))

(defn result
  "Read one outcome by tenant, session and result identity; never by an artifact URL."
  [ds tenant-id session-id result-id]
  (jdbc/execute-one!
   ds ["SELECT t.*, r.source_artifact_id FROM transcript_track_results t LEFT JOIN recordings r ON r.id=t.recording_id AND r.session_id=t.session_id WHERE t.tenant_id=? AND t.session_id=? AND t.result_id=?"
       tenant-id session-id result-id] {:builder-fn rs/as-unqualified-lower-maps}))

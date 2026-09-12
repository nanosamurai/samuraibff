(ns samuraibff.final-tracks
  "Freeze operator-selected final tracks before accepting a retained recording."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [org.corfield.logging4j2 :as log]
            [samuraibff.async-tracks :as async-tracks]
            [samuraibff.kafka.producer :as producer])
  (:import (java.util UUID)
           (org.postgresql.util PGobject)))

(defn enabled?
  "Return whether the operator enabled final-track plan creation."
  [config]
  (or (true? (get-in config [:final-tracks :enabled?]))
      (true? (get-in config [:refinement-tracks :enabled?]))))

(defn selected?
  "Return whether an enabled asynchronous stage was requested for this session."
  [config controls]
  (or (and (get-in config [:final-tracks :enabled?]) (:final controls))
      (and (get-in config [:refinement-tracks :enabled?]) (:refined controls))))

(defn- reject!
  "Throw a sanitized validation error, without echoing user or operator data."
  [reason]
  (throw (ex-info "Invalid final-track plan" {:type ::invalid-plan :reason reason})))

(defn- canonical-uuid
  "Validate a canonical UUID string; reject alternate or malformed identities."
  [value]
  (try
    (let [parsed (UUID/fromString (str value))]
      (when-not (= (str parsed) (str value)) (reject! :invalid-identity))
      parsed)
    (catch IllegalArgumentException _ (reject! :invalid-identity))))

(defn selections
  "Resolve compatible operator defaults to the unchanged worker plan shape."
  ([config] (selections config :final-tracks))
  ([config stage]
   (async-tracks/resolve-selection config stage nil nil)))

(defn new-plan
  "Build a bounded plan carrying the authenticated tenant/session identity."
  ([config tenant-id session-id]
   (new-plan (assoc-in config [:final-tracks :enabled?] true) tenant-id session-id {:final true}))
  ([config tenant-id session-id controls]
   (canonical-uuid tenant-id)
   (canonical-uuid session-id)
   (let [refined? (and (get-in config [:refinement-tracks :enabled?]) (:refined controls))
         samples (long (* 16000 (or (:refinement_window_sec controls) 60)))]
     (when (and refined? (not (<= 160000 samples 9600000))) (reject! :invalid-window-policy))
     (cond-> {:schema_version 1 :plan_id (str (UUID/randomUUID))
              :tenant_id (str tenant-id) :session_id (str session-id)
              :final_tracks (if (and (get-in config [:final-tracks :enabled?]) (:final controls))
                              (async-tracks/resolve-selection config :final-tracks tenant-id (:final_tracks controls)) [])}
       refined? (assoc :refinement_tracks (async-tracks/resolve-selection config :refinement-tracks tenant-id (:refinement_tracks controls))
                       :refinement_window_samples samples)))))

(defn kafka-headers
  "Encode the frozen plan canonically, matching the Python contract byte for byte."
  [plan]
  (let [sorted-plan (walk/postwalk #(if (map? %) (into (sorted-map) %) %) plan)
        encoded (.getBytes ^String (json/generate-string sorted-plan) "UTF-8")]
    (when (> (alength encoded) 8192) (reject! :plan-too-large))
    (cond-> {"x-asr-plan" encoded
             "x-asr-plan-id" (.getBytes ^String (:plan_id plan) "UTF-8")
             "x-final-track-ids" (.getBytes ^String (str/join "," (map :track_id (:final_tracks plan))) "UTF-8")}
      (:refinement_tracks plan)
      (assoc "x-refinement-track-ids"
             (.getBytes ^String (str/join "," (map :track_id (:refinement_tracks plan))) "UTF-8")))))

(defn- decode-json
  "Decode a JDBC JSONB value into keyword-keyed Clojure data."
  [value]
  (cond
    (instance? PGobject value) (json/parse-string (.getValue ^PGobject value) true)
    (string? value) (json/parse-string value true)
    (map? value) value
    :else nil))

(defn save-meta-snapshot!
  "Persist the complete inception snapshot for opted-in sessions before audio."
  [ds config tenant-id session-id snapshot]
  (when (enabled? config)
    (jdbc/execute-one! ds
                       ["UPDATE sessions SET asr_meta_snapshot=?::jsonb WHERE tenant_id=? AND id=? AND started_at IS NULL"
                        (json/generate-string snapshot) tenant-id session-id])))

(defn freeze!
  "Freeze or reload a session plan transactionally, then mirror it to sessions.meta.
  Requires a newly created, retained, 16 kHz session with an inception snapshot.
  Returns the plan or nil when disabled/final output was not selected. Publication
  must be acknowledged before callers accept audio; retries reuse the same plan."
  [ds kafka-producer config tenant-id session-id controls sample-rate]
  (when (and ds tenant-id session-id (not (selected? config controls)))
    (let [row (jdbc/execute-one! ds
                                 ["SELECT stream_controls->'asr_plan' AS plan FROM sessions WHERE tenant_id=? AND id=?"
                                  (canonical-uuid tenant-id) (canonical-uuid session-id)]
                                 {:builder-fn rs/as-unqualified-lower-maps})]
      (when (:plan row) (reject! :frozen-plan-disabled))))
  (when (selected? config controls)
    (when-not (and ds kafka-producer (:store_recording controls) (= 16000 sample-rate))
      (reject! :unsupported-retention-or-storage))
    (let [tenant (canonical-uuid tenant-id)
          session (canonical-uuid session-id)
          {:keys [plan snapshot]}
          (jdbc/with-transaction [tx ds]
            (let [row (jdbc/execute-one! tx
                                         ["SELECT stream_controls, asr_meta_snapshot, started_at, ended_at FROM sessions WHERE tenant_id=? AND id=? FOR UPDATE"
                                          tenant session] {:builder-fn rs/as-unqualified-lower-maps})
                  previous (decode-json (:stream_controls row))
                  meta-snapshot (decode-json (:asr_meta_snapshot row))
                  existing (:asr_plan previous)]
              (when-not (and row meta-snapshot (nil? (:ended_at row)))
                (reject! :session-unavailable))
              (when (and (:started_at row) (nil? existing)) (reject! :session-already-started))
              (when (and existing (not= controls (dissoc previous :asr_plan)))
                (reject! :frozen-controls-changed))
              (let [plan (or existing (new-plan config tenant-id session-id controls))
                    snapshot (if existing meta-snapshot
                                 (assoc meta-snapshot :asr_plan plan
                                        :asr_track_catalog (async-tracks/public-catalog config tenant-id)
                                        :event_id (str (UUID/randomUUID))))]
                (jdbc/execute-one! tx
                                   ["UPDATE sessions SET status='active', started_at=COALESCE(started_at,now()), stream_controls=?::jsonb, asr_meta_snapshot=?::jsonb WHERE tenant_id=? AND id=?"
                                    (json/generate-string (assoc controls :asr_plan plan))
                                    (json/generate-string snapshot) tenant session])
                {:plan plan :snapshot snapshot})))]
      (producer/send-sessions-meta! kafka-producer session-id snapshot
                                    {:tenant-id tenant-id :ack? true})
      (log/info "Final track plan frozen" {:session-id session-id :tenant-id tenant-id
                                           :plan-id (:plan_id plan) :track-count (count (:final_tracks plan))})
      plan)))

(ns samuraibff.http.track-results
  "Thin tenant-scoped track history and bounded, verified S3 transcript reads."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [org.corfield.logging4j2 :as log]
            [samuraibff.db.track-results :as db]
            [samuraibff.s3.client :as s3])
  (:import (java.util UUID HexFormat)
           (java.security MessageDigest)
           (software.amazon.awssdk.services.s3 S3Client)
           (software.amazon.awssdk.services.s3.model GetObjectRequest)
           (java.time Duration)))

(defn- reject!
  "Raise a safe HTTP failure."
  [status message]
  (throw (ex-info message {:status status :message message})))

(defn- uuid!
  "Parse a canonical UUID without reflecting malformed input."
  [value]
  (try
    (let [id (UUID/fromString (str value))]
      (when-not (= (str id) (str value)) (reject! 400 "invalid-id"))
      id)
    (catch IllegalArgumentException _ (reject! 400 "invalid-id"))))

(defn public-result
  "Select result metadata suitable for the browser; storage/provenance stays private."
  [row]
  (-> (select-keys row [:track_id :profile_id :stage :unit_id :revision :status :error_code])
      (assoc :result_id (str (:result_id row)) :run_id (str (:run_id row))
             :capabilities (select-keys (db/decode (:capabilities row))
                                        [:segment_timestamps :word_timestamps :speaker_labels])
             :degradations (vec (or (db/decode (:degradations row)) [])))))

(defn track-index
  "Build selected tabs from the frozen plan, retaining labels after catalog edits."
  [session rows]
  (let [plan (get-in session [:stream_controls :asr_plan])
        labels (get-in session [:asr_meta_snapshot :asr_track_catalog])
        rows (filter #(db/selected-result? plan %) rows)]
    {:ok true :has_recording (boolean (:has_recording session))
     :session_status (:status session)
     :tracks (vec
              (mapcat
               (fn [[stage key]]
                 (for [track (get plan key)
                       :let [label (some #(when (and (= stage (:stage %))
                                                     (= (:track_id track) (:track_id %))
                                                     (= (:profile_id track) (:profile_id %)))
                                            (:display_name %)) labels)]]
                   (assoc track :stage stage :display_name (or label (:track_id track))
                          :results (mapv public-result
                                         (filter #(and (= stage (:stage %))
                                                       (= (:track_id track) (:track_id %))) rows)))))
               [["refined" :refinement_tracks] ["final" :final_tracks]]))}))

(defn artifact-key
  "Derive the only permitted transcript key from persisted, verified identities."
  [config row]
  (let [bucket (get-in config [:s3 :buckets :recordings :bucket])
        source-id (or (:source-id (db/decode (:source row))) (:source_artifact_id row))
        stage (:stage row)
        track-id (:track_id row)]
    (when-not (and (string? bucket) (re-matches #"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]" bucket)
                   (#{"refined" "final"} stage)
                   (string? track-id) (re-matches #"[a-z0-9][a-z0-9._-]{0,95}" track-id))
      (reject! 503 "track-artifact-unavailable"))
    (let [prefix (if (= stage "refined") "refined-tracks" "final-tracks")
          key (str/join "/" [prefix (uuid! (:tenant_id row)) (uuid! (:session_id row))
                             (uuid! source-id) track-id (uuid! (:run_id row))
                             (str (uuid! (:attempt_id row)) ".transcript.json")])]
      (when-not (= (:result_uri row) (str "s3://" bucket "/" key))
        (reject! 503 "track-artifact-unavailable"))
      {:bucket bucket :key key})))

(defn read-transcript!
  "Read at most one megabyte from the operator S3 bucket and verify its SHA-256.
  Return transcript fields only, never runtime provenance or object locations."
  [config row]
  (let [{:keys [bucket key]} (artifact-key config row)
        request (-> (GetObjectRequest/builder) (.bucket bucket) (.key key)
                    (.overrideConfiguration
                     (reify java.util.function.Consumer
                       (accept [_ builder]
                         (.apiCallTimeout builder (Duration/ofSeconds 15))
                         (.apiCallAttemptTimeout builder (Duration/ofSeconds 10)))))
                    .build)]
    (with-open [^S3Client client (s3/build-s3-client config)
                stream (.getObject client ^GetObjectRequest request)]
      (when (> (.contentLength (.response stream)) 1000000)
        (reject! 503 "track-artifact-unavailable"))
      (let [data (.readNBytes stream 1000001)
            digest (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") data))]
        (when (or (> (alength data) 1000000) (not= digest (:result_sha256 row)))
          (reject! 503 "track-artifact-unavailable"))
        (let [value (json/parse-string-strict (String. data java.nio.charset.StandardCharsets/UTF_8) true)
              caps (db/decode (:capabilities row))]
          {:full_text (:full_text value) :lang (:lang value)
           :segments (mapv (fn [segment]
                             (cond-> (select-keys segment [:text])
                               (:segment_timestamps caps) (merge (select-keys segment [:start_s :end_s]))
                               (:speaker_labels caps) (merge (select-keys segment [:speaker]))
                               (:word_timestamps caps)
                               (assoc :words (mapv #(select-keys % [:text :start_s :end_s]) (:words segment)))))
                           (:segments value))})))))

(defn- handler
  "Wrap a read with canonical identity parsing, tenant authorization and safe errors."
  [{:keys [db] :as deps} read!]
  (fn [req]
    (try
      (when-not (:ds db) (reject! 503 "db-unavailable"))
      (when-not (:auth/tenant-id req) (reject! 403 "missing-tenant-id"))
      (let [tenant (uuid! (:auth/tenant-id req))
            session-id (uuid! (get-in req [:path-params :session_id]))
            session (db/session (:ds db) tenant session-id)]
        (when-not session (reject! 404 "session-not-found"))
        {:status 200 :headers {"Cache-Control" "private, no-store"}
         :body (read! deps req tenant session-id session)})
      (catch Exception error
        (let [{:keys [status]} (ex-data error)]
          (when-not status
            (log/warn "Track read failed" {:error-kind (.getSimpleName (class error))})))
        {:status (or (:status (ex-data error)) 503)
         :headers {"Cache-Control" "private, no-store"}
         :body {:ok false :message (or (:message (ex-data error)) "track-results-unavailable")}}))))

(defn index-handler
  "GET /api/sessions/:session_id/tracks, including pending selected tracks."
  [deps]
  (handler deps (fn [{:keys [db]} _ tenant session-id session]
                  (track-index session (when (get-in session [:stream_controls :asr_plan])
                                         (db/results (:ds db) tenant session-id))))))

(defn result-handler
  "GET /api/sessions/:session_id/track-results/:result_id for one selected outcome."
  [deps]
  (handler deps
           (fn [{:keys [db config]} req tenant session-id session]
             (let [row (db/result (:ds db) tenant session-id (uuid! (get-in req [:path-params :result_id])))]
               (when-not (and row (db/selected-result? (get-in session [:stream_controls :asr_plan]) row))
                 (reject! 404 "track-result-not-found"))
               (cond-> {:ok true :result (public-result row)}
                 (= "succeeded" (:status row)) (assoc :transcript (read-transcript! config row)))))))

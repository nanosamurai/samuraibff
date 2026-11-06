(ns samuraibff.http.transcript-export
  "HTTP handler for `GET /api/recordings/:session_id/export`.

  Returns one stored transcript track as a file download (SRT, WebVTT, TXT,
  JSON or DOCX). Access uses the same read check as recording detail: an
  unreadable session is 404, so readers export exactly what they can read.
  Row selection matches the recording detail page: the latest final row of
  the track, or all refined windows of the track ordered by audio start."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.db.recordings :as db.recordings]
   [samuraibff.http.session-guard :as session-guard]
   [samuraibff.transcripts.export :as export]
   [samuraibff.ui.recording-detail :as recording-detail])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util UUID)))

(defn- query-param
  "Read a query parameter from coerced Reitit parameters or raw query params."
  [req k]
  (or (get-in req [:parameters :query k])
      (get-in req [:query-params (name k)])
      (get-in req [:params k])))

(defn- parse-session-uuid
  "Parse the :session_id path parameter, or throw {:type ::invalid-session-id}."
  [req]
  (try
    (UUID/fromString (str (get-in req [:path-params :session_id])))
    (catch Exception _
      (throw (ex-info "invalid-session-id" {:type ::invalid-session-id})))))

(defn- track-of
  "Return a transcript row's track ID; NULL means WhisperX."
  [row]
  (or (:track_id row) "whisperx"))

(defn- decode-segments
  "Decode a row's `segments` jsonb (PGobject, string or data) into a vector."
  [row]
  (let [v (:segments row)]
    (assoc row :segments (vec (cond
                                (nil? v) []
                                (sequential? v) v
                                :else (cheshire/parse-string (str v) true))))))

(defn select-track-rows
  "Return the rows of one track.

  Inputs: rows ordered by created_at and an optional track ID. Without a
  track ID the track of the earliest stored row is used.
  Returns: {:track-id string-or-nil :rows vector}."
  [rows track-id]
  (let [track-id (or (not-empty track-id) (some-> (first rows) track-of))]
    {:track-id track-id
     :rows (filterv #(= track-id (track-of %)) rows)}))

(defn export-filename
  "Return a safe ASCII download filename.

  Inputs: session title (may be nil/unicode), session UUID string, track ID,
  extension. Characters outside [A-Za-z0-9._-] become `_`; a title with no
  usable characters falls back to the session ID."
  [title session-id track-id extension]
  (let [safe (fn [s limit]
               (let [cleaned (-> (str s) (str/replace #"[^A-Za-z0-9._-]+" "_") (str/replace #"^[._]+|[._]+$" ""))]
                 (subs cleaned 0 (min limit (count cleaned)))))
        base (or (not-empty (safe title 60)) session-id)]
    (str base "-" (or (not-empty (safe track-id 40)) "transcript") "." extension)))

(defn- render
  "Render segments in `fmt`; returns a byte array."
  [fmt metadata segments]
  (if (= fmt "docx")
    (export/->docx (:title metadata) segments)
    (.getBytes ^String (case fmt
                         "srt" (export/->srt segments)
                         "vtt" (export/->vtt segments)
                         "txt" (export/->txt segments)
                         "json" (export/->json metadata segments))
               StandardCharsets/UTF_8)))

(defn- error
  [status message]
  {:status status :body {:ok false :message message}})

(defn export-transcript-handler
  "Handler for `GET /api/recordings/:session_id/export`.

  Query: format (srt|vtt|txt|json|docx, required), stage (final|refined,
  default final), track_id (optional).
  Returns 200 with the file bytes, Content-Type and an attachment
  Content-Disposition; 400 for a bad format or session ID; 404 for an
  unreadable session or a track without a stored transcript; 503 without DB.

  Dependencies: {:db {:ds DataSource}}."
  [{:keys [db]}]
  (fn [req]
    (let [ds (:ds db)
          fmt (some-> (query-param req :format) name str/lower-case)
          stage (or (some-> (query-param req :stage) name) "final")]
      (try
        (cond
          (nil? ds) (error 503 "db-unavailable")
          (not (contains? export/formats fmt)) (error 400 "invalid-format")
          (not (#{"final" "refined"} stage)) (error 400 "invalid-stage")
          :else
          (let [session-id (parse-session-uuid req)
                session (session-guard/require-readable-session! ds req session-id)
                rows (db.recordings/list-transcript-records
                      ds (:tenant_id session) session-id
                      {:type stage :limit (if (= stage "final") 20 2000)
                       :track-id (not-empty (query-param req :track_id))})
                {:keys [track-id rows]} (select-track-rows rows (query-param req :track_id))]
            (if (empty? rows)
              (error 404 "transcript-not-found")
              (let [segments (recording-detail/record-messages (mapv decode-segments rows) (keyword stage))
                    metadata {:session_id (str session-id) :title (:title session) :stage stage
                              :track_id track-id :lang (:lang (last rows))}
                    {:keys [extension content-type]} (export/formats fmt)
                    filename (export-filename (:title session) (str session-id) track-id extension)]
                (log/info "Exported transcript" {:session-id (str session-id) :tenant-id (str (:tenant_id session))
                                                 :format fmt :stage stage :track-id track-id})
                {:status 200
                 :headers {"Content-Type" content-type
                           "Content-Disposition" (str "attachment; filename=\"" filename "\"")
                           "Cache-Control" "no-store"
                           "X-Content-Type-Options" "nosniff"}
                 :body (render fmt metadata segments)}))))
        (catch clojure.lang.ExceptionInfo e
          (let [type (:type (ex-data e))]
            (cond
              (= type ::invalid-session-id) (error 400 "invalid-session-id")
              (session-guard/error-response type) (session-guard/error-response type)
              :else (do (log/error e "Failed to export transcript")
                        (error 500 "internal-error")))))
        (catch Exception e
          (log/error e "Unexpected error exporting transcript")
          (error 500 "internal-error"))))))

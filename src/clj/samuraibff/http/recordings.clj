(ns samuraibff.http.recordings
  "HTTP handlers for DB-backed recordings/sessions.

  Endpoints (all secured by auth middleware in router):
  - GET /api/recordings
  - GET /api/recordings/:session_id
  - GET /api/recordings/:session_id/audio
  - DELETE /api/recordings/:session_id

  Auth:
  - Requires `wrap-authenticate` + `wrap-require-auth` in router.
  - Uses `:auth/tenant-id` from request and scopes all DB reads by tenant.

  Returns JSON only (Muuntaja handles content negotiation)." 
  (:require
    [cheshire.core :as cheshire]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [samuraibff.db.recordings :as db.recordings]
    [samuraibff.s3.enrollment :as s3.enrollment]
    [samuraibff.schemas :as schemas])
  (:import
    (java.io File FileInputStream FilterInputStream InputStream)
    (java.net URI)
    (java.util UUID)
    (javax.sql DataSource)
    (software.amazon.awssdk.services.s3 S3Client)
    (software.amazon.awssdk.services.s3.model GetObjectRequest HeadObjectRequest)
    (software.amazon.awssdk.core ResponseInputStream)))

(defn- parse-range-header
  "Parse an HTTP Range header of the form `bytes=start-end`.

  Inputs:
  - s: string (header value)

  Returns:
  - {:start long? :end long?} where either may be nil (open-ended)
  - nil when header is missing/invalid.

  Notes:
  - We support only a single range (no multi-range)." 
  [s]
  (let [s (some-> s str str/trim)
        m (when (seq s) (re-matches #"(?i)bytes=(\d*)-(\d*)" s))]
    (when m
      (let [[_ a b] m
            parse (fn [x]
                    (when (seq x)
                      (try
                        (Long/parseLong x)
                        (catch Exception _
                          nil))))
            start (parse a)
            end (parse b)]
        (when (or (some? start) (some? end))
          {:start start :end end})))))

(defn- safe-file-under-root
  "Resolve a `file://` URL into a File under configured root.

  Inputs:
  - root-path: string (required)
  - file-url: string (e.g. file:///data/recordings/foo.wav)

  Returns:
  - java.io.File when allowed

  Throws:
  - ex-info on invalid URL or path traversal attempt." 
  [root-path file-url]
  (when-not (seq (str root-path))
    (throw (ex-info "recordings-local-root-not-configured"
                    {:type :samuraibff.http/recordings-local-root-not-configured})))
  (let [root (-> (io/file root-path) .getCanonicalFile)
        uri (try
              (URI/create (str file-url))
              (catch Exception e
                (throw (ex-info "invalid-recording-url"
                                {:type :samuraibff.http/invalid-recording-url
                                 :recording-url file-url}
                                e))))
        _ (when-not (= "file" (.getScheme uri))
            (throw (ex-info "invalid-recording-url"
                            {:type :samuraibff.http/invalid-recording-url
                             :recording-url file-url})))
        f (-> (File. uri) .getCanonicalFile)
        root-path* (.getPath root)
        file-path (.getPath f)
        allowed? (or (= root-path* file-path)
                     (.startsWith file-path (str root-path* File/separator)))]
    (when-not allowed?
      (throw (ex-info "recording-path-outside-root"
                      {:type :samuraibff.http/recording-path-outside-root
                       :root root-path*
                       :path file-path})))
    f))

(defn- bounded-input-stream
  "Wrap an InputStream so it yields at most `n` bytes.

  Inputs:
  - in: InputStream
  - n: long (>=0)

  Returns:
  - InputStream (FilterInputStream)" 
  ^InputStream
  [^InputStream in n]
  (let [remaining* (atom (max 0 (long n)))]
    (proxy [FilterInputStream] [in]
      (read
        ([]
         (if (zero? @remaining*)
           -1
           (let [b (.read ^InputStream in)]
             (if (neg? b)
               -1
               (do
                 (swap! remaining* dec)
                 b)))))
        ([b off len]
         (let [rem @remaining*]
           (cond
             (zero? rem) -1
             :else
             (let [nread (.read ^InputStream in b (int off) (int (min (long len) rem)))]
               (when (pos? nread)
                 (swap! remaining* - (long nread)))
               nread))))))))

(defn- ring-stream-file
  "Build a Ring response streaming a local file, with optional Range support.

  Inputs:
  - f: java.io.File
  - range: {:start long? :end long?} from Range header

  Returns:
  - Ring response map with :body InputStream." 
  [^File f range]
  (let [size (.length f)
        ;; Range semantics:
        ;; - bytes=START-END
        ;; - bytes=START- (open end)
        ;; - bytes=-SUFFIX is not supported here (rare for audio seeking)
        {:keys [start end]} (or range {})
        start (when (some? start) (max 0 (long start)))
        end (when (some? end) (max 0 (long end)))
        ;; compute effective start/end
        ;; if end is nil => end = size-1
        ;; if start is nil but end is set => reject (suffix ranges not supported)
        _ (when (and (nil? start) (some? end))
            (throw (ex-info "range-not-supported"
                            {:type :samuraibff.http/range-not-supported
                             :range range})))
        start (or start 0)
        end (or end (dec size))
        end (min end (dec size))
        _ (when (or (neg? start) (neg? end) (> start end))
            (throw (ex-info "invalid-range"
                            {:type :samuraibff.http/invalid-range
                             :range range
                             :size size})))
        len (inc (- end start))
        partial? (or (some? (:start range)) (some? (:end range)))
        ^FileInputStream fin (FileInputStream. f)]
    ;; Important: skip to start (FileInputStream.skip is best-effort; loop)
    (when (pos? start)
      (loop [to-skip start]
        (when (pos? to-skip)
          (let [skipped (.skip fin to-skip)]
            (when (pos? skipped)
              (recur (- to-skip skipped)))))))
    {:status (if partial? 206 200)
     :headers (cond-> {"Content-Type" "audio/wav"
                       "Accept-Ranges" "bytes"
                       "Content-Length" (str len)}
                partial?
                (assoc "Content-Range" (str "bytes " start "-" end "/" size)))
     :body (bounded-input-stream fin len)}))

(defn- parse-s3-url
  "Parse an s3:// URL into {:bucket :key}.

  Inputs:
  - s: string (s3://bucket/key)

  Returns:
  - {:bucket string :key string} or nil." 
  [s]
  (let [s (some-> s str str/trim)]
    (when (and (seq s) (str/starts-with? s "s3://"))
      (let [uri (URI/create s)
            bucket (.getHost uri)
            path (some-> (.getPath uri) (str/replace #"^/+" ""))]
        (when (and (seq bucket) (seq path))
          {:bucket bucket :key path})))))

(defn- ring-stream-s3
  "Build a Ring response streaming an S3 object, with optional Range support.

  Inputs:
  - s3: S3Client
  - bucket: string
  - key: string
  - range: {:start long? :end long?} from Range header

  Returns: Ring response map." 
  [^S3Client s3 bucket key range]
  (let [head (.headObject s3 (-> (HeadObjectRequest/builder)
                                 (.bucket bucket)
                                 (.key key)
                                 (.build)))
        size (long (.contentLength head))
        {:keys [start end]} (or range {})
        start (when (some? start) (max 0 (long start)))
        end (when (some? end) (max 0 (long end)))
        _ (when (and (nil? start) (some? end))
            (throw (ex-info "range-not-supported"
                            {:type :samuraibff.http/range-not-supported
                             :range range})))
        start (or start 0)
        end (or end (dec size))
        end (min end (dec size))
        _ (when (or (neg? start) (neg? end) (> start end))
            (throw (ex-info "invalid-range"
                            {:type :samuraibff.http/invalid-range
                             :range range
                             :size size})))
        len (inc (- end start))
        partial? (or (some? (:start range)) (some? (:end range)))
        get-req (cond-> (GetObjectRequest/builder)
                  true (.bucket bucket)
                  true (.key key)
                  partial? (.range (str "bytes=" start "-" end)))
        ^ResponseInputStream in (.getObject s3 (.build get-req))
        content-type (or (.contentType head) "audio/wav")]
    {:status (if partial? 206 200)
     :headers (cond-> {"Content-Type" content-type
                       "Accept-Ranges" "bytes"
                       "Content-Length" (str len)}
                partial?
                (assoc "Content-Range" (str "bytes " start "-" end "/" size)))
     :body (bounded-input-stream in len)}))

(defn- jsonb->clj
  "Normalize a Postgres json/jsonb value into a Clojure value.

  Inputs:
  - v: value returned from JDBC for a json/jsonb column

  Returns:
  - decoded Clojure value (typically vector/map)

  Notes:
  - We decode jsonb into data so OpenAPI/SDK can describe the real wire format.
  - For transcript segments, nil is normalized to an empty vector.
  - We expect jsonb columns to arrive either as:
      - org.postgresql.util.PGobject (common)
      - already-decoded Clojure map/vector (possible in some setups)
      - string (rare; but supported)." 
  [v]
  (cond
    (nil? v) nil

    ;; Already decoded (depends on driver / next.jdbc config)
    (or (map? v) (vector? v) (sequential? v)) v

    (string? v) (cheshire/parse-string v true)

    ;; Postgres driver jsonb
    (and (some? v) (= "org.postgresql.util.PGobject" (.getName (class v))))
    (some-> v (.getValue) (cheshire/parse-string true))

    :else v))

(defn- segments-jsonb->segments
  "Decode the `segments` jsonb column into a vector of segment maps.

  Inputs:
  - v: jsonb value

  Returns:
  - vector of segment maps (empty when nil)." 
  [v]
  (let [x (jsonb->clj v)]
    (vec (or x []))))

(defn- json-response
  "Return a Ring JSON response.

  Inputs:
  - status int
  - body map

  Returns Ring response map." 
  [status body]
  ;; NOTE: Return a *data* body (map). Muuntaja JSON-encodes it.
  ;; This keeps Reitit response coercion compatible with Malli schemas.
  {:status status
   :body body})

(defn- tenant-id-uuid
  "Extract tenant id from req and convert to UUID.

  Inputs:
  - req: Ring request map

  Returns:
  - UUID

  Throws:
  - ex-info if missing/invalid." 
  [req]
  (let [tid (or (:auth/tenant-id req)
                (get-in req [:auth :tenant-id]))]
    (when (str/blank? (str tid))
      (throw (ex-info "missing-tenant-id" {:type :samuraibff.http/missing-tenant-id})))
    (try
      (UUID/fromString (str tid))
      (catch Exception e
        (throw (ex-info "invalid-tenant-id" {:type :samuraibff.http/invalid-tenant-id
                                              :tenant-id tid}
                        e))))))

(defn- parse-int
  [s default]
  (try
    (let [n (Long/parseLong (str s))]
      (if (neg? n) default n))
    (catch Exception _
      default)))

(defn- parse-session-uuid
  "Parse :session_id path parameter into UUID.

  Inputs:
  - req: Ring request map

  Returns:
  - UUID

  Throws:
  - ex-info with :type :samuraibff.http/invalid-session-id" 
  [req]
  (let [sid-str (or (get-in req [:path-params :session_id])
                    (get-in req [:path-params "session_id"]))]
    (try
      (UUID/fromString (str sid-str))
      (catch Exception _
        (throw (ex-info "invalid-session-id"
                        {:type :samuraibff.http/invalid-session-id
                         :session-id sid-str}))))))

(defn- recordings-s3-allow-bucket
  "Return the configured allowlist bucket for recording playback.

  Precedence:
  1) config [:recordings :s3-bucket]
  2) config [:s3 :bucket] (legacy default)

  Returns:
  - string? (blank => nil)." 
  [config]
  (let [b0 (some-> (get-in config [:recordings :s3-bucket]) str str/trim)
        b1 (some-> (get-in config [:s3 :bucket]) str str/trim)
        b (if (seq b0) b0 (when (seq b1) b1))]
    (when (seq b) b)))

(defn get-recording-audio-handler
  "Handler for `GET /api/recordings/:session_id/audio`.

  Streams the session's latest recording audio.

  Storage backends:
  - `file://...` (local filesystem)
  - `s3://bucket/key` (S3)

  Security:
  - Tenant-scoped (session must belong to tenant)
  - For `file://`, access is restricted to `[:recordings :local-root]`.
  - For `s3://`, bucket is allowlisted (see `[:recordings :s3-bucket]`).

  Range:
  - Supports `Range: bytes=start-end` (single range) for seeking.

  Dependencies:
  - deps: {:db {:ds DataSource} :config config}

  Returns:
  - 200/206 with audio stream
  - JSON error body on failures (e.g. 404 not found, 416 invalid range)." 
  [{:keys [db config]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot serve recording audio" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              session-uuid (parse-session-uuid req)
              recording (db.recordings/find-latest-recording ds tenant-uuid session-uuid)
              recording-url (some-> (:recording_url recording) str)
              range (parse-range-header (or (get-in req [:headers "range"])
                                            (get-in req [:headers "Range"])))]
          (cond
            (nil? recording)
            (json-response 404 {:ok false :message "recording-not-found"})

            (str/blank? recording-url)
            (json-response 404 {:ok false :message "recording-url-missing"})

            (str/starts-with? recording-url "file://")
            (let [root (get-in config [:recordings :local-root])
                  f (safe-file-under-root root recording-url)]
              (if (and (.exists ^File f) (.isFile ^File f))
                (-> (ring-stream-file f range)
                    (assoc-in [:headers "Cache-Control"] "no-store"))
                (json-response 404 {:ok false :message "audio-file-not-found"})))

            (str/starts-with? recording-url "s3://")
            (let [{:keys [bucket key]} (parse-s3-url recording-url)
                  allow-bucket (recordings-s3-allow-bucket config)]
              (when-not (and (seq bucket) (seq key))
                (throw (ex-info "invalid-recording-url"
                                {:type :samuraibff.http/invalid-recording-url
                                 :recording-url recording-url})))
              (when (and allow-bucket (not= allow-bucket bucket))
                (throw (ex-info "recording-bucket-not-allowed"
                                {:type :samuraibff.http/recording-bucket-not-allowed
                                 :bucket bucket
                                 :allowed allow-bucket})))
              (with-open [^S3Client s3 (s3.enrollment/build-s3-client config)]
                (-> (ring-stream-s3 s3 bucket key range)
                    (assoc-in [:headers "Cache-Control"] "no-store"))))

            :else
            (json-response 400 {:ok false :message "unsupported-recording-url"})))

        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})

              :samuraibff.http/recordings-local-root-not-configured (json-response 503 {:ok false :message "recordings-local-root-not-configured"})
              :samuraibff.http/recording-path-outside-root (json-response 403 {:ok false :message "recording-path-outside-root"})
              :samuraibff.http/invalid-recording-url (json-response 400 {:ok false :message "invalid-recording-url"})
              :samuraibff.http/recording-bucket-not-allowed (json-response 403 {:ok false :message "recording-bucket-not-allowed"})

              :samuraibff.http/range-not-supported (json-response 416 {:ok false :message "range-not-supported"})
              :samuraibff.http/invalid-range (json-response 416 {:ok false :message "invalid-range"})

              (do
                (log/error e "Failed to serve recording audio")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "Unexpected error serving recording audio")
          (json-response 500 {:ok false :message "internal-error"}))))))

(defn list-recordings-handler
  "Handler for `GET /api/recordings`.

  Query params:
  - limit (optional, default 200)
  - offset (optional, default 0)

  Response body:
  - {:items [ ... ]}

  Each item contains session metadata and best-effort recording flags.

  Dependencies:
  - deps: {:db {:ds DataSource}}

  Returns Ring handler fn." 
  [{:keys [db config]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot serve recordings" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              limit (parse-int (or (get-in req [:params :limit]) (get-in req [:params "limit"])) 200)
              offset (parse-int (or (get-in req [:params :offset]) (get-in req [:params "offset"])) 0)
              rows (db.recordings/list-sessions-for-tenant ds tenant-uuid {:limit limit :offset offset})
              items (mapv (fn [r]
                            {:session_id (str (:id r))
                             :session_key (:session_key r)
                             :status (:status r)
                             :started_at (some-> (:started_at r) str)
                             :ended_at (some-> (:ended_at r) str)
                             :created_at (some-> (:created_at r) str)
                             :has_recording (boolean (:has_recording r))
                             :has_final_transcript (boolean (:has_final_transcript r))
                             :recording {:created_at (some-> (:recording_created_at r) str)
                                         :duration_s (:duration_s r)
                                         :sample_rate (:sample_rate r)
                                         :lang (:lang r)}})
                          rows)
              body {:ok true
                    :tenant_id (str tenant-uuid)
                    :items items}]
          ;; Validate in dev/test.
          (when (#{:dev :test} (:env config))
            (schemas/validate! schemas/RecordingsListResponse body))
          (json-response 200 body))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed to list recordings")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error listing recordings")
          (json-response 500 {:ok false :message "db-error"}))))))

(defn get-recording-handler
  "Handler for `GET /api/recordings/:session_id`.

  Returns metadata + transcript records.

  Response body:
  {:ok true
   :tenant_id <uuid>
   :session {...}
   :transcripts {:refined [...records...] :final [...records...]}}

  Notes:
  - refined is append-only; we return all refined records ordered by created_at.
  - final may have multiple entries; UI will pick the latest.

  Returns 404 if the session is not found within tenant." 
  [{:keys [db config]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot serve recording detail" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              sid-str (or (get-in req [:path-params :session_id])
                          (get-in req [:path-params "session_id"]))
              session-uuid (try
                             (UUID/fromString (str sid-str))
                             (catch Exception _
                               (throw (ex-info "invalid-session-id"
                                               {:type :samuraibff.http/invalid-session-id
                                                :session-id sid-str}))))
              session (db.recordings/find-session-by-id ds tenant-uuid session-uuid)]
          (if-not session
            (json-response 404 {:ok false :message "not-found"})
            (let [refined (db.recordings/list-transcript-records ds tenant-uuid session-uuid {:type "refined" :limit 2000})
                  final (db.recordings/list-transcript-records ds tenant-uuid session-uuid {:type "final" :limit 20})
                  ;; Normalize keys for UI JSON.
                  normalize-record
                  (fn [r]
                    {:id (str (:id r))
                     :type (:type r)
                     :source (:source r)
                     :model (:model r)
                     :window_length (:window_length r)
                     :segment_start_s (:segment_start_s r)
                     :segment_end_s (:segment_end_s r)
                     :supersedes_seq (some-> (:supersedes_seq r) vec)
                     :event_created_at_ns (:event_created_at_ns r)
                     :created_at (some-> (:created_at r) str)
                     :lang (:lang r)
                     :duration_s (:duration_s r)
                     :full_text (:full_text r)
                     ;; segments is jsonb; decode to vector so clients don't need JSON.parse.
                     :segments (segments-jsonb->segments (:segments r))})
                  body {:ok true
                        :tenant_id (str tenant-uuid)
                        :session {:id (str (:id session))
                                  :session_key (:session_key session)
                                  :title (:title session)
                                  :status (:status session)
                                  :started_at (some-> (:started_at session) str)
                                  :ended_at (some-> (:ended_at session) str)
                                  :created_at (some-> (:created_at session) str)}
                        :transcripts {:refined (mapv normalize-record refined)
                                      :final (mapv normalize-record final)}}]
              (when (#{:dev :test} (:env config))
                (schemas/validate! schemas/RecordingDetailResponse body))
              (json-response 200 body))))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed to load recording")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error loading recording")
          (json-response 500 {:ok false :message "db-error"}))))))

(defn delete-recording-handler
  "Handler for `DELETE /api/recordings/:session_id`.

  Deletes the session and cascaded recordings/transcripts (FK ON DELETE CASCADE).

  Response:
  - 200 {:ok true :deleted true}
  - 404 {:ok false :message \"not-found\"} (within tenant)
  - 400 invalid session id
  - 403 missing tenant id
  - 503 db unavailable" 
  [{:keys [db]}]
  (fn [req]
    (let [^DataSource ds (:ds db)]
      (try
        (when-not ds
          (log/error "DB datasource missing; cannot delete recording" {:uri (:uri req)})
          (throw (ex-info "missing-datasource" {:type :samuraibff.http/missing-datasource})))
        (let [tenant-uuid (tenant-id-uuid req)
              sid-str (or (get-in req [:path-params :session_id])
                          (get-in req [:path-params "session_id"]))
              session-uuid (try
                             (UUID/fromString (str sid-str))
                             (catch Exception _
                               (throw (ex-info "invalid-session-id"
                                               {:type :samuraibff.http/invalid-session-id
                                                :session-id sid-str}))))
              {:keys [deleted?]} (db.recordings/delete-session! ds tenant-uuid session-uuid)]
          (if deleted?
            (json-response 200 {:ok true :deleted true})
            (json-response 404 {:ok false :message "not-found"})))
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type]} (ex-data e)]
            (case type
              :samuraibff.http/missing-tenant-id (json-response 403 {:ok false :message "missing-tenant-id"})
              :samuraibff.http/invalid-tenant-id (json-response 400 {:ok false :message "invalid-tenant-id"})
              :samuraibff.http/invalid-session-id (json-response 400 {:ok false :message "invalid-session-id"})
              :samuraibff.http/missing-datasource (json-response 503 {:ok false :message "db-unavailable"})
              (do
                (log/error e "Failed to delete recording")
                (json-response 500 {:ok false :message "internal-error"})))))
        (catch Exception e
          (log/error e "DB error deleting recording")
          (json-response 500 {:ok false :message "db-error"}))))))

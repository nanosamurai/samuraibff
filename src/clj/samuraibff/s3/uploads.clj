(ns samuraibff.s3.uploads
  "Store an uploaded media file unchanged in the recordings bucket.

  The BFF never decodes uploaded media. It checks the leading bytes against a
  short list of audio/video container signatures, enforces a byte limit while
  it copies, and writes the bytes to S3 in parts. Decoding happens in the
  recorder worker.

  Public API:
  - `sniff-container`  leading bytes -> container keyword or nil
  - `source-key`       S3 key for a session's uploaded source
  - `put-source!`      copy a stream to S3 with limits
  - `delete-source!`   best-effort removal

  Security:
  - Client file names and content types are ignored. Only the leading bytes
    decide whether a file looks like a supported container.
  - On any failure a started multipart upload is aborted, so no partial object
    remains."
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log])
  (:import
   (java.io InputStream)
   (java.util Arrays)
   (software.amazon.awssdk.core.sync RequestBody)
   (software.amazon.awssdk.services.s3 S3Client)
   (software.amazon.awssdk.services.s3.model AbortMultipartUploadRequest
                                             CompleteMultipartUploadRequest
                                             CompletedMultipartUpload
                                             CompletedPart
                                             CreateMultipartUploadRequest
                                             DeleteObjectRequest
                                             PutObjectRequest
                                             UploadPartRequest)))

(def part-size
  "Bytes per S3 multipart part (S3 minimum is 5 MiB except for the last part)."
  (* 8 1024 1024))

(defn- ascii-at?
  "True when `bs` contains the ASCII string `s` at `offset`."
  [^bytes bs offset ^String s]
  (let [expected (.getBytes s "US-ASCII")
        end (+ offset (alength expected))]
    (and (<= end (alength bs))
         (Arrays/equals (Arrays/copyOfRange bs (int offset) (int end)) expected))))

(defn- bytes-at?
  "True when `bs` starts with the unsigned byte values `values` at `offset`."
  [^bytes bs offset values]
  (and (<= (+ offset (count values)) (alength bs))
       (every? true? (map-indexed (fn [i v] (= (bit-and (aget bs (+ offset i)) 0xff) v)) values))))

(defn sniff-container
  "Return a keyword for the container that the leading bytes identify, or nil.

  Input: byte array with at least the first 12 bytes of the file.
  Output: one of :wav :avi :aiff :mp3 :aac :flac :ogg :mp4 :matroska :amr
  :asf :caf, or nil when the bytes do not look like a supported container."
  [^bytes head]
  (cond
    (and (ascii-at? head 0 "RIFF") (ascii-at? head 8 "WAVE")) :wav
    (and (ascii-at? head 0 "RIFF") (ascii-at? head 8 "AVI ")) :avi
    (and (ascii-at? head 0 "FORM") (or (ascii-at? head 8 "AIFF") (ascii-at? head 8 "AIFC"))) :aiff
    (ascii-at? head 0 "ID3") :mp3
    (ascii-at? head 0 "fLaC") :flac
    (ascii-at? head 0 "OggS") :ogg
    (ascii-at? head 4 "ftyp") :mp4
    (bytes-at? head 0 [0x1a 0x45 0xdf 0xa3]) :matroska
    (ascii-at? head 0 "#!AMR") :amr
    (bytes-at? head 0 [0x30 0x26 0xb2 0x75]) :asf
    (ascii-at? head 0 "caff") :caf
    (and (>= (alength head) 2)
         (= 0xff (bit-and (aget head 0) 0xff))
         (= 0xf0 (bit-and (aget head 1) 0xf6))) :aac
    (and (>= (alength head) 2)
         (= 0xff (bit-and (aget head 0) 0xff))
         (= 0xe0 (bit-and (aget head 1) 0xe0))) :mp3
    :else nil))

(defn source-key
  "Return the S3 key for a session's uploaded source.

  Inputs: recordings prefix (string or nil), tenant id, session id.
  Output: `<prefix>/<tenant_id>/uploads/<session_id>/source` (no leading slash)."
  [prefix tenant-id session-id]
  (->> [(some-> prefix str (str/replace #"^/+|/+$" "")) (str tenant-id) "uploads" (str session-id) "source"]
       (remove str/blank?)
       (str/join "/")))

(defn- read-part
  "Read up to `n` bytes from `in`. Returns a byte array (empty at end of stream)."
  ^bytes [^InputStream in n]
  (.readNBytes in (int n)))

(defn- too-large!
  [max-bytes]
  (throw (ex-info "Uploaded file is too large" {:type ::too-large :max-bytes max-bytes})))

(defn- put-single!
  "Store a small file with one PutObject request."
  [^S3Client s3 bucket key ^bytes body]
  (.putObject s3
              ^PutObjectRequest (-> (PutObjectRequest/builder)
                                    (.bucket bucket) (.key key)
                                    (.contentType "application/octet-stream")
                                    (.build))
              (RequestBody/fromBytes body)))

(defn- put-multipart!
  "Store `first-part` and the rest of `in` as an S3 multipart upload.

  Returns the total byte count. Aborts the upload and rethrows on any failure."
  [^S3Client s3 bucket key ^bytes first-part ^InputStream in max-bytes]
  (let [upload-id (.uploadId (.createMultipartUpload
                              s3 ^CreateMultipartUploadRequest
                              (-> (CreateMultipartUploadRequest/builder)
                                  (.bucket bucket) (.key key)
                                  (.contentType "application/octet-stream")
                                  (.build))))]
    (try
      (loop [part first-part
             part-number 1
             total (alength first-part)
             completed []]
        (if (zero? (alength part))
          (do
            (.completeMultipartUpload
             s3 ^CompleteMultipartUploadRequest
             (-> (CompleteMultipartUploadRequest/builder)
                 (.bucket bucket) (.key key) (.uploadId upload-id)
                 (.multipartUpload ^CompletedMultipartUpload
                                   (-> (CompletedMultipartUpload/builder)
                                       (.parts ^java.util.Collection completed)
                                       (.build)))
                 (.build)))
            total)
          (let [etag (.eTag (.uploadPart s3
                                         ^UploadPartRequest (-> (UploadPartRequest/builder)
                                                                (.bucket bucket) (.key key)
                                                                (.uploadId upload-id)
                                                                (.partNumber (int part-number))
                                                                (.build))
                                         (RequestBody/fromBytes part)))
                done (conj completed (-> (CompletedPart/builder)
                                         (.partNumber (int part-number))
                                         (.eTag etag)
                                         (.build)))
                next-part (read-part in (min part-size (- (inc max-bytes) total)))
                total' (+ total (alength next-part))]
            (when (> total' max-bytes)
              (too-large! max-bytes))
            (recur next-part (inc part-number) total' done))))
      (catch Throwable t
        (try
          (.abortMultipartUpload s3 ^AbortMultipartUploadRequest
                                 (-> (AbortMultipartUploadRequest/builder)
                                     (.bucket bucket) (.key key) (.uploadId upload-id)
                                     (.build)))
          (catch Exception e
            (log/warn e "Failed to abort multipart upload" {:bucket bucket})))
        (throw t)))))

(defn put-source!
  "Copy an uploaded file stream unchanged to `s3://bucket/key`.

  Inputs:
  - s3: S3Client
  - bucket, key: strings
  - in: InputStream with the file bytes (consumed)
  - max-bytes: positive long; larger files are rejected

  Returns: {:bytes long :container keyword}.

  Throws ex-info with :type
  - ::empty             when the file has no bytes
  - ::unsupported-media when the leading bytes are not a supported container
  - ::too-large         when the file exceeds max-bytes
  Storage errors propagate. Nothing is stored when an exception is thrown."
  [^S3Client s3 bucket key ^InputStream in max-bytes]
  (let [first-part (read-part in (min part-size (inc max-bytes)))
        container (sniff-container first-part)]
    (cond
      (zero? (alength first-part))
      (throw (ex-info "Uploaded file is empty" {:type ::empty}))

      (nil? container)
      (throw (ex-info "Unsupported media" {:type ::unsupported-media}))

      (> (alength first-part) max-bytes)
      (too-large! max-bytes))
    (if (< (alength first-part) part-size)
      (do (put-single! s3 bucket key first-part)
          {:bytes (alength first-part) :container container})
      {:bytes (put-multipart! s3 bucket key first-part in max-bytes)
       :container container})))

(defn delete-source!
  "Best-effort delete of an uploaded source. Logs and returns nil on failure."
  [^S3Client s3 bucket key]
  (try
    (.deleteObject s3 ^DeleteObjectRequest (-> (DeleteObjectRequest/builder)
                                               (.bucket bucket) (.key key)
                                               (.build)))
    (catch Exception e
      (log/warn e "Failed to delete uploaded source" {:bucket bucket})))
  nil)

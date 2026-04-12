;; Copyright (c) samuraibff contributors.
(ns samuraibff.audio.wav
  "WAV parsing + clipping helpers.

  Purpose
  - Extract a short WAV clip from a larger WAV recording (local file or S3 object).
  - Used by speaker enrollment from recordings.

  Supported input:
  - RIFF/WAVE
  - PCM (audioFormat=1)
  - Any channel count, sample rate, bits-per-sample that yields an integral blockAlign.

  Output:
  - A canonical PCM WAV with a 44-byte header (RIFF+fmt(16)+data).

  Security notes
  - This namespace does not do auth / tenant checks.
  - Callers must ensure access control and storage allowlists (file roots, S3 buckets)."
  (:require
    [clojure.string :as str])
  (:import
    (java.io ByteArrayOutputStream InputStream RandomAccessFile)
    (java.nio ByteBuffer ByteOrder)
    (software.amazon.awssdk.services.s3 S3Client)
    (software.amazon.awssdk.services.s3.model GetObjectRequest HeadObjectRequest)
    (software.amazon.awssdk.core ResponseInputStream)))

(def ^:private max-header-bytes
  "How many bytes we will read from the start of the WAV to locate fmt/data chunks.

  WAV headers can contain additional chunks, but in practice they fit well below this.
  If a file has a very large pre-data chunk area, callers will receive an error."
  65536)

(defn- le-int
  "Read a little-endian signed 32-bit int from a ByteBuffer at its current position.

  Inputs:
  - bb: ByteBuffer (little-endian)

  Returns: long (0..2^32-1) by treating the int as unsigned." 
  ^long
  [^ByteBuffer bb]
  (bit-and 0xffffffff (long (.getInt bb))))

(defn- read-ascii4
  "Read 4 bytes and decode as ASCII.

  Inputs:
  - bb: ByteBuffer

  Returns: string length 4." 
  [^ByteBuffer bb]
  (let [arr (byte-array 4)]
    (.get bb arr)
    (String. arr "US-ASCII")))

(defn parse-wav-header
  "Parse a WAV header from the start of a WAV file.

  Inputs:
  - header-bytes: byte-array (must contain the RIFF header and the complete fmt/data chunk headers)

  Returns:
  - {:audio-format int
     :channels int
     :sample-rate int
     :byte-rate int
     :block-align int
     :bits-per-sample int
     :data-offset long   (absolute offset from file start)
     :data-size long}

  Throws:
  - ex-info when the header is invalid or fmt/data chunks are missing." 
  [^bytes header-bytes]
  (when-not (and header-bytes (<= 12 (alength header-bytes)))
    (throw (ex-info "wav-header-too-short" {:type :samuraibff.audio/wav-header-too-short
                                             :bytes (when header-bytes (alength header-bytes))})))
  (let [^ByteBuffer bb (doto (ByteBuffer/wrap header-bytes)
                         (.order ByteOrder/LITTLE_ENDIAN))
        riff (read-ascii4 bb)
        _riff-size (le-int bb)
        wave (read-ascii4 bb)]
    (when-not (= "RIFF" riff)
      (throw (ex-info "not-a-riff-wav" {:type :samuraibff.audio/not-a-riff-wav
                                         :riff riff})))
    (when-not (= "WAVE" wave)
      (throw (ex-info "not-a-wave" {:type :samuraibff.audio/not-a-wave
                                     :wave wave})))

    (loop [fmt nil
           data nil]
      (if (and fmt data)
        (merge fmt data)
        (let [pos (.position bb)]
          ;; We need at least 8 bytes to read the next chunk header.
          (when (> pos (- (alength header-bytes) 8))
            (throw (ex-info "wav-missing-chunks" {:type :samuraibff.audio/wav-missing-chunks
                                                   :have {:fmt? (boolean fmt)
                                                          :data? (boolean data)}})))
          (let [chunk-id (read-ascii4 bb)
                chunk-size (le-int bb)
                chunk-data-pos (.position bb)
                next-pos (+ chunk-data-pos chunk-size (if (odd? chunk-size) 1 0))
                remaining (- (alength header-bytes) chunk-data-pos)
                truncated? (> chunk-size remaining)]

            (cond
              (and (= "fmt " chunk-id) (nil? fmt))
              (do
                (when (< chunk-size 16)
                  (throw (ex-info "wav-fmt-too-short" {:type :samuraibff.audio/wav-fmt-too-short
                                                        :chunk-size chunk-size})))
                (when truncated?
                  (throw (ex-info "wav-chunk-truncated" {:type :samuraibff.audio/wav-chunk-truncated
                                                          :chunk chunk-id
                                                          :chunk-size chunk-size
                                                          :available remaining})))
                (let [audio-format (int (bit-and 0xffff (long (.getShort bb))))
                      channels (int (bit-and 0xffff (long (.getShort bb))))
                      sample-rate (int (le-int bb))
                      byte-rate (int (le-int bb))
                      block-align (int (bit-and 0xffff (long (.getShort bb))))
                      bits-per-sample (int (bit-and 0xffff (long (.getShort bb))))
                      _ (when (<= channels 0)
                          (throw (ex-info "wav-invalid-channels" {:type :samuraibff.audio/wav-invalid-channels
                                                                   :channels channels})))
                      _ (when (<= sample-rate 0)
                          (throw (ex-info "wav-invalid-sample-rate" {:type :samuraibff.audio/wav-invalid-sample-rate
                                                                      :sample-rate sample-rate})))
                      _ (when (<= block-align 0)
                          (throw (ex-info "wav-invalid-block-align" {:type :samuraibff.audio/wav-invalid-block-align
                                                                      :block-align block-align})))
                      _ (when (<= bits-per-sample 0)
                          (throw (ex-info "wav-invalid-bits" {:type :samuraibff.audio/wav-invalid-bits
                                                               :bits-per-sample bits-per-sample})))
                      _ (when-not (= 1 audio-format)
                          (throw (ex-info "wav-unsupported-audio-format" {:type :samuraibff.audio/wav-unsupported-audio-format
                                                                           :audio-format audio-format})))
                      fmt {:audio-format audio-format
                           :channels channels
                           :sample-rate sample-rate
                           :byte-rate byte-rate
                           :block-align block-align
                           :bits-per-sample bits-per-sample}]
                  (if data
                    (merge fmt data)
                    (do
                      (.position bb (int next-pos))
                      (recur fmt data)))))

              (and (= "data" chunk-id) (nil? data))
              (let [data-offset chunk-data-pos
                    data-size (long chunk-size)
                    data {:data-offset (long data-offset)
                          :data-size data-size}]
                ;; Note: we do NOT require the header bytes to include the data payload.
                ;; In production we usually read enough bytes anyway, but tests pass only
                ;; a 44-byte canonical header.
                (if fmt
                  (merge fmt data)
                  (do
                    ;; If data appears before fmt, we must be able to skip its payload.
                    (when truncated?
                      (throw (ex-info "wav-data-before-fmt" {:type :samuraibff.audio/wav-data-before-fmt
                                                             :data-size data-size
                                                             :header-bytes (alength header-bytes)})))
                    (.position bb (int next-pos))
                    (recur fmt data))))

              :else
              (do
                (when truncated?
                  (throw (ex-info "wav-chunk-truncated" {:type :samuraibff.audio/wav-chunk-truncated
                                                          :chunk chunk-id
                                                          :chunk-size chunk-size
                                                          :available remaining})))
                (.position bb (int next-pos))
                (recur fmt data)))))))))

(defn- clamp-window
  "Clamp and normalize a [start_s,end_s] window.

  Inputs:
  - start-s: number
  - end-s: number
  - max-duration-s: number

  Returns:
  - {:start_s double :end_s double :truncated? boolean}

  Throws:
  - ex-info on invalid times." 
  [start-s end-s max-duration-s]
  (let [start (double (or start-s 0.0))
        end (double (or end-s 0.0))]
    (when (or (neg? start) (neg? end) (<= end start))
      (throw (ex-info "invalid-time-window" {:type :samuraibff.audio/invalid-time-window
                                              :start_s start
                                              :end_s end})))
    (let [max-d (double (or max-duration-s 0.0))
          end-max (if (pos? max-d) (min end (+ start max-d)) end)
          truncated? (< end-max end)]
      {:start_s start
       :end_s end-max
       :truncated? truncated?})))

(defn- window->byte-range
  "Convert a [start_s,end_s] window to an absolute [start,end] byte range in the file.

  Inputs:
  - header: map returned by `parse-wav-header`
  - start-s: double
  - end-s: double

  Returns:
  - {:start long :end long :data-bytes long}

  Notes:
  - The range is aligned to blockAlign.
  - The returned :end is inclusive." 
  [{:keys [data-offset data-size byte-rate block-align] :as header} start-s end-s]
  (when-not (and (map? header)
                 (number? data-offset)
                 (number? data-size)
                 (number? byte-rate)
                 (number? block-align))
    (throw (ex-info "invalid-wav-header" {:type :samuraibff.audio/invalid-wav-header
                                          :header (select-keys header [:data-offset :data-size :byte-rate :block-align])})))
  (let [data-offset (long data-offset)
        data-size (long data-size)
        block-align (long block-align)
        byte-rate (double byte-rate)
        start-in-data (long (Math/floor (* start-s byte-rate)))
        end-in-data (long (Math/ceil (* end-s byte-rate)))
        start-aligned (* block-align (quot start-in-data block-align))
        ;; Align the end up to blockAlign so we never cut a frame in half.
        end-aligned (* block-align (long (Math/ceil (/ (double (max start-aligned end-in-data))
                                                       (double block-align)))))
        end-aligned (min data-size end-aligned)
        start-aligned (min data-size start-aligned)
        data-bytes (max 0 (- end-aligned start-aligned))
        abs-start (+ data-offset start-aligned)
        abs-end (dec (+ abs-start data-bytes))]
    (when (zero? data-bytes)
      (throw (ex-info "empty-clip" {:type :samuraibff.audio/empty-clip
                                     :start_s start-s
                                     :end_s end-s})))
    {:start abs-start
     :end abs-end
     :data-bytes data-bytes}))

(defn- pcm-wav-header-bytes
  "Build a canonical 44-byte PCM WAV header.

  Inputs:
  - channels int
  - sample-rate int
  - bits-per-sample int
  - data-bytes long (size of following data chunk)

  Returns:
  - byte-array length 44." 
  ^bytes
  [channels sample-rate bits-per-sample data-bytes]
  (let [channels (int channels)
        sample-rate (int sample-rate)
        bits-per-sample (int bits-per-sample)
        block-align (int (* channels (quot bits-per-sample 8)))
        byte-rate (int (* sample-rate block-align))
        data-bytes (long data-bytes)
        riff-size (+ 36 data-bytes)
        ^ByteBuffer bb (doto (ByteBuffer/allocate 44)
                         (.order ByteOrder/LITTLE_ENDIAN))]
    (.put bb (.getBytes "RIFF" "US-ASCII"))
    (.putInt bb (int riff-size))
    (.put bb (.getBytes "WAVE" "US-ASCII"))
    (.put bb (.getBytes "fmt " "US-ASCII"))
    (.putInt bb 16)
    (.putShort bb (short 1))
    (.putShort bb (short channels))
    (.putInt bb sample-rate)
    (.putInt bb byte-rate)
    (.putShort bb (short block-align))
    (.putShort bb (short bits-per-sample))
    (.put bb (.getBytes "data" "US-ASCII"))
    (.putInt bb (int data-bytes))
    (.array bb)))

(defn clip-wav-file
  "Extract a WAV clip from a local file.

  Inputs:
  - f: java.io.File
  - {:keys [start_s end_s max_duration_s]} numbers

  Returns:
  - {:wav-bytes byte-array
     :clip {:start_s double :end_s double :truncated? boolean}}

  Throws:
  - ex-info on invalid WAV header or window." 
  [^java.io.File f {:keys [start_s end_s max_duration_s]}]
  (when-not (and f (.exists f) (.isFile f))
    (throw (ex-info "file-not-found" {:type :samuraibff.audio/file-not-found
                                       :file (some-> f .getPath)})))
  (let [{:keys [start_s end_s truncated?]} (clamp-window start_s end_s max_duration_s)
        ^RandomAccessFile raf (RandomAccessFile. f "r")]
    (try
      (let [header-len (int (min (long max-header-bytes) (.length raf)))
            header-bytes (byte-array header-len)
            _ (.seek raf 0)
            _ (.readFully raf header-bytes)
            header (parse-wav-header header-bytes)
            {:keys [start end data-bytes]} (window->byte-range header start_s end_s)
            clip-data (byte-array (int data-bytes))
            _ (.seek raf (long start))
            _ (.readFully raf clip-data)
            out-header (pcm-wav-header-bytes (:channels header) (:sample-rate header) (:bits-per-sample header) data-bytes)
            out (ByteArrayOutputStream.)]
        (.write out out-header)
        (.write out clip-data)
        {:wav-bytes (.toByteArray out)
         :clip {:start_s start_s
                :end_s end_s
                :truncated? truncated?}})
      (finally
        (.close raf)))))

(defn- slurp-input-stream
  "Read all bytes from an InputStream.

  Inputs:
  - in: InputStream

  Returns: byte-array." 
  ^bytes
  [^InputStream in]
  (with-open [in in]
    (let [buf (byte-array 8192)
          out (ByteArrayOutputStream.)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.write out buf 0 n)
            (recur))))
      (.toByteArray out))))

(defn clip-wav-s3
  "Extract a WAV clip from an S3 object.

  Inputs:
  - s3: S3Client
  - bucket: string
  - key: string
  - {:keys [start_s end_s max_duration_s]} numbers

  Returns:
  - {:wav-bytes byte-array
     :clip {:start_s double :end_s double :truncated? boolean}}

  Throws:
  - ex-info on invalid WAV header or window." 
  [^S3Client s3 bucket key {:keys [start_s end_s max_duration_s]}]
  (when-not (and s3 (seq (str bucket)) (seq (str key)))
    (throw (ex-info "missing-s3-params" {:type :samuraibff.audio/missing-s3-params
                                          :bucket bucket
                                          :key key})))
  (let [{:keys [start_s end_s truncated?]} (clamp-window start_s end_s max_duration_s)
        head (.headObject s3 (-> (HeadObjectRequest/builder)
                                 (.bucket bucket)
                                 (.key key)
                                 (.build)))
        size (long (.contentLength head))
        header-end (dec (min size (long max-header-bytes)))
        header-resp (.getObject s3 (-> (GetObjectRequest/builder)
                                       (.bucket bucket)
                                       (.key key)
                                       (.range (str "bytes=0-" header-end))
                                       (.build)))
        header-bytes (slurp-input-stream ^ResponseInputStream header-resp)
        header (parse-wav-header header-bytes)
        {:keys [start end data-bytes]} (window->byte-range header start_s end_s)
        range (str "bytes=" start "-" end)
        clip-resp (.getObject s3 (-> (GetObjectRequest/builder)
                                     (.bucket bucket)
                                     (.key key)
                                     (.range range)
                                     (.build)))
        clip-data (slurp-input-stream ^ResponseInputStream clip-resp)
        _ (when-not (= (alength ^bytes clip-data) (int data-bytes))
            (throw (ex-info "s3-range-read-size-mismatch" {:type :samuraibff.audio/s3-range-read-size-mismatch
                                                           :expected data-bytes
                                                           :got (alength ^bytes clip-data)
                                                           :range range})))
        out-header (pcm-wav-header-bytes (:channels header) (:sample-rate header) (:bits-per-sample header) data-bytes)
        out (ByteArrayOutputStream.)]
    (.write out out-header)
    (.write out clip-data)
    {:wav-bytes (.toByteArray out)
     :clip {:start_s start_s
            :end_s end_s
            :truncated? truncated?}}))

(defn sanitize-label
  "Normalize a user-provided speaker label.

  Inputs:
  - label: any

  Returns:
  - trimmed string

  Throws:
  - ex-info when label is blank or too long." 
  [label]
  (let [s (some-> label str str/trim)]
    (when (str/blank? s)
      (throw (ex-info "missing-label" {:type :samuraibff.audio/missing-label})))
    (when (> (count s) 200)
      (throw (ex-info "label-too-long" {:type :samuraibff.audio/label-too-long
                                         :len (count s)})))
    s))

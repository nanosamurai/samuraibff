;; Copyright (c) samuraibff contributors.
(ns samuraibff.s3.enrollment
  "S3 enrollment storage helpers for enrolled speakers.

  This namespace writes per-speaker manifests and samples to S3 using the
  layout described in the drsynth enrollment docs:

    s3://<bucket>/<prefix>/<tenant_id>/speakers/<speaker_id>/speaker.json
    s3://<bucket>/<prefix>/<tenant_id>/speakers/<speaker_id>/samples/<sample_id>.wav

  Public API:
  - `build-s3-client`
  - `speaker-prefix`
  - `sample-key`
  - `manifest-key`
  - `put-speaker!`
  - `delete-speaker-prefix!`

  All public functions include docstrings with inputs/outputs." 
  (:require
    [clojure.string :as str]
    [jsonista.core :as json]
    [org.corfield.logging4j2 :as log])
  (:import
    (java.time Instant)
    (software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider)
    (software.amazon.awssdk.core.sync RequestBody)
    (software.amazon.awssdk.regions Region)
    (software.amazon.awssdk.services.s3 S3Client)
    (software.amazon.awssdk.services.s3.model DeleteObjectRequest ListObjectsV2Request PutObjectRequest)))

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name
                       :escape-non-ascii true}))

(defn build-s3-client
  "Create an AWS SDK S3 client from config.

  Inputs:
  - config: map containing :s3 keys

  Expected keys (all optional except bucket for writes):
  - :region string
  - :endpoint string (e.g. http://localhost:4566)
  - :access-key string
  - :secret-key string
  - :force-path-style? boolean

  Returns:
  - software.amazon.awssdk.services.s3.S3Client" 
  [config]
  (let [{:keys [region endpoint access-key secret-key force-path-style?]} (:s3 config)
        builder (cond-> (S3Client/builder)
                  (seq (str region)) (.region (Region/of region))
                  (seq (str endpoint)) (.endpointOverride (java.net.URI/create endpoint))
                  (and (seq (str access-key)) (seq (str secret-key)))
                  (.credentialsProvider
                    (StaticCredentialsProvider/create
                      (AwsBasicCredentials/create access-key secret-key)))
                  (some? force-path-style?)
                  (.forcePathStyle (boolean force-path-style?)))]
    (.build builder)))

(defn- join-path
  "Join path segments into an S3 key (no double slashes)." 
  [& parts]
  (->> parts
       (map str)
       (map #(str/replace % #"^/+|/+$" ""))
       (remove str/blank?)
       (str/join "/")))

(defn speaker-prefix
  "Return the S3 prefix for a speaker.

  Inputs:
  - config: config map
  - tenant-id: UUID/string
  - speaker-id: UUID/string

  Returns:
  - string prefix (no trailing slash)." 
  [config tenant-id speaker-id]
  (let [prefix (get-in config [:s3 :enrollment-prefix])]
    (join-path prefix tenant-id "speakers" speaker-id)))

(defn manifest-key
  "Return the S3 key for a speaker manifest.

  Inputs:
  - config: config map
  - tenant-id: UUID/string
  - speaker-id: UUID/string

  Returns:
  - string key" 
  [config tenant-id speaker-id]
  (join-path (speaker-prefix config tenant-id speaker-id) "speaker.json"))

(defn sample-key
  "Return the S3 key for a speaker sample.

  Inputs:
  - config: config map
  - tenant-id: UUID/string
  - speaker-id: UUID/string
  - sample-id: UUID/string

  Returns:
  - string key" 
  [config tenant-id speaker-id sample-id]
  (join-path (speaker-prefix config tenant-id speaker-id) "samples" (str sample-id ".wav")))

(defn- s3-url
  "Return s3:// URL for a bucket + key." 
  [bucket key]
  (str "s3://" bucket "/" key))

(defn- manifest-payload
  "Build speaker.json payload.

  Inputs:
  - speaker-id string
  - label string
  - sample-url string
  - sample-id string

  Returns:
  - JSON string." 
  [speaker-id label sample-url sample-id]
  (json/write-value-as-string
    {:speaker_id speaker-id
     :label label
     :updated_at (.toString (Instant/now))
     :samples [{:url sample-url
                :sample_id (str sample-id)}]}
    json-mapper))

(defn put-speaker!
  "Upload a speaker sample and manifest to S3.

  Inputs:
  - s3: S3Client
  - config: config map (expects :s3 :bucket)
  - tenant-id: UUID/string
  - speaker-id: UUID/string
  - label: string
  - sample-id: UUID/string
  - sample-bytes: byte-array

  Returns:
  - {:sample-url string :manifest-url string :sample-key string :manifest-key string}"
  [^S3Client s3 config tenant-id speaker-id label sample-id sample-bytes]
  (let [bucket (get-in config [:s3 :bucket])]
    (when-not (seq (str bucket))
      (throw (ex-info "S3 bucket missing" {:config (select-keys (:s3 config) [:bucket])})))
    (let [sample-key (sample-key config tenant-id speaker-id sample-id)
          manifest-key (manifest-key config tenant-id speaker-id)
          sample-url (s3-url bucket sample-key)
          manifest-url (s3-url bucket manifest-key)
          payload (manifest-payload (str speaker-id) (str label) sample-url (str sample-id))]
      (log/info "Uploading speaker sample" {:bucket bucket :key sample-key})
      (.putObject s3
                  (-> (PutObjectRequest/builder)
                      (.bucket bucket)
                      (.key sample-key)
                      (.contentType "audio/wav")
                      (.build))
                  (RequestBody/fromBytes ^bytes sample-bytes))
      (log/info "Uploading speaker manifest" {:bucket bucket :key manifest-key})
      (.putObject s3
                  (-> (PutObjectRequest/builder)
                      (.bucket bucket)
                      (.key manifest-key)
                      (.contentType "application/json")
                      (.build))
                  (RequestBody/fromBytes (.getBytes payload "UTF-8")))
      {:sample-url sample-url
       :manifest-url manifest-url
       :sample-key sample-key
       :manifest-key manifest-key})))

(defn delete-speaker-prefix!
  "Delete all objects under a speaker prefix.

  Inputs:
  - s3: S3Client
  - config: config map
  - tenant-id: UUID/string
  - speaker-id: UUID/string

  Returns:
  - number of deleted objects." 
  [^S3Client s3 config tenant-id speaker-id]
  (let [bucket (get-in config [:s3 :bucket])
        prefix (str (speaker-prefix config tenant-id speaker-id) "/")]
    (when-not (seq (str bucket))
      (throw (ex-info "S3 bucket missing" {:config (select-keys (:s3 config) [:bucket])})))
    (loop [deleted 0
           token nil]
      (let [req (cond-> (ListObjectsV2Request/builder)
                  true (.bucket bucket)
                  true (.prefix prefix)
                  token (.continuationToken token))
            resp (.listObjectsV2 s3 (.build req))
            contents (.contents resp)
            _ (doseq [obj contents]
                (let [key (.key obj)]
                  (.deleteObject s3
                                 (-> (DeleteObjectRequest/builder)
                                     (.bucket bucket)
                                     (.key key)
                                     (.build)))))
            deleted' (+ deleted (count contents))
            next-token (.nextContinuationToken resp)]
        (if (seq next-token)
          (recur deleted' next-token)
          deleted')))))
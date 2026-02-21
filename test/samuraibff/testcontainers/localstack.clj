;; Copyright (c) samuraibff contributors.
(ns samuraibff.testcontainers.localstack
  "LocalStack Testcontainers helpers (S3).

  Public API:
  - `with-localstack`
  - `s3-endpoint`
  - `s3-credentials`
  - `create-bucket!`
  - `list-objects`

  These helpers are designed for S3 enrollment integration tests." 
  (:import
    (org.testcontainers.containers.localstack LocalStackContainer LocalStackContainer$Service)
    (org.testcontainers.utility DockerImageName)
    (software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider)
    (software.amazon.awssdk.regions Region)
    (software.amazon.awssdk.services.s3 S3Client)
    (software.amazon.awssdk.services.s3.model CreateBucketRequest ListObjectsV2Request)
    (java.net URI)))

(defn start-localstack!
  "Start a LocalStack container (S3 only).

  Returns:
  - LocalStackContainer" 
  []
  (doto (LocalStackContainer. (DockerImageName/parse "localstack/localstack:3.3"))
    (.withServices (into-array LocalStackContainer$Service
                               [LocalStackContainer$Service/S3]))
    (.start)))

(defn stop-localstack!
  "Stop a LocalStack container." 
  [^LocalStackContainer c]
  (when c
    (try
      (.stop c)
      (catch Exception _
        nil))))

(defn s3-endpoint
  "Return the S3 endpoint URL for LocalStack." 
  [^LocalStackContainer c]
  (str (.getEndpointOverride c LocalStackContainer$Service/S3)))

(defn s3-credentials
  "Return {:access-key :secret-key :region} from LocalStack." 
  [^LocalStackContainer c]
  {:access-key (.getAccessKey c)
   :secret-key (.getSecretKey c)
   :region (.getRegion c)})

(defn s3-client
  "Create an S3 client configured for LocalStack." 
  [^LocalStackContainer c]
  (let [{:keys [access-key secret-key region]} (s3-credentials c)
        endpoint (s3-endpoint c)]
    (-> (S3Client/builder)
        (.endpointOverride (URI/create endpoint))
        (.region (Region/of region))
        (.credentialsProvider
          (StaticCredentialsProvider/create
            (AwsBasicCredentials/create access-key secret-key)))
        (.forcePathStyle true)
        (.build))))

(defn create-bucket!
  "Create an S3 bucket in LocalStack." 
  [^S3Client s3 bucket]
  (.createBucket s3 (-> (CreateBucketRequest/builder)
                        (.bucket bucket)
                        (.build)))
  bucket)

(defn list-objects
  "List object keys for a bucket/prefix.

  Returns: vector of keys." 
  [^S3Client s3 bucket prefix]
  (let [req (-> (ListObjectsV2Request/builder)
                (.bucket bucket)
                (.prefix (or prefix ""))
                (.build))
        resp (.listObjectsV2 s3 req)]
    (vec (map #(.key %) (.contents resp)))))

(defmacro with-localstack
  "Run body with a running LocalStack container.

  Binds:
  - container-sym => LocalStackContainer" 
  [[container-sym] & body]
  `(let [~container-sym (start-localstack!)]
     (try
       ~@body
       (finally
         (stop-localstack! ~container-sym)))))
;; Copyright (c) samuraibff contributors.
(ns samuraibff.s3.client
  "Shared S3 client builder for samuraibff.

  This namespace centralizes construction of the AWS SDK S3 client so that
  multiple features (enrollments, recordings playback, future persistence)
  share a single implementation.

  Auth model:
  - In production we typically rely on the AWS default credential chain
    (IAM role / IRSA / instance profile), so access/secret keys are usually not
    configured.
  - In local/dev environments (LocalStack, MinIO, Ceph RGW), static credentials
    may be provided.

  Public API:
  - `build-s3-client`

  Config:
  Reads keys under `[:s3 ...]`:
  - :region string (optional)
  - :endpoint string (optional)
  - :access-key string (optional)
  - :secret-key string (optional)
  - :force-path-style? boolean (optional)

  Returns:
  - software.amazon.awssdk.services.s3.S3Client"
  (:require
    [clojure.string :as str])
  (:import
    (java.util.function Consumer)
    (software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider)
    (software.amazon.awssdk.regions Region)
    (software.amazon.awssdk.services.s3 S3Client)))

(defn build-s3-client
  "Create an AWS SDK S3 client from config.

  Inputs:
  - config: map containing an :s3 key

  Output:
  - software.amazon.awssdk.services.s3.S3Client

  Notes:
  - When :access-key/:secret-key are blank, the AWS default credential chain is
    used (IAM/IRSA/etc.)."
  [config]
  (let [{:keys [region endpoint access-key secret-key force-path-style?]} (:s3 config)
        builder (cond-> (S3Client/builder)
                  (seq (str/trim (str region)))
                  (.region (Region/of (str region)))

                  (seq (str/trim (str endpoint)))
                  (.endpointOverride (java.net.URI/create (str endpoint)))

                  (some? force-path-style?)
                  (.serviceConfiguration
                    (reify Consumer
                      (accept [_ cfg-builder]
                        ;; cfg-builder is software.amazon.awssdk.services.s3.S3Configuration$Builder
                        (.pathStyleAccessEnabled cfg-builder (boolean force-path-style?)))))

                  (and (seq (str/trim (str access-key)))
                       (seq (str/trim (str secret-key))))
                  (.credentialsProvider
                    (StaticCredentialsProvider/create
                      (AwsBasicCredentials/create (str access-key) (str secret-key)))))]
    (.build builder)))

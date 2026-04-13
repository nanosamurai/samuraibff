(ns samuraibff.secrets.aws-secrets-manager
  "AWS Secrets Manager implementation of `samuraibff.secrets.core/SecretStore`.

  This is used for webhook secrets in production and for integration tests
  against LocalStack.

  Config (under `:secrets` in `:samuraibff/config`):
  - :backend :aws-secrets-manager
  - :aws {:region string
          :endpoint string (optional; LocalStack)
          :access-key string (optional)
          :secret-key string (optional)
          :kms-key-id string (optional)
          :name-prefix string (optional; default nanosamurai)
          :name-suffix string (optional)}

  Secret naming
  -------------
  We generate a unique secret name containing:
  - prefix
  - tenant_id
  - logical name
  - random UUID
  "
  (:require
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [samuraibff.secrets.core :as secrets.core])
  (:import
    (java.net URI)
    (java.util UUID)
    (software.amazon.awssdk.auth.credentials AwsBasicCredentials StaticCredentialsProvider)
    (software.amazon.awssdk.regions Region)
    (software.amazon.awssdk.services.secretsmanager SecretsManagerClient)
    (software.amazon.awssdk.services.secretsmanager.model CreateSecretRequest DeleteSecretRequest)))

(defn- join-name
  [& parts]
  (->> parts
       (map (fn [p]
              (-> (str p)
                  (str/trim)
                  (str/replace #"\s+" "-")
                  (str/replace #"[^a-zA-Z0-9_.\-/]" "-"))))
       (remove str/blank?)
       (str/join "/")))

(defn build-client
  "Build an AWS Secrets Manager client.

  Inputs:
  - aws-config map with keys:
      :region string
      :endpoint string (optional)
      :access-key string (optional)
      :secret-key string (optional)

  Returns: SecretsManagerClient" 
  [{:keys [region endpoint access-key secret-key]}]
  (let [builder (cond-> (SecretsManagerClient/builder)
                  (seq (str/trim (str region)))
                  (.region (Region/of (str region)))

                  (seq (str/trim (str endpoint)))
                  (.endpointOverride (URI/create (str endpoint)))

                  (and (seq (str/trim (str access-key)))
                       (seq (str/trim (str secret-key))))
                  (.credentialsProvider
                    (StaticCredentialsProvider/create
                      (AwsBasicCredentials/create (str access-key) (str secret-key)))))]
    (.build builder)))

(defn secret-store
  "Create an AWS Secrets Manager-backed SecretStore.

  Inputs:
  - config: full config map

  Returns: map implementing SecretStore." 
  [config]
  (let [aws (get-in config [:secrets :aws] {})
        client (build-client aws)
        name-prefix (or (secrets.core/blank->nil (:name-prefix aws)) "nanosamurai")
        name-suffix (secrets.core/blank->nil (:name-suffix aws))
        kms-key-id (secrets.core/blank->nil (:kms-key-id aws))]
    (reify
      secrets.core/SecretStore
      (put-secret! [_ {:keys [tenant-id name value]}]
        (let [tenant-id (or (secrets.core/blank->nil tenant-id) "unknown")
              logical-name (or (secrets.core/blank->nil name) "secret")
              v (or (secrets.core/blank->nil value) "")
              secret-name (join-name name-prefix "tenants" tenant-id logical-name (str (UUID/randomUUID)) name-suffix)
              req (cond-> (CreateSecretRequest/builder)
                    true (.name secret-name)
                    true (.secretString v)
                    kms-key-id (.kmsKeyId kms-key-id)
                    true (.build))
              resp (.createSecret client req)
              arn (some-> resp .arn str)]
          (log/info "Stored secret in Secrets Manager" {:tenant_id (str tenant-id)
                                                        :secret_name secret-name
                                                        :has_arn (boolean (seq arn))})
          {:secret-ref (str "aws-sm:" (or arn secret-name))}))

      (delete-secret! [_ {:keys [secret-ref]}]
        (let [ref (secrets.core/blank->nil secret-ref)]
          (when (and ref (str/starts-with? ref "aws-sm:"))
            (let [id (subs ref (count "aws-sm:"))]
              (try
                (.deleteSecret client
                               (-> (DeleteSecretRequest/builder)
                                   (.secretId id)
                                   ;; Immediate delete in tests; in prod we can revisit.
                                   (.forceDeleteWithoutRecovery true)
                                   (.build)))
                (catch Exception e
                  (log/warn e "Failed deleting secret" {:secret_ref secret-ref}))))))
        nil)

      java.io.Closeable
      (close [_]
        (try
          (.close client)
          (catch Exception _ nil))))))

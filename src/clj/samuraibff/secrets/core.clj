(ns samuraibff.secrets.core
  "Secrets abstraction for samuraibff.

  Purpose
  -------
  Webhook configuration contains customer-managed secrets (HMAC signing secrets,
  OAuth client secrets, API keys). Per RFC, these secrets must be **write-only**
  and must not be stored in Postgres.

  This namespace defines a small protocol used by HTTP handlers:

  - store a secret value and return a *reference* string
  - delete a previously stored secret by reference

  Implementations:
  - AWS Secrets Manager (LocalStack in dev/integration tests)
  - Kubernetes Secrets (optional, for high-cardinality secret sets)

  Data model
  ----------
  We treat secret references as opaque strings.

  Expected reference formats:
  - AWS SM:  aws-sm:<secret-arn> or aws-sm:<secret-name> (implementation detail)
  - K8s:     k8s-secret:<namespace>/<name>#<key> (implementation detail)
  "
  (:require
    [clojure.string :as str]))

(defprotocol SecretStore
  (put-secret!
    [this params]
    "Store a secret and return a reference string.

    Inputs:
    - this: SecretStore implementation
    - params: map
        {:tenant-id string/uuid
         :name string
         :value string}

    Returns:
    - {:secret-ref string}")
  (delete-secret!
    [this params]
    "Delete a secret by reference.

    Inputs:
    - this: SecretStore implementation
    - params: map
        {:secret-ref string}

    Returns: nil"))

(defn blank->nil
  "Normalize a string-like value into nil when blank.

  Inputs:
  - s: any

  Returns:
  - string or nil." 
  [s]
  (let [s0 (some-> s str str/trim)]
    (when-not (str/blank? (or s0 ""))
      s0)))

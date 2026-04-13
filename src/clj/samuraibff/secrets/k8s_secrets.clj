(ns samuraibff.secrets.k8s-secrets
  "Kubernetes Secrets implementation of `samuraibff.secrets.core/SecretStore`.

  Notes
  -----
  This is an optional backend intended for high-cardinality customer-managed
  secrets where AWS Secrets Manager cost/ops can become undesirable.

  In this repo we intentionally keep the interface small and the implementation
  minimal.

  For now, this backend is a **stub** that returns a deterministic reference
  without actually creating Kubernetes resources. In production deployments,
  this should be implemented by:

  - using the Kubernetes API (in-cluster) to create/update Secret objects, OR
  - delegating to an external controller/operator (preferred).

  We still implement the protocol so callers can switch backends.

  Reference format
  ----------------
  k8s-secret:<namespace>/<name>#<key>
  "
  (:require
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]
    [samuraibff.secrets.core :as secrets.core])
  (:import
    (java.util UUID)))

(defn secret-store
  "Create a Kubernetes-backed SecretStore.

  Inputs:
  - config: full config map

  Config keys (under [:secrets :k8s]):
  - :namespace string (default "default")
  - :key string (default "value")
  - :name-prefix string (default "nanosamurai")

  Returns: SecretStore.

  WARNING:
  - This is currently a stub; it does not call Kubernetes API." 
  [config]
  (let [k8s (get-in config [:secrets :k8s] {})
        namespace (or (secrets.core/blank->nil (:namespace k8s)) "default")
        key (or (secrets.core/blank->nil (:key k8s)) "value")
        name-prefix (or (secrets.core/blank->nil (:name-prefix k8s)) "nanosamurai")]
    (reify
      secrets.core/SecretStore
      (put-secret! [_ {:keys [tenant-id name value]}]
        (let [tenant-id (or (secrets.core/blank->nil tenant-id) "unknown")
              logical-name (or (secrets.core/blank->nil name) "secret")
              _ (when (str/blank? (or value ""))
                  (log/warn "Storing blank secret value" {:tenant_id tenant-id :name logical-name}))
              secret-name (str name-prefix "-" tenant-id "-" logical-name "-" (UUID/randomUUID))
              ref (str "k8s-secret:" namespace "/" secret-name "#" key)]
          (log/info "Generated k8s secret reference (stub backend)" {:tenant_id tenant-id
                                                                      :secret_name secret-name
                                                                      :namespace namespace
                                                                      :key key})
          {:secret-ref ref}))

      (delete-secret! [_ {:keys [secret-ref]}]
        (when (and (string? secret-ref) (str/starts-with? secret-ref "k8s-secret:"))
          (log/info "Ignoring delete-secret! for k8s stub backend" {:secret_ref secret-ref}))
        nil))))

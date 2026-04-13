(ns samuraibff.secrets.component
  "Integrant component for secrets backend selection.

  Integrant key:
  - :samuraibff/secrets

  Config (under :samuraibff/config):
  - [:secrets :backend] keyword
      :aws-secrets-manager (default)
      :k8s-secrets
  - backend-specific sub-maps under [:secrets :aws] and [:secrets :k8s]

  Returns component map:
  - {:store <SecretStore>}
  "
  (:require
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log]
    [samuraibff.secrets.aws-secrets-manager :as aws-sm]
    [samuraibff.secrets.k8s-secrets :as k8s]
    [samuraibff.secrets.core :as secrets.core]))

(defn- backend
  [config]
  (or (get-in config [:secrets :backend]) :aws-secrets-manager))

(defmethod ig/init-key :samuraibff/secrets
  [_ {:keys [config]}]
  (let [b (backend config)
        _ (log/info "Starting secrets backend" {:backend b})
        store (case b
                :k8s-secrets (k8s/secret-store config)
                ;; default
                (aws-sm/secret-store config))]
    {:store store
     :backend b
     :config config}))

(defmethod ig/halt-key! :samuraibff/secrets
  [_ {:keys [store]}]
  (when (instance? java.io.Closeable store)
    (try
      (.close ^java.io.Closeable store)
      (catch Exception _ nil)))
  nil)

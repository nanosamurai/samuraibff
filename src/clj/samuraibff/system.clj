(ns samuraibff.system
  "System lifecycle helpers.

  This namespace provides small helpers for starting/stopping the Integrant
  system from `resources/system.edn`.

  Public API:
  - `read-system-config`
  - `start!`
  - `stop!`

  Notes:
  - Some Integrant keys in system.edn may be present for future PRs (DB/Kafka/Auth).
    You can comment them out in system.edn if they are not yet implemented." 
  (:require
    [clojure.java.io :as io]
    [integrant.core :as ig]
    [clojure.string :as str]
    [org.corfield.logging4j2 :as log]))

(defonce ^:private system* (atom nil))

(defn- getenv
  "Read an environment variable.

  Inputs:
  - k: string

  Returns:
  - string value or nil." 
  [^String k]
  (System/getenv k))

(defn- parse-bool
  "Parse a boolean from an env var string.

  Accepts: true/false, 1/0, yes/no (case-insensitive).

  Returns:
  - boolean or nil if input is blank/nil or not parseable." 
  [s]
  (let [s0 (some-> s str str/trim str/lower-case)]
    (cond
      (or (nil? s0) (str/blank? s0)) nil
      (#{"true" "1" "yes" "y"} s0) true
      (#{"false" "0" "no" "n"} s0) false
      :else nil)))

(defn- parse-int
  "Parse an integer from an env var string.

  Returns:
  - int or nil if input is blank/nil or not parseable." 
  [s]
  (let [s0 (some-> s str str/trim)]
    (when-not (str/blank? (or s0 ""))
      (try
        (Integer/parseInt s0)
        (catch Exception _
          nil)))))

(defn- deep-merge
  "Deep merge nested maps.

  Later values win.

  Inputs:
  - ms: maps

  Returns: merged map" 
  [& ms]
  (apply
    merge-with
    (fn [a b]
      (if (and (map? a) (map? b))
        (apply deep-merge [a b])
        b))
    ms))

(defn- prune-nils
  "Recursively remove nil values from nested maps.

  This is used to ensure env-derived overrides only override when an env var is
  actually present.

  Inputs:
  - x: any

  Returns:
  - x with nil leaves removed; empty maps become nil." 
  [x]
  (cond
    (map? x)
    (let [m (->> x
                 (keep (fn [[k v]]
                         (let [v' (prune-nils v)]
                           (when-not (nil? v')
                             [k v']))))
                 (into {}))]
      (when (seq m) m))

    (sequential? x)
    (let [xs (->> x (map prune-nils) (remove nil?) vec)]
      (when (seq xs) xs))

    :else
    x))

(defn- env-overrides-map
  "Build a config-shaped map from environment variables.

  The returned map mirrors the shape of the Integrant config we care about
  (primarily `:samuraibff/config`). Any missing/blank env vars become nil so
  `prune-nils` can strip them before merging.

  Inputs:
  - getenv-fn: (fn [string] => string|nil)

  Returns:
  - partial Integrant config map" 
  [getenv-fn]
  (let [s (fn [k] (some-> (getenv-fn k) str/trim not-empty))
        b (fn [k] (parse-bool (getenv-fn k)))
        i (fn [k] (parse-int (getenv-fn k)))
        kafka-security-protocol (or (s "SAMURAIBFF_KAFKA_SECURITY_PROTOCOL")
                                    (s "KAFKA_SECURITY_PROTOCOL"))]
    {:samuraibff/config
     {:env (some-> (s "SAMURAIBFF_ENV") keyword)

      :http {:host (s "SAMURAIBFF_HTTP_HOST")
             :port (i "SAMURAIBFF_HTTP_PORT")}

      :ws {:host (s "SAMURAIBFF_WS_HOST")
           :port (i "SAMURAIBFF_WS_PORT")}

      :auth {:required? (b "SAMURAIBFF_AUTH_REQUIRED")
             :guest-tenant-id (s "SAMURAIBFF_AUTH_GUEST_TENANT_ID")
             :issuer (s "SAMURAIBFF_AUTH_ISSUER")
             :audience (s "SAMURAIBFF_AUTH_AUDIENCE")
             :client-id (s "SAMURAIBFF_AUTH_CLIENT_ID")
             :cookie-name (s "SAMURAIBFF_AUTH_COOKIE_NAME")
             :tenant-claim (s "SAMURAIBFF_AUTH_TENANT_CLAIM")}

      ;; Keycloak admin API (optional; used only for M2M credential management)
      :keycloak {:admin {:issuer (s "SAMURAIBFF_KEYCLOAK_ADMIN_ISSUER")
                         :realm (s "SAMURAIBFF_KEYCLOAK_ADMIN_REALM")
                         :client-id (s "SAMURAIBFF_KEYCLOAK_ADMIN_CLIENT_ID")
                         :client-secret (s "SAMURAIBFF_KEYCLOAK_ADMIN_CLIENT_SECRET")}}

      :db {:jdbc-url (s "SAMURAIBFF_DB_JDBC_URL")
           :username (s "SAMURAIBFF_DB_USERNAME")
           :password (s "SAMURAIBFF_DB_PASSWORD")
           :maximum-pool-size (i "SAMURAIBFF_DB_MAX_POOL_SIZE")}

      :kafka {:bootstrap-servers (s "SAMURAIBFF_KAFKA_BOOTSTRAP_SERVERS")
              :client-id (s "SAMURAIBFF_KAFKA_CLIENT_ID")
              :acks (s "SAMURAIBFF_KAFKA_ACKS")
              :compression-type (s "SAMURAIBFF_KAFKA_COMPRESSION_TYPE")
              :security-protocol kafka-security-protocol
              :consumer-group-id (s "SAMURAIBFF_KAFKA_CONSUMER_GROUP_ID")
              :topics {:audio-raw (s "SAMURAIBFF_KAFKA_TOPIC_AUDIO_RAW")
                       :refined (s "SAMURAIBFF_KAFKA_TOPIC_REFINED")}}

      :grpc {:rtservice-addr (s "SAMURAIBFF_GRPC_RTSERVICE_ADDR")}

      ;; Recordings playback (audio).
      ;; - local-root: filesystem path allowed for file:// recording_url values
      :recordings {:local-root (s "SAMURAIBFF_RECORDINGS_LOCAL_ROOT")}

      :s3 {:region (s "SAMURAIBFF_S3_REGION")
           :endpoint (s "SAMURAIBFF_S3_ENDPOINT")
           :access-key (s "SAMURAIBFF_S3_ACCESS_KEY")
           :secret-key (s "SAMURAIBFF_S3_SECRET_KEY")
           :force-path-style? (b "SAMURAIBFF_S3_FORCE_PATH_STYLE")
           :buckets {:enrollments {:bucket (s "SAMURAIBFF_S3_ENROLLMENTS_BUCKET")
                                   :prefix (s "SAMURAIBFF_S3_ENROLLMENTS_PREFIX")}
                     :recordings {:bucket (s "SAMURAIBFF_S3_RECORDINGS_BUCKET")
                                  :prefix (s "SAMURAIBFF_S3_RECORDINGS_PREFIX")}}}

       :secrets {:backend (some-> (s "SAMURAIBFF_SECRETS_BACKEND") keyword)
                 :aws {:region (s "SAMURAIBFF_SECRETS_AWS_REGION")
                       :endpoint (s "SAMURAIBFF_SECRETS_AWS_ENDPOINT")
                       :access-key (s "SAMURAIBFF_SECRETS_AWS_ACCESS_KEY")
                       :secret-key (s "SAMURAIBFF_SECRETS_AWS_SECRET_KEY")
                       :kms-key-id (s "SAMURAIBFF_SECRETS_AWS_KMS_KEY_ID")
                       :name-prefix (s "SAMURAIBFF_SECRETS_AWS_NAME_PREFIX")
                       :name-suffix (s "SAMURAIBFF_SECRETS_AWS_NAME_SUFFIX")}
                 :k8s {:namespace (s "SAMURAIBFF_SECRETS_K8S_NAMESPACE")
                       :key (s "SAMURAIBFF_SECRETS_K8S_KEY")
                       :name-prefix (s "SAMURAIBFF_SECRETS_K8S_NAME_PREFIX")}}

      :bff {:origin-uri (s "SAMURAIBFF_ORIGIN_URI")
            ;; Public browser origin used for OIDC redirect_uri computation.
            ;; Keep this separate from origin-uri (which may be a pod-IP for inter-BFF callbacks).
            :public-origin-uri (s "SAMURAIBFF_PUBLIC_ORIGIN_URI")
            :callback-path (s "SAMURAIBFF_CALLBACK_PATH")}}}))

(defn- apply-env-overrides
  "Overlay environment-variable overrides onto the base config map.

  Only env vars that are present (and parse successfully for typed values)
  override existing config.

  Inputs:
  - cfg: Integrant config map (as read from system.edn)

  Returns:
  - cfg' with overrides applied." 
  ([cfg]
   (apply-env-overrides cfg getenv))
  ([cfg getenv-fn]
   (let [overrides (-> (env-overrides-map getenv-fn)
                       prune-nils)]
     (if overrides
       (deep-merge cfg overrides)
       cfg))))

(defn- read-config-from-path
  "Read Integrant EDN config from an explicit filesystem path.

  Inputs:
  - path: string

  Returns:
  - config map

  Throws:
  - ex-info when file cannot be read." 
  [path]
  (try
    (ig/read-string (slurp path))
    (catch Exception e
      (throw (ex-info "Failed to read config from SAMURAIBFF_CONFIG_PATH"
                      {:path path}
                      e)))))

(defn read-system-config
  "Read Integrant configuration from `resources/system.edn`.

  This uses Integrant's EDN readers so tags like `#ig/ref` work.

  Environment overrides:
  - If `SAMURAIBFF_CONFIG_PATH` is set, config is read from that file path
    instead of classpath `system.edn`.
  - Selected env vars are overlaid on top of the EDN config (see
    `apply-env-overrides`).

  Returns: config map." 
  []
  (let [cfg-path (some-> (getenv "SAMURAIBFF_CONFIG_PATH") str/trim not-empty)
        cfg (if cfg-path
              (do
                (log/info "Reading config from SAMURAIBFF_CONFIG_PATH" {:path cfg-path})
                (read-config-from-path cfg-path))
              (-> "system.edn" io/resource slurp ig/read-string))]
    (apply-env-overrides cfg)))

(defn start!
  "Initialize the Integrant system from `resources/system.edn`.

  Side effects:
  - loads key namespaces referenced in cfg
  - starts components
  - stores the system in an internal atom

  Returns: the initialized system map." 
  []
  (let [cfg (read-system-config)
        _ (ig/load-namespaces cfg)
        sys (ig/init cfg)]
    (reset! system* sys)
    (log/info "System started" {:keys (keys sys)})
    sys))

(defn stop!
  "Stop the currently running Integrant system (if any).

  Returns: nil." 
  []
  (when-let [sys @system*]
    (try
      (ig/halt! sys)
      (finally
        (reset! system* nil)))
    (log/info "System stopped" {}))
  nil)

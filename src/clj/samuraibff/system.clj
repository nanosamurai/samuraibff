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

(defn- apply-env-overrides
  "Overlay environment-variable overrides onto the base config map.

  Only env vars that are present (and parse successfully for typed values)
  override existing config.

  Inputs:
  - cfg: Integrant config map (as read from system.edn)

  Returns:
  - cfg' with overrides applied." 
  [cfg]
  (let [cfg0 cfg
        ;; helper so we can keep the override table compact
        set-in-if (fn [m path v]
                    (if (nil? v) m (assoc-in m path v)))
        ;; --- typed values ---
        auth-required (parse-bool (getenv "SAMURAIBFF_AUTH_REQUIRED"))
        http-port (parse-int (getenv "SAMURAIBFF_HTTP_PORT"))
        db-max-pool-size (parse-int (getenv "SAMURAIBFF_DB_MAX_POOL_SIZE"))]
    (-> cfg0
        ;; :env
        (set-in-if [:samuraibff/config :env]
                   (some-> (getenv "SAMURAIBFF_ENV") str/trim not-empty keyword))

        ;; HTTP
        (set-in-if [:samuraibff/config :http :host]
                   (some-> (getenv "SAMURAIBFF_HTTP_HOST") str/trim not-empty))
        (set-in-if [:samuraibff/config :http :port] http-port)

        ;; WS (kept for parity; currently same port)
        (set-in-if [:samuraibff/config :ws :host]
                   (some-> (getenv "SAMURAIBFF_WS_HOST") str/trim not-empty))
        (set-in-if [:samuraibff/config :ws :port]
                   (parse-int (getenv "SAMURAIBFF_WS_PORT")))

        ;; Auth / OIDC
        (set-in-if [:samuraibff/config :auth :required?] auth-required)
        (set-in-if [:samuraibff/config :auth :guest-tenant-id]
                   (some-> (getenv "SAMURAIBFF_AUTH_GUEST_TENANT_ID") str/trim not-empty))
        (set-in-if [:samuraibff/config :auth :issuer]
                   (some-> (getenv "SAMURAIBFF_AUTH_ISSUER") str/trim not-empty))
        (set-in-if [:samuraibff/config :auth :audience]
                   (some-> (getenv "SAMURAIBFF_AUTH_AUDIENCE") str/trim not-empty))
        (set-in-if [:samuraibff/config :auth :client-id]
                   (some-> (getenv "SAMURAIBFF_AUTH_CLIENT_ID") str/trim not-empty))
        (set-in-if [:samuraibff/config :auth :cookie-name]
                   (some-> (getenv "SAMURAIBFF_AUTH_COOKIE_NAME") str/trim not-empty))
        (set-in-if [:samuraibff/config :auth :tenant-claim]
                   (some-> (getenv "SAMURAIBFF_AUTH_TENANT_CLAIM") str/trim not-empty))

        ;; DB
        (set-in-if [:samuraibff/config :db :jdbc-url]
                   (some-> (getenv "SAMURAIBFF_DB_JDBC_URL") str/trim not-empty))
        (set-in-if [:samuraibff/config :db :username]
                   (some-> (getenv "SAMURAIBFF_DB_USERNAME") str/trim not-empty))
        (set-in-if [:samuraibff/config :db :password]
                   (some-> (getenv "SAMURAIBFF_DB_PASSWORD") str/trim not-empty))
        (set-in-if [:samuraibff/config :db :maximum-pool-size] db-max-pool-size)

        ;; Kafka
        (set-in-if [:samuraibff/config :kafka :bootstrap-servers]
                   (some-> (getenv "SAMURAIBFF_KAFKA_BOOTSTRAP_SERVERS") str/trim not-empty))
        (set-in-if [:samuraibff/config :kafka :client-id]
                   (some-> (getenv "SAMURAIBFF_KAFKA_CLIENT_ID") str/trim not-empty))
        (set-in-if [:samuraibff/config :kafka :acks]
                   (some-> (getenv "SAMURAIBFF_KAFKA_ACKS") str/trim not-empty))
        (set-in-if [:samuraibff/config :kafka :compression-type]
                   (some-> (getenv "SAMURAIBFF_KAFKA_COMPRESSION_TYPE") str/trim not-empty))
        (set-in-if [:samuraibff/config :kafka :consumer-group-id]
                   (some-> (getenv "SAMURAIBFF_KAFKA_CONSUMER_GROUP_ID") str/trim not-empty))
        (set-in-if [:samuraibff/config :kafka :topics :audio-raw]
                   (some-> (getenv "SAMURAIBFF_KAFKA_TOPIC_AUDIO_RAW") str/trim not-empty))
        (set-in-if [:samuraibff/config :kafka :topics :refined]
                   (some-> (getenv "SAMURAIBFF_KAFKA_TOPIC_REFINED") str/trim not-empty))

        ;; gRPC
        (set-in-if [:samuraibff/config :grpc :rtservice-addr]
                   (some-> (getenv "SAMURAIBFF_GRPC_RTSERVICE_ADDR") str/trim not-empty))

        ;; S3 enrollment storage
        (set-in-if [:samuraibff/config :s3 :bucket]
                   (some-> (getenv "SAMURAIBFF_S3_BUCKET") str/trim not-empty))
        (set-in-if [:samuraibff/config :s3 :enrollment-prefix]
                   (some-> (getenv "SAMURAIBFF_S3_ENROLLMENT_PREFIX") str/trim not-empty))
        (set-in-if [:samuraibff/config :s3 :region]
                   (some-> (getenv "SAMURAIBFF_S3_REGION") str/trim not-empty))
        (set-in-if [:samuraibff/config :s3 :endpoint]
                   (some-> (getenv "SAMURAIBFF_S3_ENDPOINT") str/trim not-empty))
        (set-in-if [:samuraibff/config :s3 :access-key]
                   (some-> (getenv "SAMURAIBFF_S3_ACCESS_KEY") str/trim not-empty))
        (set-in-if [:samuraibff/config :s3 :secret-key]
                   (some-> (getenv "SAMURAIBFF_S3_SECRET_KEY") str/trim not-empty))
        (set-in-if [:samuraibff/config :s3 :force-path-style?]
                   (parse-bool (getenv "SAMURAIBFF_S3_FORCE_PATH_STYLE")))

        ;; BFF identity
        (set-in-if [:samuraibff/config :bff :origin-uri]
                   (some-> (getenv "SAMURAIBFF_ORIGIN_URI") str/trim not-empty))
        (set-in-if [:samuraibff/config :bff :callback-path]
                   (some-> (getenv "SAMURAIBFF_CALLBACK_PATH") str/trim not-empty)))))

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

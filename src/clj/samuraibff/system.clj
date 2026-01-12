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
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [integrant.core :as ig]
    [org.corfield.logging4j2 :as log]))

(defonce ^:private system* (atom nil))

(defn read-system-config
  "Read Integrant configuration from `resources/system.edn`.

  This uses Integrant's EDN readers so tags like `#ig/ref` work.

  Returns: config map." 
  []
  (-> "system.edn"
      io/resource
      slurp
      ig/read-string))

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

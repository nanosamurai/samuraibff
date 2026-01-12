(ns samuraibff.core
  "Application entrypoint.

  This namespace exists primarily so that the `:run` alias in `deps.edn`
  (`clojure -M:run`) has a stable main namespace.

  It delegates to `samuraibff.system/start!` and installs a shutdown hook
  that calls `samuraibff.system/stop!`.

  Usage:

  ```bash
  clojure -M:run
  ```"
  (:gen-class)
  (:require
    [org.corfield.logging4j2 :as log]
    [samuraibff.system :as system]))

(defn -main
  "Start the Integrant system and block the main thread.

  This is intended for local dev and simple deployments.

  Side effects:
  - Starts Integrant system (see `samuraibff.system/start!`).
  - Registers JVM shutdown hook that stops the system.
  - Blocks forever (sleep loop) so the JVM keeps running.

  Returns: never (unless interrupted)."
  [& _args]
  (log/info "Starting samuraibff" {})
  (system/start!)
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      (fn []
        (log/info "Shutdown hook triggered" {})
        (system/stop!))))
  ;; Keep process alive.
  (loop []
    (Thread/sleep 600000)
    (recur)))

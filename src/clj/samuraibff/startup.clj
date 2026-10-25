(ns samuraibff.startup
  "Start the application without exposing resolved configuration on failure."
  (:require [org.corfield.logging4j2 :as log]
            [samuraibff.observability.diagnostics :as diagnostics]))

(defn failure-details
  "Return bounded exception classes and code locations, excluding messages/data.

  Integrant exceptions can contain the whole resolved system, including secrets.
  Neither Throwable messages, ex-data nor Throwable objects belong in this map."
  [^Throwable failure]
  (assoc (diagnostics/failure-details failure) :event :startup-failed))

(defn start!
  "Call a zero-argument startup function and return whether it succeeded.

  On failure, emit sanitized structured diagnostics and return false. The caller
  must terminate rather than continue with a partially initialized application."
  [start-system!]
  (try
    (start-system!)
    true
    (catch Throwable failure
      (log/error "Application startup failed" (failure-details failure))
      false)))

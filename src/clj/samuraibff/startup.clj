(ns samuraibff.startup
  "Start the application without exposing resolved configuration on failure."
  (:require [org.corfield.logging4j2 :as log]))

(defn failure-details
  "Return bounded exception classes and code locations, excluding messages/data.

  Integrant exceptions can contain the whole resolved system, including secrets.
  Neither Throwable messages, ex-data nor Throwable objects belong in this map."
  [^Throwable failure]
  {:event :startup-failed
   :causes (loop [error failure
                  remaining 8
                  result []]
             (if (or (nil? error) (zero? remaining))
               result
               (recur (.getCause ^Throwable error)
                      (dec remaining)
                      (conj result
                            {:exception-class (.getName (class error))
                             :locations (mapv (fn [^StackTraceElement frame]
                                                {:class (.getClassName frame)
                                                 :method (.getMethodName frame)
                                                 :line (.getLineNumber frame)})
                                              (take 12 (.getStackTrace ^Throwable error)))}))))})

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

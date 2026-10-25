(ns samuraibff.observability.diagnostics
  "Bounded diagnostic fields that do not contain request or exception payloads."
  (:require [clojure.string :as str]))

(defn failure-details
  "Return bounded exception classes and code locations for a Throwable.

  Messages, exception data, file names and Throwable objects are excluded."
  [^Throwable failure]
  {:causes (loop [error failure
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

(defn http-status
  "Return an integer HTTP status in 100..599, or nil for untrusted input."
  [status]
  (when (and (integer? status) (<= 100 status 599))
    status))

(defn session-id
  "Return a canonical UUID string for log correlation, or nil.

  Only canonical UUID input is accepted; arbitrary query values are not logged."
  [value]
  (when (and (string? value)
             (re-matches #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                         value))
    (str/lower-case value)))

;; Copyright (c) samuraibff contributors.
(ns samuraibff.observability.mdc
  "Log4j2 MDC (ThreadContext) helpers.

  Motivation
  ----------
  For Grafana Tempo ⇄ Loki correlation we must log `trace_id` (and optionally
  `span_id`) in every relevant log line. log4j2 exposes this via ThreadContext
  (aka MDC).

  This namespace provides small helpers to bind MDC keys for a dynamic scope.

  Notes
  -----
  - Keys are strings in log4j2.
  - Values are coerced to strings.
  - We intentionally remove keys on scope exit to avoid leaking context between
    unrelated requests (esp. on thread pools).
  "
  (:import
    (org.apache.logging.log4j ThreadContext)))

(defn put!
  "Put a single MDC key/value.

  Inputs:
  - k: string/keyword
  - v: any (nil means remove)

  Returns: nil" 
  [k v]
  (let [k (name k)]
    (if (nil? v)
      (ThreadContext/remove k)
      (ThreadContext/put k (str v)))))

(defn put-all!
  "Put multiple MDC entries.

  Inputs:
  - m: map of key -> value. nil values remove the key.

  Returns: nil" 
  [m]
  (doseq [[k v] (or m {})]
    (put! k v))
  nil)

(defn remove-all!
  "Remove a set of MDC keys.

  Inputs:
  - ks: seq of key names (keyword/string)

  Returns: nil" 
  [ks]
  (doseq [k ks]
    (ThreadContext/remove (name k)))
  nil)

(defmacro with-mdc
  "Execute body with given MDC entries bound.

  Inputs:
  - m: map of keys -> values. nil values remove the key.

  Returns:
  - result of body" 
  [m & body]
  (let [ks (vec (keys m))]
    `(do
       (put-all! ~m)
       (try
         ~@body
         (finally
           (remove-all! ~ks))))))

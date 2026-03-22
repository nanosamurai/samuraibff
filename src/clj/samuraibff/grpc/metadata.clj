(ns samuraibff.grpc.metadata
  "Helpers for building safe gRPC metadata maps.

  We intentionally keep this minimal:
  - rtservice owns the actual semantics and limits
  - BFF only ensures values are finite numbers and serializes them safely

  This keeps BFF a 'messenger' while still avoiding header-injection or NaN/Inf
  edge cases." 
  (:require
    [clojure.string :as str]))

(defn finite-double?
  "Return true if x is a finite numeric value.

  Inputs:
  - x: any

  Returns: boolean." 
  [x]
  (and (number? x)
       (let [d (double x)]
         (and (not (Double/isNaN d))
              (not (Double/isInfinite d))))))

(defn header-double
  "Serialize a numeric value as a gRPC metadata header value.

  Inputs:
  - x: any

  Returns:
  - string (finite number formatted using Double/toString)
  - nil if x is not a finite number." 
  [x]
  (when (finite-double? x)
    (-> (double x)
        Double/toString
        ;; Transport hygiene: ensure no CR/LF in header value (should not happen
        ;; for Double/toString, but be explicit).
        (str/replace #"[\r\n]" ""))))

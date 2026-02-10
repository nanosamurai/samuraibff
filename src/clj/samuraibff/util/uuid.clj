(ns samuraibff.util.uuid
  "UUID helpers.

  This namespace centralizes generation of UUIDs.

  We intentionally generate UUIDv7 for new sessions:
  - time-ordered (better index locality than v4)
  - stable canonical string form

  Public API:
  - `uuid7`     => java.util.UUID
  - `uuid7-str` => canonical UUID string"
  (:import
    (com.github.f4b6a3.uuid UuidCreator)
    (java.util UUID)))

(defn uuid7
  "Generate a new UUIDv7.

  Returns:
  - java.util.UUID

  Notes:
  - Uses `com.github.f4b6a3/uuid-creator`.
  - UUIDv7 is time-ordered, which is beneficial for DB indexes." 
  ^UUID
  []
  (UuidCreator/getTimeOrderedEpoch))

(defn uuid7-str
  "Generate a new UUIDv7 and return its canonical string form.

  Returns:
  - string (36-char canonical UUID)" 
  ^String
  []
  (str (uuid7)))

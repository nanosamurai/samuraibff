(ns samuraibff.ui.recording-detail
  "Pure helpers shared by the UI for building Recording detail transcript feeds.

  This namespace exists so that logic used by the CLJS UI can be unit-tested from
  the CLJ test runner.

  In particular, DB transcript records returned by `GET /api/recordings/:session_id`
  often keep metadata (like `:lang`) on the *record* level, while per-segment maps
  inside `:segments` may omit it. The UI must inherit such metadata when converting
  DB data into per-segment events/messages, otherwise downstream de-duplication
  between DB and cached WS events can fail.
  "
  (:require
   [samuraibff.ui.transcript :as transcript]))

(defn db-refined-records->events
  "Convert DB refined transcript records into refined events.

  Inputs:
  - records: vector of DB refined transcript records (as returned by the BFF API)
    Each record is expected to contain:
      - :event_created_at_ns (optional)
      - :lang (optional)
      - :speaker (optional)
      - :full_text (optional)
      - :segments: vector of segment maps with keys:
          :start_s, :end_s, :text, :speaker?, :lang?

  Output:
  - vector of refined event maps compatible with `transcript/normalize-refined`.

  Important:
  - :seq is synthesized as `(event_created_at_ns + idx)` when event_created_at_ns
    is present; otherwise idx is used.
  - :lang and :speaker are inherited from the parent record when missing on a
    segment.
  - :text is inherited from :full_text when a segment omits :text (defensive).
  "
  [records]
  (reduce
   (fn [events r]
     (let [segments (vec (or (:segments r) []))
           base (long (or (:event_created_at_ns r) 0))
           record-lang (:lang r)
           record-speaker (:speaker r)
           record-text (:full_text r)]
       (reduce
        (fn [events [idx seg]]
          (conj events {:seq (+ base (long idx))
                        :ts_ms 0
                        :start_s (:start_s seg)
                        :end_s (:end_s seg)
                        :text (or (:text seg) record-text)
                        :speaker (or (:speaker seg) record-speaker)
                        :lang (or (:lang seg) record-lang)}))
        events
        (map-indexed vector segments))))
   []
   (vec (or records []))))

(defn refined-events->messages
  "Convert refined events (with start/end/text) into transcript messages.

  Inputs:
  - events: vector of refined event maps

  Returns: vector of transcript messages." 
  [events]
  (->> (or events [])
       (mapv transcript/normalize-refined)
       transcript/sort-messages))

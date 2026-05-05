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

(def ^:private transcript-tab-display-order
  "The fixed transcript tab display order used by Recording detail UI.

  NOTE:
  - This is intentionally *not* the same as the default-selection preference.
  - Default selection prefers :final first (most detail)."
  [:realtime :refined :final])

(def ^:private transcript-tab-default-order
  "Transcript tab default-selection preference.

  Preference:
  - :final (most detail)
  - :refined
  - :realtime"
  [:final :refined :realtime])

(defn available-transcript-tabs
  "Return a vector of transcript tab ids that should be visible.

  This is used by Recording detail page to hide transcript tabs/panels that
  contain no transcript messages (e.g. realtime transcripts are currently not
  stored in DB, so on a later visit they are typically empty).

  Inputs:
  - {:keys [realtime-msgs refined-msgs final-msgs]}
    - each is expected to be a vector/seq of transcript message maps

  Returns:
  - vector of keywords from #{:realtime :refined :final} in UI display order."
  [{:keys [realtime-msgs refined-msgs final-msgs]}]
  (let [present? {:realtime (boolean (seq (or realtime-msgs [])))
                  :refined (boolean (seq (or refined-msgs [])))
                  :final (boolean (seq (or final-msgs [])))}]
    (->> transcript-tab-display-order
         (filterv (fn [tab-id] (true? (get present? tab-id)))))))

(defn default-transcript-tab
  "Return the default transcript tab id to use given currently available tabs.

  Preference: :final → :refined → :realtime.

  Inputs:
  - available-tabs: vector of tab ids (typically from `available-transcript-tabs`)

  Returns:
  - keyword tab id or nil when no tabs are available."
  [available-tabs]
  (let [available? (set (or available-tabs []))]
    (some (fn [tab-id]
            (when (contains? available? tab-id) tab-id))
          transcript-tab-default-order)))

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

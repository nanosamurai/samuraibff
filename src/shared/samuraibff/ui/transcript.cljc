(ns samuraibff.ui.transcript
  "Pure transcript merging + normalization logic.

  The UI receives two kinds of transcript-bearing WS events:
  - realtime `asr` events (partial/final)
  - near-realtime `refined` events (WhisperX)

  This namespace provides pure helpers so we can unit test transcript behavior
  from Clojure (clj) test runner while reusing the same logic in ClojureScript.

  ## Concepts

  ### Transcript message

  A transcript message is a normalized map used by the UI rendering layer.

  Shape:
  - :kind    string (asr | refined)
  - :seq     int (monotonic per session; assigned by BFF)
  - :ts_ms   int (epoch ms when BFF emitted the event)
  - :start_s double
  - :end_s   double
  - :text    string
  - :speaker string? (optional)
  - :lang    string? (optional)
  - :final   boolean? (only for :kind asr)

  ### Refined replacement semantics

  Replacement heuristic (matches the Python BFF UI behavior):
  - when a refined segment arrives, remove **ASR** messages whose time window
    is fully contained within the refined time window (inclusive bounds)
  - keep other refined messages

  See `apply-refined` for details.

  All functions in this namespace are pure.
  "
  (:require
    [clojure.string :as str]))

(defn normalize-asr
  "Normalize an incoming WS `asr` event map into a transcript message.

  Inputs:
  - ev: map (decoded JSON from WS) with keys:
      :seq, :ts_ms, :start_s, :end_s, :text, :speaker?, :lang?, :final

  Returns:
  - transcript message map (see namespace docstring)."
  [ev]
  {:kind "asr"
   :seq (long (or (:seq ev) 0))
   :ts_ms (long (or (:ts_ms ev) 0))
   :start_s (double (or (:start_s ev) 0))
   :end_s (double (or (:end_s ev) 0))
   :text (str (or (:text ev) ""))
   :speaker (some-> (:speaker ev) str)
   :lang (some-> (:lang ev) str)
   :final (boolean (:final ev))})

(defn normalize-refined
  "Normalize an incoming WS `refined` event map into a transcript message.

  Inputs:
  - ev: map (decoded JSON from WS) with keys:
      :seq, :ts_ms, :start_s, :end_s, :text, :speaker?, :lang?

  Returns:
  - transcript message map (see namespace docstring)."
  [ev]
  {:kind "refined"
   :seq (long (or (:seq ev) 0))
   :ts_ms (long (or (:ts_ms ev) 0))
   :start_s (double (or (:start_s ev) 0))
   :end_s (double (or (:end_s ev) 0))
   :text (str (or (:text ev) ""))
   :speaker (some-> (:speaker ev) str)
   :lang (some-> (:lang ev) str)})

(defn message-mid-s
  "Return the midpoint timestamp of a transcript message in seconds.

  Inputs:
  - msg: transcript message map

  Returns: double."
  [msg]
  (/ (+ (double (or (:start_s msg) 0))
        (double (or (:end_s msg) 0)))
     2.0))

(defn- sort-key
  [msg]
  ;; Keep stable ordering within the same start time.
  [(double (or (:start_s msg) 0)) (long (or (:seq msg) 0))])

(defn sort-messages
  "Sort transcript messages by time.

  Sorting:
  - primary: :start_s
  - tie-breaker: :seq

  Input: vector/seq of transcript message maps.
  Output: vector."
  [msgs]
  (->> msgs
       (sort-by sort-key)
       vec))

(defn- absd
  "Absolute value helper usable from both CLJ and CLJS.

  Input: number
  Returns: double." 
  [x]
  #?(:cljs (js/Math.abs (double x))
     :clj (Math/abs (double x))))

(defn upsert-asr
  "Insert or update a realtime ASR message in an existing transcript.

  Semantics (Plan C / cumulative refinement):
  - maintain at most one in-flight ASR message *per window*
  - incoming PARTIAL replaces the partial for its window (if present)
  - incoming FINAL commits its window by replacing the in-flight partial
  - PARTIAL arriving after a FINAL for the same window is ignored
  - if no matching in-flight window message exists, append

  Rationale:
  - rtservice now emits PARTIAL hypotheses for the same time window, followed
    by a FINAL event for that window. The UI must treat PARTIALs as replaceable
    and FINALs as locking.

  Inputs:
  - msgs: vector of transcript messages
  - asr-ev: WS event map

  Returns:
  - updated vector of transcript messages." 
  [msgs asr-ev]
  (let [msg (normalize-asr asr-ev)
        msgs (vec (or msgs []))
        ;; Without a stable segment_id in the proto, we derive a best-effort
        ;; "window key" from timing.
        ;;
        ;; Rationale:
        ;; - rtservice can emit PARTIALs for the *next* window while the
        ;;   previous window is still being finalized.
        ;; - if we keep only one global partial, we can overwrite the wrong
        ;;   window and leave a dangling partial bubble.
        ;;
        ;; We match by start_s within a small epsilon, which is robust to minor
        ;; timing jitter but will not confuse adjacent windows (typically spaced
        ;; by window_sec-overlap_sec, e.g. 4.5s).
        window-eps-s 0.75
        idx (->> (map-indexed vector msgs)
                 (keep (fn [[i m]]
                         (when (and (= "asr" (:kind m))
                                    (<= (absd (- (double (:start_s m)) (double (:start_s msg))))
                                        window-eps-s))
                           {:idx i
                            :delta (absd (- (double (:start_s m)) (double (:start_s msg))))
                            :final? (boolean (:final m))})))
                 (sort-by (juxt :delta :idx))
                 first
                 :idx)
        msgs'
        (if (some? idx)
          (let [existing (nth msgs idx)]
            (if (and (true? (:final existing)) (false? (:final msg)))
              ;; Ignore late PARTIAL updates after a window was already committed.
              msgs
              (assoc msgs idx msg)))
          (conj msgs msg))]
    (sort-messages msgs')))

(defn- contained-within?
  "Return true if [a0,a1] is fully contained within [b0,b1] (inclusive bounds).

  Inputs:
  - a0,a1,b0,b1: doubles (seconds)

  Returns: boolean." 
  [a0 a1 b0 b1]
  (and (<= (double b0) (double a0))
       (<= (double a1) (double b1))))

(defn apply-refined
  "Apply a refined transcript event to an existing transcript.

  Replacement semantics:
  - remove only ASR messages whose time window is fully contained within the
    refined window (inclusive)
  - insert refined message
  - sort by :start_s then :seq

  Note on the (optional) safety margin:
  - we keep the knob so we can re-introduce head-safety later if needed
  - for now it is set to 0.0s

  Inputs:
  - msgs: vector of transcript messages
  - refined-ev: WS event map

  Returns:
  - updated vector of transcript messages." 
  [msgs refined-ev]
  (let [ref (normalize-refined refined-ev)
        start (double (:start_s ref))
        end (double (:end_s ref))
        safety-s 0.0
        ;; end is reduced by safety (if any)
        end' (- end safety-s)
        msgs (vec (or msgs []))
        msgs' (->> msgs
                   ;; drop duplicates if we re-receive the same refined seq
                   (remove #(and (= "refined" (:kind %))
                                 (= (:seq %) (:seq ref))))
                   (remove (fn [m]
                             (and (= "asr" (:kind m))
                                  (contained-within? (:start_s m) (:end_s m) start end'))))
                   (concat [ref])
                   sort-messages)]
    msgs'))

(defn- parse-int
  "Parse a base-10 integer from a string.

  This is defined in this namespace (instead of using platform-specific APIs)
  so we can share the speaker mapping logic between CLJ tests and CLJS UI.

  Inputs:
  - s: string

  Returns: long." 
  [s]
  #?(:cljs (js/parseInt s 10)
     :clj (Long/parseLong s)))

(defn speaker->display-name
  "Convert a diarization speaker label into a nicer display name.

  Current heuristic:
  - nil/blank => Unknown
  - SPEAKER_00 => Speaker 1 (index+1)
  - otherwise => original label

  Returns: string." 
  [speaker]
  (let [s (some-> speaker str)]
    (cond
      (or (nil? s) (str/blank? s)) "Unknown"
      :else
      (if-let [[_ digits] (re-matches #"(?i)SPEAKER_(\\d+)" s)]
        (str "Speaker " (inc (parse-int digits)))
        s))))

(defn speaker->avatar-text
  "Return a compact avatar label for a speaker.

  Current heuristic:
  - SPEAKER_00 => S1
  - otherwise => first 2 characters uppercased

  Returns: string." 
  [speaker]
  (let [s (some-> speaker str)]
    (cond
      (or (nil? s) (str/blank? s)) "?"
      :else
      (if-let [[_ digits] (re-matches #"(?i)SPEAKER_(\\d+)" s)]
        (str "S" (inc (parse-int digits)))
        (-> s (subs 0 (min 2 (count s))) str/upper-case)))))

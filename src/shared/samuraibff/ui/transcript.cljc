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

(defn- overlap-score
  "Compute an overlap score between two [start,end] ranges.

  Score definition:
  - let overlap = intersection length in seconds
  - let denom = min(duration-a, duration-b)
  - score = overlap/denom in [0,1]

  Inputs:
  - a0,a1,b0,b1: doubles (seconds)

  Returns: double in [0,1]." 
  [a0 a1 b0 b1]
  (let [a0 (double a0)
        a1 (double a1)
        b0 (double b0)
        b1 (double b1)
        start (max a0 b0)
        end (min a1 b1)
        overlap (max 0.0 (- end start))
        da (max 0.0 (- a1 a0))
        db (max 0.0 (- b1 b0))
        denom (max 0.000001 (min da db))]
    (/ overlap denom)))

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
        ;; Pair PARTIAL<->FINAL and updates by window overlap.
        ;;
        ;; Why not start_s only?
        ;; - diarization/speaker assignment may shift start_s slightly between
        ;;   PARTIAL and FINAL (e.g. 0.0 -> 1.09), which would miss an epsilon
        ;;   match and leave an orphan partial "typing" bubble.
        ;;
        ;; Overlap-based pairing still won't confuse adjacent windows because
        ;; their overlap ratio is small (typically overlap_sec/window_sec).
        min-overlap-score 0.6
        ;; IMPORTANT:
        ;; - When a PARTIAL arrives we must *not* match it to an already-FINAL
        ;;   message (even if it overlaps), otherwise we can end up ignoring it
        ;;   due to the "late PARTIAL after FINAL" guard.
        ;; - That would make the UI appear to "lose" the typing bubble.
        ;;
        ;; So:
        ;; - PARTIAL can only match existing non-final ASR messages.
        ;; - FINAL can match any ASR message (partial preferred by overlap).
        candidate?
        (fn [m]
          (and (= "asr" (:kind m))
               (or (true? (:final msg))
                   (false? (:final m)))))
        ;; If a PARTIAL arrives *after* a FINAL for the same window, ignore it.
        ;;
        ;; IMPORTANT: This is distinct from the candidate matching logic below.
        ;; We avoid matching PARTIALs against FINALs for replacement, but we
        ;; still want to suppress truly late PARTIAL updates.
        late-partial?
        (when (false? (:final msg))
          (let [{:keys [m score]}
                (->> msgs
                     (filter #(and (= "asr" (:kind %)) (true? (:final %))))
                     (map (fn [m]
                            {:m m
                             :score (overlap-score (:start_s m) (:end_s m)
                                                   (:start_s msg) (:end_s msg))
                             :delta (absd (- (double (:start_s m)) (double (:start_s msg))))}))
                     ;; Prefer higher overlap, then closer start.
                     (sort-by (juxt (comp - :score) :delta))
                     first)
                msg-ord (long (or (:ts_ms msg) (:seq msg) 0))
                fin-ord (long (or (:ts_ms m) (:seq m) 0))]
            (and m
                 ;; Only consider it "the same window" if the overlap is very high.
                 (>= (double (or score 0.0)) 0.9)
                 ;; And only ignore if it is actually later.
                 (>= msg-ord fin-ord))))

        idx (->> (map-indexed vector msgs)
                 (keep (fn [[i m]]
                         (when (candidate? m)
                           (let [score (overlap-score (:start_s m) (:end_s m)
                                                      (:start_s msg) (:end_s msg))]
                             (when (>= score min-overlap-score)
                               {:idx i
                                :score score
                                :delta (absd (- (double (:start_s m)) (double (:start_s msg))))
                                :final? (boolean (:final m))})))))
                 ;; Prefer higher overlap, then closer start_s, then earlier index.
                 (sort-by (juxt (comp - :score) :delta :idx))
                 first
                 :idx)
        msgs'
        (cond
          (true? late-partial?)
          msgs

          (some? idx)
          (assoc msgs idx msg)

          :else
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

;; --- Cosmetic rendering helpers ---

(defn coalesce-asr-finals
  "Coalesce consecutive realtime ASR FINAL messages into larger bubbles.

  This is a UI-only cosmetic helper to reduce visual fragmentation when rtservice
  emits short FINAL chunks.

  Merge rule:
  - only messages with {:kind \"asr\" :final true}
  - same :speaker (string-equal, treating nil/blank as empty string)
  - gap between prev.end_s and next.start_s <= max-gap-s
  - does NOT merge across refined messages (they break the run)

  When merged:
  - :start_s from the first message
  - :end_s from the last message
  - :text concatenated with a single space
  - :ts_ms taken from the last message (freshest)
  - :seq taken from the first message (stable)

  Inputs:
  - msgs: vector/seq of transcript messages
  - opts: optional map {:max-gap-s double} (default 0.3)

  Returns: vector of transcript messages." 
  ([msgs]
   (coalesce-asr-finals msgs {:max-gap-s 0.3}))
  ([msgs {:keys [max-gap-s] :or {max-gap-s 0.3}}]
   (let [max-gap-s (double (or max-gap-s 0.3))
         msgs (sort-messages (vec (or msgs [])))
         speaker-key (fn [m]
                       (let [s (some-> (:speaker m) str)]
                         (if (str/blank? s) "" s)))
         join-text (fn [a b]
                     (let [a (str (or a ""))
                           b (str (or b ""))
                           a (str/trimr a)
                           b (str/triml b)]
                       (cond
                         (str/blank? a) b
                         (str/blank? b) a
                         :else (str a " " b))))
         mergeable?
         (fn [prev next]
           (and (= "asr" (:kind prev))
                (= "asr" (:kind next))
                (true? (:final prev))
                (true? (:final next))
                (= (speaker-key prev) (speaker-key next))
                (<= (max 0.0 (- (double (:start_s next)) (double (:end_s prev)))) max-gap-s)))
         merge2
         (fn [prev next]
           (-> prev
               (assoc :end_s (:end_s next)
                      :ts_ms (:ts_ms next)
                      :text (join-text (:text prev) (:text next))
                      :speaker (:speaker next)
                      :lang (or (:lang next) (:lang prev)))))]
     (reduce
       (fn [out m]
         (let [prev (peek out)]
           (cond
             ;; Never merge across refined boundaries.
             (= "refined" (:kind m))
             (conj out m)

             (and prev (mergeable? prev m))
             (conj (pop out) (merge2 prev m))

             :else
             (conj out m))))
       []
       msgs))))

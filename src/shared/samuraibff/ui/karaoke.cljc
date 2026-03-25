(ns samuraibff.ui.karaoke
  "Pure helpers for word-level timing karaoke highlighting.

  This namespace is CLJC so we can unit-test the same logic on the JVM.

  ## Word model

  A *word* is a map:

  - :start_s double  (absolute time in recording)
  - :end_s   double  (absolute time in recording)
  - :text    string

  Times are expected to be monotonic by :start_s.

  All functions are pure." 
  (:require
    [clojure.string :as str]))

(def ^:private epsilon
  "Small epsilon used for inclusive end comparisons."
  1.0e-9)

(defn normalize-word
  "Normalize a word map.

  Inputs:
  - w: map with keys :start_s :end_s :text (may be missing/nil)

  Returns:
  - {:start_s double :end_s double :text string}"
  [w]
  {:start_s (double (or (:start_s w) 0.0))
   :end_s (double (or (:end_s w) 0.0))
   :text (str (or (:text w) ""))})

(defn normalize-words
  "Normalize a sequence of words.

  Inputs:
  - words: seq of word maps

  Returns:
  - vector of normalized word maps." 
  [words]
  (->> (or words [])
       (map normalize-word)
       vec))

(defn active-word-idx
  "Return the index of the currently active word at time `t-s`.

  Definition:
  - a word is active when start_s <= t-s <= end_s (inclusive, with epsilon)

  Inputs:
  - words: vector/seq of word maps (see namespace docstring)
  - t-s: number (seconds)

  Returns:
  - int index into the normalized words vector, or nil when no word is active." 
  [words t-s]
  (let [ws (normalize-words words)
        ;; Delegate to the normalized implementation so callers that already
        ;; normalized can skip the repeated work.
        idx (active-word-idx-normalized ws t-s)]
    idx))

(defn active-word-idx-normalized
  "Return the index of the currently active word at time `t-s`.

  This is identical to `active-word-idx` but expects `words` to already be
  normalized (i.e. doubles for :start_s/:end_s and string :text).

  Inputs:
  - words: vector of word maps with keys :start_s :end_s :text
  - t-s: number (seconds)

  Returns:
  - int index into `words`, or nil when no word is active." 
  [words t-s]
  (let [ws (vec (or words []))
        n (count ws)
        t (double (or t-s 0.0))]
    (when (pos? n)
      ;; Binary search for the last word with start_s <= t.
      (loop [lo 0
             hi (dec n)
             best -1]
        (if (> lo hi)
          (let [idx best]
            (when (<= 0 idx)
              (let [{:keys [start_s end_s]} (nth ws idx)
                    ;; inclusive end with epsilon so we don't "blink" at exact boundaries
                    active? (and (<= start_s t)
                                (<= t (+ end_s epsilon))
                                (>= end_s start_s))]
                (when active? idx))))
          (let [mid (quot (+ lo hi) 2)
                {:keys [start_s]} (nth ws mid)]
            (if (<= start_s t)
              (recur (inc mid) hi mid)
              (recur lo (dec mid) best))))))))

(defn build-word-index
  "Build a flattened word index for a transcript.

  This is intended for UI karaoke highlighting where we want to:
  1) precompute a single word timeline,
  2) binary-search it by audio.currentTime,
  3) map the active word back to its originating message + word position.

  Inputs:
  - messages: vector/seq of transcript message maps. Each message may include:
      :words (seq of word maps with :start_s/:end_s/:text)

  Returns:
  - vector of maps (possibly empty), sorted by :start_s ascending:

      {:msg-idx  int
       :word-idx int
       :start_s  double
       :end_s    double
       :text     string}

  Notes:
  - Words are normalized using `normalize-word`.
  - Empty/blank word text is kept (best-effort) so indexing stays stable.
  - Output is sorted by :start_s so that binary search is valid even if input
    segments are slightly out of order." 
  [messages]
  (->> (vec (or messages []))
       (map-indexed
        (fn [msg-idx msg]
          (let [ws (normalize-words (:words msg))]
            (map-indexed
             (fn [word-idx w]
               (let [{:keys [start_s end_s text]} w]
                 {:msg-idx msg-idx
                  :word-idx word-idx
                  :start_s (double (or start_s 0.0))
                  :end_s (double (or end_s 0.0))
                  :text (str (or text ""))}))
             ws))))
       (apply concat)
       (sort-by :start_s)
       vec))

(defn word-text
  "Return display text for a word (best-effort).

  Inputs:
  - w: word map

  Returns: string (trimmed)." 
  [w]
  (-> (str (or (:text w) ""))
      str/trim))

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

(defn word-text
  "Return display text for a word (best-effort).

  Inputs:
  - w: word map

  Returns: string (trimmed)." 
  [w]
  (-> (str (or (:text w) ""))
      str/trim))

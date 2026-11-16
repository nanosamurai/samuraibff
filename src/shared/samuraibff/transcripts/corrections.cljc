(ns samuraibff.transcripts.corrections
  "Pure functions that combine a machine final transcript with its human
  corrections. The BFF uses them for export, workflows and request checks;
  the browser UI uses them for the corrected, changes and original views.

  A correction is a map with these keys (IDs may be UUIDs or strings):
  - :id, :seq (1, 2, 3 … per transcript)
  - :kind \"segment_text\" | \"segment_speaker\" | \"speaker_label\" | \"undo\"
  - :segment_index for segment_* kinds (index into the machine segments)
  - :speaker_label for speaker_label (a machine speaker label)
  - :new_value {:text string} or {:speaker string :speaker_id string?}
  - :undoes_id for undo (the ID of the cancelled edit)

  Corrections are only appended. The corrected transcript is the machine
  segments plus every edit that is not undone:
  - text: the latest active segment_text edit of the segment;
  - speaker: the latest active segment_speaker edit of the segment, else the
    latest active speaker_label edit for its machine label, else the machine
    label."
  (:require
   [clojure.string :as str]))

(def kinds
  "Correction kinds stored in `transcript_corrections.kind`."
  #{"segment_text" "segment_speaker" "speaker_label" "undo"})

(def ^:private max-lcs-cells
  "Largest word table compared with a full LCS. Larger texts (for example a
  text-only transcript with thousands of words) fall back to a common
  prefix and suffix, which keeps the browser responsive."
  250000)

(defn- id-key
  "Return an ID as a string so UUIDs and JSON strings compare equal."
  [id]
  (some-> id str))

(defn undone-by
  "Return {edit-id-string undo-correction} for every edit cancelled by an undo."
  [corrections]
  (into {}
        (keep #(when (= "undo" (:kind %)) [(id-key (:undoes_id %)) %]))
        corrections))

(defn active-corrections
  "Return the edits that are neither undo rows nor undone, ordered by :seq."
  [corrections]
  (let [undone (undone-by corrections)]
    (->> corrections
         (remove #(or (= "undo" (:kind %)) (contains? undone (id-key (:id %)))))
         (sort-by :seq)
         vec)))

(defn head-seq
  "Return the highest :seq of `corrections`, or 0 when there are none."
  [corrections]
  (reduce max 0 (keep :seq corrections)))

(defn tokens
  "Split text into words on whitespace. Returns a vector of non-blank strings."
  [text]
  (vec (remove str/blank? (str/split (str/trim (str text)) #"\s+"))))

(defn- next-lcs-row
  "Return the LCS table row for element `x` of the first sequence, given the
  row below it (`below`, length (inc (count b)))."
  [x b below]
  (loop [j (dec (count b))
         row (transient (vec (repeat (inc (count b)) 0)))]
    (if (neg? j)
      (persistent! row)
      (recur (dec j)
             (assoc! row j (if (= x (nth b j))
                             (inc (nth below (inc j)))
                             (max (nth below j) (nth row (inc j)))))))))

(defn- lcs-pairs
  "Return the [i j] index pairs of a longest common subsequence of vectors a and b."
  [a b]
  (let [n (count a)
        m (count b)
        table (loop [i (dec n)
                     rows (list (vec (repeat (inc m) 0)))]
                (if (neg? i)
                  (vec rows)
                  (recur (dec i) (conj rows (next-lcs-row (nth a i) b (first rows))))))]
    (loop [i 0 j 0 out []]
      (cond
        (or (= i n) (= j m)) out
        (= (nth a i) (nth b j)) (recur (inc i) (inc j) (conj out [i j]))
        (>= (nth (nth table (inc i)) j) (nth (nth table i) (inc j))) (recur (inc i) j out)
        :else (recur i (inc j) out)))))

(defn- affix-pairs
  "Return the [i j] index pairs of the common prefix and suffix of a and b."
  [a b]
  (let [n (count a)
        m (count b)
        prefix (count (take-while true? (map = a b)))
        limit (min (- n prefix) (- m prefix))
        suffix (count (take-while true? (map = (take limit (rseq a)) (take limit (rseq b)))))]
    (into (mapv (fn [k] [k k]) (range prefix))
          (map (fn [k] [(- n suffix (- k)) (- m suffix (- k))]))
          (range suffix))))

(defn match-pairs
  "Return the [i j] index pairs of words that stay the same between word
  vectors a and b, in order. Uses a full LCS for normal segment sizes."
  [a b]
  (if (> (* (count a) (count b)) max-lcs-cells)
    (affix-pairs a b)
    (lcs-pairs a b)))

(defn- runs
  "Merge consecutive [op word] pairs with the same op into [op \"w1 w2\"] runs."
  [ops]
  (reduce (fn [out [op word]]
            (let [[last-op last-text] (peek out)]
              (if (= op last-op)
                (conj (pop out) [op (str last-text " " word)])
                (conj out [op word]))))
          []
          ops))

(defn word-diff
  "Compare two texts word by word.

  Inputs: old text and new text (strings or nil).
  Returns a vector of [op text] runs in reading order, where op is :same,
  :del (only in the old text) or :ins (only in the new text). Removed words
  come before inserted words at each change."
  [old-text new-text]
  (let [a (tokens old-text)
        b (tokens new-text)
        changes (fn [out i0 i1 j0 j1]
                  (-> out
                      (into (map (fn [i] [:del (nth a i)])) (range i0 i1))
                      (into (map (fn [j] [:ins (nth b j)])) (range j0 j1))))]
    (loop [i 0 j 0 pairs (match-pairs a b) out []]
      (if-let [[pi pj] (first pairs)]
        (recur (inc pi) (inc pj) (rest pairs)
               (conj (changes out i pi j pj) [:same (nth a pi)]))
        (runs (changes out i (count a) j (count b)))))))

(defn- round-ms
  "Round seconds to whole milliseconds."
  [s]
  (/ (Math/round (* (double s) 1000.0)) 1000.0))

(defn- spread-timings
  "Return n word timings that share [lo, hi] evenly, for the given texts.
  Times are rounded to milliseconds."
  [texts lo hi]
  (let [n (count texts)
        step (/ (- hi lo) n)]
    (mapv (fn [k text] {:start_s (round-ms (+ lo (* k step)))
                        :end_s (if (= k (dec n)) hi (round-ms (+ lo (* (inc k) step))))
                        :text text})
          (range n) texts)))

(defn retime-words
  "Give each word of corrected text a timing.

  Inputs:
  - words: machine words [{:start_s :end_s :text}] of the segment
  - text: corrected segment text
  - seg-start, seg-end: segment bounds in seconds (or nil)

  Words that match a machine word keep its timing. Other words share the
  time between their matched neighbours (or the segment bounds) evenly.
  Returns a vector of {:start_s :end_s :text}, or nil when the segment has no
  machine word timing or the corrected text is empty."
  [words text seg-start seg-end]
  (let [words (filterv #(and (number? (:start_s %)) (number? (:end_s %))) words)
        b (tokens text)
        start (if (number? seg-start) seg-start (:start_s (first words)))
        end (if (number? seg-end) seg-end (:end_s (peek words)))]
    (when (and (seq words) (seq b) (number? start) (number? end))
      (let [matched (into {} (map (fn [[i j]] [j (nth words i)]))
                          (match-pairs (mapv #(str/trim (str (:text %))) words) b))
            total (count b)]
        (loop [j 0 prev-end start out []]
          (if (= j total)
            out
            (if-let [w (get matched j)]
              (recur (inc j) (:end_s w) (conj out {:start_s (:start_s w) :end_s (:end_s w) :text (nth b j)}))
              (let [run-end (or (first (filter #(contains? matched %) (range j total))) total)
                    gap-end (if (< run-end total) (:start_s (get matched run-end)) end)
                    lo (double prev-end)
                    hi (max lo (double gap-end))]
                (recur run-end hi (into out (spread-timings (subvec b j run-end) lo hi)))))))))))

(defn- latest-by
  "Index edits (ordered by :seq) by (f edit); later edits win."
  [f edits]
  (reduce (fn [m e] (assoc m (f e) e)) {} edits))

(defn- edits-of-kind
  [active kind]
  (filter #(= kind (:kind %)) active))

(defn- effective-edits
  "Return {:text {idx edit} :segment-speaker {idx edit} :label-speaker {label edit}}."
  [corrections]
  (let [active (active-corrections corrections)]
    {:text (latest-by :segment_index (edits-of-kind active "segment_text"))
     :segment-speaker (latest-by :segment_index (edits-of-kind active "segment_speaker"))
     :label-speaker (latest-by :speaker_label (edits-of-kind active "speaker_label"))}))

(defn- speaker-edit
  "Return the edit that sets the speaker of segment idx, or nil."
  [edits idx segment]
  (or (get-in edits [:segment-speaker idx])
      (when-let [label (not-empty (:speaker segment))]
        (get-in edits [:label-speaker label]))))

(defn- apply-text
  [segment edit]
  (let [text (str (get-in edit [:new_value :text]))
        words (retime-words (:words segment) text (:start_s segment) (:end_s segment))]
    (cond-> (assoc segment :text text)
      words (assoc :words words)
      (nil? words) (dissoc :words))))

(defn- apply-speaker
  [segment edit]
  (let [{:keys [speaker speaker_id]} (:new_value edit)]
    (cond-> (assoc segment :speaker (str speaker))
      (some? speaker_id) (assoc :speaker_id (str speaker_id))
      (nil? speaker_id) (dissoc :speaker_id))))

(defn corrected-segments
  "Apply corrections to machine segments.

  Inputs: machine segments (vector of maps with :text, optional :speaker,
  :start_s, :end_s, :words) and the corrections of that transcript.
  Returns a vector with the same count and order. Corrected text gets
  estimated word timing (see `retime-words`); a corrected speaker sets
  :speaker and, for an enrolled speaker, :speaker_id. Segments whose text was
  cleared stay in place with an empty :text."
  [segments corrections]
  (let [edits (effective-edits corrections)]
    (vec (map-indexed
          (fn [idx segment]
            (let [text-edit (get-in edits [:text idx])
                  speaker (speaker-edit edits idx segment)]
              (cond-> segment
                text-edit (apply-text text-edit)
                speaker (apply-speaker speaker))))
          segments))))

(defn segment-target
  "Return what a non-undo correction changes, as a comparable key:
  [:text idx], [:segment-speaker idx] or [:speaker-label label]."
  [correction]
  (case (:kind correction)
    "segment_text" [:text (:segment_index correction)]
    "segment_speaker" [:segment-speaker (:segment_index correction)]
    "speaker_label" [:speaker-label (:speaker_label correction)]
    nil))

(defn touches-segment?
  "True when a non-undo correction changes segment idx with machine label `label`."
  [correction idx label]
  (case (:kind correction)
    ("segment_text" "segment_speaker") (= idx (:segment_index correction))
    "speaker_label" (and (some? label) (= label (:speaker_label correction)))
    false))

(defn segment-changes
  "Describe the corrections of every segment for display.

  Inputs: machine segments and the corrections of that transcript.
  Returns a vector, one map per segment:
  {:index int
   :original machine segment
   :corrected corrected segment
   :text-edit active text edit or nil
   :speaker-edit active speaker edit (segment or label) or nil
   :history [non-undo corrections touching the segment, newest first, each
             with :undone true and :undone_by <undo correction> when undone]}"
  [segments corrections]
  (let [edits (effective-edits corrections)
        undone (undone-by corrections)
        corrected (corrected-segments segments corrections)
        ordered (sort-by (comp - :seq) (remove #(= "undo" (:kind %)) corrections))]
    (vec (map-indexed
          (fn [idx segment]
            {:index idx
             :original segment
             :corrected (nth corrected idx)
             :text-edit (get-in edits [:text idx])
             :speaker-edit (speaker-edit edits idx segment)
             :history (into []
                            (comp (filter #(touches-segment? % idx (not-empty (:speaker segment))))
                                  (map #(if-let [undo (get undone (id-key (:id %)))]
                                          (assoc % :undone true :undone_by undo)
                                          %)))
                            ordered)})
          segments))))

(defn machine-labels
  "Return the distinct non-blank machine speaker labels of segments, in order of appearance."
  [segments]
  (into [] (comp (keep #(not-empty (str/trim (str (:speaker %))))) (distinct)) segments))

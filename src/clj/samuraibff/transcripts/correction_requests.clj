(ns samuraibff.transcripts.correction-requests
  "Check a correction request against a final transcript and build the row to
  append to `transcript_corrections`.

  The functions do no I/O. `samuraibff.db.transcript-corrections/append!`
  loads the transcript, its corrections and the named speakers inside one
  transaction and calls `plan`.

  Optimistic concurrency: the client sends `base_seq`, the highest correction
  seq it has seen. The request is rejected with :correction-conflict only
  when a newer correction changes the same target (the same segment text,
  segment speaker or speaker label). Other newer corrections are fine."
  (:require
   [clojure.string :as str]
   [samuraibff.transcripts.corrections :as corrections]))

(def max-corrections-per-transcript
  "Upper bound of stored corrections for one transcript."
  5000)

(def max-text-length
  "Longest accepted corrected segment text, in characters."
  10000)

(def max-speaker-length
  "Longest accepted speaker name, in characters."
  120)

(defn- by-id
  [corrections]
  (into {} (map (juxt (comp str :id) identity)) corrections))

(defn- target-of
  "Return the target of a correction; an undo targets what its edit changed."
  [ids correction]
  (corrections/segment-target
   (if (= "undo" (:kind correction))
     (get ids (str (:undoes_id correction)))
     correction)))

(defn conflict?
  "True when a correction newer than base-seq changes `target`.

  Inputs: stored corrections, base seq (int), target key from
  `corrections/segment-target`, and optionally the ID of an edit to ignore
  (the edit an undo cancels)."
  ([corrections base-seq target]
   (conflict? corrections base-seq target nil))
  ([corrections base-seq target ignore-id]
   (let [ids (by-id corrections)
         ignored? (fn [c]
                    (and (some? ignore-id)
                         (or (= (str (:id c)) (str ignore-id))
                             (= (str (:undoes_id c)) (str ignore-id)))))]
     (boolean
      (some (fn [c]
              (and (> (:seq c) base-seq)
                   (not (ignored? c))
                   (= target (target-of ids c))))
            corrections)))))

(defn- segment-at
  [segments idx]
  (when (and (int? idx) (< -1 idx (count segments)))
    (nth segments idx)))

(defn- speaker-target
  "Return {:speaker name :speaker_id string?} or an error keyword."
  [request speaker]
  (if (some? (:speaker_id request))
    (if speaker
      {:speaker (:label speaker) :speaker_id (str (:id speaker))}
      :unknown-speaker)
    (let [name (str/trim (str (:speaker request)))]
      (if (or (str/blank? name) (> (count name) max-speaker-length))
        :invalid-speaker
        {:speaker name}))))

(defn- same-speaker?
  [current target]
  (and (= (:speaker current) (:speaker target))
       (= (some-> (:speaker_id current) str) (:speaker_id target))))

(defn- current-speaker
  "Return {:speaker :speaker_id?} of a corrected segment."
  [segment]
  (cond-> {:speaker (str (:speaker segment))}
    (:speaker_id segment) (assoc :speaker_id (str (:speaker_id segment)))))

(defn- machine-speaker
  "Return what the machine said about a label: {:machine_speaker label
  :machine_speaker_id id?}, using the enrolled speaker ID when exactly one
  enrolled speaker has the label."
  [label enrolled-ids]
  (cond-> {:machine_speaker (str label)}
    (get enrolled-ids label) (assoc :machine_speaker_id (str (get enrolled-ids label)))))

(defn- audio-span
  [segment]
  (cond-> {}
    (number? (:start_s segment)) (assoc :audio_start_s (double (:start_s segment)))
    (number? (:end_s segment)) (assoc :audio_end_s (double (:end_s segment)))))

(defn- plan-text
  [{:keys [segments corrected corrections request]}]
  (let [idx (:segment_index request)
        segment (segment-at corrected idx)
        text (str/trim (str (:text request)))]
    (cond
      (nil? segment) {:error :invalid-segment}
      (not (string? (:text request))) {:error :invalid-text}
      (> (count text) max-text-length) {:error :invalid-text}
      (= text (str (:text segment))) {:error :correction-unchanged}
      (conflict? corrections (:base_seq request) [:text idx]) {:error :correction-conflict}
      :else {:row (merge {:kind "segment_text"
                          :segment_index idx
                          :old_value {:text (str (:text segment))}
                          :new_value {:text text}}
                         (audio-span (nth segments idx)))})))

(defn- plan-segment-speaker
  [{:keys [segments corrected corrections request speaker enrolled-ids]}]
  (let [idx (:segment_index request)
        segment (segment-at corrected idx)
        target (speaker-target request speaker)]
    (cond
      (nil? segment) {:error :invalid-segment}
      (keyword? target) {:error target}
      (same-speaker? (current-speaker segment) target) {:error :correction-unchanged}
      (conflict? corrections (:base_seq request) [:segment-speaker idx]) {:error :correction-conflict}
      :else (let [machine (nth segments idx)]
              {:row (merge {:kind "segment_speaker"
                            :segment_index idx
                            :old_value (merge (current-speaker segment)
                                              (machine-speaker (:speaker machine) enrolled-ids))
                            :new_value target}
                           (audio-span machine))}))))

(defn- plan-speaker-label
  [{:keys [segments corrections request speaker enrolled-ids]}]
  (let [label (:speaker_label request)
        target (speaker-target request speaker)
        mapped (some-> (last (filter #(and (= "speaker_label" (:kind %)) (= label (:speaker_label %)))
                                     (corrections/active-corrections corrections)))
                       :new_value)
        current (if mapped (current-speaker mapped) {:speaker (str label)})]
    (cond
      (not (some #{label} (corrections/machine-labels segments))) {:error :invalid-speaker-label}
      (keyword? target) {:error target}
      (same-speaker? current target) {:error :correction-unchanged}
      (conflict? corrections (:base_seq request) [:speaker-label label]) {:error :correction-conflict}
      :else {:row {:kind "speaker_label"
                   :speaker_label label
                   :old_value (merge current (machine-speaker label enrolled-ids))
                   :new_value target}})))

(defn- plan-undo
  [{:keys [corrections request]}]
  (let [ids (by-id corrections)
        target (get ids (str (:correction_id request)))]
    (cond
      (or (nil? target) (= "undo" (:kind target))) {:error :unknown-correction}
      (contains? (corrections/undone-by corrections) (str (:id target))) {:error :already-undone}
      (conflict? corrections (:base_seq request) (corrections/segment-target target) (:id target))
      {:error :correction-conflict}
      :else {:row (merge {:kind "undo"
                          :undoes_id (:id target)}
                         (select-keys target [:audio_start_s :audio_end_s]))})))

(defn plan
  "Check a correction request and build the row to append.

  Inputs map:
  - :segments machine segments of the final transcript (vector)
  - :corrections stored corrections of the transcript, ordered by :seq
  - :request {:kind :base_seq :segment_index? :speaker_label? :text?
              :speaker? :speaker_id? :correction_id?}
  - :speaker the enrolled speaker row {:id :label} named by :speaker_id, or nil
  - :enrolled-ids {machine-label enrolled-speaker-id} for labels with exactly
    one enrolled speaker

  Returns {:row {:kind :seq ...}} with :seq set to the next number, or
  {:error keyword}: :too-many-corrections, :invalid-base-seq,
  :invalid-kind, :invalid-segment, :invalid-text, :invalid-speaker,
  :unknown-speaker, :invalid-speaker-label, :unknown-correction,
  :already-undone, :correction-unchanged or :correction-conflict."
  [{:keys [segments corrections request] :as input}]
  (let [head (corrections/head-seq corrections)
        base (:base_seq request)
        input (assoc input :corrected (corrections/corrected-segments segments corrections))
        result (cond
                 (>= (count corrections) max-corrections-per-transcript) {:error :too-many-corrections}
                 (not (and (int? base) (<= 0 base head))) {:error :invalid-base-seq}
                 :else (case (:kind request)
                         "segment_text" (plan-text input)
                         "segment_speaker" (plan-segment-speaker input)
                         "speaker_label" (plan-speaker-label input)
                         "undo" (plan-undo input)
                         {:error :invalid-kind}))]
    (if (:row result)
      (assoc-in result [:row :seq] (inc head))
      result)))

(ns samuraibff.ui.corrections
  "Pure helpers for the corrected, changes and original views of a final
  transcript on the recording page.

  The recording detail response carries the machine segments of each final
  record plus `:corrections {:head_seq n :items [...]}`. These helpers turn
  the latest final record into display messages for one view mode and
  describe corrections in plain language."
  (:require
   [clojure.string :as str]
   [samuraibff.transcripts.corrections :as corrections]
   [samuraibff.ui.transcript :as transcript]))

(def modes
  "View modes with their button labels, in display order."
  [[:corrected "Corrected"] [:changes "Changes"] [:original "Original"]])

(defn final-source
  "Return the correction source of a final tab, or nil.

  Inputs: the tab's final rows (ordered by created_at).
  Returns {:transcript-id string :head-seq int :items [correction]
  :segments [segment] :lang string?} for the latest row, the one the
  page shows."
  [rows]
  (when-let [row (last rows)]
    {:transcript-id (:id row)
     :head-seq (or (get-in row [:corrections :head_seq]) 0)
     :items (vec (get-in row [:corrections :items]))
     :segments (vec (:segments row))
     :lang (:lang row)}))

(defn correctable?
  "True when the source has segments that corrections can address."
  [source]
  (boolean (seq (:segments source))))

(defn text-changed?
  "True when a segment change from `corrections/segment-changes` leaves text
  that differs from the machine text. An edit that restores the machine text
  is still in the history, but the segment no longer counts as changed."
  [{:keys [original corrected text-edit]}]
  (boolean (and text-edit (not= (:text original) (:text corrected)))))

(defn speaker-changed?
  "True when a segment change leaves a speaker (or linked enrolled speaker)
  that differs from the machine one."
  [{:keys [original corrected speaker-edit]}]
  (boolean (and speaker-edit
                (or (not= (:speaker original) (:speaker corrected))
                    (not= (:speaker_id original) (:speaker_id corrected))))))

(defn changed?
  "True when a segment change differs from the machine segment."
  [change]
  (or (text-changed? change) (speaker-changed? change)))

(defn changed-count
  "Return how many segments of the source differ from the machine transcript."
  [source]
  (count (filter changed? (corrections/segment-changes (:segments source) (:items source)))))

(defn view-messages
  "Build display messages of the source for a view mode.

  Inputs: source from `final-source`; mode :corrected, :changes or :original.
  Returns a vector of messages {:kind \"final\" :seq segment-index :lang ...
  segment keys ... :change change}, where :change comes from
  `corrections/segment-changes`.
  - :corrected shows corrected segments, marks segments that differ from the
    machine segment with :edited and leaves out segments whose text was
    cleared.
  - :changes shows corrected segments with :diff (word runs from
    `corrections/word-diff`, machine text against corrected text) when the
    text differs and :speaker_was (the machine speaker's display name) when
    the speaker differs.
  - :original shows the machine segments."
  [{:keys [segments items lang]} mode]
  (into []
        (keep (fn [{:keys [index original corrected text-edit] :as change}]
                (let [edited? (changed? change)
                      base {:kind "final" :seq index :lang lang :change change}]
                  (case mode
                    :original (merge base original)
                    :corrected (when-not (and text-edit (str/blank? (:text corrected)))
                                 (cond-> (merge base corrected)
                                   edited? (assoc :edited true)))
                    :changes (cond-> (merge base corrected)
                               edited? (assoc :edited true)
                               (text-changed? change)
                               (assoc :diff (corrections/word-diff (:text original) (:text corrected)))
                               (and (speaker-changed? change) (not= (:speaker original) (:speaker corrected)))
                               (assoc :speaker_was (transcript/speaker->display-name (:speaker original))))))))
        (corrections/segment-changes segments items)))

(defn speaker-names
  "Return the speaker names to offer when changing a speaker: enrolled
  speaker labels first, then the other names shown in the corrected
  transcript, without duplicates or blanks.

  Inputs: enrolled speakers [{:id :label}], source from `final-source`."
  [speakers source]
  (into []
        (comp (map str/trim) (remove str/blank?) (distinct))
        (concat (map :label speakers)
                (map #(transcript/speaker->display-name (:speaker %))
                     (corrections/corrected-segments (:segments source) (:items source))))))

(defn speaker-request
  "Build the speaker part of a correction request from a typed name.

  A name equal to an enrolled speaker's label links that speaker
  ({:speaker_id id}); any other name is stored as typed ({:speaker name}).
  Returns nil for a blank name."
  [speakers name]
  (let [name (str/trim (str name))]
    (when-not (str/blank? name)
      (if-let [speaker (first (filter #(= name (:label %)) speakers))]
        {:speaker_id (str (:id speaker))}
        {:speaker name}))))

(defn- quoted
  [text]
  (let [text (str/trim (str text))]
    (if (str/blank? text) "(empty)" (str "“" text "”"))))

(defn- change-hunks
  "Group a word diff into separate changes: [{:removed string :added string}]."
  [diff]
  (->> (partition-by #(= :same (first %)) diff)
       (remove #(= :same (ffirst %)))
       (map (fn [runs]
              {:removed (str/join " " (keep (fn [[op text]] (when (= :del op) text)) runs))
               :added (str/join " " (keep (fn [[op text]] (when (= :ins op) text)) runs))}))))

(defn- hunk-phrase
  [{:keys [removed added]}]
  (cond
    (str/blank? removed) (str "added " (quoted added))
    (str/blank? added) (str "removed " (quoted removed))
    :else (str "changed " (quoted removed) " to " (quoted added))))

(defn- capitalize-first
  [s]
  (if (seq s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s))

(defn- describe-text
  "Describe a text correction: up to three separate changes, then a count."
  [old-text new-text]
  (let [hunks (change-hunks (corrections/word-diff old-text new-text))
        more (- (count hunks) 3)]
    (cond
      (str/blank? new-text) "Cleared the text"
      (empty? hunks) "Changed the spacing"
      :else (capitalize-first
             (str (str/join "; " (map hunk-phrase (take 3 hunks)))
                  (when (pos? more) (str "; " more " more")))))))

(defn describe
  "Describe one correction in plain language for the change history.

  Inputs: a correction map. Returns a string such as
  \"Changed “tear” to “tier”; added “test”\", \"Set the speaker to Petr\"
  or \"Renamed SPEAKER_00 to Miro in the whole transcript\"."
  [{:keys [kind old_value new_value speaker_label]}]
  (case kind
    "segment_text" (describe-text (:text old_value) (:text new_value))
    "segment_speaker" (str "Set the speaker to " (:speaker new_value))
    "speaker_label" (str "Renamed " (transcript/speaker->display-name speaker_label) " to "
                         (:speaker new_value) " in the whole transcript")
    "Undid a change"))

(defn author-label
  "Return who made a correction: \"you\", the author's name, or \"someone\"."
  [{:keys [author]}]
  (cond
    (:is_me author) "you"
    (seq (:display_name author)) (:display_name author)
    :else "someone"))

(defn apply-saved
  "Add a stored correction to a recording detail response.

  Inputs: detail map, transcript ID string, the correction from the POST
  response and the new head seq. Returns the detail with the correction
  appended to that final record (ignored when already present)."
  [detail transcript-id correction head-seq]
  (update-in detail [:transcripts :final]
             (fn [records]
               (mapv (fn [record]
                       (if (and (= (str transcript-id) (str (:id record)))
                                (not-any? #(= (:id correction) (:id %)) (get-in record [:corrections :items])))
                         (-> record
                             (update-in [:corrections :items] (fnil conj []) correction)
                             (update-in [:corrections :head_seq] (fnil max 0) head-seq))
                         record))
                     records))))

(defn error-message
  "Return the message to show when a correction request fails.

  Inputs: the safe server error code (string or nil)."
  [code]
  (case code
    "correction-conflict" "Someone changed this part while you were editing. The latest version is shown; check it and try again."
    "already-undone" "This change was already undone."
    "correction-unchanged" "Nothing changed."
    "read-only-access" "You have read-only access to this session."
    "too-many-corrections" "This transcript has reached the limit of corrections."
    "Could not save the correction. Try again."))

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

(defn active-count
  "Return how many corrections of the source are in effect."
  [source]
  (count (corrections/active-corrections (:items source))))

(defn view-messages
  "Build display messages of the source for a view mode.

  Inputs: source from `final-source`; mode :corrected, :changes or :original.
  Returns a vector of messages {:kind \"final\" :seq segment-index :lang ...
  segment keys ... :change change}, where :change comes from
  `corrections/segment-changes`.
  - :corrected shows corrected segments, marks edited ones with :edited and
    leaves out segments whose text was cleared.
  - :changes shows corrected segments with :diff (word runs from
    `corrections/word-diff`) for text edits and :speaker_was (the machine
    speaker's display name) for speaker edits.
  - :original shows the machine segments."
  [{:keys [segments items lang]} mode]
  (into []
        (keep (fn [{:keys [index original corrected text-edit speaker-edit] :as change}]
                (let [edited? (boolean (or text-edit speaker-edit))
                      base {:kind "final" :seq index :lang lang :change change}]
                  (case mode
                    :original (merge base original)
                    :corrected (when-not (and text-edit (str/blank? (:text corrected)))
                                 (cond-> (merge base corrected)
                                   edited? (assoc :edited true)))
                    :changes (cond-> (merge base corrected)
                               edited? (assoc :edited true)
                               text-edit (assoc :diff (corrections/word-diff (:text original) (:text corrected)))
                               (and speaker-edit (not= (:speaker original) (:speaker corrected)))
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

(defn describe
  "Describe one correction in plain language for the change history.

  Inputs: a correction map. Returns a string such as
  \"Changed “tear” to “tier”\", \"Set the speaker to Petr\" or
  \"Named SPEAKER_00 Miro in the whole transcript\"."
  [{:keys [kind old_value new_value speaker_label]}]
  (case kind
    "segment_text" (let [diff (corrections/word-diff (:text old_value) (:text new_value))
                         removed (str/join " " (keep (fn [[op text]] (when (= :del op) text)) diff))
                         added (str/join " " (keep (fn [[op text]] (when (= :ins op) text)) diff))]
                     (cond
                       (str/blank? (:text new_value)) "Cleared the text"
                       (str/blank? removed) (str "Added " (quoted added))
                       (str/blank? added) (str "Removed " (quoted removed))
                       :else (str "Changed " (quoted removed) " to " (quoted added))))
    "segment_speaker" (str "Set the speaker to " (:speaker new_value))
    "speaker_label" (str "Named " (transcript/speaker->display-name speaker_label) " "
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

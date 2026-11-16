(ns samuraibff.transcripts.correction-schemas
  "Malli schemas of the transcript corrections API.

  This namespace has no dependency on `samuraibff.schemas`, so that
  namespace can use these schemas in `TranscriptRecord`.")

(def ^:private Uuid
  [:re #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

(def CorrectionKind
  "Correction kinds."
  [:enum "segment_text" "segment_speaker" "speaker_label" "undo"])

(def TranscriptCorrection
  "One stored correction as returned to clients.

  - old_value: what the transcript showed before the edit ({text} or
    {speaker, speaker_id?, machine_speaker, machine_speaker_id?}).
  - new_value: the edit ({text} or {speaker, speaker_id?}); empty for undo.
  - author.display_name is the editor's name or email; is_me marks the caller."
  [:map
   [:id Uuid]
   [:seq [:int {:min 1}]]
   [:kind CorrectionKind]
   [:segment_index {:optional true} [:int {:min 0}]]
   [:speaker_label {:optional true} :string]
   [:old_value [:map-of :keyword :any]]
   [:new_value [:map-of :keyword :any]]
   [:undoes_id {:optional true} Uuid]
   [:audio_start_s {:optional true} :double]
   [:audio_end_s {:optional true} :double]
   [:created_at :string]
   [:author [:map
             [:display_name {:optional true} :string]
             [:is_me :boolean]]]])

(def TranscriptCorrections
  "Corrections of one final transcript: the highest seq (0 without
  corrections) and every stored correction ordered by seq."
  [:map
   [:head_seq [:int {:min 0}]]
   [:items [:sequential TranscriptCorrection]]])

(def CreateCorrectionRequest
  "Body of POST /api/recordings/{session_id}/transcripts/{transcript_id}/corrections.

  - kind segment_text: segment_index and text (an empty text clears the segment).
  - kind segment_speaker: segment_index and speaker or speaker_id.
  - kind speaker_label: speaker_label (a machine label) and speaker or speaker_id.
  - kind undo: correction_id.
  base_seq is the highest correction seq the client has seen."
  [:map
   [:kind CorrectionKind]
   [:base_seq [:int {:min 0}]]
   [:segment_index {:optional true} [:int {:min 0}]]
   [:speaker_label {:optional true} [:string {:min 1 :max 200}]]
   [:text {:optional true} [:string {:max 20000}]]
   [:speaker {:optional true} [:string {:max 500}]]
   [:speaker_id {:optional true} Uuid]
   [:correction_id {:optional true} Uuid]])

(def CreateCorrectionResponse
  "Response of a stored correction."
  [:map
   [:ok :boolean]
   [:correction TranscriptCorrection]
   [:head_seq [:int {:min 1}]]])

(ns samuraibff.ui.retranscribe-form
  "Pure rules of the Re-transcribe panel on the session detail page, for
  `POST /api/recordings/{session_id}/retranscribe`.

  Form state shape (map):
  - :title     string typed by the user
  - :lang      language code string; empty means detect the language
  - :controls  stream controls map for the final stage only (see
               `samuraibff.ui.upload-form`)

  Public API:
  - `default-title`, `initial-form`, `request-body`
  - `availability`, `error-message`"
  (:require
   [clojure.string :as str]
   [samuraibff.ui.output-settings :as settings]
   [samuraibff.ui.upload-form :as upload-form]))

(def ^:private title-suffix " (re-transcribed)")

(defn default-title
  "Return the suggested title of a re-transcription.

  Inputs: the title shown for the source session (string or nil).
  Returns: `<title> (re-transcribed)` within the title limit; the source
  title is shortened so the suffix stays. A blank title gives
  `Session (re-transcribed)`. Same rule as the server default."
  [source-title]
  (let [base (or (not-empty (str/trim (str (or source-title "")))) "Session")
        room (- upload-form/max-title-length (count title-suffix))]
    (str (str/trimr (subs base 0 (min room (count base)))) title-suffix)))

(defn initial-form
  "Return the starting form state for a session.

  Inputs:
  - session: detail :session map (:stream_controls, :recording_lang)
  - shown-title: the title the page shows for the session
  - detail: `/api/me` detail map (track catalog)

  Returns: {:title :lang :controls}. The source session's final tracks are
  selected when every one is still offered; otherwise the default tracks."
  [session shown-title detail]
  (let [catalog-ids (set (map :track_id (settings/entries detail :final)))
        source-tracks (get-in session [:stream_controls :final_tracks])
        reuse? (and (seq source-tracks) (seq catalog-ids) (every? catalog-ids source-tracks))]
    {:title (default-title shown-title)
     :lang (str (or (:recording_lang session) ""))
     :controls (cond-> upload-form/initial-controls
                 reuse? (assoc :final_tracks (vec source-tracks)))}))

(defn request-body
  "Return the JSON body for the re-transcribe request.

  Inputs: form state (see namespace doc) and the `/api/me` detail map.
  Returns: map with :lang (always sent; empty means detect), :store_recording,
  :title when not blank (trimmed; blank lets the server choose) and
  :final_tracks when tracks are configured."
  [{:keys [title lang controls]} detail]
  (let [controls (merge upload-form/initial-controls controls)
        title (str/trim (str (or title "")))
        catalog (settings/entries detail :final)
        store? (true? (:store_recording (settings/effective-controls controls detail)))]
    (cond-> {:lang (str/trim (str (or lang "")))
             :store_recording store?}
      (seq title) (assoc :title title)
      (seq catalog) (assoc :final_tracks (upload-form/selected-final-tracks controls detail)))))

(def unavailable-text
  "Why a manager cannot re-transcribe a session that kept no usable audio."
  "No saved audio is available, so this session cannot be transcribed again.")

(defn availability
  "Return how the page offers re-transcription for a session.

  Inputs: detail :session map with :can_retranscribe,
  :retranscribe_unavailable and :access {:can_manage}.
  Returns: :available, :unavailable (a manager, but no usable audio was
  kept) or nil (hide the control)."
  [{:keys [can_retranscribe retranscribe_unavailable access]}]
  (cond
    (true? can_retranscribe) :available
    (and (true? (:can_manage access))
         (= "recording-not-available" retranscribe_unavailable)) :unavailable
    :else nil))

(defn error-message
  "Return a plain-language message for a failed re-transcribe request.

  Inputs: HTTP status integer (nil for a network error) and the server error
  code string or nil. Returns: string."
  [status code]
  (case status
    nil "Could not reach the server. Check your connection and try again."
    401 "Your sign-in has expired. Sign in again, then try again."
    403 "You cannot transcribe this session again."
    404 "This session is no longer available."
    409 (if (= code "session-not-finished")
          "This session is still recording. Try again when it has stopped."
          unavailable-text)
    400 (case code
          "invalid-title" (str "The title is too long. Use at most " upload-form/max-title-length " characters.")
          "invalid-lang" "This language is not available. Choose another language or Auto."
          "These transcript choices are not available. Change them and try again.")
    503 (if (= code "uploads-disabled")
          "Transcribing again is turned off for this installation."
          "The service is temporarily unavailable. Try again in a few minutes.")
    "Could not start the new transcription. Try again."))

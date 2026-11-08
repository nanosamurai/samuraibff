(ns samuraibff.ui.session-status
  "Display status of a session, shared by the Sessions list and the session
  detail page so both show the same label for the same session.")

(def file-sources
  "Session `source` values for sessions whose audio is a stored file that the
  recorder converts: an uploaded file (`upload`) or the stored recording of
  another session (`retranscription`). Same values as
  `samuraibff.db.sessions/file-sources`."
  #{"upload" "retranscription"})

(defn file-source?
  "Return true when a session map has a `source` from `file-sources`."
  [session]
  (contains? file-sources (:source session)))

(defn display-status
  "Derive a compact UI status descriptor for a session.

  Inputs:
  - session: map from /api/recordings (list row or detail :session) with keys:
      :status (string)
      :has_recording (boolean)
      :has_final_transcript (boolean)
      :source (string, optional; one of `file-sources` for a session from a
               stored file)

  A finished session from a stored file without a final transcript is
  Processing: the recorder converts the file and the final tracks transcribe
  it. When conversion fails, the BFF sets the status to `failed`. A finished
  live session without a final transcript stays Finished.

  Returns:
  - {:label string :badge-class string :icon keyword :tooltip string}
    (:icon is an icon name, or :rec-dot for the recording indicator)"
  [{:keys [status has_recording has_final_transcript source]}]
  (let [status (some-> status str)
        has-recording? (true? has_recording)
        has-final? (true? has_final_transcript)
        retranscription? (= "retranscription" source)
        file? (contains? file-sources source)]
    (cond
      (= status "failed")
      {:label "Failed"
       :badge-class "bad"
       :icon :circle-x
       :tooltip "Session failed"}

      (= status "active")
      {:label "Recording"
       :badge-class "warn"
       :icon :rec-dot
       :tooltip "Recording/transcription in progress"}

      (= status "created")
      {:label "Created"
       :badge-class "muted"
       :icon :circle-dashed
       :tooltip "Draft session (recording not started)"}

      (and (= status "finished") has-final?)
      {:label "Finalized"
       :badge-class "ok"
       :icon :circle-check
       :tooltip "Final transcript is available"}

      (and (= status "finished") file?)
      {:label "Processing"
       :badge-class "muted"
       :icon :loader-circle
       :tooltip (if retranscription?
                  "Transcribing the saved audio again"
                  "Converting and transcribing the uploaded file")}

      (= status "finished")
      {:label "Finished"
       :badge-class "muted"
       :icon :circle-stop
       :tooltip "Recording stopped; final transcript not available yet"}

      has-recording?
      {:label "Processing"
       :badge-class "muted"
       :icon :loader-circle
       :tooltip "Recording finished; final transcript not available yet"}

      :else
      {:label "Created"
       :badge-class "muted"
       :icon :circle-dashed
       :tooltip "Session created"})))

(defn upload-notice
  "Return the note that the session detail page shows for a session from a
  stored file: an uploaded file or a re-transcription.

  Inputs: session map from /api/recordings/{session_id} :session with
  :status, :has_final_transcript and :source.
  Returns: {:kind :processing|:failed :text string} while the file is
  converted and transcribed or after its conversion failed; nil for a live
  session and once a final transcript exists."
  [{:keys [status has_final_transcript source]}]
  (let [retranscription? (= "retranscription" source)]
    (when (contains? file-sources source)
      (cond
        (= "failed" status)
        {:kind :failed
         :text (if retranscription?
                 (str "The saved audio could not be transcribed again. Open the original "
                      "session and try again.")
                 (str "The file could not be converted. Check that it is a readable audio "
                      "or video file with sound, then upload it again."))}

        (and (= "finished" status) (not (true? has_final_transcript)))
        {:kind :processing
         :text (if retranscription?
                 (str "The saved audio is being transcribed again. This can take a few "
                      "minutes. This page updates automatically.")
                 (str "The uploaded file is being converted and transcribed. This can take "
                      "a few minutes. This page updates automatically."))}))))

(def labels-in-sort-order
  "Status labels in the order that a Sessions status sort uses (ascending).

  The order follows the life of a session: draft, recording, processing,
  stopped, final transcript ready, then failed. The BFF builds its SQL sort
  from this vector (see `samuraibff.db.recordings`), so the list order and
  the labels from `display-status` stay the same."
  ["Created" "Recording" "Processing" "Finished" "Finalized" "Failed"])

(defn label-sort-rank
  "Return the zero-based sort rank of a status label.

  Inputs: label string from `display-status`.
  Returns: integer index in `labels-in-sort-order`. Throws for an unknown
  label, so a new label cannot be added without a sort position."
  [label]
  (or (first (keep-indexed (fn [rank known] (when (= known label) rank))
                           labels-in-sort-order))
      (throw (ex-info "Unknown session status label" {:label label}))))

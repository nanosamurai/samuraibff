(ns samuraibff.ui.session-status
  "Display status of a session, shared by the Sessions list and the session
  detail page so both show the same label for the same session.")

(defn display-status
  "Derive a compact UI status descriptor for a session.

  Inputs:
  - session: map from /api/recordings (list row or detail :session) with keys:
      :status (string)
      :has_recording (boolean)
      :has_final_transcript (boolean)

  Returns:
  - {:label string :badge-class string :icon keyword :tooltip string}
    (:icon is an icon name, or :rec-dot for the recording indicator)"
  [{:keys [status has_recording has_final_transcript]}]
  (let [status (some-> status str)
        has-recording? (true? has_recording)
        has-final? (true? has_final_transcript)]
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

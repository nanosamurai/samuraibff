# Re-transcription

`POST /api/recordings/{session_id}/retranscribe` creates a **new** session from
the saved audio of an existing session. Use it to try other final tracks or
another language. The new session gets only the final output. The original
session and its transcripts do not change.

A session keeps at most one final transcript per track. For this reason a
re-run cannot go into the same session.

## Session page

The session detail page shows a **Re-transcribe** icon button next to the
**Export transcript** button, above the transcript. The button shows when
`can_retranscribe` is true, also for a session whose transcription failed or
found no speech. When you manage the session but no usable audio was kept, the
button is disabled and its tooltip says why. Other readers do not see it.

The button opens a panel below it with:

* **Session name**: `<title> (re-transcribed)`. You can change it.
* **Language**: the Record page picker. The language of the saved recording
  is preselected. **Auto** detects the language.
* **Transcripts**: the final track cards and **Keep audio for playback**
  field of the Upload page. The tracks of the original session are
  preselected when the deployment still offers all of them; else the default
  tracks.

**Re-transcribe** sends the request and opens the new session. Errors show in
the panel in plain language. Escape or a click outside closes the panel.

The new session shows **Re-transcribed from** with a link to the original
session, when you may open it. The original session lists its
**Re-transcriptions** with their status, when you may open them.

The new session is **Processing** until its final transcript exists, like an
uploaded file. If the recorder cannot convert the audio, the status becomes
**Failed** and the page says so.

## Request

JSON body. All fields are optional; you can send no body.

| Field | Meaning | Default |
| --- | --- | --- |
| `title` | Session title, at most 200 characters | `<source title> (re-transcribed)` |
| `lang` | `""` (detect) or a code such as `en` or `pt-BR` | The language of the saved recording, else `""` |
| `final_tracks` | Array of configured final track IDs (1 to 4) | The source session's tracks when all are still configured, else the deployment default |
| `store_recording` | Keep the audio for playback (`true` or `false`) | `true`. More than one final track needs `true` |

Response `202`:

```json
{"session_id": "<new uuid>", "title": "Interview (re-transcribed)",
 "retranscribed_from": "<source uuid>"}
```

Errors (`{"ok": false, "message": ...}`):

| Status | Message | Meaning |
| --- | --- | --- |
| 400 | `invalid-session-id`, `invalid-request`, `invalid-title`, `invalid-lang`, `invalid-stream-controls` | Bad path or field |
| 403 | `forbidden` | The caller may not record, or does not manage the source session |
| 404 | `not-found` | The caller may not read the source session, or it does not exist |
| 409 | `session-not-finished` | The source session is still `created` or `active` |
| 409 | `recording-not-available` | No saved audio that can be copied (see Storage) |
| 503 | `uploads-disabled`, `db-unavailable`, `kafka-unavailable` | Service state |
| 500 | `retranscribe-failed` | Unexpected error |

Example:

```bash
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"final_tracks": ["whisperx"], "lang": "en"}' \
  https://bff.example.com/api/recordings/$SESSION_ID/retranscribe
```

## Access control

* The caller must read the source session. Otherwise the response is `404`,
  so it does not confirm that the session exists.
* The caller must be able to record and must manage the source session (its
  owner, or the manager of the organization API key that owns it). A reader of
  a shared session gets `403`: it cannot make its own copy of the audio.
* The caller owns the new session with its default audience, as for
  `POST /api/sessions`. Shares of the source session are not copied.
* `retranscribed_from` and `retranscriptions` in the session detail show only
  sessions that the caller may read. The title of an unreadable session is
  never shown.
* Each accepted request writes an access audit event
  `session.retranscribed` (target: new session; details: `parent_session_id`
  and `final_tracks`). It holds IDs only, no titles or transcript text.

## Storage and processing

1. The BFF reads the latest recording of the source session. It copies only an
   `s3://` recording in the configured recordings bucket under
   `<prefix>/<tenant_id>/` of the caller's tenant. A `file://` recording,
   another bucket or another tenant's key gives `409 recording-not-available`.
2. S3 copies the object inside the bucket (CopyObject; the audio does not pass
   through the BFF) to
   `<prefix>/<tenant_id>/uploads/<new_session_id>/source`.
3. The BFF creates the session (`finished`, final output only,
   `session_settings` `{"source": "retranscription", "parent_session_id": "<uuid>"}`),
   publishes `sessions.meta`, then `recordings.uploaded` after the broker ack.
4. The recorder converts the copy like an uploaded file, deletes it and
   publishes `recordings.finished`. Final tracks transcribe the new recording.

Each session keeps its own copy of the audio. Deleting one session does not
change the audio of the other.

Webhooks and workflows run for the new session with the caller's defaults, as
for any new session. `transcript.final.ready` fires for the new session.

On a failure after the copy (database or Kafka), the BFF deletes the copy
best-effort and sets a created session to `failed`.

The session has `source: "retranscription"` in `GET /api/recordings` and
`GET /api/recordings/{session_id}`. Status, the conversion failure event and
cancel by delete work as for uploads (see `docs/features-file-uploads.md`).

## Session detail fields

`GET /api/recordings/{session_id}` adds to `session`:

| Field | Meaning |
| --- | --- |
| `can_retranscribe` | The caller may call the endpoint now (same rules) |
| `retranscribe_unavailable` | `recording-not-available` when only the saved audio is missing; else absent |
| `recording_lang` | Language of the saved recording, or `null`; the default `lang` |
| `retranscribed_from` | `{"id", "title"}` of the source session when the caller may read it; else absent |
| `retranscriptions` | Readable re-transcriptions, newest first, at most 20: `{"id", "title", "status", "created_at", "source", "has_final_transcript"}` |

## Configuration and limits

* Re-transcription uses the file upload pipeline. It needs file uploads
  enabled (`SAMURAIBFF_UPLOAD_MAX_BYTES` > 0 and
  `SAMURAIBFF_S3_RECORDINGS_BUCKET`); else `503 uploads-disabled`.
* The BFF needs `s3:GetObject` on the tenant recording keys (also used for
  playback) and `s3:PutObject` and `s3:DeleteObject` on the `uploads/` keys.
* The recorder must write recordings under the same prefix as
  `SAMURAIBFF_S3_RECORDINGS_PREFIX`. Recordings elsewhere cannot be copied.
* The upload size limit does not apply to the copy. The recorder's file size
  and duration limits apply; audio over these limits ends as **Failed**.
* A single S3 CopyObject copies at most 5 GB.
* A session that kept no audio (`store_recording` false) or whose audio was
  deleted cannot be re-transcribed.
* There is no rate limit. Each request copies the audio and runs the final
  tracks again, so it uses storage and transcription capacity. Use edge rate
  limits if you need them.

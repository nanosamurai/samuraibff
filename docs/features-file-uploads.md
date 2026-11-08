# File uploads

`POST /api/sessions/upload` creates a session from an existing audio or video
file. The session gets only the final output: no realtime or refined
transcript. Results use the same endpoints and webhooks as a live session.

## Upload page

The browser UI has an **Upload** page (`/upload`) in the left navigation,
after **Record**. Navigation shows it only when `GET /api/me` reports
`features.uploads_enabled` and the user may record (`access.capabilities.record`).
A direct link shows a short message instead of the form in the other cases.

The page has one form:

* **File**: drop a file on the drop area, or use **Choose file**. The page
  shows the file name and size, and **Remove** clears it. The page rejects a
  file over `features.uploads_max_bytes`, or a file that the browser declares as
  neither audio nor video, before the upload starts. The BFF still checks the
  file content.
* **Session name**: the file name without its extension. You can change it.
* **Language**: the same searchable picker as on Record. **Auto** detects the
  language.
* **Transcripts**: the Record page's final track cards and **Keep audio for
  playback** field. The deployment's default tracks are preselected. The page
  sends `final_tracks` only after you change the selection. More than one
  track always keeps the audio.

**Upload and transcribe** sends the file and shows a progress bar.
**Cancel** stops the upload while the file is still being sent; then no
session is created. The form is locked during the upload. Leaving the page
cancels the upload, and closing the tab asks for confirmation. Errors are shown
in plain language and get keyboard focus.

After the upload, the page opens the new session. Its status is
**Processing** while the file is converted and transcribed, and **Finalized**
when a final transcript exists. The Sessions list shows the same status and
sorts it with the other Processing sessions. In the
same browser tab, the session page also says that the file is being converted
and transcribed, until a final transcript is available. The page updates
automatically.

## Request

`multipart/form-data`:

| Field | Required | Meaning |
| --- | --- | --- |
| `file` | yes | Audio or video file (one file per request) |
| `title` | no | Session title, at most 200 characters. Default: `Upload <date> <time>` |
| `lang` | no | Language code such as `en` or `pt-BR`. Empty: the final track detects it |
| `final_tracks` | no | Comma-separated configured final track IDs. Default: the deployment default |
| `store_recording` | no | `true` (default) or `false`. More than one final track needs `true` |

Accepted containers (checked from the leading bytes, not from the file name or
the declared type): WAV, MP3, AAC (ADTS), FLAC, Ogg (Vorbis/Opus), MP4/M4A/MOV,
Matroska/WebM, AVI, AIFF, AMR, ASF/WMA/WMV and CAF.

Response `200`: `{"session_id": "<uuid>", "title": "<title>"}`.

Errors (`{"ok": false, "message": ...}`):

| Status | Message |
| --- | --- |
| 400 | `missing-file`, `empty-file`, `multiple-files`, `invalid-multipart`, `invalid-title`, `invalid-lang`, `invalid-stream-controls` |
| 403 | `forbidden` (the caller may not record) |
| 413 | `file-too-large` |
| 415 | `unsupported-media-type` |
| 503 | `uploads-disabled`, `db-unavailable`, `kafka-unavailable` |

Example:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -F file=@interview.m4a -F lang=en \
  https://bff.example.com/api/sessions/upload
```

## Access control

An uploaded session has the same owner and audience as a session created with
`POST /api/sessions` by the same caller:

* a person owns the session; it starts Private;
* a personal API key: the key's owner owns it and the key is recorded;
* an organization API key: the key owns it and it starts with the key's
  default audience.

## Processing and status

1. The BFF stores the file unchanged at
   `s3://<recordings-bucket>/<prefix>/<tenant_id>/uploads/<session_id>/source`.
   The BFF does not decode it.
2. The BFF creates the session, saves its stream controls and sets it to
   `finished` (the input is complete, as after a live session stops). It then
   publishes `sessions.meta` and `recordings.uploaded`.
3. `recorder_worker` (xamurai) converts the file with FFmpeg into the normal
   session recording, deletes the source and publishes `recordings.finished`.
4. Final tracks transcribe the recording. Persistor stores the transcript.

Poll `GET /api/recordings/{session_id}` until the transcript of each selected
final track is present, or subscribe to the `transcript.final.ready` webhook.

If the worker cannot decode the file (corrupt file, no audio, over the duration
or time limit), it logs the reason and deletes the source. No transcript
arrives, so clients should use a timeout.

To cancel, abort the HTTP request before the response (this creates no session
and stores nothing), or delete the session with
`DELETE /api/recordings/{session_id}`. A transcript that arrives for a deleted
session is dropped.

## Limits and configuration

| Setting | Env | Default |
| --- | --- | --- |
| `[:uploads :max-bytes]` | `SAMURAIBFF_UPLOAD_MAX_BYTES` | `134217728` (128 MiB; `0` disables uploads) |
| `[:kafka :topics :recordings-uploaded]` | `SAMURAIBFF_KAFKA_TOPIC_RECORDINGS_UPLOADED` | `recordings.uploaded` |

`GET /api/me` reports the result as `features.uploads_enabled` and
`features.uploads_max_bytes` (`0` when uploads are disabled).

Uploads also need `SAMURAIBFF_S3_RECORDINGS_BUCKET`, and the BFF needs
`s3:PutObject`, `s3:AbortMultipartUpload` and `s3:DeleteObject` on the
`uploads/` keys of the recordings bucket.

128 MiB holds about 1 hour of compressed audio or 16 kHz mono WAV. The worker
limits the decoded duration (default 4 hours) and the FFmpeg run time; see
xamurai `docs/uploaded-files.md`.

## Security

* The HTTP server (http-kit) reads the whole request body into memory before
  routing and authentication. The server body limit is therefore the upload
  limit plus 1 MiB for every request. Size the BFF heap for the number of
  concurrent uploads you expect, and keep the limit as low as your files
  allow. Edge rate limits or an IP allowlist reduce unauthenticated memory
  pressure.
* The client file name and declared content type are ignored and not stored.
* The BFF never decodes media; FFmpeg runs in the recorder worker with limits.

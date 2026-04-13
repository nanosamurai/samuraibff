(ns samuraibff.schemas
  "Malli schemas for samuraibff:
   - request/response shapes
   - WebSocket event union types
   - whisperx -> bff refined callback payloads

   Conventions:
   - All WS events MUST carry :type, :session_id, :seq, :ts_ms
   - :seq is monotonic per session (assigned by BFF)
   - Times in segments are seconds (double) unless explicitly *_ms"
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.util :as mu]
   [malli.transform :as mt]))

;; ---------------------------------------------------------------------
;; Common primitives
;; ---------------------------------------------------------------------

(def Uuid
  "UUID string form (canonical 36 chars)."
  [:re #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

(def NonEmptyString
  "A non-empty string."
  [:and :string [:fn (fn [s] (pos? (count s)))]])

(def NonNegInt
  "Integer >= 0."
  [:and :int [:>= 0]])

(def Sec
  "Time in seconds (double)."
  :double)

(def MsEpoch
  "Epoch timestamp in milliseconds."
  [:and :int [:>= 0]])

(def LangCode
  "Language code like 'cs', 'en', or empty string for auto."
  [:and :string [:fn (fn [s] (<= (count s) 8))]])

(def SpeakerLabel
  "Speaker label from diarization (e.g. SPEAKER_00) or anything stable-ish."
  [:and :string [:fn (fn [s] (<= (count s) 64))]])

(def EventType
  "WS event type."
  [:enum "asr" "refined" "status" "error"])

(def BaseWsEvent
  "Fields present on every WS event."
  [:map
   [:type EventType]
   [:session_id NonEmptyString]
   [:seq NonNegInt]
   [:ts_ms MsEpoch]])

;; ---------------------------------------------------------------------
;; Domain schemas
;; ---------------------------------------------------------------------

(def TranscriptSegment
  "A transcript segment (either realtime or refined)."
  [:map
   [:start_s Sec]
   [:end_s Sec]
   [:text :string]
   [:lang {:optional true} LangCode]
   [:speaker {:optional true} SpeakerLabel]
   ;; Optional word-level timing (karaoke highlighting). Present on FINAL
   ;; transcripts when WhisperX alignment succeeds.
   [:words {:optional true}
    [:sequential
     [:map
      [:start_s Sec]
      [:end_s Sec]
      [:text :string]]]]])

(def AsrEvent
  "Realtime ASR event from BFF to UI.
   - :final indicates whether this is FINAL vs PARTIAL (realtime)."
  (mu/merge
   BaseWsEvent
   [:map
    [:type [:= "asr"]]
    [:start_s Sec]
    [:end_s Sec]
    [:text :string]
    [:lang {:optional true} LangCode]
    [:speaker {:optional true} SpeakerLabel]
    [:final :boolean]]))

(def RefinedEvent
  "Refined transcript event from BFF to UI.
   - emitted after whisperx callback merge
   - semantics: refined updates replace overlapping live window.

   Note: `:supersedes_seq` is optional and may be present when workers link
   refined segments to realtime seq numbers."
  (mu/merge
   BaseWsEvent
   [:map
    [:type [:= "refined"]]
    [:start_s Sec]
    [:end_s Sec]
    [:text :string]
    [:lang {:optional true} LangCode]
    [:speaker {:optional true} SpeakerLabel]
    [:supersedes_seq {:optional true} [:sequential NonNegInt]]]))

(def StatusEvent
  "Status event for UI debug and lifecycle."
  (mu/merge
   BaseWsEvent
   [:map
    [:type [:= "status"]]
    [:status [:enum "connected" "started" "paused" "resumed" "stopped"]]
    [:detail {:optional true} :string]]))

(def ErrorEvent
  "Error event for UI debugging."
  (mu/merge
   BaseWsEvent
   [:map
    [:type [:= "error"]]
    [:message :string]
    [:code {:optional true} :int]
    [:detail {:optional true} :any]]))

(def WsEvent
  "Union of all possible WS events."
  [:multi {:dispatch :type}
   ["asr" AsrEvent]
   ["refined" RefinedEvent]
   ["status" StatusEvent]
   ["error" ErrorEvent]])

;; ---------------------------------------------------------------------
;; HTTP API schemas
;; ---------------------------------------------------------------------

(def ApiOkResponse
  "Generic ok response body.

  Shape:
  - {:ok true}"
  [:map
   [:ok [:= true]]])

(def ApiErrorResponse
  "Generic error response.

  Shape:
  - {:ok false :message <string>}

  Notes:
  - `message` is intended to be a stable, machine-readable error identifier.
  - Additional keys may be present on some endpoints."
  [:map
   [:ok [:= false]]
   [:message :string]])

(def HealthCheckResponse
  "Response body for GET /health.

  Shape:
  - {:status \"ok\" :timestamp <inst> :version <string>}"
  [:map
   [:status [:enum "ok"]]
   [:timestamp inst?]
   [:version string?]])

(def ReadinessResponse
  "Response body for GET /ready.

  Readiness is intended for load balancers / Kubernetes readiness probes.
  Unlike liveness (/health), readiness may return a non-200 when a required
  dependency (e.g. Postgres) is unavailable."
  [:map
   [:status [:enum "ok" "degraded"]]
   [:timestamp inst?]
   [:version string?]
   [:db [:map
         [:up? boolean?]]]
   [:kafka [:map
            [:up? boolean?]]]
   [:grpc [:map
           [:up? boolean?]]]])

(def CreateSessionResponse
  "Response body for POST /api/sessions.

  Shape:
  - {:session_id <uuid>
     :title <string?>}"
  [:map
   [:session_id Uuid]
   [:title {:optional true} [:maybe :string]]])

(def CreateSessionRequest
  "Request body for POST /api/sessions.

  Shape:
  - {:title <string?>
     :webhook_overrides <WebhookOverrides?>}

  Notes:
  - Title is optional and may be nil/blank (server will generate a default).
  - webhook_overrides is optional; when present, BFF stores it and uses it to
    publish `sessions.meta` routing snapshot per webhook RFC.

  IMPORTANT:
  - We intentionally refer to `WebhookEventType` here as strings (not as a
    schema symbol) because webhook event types are defined later in this file.
  "
  [:map
   [:title {:optional true}
    [:maybe [:and :string [:fn (fn [s] (<= (count s) 200))]]]]
   [:webhook_overrides {:optional true}
    [:maybe
     [:map
      [:use_defaults {:optional true} :boolean]
      [:webhook_ids {:optional true} [:sequential Uuid]]
      [:disable_event_types {:optional true}
       [:sequential
        [:enum
         "transcript.refined.segment"
         "recording.finished"
         "transcript.final.ready"]]]]]]])

;; ---------------------------------------------------------------------
;; Webhooks (tenant-configured outbound endpoints)
;; ---------------------------------------------------------------------

(def WebhookEventType
  "Webhook event type string.

  v1 values per RFC:
  - transcript.refined.segment
  - recording.finished
  - transcript.final.ready"
  [:enum
   "transcript.refined.segment"
   "recording.finished"
   "transcript.final.ready"])

(def WebhookAuthType
  "Webhook auth type.

  Values:
  - none
  - hmac
  - oauth
  - api_key"
  [:enum "none" "hmac" "oauth" "api_key"])

(def WebhookAuth
  "Webhook auth configuration (non-secret fields).

  Notes:
  - secret values are provided separately (write-only) and stored via SecretStore.
  "
  [:map
   [:type WebhookAuthType]

   ;; OAuth client_credentials
   [:token_url {:optional true} [:maybe :string]]
   [:client_id {:optional true} [:maybe :string]]
   [:scopes {:optional true} [:maybe :string]]

   ;; API key
   [:header_name {:optional true} [:maybe :string]]
   [:prefix {:optional true} [:maybe :string]]])

(def StaticHeaders
  "Optional static headers (non-secret) attached to webhook requests.

  Shape: map string->string." 
  [:map-of :string :string])

(def CreateWebhookRequest
  "Request body for POST /api/webhooks.

  Secrets are write-only fields:
  - :hmac_secret
  - :api_key
  - :oauth_client_secret" 
  [:map
   [:name NonEmptyString]
   [:url NonEmptyString]
   [:enabled {:optional true} :boolean]
   [:auth WebhookAuth]
   [:subscriptions [:sequential WebhookEventType]]
   [:static_headers {:optional true} [:maybe StaticHeaders]]

   [:hmac_secret {:optional true} [:maybe :string]]
   [:api_key {:optional true} [:maybe :string]]
   [:oauth_client_secret {:optional true} [:maybe :string]]])

(def CreateWebhookResponse
  "Response body for POST /api/webhooks." 
  [:map
   [:ok :boolean]
   [:webhook_id Uuid]])

(def UpdateWebhookRequest
  "Request body for PUT /api/webhooks/{id}.

  All fields are optional; provided fields replace stored values.
  Secrets are write-only.
  "
  [:map
   [:name {:optional true} [:maybe :string]]
   [:url {:optional true} [:maybe :string]]
   [:enabled {:optional true} [:maybe :boolean]]
   [:auth {:optional true} [:maybe WebhookAuth]]
   [:subscriptions {:optional true} [:maybe [:sequential WebhookEventType]]]
   [:static_headers {:optional true} [:maybe StaticHeaders]]

   [:hmac_secret {:optional true} [:maybe :string]]
   [:api_key {:optional true} [:maybe :string]]
   [:oauth_client_secret {:optional true} [:maybe :string]]])

(def WebhookItem
  "Webhook list item.

  Notes:
  - secret values are never returned; only secret refs are stored in DB.
  "
  [:map
   [:id Uuid]
   [:tenant_id Uuid]
   [:name :string]
   [:url :string]
   [:enabled :boolean]
   [:auth_type :string]
   [:hmac_secret_ref {:optional true} [:maybe :string]]
   [:oauth_client_secret_ref {:optional true} [:maybe :string]]
   [:api_key_ref {:optional true} [:maybe :string]]
   [:oauth_token_url {:optional true} [:maybe :string]]
   [:oauth_client_id {:optional true} [:maybe :string]]
   [:oauth_scopes {:optional true} [:maybe :string]]
   [:api_key_header_name {:optional true} [:maybe :string]]
   [:api_key_prefix {:optional true} [:maybe :string]]
   [:static_headers {:optional true} [:maybe :map]]
   [:created_at :string]])

(def WebhooksListResponse
  "Response body for GET /api/webhooks." 
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:items [:sequential WebhookItem]]])

(def WebhookDefaultsRequest
  "Request body for PUT /api/webhooks/defaults.

  Shape:
  - {:webhook_ids [<uuid> ...]}" 
  [:map
   [:webhook_ids [:sequential Uuid]]])

(def WebhookDefaultsResponse
  "Response body for GET /api/webhooks/defaults." 
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:webhook_ids [:sequential Uuid]]])

(def ApiMeResponse
  "Response body for GET /api/me.

  When authenticated:
  - authenticated=true and user info is present.

  When unauthenticated (only possible when auth is not required by config):
  - authenticated=false and no user/tenant_id is present."
  [:map
   [:ok :boolean]
   [:authenticated :boolean]
   [:tenant_id {:optional true} Uuid]
   [:tenant_name {:optional true} :string]
   [:user {:optional true}
    [:map
     [:sub {:optional true} :string]
     [:preferred_username {:optional true} :string]
     [:email {:optional true} :string]]]
   [:message {:optional true} :string]])

(def RecordingItem
  "Single list element returned by `GET /api/recordings`.

  Notes:
  - Timestamp fields are nullable, depending on whether a session was started/ended.
  - `recording` contains best-effort metadata from the latest recording.
  - The API intentionally does NOT expose internal recording URLs (file://, s3://...)."
  [:map
   [:session_id Uuid]
   [:session_key [:maybe :string]]
   [:title {:optional true} [:maybe :string]]
   [:status [:maybe :string]]
   [:started_at [:maybe :string]]
   [:ended_at [:maybe :string]]
   [:created_at [:maybe :string]]
   [:has_recording :boolean]
   [:has_final_transcript :boolean]
   [:recording
    [:map
     [:created_at [:maybe :string]]
     [:duration_s [:maybe :double]]
     [:sample_rate [:maybe :int]]
     [:lang [:maybe :string]]]]])

(def RecordingsListResponse
  "Response body for GET /api/recordings."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:items [:sequential RecordingItem]]])

(def RecordingDetailSession
  "Session metadata returned by `GET /api/recordings/{session_id}`."
  [:map
   [:id Uuid]
   [:session_key [:maybe :string]]
   [:title [:maybe :string]]
   [:status [:maybe :string]]
   [:started_at [:maybe :string]]
   [:ended_at [:maybe :string]]
   [:created_at [:maybe :string]]
   ;; Stream controls snapshot (outputs + retention + realtime knobs).
   ;; Stored as JSONB on sessions and returned as a JSON object.
   [:stream_controls {:optional true} [:maybe :map]]
   ;; Flags for UI convenience (used for audio playback gating).
   [:has_recording :boolean]
   [:has_final_transcript :boolean]])

(def TranscriptRecord
  "Transcript record element returned by `GET /api/recordings/{session_id}`.

  Notes:
  - `segments` is returned as a JSON array of segment objects (not a JSON string).
  - Only a whitelisted subset of DB fields is exposed (no tenant_id/user_id/etc.)."
  [:map
   [:id Uuid]
   [:type [:enum "refined" "final"]]

   ;; Pipeline metadata (safe to expose)
   [:source :string]
   [:model [:maybe :string]]
   [:window_length [:maybe :int]]

   ;; Window metadata (may be null depending on source/type)
   [:segment_start_s [:maybe Sec]]
   [:segment_end_s [:maybe Sec]]

   ;; Linkage to realtime WS seq numbers (worker-provided)
   [:supersedes_seq {:optional true} [:maybe [:sequential NonNegInt]]]

   ;; Original event timestamp in nanoseconds (if available)
   [:event_created_at_ns [:maybe :int]]

   ;; Transcript payload
   [:created_at :string]
   [:lang [:maybe LangCode]]
   [:duration_s [:maybe :double]]
   [:full_text :string]
   [:segments [:sequential TranscriptSegment]]])

(def RecordingDetailResponse
  "Response body for GET /api/recordings/{session_id}."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:session RecordingDetailSession]
   [:transcripts
    [:map
     [:refined [:sequential TranscriptRecord]]
     [:final [:sequential TranscriptRecord]]]]])

(def DeleteRecordingResponse
  "Response body for DELETE /api/recordings/{session_id}."
  [:map
   [:ok :boolean]
   [:deleted {:optional true} :boolean]
   [:message {:optional true} :string]])

(def UpdateSessionTitleRequest
  "Request body for PATCH /api/sessions/{session_id}.

  Shape:
  - {:title <string?>}

  Notes:
  - Title may be nil/blank (server normalizes blank to nil)."
  [:map
   [:title {:optional true} [:maybe :string]]])

(def UpdateSessionTitleResponse
  "Response body for PATCH /api/sessions/{session_id}."
  [:map
   [:ok :boolean]
   [:session_id Uuid]
   [:title {:optional true} [:maybe :string]]
   [:message {:optional true} :string]])

(def ApiCredentialsListResponse
  "Response body for GET /api/api-credentials."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:items
    [:sequential
     [:map
      [:id Uuid]
      [:tenant_id Uuid]
      [:name :string]
      [:keycloak_client_id :string]
      [:created_by_sub [:maybe :string]]
      [:created_at :string]
      [:last_used_at [:maybe :string]]
      [:revoked_at [:maybe :string]]]]]])

(def CreateApiCredentialRequest
  "Request body for POST /api/api-credentials."
  [:map
   [:name [:and :string [:fn (fn [s] (<= 1 (count s) 200))]]]])

(def CreateApiCredentialResponse
  "Response body for POST /api/api-credentials.

  The client_secret is returned only at creation/rotation time."
  [:map
   [:ok :boolean]
   [:credential_id Uuid]
   [:client_id :string]
   [:client_secret :string]])

(def RotateApiCredentialResponse
  "Response body for POST /api/api-credentials/{id}/rotate.

  The new client_secret is returned only once."
  [:map
   [:ok :boolean]
   [:credential_id Uuid]
   [:client_id :string]
   [:client_secret :string]])

(def SpeakersListResponse
  "Response body for GET /api/speakers."
  [:map
   [:ok :boolean]
   [:items
    [:sequential
     [:map
      [:id Uuid]
      [:tenant_id Uuid]
      [:user_id [:maybe Uuid]]
      [:label :string]
      [:audio_url [:maybe :string]]
      [:created_at [:maybe :string]]]]]])

(def CreateSpeakerResponse
  "Response body for POST /api/speakers."
  [:map
   [:ok :boolean]
   [:speaker_id Uuid]
   [:tenant_id Uuid]
   [:label :string]
   [:sample_url :string]
   [:manifest_url :string]])

(def CreateSpeakerFromRecordingRequest
  "Request body for POST /api/speaker-enrollment/from-recording.

  Shape:
  - {:session_id <uuid>
     :start_s <double>
     :end_s <double>
     :label <string>}"
  [:map
   [:session_id Uuid]
   [:start_s Sec]
   [:end_s Sec]
   [:label NonEmptyString]])

(def SpeakerEnrollmentClipInfo
  "Clip window information returned by speaker enrollment from a recording.

  Shape:
  - {:session_id <uuid>
     :start_s <double>
     :end_s <double>
     :max_duration_s <double>
     :truncated? <boolean>}"
  [:map
   [:session_id Uuid]
   [:start_s Sec]
   [:end_s Sec]
   [:max_duration_s Sec]
   [:truncated? :boolean]])

(def CreateSpeakerFromRecordingResponse
  "Response body for POST /api/speaker-enrollment/from-recording."
  (mu/merge
   CreateSpeakerResponse
   [:map
    [:clip SpeakerEnrollmentClipInfo]]))

(def DeleteSpeakerResponse
  "Response body for DELETE /api/speakers/{speaker_id}."
  [:map
   [:ok :boolean]
   [:speaker_id Uuid]])

(def SessionStartRequest
  "Start a session.
   tenant_id is required; user_id optional."
  [:map
   [:tenant_id Uuid]
   [:user_id {:optional true} Uuid]
   [:session_title {:optional true} :string]
   [:lang {:optional true} LangCode]])

(def SessionStartResponse
  "Returned to UI / clients."
  [:map
   [:session_id Uuid]
   [:session_key NonEmptyString]
   [:tenant_id Uuid]
   [:user_id {:optional true} Uuid]
   [:status [:enum "active" "finished" "failed"]]
   [:started_at_ms MsEpoch]])

(def RefinedCallbackRequest
  "WhisperX worker -> BFF callback payload.
   Can send either:
   - a single segment (start_s/end_s/text/...)
   - or multiple segments in :segments

   REQUIRED: :session_id, :tenant_id
   Optional: :user_id, :recording_id"
  [:map
   [:session_id NonEmptyString]
   [:tenant_id Uuid]
   [:user_id {:optional true} Uuid]
   [:recording_id {:optional true} Uuid]
   [:lang {:optional true} LangCode]

   ;; Option A: send one segment inline
   [:start_s {:optional true} Sec]
   [:end_s {:optional true} Sec]
   [:text {:optional true} :string]
   [:speaker {:optional true} SpeakerLabel]

   ;; Option B: send multiple
   [:segments {:optional true} [:sequential TranscriptSegment]]])

(def RefinedCallbackResponse
  "Acknowledgement from BFF to worker.
   Useful for debugging + retries."
  [:map
   [:ok :boolean]
   [:session_id NonEmptyString]
   [:applied_segments NonNegInt]
   [:message {:optional true} :string]])

;; ---------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------

(defn explain
  "Return a human-readable error map for schema validation failures."
  [schema value]
  (-> (m/explain schema value)
      (me/humanize)))

(defn validate!
  "Validate value against schema.
   Throws ex-info with :schema and :errors on failure."
  [schema value]
  (when-not (m/validate schema value)
    (throw (ex-info "Schema validation failed"
                    {:schema schema
                     :value value
                     :errors (explain schema value)})))
  value)

(def json-transformer
  "Transformer for common JSON coercions (strings->numbers etc.)."
  (mt/transformer
   mt/string-transformer
   mt/json-transformer))

(defn decode-and-validate!
  "Coerce + validate (useful for HTTP handlers).
   Returns coerced value, throws on failure."
  [schema value]
  (let [coerced (m/coerce schema value json-transformer)]
    (validate! schema coerced)))

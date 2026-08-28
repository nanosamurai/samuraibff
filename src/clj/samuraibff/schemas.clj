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
  [:enum "asr" "refined" "workflow_result" "status" "error"])

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
    [:track {:optional true} :string]
    [:primary_track {:optional true} :boolean]
    [:provider_profile_id {:optional true} :string]
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

(def WorkflowResultEvent
  "Workflow result event from BFF to UI.

  This is a lightweight view intended for live streaming.

  Inputs:
  - event map emitted by BFF from Kafka consumer / internal callback

  Payload:
  - workflow_id + optional workflow_name
  - trigger_type, status
  - render_markdown (optional, may be truncated)
  - created_at (string, runner timestamp)

  Security:
  - Must not include secrets."
  (mu/merge
   BaseWsEvent
   [:map
    [:type [:= "workflow_result"]]
    [:workflow_id Uuid]
    [:workflow_name {:optional true} [:maybe :string]]
    [:workflow_run_id {:optional true} [:maybe Uuid]]
    [:created_at {:optional true} [:maybe :string]]
    [:trigger_type {:optional true} [:maybe :string]]
    [:status :string]
    [:render_markdown {:optional true} [:maybe :string]]
    [:error_code {:optional true} [:maybe :string]]
    [:error_detail {:optional true} [:maybe :string]]]))

(def WsEvent
  "Union of all possible WS events."
  [:multi {:dispatch :type}
   ["asr" AsrEvent]
   ["refined" RefinedEvent]
   ["workflow_result" WorkflowResultEvent]
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
     :webhook_overrides <WebhookOverrides?>
     :workflow_overrides <WorkflowOverrides?>}

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

    ;; Generic session-scoped settings (webhook-agnostic).
    ;; This is intentionally a nested map so it can grow over time without
    ;; cluttering the top-level session create payload.
    ;;
    ;; v1 supported setting:
    ;; - {:refined_transcript {:consolidation {:enabled true|false}}}
   [:session_settings {:optional true}
    [:maybe
     [:map
      [:refined_transcript {:optional true}
       [:maybe
        [:map
         [:consolidation {:optional true}
          [:maybe
           [:map
            [:enabled {:optional true} :boolean]]]]]]]]]]

    ;; Workflow overrides (session-level binding at inception).
    ;; Mirrors webhook_overrides semantics (defaults + additions).
   [:workflow_overrides {:optional true}
    [:maybe
     [:map
      [:use_defaults {:optional true} :boolean]
      [:workflow_ids {:optional true} [:sequential Uuid]]]]]

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
;; Workflows (tenant-configured LLM post-processing definitions)
;; ---------------------------------------------------------------------

(def WorkflowTriggerType
  "Workflow trigger type string.

  v1 values:
  - transcript.refined.segment
  - recording.finished
  - transcript.final.ready"
  [:enum
   "transcript.refined.segment"
   "recording.finished"
   "transcript.final.ready"])

(def WorkflowTrigger
  "Workflow trigger configuration.

  Shape:
  - {:type <WorkflowTriggerType>}"
  [:map
   [:type WorkflowTriggerType]])

(def WorkflowProvider
  "Workflow provider configuration.

  v1 provider is bedrock.

  Shape:
  - {:type bedrock :model_id <string> :params <map?>}"
  [:map
   [:type [:enum "bedrock"]]
   [:model_id NonEmptyString]
   [:params {:optional true} [:maybe :map]]])

(def WorkflowPrompt
  "Workflow prompt.

  Shape:
  - {:text <string>}"
  [:map
   [:text NonEmptyString]])

(def WorkflowIncremental
  "Workflow refined-trigger behavior.

  This sub-object is named `incremental` for compatibility with webhook-router's
  `sessions.meta.workflows.targets[].incremental` contract.

  Semantics:
  - `enabled`: when true, the workflow wants to consume the *consolidated* refined
    transcript input (rolling tail) instead of per-window refined segments.
  - `min_interval_sec`: optional dispatch throttling interval (debounce) applied by
    webhook-router per (session_id, workflow_id) to avoid triggering the workflow
    too frequently.

  Shape:
  - {:enabled <boolean?> :min_interval_sec <int?>}"
  [:map
   [:enabled {:optional true} :boolean]
   [:min_interval_sec {:optional true} [:maybe NonNegInt]]])

(def CreateWorkflowRequest
  "Request body for POST /api/workflows.

  Shape:
  - {:name <string>
     :enabled <boolean?>
     :trigger {:type <WorkflowTriggerType>}
     :provider {:type bedrock :model_id <string> :params <map?>}
     :prompt {:text <string>}
     :incremental {:enabled <boolean?> :min_interval_sec <int?>}?}"
  [:map
   [:name NonEmptyString]
   [:enabled {:optional true} :boolean]
   [:trigger WorkflowTrigger]
   [:provider WorkflowProvider]
   [:prompt WorkflowPrompt]
   [:incremental {:optional true} [:maybe WorkflowIncremental]]])

(def UpdateWorkflowRequest
  "Request body for PUT /api/workflows/{id}.

  All fields optional; provided fields replace stored values."
  [:map
   [:name {:optional true} [:maybe :string]]
   [:enabled {:optional true} [:maybe :boolean]]
   [:trigger {:optional true} [:maybe WorkflowTrigger]]
   [:provider {:optional true} [:maybe WorkflowProvider]]
   [:prompt {:optional true} [:maybe WorkflowPrompt]]
   [:incremental {:optional true} [:maybe WorkflowIncremental]]])

(def WorkflowItem
  "Workflow list item returned by GET /api/workflows."
  [:map
   [:id Uuid]
   [:tenant_id Uuid]
   [:name :string]
   [:enabled :boolean]
   [:trigger WorkflowTrigger]
   [:provider WorkflowProvider]
   [:prompt WorkflowPrompt]
   [:incremental {:optional true} [:maybe WorkflowIncremental]]
   [:created_at :string]
   [:updated_at :string]])

(def WorkflowsListResponse
  "Response body for GET /api/workflows."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:items [:sequential WorkflowItem]]])

(def CreateWorkflowResponse
  "Response body for POST /api/workflows."
  [:map
   [:ok :boolean]
   [:workflow_id Uuid]])

(def WorkflowDefaultsRequest
  "Request body for PUT /api/workflows/defaults.

  Shape:
  - {:workflow_ids [<uuid> ...]}"
  [:map
   [:workflow_ids [:sequential Uuid]]])

(def WorkflowDefaultsResponse
  "Response body for GET /api/workflows/defaults."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:workflow_ids [:sequential Uuid]]])

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
   ;; Subscribed event types for this webhook.
   [:subscriptions [:sequential WebhookEventType]]
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

(def RealtimeTrackCapability
  "User-relevant capabilities for one operator-configured realtime track."
  [:map
   [:id NonEmptyString]
   [:available :boolean]
   [:provider_profile_id {:optional true} NonEmptyString]
   [:windowed_realtime {:optional true} :boolean]
   [:native_streaming {:optional true} :boolean]
   [:segment_timestamps {:optional true} :boolean]
   [:word_timestamps {:optional true} :boolean]
   [:speaker_labels {:optional true} :boolean]
   [:aligned_diarized_languages {:optional true} [:vector NonEmptyString]]
   [:language_detection {:optional true} :boolean]
   [:supported_languages {:optional true} [:vector NonEmptyString]]
   [:preferred_sample_rate {:optional true} :int]
   [:maximum_audio_seconds {:optional true} :double]
   [:maximum_concurrent_sessions {:optional true} :int]])

(def ApiMeResponse
  "Response body for GET /api/me.

  When authenticated:
  - authenticated=true and user info is present.

  When unauthenticated (only possible when auth is not required by config):
  - authenticated=false and no user/tenant_id is present."
  [:map
   [:ok :boolean]
   [:authenticated :boolean]
   [:realtime_tracks [:vector {:min 1 :max 4} NonEmptyString]]
   [:realtime_track_capabilities [:vector {:min 1 :max 4} RealtimeTrackCapability]]
   [:tenant_id {:optional true} Uuid]
   [:tenant_name {:optional true} :string]
   [:user {:optional true}
    [:map
     [:sub {:optional true} :string]
     [:preferred_username {:optional true} :string]
     [:email {:optional true} :string]]]
   [:features {:optional true}
    [:map
     [:ce_mode :boolean]
     [:workflow_webhook_runtime_enabled :boolean]
     [:webhooks_enabled :boolean]
     [:workflows_enabled :boolean]]]
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

(def WebhookDeliveryOutcomeItem
  "Latest webhook delivery outcome per dispatch_id (plus attempts count).

  This is used by:
  - GET /api/recordings/{session_id}
  - GET /api/sessions/{session_id}/webhook-delivery-outcomes

  Notes:
  - `attempts_count` is derived (max attempt_no) and represents how many delivery
    attempts were made for the dispatch.
  - `error_detail` may be present for failures; do not put secrets there."
  [:map
   [:id Uuid]
   [:created_at :string]
   [:webhook_id :string]
   [:webhook_name {:optional true} [:maybe :string]]
   [:dispatch_id Uuid]
   [:event_id {:optional true} [:maybe :string]]
   [:event_type :string]
   [:attempt_no NonNegInt]
   [:attempts_count NonNegInt]
   [:status :string]
   [:http_status {:optional true} [:maybe :int]]
   [:error_code {:optional true} [:maybe :string]]
   [:error_detail {:optional true} [:maybe :string]]
   [:latency_ms {:optional true} [:maybe :int]]])

(def RecordingDetailResponse
  "Response body for GET /api/recordings/{session_id}."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:session RecordingDetailSession]
   [:webhook_delivery_outcomes {:optional true} [:sequential WebhookDeliveryOutcomeItem]]
   [:workflow_results_latest {:optional true}
    [:sequential
     [:map
      [:workflow_id Uuid]
      [:workflow_name {:optional true} [:maybe :string]]
      [:workflow_run_id Uuid]
      [:created_at :string]
      [:status :string]
      [:trigger_type {:optional true} [:maybe :string]]
      [:provider_type {:optional true} [:maybe :string]]
      [:provider_model_id {:optional true} [:maybe :string]]
      [:usage_input_tokens {:optional true} [:maybe :int]]
      [:usage_output_tokens {:optional true} [:maybe :int]]
      [:stream_source_uri {:optional true} [:maybe :string]]
      [:stream_source_node_id {:optional true} [:maybe :string]]
      [:error_code {:optional true} [:maybe :string]]
      [:error_detail {:optional true} [:maybe :string]]
      [:render_markdown {:optional true} [:maybe :string]]]]]
   [:transcripts
    [:map
     [:refined [:sequential TranscriptRecord]]
     [:final [:sequential TranscriptRecord]]]]])

(def WebhookDeliveryOutcomesResponse
  "Response body for GET /api/sessions/{session_id}/webhook-delivery-outcomes."
  [:map
   [:ok :boolean]
   [:tenant_id Uuid]
   [:session_id Uuid]
   [:items [:sequential WebhookDeliveryOutcomeItem]]])

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

(def FinishSessionResponse
  "Response body for POST /api/sessions/{session_id}/finish.

  Shape:
  - {:ok true :session_id <uuid> :status \"finished\"}

  Notes:
  - This endpoint is used by UI to explicitly transition session state machine
    when the user stops recording."
  [:map
   [:ok [:= true]]
   [:session_id Uuid]
   [:status [:= "finished"]]])

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
   [:speaker_id Uuid]
   ;; Best-effort cleanup of S3 enrollment data (LocalStack may restart and lose
   ;; buckets/objects). When cleanup fails, the speaker DB record may still be
   ;; deleted and we surface this for observability.
   [:s3_deleted_objects {:optional true} NonNegInt]
   [:s3_delete_failed {:optional true} :boolean]])

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

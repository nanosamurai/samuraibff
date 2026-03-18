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
   [:speaker {:optional true} SpeakerLabel]])

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
  - {:session_id <uuid>}" 
  [:map
   [:session_id Uuid]])

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
     [:lang [:maybe :string]]
     ]]]])

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
   [:created_at [:maybe :string]]])

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

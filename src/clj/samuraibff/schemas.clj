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

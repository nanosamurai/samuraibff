(ns samuraibff.track-schemas
  "Public asynchronous catalog and result schemas, kept separate from legacy contracts.")

(def Uuid "Canonical UUID string." [:re #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])
(def NonEmptyString "Non-empty public identifier." [:string {:min 1}])
(def AsyncTrackChoice
  "A permitted configured choice; it makes no worker-health assertion."
  [:map
   [:stage [:enum "refined" "final"]]
   [:track_id NonEmptyString]
   [:profile_id NonEmptyString]
   [:display_name :string]
   [:primary :boolean]
   [:default_selected :boolean]])

(def TrackResultMetadata
  "Stable result identity with safe status and actual output capabilities."
  [:map
   [:result_id Uuid] [:run_id Uuid]
   [:track_id NonEmptyString] [:profile_id NonEmptyString]
   [:stage [:enum "refined" "final"]] [:unit_id :string] [:revision :int]
   [:status [:enum "succeeded" "failed"]]
   [:error_code [:maybe :string]]
   [:capabilities [:map [:segment_timestamps :boolean] [:word_timestamps :boolean] [:speaker_labels :boolean]]]
   [:degradations [:vector :string]]])

(def TrackIndexResponse
  "Frozen per-stage choices and their bounded result metadata."
  [:map [:ok :boolean] [:has_recording :boolean] [:session_status [:maybe :string]]
   [:tracks [:vector {:max 8}
             [:map [:stage [:enum "refined" "final"]]
              [:track_id NonEmptyString] [:profile_id NonEmptyString]
              [:display_name :string] [:primary :boolean]
              [:results [:vector {:max 60} TrackResultMetadata]]]]]])

(def TrackWord
  "An actual provider-timed word."
  [:map [:text :string] [:start_s :double] [:end_s :double]])

(def TrackSegment
  "A normalized segment with optional timing and speaker data."
  [:map [:text :string]
   [:start_s {:optional true} :double] [:end_s {:optional true} :double]
   [:speaker {:optional true} :string]
   [:words {:optional true} [:vector TrackWord]]])

(def TrackResultResponse
  "One outcome with an optional successful transcript."
  [:map [:ok :boolean] [:result TrackResultMetadata]
   [:transcript {:optional true}
    [:map [:full_text :string] [:lang :string] [:segments [:vector TrackSegment]]]]])

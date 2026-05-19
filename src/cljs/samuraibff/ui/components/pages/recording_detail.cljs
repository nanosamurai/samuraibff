(ns samuraibff.ui.components.pages.recording-detail
  "Recording detail page.

  Shows cached realtime ASR (from local store), refined segments (from DB + cached WS),
  and final transcript (from DB) with optional audio playback + karaoke highlighting."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.transcript :as components.transcript]
   [samuraibff.ui.recording-detail :as recording-detail]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]
   [samuraibff.ui.webhook-delivery-outcomes :as ui.wh.outcomes]
   [samuraibff.ui.workflow-results :as ui.wf.results]
   ["react" :as react]))

(defn- enroll-speaker-modal
  "Modal for enrolling a speaker from a final transcript bubble.

  Inputs:
  - {:keys [open? session-id start-s end-s on-close]}
    - open?: boolean
    - session-id: string
    - start-s/end-s: numbers (seconds)
    - on-close: (fn [])

  Behavior:
  - Lets user enter speaker label and submits to backend
  - On success, prepends speaker into `store/speakers*`

  Returns: hiccup node or nil."
  [{:keys [open? session-id start-s end-s on-close]}]
  (let [open? (true? open?)
        label* (react/useState "")
        label (aget label* 0)
        set-label! (aget label* 1)
        saving?* (react/useState false)
        saving? (aget saving?* 0)
        set-saving! (aget saving?* 1)
        error* (react/useState nil)
        error (aget error* 0)
        set-error! (aget error* 1)
        close! (fn []
                 (set-error! nil)
                 (set-saving! false)
                 (set-label! "")
                 (when (fn? on-close) (on-close)))
        submit! (fn []
                  (set-saving! true)
                  (set-error! nil)
                  (-> (api/create-speaker-from-recording!
                       {:session-id session-id
                        :start-s start-s
                        :end-s end-s
                        :label label})
                      (.then (fn [{:keys [speaker_id label]}]
                               ;; Keep the speakers management page in sync.
                               (store/prepend-speaker!
                                {:id speaker_id
                                 :label label
                                 :created_at_ms (.getTime (js/Date.))})
                               (close!)))
                      (.catch (fn [e]
                                (store/append-log! (str "[ui] failed enrolling speaker from recording: " (shared/safe-http-error e)))
                                (set-error! (shared/safe-http-error e))))
                      (.finally (fn []
                                  (set-saving! false)))))]
    (when open?
      [:div {:class "modal-overlay"
             :on-click (fn [_] (close!))}
       [:div {:class "modal"
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "modal-title"} "Enroll speaker"]
        [:div {:class "muted" :style {:marginBottom "10px"}}
         (str "This will clip audio from the recording (" (util/fmt-sec start-s)
              " → " (util/fmt-sec end-s) ") and create a new enrolled speaker.")]

        [:div {:class "row" :style {:marginBottom "10px"}}
         [:input {:placeholder "Speaker name (e.g. Dr Novak)"
                  :value label
                  :disabled saving?
                  :on-change (fn [e]
                               (set-label! (.. e -target -value)))}]
         [:button {:class "btn primary"
                   :disabled (or saving? (str/blank? (str label)))
                   :on-click (fn [_] (submit!))}
          (if saving? "Saving…" "Enroll")]
         [:button {:class "btn"
                   :disabled saving?
                   :on-click (fn [_] (close!))}
          "Cancel"]]

        (when (seq (str error))
          [:div {:class "badge bad"} (str error)])]])))

(defn- title-editor
  "Inline session title editor.

  Inputs:
  - session-id: string
  - current-title: string?
  - on-saved: (fn [new-title] ...) (optional)

  Behavior:
  - Calls `api/rename-session!`
  - Updates recordings list state via `store/update-recording-db-title!`
  - Updates live session form title via `store/set-session-title!` when editing the active live session

  Returns: hiccup."
  [{:keys [session-id current-title on-saved]}]
  (let [editing?* (react/useState false)
        editing? (aget editing?* 0)
        set-editing! (aget editing?* 1)

        draft* (react/useState (str (or current-title "")))
        draft (aget draft* 0)
        set-draft! (aget draft* 1)

        saving?* (react/useState false)
        saving? (aget saving?* 0)
        set-saving! (aget saving?* 1)

        error* (react/useState nil)
        error (aget error* 0)
        set-error! (aget error* 1)

        start-edit! (fn []
                      (set-error! nil)
                      (set-draft! (str (or current-title "")))
                      (set-editing! true))

        cancel! (fn []
                  (set-error! nil)
                  (set-editing! false))

        save! (fn []
                (set-saving! true)
                (set-error! nil)
                (-> (api/rename-session! session-id draft)
                    (.then (fn [{:keys [title]}]
                             (store/update-recording-db-title! session-id title)
                             (when (= (or session-id "") (or (get @store/session* :id) ""))
                               (store/set-session-title! (or title "")))
                             (when (fn? on-saved)
                               (on-saved title))
                             (set-editing! false)))
                    (.catch (fn [e]
                              (store/append-log! (str "[ui] failed renaming session: " e))
                              (set-error! (or (some-> e .-message str)
                                              "Failed renaming session"))))
                    (.finally (fn []
                                (set-saving! false)))))]

    [:div {:style {:display "flex" :flexDirection "column" :gap "8px"}}
     (if editing?
       [:div {:class "row"}
        [:input {:value draft
                 :placeholder "Session name"
                 :disabled saving?
                 :on-change (fn [e]
                              (set-draft! (.. e -target -value)))}]
        [:button {:class "btn primary"
                  :disabled saving?
                  :on-click (fn [_] (save!))}
         (if saving? "Saving…" "Save")]
        [:button {:class "btn"
                  :disabled saving?
                  :on-click (fn [_] (cancel!))}
         "Cancel"]]
       [:button {:class "btn"
                 :on-click (fn [_] (start-edit!))}
        "Edit title"])

     (when (seq (str error))
       [:div {:class "badge bad"} (str error)])]))

;; NOTE: refined event conversion helpers live in shared CLJC namespace
;; `samuraibff.ui.recording-detail` so we can unit-test them from CLJ.

(defn- dedupe-by
  "De-dupe a sequence by key function, preserving the first seen item.

  Arity:
  - (dedupe-by key-fn xs) => vector
  - (dedupe-by key-fn)    => (fn [xs] ...) for ->> pipelines

  Inputs:
  - key-fn: (fn [x] k)
  - xs: seq

  Returns: vector."
  ([key-fn]
   (fn [xs] (dedupe-by key-fn xs)))
  ([key-fn xs]
   (->> (or xs [])
        (reduce (fn [acc x]
                  (let [k (key-fn x)]
                    (if (contains? acc k) acc (assoc acc k x))))
                {})
        vals
        vec)))

(defn- on-time->current-time-s
  "Read the currentTime (seconds) from a React timeupdate event.

  Inputs:
  - e: React synthetic event emitted by <audio>

  Returns: double (>=0)."
  [e]
  (let [t (some-> e .-target .-currentTime)]
    (max 0.0 (double (or t 0.0)))))

(defn- final-segments->messages
  "Convert final transcript segments (from DB json) into transcript messages.

  Inputs:
  - segments: vector of {:start_s number :end_s number :text string ...}

  Returns: vector of transcript messages."
  [segments]
  (mapv (fn [seg]
          {:kind "final"
           :seq 0
           :ts_ms 0
           :start_s (:start_s seg)
           :end_s (:end_s seg)
           :text (:text seg)
           ;; Optional word-level timing for karaoke highlighting.
           :words (:words seg)
           :speaker (:speaker seg)
           :lang (:lang seg)})
        (vec (or segments []))))

(defn- final-audio-player
  "Render audio player for finalized transcript playback.

  Inputs:
  - session-id: string
  - enabled?: boolean
  - audio-ref: React ref
  - on-time: (fn [event] ...) (optional)
  - on-play: (fn [event] ...) (optional)
  - on-pause: (fn [event] ...) (optional)
  - on-ended: (fn [event] ...) (optional)

  Returns: hiccup."
  [{:keys [session-id enabled? audio-ref on-time on-play on-pause on-ended]}]
  (let [url (api/recording-audio-url session-id)
        on-time (or on-time (fn [_] nil))
        on-play (or on-play (fn [_] nil))
        on-pause (or on-pause (fn [_] nil))
        on-ended (or on-ended (fn [_] nil))]
    [:div {:class "card"}
     [:div {:class "card-title"} "Playback"]
     (if (and (true? enabled?) (seq (str session-id)))
       [:audio {:controls true
                :preload "metadata"
                :src url
                ;; Help some browsers with Range.
                :crossOrigin "anonymous"
                :ref audio-ref
                :on-time-update on-time
                :on-seeked on-time
                :on-play on-play
                ;; Some browsers dispatch "playing" more reliably than "play".
                :on-playing on-play
                :on-pause on-pause
                :on-ended on-ended
                :style {:width "100%"}}]
       [:div {:class "muted"}
        "Audio playback not available (no recording or no final transcript)."])]))

;; NOTE: moved to `samuraibff.ui.recording-detail/db-refined-records->events`.

(defn recording-detail-page
  "Recording detail page.

  Inputs:
  - props: map with keys:
      - :session-id string

  Returns: hiccup."
  [{:keys [session-id]}]
  (let [tab* (react/useState nil)
        tab (aget tab* 0)
        set-tab! (aget tab* 1)

        right-tab* (react/useState :workflows)
        right-tab (aget right-tab* 0)
        set-right-tab! (aget right-tab* 1)

        audio-ref (react/useRef nil)
        current-time* (react/useState 0.0)
        current-time-s (aget current-time* 0)
        set-current-time! (aget current-time* 1)
        ;; Default to no auto-follow; otherwise the UI feels like it fights the user.
        follow?* (react/useState false)
        follow? (aget follow?* 0)
        set-follow! (aget follow?* 1)
        show-workflows?* (react/useState true)
        show-workflows? (aget show-workflows?* 0)
        set-show-workflows! (aget show-workflows?* 1)

        loading* (react/useState true)
        loading? (aget loading* 0)
        set-loading! (aget loading* 1)

        detail* (react/useState nil)
        detail (aget detail* 0)
        set-detail! (aget detail* 1)

        cached-asr (store/cached-asr-segments session-id)
        cached-refined (store/cached-refined-segments session-id)

        refresh! (fn []
                   (set-loading! true)
                   (-> (api/get-recording! session-id)
                       (.then (fn [resp]
                                (set-detail! resp)))
                       (.catch (fn [e]
                                 (store/append-log! (str "[ui] failed loading recording detail: " e))
                                 (set-detail! {:ok false :message "failed"})))
                       (.finally (fn [] (set-loading! false)))))

        db-refined (get-in detail [:transcripts :refined])
        db-final (get-in detail [:transcripts :final])
        refined-events (recording-detail/db-refined-records->events db-refined)

        current-title (get-in detail [:session :title])
        title-display (let [t (str/trim (str (or current-title "")))]
                        (when (seq t) t))

        created-at-ms (or (util/iso->ms (get-in detail [:session :created_at]))
                          (util/now-ms))
        title-display* (or title-display
                           (util/default-session-title created-at-ms)
                           "Recording")

        session-status (str (or (get-in detail [:session :status]) ""))
        status-label (cond
                       (= session-status "active") "Recording"
                       (seq session-status) (str/capitalize session-status)
                       :else "Unknown")
        status-kind (cond
                      (= session-status "active") :warn
                      (= session-status "failed") :bad
                      (= session-status "finished") :ok
                      :else :muted)
        status-tooltip (str "Session status: " (or (seq session-status) "unknown"))

        on-title-saved (fn [new-title]
                         (set-detail! (fn [prev]
                                        (assoc-in (or prev {}) [:session :title] new-title))))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [session-id])

    ;; Build 3 independent feeds:
    ;; - realtime ASR (cached locally if available)
    ;; - refined realtime (DB refined records + cached refined WS items if available)
    ;; - final transcript (DB)
    (let [enroll-open?* (react/useState false)
          enroll-open? (aget enroll-open?* 0)
          set-enroll-open! (aget enroll-open?* 1)
          enroll-range* (react/useState nil)
          enroll-range (aget enroll-range* 0)
          set-enroll-range! (aget enroll-range* 1)
          open-enroll! (fn [{:keys [start_s end_s]}]
                         (set-enroll-range! {:start_s start_s :end_s end_s})
                         (set-enroll-open! true))
          close-enroll! (fn []
                          (set-enroll-open! false)
                          (set-enroll-range! nil))

          realtime-msgs (transcript/sort-messages (vec (or cached-asr [])))
          refined-msgs (->> (concat (recording-detail/refined-events->messages refined-events)
                                    (vec (or cached-refined [])))
                            ;; De-dupe refined segments by stable content/time key.
                            ;; :seq is not stable across DB vs WS.
                            (dedupe-by transcript/refined-dedupe-key)
                            transcript/sort-messages
                            vec)

          ;; Final transcript: take the last record and render its segments.
          final-record (last (vec (or db-final [])))
          final-msgs (final-segments->messages (vec (or (:segments final-record) [])))

          available-tabs
          (recording-detail/available-transcript-tabs
           {:realtime-msgs realtime-msgs
            :refined-msgs refined-msgs
            :final-msgs final-msgs})

          default-tab (recording-detail/default-transcript-tab available-tabs)

          selected-tab (let [allowed? (contains? (set (or available-tabs [])) tab)]
                         (cond
                           allowed? tab
                           (some? default-tab) default-tab
                           :else nil))

          ;; Playback is only shown when we have both:
          ;; - a recording stored
          ;; - a final transcript stored
          playback-enabled? (boolean (and final-record
                                          (true? (get-in detail [:session :has_recording]))))

          karaoke-enabled? (boolean (and playback-enabled?
                                         (seq final-msgs)
                                         (some (fn [m] (seq (:words m))) final-msgs)))

          on-audio-time (fn [e]
                          (set-current-time! (on-time->current-time-s e)))

          final-body
          [:div {:style {:display "flex" :flexDirection "column" :gap "12px"}}
           [final-audio-player {:session-id session-id
                                :enabled? playback-enabled?
                                :audio-ref audio-ref
                                :on-time on-audio-time}]

           (when karaoke-enabled?
             [:div {:class "row" :style {:marginTop "-4px"}}
              [:label {:class "muted"
                       :style {:display "inline-flex" :gap "8px" :alignItems "center"}}
               [:input {:type "checkbox"
                        :checked (boolean follow?)
                        :on-change (fn [e]
                                     (set-follow! (.. e -target -checked)))}]
               "Follow"]
              [:span {:class "muted"}
               (str "t=" (util/fmt-sec current-time-s))]])

           (let [enroll-action
                 (fn [{:keys [msg]}]
                   (when (and (= "final" (:kind msg))
                              (number? (:start_s msg))
                              (number? (:end_s msg))
                              (> (double (:end_s msg)) (double (:start_s msg))))
                     [:div {:class "bubble-actions"}
                      [:button {:class "bubble-action-btn"
                                :title "Enroll speaker from this segment"
                                :on-click (fn [e]
                                            (.stopPropagation e)
                                            (open-enroll! {:start_s (:start_s msg)
                                                           :end_s (:end_s msg)}))}
                       (shared/icon "＋" {:title "Enroll"})]]))]
             (if karaoke-enabled?
               [components.transcript/final-transcript-karaoke
                {:messages final-msgs
                 :audio-ref audio-ref
                 :current-time-s current-time-s
                 :follow? follow?
                 :message-actions enroll-action}]
               [components.transcript/transcript-view
                {:messages final-msgs
                 :auto-scroll? false
                 :initial-scroll :top
                 :empty-title "Final transcript"
                 :empty-hint (if final-record "(no segments)" "No final transcript stored")
                 :message-actions enroll-action}]))]]

      (react/useEffect
       (fn []
         ;; Ensure selected tab always points to a visible tab.
         ;;
         ;; This handles two cases:
         ;; 1) page default (tab starts as nil) => select preferred available tab
         ;; 2) user-selected tab becomes unavailable after refresh => fallback
         (let [tab-id tab
               next-tab selected-tab]
           (when (not= tab-id next-tab)
             (set-tab! next-tab)))
         js/undefined)
       #js [tab selected-tab])

      [:div {:class "page"}
       [enroll-speaker-modal {:open? enroll-open?
                              :session-id session-id
                              :start-s (get enroll-range :start_s)
                              :end-s (get enroll-range :end_s)
                              :on-close close-enroll!}]
       [:div {:class "page-header"}
        [:div
         [:div {:class "page-title"}
          title-display*
          [:span {:style {:marginLeft "10px"}}
           [shared/status-pill {:label status-label
                                :kind status-kind
                                :blink? (= session-status "active")
                                :tooltip status-tooltip}]]]
         [:div {:class "mono muted"} session-id]
         (when loading?
           [:div {:class "muted"} "Loading…"])]

        [:div {:class "row"}
         [router/link {:route {:page :recordings :params {}}
                       :class "btn"}
          "Back to recordings"]

         (when (= session-status "created")
           [router/link {:route {:page :live :params {}}
                         :class "btn"
                         :title "Record with this session"
                         :on-click (fn [_]
                                     (store/set-session-id! session-id)
                                     (store/set-session-created-at-ms! created-at-ms)
                                     (store/set-session-title!
                                      (or (some-> current-title str str/trim not-empty)
                                          (util/default-session-title created-at-ms)
                                          ""))
                                     (store/set-session-status! session-status))}
            "Record with this session"])

         [title-editor {:session-id session-id
                        :current-title current-title
                        :on-saved on-title-saved}]

         [:button {:class "btn"
                   :on-click (fn [_] (refresh!))}
          "Refresh"]]]

       [:div {:class "tabs"}
        (when (seq available-tabs)
          (for [tab-id available-tabs]
            ^{:key (str "tab-" (name tab-id))}
            [:button {:class (str "tab " (when (= tab tab-id) "active"))
                      :type "button"
                      :on-click (fn [_] (set-tab! tab-id))}
             (case tab-id
               :realtime "Real-time transcript"
               :refined "Refined real-time"
               :final "Final transcript"
               (name tab-id))]))
        [:div {:class "spacer"}]
        [:button {:class "btn ghost icon"
                  :type "button"
                  :aria-label (if show-workflows?
                                "Hide workflows panel"
                                "Show workflows panel")
                  :title (if show-workflows?
                           "Hide workflows panel"
                           "Show workflows panel")
                  :on-click (fn [_]
                              (set-show-workflows! (not show-workflows?)))}
         (shared/icon (if show-workflows? "❯" "❮")
                      {:title (if show-workflows?
                                "Hide workflows panel"
                                "Show workflows panel")})]]

       (if show-workflows?
         [:div {:class "split"}
          [:div {:class "split-main"}
           [:div {:class "card"}
            (if (empty? available-tabs)
              [:div {:class "muted"}
               "No transcripts available for this recording."]
              [:div
               [:div {:class "card-title"}
                (case selected-tab
                  :refined "Refined real-time"
                  :final "Final"
                  "Real-time")]
               (case selected-tab
                 :final final-body
                 :refined [components.transcript/transcript-view
                           {:messages refined-msgs
                            :empty-title "Refined real-time"
                            :empty-hint "No refined transcript available"}]
                 [components.transcript/transcript-view
                  {:messages realtime-msgs
                   :empty-title "Real-time transcript"
                   :empty-hint "No realtime transcript available"}])])]]

          [:div {:class "split-side"}
           [:div {:class "right-panel"}
            [:div {:class "tabs"}
             [:button {:class (str "tab " (when (= right-tab :workflows) "active"))
                       :type "button"
                       :on-click (fn [_] (set-right-tab! :workflows))}
              "Workflows"]
             [:button {:class (str "tab " (when (= right-tab :webhooks) "active"))
                       :type "button"
                       :on-click (fn [_] (set-right-tab! :webhooks))}
              "Webhooks"]]

            [:div {:class "right-panel-body"}
             (case right-tab
               :workflows
               [ui.wf.results/workflow-results-card
                {:items (vec (or (:workflow_results_latest detail) []))
                 :title "Workflow results"
                 :fill? true
                 :empty-hint "No workflow results recorded for this recording."}]

               [ui.wh.outcomes/webhook-dispatches-card
                {:items (vec (or (:webhook_delivery_outcomes detail) []))
                 :title "Webhook dispatches"
                 :fill? true}])]]]]

         [:div {:class "card"}
          (if (empty? available-tabs)
            [:div {:class "muted"}
             "No transcripts available for this recording."]
            [:div
             [:div {:class "card-title"}]
             (case selected-tab
               :refined "Refined real-time"
               :final "Final"
               "Real-time")
             (case selected-tab
               :final final-body
               :refined [components.transcript/transcript-view
                         {:messages refined-msgs
                          :empty-title "Refined real-time"
                          :empty-hint "No refined transcript available"}]
               [components.transcript/transcript-view
                {:messages realtime-msgs
                 :empty-title "Real-time transcript"
                 :empty-hint "No realtime transcript available"}])])])])))

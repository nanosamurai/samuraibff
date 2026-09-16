(ns samuraibff.ui.components.pages.recording-detail
  "Recording detail page.

  Shows cached realtime ASR (from local store), refined segments (from DB + cached WS),
  and final transcript (from DB) with optional audio playback + karaoke highlighting."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.transcript :as components.transcript]
   [samuraibff.ui.recording-detail :as recording-detail]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
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

(defn- on-time->current-time-s
  "Read the currentTime (seconds) from a React timeupdate event.

  Inputs:
  - e: React synthetic event emitted by <audio>

  Returns: double (>=0)."
  [e]
  (let [t (some-> e .-target .-currentTime)]
    (max 0.0 (double (or t 0.0)))))

(defn- final-audio-player
  "Render audio player for finalized transcript playback.

  Inputs:
  - session-id: string
  - enabled?: boolean
  - follow-enabled?: boolean; show Follow when the selected final has word timing
  - follow?: boolean; whether playback scrolls the transcript
  - on-follow: checkbox change callback
  - audio-ref: React ref
  - on-time: (fn [event] ...) (optional)
  - on-play: (fn [event] ...) (optional)
  - on-pause: (fn [event] ...) (optional)
  - on-ended: (fn [event] ...) (optional)

  Returns: hiccup."
  [{:keys [session-id enabled? audio-ref on-time on-play on-pause on-ended
           follow-enabled? follow? on-follow]}]
  (let [url (api/recording-audio-url session-id)
        on-time (or on-time (fn [_] nil))
        on-play (or on-play (fn [_] nil))
        on-pause (or on-pause (fn [_] nil))
        on-ended (or on-ended (fn [_] nil))]
    [:div {:class "card"}
     [:div {:class "row playback-header"}
      [:div {:class "card-title"} "Playback"]
      (when follow-enabled?
        [:label {:class "checkbox-row"}
         [:input {:type "checkbox" :checked follow? :on-change on-follow}]
         "Follow"])]
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
        "Audio playback not available (no stored recording)."])]))

(defn recording-detail-page
  "Render flat per-track Postgres results and shared recording playback for a session ID."
  [{:keys [session-id]}]
  (let [[detail set-detail!] (react/useState nil)
        [error set-error!] (react/useState nil)
        [tab set-tab!] (react/useState nil)
        [current-time-s set-time!] (react/useState 0)
        [follow? set-follow!] (react/useState false)
        [enroll-range set-enroll!] (react/useState nil)
        [show-workflows? set-show-workflows!] (react/useState false)
        [right-tab set-right-tab!] (react/useState :workflows)
        audio-ref (react/useRef nil)
        runtime-enabled? (store/workflow-webhook-runtime-enabled?)
        cached-asr (get (hooks/use-atom store/asr-by-session*) session-id [])
        tabs (cond-> (recording-detail/track-tabs detail)
               (seq cached-asr) (into [{:id [:realtime] :label "Real-time Transcript"}]))
        selected (or (some #(when (= tab (:id %)) %) tabs)
                     (first (filter #(= :final (first (:id %))) tabs)) (first tabs))
        stage (first (:id selected))
        rows (:rows selected)
        messages (if (= stage :realtime) cached-asr (recording-detail/record-messages rows stage))
        playback? (true? (get-in detail [:session :has_recording]))
        karaoke? (and (= stage :final) playback? (some #(seq (:words %)) messages))
        session (:session detail)
        status (:status session)
        created-at-ms (util/iso->ms (:created_at session))
        title (or (not-empty (str/trim (or (:title session) "")))
                  (util/default-session-title created-at-ms) "Recording")
        refresh! (fn []
                   (set-error! nil)
                   (-> (api/get-recording! session-id)
                       (.then set-detail!)
                       (.catch #(set-error! (shared/safe-http-error %)))))
        enroll-action (fn [{:keys [msg]}]
                        (when (and playback? (seq (:speaker msg))
                                   (number? (:start_s msg)) (number? (:end_s msg))
                                   (< (:start_s msg) (:end_s msg)))
                          [:div {:class "bubble-actions"}
                           [:button {:class "bubble-action-btn" :title "Enroll speaker from this segment"
                                     :on-click #(do (.stopPropagation %) (set-enroll! msg))}
                            (shared/icon "＋" {:title "Enroll"})]]))]
    (react/useEffect
     (fn []
       (let [active? (atom true)
             load! (fn []
                     (-> (api/get-recording! session-id)
                         (.then #(when @active? (set-detail! %)))
                         (.catch #(when @active? (set-error! (shared/safe-http-error %))))))
             timer (js/setInterval load! 4000)]
         (set-detail! nil)
         (set-tab! nil)
         (set-error! nil)
         (load!)
         (fn [] (reset! active? false) (js/clearInterval timer))))
     #js [session-id])
    [:div {:class "page"}
     [enroll-speaker-modal {:open? (some? enroll-range) :session-id session-id
                            :start-s (:start_s enroll-range) :end-s (:end_s enroll-range)
                            :on-close #(set-enroll! nil)}]
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} title
        [:span {:style {:marginLeft "10px"}}
         [shared/status-pill {:label (if (= status "active") "Recording" (str/capitalize (or status "Loading")))
                              :kind :muted :tooltip "Session status does not describe every track's outcome."}]]]
       [:div {:class "mono muted"} session-id]]
      [:div {:class "row"}
       [router/link {:route {:page :recordings :params {}} :class "btn"} "Back to recordings"]
       (when (= status "created")
         [router/link {:route {:page :live :params {}} :class "btn"
                       :on-click #(do (store/set-session-id! session-id)
                                      (store/set-session-title! (:title session))
                                      (store/set-session-created-at-ms! created-at-ms)
                                      (store/set-session-status! status))}
          "Record with this session"])
       [title-editor {:session-id session-id :current-title (:title session)
                      :on-saved #(set-detail! (fn [old] (assoc-in old [:session :title] %)))}]
       [:button {:class "btn" :on-click refresh!} "Refresh"]]]
     (when error [:p {:role "alert" :class "badge bad"} error])
     [:div {:class "tabs transcript-tabs" :role "tablist" :aria-label "Transcripts"}
      (for [{:keys [id label]} tabs]
        [:button {:key (pr-str id) :class (str "tab " (when (= id (:id selected)) "active"))
                  :type "button" :role "tab" :aria-selected (= id (:id selected))
                  :on-click #(do
                               (when (not= :final (first id))
                                 (when-let [audio (.-current audio-ref)] (.pause audio))
                                 (set-time! 0))
                               (set-tab! id))} label])
      (when runtime-enabled?
        [:button {:class "btn ghost" :on-click #(set-show-workflows! (not show-workflows?))}
         "Workflows / Webhooks"])]
     [:div {:class "split"}
      [:div {:class "split-main"}
       (when (= stage :final)
         [final-audio-player {:session-id session-id :enabled? playback? :audio-ref audio-ref
                              :on-time #(set-time! (on-time->current-time-s %))
                              :follow-enabled? karaoke? :follow? follow?
                              :on-follow #(set-follow! (.. % -target -checked))}])
       [:div {:class "card" :role "tabpanel" :aria-label (:label selected)}
        (if selected
          [:div
           [:p {:class "muted" :role "status"}
            (cond (= stage :realtime) "Live results cached in this browser; realtime text is not stored."
                  (empty? rows) "No result available yet. This does not establish whether the track is processing or failed."
                  (= stage :refined) (str (count rows) " saved window(s) available. More windows may still arrive.")
                  :else "Saved result available.")]
           (if karaoke?
             [components.transcript/final-transcript-karaoke
              {:key (pr-str (:id selected)) :messages messages :audio-ref audio-ref
               :current-time-s current-time-s :follow? follow? :message-actions enroll-action}]
             [components.transcript/transcript-view
              {:key (pr-str (:id selected)) :messages messages :auto-scroll? false :initial-scroll :top
               :empty-title (:label selected)
               :empty-hint (if (seq rows) "The stored result contains no speech." "No result available yet.")
               :message-actions enroll-action}])]
          [:p {:class "muted"} (if detail "No saved transcript tracks for this session." "Loading…")])]]
      (when (and runtime-enabled? show-workflows?)
        [:div {:class "split-side"}
         [:div {:class "right-panel"}
          [:div {:class "tabs"}
           (for [[id label] [[:workflows "Workflows"] [:webhooks "Webhooks"]]]
             [:button {:key id :class (str "tab " (when (= id right-tab) "active"))
                       :on-click #(set-right-tab! id)} label])]
          [:div {:class "right-panel-body"}
           (if (= right-tab :workflows)
             [ui.wf.results/workflow-results-card {:items (:workflow_results_latest detail) :title "Workflow results" :fill? true}]
             [ui.wh.outcomes/webhook-dispatches-card {:items (:webhook_delivery_outcomes detail) :title "Webhook dispatches" :fill? true}])]]])]]))

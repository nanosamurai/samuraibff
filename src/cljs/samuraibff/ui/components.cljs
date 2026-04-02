(ns samuraibff.ui.components
  "HSX (hiccup-style) React components for the SPA.

  Components here are plain React function components compiled via HSX.
  State is kept in plain CLJS atoms in `samuraibff.ui.store` and consumed
  from React via `samuraibff.ui.hooks`.

  This file intentionally contains only UI rendering logic; side effects
  (ws/audio/fetch) should remain in their dedicated namespaces."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.langs :as langs]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.audio :as audio]
   [samuraibff.ui.auth :as auth]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.layout :as components.layout]
   [samuraibff.ui.components.pages.live :as components.pages.live]
   [samuraibff.ui.components.pages.recording-detail :as components.pages.recording-detail]
   [samuraibff.ui.components.pages.recordings :as components.pages.recordings]
   [samuraibff.ui.components.pages.speakers :as components.pages.speakers]
   [samuraibff.ui.components.pages.api-credentials :as components.pages.api-credentials]
   [samuraibff.ui.components.transcript :as components.transcript]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.karaoke :as karaoke]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.api-credentials-store :as api-creds.store]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]
   [samuraibff.ui.ws :as ws]
   ["react" :as react]))

(defn- lang-option->search-haystack
  "Build a lowercase search string for a language option.

  Inputs:
  - opt: {:value string :label string :flag string}

  Returns: string."
  [{:keys [value label]}]
  (-> (str (or value "") " " (or label ""))
      str/lower-case))

(defn searchable-dropdown
  "A lightweight searchable dropdown.

  This is used for language selection on Live Recording.

  Inputs:
  - value: currently selected option value (string)
  - options: vector of options {:value string :label string :flag string}
  - placeholder: string shown when no matching option is found
  - on-change: (fn [new-value] ...)

  Returns: hiccup."
  [{:keys [value options placeholder on-change]}]
  (let [open?* (react/useState false)
        open? (aget open?* 0)
        set-open! (aget open?* 1)

        query* (react/useState "")
        query (aget query* 0)
        set-query! (aget query* 1)

        root-ref (react/useRef nil)
        search-ref (react/useRef nil)

        value (str (or value ""))
        placeholder (or placeholder "Select...")

        opts (vec (or options []))
        selected (or (first (filter (fn [o] (= (str (:value o)) value)) opts))
                     (first opts)
                     {:value value :label value :flag "🏳"})

        q (-> (str (or query "")) str/trim str/lower-case)
        visible-opts (if (str/blank? q)
                       opts
                       (->> opts
                            (filter (fn [o]
                                      (str/includes? (lang-option->search-haystack o) q)))
                            vec))]

    ;; Close on outside click.
    (react/useEffect
     (fn []
       (let [handler (fn [e]
                       (when (and (true? open?)
                                  (some? (.-current root-ref)))
                         (let [root (.-current root-ref)
                               target (.-target e)]
                           (when (and root (not (.contains root target)))
                             (set-open! false)
                             (set-query! "")))))]
         (.addEventListener js/document "mousedown" handler)
         (fn []
           (.removeEventListener js/document "mousedown" handler))))
     #js [open?])

    ;; Focus search when opening.
    (react/useEffect
     (fn []
       (when (and (true? open?) (some? (.-current search-ref)))
         (try
           (.focus (.-current search-ref))
           (catch :default _ nil)))
       js/undefined)
     #js [open?])

    (let [trigger
          [:button {:type "button"
                    :class "dropdown-trigger"
                    :on-click (fn [_]
                                (set-open! (not open?)))}
           [:span {:class "dropdown-flag"} (or (:flag selected) "")]
           [:span {:class "dropdown-label"} (or (:label selected) placeholder)]
           [:span {:class "dropdown-caret"} "v"]]

          menu
          (when open?
            [:div {:class "dropdown-menu"
                   :on-key-down (fn [e]
                                  (when (= "Escape" (.-key e))
                                    (set-open! false)
                                    (set-query! "")))}
             [:div {:class "dropdown-search"}
              [:input {:ref search-ref
                       :value query
                       :placeholder "Search..."
                       :on-change (fn [e]
                                    (set-query! (.. e -target -value)))}]]
             [:div {:class "dropdown-items"}
              (if (empty? visible-opts)
                [:div {:class "dropdown-empty muted"} "No matches"]
                (for [{:keys [value label flag]} visible-opts]
                  [:button {:type "button"
                            :key (str "opt-" value)
                            :class (str "dropdown-item"
                                        (when (= (str value) (str (:value selected))) " active"))
                            :on-click (fn [_]
                                        (when (fn? on-change)
                                          (on-change (str value)))
                                        (set-open! false)
                                        (set-query! ""))}
                   [:span {:class "dropdown-flag"} (or flag "")]
                   [:span {:class "dropdown-item-label"} (or label (str value))]
                   [:span {:class "dropdown-item-code muted"} (or value "")]]))]])]

      [:div {:class (str "dropdown" (when open? " open"))
             :ref root-ref}
       trigger
       menu])))

(defn- iso->local
  "Best-effort formatting of an ISO timestamp into a local date/time string."
  [s]
  (when (seq (str s))
    (try
      (.toLocaleString (js/Date. s))
      (catch :default _
        (str s)))))

(defn- refined-events->messages
  "Convert refined events (with start/end/text) into transcript messages."
  [events]
  (->> (or events [])
       (mapv transcript/normalize-refined)
       transcript/sort-messages))

(defn- dedupe-by
  "De-dupe a sequence by key function, preserving the first seen item.

  Arity:
  - (dedupe-by key-fn xs) => vector
  - (dedupe-by key-fn)    => (fn [xs] ...) convenience for ->> pipelines

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
  "Convert final transcript segments (from DB json) into transcript messages."
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

(defn- status-dot-class
  "Translate websocket status keyword to CSS class name."
  [status]
  (case status
    :connected "dot ok"
    :error "dot bad"
    "dot"))

(defn ws-indicator
  "Render a small status widget for events/audio websockets.

  This is used in the right-side panel (debug)."
  []
  (let [ws-status (hooks/use-atom store/ws-status*)
        events-status (get-in ws-status [:events :status])
        audio-status (get-in ws-status [:audio :status])]
    [:div {:class "ws-indicator"}
     [:div {:class "pill"}
      [:span {:class (status-dot-class events-status)}]
      [:span {:class "muted"} "events:"]
      [:span (name events-status)]]
     [:div {:class "pill"}
      [:span {:class (status-dot-class audio-status)}]
      [:span {:class "muted"} "audio:"]
      [:span (name audio-status)]]]))

(defn controls
  "Session controls (create session, set lang, start/stop).

  This is the top strip of the Live Recording page."
  []
  (let [{:keys [id lang]} (hooks/use-atom store/session*)
        running? (hooks/use-atom store/running?*)]
    [:div {:class "controls"}
     [:div {:class "controls-row"}
      [:button {:class "btn"
                :on-click (fn [_]
                            (store/append-log! "[ui] creating session...")
                            (-> (api/create-session! {:title (get @store/session* :title "")})
                                (.then (fn [{:keys [session_id title]}]
                                         (store/set-session-id! session_id)
                                         (store/set-session-title! (or title ""))
                                         (store/add-recording! {:session_id session_id
                                                                :created_at_ms (util/now-ms)
                                                                :status :ready})
                                         (store/append-log! (str "[ui] new session " session_id))))
                                (.catch (fn [e]
                                          (store/append-log! (str "[ui] failed creating session: " e))))))}
       "New session"]

      [:div {:class "field"}
       [:div {:class "label"} "Session name"]
       [:input {:value (or (get @store/session* :title) "")
                :placeholder "Untitled session"
                :on-change (fn [e]
                             (store/set-session-title! (.. e -target -value)))}]
       (when (seq (str id))
         [:div {:class "hint"}
          [:span {:class "mono"} id]])]

      [:div {:class "field"}
       [:div {:class "label"} "Language"]
       [shared/searchable-dropdown
        {:value (or lang "")
         :options (langs/language-options)
         :placeholder "Auto"
         :on-change (fn [new-val]
                      (store/set-lang! new-val))}]]

      [:div {:class "spacer"}]

      [:button {:class "btn primary"
                :disabled (or running? (empty? (str id)))
                :on-click (fn [_]
                            ;; Starting a new capture run; reset transcript time base.
                            (store/clear-segments!)
                            (store/set-running! true)
                            (store/set-recording-status! id :recording)
                            (store/append-log! "[ui] start")
                            (ws/connect-events! id)
                            (-> (audio/start-audio! id lang)
                                (.catch (fn [_]
                                          ;; audio will log; ensure running resets
                                          (store/set-running! false)
                                          (store/set-recording-status! id :ready)))))}
       "Start"]

      [:button {:class "btn"
                :disabled (not running?)
                :on-click (fn [_]
                            (store/append-log! "[ui] stop")
                            (store/set-running! false)
                            (store/set-recording-status! id :stopped)
                            (audio/stop-audio!)
                            (ws/close-events!))}
       "Stop"]

      [:button {:class "btn ghost"
                :on-click (fn [_] (store/clear-segments!))}
       "Clear transcript"]

      [:button {:class "btn ghost"
                :on-click (fn [_] (store/clear-log!))}
       "Clear log"]]]))

(defn- message-key
  [idx msg]
  (str "msg-" idx "-" (:seq msg) "-" (:ts_ms msg)))

(defn- badge
  [{:keys [kind final]}]
  (cond
    (= kind "refined")
    [:span {:class "badge refined"} "★ refined"]

    (and (= kind "asr") (false? final))
    [:span {:class "badge muted typing"
            :title "partial"}
     [:span {:class "typing-dots"}
      [:span]]]

    :else
    nil))

(defn transcript-view
  "Transcript display (Slack-like feed).

  Renders transcript as a message thread:
  - avatar
  - speaker name
  - timestamp
  - message bubble

  Refined messages are visually marked (★ refined).

  Note:
  - We no longer merge refined segments into realtime ASR in the UI.
  - Each tab renders its own message stream."
  [{:keys [messages empty-title empty-hint auto-scroll? initial-scroll]}]
  (let [msgs (->> (or messages [])
                  transcript/coalesce-asr-finals
                  vec)
        container-ref (react/useRef nil)
        ;; Auto-scroll unless the user scrolled up.
        ;; NOTE: for some views (e.g. final transcript playback) we disable this.
        auto-scroll? (if (some? auto-scroll?) (boolean auto-scroll?) true)
        initial-scroll (or initial-scroll :bottom)
        auto-scroll?* (react/useRef true)
        initial-scrolled?* (react/useRef false)]

    (react/useEffect
     (fn []
       (when-let [el (.-current container-ref)]
         (when (and auto-scroll? (true? (.-current auto-scroll?*)))
           (set! (.-scrollTop el) (.-scrollHeight el)))

         ;; One-time initial positioning for non-auto-scrolling views.
         (when (and (not auto-scroll?) (false? (.-current initial-scrolled?*)))
           (case initial-scroll
             :top (set! (.-scrollTop el) 0)
             :bottom (set! (.-scrollTop el) (.-scrollHeight el))
             nil)
           (set! (.-current initial-scrolled?*) true)))
       js/undefined)
     #js [(count msgs) auto-scroll? initial-scroll])

    [:div {:class "transcript"}
     (if (empty? msgs)
       [:div {:class "empty"}
        [:div {:class "empty-title"} (or empty-title "Transcript")]
        [:div {:class "muted"} (or empty-hint "No events yet…")]]
       [:div (cond-> {:class "transcript-feed"
                      :ref container-ref}
               auto-scroll?
               (assoc :on-scroll
                      (fn [e]
                        (let [el (.-target e)
                              dist (- (.-scrollHeight el)
                                      (.-scrollTop el)
                                      (.-clientHeight el))]
                          (set! (.-current auto-scroll?*) (<= dist 48))))))
        (for [[idx msg] (map-indexed vector msgs)]
          (let [k (message-key idx msg)
                speaker (:speaker msg)
                who (transcript/speaker->display-name speaker)
                avatar (transcript/speaker->avatar-text speaker)
                start-ts (util/fmt-sec (:start_s msg))
                end-ts (util/fmt-sec (:end_s msg))
                bubble-class (str "bubble" (when (and (= "asr" (:kind msg)) (false? (:final msg))) " draft"))]
            [:div {:class "msg" :key k}
             [:div {:class "avatar"} avatar]
             [:div {:class "msgBody"}
              [:div {:class "msgHeader"}
               [:span {:class "who"} who]
               [:span {:class "ts"} (str start-ts " → " end-ts)]
               (badge msg)]
              [:div {:class bubble-class} (:text msg)]]]))])]))

(defn- final-audio-player
  "Render audio player for finalized transcript playback.

  Inputs:
  - session-id: string
  - enabled?: boolean

  Returns: hiccup"
  [{:keys [session-id enabled? audio-ref on-time]}]
  (let [url (api/recording-audio-url session-id)
        on-time (or on-time (fn [_] nil))]
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
                :style {:width "100%"}}]
       [:div {:class "muted"}
        "Audio playback not available (no recording or no final transcript)."])]))

(defn live-transcript
  "Transcript component bound to the live session store."
  []
  [components.transcript/transcript-view
   {:messages (hooks/use-atom store/asr-segments*)
    :empty-title "Real-time transcript"
    :empty-hint "No ASR events yet…"}])

(defn refined-live-transcript
  "Refined realtime transcript component bound to the live session store."
  []
  [components.transcript/transcript-view
   {:messages (hooks/use-atom store/refined-segments*)
    :empty-title "Refined real-time"
    :empty-hint "No refined events yet…"}])

(defn log-view
  "Debug log view."
  []
  (let [lines (->> (hooks/use-atom store/log*)
                   (take-last 160))]
    [:div {:class "log"}
     (if (empty? lines)
       [:span {:class "muted"} "(empty)"]
       (for [[idx line] (map-indexed vector lines)]
         [:div {:class "log-line" :key (str "log-" idx)} line]))]))

(defn- format-status
  [status]
  (case status
    :ready "Ready"
    :recording "Recording"
    :stopped "Stopped"
    (name status)))

(defn- rec->display-status
  "Derive a UI status descriptor for a DB recording row.

  Inputs:
  - rec: map from /api/recordings with keys:
      :status (string)
      :has_recording (boolean)
      :has_final_transcript (boolean)

  Returns:
  - {:label string :badge-class string :icon string :title string}

  Notes:
  - We deliberately fold \"finalized\" into status so the table can stay compact.
  - Sessions without recordings are treated as \"Created\" (draft)."
  [{:keys [status has_recording has_final_transcript]}]
  (let [status (some-> status str)
        has-recording? (true? has_recording)
        has-final? (true? has_final_transcript)]
    (cond
      (not has-recording?)
      {:label "Created"
       :badge-class "muted"
       :icon "○"
       :title "Session created but recording never started"}

      (= status "failed")
      {:label "Failed"
       :badge-class "bad"
       :icon "✗"
       :title "Session failed"}

      has-final?
      {:label "Finalized"
       :badge-class "ok"
       :icon "✓"
       :title "Final transcript is available"}

      (= status "active")
      {:label "Recording"
       :badge-class "warn"
       :icon "●"
       :title "Recording in progress"}

      :else
      {:label "Processing"
       :badge-class "muted"
       :icon "…"
       :title "Recording stopped; final transcript not available yet"})))

(defn- icon
  "Render a lightweight icon glyph (no external dependencies).

  Inputs:
  - s: string
  - opts: optional map {:title string}

  Returns: hiccup"
  ([s] (icon s nil))
  ([s {:keys [title]}]
   [:span {:class "icon" :title title} (or s "")]))

(defn- recordings-row
  "Render a single recordings table row.

  Inputs:
  - rec: a map from /api/recordings

  Returns: hiccup <tr>"
  [{:keys [session_id title started_at created_at] :as rec}]
  (let [{:keys [label badge-class title]
         icon-glyph :icon} (rec->display-status rec)]
    [:tr
     [:td
      [:div {:style {:display "flex" :flexDirection "column" :gap "2px"}}
       [:div (or title session_id)]
       [:div {:class "hint"}
        [:span {:class "mono"} session_id]]]]
     [:td {:class "muted"} (or (iso->local created_at) "")]
     [:td {:class "muted"} (or (iso->local started_at) "")]
     [:td
      [:span {:class (str "badge " badge-class)
              :title title}
       (icon icon-glyph {:title title})
       [:span {:style {:marginLeft "8px"}} label]]]
     [:td {:style {:textAlign "right"}}
      [:div {:class "row"}
       [router/link {:route {:page :recording :params {:session_id session_id}}
                     :class "btn"
                     :title "Open detail"}
        (icon "↗" {:title "Open"})]

       [router/link {:route {:page :live :params {}}
                     :class "btn ghost"
                     :title "Open in Live Recording"
                     :on-click (fn [_]
                                 (store/set-session-id! session_id))}
        (icon "●" {:title "Go live"})]

       [:button {:class "btn ghost"
                 :title "Delete session"
                 :on-click (fn [_]
                             (when (js/confirm (str "Delete session " session_id
                                                    "?\n\nThis will remove recordings and transcripts."))
                               (-> (api/delete-recording! session_id)
                                   (.then (fn [_]
                                            (store/remove-recording-db! session_id)))
                                   (.catch (fn [e]
                                             (store/append-log!
                                              (str "[ui] failed deleting session: " e)))))))}
        (icon "×" {:title "Delete"})]]]]))

(defn recordings-table
  "Table of DB-backed recordings."
  []
  (let [recs0 (->> (hooks/use-atom store/recordings-db*)
                   (sort-by :created_at)
                   reverse)
        show-drafts?* (react/useState false)
        show-drafts? (aget show-drafts?* 0)
        set-show-drafts! (aget show-drafts?* 1)
        recs (if show-drafts?
               (vec recs0)
               (vec (remove (fn [r] (false? (:has_recording r))) recs0)))
        drafts-count (count (filter (fn [r] (false? (:has_recording r))) recs0))]
    [:div {:class "card"}
     [:div {:class "row" :style {:alignItems "center"}}
      [:div {:class "card-title"} "Recordings"]
      [:div {:class "spacer"}]
      (when (pos? drafts-count)
        [:label {:class "muted"
                 :style {:display "inline-flex" :gap "8px" :alignItems "center"}}
         [:input {:type "checkbox"
                  :checked (boolean show-drafts?)
                  :on-change (fn [e]
                               (set-show-drafts! (.. e -target -checked)))}]
         (str "Show drafts (" drafts-count ")")])]

     (if (empty? recs)
       [:div {:class "muted"} "No recordings yet."]
       [:table {:class "table"}
        [:thead
         [:tr
          [:th "Session"]
          [:th "Created"]
          [:th "Started"]
          [:th "Status"]
          [:th {:style {:textAlign "right"}} "Actions"]]]
        [:tbody
         (for [{:keys [session_id] :as rec} recs]
           ^{:key (str "rec-" session_id)}
           [recordings-row rec])]])]))

(defn recordings-page
  "Recordings page."
  []
  (let [loading?* (react/useState false)
        loading? (aget loading?* 0)
        set-loading! (aget loading?* 1)
        refresh! (fn []
                   (set-loading! true)
                   (-> (api/list-recordings!)
                       (.then (fn [resp]
                                (store/set-recordings-db! (:items resp))))
                       (.catch (fn [e]
                                 (store/append-log! (str "[ui] failed loading recordings: " e))))
                       (.finally (fn [] (set-loading! false)))))]
    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Recordings"]
       [:div {:class "muted"} "All sessions and recordings."]]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn primary"
                 :on-click (fn [_]
                             (store/append-log! "[ui] creating session...")
                             (-> (api/create-session! {:title (get @store/session* :title "")})
                                 (.then (fn [{:keys [session_id title]}]
                                          (store/set-session-id! session_id)
                                          (store/set-session-title! (or title ""))
                                          (store/add-recording! {:session_id session_id
                                                                 :created_at_ms (util/now-ms)
                                                                 :status :ready})
                                          (router/navigate! {:page :live :params {}})
                                          (store/append-log! (str "[ui] new session " session_id))))
                                 (.catch (fn [e]
                                           (store/append-log! (str "[ui] failed creating session: " e))))))}
        "New live session"]]]
     [recordings-table]]))

(defn- speaker-row
  [{:keys [id label created_at created_at_ms]} on-delete]
  [:tr
   [:td {:class "mono"} id]
   [:td label]
   [:td {:class "muted"} (or created_at (when created_at_ms (.toLocaleString (js/Date. created_at_ms))) "")]
   [:td {:style {:textAlign "right"}}
    [:button {:class "btn ghost"
              :on-click (fn [_]
                          (on-delete id))}
     "Delete"]]])

(defn speakers-page
  "Enrolled speakers management page."
  []
  (let [items (hooks/use-atom store/speakers*)
        label* (react/useState "")
        file* (react/useState nil)
        label (aget label* 0)
        set-label! (aget label* 1)
        sample (aget file* 0)
        set-sample! (aget file* 1)
        loading?* (react/useState false)
        loading? (aget loading?* 0)
        set-loading! (aget loading?* 1)
        refresh! (fn []
                   (set-loading! true)
                   (-> (api/list-speakers!)
                       (.then (fn [xs]
                                (store/set-speakers! (js->clj xs :keywordize-keys true))))
                       (.catch (fn [e]
                                 (store/append-log! (str "[ui] failed loading speakers: " e))))
                       (.finally (fn [] (set-loading! false)))))
        delete! (fn [sid]
                  (when (js/confirm (str "Delete speaker " sid "?"))
                    (-> (api/delete-speaker! sid)
                        (.then (fn [_]
                                 (store/remove-speaker! sid)))
                        (.catch (fn [e]
                                  (store/append-log! (str "[ui] failed deleting speaker: " e)))))))]
    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Speakers"]
       [:div {:class "muted"} "Manage enrolled speakers for diarization."]]
      [:div {:class "row"}
       [:button {:class "btn"
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Add speaker"]
      [:div {:class "row"}
       [:input {:placeholder "Label (e.g. Dr Novak)"
                :value label
                :on-change (fn [e]
                             (set-label! (.. e -target -value)))}]
       [:input {:type "file"
                :accept "audio/wav"
                :on-change (fn [e]
                             (let [f (aget (.. e -target -files) 0)]
                               (set-sample! f)))}]
       [:button {:class "btn primary"
                 :disabled (or loading? (str/blank? label) (nil? sample))
                 :on-click (fn [_]
                             (set-loading! true)
                             (-> (api/create-speaker! label sample)
                                 (.then (fn [resp]
                                          (store/prepend-speaker!
                                           {:id (aget resp "speaker_id")
                                            :label (aget resp "label")
                                            :created_at_ms (.getTime (js/Date.))})
                                          (set-label! "")
                                          (set-sample! nil)))
                                 (.catch (fn [e]
                                           (store/append-log! (str "[ui] failed creating speaker: " e))))
                                 (.finally (fn [] (set-loading! false)))))}
        "Upload"]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Enrolled speakers"]
      (if (empty? items)
        [:div {:class "muted"} "No speakers enrolled yet."]
        [:table {:class "table"}
         [:thead
          [:tr
           [:th "Id"]
           [:th "Label"]
           [:th "Created"]
           [:th {:style {:textAlign "right"}} "Actions"]]]
         [:tbody
          (for [item items]
            ^{:key (str "speaker-" (:id item))}
            [speaker-row item delete!])]])]]))

(defn recording-detail-page
  "Recording detail page.

  Features:
  - Real-time transcript tab: cached realtime ASR (if available locally)
  - Refined real-time tab: refined segments from DB, plus cached refined WS (if available locally)
  - Final transcript tab: final transcript records from DB
  - Hideable log panel (cached locally only)"
  [session-id]
  (let [tab* (react/useState :realtime)
        tab (aget tab* 0)
        set-tab! (aget tab* 1)
        audio-ref (react/useRef nil)
        current-time* (react/useState 0.0)
        current-time-s (aget current-time* 0)
        set-current-time! (aget current-time* 1)
        ;; Default to no auto-follow; otherwise the UI feels like it fights the user.
        follow?* (react/useState false)
        follow? (aget follow?* 0)
        set-follow! (aget follow?* 1)
        show-log?* (react/useState true)
        show-log? (aget show-log?* 0)
        set-show-log! (aget show-log?* 1)

        loading* (react/useState true)
        loading? (aget loading* 0)
        set-loading! (aget loading* 1)

        detail* (react/useState nil)
        detail (aget detail* 0)
        set-detail! (aget detail* 1)

        cached-asr (store/cached-asr-segments session-id)
        cached-refined (store/cached-refined-segments session-id)
        cached-log-lines (store/cached-log session-id)

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

        ;; Convert DB refined transcript records to "refined" events.
        refined-events
        (let [records (vec (or db-refined []))]
          (reduce
           (fn [events r]
             (let [segments (vec (or (:segments r) []))]
               (reduce
                (fn [events [idx seg]]
                  ;; IMPORTANT:
                  ;; - One DB transcript record may contain many segments.
                  ;; - These segments all share the same :event_created_at_ns.
                  ;; - If we used that value as :seq for every segment and then
                  ;;   de-duped by :seq, we'd collapse to one bubble.
                  (conj events {:seq (+ (long (or (:event_created_at_ns r) 0)) (long idx))
                                :ts_ms 0
                                :start_s (:start_s seg)
                                :end_s (:end_s seg)
                                :text (:text seg)
                                :speaker (:speaker seg)
                                :lang (:lang seg)}))
                events
                (map-indexed vector segments))))
           []
           records))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [session-id])

    ;; Build 3 independent feeds:
    ;; - realtime ASR (cached locally if available)
    ;; - refined realtime (DB refined records + cached refined WS items if available)
    ;; - final transcript (DB)
    (let [realtime-msgs (transcript/sort-messages (vec (or cached-asr [])))
          refined-msgs (->> (concat (refined-events->messages refined-events)
                                    (vec (or cached-refined [])))
                            ;; De-dupe refined segments by stable content/time key.
                            ;; :seq is not stable across DB vs WS.
                            (dedupe-by transcript/refined-dedupe-key)
                            transcript/sort-messages
                            vec)

          ;; Final transcript: take the last record and render its segments (or full_text).
          final-record (last (vec (or db-final [])))
          final-msgs (final-segments->messages (vec (or (:segments final-record) [])))

          ;; Playback is only shown when we have both:
          ;; - a recording stored
          ;; - a final transcript stored
          playback-enabled? (boolean (and final-record
                                          (true? (get-in detail [:session :has_recording]))))

          karaoke-enabled? (boolean (and playback-enabled?
                                         (seq final-msgs)
                                         (some (fn [m] (seq (:words m))) final-msgs)))

          on-audio-time
          (fn [e]
            (set-current-time! (on-time->current-time-s e)))]

      [:div {:class "page"}
       [:div {:class "page-header"}
        [:div
         [:div {:class "page-title"} "Recording"]
         [:div {:class "mono muted"} session-id]
         (when loading?
           [:div {:class "muted"} "Loading…"])]
        [:div {:class "row"}
         [router/link {:route {:page :recordings :params {}}
                       :class "btn"}
          "Back to recordings"]
         [router/link {:route {:page :live :params {}}
                       :class "btn ghost"
                       :on-click (fn [_] (store/set-session-id! session-id))}
          "Open in Live Recording"]
         [:button {:class "btn"
                   :on-click (fn [_] (refresh!))}
          "Refresh"]]]

       [:div {:class "tabs"}
        [:button {:class (str "tab " (when (= tab :realtime) "active"))
                  :on-click (fn [_] (set-tab! :realtime))}
         "Real-time transcript"]
        [:button {:class (str "tab " (when (= tab :refined) "active"))
                  :on-click (fn [_] (set-tab! :refined))}
         "Refined real-time"]
        [:button {:class (str "tab " (when (= tab :final) "active"))
                  :on-click (fn [_] (set-tab! :final))}
         "Final transcript"]
        [:div {:class "spacer"}]
        [:button {:class "btn ghost"
                  :on-click (fn [_] (set-show-log! (not show-log?)))}
         (if show-log? "Hide log" "Show log")]]

       (if show-log?
         [:div {:class "grid-2"}
          [:div {:class "card"}
           [:div {:class "card-title"}
            (case tab
              :refined "Refined real-time"
              :final "Final"
              "Real-time")]
           (case tab
             :final [:div {:style {:display "flex" :flexDirection "column" :gap "12px"}}
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

                     (if karaoke-enabled?
                       [components.transcript/final-transcript-karaoke
                        {:messages final-msgs
                         :audio-ref audio-ref
                         :current-time-s current-time-s
                         :follow? follow?}]
                       [components.transcript/transcript-view
                        {:messages final-msgs
                         :auto-scroll? false
                         :initial-scroll :top
                         :empty-title "Final transcript"
                         :empty-hint (if final-record "(no segments)" "No final transcript stored")}])]
             :refined [components.transcript/transcript-view]
             {:messages refined-msgs
              :empty-title "Refined real-time"
              :empty-hint "No refined transcript available"}
             [components.transcript/transcript-view
              {:messages realtime-msgs
               :empty-title "Real-time transcript"
               :empty-hint "No realtime transcript available"}])]

          [:div {:class "card"}
           [:div {:class "card-title"} "Log"]
           (if (seq cached-log-lines)
             [:div {:class "log"}
              (for [[idx line] (map-indexed vector cached-log-lines)]
                [:div {:class "log-line" :key (str "logc-" idx)} line])]
             [:div {:class "muted"} "No log available for this session (not persisted)."])]]

         [:div {:class "card"}
          [:div {:class "card-title"}
           (case tab
             :refined "Refined real-time"
             :final "Final"
             "Real-time")]
          (case tab
            :final [:div {:style {:display "flex" :flexDirection "column" :gap "12px"}}
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
                        "Follow"]])
                    (if karaoke-enabled?
                      [components.transcript/final-transcript-karaoke
                       {:messages final-msgs
                        :audio-ref audio-ref
                        :current-time-s current-time-s
                        :follow? follow?}]
                      [components.transcript/transcript-view
                       {:messages final-msgs
                        :auto-scroll? false
                        :initial-scroll :top
                        :empty-title "Final transcript"
                        :empty-hint (if final-record "(no segments)" "No final transcript stored")}])]
            :refined [components.transcript/transcript-view]
            {:messages refined-msgs
             :empty-title "Refined real-time"
             :empty-hint "No refined transcript available"}
            [components.transcript/transcript-view
             {:messages realtime-msgs
              :empty-title "Real-time transcript"
              :empty-hint "No realtime transcript available"}]
            [transcript-view {:messages realtime-msgs
                              :empty-title "Real-time transcript"
                              :empty-hint "No realtime transcript available"}])])])))

(defn right-panel
  "Right-side panel for Live Recording.

  Contains tabs (for now: Log only)."
  []
  (let [active :log
        debug-asr? (hooks/use-atom store/debug-asr-log?*)]
    [:div {:class "right-panel"}
     [:div {:class "tabs"}
      [:button {:class (str "tab " (when (= active :log) "active"))}
       "Log"]]
     [:div {:class "right-panel-body"}
      [ws-indicator]
      [:label {:class "muted"
               :style {:display "inline-flex"
                       :gap "8px"
                       :alignItems "center"
                       :margin "8px 0"}}
       [:input {:type "checkbox"
                :checked (boolean debug-asr?)
                :on-change (fn [e]
                             (store/set-debug-asr-log! (.. e -target -checked)))}]
       "Log ASR events"]
      [log-view]]]))

(defn live-recording-page
  "Live Recording page."
  []
  (let [tab* (react/useState :realtime)
        tab (aget tab* 0)
        set-tab! (aget tab* 1)]
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Live Recording"]
       [:div {:class "muted"} "Capture audio and view the live transcript."]]
      [:div {:class "row"}
       [router/link {:route {:page :recordings :params {}}
                     :class "btn"}
        "Recordings"]]]

     [controls]

     [:div {:class "tabs"}
      [:button {:class (str "tab " (when (= tab :realtime) "active"))
                :on-click (fn [_] (set-tab! :realtime))}
       "Real-time transcript"]
      [:button {:class (str "tab " (when (= tab :refined) "active"))
                :on-click (fn [_] (set-tab! :refined))}
       "Refined real-time"]
      [:div {:class "spacer"}]]

     [:div {:class "split"}
      [:div {:class "split-main"}
       (case tab
         :refined [refined-live-transcript]
         [live-transcript])]
      [:div {:class "split-side"}
       [right-panel]]]]))

(defn- sidebar-item
  [{:keys [active? label route]}]
  [router/link {:route route
                :class (str "nav-item" (when active? " active"))}
   [:span {:class "nav-label"} label]])

(defn sidebar
  "Left navigation sidebar."
  [route]
  (let [page (:page route)]
    [:aside {:class "sidebar"}
     [:div {:class "sidebar-section"}
      [:div {:class "sidebar-title"} "Navigation"]
      [sidebar-item {:label "Recordings"
                     :route {:page :recordings :params {}}
                     :active? (= page :recordings)}]
      [sidebar-item {:label "Live Recording"
                     :route {:page :live :params {}}
                     :active? (= page :live)}]
      [sidebar-item {:label "Speakers"
                     :route {:page :speakers :params {}}
                     :active? (= page :speakers)}]
      [sidebar-item {:label "API Credentials"
                     :route {:page :api-credentials :params {}}
                     :active? (= page :api-credentials)}]]]))

(defn breadcrumbs
  "Breadcrumbs derived from current route."
  [route]
  (let [{:keys [page params]} route
        crumbs (case page
                 :recordings [{:label "Recordings" :route {:page :recordings :params {}}}]
                 :live [{:label "Recordings" :route {:page :recordings :params {}}}
                        {:label "Live Recording" :route {:page :live :params {}}}]
                 :speakers [{:label "Speakers" :route {:page :speakers :params {}}}]
                 :api-credentials [{:label "API Credentials" :route {:page :api-credentials :params {}}}]
                 :recording [{:label "Recordings" :route {:page :recordings :params {}}}
                             {:label (or (:session_id params) "Recording")
                              :route {:page :recording :params params}}]
                 [{:label "Recordings" :route {:page :recordings :params {}}}])]
    [:div {:class "breadcrumbs"}
     (for [[idx c] (map-indexed vector crumbs)]
       [:span {:class "crumb" :key (str "crumb-" idx "-" (:label c))}
        (when (pos? idx)
          [:span {:class "sep"} "/"])
        [router/link {:route (:route c) :class "crumb-link"}
         (:label c)]])]))

(defn topbar
  "Top application bar (logo + product name + breadcrumbs)."
  [route]
  (let [{:keys [status detail]} (hooks/use-atom store/auth*)
        user (get detail :user)
        tenant-name (get detail :tenant_name)]
    [:header {:class "topbar"}
     [:div {:class "brand"}
      [:img {:class "logo" :src "/img/nonosamurai_art.jpg" :alt "nanosamur.ai"}]
      [:div {:class "brand-name"} "nanosamur.ai"]]
     [breadcrumbs route]
     [:div {:class "topbar-right"}
      (cond
        (= status :loading)
        [:span {:class "muted"} "auth: loading…"]

        (= status :authenticated)
        [:div {:class "row"}
         (when (seq (str tenant-name))
           [:span {:class "badge muted"} (str tenant-name)])
         [:span {:class "badge ok"}
          (or (:preferred_username user) (:email user) (:sub user) "user")]
         [:button {:class "btn"
                   :on-click (fn [_]
                               (-> (auth/logout!)
                                   (.then (fn [_] (auth/fetch-me!)))))}
          "Logout"]]
        :else
        [:div {:class "row"}
         [:span {:class "badge muted"} "anonymous"]
         [:button {:class "btn primary"
                   :on-click (fn [_]
                               (auth/login! (router/route->href route)))}
          "Login"]])]]))

(defn- safe-http-error
  "Return a safe string to show/log for fetch errors.

  Important:
  - never include response bodies (may contain secrets)
  - never include stack traces

  Inputs:
  - e: JS error

  Returns: string."
  [e]
  (let [msg (some-> e .-message str)]
    (if (seq msg) msg "Request failed")))

(defn- copy-to-clipboard!
  "Copy text to clipboard (best effort).

  Inputs:
  - s: string

  Returns:
  - Promise resolving to true/false."
  [s]
  (let [s (str (or s ""))]
    (cond
      (and (exists? js/navigator)
           (exists? (.-clipboard js/navigator))
           (exists? (.-writeText (.-clipboard js/navigator))))
      (-> (.writeText (.-clipboard js/navigator) s)
          (.then (fn [_] true))
          (.catch (fn [_] false)))

      :else
      (js/Promise.resolve false))))

(defn api-credentials-secret-modal
  "Modal that shows `client_secret` exactly once.

  Security:
  - The secret is kept only in memory (store atom) and cleared on close."
  []
  (let [st (hooks/use-atom store/api-credentials*)
        {:keys [open? credential-id client-id client-secret copied?]} (:secret-modal st)]
    (when (true? open?)
      [:div {:class "modal-overlay"
             :on-click (fn [_]
                         (store/api-credentials-close-secret!))}
       [:div {:class "modal"
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "modal-title"} "Client secret"]
        [:div {:class "muted" :style {:marginBottom "10px"}}
         "This secret is shown only once. Copy it now and store it securely."
         (when credential-id
           [:div {:class "muted" :style {:marginTop "6px"}}
            [:span {:class "mono"} credential-id]])]

        [:div {:class "card" :style {:marginBottom "10px"}}
         [:div {:class "muted" :style {:fontSize "12px" :marginBottom "6px"}} "client_id"]
         [:div {:class "mono" :style {:wordBreak "break-all"}} (or client-id "")]]

        [:div {:class "card" :style {:marginBottom "12px"}}
         [:div {:class "muted" :style {:fontSize "12px" :marginBottom "6px"}} "client_secret"]
         [:div {:class "mono" :style {:wordBreak "break-all"}} (or client-secret "")]]

        [:div {:class "row" :style {:justifyContent "flex-end"}}
         [:button {:class (str "btn " (when copied? "primary"))
                   :on-click (fn [_]
                               (-> (copy-to-clipboard! (or client-secret ""))
                                   (.then (fn [ok?]
                                            (store/api-credentials-mark-secret-copied! (true? ok?))))))}
          (if copied? "Copied" "Copy secret")]
         [:button {:class "btn"
                   :on-click (fn [_]
                               (store/api-credentials-close-secret!))}
          "Close"]]]])))

(defn- credential-revoked?
  [cred]
  (some? (or (:revoked_at cred) (:revoked-at cred))))

(defn- api-credentials-row
  [{:keys [id name keycloak_client_id created_at last_used_at revoked_at]} refresh!]
  (let [revoked? (some? revoked_at)
        id (or id "")
        client-id (or keycloak_client_id "")]
    [:tr
     [:td name]
     [:td {:class "mono"} client-id]
     [:td {:class "muted"} (or (iso->local created_at) "")]
     [:td {:class "muted"} (or (iso->local last_used_at) "")]
     [:td
      (if revoked?
        [:span {:class "badge muted"} "Revoked"]
        [:span {:class "badge ok"} "Active"])]
     [:td {:style {:textAlign "right"}}
      [:div {:class "row" :style {:justifyContent "flex-end"}}
       [:button {:class "btn"
                 :disabled revoked?
                 :title "Rotate secret"
                 :on-click (fn [_]
                             (when (js/confirm (str "Rotate secret for " name "?\n\nThe old secret will stop working."))
                               (store/api-credentials-set-loading! true)
                               (store/api-credentials-set-error! nil)
                               (-> (api/rotate-api-credential! id)
                                   (.then (fn [resp]
                                            (store/api-credentials-open-secret!
                                             {:credential-id (:credential_id resp)
                                              :client-id (:client_id resp)
                                              :client-secret (:client_secret resp)})
                                            (refresh!)))
                                   (.catch (fn [e]
                                             (store/api-credentials-set-error! (safe-http-error e))))
                                   (.finally (fn []
                                               (store/api-credentials-set-loading! false))))))}
        "Rotate"]

       [:button {:class "btn ghost"
                 :disabled revoked?
                 :title "Revoke credential"
                 :on-click (fn [_]
                             (when (js/confirm (str "Revoke credential " name "?\n\nThis will disable the Keycloak client."))
                               (store/api-credentials-set-loading! true)
                               (store/api-credentials-set-error! nil)
                               (-> (api/revoke-api-credential! id)
                                   (.then (fn [_]
                                            ;; Optimistic: mark revoked, then refresh.
                                            (store/api-credentials-mark-revoked! id)
                                            (refresh!)))
                                   (.catch (fn [e]
                                             (store/api-credentials-set-error! (safe-http-error e))))
                                   (.finally (fn []
                                               (store/api-credentials-set-loading! false))))))}
        "Revoke"]]]]))

(defn api-credentials-page
  "API credentials management page (tenant-scoped)."
  []
  (let [st (hooks/use-atom store/api-credentials*)
        items (api-creds.store/visible-items st)
        loading? (:loading? st)
        error (:error st)
        show-revoked? (:show-revoked? st)

        name* (react/useState "")
        name (aget name* 0)
        set-name! (aget name* 1)

        refresh! (fn []
                   (store/api-credentials-set-loading! true)
                   (store/api-credentials-set-error! nil)
                   (-> (api/list-api-credentials!)
                       (.then (fn [resp]
                                (store/api-credentials-set-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/api-credentials-set-error! (safe-http-error e))))
                       (.finally (fn []
                                   (store/api-credentials-set-loading! false)))))

        create! (fn []
                  (store/api-credentials-set-loading! true)
                  (store/api-credentials-set-error! nil)
                  (-> (api/create-api-credential! name)
                      (.then (fn [resp]
                               (set-name! "")
                               (store/api-credentials-open-secret!
                                {:credential-id (:credential_id resp)
                                 :client-id (:client_id resp)
                                 :client-secret (:client_secret resp)})
                               (refresh!)))
                      (.catch (fn [e]
                                (store/api-credentials-set-error! (safe-http-error e))))
                      (.finally (fn []
                                  (store/api-credentials-set-loading! false)))))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])

    [:div {:class "page"}
     [api-credentials-secret-modal]

     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "API Credentials"]
       [:div {:class "muted"}
        "Machine-to-machine credentials for API access."]
       (when (seq error)
         [:div {:class "badge bad" :style {:marginTop "10px"}} error])]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Create credential"]
      [:div {:class "row"}
       [:input {:placeholder "Name (e.g. my-sdk)"
                :value name
                :on-change (fn [e] (set-name! (.. e -target -value)))}]
       [:button {:class "btn primary"
                 :disabled (or loading? (str/blank? name))
                 :on-click (fn [_] (create!))}
        "Create"]]
      [:div {:class "hint"}
       "The client secret will be shown exactly once."]]

     [:div {:class "card"}
      [:div {:class "row" :style {:alignItems "center"}}
       [:div {:class "card-title"} "Credentials"]
       [:div {:class "spacer"}]
       [:label {:class "muted"
                :style {:display "inline-flex" :gap "8px" :alignItems "center"}}
        [:input {:type "checkbox"
                 :checked (boolean show-revoked?)
                 :on-change (fn [_]
                              (store/api-credentials-toggle-show-revoked!))}]
        "Show revoked"]]
      (if (empty? items)
        [:div {:class "muted"} "No API credentials yet."]
        [:table {:class "table"}
         [:thead
          [:tr
           [:th "Name"]
           [:th "Client id"]
           [:th "Created"]
           [:th "Last used"]
           [:th "Status"]
           [:th {:style {:textAlign "right"}} "Actions"]]]
         [:tbody
          (for [c (->> items
                       (sort-by :created_at)
                       reverse)]
            ^{:key (str "cred-" (:id c))}
            [api-credentials-row c refresh!])]])]]))

(defn app
  "Root app component."
  []
  (let [route (hooks/use-atom store/route*)
        {:keys [status detail]} (hooks/use-atom store/auth*)
        auth-required? (true? (get detail :auth-required?))]
    (react/useEffect
     (fn []
       (when (and (= status :anonymous) auth-required?)
          ;; Keep it harder to poke around: force a full redirect to login.
         (auth/login! (router/route->href route)))
       js/undefined)
     #js [status auth-required? (:page route) (get-in route [:params :session_id])])
    [:div {:class "app"}
     [components.layout/topbar route]
     [:div {:class "body"}
      [components.layout/sidebar route]
      [:main {:class "main"}
       (case (:page route)
         :recordings [components.pages.recordings/recordings-page]
         :live [components.pages.live/live-recording-page]
         :recording [components.pages.recording-detail/recording-detail-page
                     (get-in route [:params :session_id])]
         :speakers [components.pages.speakers/speakers-page]
         :api-credentials [components.pages.api-credentials/api-credentials-page]
         [components.pages.recordings/recordings-page])]]]))

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)."
  []
  (shared/memo-clear!))

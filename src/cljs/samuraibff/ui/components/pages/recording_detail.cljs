(ns samuraibff.ui.components.pages.recording-detail
  "Recording detail page.

  Shows cached realtime ASR (from local store), refined segments (from DB + cached WS),
  and final transcript (from DB) with optional audio playback + karaoke highlighting."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.transcript :as components.transcript]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.transcript :as transcript]
   [samuraibff.ui.util :as util]
   ["react" :as react]))

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

(defn- refined-events->messages
  "Convert refined events (with start/end/text) into transcript messages.

  Inputs:
  - events: vector of refined event maps

  Returns: vector of transcript messages."
  [events]
  (->> (or events [])
       (mapv transcript/normalize-refined)
       transcript/sort-messages))

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
  - on-time: (fn [event] ...)

  Returns: hiccup."
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

(defn- db-refined-records->events
  "Convert DB refined transcript records into refined events.

  We assign a stable unique :seq per segment (DB record may contain multiple segments).

  Inputs:
  - records: vector of DB refined transcript records

  Returns: vector of events."
  [records]
  (reduce
   (fn [events r]
     (let [segments (vec (or (:segments r) []))]
       (reduce
        (fn [events [idx seg]]
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
   (vec (or records []))))

(defn recording-detail-page
  "Recording detail page.

  Inputs:
  - props: map with keys:
      - :session-id string

  Returns: hiccup."
  [{:keys [session-id]}]
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
        refined-events (db-refined-records->events db-refined)

        current-title (get-in detail [:session :title])
        title-display (let [t (str/trim (str (or current-title "")))]
                        (when (seq t) t))

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
    (let [realtime-msgs (transcript/sort-messages (vec (or cached-asr [])))
          refined-msgs (->> (concat (refined-events->messages refined-events)
                                    (vec (or cached-refined [])))
                            ;; De-dupe refined segments by stable content/time key.
                            ;; :seq is not stable across DB vs WS.
                            (dedupe-by transcript/refined-dedupe-key)
                            transcript/sort-messages
                            vec)

          ;; Final transcript: take the last record and render its segments.
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
               :empty-hint (if final-record "(no segments)" "No final transcript stored")}])]]

      [:div {:class "page"}
       [:div {:class "page-header"}
        [:div
         [:div {:class "page-title"} (or title-display "Recording")]
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

         [title-editor {:session-id session-id
                        :current-title current-title
                        :on-saved on-title-saved}]

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
             :final final-body
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
            :final final-body
            :refined [components.transcript/transcript-view]
            {:messages refined-msgs
             :empty-title "Refined real-time"
             :empty-hint "No refined transcript available"}
            [components.transcript/transcript-view
             {:messages realtime-msgs
              :empty-title "Real-time transcript"
              :empty-hint "No realtime transcript available"}])])])))

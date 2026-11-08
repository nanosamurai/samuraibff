(ns samuraibff.ui.components.retranscribe
  "Re-transcribe control and session links of the session detail page.

  The control is an icon button next to the transcript export button. It
  opens an anchored panel that creates a new session from the saved audio
  (`POST /api/recordings/{session_id}/retranscribe`) and then opens the new
  session. The panel reuses the Upload page fields. Form rules live in
  `samuraibff.ui.retranscribe-form`.

  Public API:
  - `retranscribe-control`
  - `retranscribed-from-line`, `retranscriptions-list`"
  (:require
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.components.stream-controls :as stream-controls]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.langs :as langs]
   [samuraibff.ui.retranscribe-form :as form]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.session-status :as session-status]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.upload-form :as upload-form]
   ["react" :as react]))

(def ^:private panel-id "session-retranscribe-panel")

(defn- use-dismiss
  "React hook: while `open?`, focus `focus-ref` and call `on-dismiss` for a
  mouse press outside `root-ref`."
  [open? root-ref focus-ref on-dismiss]
  (react/useEffect
   (fn []
     (if open?
       (let [on-mouse-down (fn [e]
                             (when-let [root (.-current root-ref)]
                               (when-not (.contains root (.-target e))
                                 (on-dismiss))))]
         (some-> (.-current focus-ref) .focus)
         (.addEventListener js/document "mousedown" on-mouse-down)
         (fn [] (.removeEventListener js/document "mousedown" on-mouse-down)))
       js/undefined))
   #js [open?]))

(defn- retranscribe-panel
  "Render the form of the Re-transcribe panel.

  Inputs (map):
  - :session-id  source session ID string
  - :initial     starting form state from `form/initial-form`
  - :detail      `/api/me` detail map
  - :title-ref   React ref for the title input (focused on open)

  On success it opens the new session. Returns: hiccup."
  [{:keys [session-id initial detail title-ref]}]
  (let [[title set-title!] (react/useState (:title initial))
        [lang set-lang!] (react/useState (:lang initial))
        [controls set-controls!] (react/useState (:controls initial))
        [busy? set-busy!] (react/useState false)
        [error set-error!] (react/useState nil)
        tracks-chosen? (upload-form/tracks-chosen? controls detail)
        submit! (fn [e]
                  (.preventDefault e)
                  (when (and tracks-chosen? (not busy?))
                    (set-busy! true)
                    (set-error! nil)
                    (-> (api/retranscribe-session!
                         session-id (form/request-body {:title title :lang lang :controls controls} detail))
                        (.then (fn [{:keys [session_id]}]
                                 (store/append-log! (str "[ui] re-transcribing as session " session_id))
                                 (router/navigate! {:page :recording :params {:session_id session_id}})))
                        (.catch (fn [e]
                                  (store/append-log! (str "[ui] re-transcribe failed: " (shared/safe-http-error e)))
                                  (set-busy! false)
                                  (set-error! (form/error-message (api/error-status e) (api/error-code e))))))))]
    [:form {:class "retranscribe-form" :on-submit submit!}
     [:p {:class "muted"} "Create a new session from the saved audio. This session and its transcripts do not change."]
     [:fieldset {:class "stage-fieldset retranscribe-fieldset" :disabled busy?}
      [:div {:class "field"}
       [:label {:class "label" :for "retranscribe-title"} "Session name"]
       [:input {:id "retranscribe-title" :ref title-ref :value title
                :max-length upload-form/max-title-length :placeholder "Untitled session"
                :on-change #(set-title! (.. % -target -value))}]]
      [:div {:class "field"}
       [:span {:class "label"} "Language"]
       [shared/searchable-dropdown {:value lang :options (langs/language-options) :placeholder "Auto"
                                    :aria-label "Language" :disabled busy? :on-change set-lang!}]]
      [stream-controls/track-picker {:stage :final :controls controls :detail detail
                                     :on-update (fn [f & args] (set-controls! #(apply f % args)))
                                     :hint "Each choice creates a separate transcript."}]
      (when-not tracks-chosen?
        [:p {:class "error" :role "alert"} "Choose at least one transcript."])
      [stream-controls/retention-field {:controls controls :detail detail
                                        :on-change (fn [keep?] (set-controls! #(assoc % :store_recording keep?)))}]]
     (when error
       [:p {:class "error" :role "alert"} error])
     [:button {:class "btn primary" :type "submit" :disabled (or busy? (not tracks-chosen?))}
      (icons/icon :rotate-ccw)
      (if busy? "Starting…" "Re-transcribe")]]))

(defn retranscribe-control
  "Render the Re-transcribe icon button and its panel, or the unavailable
  variant, or nil.

  Inputs (map):
  - :session-id   source session ID string
  - :session      detail :session map (see `form/availability`)
  - :shown-title  the title the page shows for the session

  The panel closes on Escape (focus returns to the button) and on a click
  outside. A manager of a session that kept no usable audio sees a disabled
  button that says why. Returns: hiccup or nil."
  [{:keys [session-id session shown-title]}]
  (let [detail (:detail (hooks/use-atom store/auth*))
        [open? set-open!] (react/useState false)
        [initial set-initial!] (react/useState nil)
        root-ref (react/useRef nil)
        button-ref (react/useRef nil)
        title-ref (react/useRef nil)
        close! (fn []
                 (set-open! false)
                 (some-> (.-current button-ref) .focus))
        availability (form/availability session)]
    (use-dismiss open? root-ref title-ref #(set-open! false))
    (case availability
      :unavailable
      [:span {:class "btn ghost icon-only disabled" :role "button" :tab-index 0 :aria-disabled "true"
              :aria-label (str "Re-transcribe. " form/unavailable-text) :title form/unavailable-text}
       (icons/icon :rotate-ccw)]

      :available
      [:div {:class "popover-menu" :ref root-ref
             :on-key-down #(when (and open? (= "Escape" (.-key %)))
                             (.stopPropagation %)
                             (close!))}
       [:button {:class (str "btn ghost icon-only" (when open? " active")) :type "button" :ref button-ref
                 :aria-label "Re-transcribe" :title "Re-transcribe"
                 :aria-haspopup "dialog" :aria-expanded open? :aria-controls panel-id
                 :on-click (fn [_]
                             (when-not open?
                               (set-initial! (form/initial-form session shown-title detail)))
                             (set-open! (not open?)))}
        (icons/icon :rotate-ccw)]
       (when (and open? initial)
         [:div {:id panel-id :class "popover-panel form-panel" :role "dialog" :aria-label "Re-transcribe"}
          [retranscribe-panel {:session-id session-id :initial initial :detail detail :title-ref title-ref}]])]

      nil)))

(defn retranscribed-from-line
  "Render the muted line that links a re-transcription to its source session.

  Inputs: detail :session map; :retranscribed_from is present only when the
  reader may open the source session. Returns: hiccup or nil."
  [{:keys [retranscribed_from]}]
  (when-let [{:keys [id title]} retranscribed_from]
    [:div {:class "muted"}
     "Re-transcribed from "
     [router/link {:route {:page :recording :params {:session_id id}}}
      (or (not-empty title) "the original session")]]))

(defn retranscriptions-list
  "Render the compact list of re-transcriptions of a session.

  Inputs: detail :session map with :retranscriptions [{:id :title :status
  :created_at :source :has_final_transcript}]. Returns: hiccup or nil when
  the list is empty."
  [{:keys [retranscriptions]}]
  (when (seq retranscriptions)
    [:nav {:class "retranscriptions" :aria-label "Re-transcriptions"}
     [:span {:class "muted"} "Re-transcriptions"]
     [:ul
      (for [{:keys [id title] :as child} retranscriptions]
        [:li {:key id}
         [router/link {:route {:page :recording :params {:session_id id}}}
          (or (not-empty title) "Untitled session")]
         [shared/session-status-badge (session-status/display-status child)]])]]))

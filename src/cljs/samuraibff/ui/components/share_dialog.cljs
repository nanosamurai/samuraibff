(ns samuraibff.ui.components.share-dialog
  "Share dialog and \"Who can access\" row for sessions.

  The dialog edits a complete audience: Private, Selected people, or Everyone
  in the organization. Sharing grants read-only access to the whole session.
  Saving sends the full audience with the revision the dialog loaded, so a
  concurrent change returns 409 instead of being overwritten. A link never
  grants access by itself.

  Draft mode (Record page before the session exists) keeps the chosen
  audience in `draft-audience*`; `apply-draft-audience!` saves it right
  after the session is created.

  Public API:
  - `share-dialog`, `who-can-access-row`
  - `draft-audience*`, `reset-draft-audience!`, `apply-draft-audience!`
  - `session-access*`, `remember-session-access!`"
  (:require
   [clojure.string :as str]
   [samuraibff.ui.access :as access]
   [samuraibff.ui.access-api :as access-api]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.permissions :as permissions]
   ["react" :as react]))

(def ^:private search-debounce-ms 300)

(def ^:private private-audience
  {:visibility "private" :recipients []})

(defonce draft-audience*
  (atom private-audience))

(defonce ^:private draft-notice*
  (atom nil))

(defonce session-access*
  (atom {}))

(defn reset-draft-audience!
  "Reset the Record page draft audience to Private and clear its notice."
  []
  (reset! draft-audience* private-audience)
  (reset! draft-notice* nil)
  nil)

(defn remember-session-access!
  "Cache the latest known access map for a session.

  Inputs: session id string, access map with :revision. An older revision
  never replaces a newer one. Returns: nil."
  [session-id access-map]
  (when (and (seq (str session-id)) (map? access-map))
    (swap! session-access* update (str session-id)
           (fn [old]
             (if (and old (> (or (:revision old) 0) (or (:revision access-map) 0)))
               old
               access-map))))
  nil)

(defn apply-draft-audience!
  "Save the Record page draft audience for a newly created session.

  Inputs: session id string. Uses revision 0 because the session is new.
  When the save fails the session stays Private and the Record page shows a
  notice. Returns: Promise resolving to nil."
  [session-id]
  (let [{:keys [visibility recipients]} @draft-audience*]
    (reset! draft-audience* private-audience)
    (reset! draft-notice* nil)
    (if (= "private" visibility)
      (js/Promise.resolve nil)
      (-> (access-api/put-session-access! session-id {:visibility visibility
                                                      :recipient-ids (map :id recipients)
                                                      :revision 0})
          (.then (fn [resp] (remember-session-access! session-id resp) nil))
          (.catch (fn [e]
                    (reset! draft-notice*
                            (str "Sharing could not be saved, so this session is Private. "
                                 (access/error-message (api/error-code e) "")
                                 " Use Change to try again."))
                    nil))))))

(defn- session-link
  "Return the absolute in-app URL of a session detail page."
  [session-id]
  (str (.-origin js/location) "/recordings/" (js/encodeURIComponent (str session-id))))

(defn- choice
  "Render one audience radio option using the existing choice-card style."
  [{:keys [value label hint selected? disabled? on-select]}]
  [:label {:class (str "track-choice" (when selected? " selected"))}
   [:input {:type "radio" :name "share-visibility" :value value :checked (boolean selected?)
            :disabled (boolean disabled?)
            :on-change (fn [_] (on-select value))}]
   [:span {:class "track-choice-copy"}
    [:strong label]
    [:span {:class "hint"} hint]]])

(defn- recipient-chip
  "Render a selected person with a remove button."
  [{:keys [person disabled? on-remove]}]
  (let [who (or (not-empty (str (:display_name person))) (:email person) "")]
    [:span {:class "badge" :style {:gap "6px"}}
     who
     (when (:suspended person) [:span {:class "muted"} " (suspended)"])
     [:button {:type "button" :class "btn ghost" :disabled (boolean disabled?)
               :aria-label (str "Remove " who)
               :style {:padding "0 4px" :border "0"}
               :on-click (fn [_] (on-remove person))}
      "×"]]))

(defn- use-directory-search
  "Search the colleague directory while the Selected option is open.

  Inputs: enabled? boolean, query string.
  Returns: {:items [...] :loading? boolean :error string? :empty-directory? boolean}."
  [enabled? query]
  (let [[result set-result!] (react/useState {:items [] :loading? false})]
    (react/useEffect
     (fn []
       (if-not enabled?
         js/undefined
         (let [active? (atom true)
               timer (js/setTimeout
                      (fn []
                        (set-result! #(assoc % :loading? true :error nil))
                        (-> (access-api/search-directory! query)
                            (.then (fn [{:keys [items]}]
                                     (when @active?
                                       (set-result! {:items (vec items) :loading? false
                                                     :empty-directory? (and (str/blank? query) (empty? items))}))))
                            (.catch (fn [e]
                                      (when @active?
                                        (set-result! {:items [] :loading? false
                                                      :error (access/error-message
                                                              (api/error-code e)
                                                              "People could not be loaded. Try again.")}))))))
                      search-debounce-ms)]
           (fn [] (reset! active? false) (js/clearTimeout timer)))))
     #js [enabled? query])
    result))

(defn- audience-state
  "Return dialog form state from an access map or draft audience."
  [{:keys [visibility recipients revision]}]
  {:visibility (or (not-empty (str visibility)) "private")
   :recipients (vec (or recipients []))
   :revision (or revision 0)})

(defn share-dialog
  "Modal that edits who can access a session.

  Inputs (map):
  - :open? boolean
  - :session-id string or nil; nil means draft mode (no API call on Save)
  - :session-title string shown under the dialog title
  - :initial {:visibility :recipients :revision} used in draft mode
  - :on-close (fn []) Cancel or close; the prior audience stays
  - :on-saved (fn [access-map]) after a successful save (server response, or
    {:visibility :recipients} in draft mode)

  Returns: hiccup or nil."
  [{:keys [open? session-id session-title initial on-close on-saved]}]
  (let [draft? (str/blank? (str session-id))
        [form set-form!] (react/useState (audience-state initial))
        [loading? set-loading!] (react/useState false)
        [saving? set-saving!] (react/useState false)
        [error set-error!] (react/useState nil)
        [copy-note set-copy-note!] (react/useState nil)
        [query set-query!] (react/useState "")
        {:keys [visibility recipients revision]} form
        selected? (= "selected" visibility)
        directory (use-directory-search (and open? selected?) query)
        tenant (permissions/tenant-name)
        busy? (or loading? saving?)
        load! (fn []
                (set-loading! true)
                (-> (access-api/get-session-access! session-id)
                    (.then (fn [resp]
                             (remember-session-access! session-id resp)
                             (set-form! (audience-state resp))))
                    (.catch (fn [e]
                              (set-error! (access/error-message (api/error-code e)
                                                                "Who can access could not be loaded. Try again."))))
                    (.finally (fn [] (set-loading! false)))))
        close! (fn [] (when (fn? on-close) (on-close)))
        save! (fn []
                (set-error! nil)
                (if draft?
                  (do (on-saved {:visibility visibility
                                 :recipients (if selected? recipients [])})
                      (close!))
                  (do
                    (set-saving! true)
                    (-> (access-api/put-session-access! session-id {:visibility visibility
                                                                    :recipient-ids (map :id recipients)
                                                                    :revision revision})
                        (.then (fn [resp]
                                 (remember-session-access! session-id resp)
                                 (set-saving! false)
                                 (when (fn? on-saved) (on-saved resp))
                                 (close!)))
                        (.catch (fn [e]
                                  (set-saving! false)
                                  (set-error! (access/error-message (api/error-code e)
                                                                    "Sharing could not be saved. Nothing changed."))
                                  (when (= "access-changed" (api/error-code e))
                                    (load!))))))))]
    (react/useEffect
     (fn []
       (when open?
         (set-error! nil)
         (set-copy-note! nil)
         (set-query! "")
         (set-form! (audience-state initial))
         (when-not draft? (load!)))
       js/undefined)
     #js [open? session-id])
    (when open?
      [:div {:class "modal-overlay" :on-click (fn [_] (when-not saving? (close!)))}
       [:div {:class "modal" :role "dialog" :aria-modal true :aria-label "Share session"
              :style {:maxWidth "560px"}
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "row"}
         [:div {:class "modal-title" :style {:marginBottom 0}} "Share session"]
         [:div {:class "spacer"}]
         [:button {:class "btn ghost" :type "button" :title "Close" :disabled saving?
                   :on-click (fn [_] (close!))} "×"]]
        (when (seq (str session-title))
          [:div {:class "muted" :style {:marginBottom "10px"}} session-title])
        [:div {:style {:display "flex" :flexDirection "column" :gap "8px"} :role "radiogroup"
               :aria-label "Who can access"}
         (for [{:keys [value label hint]}
               [{:value "private" :label "Private" :hint "Only you"}
                {:value "selected" :label "Selected people"
                 :hint "Choose people who already have an account here"}
                {:value "tenant" :label (access/audience-label "tenant" tenant)
                 :hint "All active members, including people who join later"}]]
           [choice {:key value :value value :label label :hint hint
                    :selected? (= value visibility) :disabled? busy?
                    :on-select (fn [v] (set-form! #(assoc % :visibility v)))}])]
        (when (access/clear-warning? visibility recipients)
          [:div {:class "hint" :role "status"}
           (str "Saving removes the " (count recipients)
                (if (= 1 (count recipients)) " selected person." " selected people."))])
        (when selected?
          [:div {:style {:display "flex" :flexDirection "column" :gap "8px" :marginTop "10px"}}
           [:input {:type "search" :value query :placeholder "Find a person" :aria-label "Find a person"
                    :disabled busy?
                    :on-change (fn [e] (set-query! (.. e -target -value)))}]
           (when (seq recipients)
             [:div {:class "row" :style {:gap "6px"}}
              (for [person recipients]
                [recipient-chip {:key (str "chip-" (:id person)) :person person :disabled? busy?
                                 :on-remove (fn [p] (set-form! #(update % :recipients access/toggle-recipient p false)))}])])
           (cond
             (:error directory) [:div {:class "badge bad"} (:error directory)]
             (:empty-directory? directory) [:div {:class "muted"}
                                            "No one else to share with yet. Ask an Admin to add people."]
             (and (:loading? directory) (empty? (:items directory))) [:div {:class "muted"} "Searching…"]
             (empty? (:items directory)) [:div {:class "muted"} "No one matches this search."]
             :else
             [:div {:style {:display "flex" :flexDirection "column" :gap "4px" :maxHeight "220px" :overflow "auto"}}
              (for [person (:items directory)
                    :let [id (str (:id person))
                          checked? (boolean (some #(= id (str (:id %))) recipients))]]
                [:label {:key (str "dir-" id) :class "checkbox-row"
                         :style {:display "flex" :gap "8px" :alignItems "center"}}
                 [:input {:type "checkbox" :checked checked? :disabled busy?
                          :on-change (fn [e]
                                       (let [on? (.. e -target -checked)]
                                         (set-form! #(update % :recipients access/toggle-recipient person on?))))}]
                 [:span (or (not-empty (str (:display_name person))) (:email person))
                  (when (and (seq (str (:email person))) (not= (:display_name person) (:email person)))
                    [:span {:class "muted" :style {:marginLeft "8px" :fontSize "12px"}} (:email person)])]])])])
        (when (not= "private" visibility)
          [:div {:class "hint" :style {:marginTop "10px"}}
           "Read-only access includes audio, transcripts and results, including live content. Recipients can keep copies."])
        (when loading? [:div {:class "muted" :style {:marginTop "8px"}} "Loading who can access…"])
        (when error [:div {:class "badge bad" :role "alert" :style {:marginTop "10px"}} error])
        (when copy-note [:div {:class "hint" :role "status"} copy-note])
        [:div {:class "row" :style {:marginTop "14px"}}
         (when-not draft?
           [:button {:class "btn" :type "button"
                     :on-click (fn [_]
                                 (-> (shared/copy-to-clipboard! (session-link session-id))
                                     (.then (fn [ok?]
                                              (set-copy-note!
                                               (if ok?
                                                 "Link copied. Copying the link does not give anyone access."
                                                 "Could not copy the link. Copying the link does not give anyone access."))))))}
            "Copy link"])
         [:div {:class "spacer"}]
         [:button {:class "btn" :type "button" :disabled saving? :on-click (fn [_] (close!))} "Cancel"]
         [:button {:class "btn primary" :type "button"
                   :disabled (or busy? (and selected? (empty? recipients)))
                   :title (when (and selected? (empty? recipients)) "Choose at least one person")
                   :on-click (fn [_] (save!))}
          (if saving? "Saving…" "Save changes")]]]])))

(defn- audience-summary
  "Return the row label for an audience map: label plus the people count."
  [{:keys [visibility recipients]} tenant]
  (str (access/audience-label visibility tenant)
       (when (and (= "selected" visibility) (seq recipients))
         (str " · " (count recipients) (if (= 1 (count recipients)) " person" " people")))))

(defn who-can-access-row
  "Render the Record page \"Who can access\" row with a Change button.

  Inputs: {:session-id string-or-nil :session-title string :disabled? boolean
  :extra hiccup-or-nil}. `extra` renders next to the audience (the Record page
  puts the destinations summary there). Before the session exists the row
  edits `draft-audience*`. Afterwards it loads and changes the session
  audience on the server.

  Returns: hiccup."
  [{:keys [session-id session-title disabled? extra]}]
  (let [session-id (not-empty (str (or session-id "")))
        draft (hooks/use-atom draft-audience*)
        notice (hooks/use-atom draft-notice*)
        server-access (get (hooks/use-atom session-access*) session-id)
        [open? set-open!] (react/useState false)
        tenant (permissions/tenant-name)
        can-share? (permissions/use-capability? :share_sessions)
        manager? (or (nil? session-id) (nil? server-access) (true? (:can_manage server-access)))]
    (react/useEffect
     (fn []
       (when session-id
         (-> (access-api/get-session-access! session-id)
             (.then (fn [resp] (remember-session-access! session-id resp)))
             (.catch (fn [_] nil))))
       js/undefined)
     #js [session-id])
    (if-not can-share?
      (when extra
        [:div {:class "row" :style {:marginTop "12px" :paddingTop "12px" :borderTop "1px solid var(--line)"
                                    :alignItems "flex-start"}}
         extra])
      [:div {:class "row" :style {:marginTop "12px" :paddingTop "12px" :borderTop "1px solid var(--line)"
                                  :alignItems "flex-start" :gap "24px"}}
       [:div {:class "field"}
        [:div {:class "label"} "Who can access"]
        [:div (cond
                (nil? session-id) (audience-summary draft tenant)
                server-access (audience-summary server-access tenant)
                :else "Checking…")]
        (when notice [:div {:class "badge bad" :role "alert"} notice])]
       extra
       [:div {:class "spacer"}]
       (when manager?
         [:button {:class "btn" :type "button" :disabled (boolean disabled?)
                   :on-click (fn [_] (set-open! true))}
          "Change"])
       [share-dialog {:open? open?
                      :session-id session-id
                      :session-title session-title
                      :initial draft
                      :on-close (fn [] (set-open! false))
                      :on-saved (fn [result]
                                  (when (nil? session-id)
                                    (reset! draft-notice* nil)
                                    (reset! draft-audience* {:visibility (:visibility result)
                                                             :recipients (vec (:recipients result))})))}]])))

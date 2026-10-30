(ns samuraibff.ui.components.pages.recordings
  "Sessions list page.

  This page shows all sessions/recordings for the tenant."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.access :as access]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.share-dialog :as share-dialog]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.pagination :as pagination]
   [samuraibff.ui.permissions :as permissions]
   [samuraibff.ui.recordings-page :as recordings-page]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.session-request :as session.req]
   [samuraibff.ui.session-status :as session-status]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]
   ["react" :as react]))

(def ^:private mobile-breakpoint-query
  "CSS media query used as the threshold for mobile layout.

  Must match the @media rule in `resources/public/index.html`."
  "(max-width: 768px)")

(defn- row-access
  "Return {:can-manage? boolean :access-label string?} for a recording row.

  A row without an :access summary (older BFF) stays manageable, as before.
  The BFF still rejects mutations by non-owners with 403. The owner and
  audience label is shown only when sharing is available (not to the guest of
  a single-user install)."
  [rec]
  (let [summary (:access rec)]
    {:can-manage? (not (false? (:can_manage summary)))
     :access-label (when (permissions/capability? :share_sessions)
                     (access/row-access-label summary (permissions/tenant-name)))}))

(def ^:private scopes
  [["all" "All"] ["mine" "My sessions"] ["shared" "Shared with me"]])

(defn- session-actions
  "Render the actions of a session row or card.

  Record shows only for a draft the user can record into, before the other
  actions, so Open, Share and Delete line up across rows. Share shows when
  sharing is available and is disabled for people who cannot manage the
  session. Unavailable actions say why in their tooltip.

  Inputs:
  - rec: a map from /api/recordings
  - opts: {:class string, :on-deleted! (fn []), :on-share! (fn [rec]) or nil
    when sharing is not available}

  Returns: hiccup <div>."
  [{:keys [session_id title created_at] :as rec} {:keys [class on-deleted! on-share!]}]
  (let [session-status (str (or (:status rec) ""))
        active? (= "active" session-status)
        {:keys [can-manage?]} (row-access rec)
        recordable? (and can-manage?
                         (= "created" session-status)
                         (false? (:has_recording rec)))
        shareable? (and can-manage? (some? (:access rec)))
        created-at-ms (or (util/iso->ms created_at) (util/now-ms))
        session-title (let [t (str/trim (str (or title "")))]
                        (when (seq t) t))
        session-title-display (or session-title (util/default-session-title created-at-ms))
        unavailable (fn [icon-name title]
                      [:span {:class "btn ghost icon-only disabled" :aria-disabled "true" :title title}
                       (icons/icon icon-name)])]
    [:div {:class (str "session-actions " class)}
     (when recordable?
       [router/link {:route {:page :live :params {}}
                     :class "btn ghost icon-only"
                     :title "Record with this session"
                     :aria-label "Record with this session"
                     :on-click (fn [_]
                                 (store/set-session-id! session_id)
                                 (store/set-session-created-at-ms! created-at-ms)
                                 (store/set-session-title! (or session-title ""))
                                 (store/set-session-status! (:status rec)))}
        (icons/icon :record)])

     [router/link {:route {:page :recording :params {:session_id session_id}}
                   :class "btn session-open"
                   :title "Open session"}
      "Open"]

     (when (fn? on-share!)
       (if shareable?
         [:button {:class "btn ghost icon-only"
                   :type "button"
                   :title "Share session"
                   :aria-label "Share session"
                   :on-click (fn [_] (on-share! rec))}
          (icons/icon :share)]
         (unavailable :share (if can-manage?
                               "Sharing is not available for this session"
                               "Only the owner can share this session"))))

     (cond
       (not can-manage?)
       (unavailable :trash "Only the owner can delete this session")

       active?
       (unavailable :trash "Stop recording before deleting this session")

       :else
       [:button {:class "btn ghost icon-only session-delete"
                 :type "button"
                 :title "Delete session"
                 :aria-label "Delete session"
                 :on-click (fn [_]
                             (when (js/confirm (str "Delete session “" session-title-display "”"
                                                    "?\n\nThis will remove recordings and transcripts."))
                               (-> (api/delete-recording! session_id)
                                   (.then (fn [_]
                                            (store/remove-recording-db! session_id)
                                            (on-deleted!)))
                                   (.catch (fn [e]
                                             (store/append-log!
                                              (str "[ui] failed deleting session: " e)))))))}
        (icons/icon :trash)])]))

(defn- recordings-row
  "Render a single recordings table row.

  Inputs:
  - rec: a map from /api/recordings
  - on-deleted!: no-argument callback that refreshes the current page
  - on-share!: (fn [rec]) that opens the share dialog, or nil without sharing

  Returns: hiccup <tr>"
  [{:keys [title started_at created_at] :as rec} on-deleted! on-share!]
  (let [display-status (session-status/display-status rec)
        {:keys [access-label]} (row-access rec)
        created-at-ms (or (util/iso->ms created_at) (util/now-ms))
        session-title (let [t (str/trim (str (or title "")))]
                        (when (seq t) t))
        session-title-display (or session-title (util/default-session-title created-at-ms))
        lang (get-in rec [:recording :lang])]
    [:tr
     [:td
      [:div {:style {:display "flex" :flexDirection "column" :gap "2px"}}
       [:div {:style {:display "flex" :gap "8px" :alignItems "baseline"}}
        ;; Language flag hint (best-effort). Blank => omit.
        (when (seq (str lang))
          [shared/lang-flag lang])
        [:span session-title-display]]
       (when access-label
         [:span {:class "muted" :style {:fontSize "12px"}} access-label])]]
     [:td {:class "muted"} (or (shared/iso->local created_at) "")]
     [:td {:class "muted"} (or (shared/iso->local started_at) "")]
     [:td [shared/session-status-badge display-status]]
     [:td [session-actions rec {:on-deleted! on-deleted! :on-share! on-share!}]]]))

(defn- recordings-card
  "Render a single recording as a stacked card (mobile layout).

  Inputs:
  - rec: a map from /api/recordings
  - on-deleted!: no-argument callback that refreshes the current page
  - on-share!: (fn [rec]) that opens the share dialog, or nil without sharing

  Returns: hiccup <div>."
  [{:keys [title started_at created_at] :as rec} on-deleted! on-share!]
  (let [display-status (session-status/display-status rec)
        {:keys [access-label]} (row-access rec)
        created-at-ms (or (util/iso->ms created_at) (util/now-ms))
        session-title (let [t (str/trim (str (or title "")))]
                        (when (seq t) t))
        session-title-display (or session-title (util/default-session-title created-at-ms))
        lang (get-in rec [:recording :lang])]
    [:div {:class "list-item"}
     [:div {:style {:display "flex" :gap "10px" :alignItems "flex-start"}}
      [:div {:style {:flex "1" :minWidth 0}}
       [:div {:class "list-item-title"}
        [:div {:style {:display "flex" :gap "8px" :alignItems "baseline" :flexWrap "wrap"}}
         (when (seq (str lang))
           [shared/lang-flag lang])]
        [:span session-title-display]]
       [:div {:class "list-item-meta"}
        [:span (str "Created: " (or (shared/iso->local created_at) "—"))]
        [:span (str "Started: " (or (shared/iso->local started_at) "—"))]
        (when access-label
          [:span access-label])]]

      [:div [shared/session-status-badge display-status]]]

     [session-actions rec {:class "list-item-actions" :on-deleted! on-deleted! :on-share! on-share!}]]))

(defn- pagination-footer
  "Render compact pagination from use-recordings-page state; return footer Hiccup."
  [{:keys [total page-info loading? error set-page! set-page-size!]}]
  (let [{:keys [page page-size pages from to previous? next?]} page-info]
    [:nav {:class "sessions-pagination" :aria-label "Sessions pagination"}
     [:span {:class "muted sessions-range" :role "status" :aria-live "polite"}
      (cond loading? "Loading sessions…"
            error "Sessions unavailable"
            :else (str from "–" to " of " total))]
     [:div {:class "sessions-pagination-controls"}
      [:label {:class "sessions-page-size muted"}
       "Rows"
       [:select {:aria-label "Sessions per page" :value page-size :disabled loading?
                 :on-change (fn [e] (set-page-size! (js/parseInt (.. e -target -value) 10)))}
        (for [size pagination/page-sizes]
          ^{:key size} [:option {:value size} size])]]
      [:span {:class "muted sessions-page-number"} (str (inc page) " / " pages)]
      [:div {:class "sessions-page-buttons"}
       [:button {:class "btn ghost" :aria-label "First page" :title "First page"
                 :disabled (or loading? (not previous?))
                 :on-click (fn [_] (set-page! 0))}
        (icons/icon :chevrons-left)]
       [:button {:class "btn ghost" :aria-label "Previous page" :title "Previous page"
                 :disabled (or loading? (not previous?))
                 :on-click (fn [_] (set-page! (dec page)))}
        (icons/icon :chevron-left)]
       [:button {:class "btn ghost" :aria-label "Next page" :title "Next page"
                 :disabled (or loading? error (not next?))
                 :on-click (fn [_] (set-page! (inc page)))}
        (icons/icon :chevron-right)]
       [:button {:class "btn ghost" :aria-label "Last page" :title "Last page"
                 :disabled (or loading? error (not next?))
                 :on-click (fn [_] (set-page! (dec pages)))}
        (icons/icon :chevrons-right)]]]]))

(defn recordings-table
  "Render a page of sessions as a table or mobile cards from pagination hook state."
  [{:keys [items drafts-count show-drafts? scope loading? error set-show-drafts! set-scope! refresh!] :as state}]
  (let [mobile? (hooks/use-media-query mobile-breakpoint-query)
        sharing? (permissions/use-capability? :share_sessions)
        [share-rec set-share-rec!] (react/useState nil)
        on-share! (when sharing? set-share-rec!)]
    [:div {:class "card" :aria-busy (boolean loading?)}
     [share-dialog/share-dialog {:open? (some? share-rec)
                                 :session-id (:session_id share-rec)
                                 :session-title (or (not-empty (str/trim (str (or (:title share-rec) ""))))
                                                    (util/default-session-title
                                                     (or (util/iso->ms (:created_at share-rec)) (util/now-ms))))
                                 :on-close #(set-share-rec! nil)
                                 :on-saved (fn [_] (refresh!))
                                 :on-owner-assigned (fn [_] (refresh!))}]
     (let [drafts-toggle? (or show-drafts? (pos? drafts-count))]
       ;; The page title already says Sessions: without scope tabs the header
       ;; only holds the drafts toggle, and is left out when there is none.
       (when (or sharing? drafts-toggle?)
         [:div {:class (str "row sessions-table-header" (when sharing? " with-tabs"))}
          (if sharing?
            [:div {:class "tabs sessions-scope-tabs" :role "tablist" :aria-label "Sessions view"}
             (for [[value label] scopes]
               [:button {:key value :type "button" :role "tab"
                         :class (str "tab " (when (= value scope) "active"))
                         :aria-selected (= value scope)
                         :on-click (fn [_] (set-scope! value))}
                label])]
            [:div {:class "spacer"}])
          (when drafts-toggle?
            [:label {:class "muted sessions-drafts"}
             [:input {:type "checkbox" :checked (boolean show-drafts?) :disabled loading?
                      :on-change (fn [e] (set-show-drafts! (.. e -target -checked)))}]
             (str "Show drafts (" drafts-count ")")])]))
     (cond
       loading?
       [:div {:class "muted sessions-list-message"} "Loading sessions…"]

       error
       [:div {:class "muted sessions-list-message" :role "alert"} error]

       (empty? items)
       [:div {:class "muted sessions-list-message"}
        (cond
          (= "shared" scope) "Nothing has been shared with you yet."
          (and (not show-drafts?) (pos? drafts-count))
          "No recorded sessions yet. Enable Show drafts to see your drafts."
          :else "No sessions yet.")]

       mobile?
       [:div {:class "list"}
        (for [{:keys [session_id] :as rec} items]
          ^{:key (str "rec-card-" session_id)}
          [recordings-card rec refresh! on-share!])]

       :else
       [:table {:class "table"}
        [:thead
         [:tr
          [:th "Session"]
          [:th "Created"]
          [:th "Started"]
          [:th "Status"]
          [:th {:style {:textAlign "right"}} "Actions"]]]
        [:tbody
         (for [{:keys [session_id] :as rec} items]
           ^{:key (str "rec-" session_id)}
           [recordings-row rec refresh! on-share!])]])
     [pagination-footer state]]))

(defn recordings-page
  "Render Sessions with server pagination and the existing create/record actions."
  []
  (let [{:keys [loading? refresh!] :as state} (recordings-page/use-recordings-page)
        sharing? (permissions/use-capability? :share_sessions)
        new-draft! (fn []
                     (store/append-log! "[ui] creating session draft...")
                     (let [req (session.req/create-session-request-body
                                (assoc @store/session* :title ""))]
                       (-> (api/create-session! req)
                           (.then (fn [{:keys [session_id title]}]
                                    (store/set-session-id! session_id)
                                    (store/set-session-created-at-ms! (util/now-ms))
                                    (store/set-session-title! (or title ""))
                                    (store/set-session-status! :created)
                                    (store/add-recording! {:session_id session_id
                                                           :created_at_ms (util/now-ms)
                                                           :status :ready})
                                    (router/navigate! {:page :live :params {}})
                                    (store/append-log! (str "[ui] new draft session " session_id))))
                           (.catch (fn [e]
                                     (store/append-log!
                                      (str "[ui] failed creating session draft: "
                                           (shared/safe-http-error e))))))))
        go-record! (fn [] (router/navigate! {:page :live :params {}}))]
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Sessions"]
       [:div {:class "muted"}
        (if sharing?
          "Your recordings and drafts, and the ones shared with you."
          "Your recordings and drafts.")]]
      [:div {:class "row"}
       [:button {:class "btn" :disabled loading? :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn" :disabled loading? :on-click (fn [_] (new-draft!))}
        "New session (draft)"]
       [:button {:class "btn primary" :on-click (fn [_] (go-record!))}
        "Record"]]]
     [recordings-table state]]))

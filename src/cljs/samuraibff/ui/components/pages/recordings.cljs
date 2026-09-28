(ns samuraibff.ui.components.pages.recordings
  "Sessions list page.

  This page shows all sessions/recordings for the tenant."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.pagination :as pagination]
   [samuraibff.ui.recordings-page :as recordings-page]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.session-request :as session.req]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]))

(def ^:private mobile-breakpoint-query
  "CSS media query used as the threshold for mobile layout.

  Must match the @media rule in `resources/public/index.html`."
  "(max-width: 768px)")

(defn- rec->display-status
  "Derive a compact UI status descriptor for a DB recording row.

  Inputs:
  - rec: map from /api/recordings with keys:
      :status (string)
      :has_recording (boolean)
      :has_final_transcript (boolean)

  Returns:
  - {:label string :badge-class string :icon string :tooltip string}"
  [{:keys [status has_recording has_final_transcript]}]
  (let [status (some-> status str)
        has-recording? (true? has_recording)
        has-final? (true? has_final_transcript)]
    (cond
      (= status "failed")
      {:label "Failed"
       :badge-class "bad"
       :icon "✗"
       :tooltip "Session failed"}

      (= status "active")
      {:label "Recording"
       :badge-class "warn"
       :icon "●"
       :tooltip "Recording/transcription in progress"}

      (= status "created")
      {:label "Created"
       :badge-class "muted"
       :icon "○"
       :tooltip "Draft session (recording not started)"}

      (and (= status "finished") has-final?)
      {:label "Finalized"
       :badge-class "ok"
       :icon "✓"
       :tooltip "Final transcript is available"}

      (= status "finished")
      {:label "Finished"
       :badge-class "muted"
       :icon "■"
       :tooltip "Recording stopped; final transcript not available yet"}

      has-recording?
      {:label "Processing"
       :badge-class "muted"
       :icon "…"
       :tooltip "Recording finished; final transcript not available yet"}

      :else
      {:label "Created"
       :badge-class "muted"
       :icon "○"
       :tooltip "Session created"})))

(defn- recordings-row
  "Render a single recordings table row.

  Inputs:
  - rec: a map from /api/recordings
  - on-deleted!: no-argument callback that refreshes the current page

  Returns: hiccup <tr>"
  [{:keys [session_id title started_at created_at] :as rec} on-deleted!]
  (let [{:keys [label badge-class tooltip]
         icon-glyph :icon} (rec->display-status rec)
        session-status (str (or (:status rec) ""))
        active? (= "active" session-status)
        recordable? (and (= "created" session-status)
                         (false? (:has_recording rec)))
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
       [:div {:class "hint"}
        [:span {:class "mono"} session_id]]]]
     [:td {:class "muted"} (or (shared/iso->local created_at) "")]
     [:td {:class "muted"} (or (shared/iso->local started_at) "")]
     [:td
      [:span {:class (str "badge " badge-class)
              :title tooltip}
       (shared/icon icon-glyph {:title tooltip})
       [:span {:style {:marginLeft "8px"}} label]]]
     [:td {:style {:textAlign "right"}}
      [:div {:class "row"}
       [router/link {:route {:page :recording :params {:session_id session_id}}
                     :class "btn"
                     :title "Open detail"}
        (shared/icon "↗" {:title "Open"})]

       (if recordable?
         [router/link {:route {:page :live :params {}}
                       :class "btn ghost"
                       :title "Record with this session"
                       :on-click (fn [_]
                                   (store/set-session-id! session_id)
                                   (store/set-session-created-at-ms! created-at-ms)
                                   (store/set-session-title!
                                    (or session-title
                                        (util/default-session-title created-at-ms)
                                        ""))
                                   (store/set-session-status! (:status rec)))}
          (shared/icon "●" {:title "Record"})]
         [:span {:class "btn ghost disabled"
                 :title "Recording is not available for this session status"}
          (shared/icon "●" {:title "Not available"})])

       (if active?
         [:span {:class "btn ghost disabled"
                 :title "Active sessions cannot be deleted"}
          (shared/icon "×" {:title "Delete"})]
         [:button {:class "btn ghost"
                   :title "Delete session"
                   :on-click (fn [_]
                               (when (js/confirm (str "Delete session " session_id
                                                      "?\n\nThis will remove recordings and transcripts."))
                                 (-> (api/delete-recording! session_id)
                                     (.then (fn [_]
                                              (store/remove-recording-db! session_id)
                                              (on-deleted!)))
                                     (.catch (fn [e]
                                               (store/append-log!
                                                (str "[ui] failed deleting session: " e)))))))}
          (shared/icon "×" {:title "Delete"})])]]]))

(defn- recordings-card
  "Render a single recording as a stacked card (mobile layout).

  Inputs:
  - rec: a map from /api/recordings
  - on-deleted!: no-argument callback that refreshes the current page

  Returns: hiccup <div>."
  [{:keys [session_id title started_at created_at] :as rec} on-deleted!]
  (let [{:keys [label badge-class tooltip]
         icon-glyph :icon} (rec->display-status rec)
        session-status (str (or (:status rec) ""))
        active? (= "active" session-status)
        recordable? (and (= "created" session-status)
                         (false? (:has_recording rec)))
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
       [:div {:class "list-item-sub mono"} session_id]
       [:div {:class "list-item-meta"}
        [:span (str "Created: " (or (shared/iso->local created_at) "—"))]
        [:span (str "Started: " (or (shared/iso->local started_at) "—"))]]]

      [:div
       [:span {:class (str "badge " badge-class)
               :title tooltip}
        (shared/icon icon-glyph {:title tooltip})
        [:span {:style {:marginLeft "8px"}} label]]]]

     [:div {:class "list-item-actions"}
      [router/link {:route {:page :recording :params {:session_id session_id}}
                    :class "btn icon"
                    :title "Open detail"}
       (shared/icon "↗" {:title "Open"})]

      (if recordable?
        [router/link {:route {:page :live :params {}}
                      :class "btn ghost icon"
                      :title "Record with this session"
                      :on-click (fn [_]
                                  (store/set-session-id! session_id)
                                  (store/set-session-created-at-ms! created-at-ms)
                                  (store/set-session-title!
                                   (or session-title
                                       (util/default-session-title created-at-ms)
                                       ""))
                                  (store/set-session-status! (:status rec)))}
         (shared/icon "●" {:title "Record"})]
        [:span {:class "btn ghost icon disabled"
                :title "Recording is not available for this session status"}
         (shared/icon "●" {:title "Not available"})])

      (if active?
        [:span {:class "btn ghost icon disabled"
                :title "Active sessions cannot be deleted"}
         (shared/icon "×" {:title "Delete"})]
        [:button {:class "btn ghost icon"
                  :title "Delete session"
                  :on-click (fn [_]
                              (when (js/confirm (str "Delete session " session_id
                                                     "?\n\nThis will remove recordings and transcripts."))
                                (-> (api/delete-recording! session_id)
                                    (.then (fn [_]
                                             (store/remove-recording-db! session_id)
                                             (on-deleted!)))
                                    (.catch (fn [e]
                                              (store/append-log!
                                               (str "[ui] failed deleting session: " e)))))))}
         (shared/icon "×" {:title "Delete"})])]]))

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
        "«"]
       [:button {:class "btn ghost" :aria-label "Previous page" :title "Previous page"
                 :disabled (or loading? (not previous?))
                 :on-click (fn [_] (set-page! (dec page)))}
        "‹"]
       [:button {:class "btn ghost" :aria-label "Next page" :title "Next page"
                 :disabled (or loading? error (not next?))
                 :on-click (fn [_] (set-page! (inc page)))}
        "›"]
       [:button {:class "btn ghost" :aria-label "Last page" :title "Last page"
                 :disabled (or loading? error (not next?))
                 :on-click (fn [_] (set-page! (dec pages)))}
        "»"]]]]))

(defn recordings-table
  "Render a page of sessions as a table or mobile cards from pagination hook state."
  [{:keys [items drafts-count show-drafts? loading? error set-show-drafts! refresh!] :as state}]
  (let [mobile? (hooks/use-media-query mobile-breakpoint-query)]
    [:div {:class "card" :aria-busy (boolean loading?)}
     [:div {:class "row sessions-table-header"}
      [:div {:class "card-title"} "Sessions"]
      [:div {:class "spacer"}]
      (when (or show-drafts? (pos? drafts-count))
        [:label {:class "muted sessions-drafts"}
         [:input {:type "checkbox" :checked (boolean show-drafts?) :disabled loading?
                  :on-change (fn [e] (set-show-drafts! (.. e -target -checked)))}]
         (str "Show drafts (" drafts-count ")")])]
     (cond
       loading?
       [:div {:class "muted sessions-list-message"} "Loading sessions…"]

       error
       [:div {:class "muted sessions-list-message" :role "alert"} error]

       (empty? items)
       [:div {:class "muted sessions-list-message"}
        (if (and (not show-drafts?) (pos? drafts-count))
          "No recorded sessions yet. Enable Show drafts to see your drafts."
          "No sessions yet.")]

       mobile?
       [:div {:class "list"}
        (for [{:keys [session_id] :as rec} items]
          ^{:key (str "rec-card-" session_id)}
          [recordings-card rec refresh!])]

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
           [recordings-row rec refresh!])]])
     [pagination-footer state]]))

(defn recordings-page
  "Render Sessions with server pagination and the existing create/record actions."
  []
  (let [{:keys [loading? refresh!] :as state} (recordings-page/use-recordings-page)
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
       [:div {:class "muted"} "All sessions (drafts and recordings)."]]
      [:div {:class "row"}
       [:button {:class "btn" :disabled loading? :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn" :disabled loading? :on-click (fn [_] (new-draft!))}
        "New session (draft)"]
       [:button {:class "btn primary" :on-click (fn [_] (go-record!))}
        "Record"]]]
     [recordings-table state]]))

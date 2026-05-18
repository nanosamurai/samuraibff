(ns samuraibff.ui.components.pages.recordings
  "Sessions list page.

  This page shows all sessions/recordings for the tenant."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.session-request :as session.req]
   [samuraibff.ui.store :as store]
   [samuraibff.ui.util :as util]
   ["react" :as react]))

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

      (= status "finished")
      {:label "Finished"
       :badge-class "muted"
       :icon "■"
       :tooltip "Recording stopped; final transcript not available yet"}

      (= status "created")
      {:label "Created"
       :badge-class "muted"
       :icon "○"
       :tooltip "Draft session (recording not started)"}

      has-final?
      {:label "Finalized"
       :badge-class "ok"
       :icon "✓"
       :tooltip "Final transcript is available"}

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

  Returns: hiccup <tr>"
  [{:keys [session_id title started_at created_at] :as rec}]
  (let [{:keys [label badge-class tooltip]
         icon-glyph :icon} (rec->display-status rec)
        recordable? (false? (:has_recording rec))
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
                 :title "Recording is already completed"}
          (shared/icon "●" {:title "Not available"})])

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
        (shared/icon "×" {:title "Delete"})]]]]))

(defn- recordings-card
  "Render a single recording as a stacked card (mobile layout).

  Inputs:
  - rec: a map from /api/recordings

  Returns: hiccup <div>."
  [{:keys [session_id title started_at created_at] :as rec}]
  (let [{:keys [label badge-class tooltip]
         icon-glyph :icon} (rec->display-status rec)
        recordable? (false? (:has_recording rec))
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
                :title "Recording is already completed"}
         (shared/icon "●" {:title "Not available"})])

      [:button {:class "btn ghost icon"
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
       (shared/icon "×" {:title "Delete"})]]]))

(defn recordings-table
  "Table of DB-backed recordings."
  []
  (let [recs0 (->> (hooks/use-atom store/recordings-db*)
                   (sort-by :created_at)
                   reverse)
        mobile? (hooks/use-media-query mobile-breakpoint-query)
        show-drafts?* (react/useState false)
        show-drafts? (aget show-drafts?* 0)
        set-show-drafts! (aget show-drafts?* 1)
        recs (if show-drafts?
               (vec recs0)
               (vec (remove (fn [r] (false? (:has_recording r))) recs0)))
        drafts-count (count (filter (fn [r] (false? (:has_recording r))) recs0))]
    [:div {:class "card"}
     [:div {:class "row" :style {:alignItems "center"}}
      [:div {:class "card-title"} "Sessions"]
      [:div {:class "spacer"}]
      (when (pos? drafts-count)
        [:label {:class "muted"
                 :style {:display "inline-flex" :gap "8px" :alignItems "center"}}
         [:input {:type "checkbox"
                  :checked (boolean show-drafts?)
                  :on-change (fn [e]
                               (set-show-drafts! (.. e -target -checked)))}]
         (str "Show drafts (" drafts-count ")")])]

     (cond
       (empty? recs)
       [:div {:class "muted"} "No sessions yet."]

       mobile?
       [:div {:class "list"}
        (for [{:keys [session_id] :as rec} recs]
          ^{:key (str "rec-card-" session_id)}
          [recordings-card rec])]

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
         (for [{:keys [session_id] :as rec} recs]
           ^{:key (str "rec-" session_id)}
           [recordings-row rec])]])]))

(defn recordings-page
  "Sessions page."
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
                       (.finally (fn [] (set-loading! false)))))
        new-draft! (fn []
                     (store/append-log! "[ui] creating session draft...")
                     (let [req (session.req/create-session-request-body @store/session*)]
                       (-> (api/create-session! req)
                           (.then (fn [{:keys [session_id title]}]
                                    (store/set-session-id! session_id)
                                    (store/set-session-created-at-ms! (util/now-ms))
                                    (store/set-session-title! (or title ""))
                                    (store/set-session-status! "created")
                                    (store/add-recording! {:session_id session_id
                                                           :created_at_ms (util/now-ms)
                                                           :status :ready})
                                    ;; Refresh list so the draft is visible immediately.
                                    (refresh!)
                                    (store/append-log! (str "[ui] new draft session " session_id))))
                           (.catch (fn [e]
                                     (store/append-log! (str "[ui] failed creating session draft: " e)))))))

        go-record! (fn []
                     (router/navigate! {:page :live :params {}}))]
    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])
    [:div {:class "page"}
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Sessions"]
       [:div {:class "muted"} "All sessions (drafts and recordings)."]]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (new-draft!))}
        "New session (draft)"]
       [:button {:class "btn primary"
                 :on-click (fn [_] (go-record!))}
        "Record"]]]
     [recordings-table]]))

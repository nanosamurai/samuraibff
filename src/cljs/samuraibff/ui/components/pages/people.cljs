(ns samuraibff.ui.components.pages.people
  "People page (Admin only): add people, change roles, suspend and reactivate
  accounts, resend setup emails and retry pending sign-in sync.

  The BFF authorizes every call. This page only renders for principals with
  the `manage_people` capability hint."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.access :as access]
   [samuraibff.ui.access-api :as access-api]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.icons :as icons]
   [samuraibff.ui.pagination :as pagination]
   [samuraibff.ui.permissions :as permissions]
   ["react" :as react]))

(def ^:private search-debounce-ms 300)

(defn- error-text
  "Return a safe user-facing message for a rejected People call."
  [e fallback]
  (access/error-message (api/error-code e) fallback))

(defn- role-help
  "Render the role description list. Returns hiccup."
  []
  [:div {:class "muted" :style {:fontSize "12px" :display "flex" :flexDirection "column" :gap "4px"}}
   (for [{:keys [value label description]} access/role-descriptions]
     [:div {:key value} [:strong label] " — " description])
   [:div "No role can read someone else's private sessions."]])

(defn- add-person-modal
  "Dialog to add a person with email, optional name and role.

  Inputs: {:open? boolean :on-close (fn []) :on-added (fn [response email])}.
  Returns: hiccup or nil."
  [{:keys [open? on-close on-added]}]
  (let [[email set-email!] (react/useState "")
        [person-name set-name!] (react/useState "")
        [role set-role!] (react/useState "member")
        [saving? set-saving!] (react/useState false)
        [error set-error!] (react/useState nil)
        close! (fn []
                 (set-email! "") (set-name! "") (set-role! "member")
                 (set-error! nil) (set-saving! false)
                 (on-close))
        valid? (boolean (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+" (str/trim (str email))))
        submit! (fn []
                  (set-saving! true)
                  (set-error! nil)
                  (-> (access-api/add-person! {:email email :name person-name :role role})
                      (.then (fn [resp]
                               (on-added resp (str/trim email))
                               (close!)))
                      (.catch (fn [e]
                                (set-saving! false)
                                (set-error! (error-text e "Could not add this person. Try again."))))))]
    (when open?
      [:div {:class "modal-overlay" :on-click (fn [_] (when-not saving? (close!)))}
       [:div {:class "modal" :role "dialog" :aria-modal true :aria-label "Add person"
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "modal-title"} "Add person"]
        [:div {:class "muted" :style {:marginBottom "10px"}}
         "We send a setup email. The person sets a password and then signs in."]
        [:div {:class "field" :style {:marginBottom "10px"}}
         [:label {:class "label" :htmlFor "person-email"} "Email"]
         [:input {:id "person-email" :type "email" :value email :disabled saving?
                  :placeholder "name@example.com" :autoComplete "off"
                  :on-change (fn [e] (set-email! (.. e -target -value)))}]]
        [:div {:class "field" :style {:marginBottom "10px"}}
         [:label {:class "label" :htmlFor "person-name"} "Name (optional)"]
         [:input {:id "person-name" :value person-name :disabled saving?
                  :on-change (fn [e] (set-name! (.. e -target -value)))}]]
        [:div {:class "field" :style {:marginBottom "10px"}}
         [:label {:class "label" :htmlFor "person-role"} "Role"]
         [:select {:id "person-role" :value role :disabled saving?
                   :on-change (fn [e] (set-role! (.. e -target -value)))}
          (for [{:keys [value label]} access/role-descriptions]
            [:option {:key value :value value} label])]]
        [role-help]
        (when error
          [:div {:class "badge bad" :role "alert" :style {:marginTop "10px"}} error])
        [:div {:class "row" :style {:justifyContent "flex-end" :marginTop "12px"}}
         [:button {:class "btn" :type "button" :disabled saving? :on-click (fn [_] (close!))}
          "Cancel"]
         [:button {:class "btn primary" :type "button" :disabled (or saving? (not valid?))
                   :on-click (fn [_] (submit!))}
          (if saving? "Adding…" "Add person")]]]])))

(defn- person-row
  "Render one People table row.

  Inputs: person map from /api/people; {:busy? boolean :on-patch (fn [person patch
  confirm-text]) :on-resend (fn [person])}.
  Returns: hiccup <tr>."
  [{:keys [display_name email role status setup_state identity_sync_state last_seen_at is_me]
    :as person}
   {:keys [busy? on-patch on-resend]}]
  (let [{status-label :label status-kind :kind} (access/person-status-label status)
        setup (access/setup-label setup_state)
        sync-label (access/sync-pending-label identity_sync_state)
        who (or (not-empty (str display_name)) email "")]
    [:tr
     [:td
      [:div {:style {:fontWeight 650}} who (when is_me [:span {:class "muted"} " (you)"])]
      (when (and (seq (str email)) (not= who email))
        [:div {:class "muted" :style {:fontSize "12px"}} email])]
     [:td
      [:select {:value (str role) :disabled busy? :aria-label (str "Role for " who)
                :on-change (fn [e]
                             (let [new-role (.. e -target -value)]
                               (on-patch person {:role new-role}
                                         (str "Change " who " to " (access/role-label new-role) "?"))))}
       (for [{:keys [value label]} access/role-descriptions]
         [:option {:key value :value value} label])]]
     [:td
      [:div {:style {:display "flex" :flexDirection "column" :gap "4px" :alignItems "flex-start"}}
       [:span {:class (str "badge " (name status-kind))} status-label]
       (when setup [:span {:class "muted" :style {:fontSize "12px"}} setup])
       (when sync-label
         [:button {:class "btn ghost" :type "button" :disabled busy?
                   :title "Send the same change to the sign-in service again"
                   :on-click (fn [_] (on-patch person {:status status} nil))}
          sync-label])
       (when (seq (str last_seen_at))
         [:span {:class "muted" :style {:fontSize "12px"}}
          (str "Last seen " (shared/iso->local last_seen_at))])]]
     [:td {:style {:textAlign "right"}}
      [:div {:class "row" :style {:justifyContent "flex-end"}}
       (when (= "pending" status)
         [:button {:class "btn" :type "button" :disabled busy?
                   :on-click (fn [_] (on-resend person))}
          "Resend setup email"])
       (case status
         "suspended"
         [:button {:class "btn" :type "button" :disabled busy?
                   :on-click (fn [_] (on-patch person {:status "active"}
                                               (str "Reactivate " who "?")))}
          "Reactivate"]
         [:button {:class "btn ghost" :type "button" :disabled (or busy? is_me)
                   :title (when is_me "You can't suspend your own account")
                   :on-click (fn [_] (on-patch person {:status "suspended"}
                                               (str "Suspend " who "?\n\nThey can no longer sign in or open sessions.")))}
          "Suspend"])]]]))

(defn- use-people-list
  "Load one page of people for the query state.

  Inputs: query map {:q :page :page-size :revision}.
  Returns: {:data map? :loading? boolean :error string?}."
  [{:keys [q page page-size revision] :as query}]
  (let [[result set-result!] (react/useState nil)]
    (react/useEffect
     (fn []
       (let [active? (atom true)]
         (set-result! #(assoc % :loading? true))
         (-> (access-api/list-people! {:q q :limit page-size :offset (* page page-size)})
             (.then (fn [resp] (when @active? (set-result! {:query query :data resp :loading? false}))))
             (.catch (fn [e]
                       (when @active?
                         (set-result! {:query query :loading? false
                                       :error (error-text e "Could not load people. Use Refresh to try again.")})))))
         (fn [] (reset! active? false))))
     #js [q page page-size revision])
    {:data (:data result)
     :loading? (or (:loading? result) (not= query (:query result)))
     :error (:error result)}))

(defn people-page
  "People page for Admins. Returns hiccup."
  []
  (let [[query set-query!] (react/useState {:q "" :page 0 :page-size 20 :revision 0})
        [search set-search!] (react/useState "")
        [adding? set-adding!] (react/useState false)
        [busy-id set-busy-id!] (react/useState nil)
        [notice set-notice!] (react/useState nil)
        [action-error set-action-error!] (react/useState nil)
        {:keys [data loading? error]} (use-people-list query)
        items (vec (or (:items data) []))
        total (or (:total data) 0)
        provisioning? (true? (:provisioning_enabled data))
        {:keys [page pages from to previous? next?]} (pagination/page-info total (:page query) (:page-size query))
        tenant (permissions/tenant-name)
        refresh! (fn [] (set-query! #(update % :revision inc)))
        on-patch (fn [person patch confirm-text]
                   (when (or (nil? confirm-text) (js/confirm confirm-text))
                     (set-busy-id! (:id person))
                     (set-notice! nil)
                     (set-action-error! nil)
                     (-> (access-api/update-person! (:id person) patch)
                         (.then (fn [{:keys [upstream]}]
                                  (when (= "pending" upstream)
                                    (set-notice! "Saved. The sign-in service did not confirm the change yet. Use the retry button on the row."))))
                         (.catch (fn [e]
                                   (set-action-error! (error-text e "Could not save the change. Try again."))))
                         (.finally (fn [] (set-busy-id! nil) (refresh!))))))
        on-resend (fn [person]
                    (set-busy-id! (:id person))
                    (set-notice! nil)
                    (set-action-error! nil)
                    (-> (access-api/resend-setup-email! (:id person))
                        (.then (fn [{:keys [setup_email]}]
                                 (if (= "failed" setup_email)
                                   (set-action-error! "The setup email could not be sent. Try again later.")
                                   (set-notice! "Setup email sent."))))
                        (.catch (fn [e]
                                  (set-action-error! (error-text e "Could not send the setup email. Try again."))))
                        (.finally (fn [] (set-busy-id! nil) (refresh!)))))
        on-added (fn [{:keys [setup_email]} email]
                   (set-action-error! nil)
                   (set-notice! (if (= "failed" setup_email)
                                  (str "Added " email ". The setup email could not be sent. Use Resend setup email.")
                                  (str "Added " email ". We sent a setup email.")))
                   (refresh!))]
    (react/useEffect
     (fn []
       (let [timer (js/setTimeout
                    (fn [] (set-query! #(if (= (:q %) search) % (assoc % :q search :page 0))))
                    search-debounce-ms)]
         (fn [] (js/clearTimeout timer))))
     #js [search])
    [:div {:class "page"}
     [add-person-modal {:open? adding? :on-close #(set-adding! false) :on-added on-added}]
     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "People"]
       [:div {:class "muted"} (str (when tenant (str tenant " · ")) "Manage accounts and access.")]]
      [:div {:class "row"}
       [:button {:class "btn" :type "button" :disabled loading? :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn primary" :type "button"
                 :disabled (or (not provisioning?) loading?)
                 :title (when (and data (not provisioning?))
                          "Adding people from the app is not enabled")
                 :on-click (fn [_] (set-adding! true))}
        "Add person"]]]
     (when (and data (not provisioning?))
       [:div {:class "muted" :role "status"}
        "Adding people from the app is not enabled. Ask your operator to add accounts."])
     [:div {:class "card"} [role-help]]
     (when notice [:div {:class "badge ok" :role "status"} notice])
     (when action-error [:div {:class "badge bad" :role "alert"} action-error])
     [:div {:class "card" :aria-busy (boolean loading?)}
      [:div {:class "row sessions-table-header"}
       [:div {:class "card-title"} "People"]
       [:div {:class "spacer"}]
       [:input {:type "search" :value search :placeholder "Find a person"
                :aria-label "Find a person"
                :on-change (fn [e] (set-search! (.. e -target -value)))}]]
      (cond
        (and loading? (empty? items)) [:div {:class "muted sessions-list-message"} "Loading people…"]
        error [:div {:class "muted sessions-list-message" :role "alert"} error]
        (empty? items) [:div {:class "muted sessions-list-message"}
                        (if (str/blank? (:q query)) "No people yet." "No one matches this search.")]
        :else
        [:div {:style {:overflowX "auto"}}
         [:table {:class "table"}
          [:thead
           [:tr
            [:th "Person"]
            [:th "Role"]
            [:th "Access"]
            [:th {:style {:textAlign "right"}} "Actions"]]]
          [:tbody
           (for [person items]
             ^{:key (str "person-" (:id person))}
             [person-row person {:busy? (or loading? (some? busy-id))
                                 :on-patch on-patch
                                 :on-resend on-resend}])]]])
      [:nav {:class "sessions-pagination" :aria-label "People pagination"}
       [:span {:class "muted sessions-range"} (str from "–" to " of " total)]
       [:div {:class "sessions-pagination-controls"}
        [:span {:class "muted sessions-page-number"} (str (inc page) " / " pages)]
        [:div {:class "sessions-page-buttons"}
         [:button {:class "btn ghost" :aria-label "Previous page" :title "Previous page"
                   :disabled (or loading? (not previous?))
                   :on-click (fn [_] (set-query! #(assoc % :page (dec page))))}
          (icons/icon :chevron-left)]
         [:button {:class "btn ghost" :aria-label "Next page" :title "Next page"
                   :disabled (or loading? (not next?))
                   :on-click (fn [_] (set-query! #(assoc % :page (inc page))))}
          (icons/icon :chevron-right)]]]]]
     [:div {:class "muted"} "Keep at least one active Admin."]]))

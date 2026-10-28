(ns samuraibff.ui.components.pages.api-credentials
  "API credentials management page (tenant-scoped).

  Access (capability hints from `/api/me`; the BFF authorizes again):
  - Admins (`manage_credentials`) see every credential with its manager,
    create organization keys and configure them.
  - Builders (`manage_own_credentials`) see and manage their own.
  - Members see an explanation instead of the list.

  Kinds: a personal key acts as its owner (its sessions are the owner's); an
  organization key owns its sessions, which its manager manages."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.access :as access]
   [samuraibff.ui.access-api :as access-api]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.api-credentials-store :as api-creds.store]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.permissions :as permissions]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(def ^:private mobile-breakpoint-query
  "CSS media query used as the threshold for mobile layout.

  Must match the @media rule in `resources/public/index.html`."
  "(max-width: 768px)")

(def ^:private disable-pending-text
  "Disable in sign-in system pending — retry")

(defn- safe-error
  "Return a user-facing message for a failed credential call."
  [e]
  (access/error-message (api/error-code e) (shared/safe-http-error e)))

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
                               (-> (shared/copy-to-clipboard! (or client-secret ""))
                                   (.then (fn [ok?]
                                            (store/api-credentials-mark-secret-copied! (true? ok?))))))}
          (if copied? "Copied" "Copy secret")]
         [:button {:class "btn"
                   :on-click (fn [_]
                               (store/api-credentials-close-secret!))}
          "Close"]]]])))

(defn- rotate!
  "Ask for confirmation, rotate a credential secret and show it once."
  [id name refresh!]
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
                  (store/api-credentials-set-error! (safe-error e))))
        (.finally (fn []
                    (store/api-credentials-set-loading! false))))))

(defn- revoke!
  "Revoke a credential, or retry a pending sign-in disable when retry? is true.

  A response with `upstream: pending` means the sign-in system did not
  confirm the disable yet; sending DELETE again retries it."
  [id name refresh! retry?]
  (when (or retry?
            (js/confirm (str "Revoke credential " name "?\n\nThis will disable the Keycloak client.")))
    (store/api-credentials-set-loading! true)
    (store/api-credentials-set-error! nil)
    (-> (api/revoke-api-credential! id)
        (.then (fn [resp]
                 ;; Optimistic: mark revoked, then refresh.
                 (store/api-credentials-mark-revoked! id)
                 (when (= "pending" (:upstream resp))
                   (store/api-credentials-set-error!
                    (str "Revoked in nanosamur.ai. " disable-pending-text " with Retry disable.")))
                 (refresh!)))
        (.catch (fn [e]
                  (store/api-credentials-set-error! (safe-error e))))
        (.finally (fn []
                    (store/api-credentials-set-loading! false))))))

(defn- credential-flags
  "Return derived display flags for a credential item."
  [{:keys [revoked_at identity_sync_state] :as cred}]
  (let [revoked? (some? revoked_at)]
    {:revoked? revoked?
     :can-manage? (not (false? (:can_manage cred)))
     :can-rotate? (not (false? (:can_rotate cred)))
     :can-configure? (true? (:can_configure cred))
     :disable-pending? (and revoked? (= "disable_pending" identity_sync_state))}))

(defn- status-node
  "Render the credential status badge and a pending-disable note."
  [{:keys [revoked? disable-pending?]}]
  [:span {:style {:display "inline-flex" :flexDirection "column" :gap "4px"}}
   (if revoked?
     [:span {:class "badge muted"} "Revoked"]
     [:span {:class "badge ok"} "Active"])
   (when disable-pending?
     [:span {:class "muted" :style {:fontSize "12px"}} disable-pending-text])])

(defn- credential-actions
  "Render Rotate and Revoke (or Retry disable) buttons for a credential.

  Inputs: credential, flags from `credential-flags`, refresh! fn and the
  attributes of the container element (table row or mobile card layout)."
  [{:keys [id name] :as cred} {:keys [revoked? can-manage? can-rotate? can-configure? disable-pending?]}
   refresh! container-attrs on-edit]
  (let [id (or id "")]
    [:div container-attrs
     (when can-configure?
       [:button {:class "btn"
                 :disabled revoked?
                 :title "Change manager or who sees its sessions"
                 :on-click (fn [_] (on-edit cred))}
        "Edit"])
     [:button {:class "btn"
               :disabled (or revoked? (not can-rotate?))
               :title (if can-rotate?
                        "Rotate secret"
                        "Only the owner of a personal key can rotate it")
               :on-click (fn [_] (rotate! id name refresh!))}
      "Rotate"]
     (if disable-pending?
       [:button {:class "btn ghost"
                 :disabled (not can-manage?)
                 :title "Send the disable to the sign-in system again"
                 :on-click (fn [_] (revoke! id name refresh! true))}
        "Retry disable"]
       [:button {:class "btn ghost"
                 :disabled (or revoked? (not can-manage?))
                 :title "Revoke credential"
                 :on-click (fn [_] (revoke! id name refresh! false))}
        "Revoke"])]))

(defn- manager-label
  "Return the manager display text for a credential.

  Credentials without a manager (created before roles) are Admin-managed."
  [cred]
  (or (access/owner-name (:manager cred)) "Admins"))

(defn- kind-cell
  "Render the key kind and what its sessions are."
  [cred]
  [:div {:style {:display "flex" :flexDirection "column" :gap "2px"}}
   [:span (access/credential-kind-label (:kind cred))]
   [:span {:class "muted" :style {:fontSize "12px"}}
    (access/credential-access-label cred (permissions/tenant-name))]])

(defn- api-credentials-row
  [{:keys [name keycloak_client_id created_at last_used_at] :as cred} refresh! show-manager? on-edit]
  (let [flags (credential-flags cred)
        client-id (or keycloak_client_id "")]
    [:tr
     [:td name]
     [:td [kind-cell cred]]
     (when show-manager?
       [:td {:class "muted"} (manager-label cred)])
     [:td {:class "mono"} client-id]
     [:td {:class "muted"} (or (shared/iso->local created_at) "")]
     [:td {:class "muted"} (or (shared/iso->local last_used_at) "")]
     [:td [status-node flags]]
     [:td {:style {:textAlign "right"}}
      [credential-actions cred flags refresh! {:class "row" :style {:justifyContent "flex-end"}} on-edit]]]))

(defn- api-credentials-card
  "Render a credential as a stacked card (mobile layout).

  Inputs:
  - cred: credential map
  - refresh!: (fn [])
  - show-manager?: boolean
  - on-edit: (fn [cred]) for organization keys

  Returns: hiccup."
  [{:keys [name keycloak_client_id created_at last_used_at] :as cred} refresh! show-manager? on-edit]
  (let [flags (credential-flags cred)
        client-id (or keycloak_client_id "")]
    [:div {:class "list-item"}
     [:div {:style {:display "flex" :gap "10px" :alignItems "flex-start"}}
      [:div {:style {:flex "1" :minWidth 0}}
       [:div {:class "list-item-title"} (or name "")]
       [:div {:class "list-item-sub"}
        [:span {:class "mono" :style {:wordBreak "break-all"}} client-id]]
       [:div {:class "list-item-meta"}
        [:span (str (access/credential-kind-label (:kind cred)) " · "
                    (access/credential-access-label cred (permissions/tenant-name)))]
        (when show-manager?
          [:span (str "Manager: " (manager-label cred))])
        [:span (str "Created: " (or (shared/iso->local created_at) "—"))]
        [:span (str "Last used: " (or (shared/iso->local last_used_at) "—"))]]]
      [:div [status-node flags]]]

     [credential-actions cred flags refresh! {:class "list-item-actions"} on-edit]]))

(defn- use-manager-options
  "React hook: return active Builders and Admins as manager choices.

  Loads People only when enabled? (Admins). Returns a vector of
  {:id :display_name}."
  [enabled?]
  (let [[options set-options!] (react/useState [])]
    (react/useEffect
     (fn []
       (when enabled?
         (-> (access-api/list-people! {:limit 200 :offset 0})
             (.then (fn [resp]
                      (set-options! (->> (:items resp)
                                         (filter #(and (= "active" (:status %))
                                                       (contains? #{"builder" "admin"} (:role %))))
                                         (mapv #(select-keys % [:id :display_name]))))))
             (.catch (fn [_] (set-options! [])))))
       js/undefined)
     #js [enabled?])
    options))

(defn- manager-select
  "Select an organization key manager; the empty value means Admins."
  [{:keys [value options on-change disabled? id]}]
  [:select {:id id :value (or value "") :disabled disabled?
            :on-change (fn [e] (on-change (not-empty (.. e -target -value))))}
   [:option {:value ""} "Admins (no single manager)"]
   (for [{:keys [id display_name]} options]
     [:option {:key id :value id} (or display_name id)])])

(defn- audience-select
  [{:keys [value on-change disabled? id]}]
  [:select {:id id :value (or value "private") :disabled disabled?
            :on-change (fn [e] (on-change (.. e -target -value)))}
   [:option {:value "private"} "Only the manager"]
   [:option {:value "tenant"} (access/audience-label "tenant" (permissions/tenant-name))]])

(defn- configure-dialog
  "Modal to change an organization key's manager and default audience."
  [{:keys [cred options on-close on-saved]}]
  (let [[manager set-manager!] (react/useState (get-in cred [:manager :id]))
        [audience set-audience!] (react/useState (or (:audience cred) "private"))
        [saving? set-saving!] (react/useState false)
        [error set-error!] (react/useState nil)]
    [:div {:class "modal-overlay" :on-click (fn [_] (on-close))}
     [:div {:class "modal" :role "dialog" :aria-label "Edit organization key"
            :on-click (fn [e] (.stopPropagation e))}
      [:div {:class "modal-title"} (str "Edit " (:name cred))]
      [:div {:class "field"}
       [:label {:class "label" :htmlFor "cred-manager"} "Manager"]
       [manager-select {:id "cred-manager" :value manager :options options
                        :disabled? saving? :on-change set-manager!}]
       [:div {:class "hint"} "The manager can play, share and delete this key's sessions."]]
      [:div {:class "field" :style {:marginTop "12px"}}
       [:label {:class "label" :htmlFor "cred-audience"} "Who sees new sessions"]
       [audience-select {:id "cred-audience" :value audience :disabled? saving? :on-change set-audience!}]
       [:div {:class "hint"} "Applies to sessions recorded from now on."]]
      (when error [:div {:class "badge bad" :role "alert" :style {:marginTop "10px"}} error])
      [:div {:class "row" :style {:justifyContent "flex-end" :marginTop "14px"}}
       [:button {:class "btn" :type "button" :disabled saving? :on-click (fn [_] (on-close))} "Cancel"]
       [:button {:class "btn primary" :type "button" :disabled saving?
                 :on-click (fn [_]
                             (set-saving! true)
                             (-> (api/configure-api-credential! (:id cred) {:manager_id manager :audience audience})
                                 (.then (fn [_] (on-saved)))
                                 (.catch (fn [e] (set-error! (safe-error e))))
                                 (.finally (fn [] (set-saving! false)))))}
        "Save"]]]]))

(defn- members-explanation
  "Render the API Credentials page for people who cannot manage credentials."
  []
  [:div {:class "page"}
   [:div {:class "page-header"}
    [:div
     [:div {:class "page-title"} "API Credentials"]
     [:div {:class "muted"}
      "Machine-to-machine credentials for API access."]]]
   [:div {:class "card"}
    [:div {:class "muted"} "Automation credentials are managed by Builders and Admins."]]])

(defn- api-credentials-manager-page
  "API credentials list and create form for Builders and Admins.

  Inputs: show-manager? boolean (Admins see every credential and its manager)."
  [show-manager?]
  (let [st (hooks/use-atom store/api-credentials*)
        mobile? (hooks/use-media-query mobile-breakpoint-query)
        items (api-creds.store/visible-items st)
        loading? (:loading? st)
        error (:error st)
        show-revoked? (:show-revoked? st)

        name* (react/useState "")
        name (aget name* 0)
        set-name! (aget name* 1)
        [kind set-kind!] (react/useState "personal")
        [read-scope set-read-scope!] (react/useState "credential")
        [audience set-audience!] (react/useState "private")
        [manager set-manager!] (react/useState nil)
        [editing set-editing!] (react/useState nil)
        manager-options (use-manager-options show-manager?)
        organization? (and show-manager? (= "organization" kind))

        refresh! (fn []
                   (store/api-credentials-set-loading! true)
                   (store/api-credentials-set-error! nil)
                   (-> (api/list-api-credentials!)
                       (.then (fn [resp]
                                (store/api-credentials-set-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/api-credentials-set-error! (safe-error e))))
                       (.finally (fn []
                                   (store/api-credentials-set-loading! false)))))

        create! (fn []
                  (store/api-credentials-set-loading! true)
                  (store/api-credentials-set-error! nil)
                  (-> (api/create-api-credential!
                       (if organization?
                         {:name name :kind "organization" :audience audience :manager_id manager}
                         {:name name :kind "personal" :read_scope read-scope}))
                      (.then (fn [resp]
                               (set-name! "")
                               (store/api-credentials-open-secret!
                                {:credential-id (:credential_id resp)
                                 :client-id (:client_id resp)
                                 :client-secret (:client_secret resp)})
                               (refresh!)))
                      (.catch (fn [e]
                                (store/api-credentials-set-error! (safe-error e))))
                      (.finally (fn []
                                  (store/api-credentials-set-loading! false)))))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])

    [:div {:class "page"}
     [api-credentials-secret-modal]
     (when editing
       [configure-dialog {:cred editing
                          :options manager-options
                          :on-close (fn [] (set-editing! nil))
                          :on-saved (fn [] (set-editing! nil) (refresh!))}])

     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "API Credentials"]
       [:div {:class "muted"}
        (str "Machine-to-machine credentials for API access. "
             (if show-manager? "All credentials in this organization." "Your credentials."))]
       (when (seq error)
         [:div {:class "badge bad" :style {:marginTop "10px"}} error])]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]]]

     [:div {:class "muted" :role "note"}
      (str "A personal key acts as you: what it records is yours and appears under My sessions. "
           "It never manages people, keys or definitions."
           (when show-manager?
             " An organization key belongs to the organization: its manager manages its sessions."))]

     [:div {:class "card"}
      [:div {:class "card-title"} "Create credential"]
      [:div {:class "row" :style {:alignItems "flex-end"}}
       [:div {:class "field"}
        [:label {:class "label" :htmlFor "cred-name"} "Name"]
        [:input {:id "cred-name"
                 :placeholder "Name (e.g. my-sdk)"
                 :value name
                 :on-change (fn [e] (set-name! (.. e -target -value)))}]]
       (when show-manager?
         [:div {:class "field"}
          [:label {:class "label" :htmlFor "cred-kind"} "Type"]
          [:select {:id "cred-kind" :value kind :on-change (fn [e] (set-kind! (.. e -target -value)))}
           [:option {:value "personal"} "Personal (acts as you)"]
           [:option {:value "organization"} "Organization"]]])
       (if organization?
         [:<>
          [:div {:class "field"}
           [:label {:class "label" :htmlFor "cred-new-manager"} "Manager"]
           [manager-select {:id "cred-new-manager" :value manager :options manager-options
                            :on-change set-manager!}]]
          [:div {:class "field"}
           [:label {:class "label" :htmlFor "cred-new-audience"} "Who sees its sessions"]
           [audience-select {:id "cred-new-audience" :value audience :on-change set-audience!}]]]
         [:div {:class "field"}
          [:label {:class "label" :htmlFor "cred-scope"} "It can read"]
          [:select {:id "cred-scope" :value read-scope
                    :on-change (fn [e] (set-read-scope! (.. e -target -value)))}
           [:option {:value "credential"} "Only sessions it records"]
           [:option {:value "owner"} "Everything I can see"]]])
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
        (if mobile?
          [:div {:class "list"}
           (for [c (->> items
                        (sort-by :created_at)
                        reverse)]
             ^{:key (str "cred-card-" (:id c))}
             [api-credentials-card c refresh! show-manager? set-editing!])]
          [:table {:class "table"}
           [:thead
            [:tr
             [:th "Name"]
             [:th "Type"]
             (when show-manager? [:th "Manager"])
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
              [api-credentials-row c refresh! show-manager? set-editing!])]]))]]))

(defn api-credentials-page
  "API credentials page: the manager view for Builders/Admins, otherwise an
  explanation. Returns hiccup."
  []
  (let [manage-own? (permissions/use-capability? :manage_own_credentials)
        manage-all? (permissions/use-capability? :manage_credentials)]
    (if (or manage-own? manage-all?)
      [api-credentials-manager-page manage-all?]
      [members-explanation])))

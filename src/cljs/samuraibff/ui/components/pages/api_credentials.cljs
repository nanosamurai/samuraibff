(ns samuraibff.ui.components.pages.api-credentials
  "API credentials management page (tenant-scoped)."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.api-credentials-store :as api-creds.store]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(def ^:private mobile-breakpoint-query
  "CSS media query used as the threshold for mobile layout.

  Must match the @media rule in `resources/public/index.html`."
  "(max-width: 768px)")

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

(defn- api-credentials-row
  [{:keys [id name keycloak_client_id created_at last_used_at revoked_at]} refresh!]
  (let [revoked? (some? revoked_at)
        id (or id "")
        client-id (or keycloak_client_id "")]
    [:tr
     [:td name]
     [:td {:class "mono"} client-id]
     [:td {:class "muted"} (or (shared/iso->local created_at) "")]
     [:td {:class "muted"} (or (shared/iso->local last_used_at) "")]
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
                                             (store/api-credentials-set-error! (shared/safe-http-error e))))
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
                                             (store/api-credentials-set-error! (shared/safe-http-error e))))
                                   (.finally (fn []
                                               (store/api-credentials-set-loading! false))))))}
        "Revoke"]]]]))

(defn- api-credentials-card
  "Render a credential as a stacked card (mobile layout).

  Inputs:
  - cred: credential map
  - refresh!: (fn [])

  Returns: hiccup." 
  [{:keys [id name keycloak_client_id created_at last_used_at revoked_at]} refresh!]
  (let [revoked? (some? revoked_at)
        id (or id "")
        client-id (or keycloak_client_id "")
        status-node (if revoked?
                      [:span {:class "badge muted"} "Revoked"]
                      [:span {:class "badge ok"} "Active"])]
    [:div {:class "list-item"}
     [:div {:style {:display "flex" :gap "10px" :alignItems "flex-start"}}
      [:div {:style {:flex "1" :minWidth 0}}
       [:div {:class "list-item-title"} (or name "")]
       [:div {:class "list-item-sub"}
        [:span {:class "mono" :style {:wordBreak "break-all"}} client-id]]
       [:div {:class "list-item-meta"}
        [:span (str "Created: " (or (shared/iso->local created_at) "—"))]
        [:span (str "Last used: " (or (shared/iso->local last_used_at) "—"))]]]
      [:div status-node]]

     [:div {:class "list-item-actions"}
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
                                            (store/api-credentials-set-error! (shared/safe-http-error e))))
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
                                           (store/api-credentials-mark-revoked! id)
                                           (refresh!)))
                                  (.catch (fn [e]
                                            (store/api-credentials-set-error! (shared/safe-http-error e))))
                                  (.finally (fn []
                                              (store/api-credentials-set-loading! false))))))}
        "Revoke"]]]))

(defn api-credentials-page
  "API credentials management page (tenant-scoped)."
  []
  (let [st (hooks/use-atom store/api-credentials*)
        mobile? (hooks/use-media-query mobile-breakpoint-query)
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
                                 (store/api-credentials-set-error! (shared/safe-http-error e))))
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
                                (store/api-credentials-set-error! (shared/safe-http-error e))))
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
        (if mobile?
          [:div {:class "list"}
           (for [c (->> items
                        (sort-by :created_at)
                        reverse)]
             ^{:key (str "cred-card-" (:id c))}
             [api-credentials-card c refresh!])]
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
              [api-credentials-row c refresh!])]]))]]))

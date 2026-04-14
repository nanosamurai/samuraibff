(ns samuraibff.ui.components.pages.webhooks
  "Webhooks management page (tenant-scoped outbound endpoints).

  Notes:
  - Secrets are write-only: we never display existing secrets.
  - This page focuses on CRUD for webhook endpoints + subscriptions."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.api :as api]
   [samuraibff.ui.components.shared :as shared]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(def ^:private event-types
  [{:value "transcript.refined.segment" :label "transcript.refined.segment"}
   {:value "recording.finished" :label "recording.finished"}
   {:value "transcript.final.ready" :label "transcript.final.ready"}])

(def ^:private auth-types
  [{:value "none" :label "None"}
   {:value "hmac" :label "HMAC"}
   {:value "oauth" :label "OAuth (client_credentials)"}
   {:value "api_key" :label "API key"}])

(defn- checkbox
  [{:keys [checked? on-change]}]
  [:input {:type "checkbox"
           :checked (boolean checked?)
           :on-change (fn [e]
                        (when (fn? on-change)
                          (on-change (true? (.. e -target -checked)))))}])

(defn- subscriptions-checkboxes
  [{:keys [selected on-toggle]}]
  (let [selected (set (map str (or selected [])))]
    [:div {:style {:display "flex" :flexDirection "column" :gap "6px"}}
     (for [{:keys [value label]} event-types]
       ^{:key (str "ev-" value)}
       [:label {:style {:display "flex" :gap "8px" :alignItems "center"}}
        [checkbox {:checked? (contains? selected value)
                   :on-change (fn [checked?]
                                (when (fn? on-toggle)
                                  (on-toggle value checked?)))}]
        [:span {:class "mono"} label]])]))

(defn- webhook-form-modal
  "Create/edit modal.

  Inputs:
  - open?: boolean
  - mode: :create or :edit
  - initial: webhook map or nil
  - on-close: (fn [])
  - on-saved: (fn [])"
  [{:keys [open? mode initial on-close on-saved]}]
  (when (true? open?)
    (let [initial (or initial {})

          name* (react/useState (or (:name initial) ""))
          name (aget name* 0)
          set-name! (aget name* 1)

          url* (react/useState (or (:url initial) ""))
          url (aget url* 0)
          set-url! (aget url* 1)

          enabled?* (react/useState (boolean (if (contains? initial :enabled) (:enabled initial) true)))
          enabled? (aget enabled?* 0)
          set-enabled! (aget enabled?* 1)

          auth-type* (react/useState (or (some-> (:auth_type initial) str) "none"))
          auth-type (aget auth-type* 0)
          set-auth-type! (aget auth-type* 1)

          subs* (react/useState (vec (or (:subscriptions initial) [])))
          subs (aget subs* 0)
          set-subs! (aget subs* 1)

          ;; write-only secrets
          hmac-secret* (react/useState "")
          hmac-secret (aget hmac-secret* 0)
          set-hmac-secret! (aget hmac-secret* 1)

          api-key* (react/useState "")
          api-key (aget api-key* 0)
          set-api-key! (aget api-key* 1)

          oauth-token-url* (react/useState (or (:oauth_token_url initial) ""))
          oauth-token-url (aget oauth-token-url* 0)
          set-oauth-token-url! (aget oauth-token-url* 1)

          oauth-client-id* (react/useState (or (:oauth_client_id initial) ""))
          oauth-client-id (aget oauth-client-id* 0)
          set-oauth-client-id! (aget oauth-client-id* 1)

          oauth-scopes* (react/useState (or (:oauth_scopes initial) ""))
          oauth-scopes (aget oauth-scopes* 0)
          set-oauth-scopes! (aget oauth-scopes* 1)

          oauth-client-secret* (react/useState "")
          oauth-client-secret (aget oauth-client-secret* 0)
          set-oauth-client-secret! (aget oauth-client-secret* 1)

          api-key-header* (react/useState (or (:api_key_header_name initial) "Authorization"))
          api-key-header (aget api-key-header* 0)
          set-api-key-header! (aget api-key-header* 1)

          api-key-prefix* (react/useState (or (:api_key_prefix initial) "Bearer "))
          api-key-prefix (aget api-key-prefix* 0)
          set-api-key-prefix! (aget api-key-prefix* 1)

          ;; static headers: simple textarea "k:v" lines to keep UI compact
          static-headers-raw* (react/useState
                               (let [m (or (:static_headers initial) {})]
                                 (->> m
                                      (map (fn [[k v]] (str k ": " v)))
                                      (str/join "\n"))))
          static-headers-raw (aget static-headers-raw* 0)
          set-static-headers-raw! (aget static-headers-raw* 1)

          saving?* (react/useState false)
          saving? (aget saving?* 0)
          set-saving! (aget saving?* 1)

          err* (react/useState nil)
          err (aget err* 0)
          set-err! (aget err* 1)

          parse-static-headers (fn [s]
                                 (->> (str/split-lines (or s ""))
                                      (map str/trim)
                                      (remove str/blank?)
                                      (map (fn [line]
                                             (let [[k v] (str/split line #":" 2)]
                                               [(some-> k str/trim) (some-> v str/trim)])))
                                      (filter (fn [[k v]] (and (seq k) (seq v))))
                                      (into {})))

          toggle-sub! (fn [event-type checked?]
                        (let [event-type (str event-type)]
                          ;; Use functional state update to avoid stale closures.
                          (set-subs!
                           (fn [prev]
                             (let [prev (vec (or prev []))
                                   next (if checked?
                                          (conj prev event-type)
                                          (vec (remove #(= event-type (str %)) prev)))]
                               (->> next distinct sort vec))))))

          save! (fn []
                  (set-saving! true)
                  (set-err! nil)
                  (let [payload {:name name
                                 :url url
                                 :enabled (boolean enabled?)
                                 :auth (cond-> {:type auth-type}
                                         (= auth-type "oauth") (assoc :token_url oauth-token-url
                                                                      :client_id oauth-client-id
                                                                      :scopes oauth-scopes)
                                         (= auth-type "api_key") (assoc :header_name api-key-header
                                                                        :prefix api-key-prefix))
                                 :subscriptions (vec (or subs []))
                                 :static_headers (parse-static-headers static-headers-raw)

                                 ;; write-only secret fields
                                 :hmac_secret (when (= auth-type "hmac") hmac-secret)
                                 :api_key (when (= auth-type "api_key") api-key)
                                 :oauth_client_secret (when (= auth-type "oauth") oauth-client-secret)}
                        p (if (= mode :edit)
                            (api/update-webhook! (:id initial) payload)
                            (api/create-webhook! payload))]
                    (-> p
                        (.then (fn [_]
                                 (when (fn? on-saved) (on-saved))
                                 (when (fn? on-close) (on-close))))
                        (.catch (fn [e]
                                  (set-err! (shared/safe-http-error e))))
                        (.finally (fn []
                                    (set-saving! false))))))]
      [:div {:class "modal-overlay"
             :on-click (fn [_] (when (fn? on-close) (on-close)))}
       [:div {:class "modal"
              :style {:maxWidth "740px"}
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:class "modal-title"}
         (if (= mode :edit) "Edit webhook" "New webhook")]

        (when (seq err)
          [:div {:class "badge bad" :style {:marginBottom "10px"}} err])

        [:div {:class "card"}
         [:div {:class "card-title"} "Basic"]
         [:div {:class "row"}
          [:input {:placeholder "Name"
                   :value name
                   :on-change (fn [e] (set-name! (.. e -target -value)))}]
          [:input {:placeholder "https://…"
                   :value url
                   :on-change (fn [e] (set-url! (.. e -target -value)))}]]
         [:label {:class "muted" :style {:display "flex" :gap "8px" :alignItems "center" :marginTop "8px"}}
          [checkbox {:checked? enabled? :on-change set-enabled!}]
          "Enabled"]]

        [:div {:class "card"}
         [:div {:class "card-title"} "Auth"]
         [:div {:class "row"}
          [:select {:value auth-type
                    :on-change (fn [e] (set-auth-type! (.. e -target -value)))}
           (for [{:keys [value label]} auth-types]
             ^{:key (str "auth-" value)}
             [:option {:value value} label])]]

         (case auth-type
           "hmac"
           [:div {:style {:marginTop "8px"}}
            [:div {:class "hint"} "Signing secret is write-only. Leave blank to keep existing secret."]
            [:input {:type "password"
                     :placeholder "HMAC signing secret (write-only)"
                     :value hmac-secret
                     :on-change (fn [e] (set-hmac-secret! (.. e -target -value)))}]]

           "api_key"
           [:div {:style {:marginTop "8px"}}
            [:div {:class "row"}
             [:input {:placeholder "Header name (e.g. Authorization)"
                      :value api-key-header
                      :on-change (fn [e] (set-api-key-header! (.. e -target -value)))}]
             [:input {:placeholder "Prefix (e.g. Bearer )"
                      :value api-key-prefix
                      :on-change (fn [e] (set-api-key-prefix! (.. e -target -value)))}]]
            [:div {:class "hint"} "API key is write-only. Leave blank to keep existing secret."]
            [:input {:type "password"
                     :placeholder "API key (write-only)"
                     :value api-key
                     :on-change (fn [e] (set-api-key! (.. e -target -value)))}]]

           "oauth"
           [:div {:style {:marginTop "8px"}}
            [:div {:class "row"}
             [:input {:placeholder "Token URL"
                      :value oauth-token-url
                      :on-change (fn [e] (set-oauth-token-url! (.. e -target -value)))}]
             [:input {:placeholder "Client ID"
                      :value oauth-client-id
                      :on-change (fn [e] (set-oauth-client-id! (.. e -target -value)))}]]
            [:input {:placeholder "Scopes (space-separated)"
                     :value oauth-scopes
                     :on-change (fn [e] (set-oauth-scopes! (.. e -target -value)))}]
            [:div {:class "hint"} "Client secret is write-only. Leave blank to keep existing secret."]
            [:input {:type "password"
                     :placeholder "OAuth client secret (write-only)"
                     :value oauth-client-secret
                     :on-change (fn [e] (set-oauth-client-secret! (.. e -target -value)))}]]

           ;; none
           [:div {:class "hint" :style {:marginTop "8px"}}
            "No authentication headers will be added."])]

        [:div {:class "card"}
         [:div {:class "card-title"} "Subscriptions"]
         [subscriptions-checkboxes {:selected subs :on-toggle toggle-sub!}]
         [:div {:class "hint" :style {:marginTop "8px"}}
          "High-volume: transcript.refined.segment. Consider enabling only when needed."]]

        [:div {:class "card"}
         [:div {:class "card-title"} "Static headers (optional)"]
         [:div {:class "hint"}
          "One header per line as ‘Name: Value’. Secret headers are not supported here."]
         [:textarea {:style {:width "100%" :minHeight "96px"}
                     :value static-headers-raw
                     :on-change (fn [e] (set-static-headers-raw! (.. e -target -value)))}]]

        [:div {:class "row" :style {:justifyContent "flex-end"}}
         [:button {:class "btn"
                   :disabled saving?
                   :on-click (fn [_] (when (fn? on-close) (on-close)))}
          "Cancel"]
         [:button {:class "btn primary"
                   :disabled (or saving? (str/blank? name) (str/blank? url) (empty? subs))
                   :on-click (fn [_] (save!))}
          (if saving? "Saving…" "Save")]]]])))

(defn- webhook-row
  [{:keys [id name url enabled auth_type created_at subscriptions]} {:keys [on-edit on-delete]}]
  [:tr
   [:td name]
   [:td {:class "mono" :style {:maxWidth "380px" :wordBreak "break-all"}} url]
   [:td
    (if enabled
      [:span {:class "badge ok"} "Enabled"]
      [:span {:class "badge muted"} "Disabled"])]
   [:td {:class "muted"} (or auth_type "")]
   [:td {:class "muted"}
    (if (seq subscriptions)
      (str (count subscriptions))
      "0")]
   [:td {:class "muted"} (or (shared/iso->local created_at) "")]
   [:td {:style {:textAlign "right"}}
    [:div {:class "row" :style {:justifyContent "flex-end"}}
     [:button {:class "btn"
               :on-click (fn [_] (when (fn? on-edit) (on-edit id)))}
      "Edit"]
     [:button {:class "btn ghost"
               :on-click (fn [_]
                           (when (js/confirm (str "Delete webhook '" name "'?"))
                             (when (fn? on-delete) (on-delete id))))}
      "Delete"]]]])

(defn webhooks-page
  "Webhooks management page."
  []
  (let [st (hooks/use-atom store/webhooks*)
        {:keys [items loading? error]} st
        items (vec (or items []))

        modal-open?* (react/useState false)
        modal-open? (aget modal-open?* 0)
        set-modal-open! (aget modal-open?* 1)

        modal-mode* (react/useState :create)
        modal-mode (aget modal-mode* 0)
        set-modal-mode! (aget modal-mode* 1)

        editing-id* (react/useState nil)
        editing-id (aget editing-id* 0)
        set-editing-id! (aget editing-id* 1)

        open-create! (fn []
                       (set-modal-mode! :create)
                       (set-editing-id! nil)
                       (set-modal-open! true))

        open-edit! (fn [webhook-id]
                     (set-modal-mode! :edit)
                     (set-editing-id! webhook-id)
                     (set-modal-open! true))

        close-modal! (fn []
                       (set-modal-open! false)
                       (set-editing-id! nil))

        refresh! (fn []
                   (store/set-webhooks-loading! true)
                   (store/set-webhooks-error! nil)
                   (-> (api/list-webhooks!)
                       (.then (fn [resp]
                                (store/set-webhooks-items! (:items resp))))
                       (.catch (fn [e]
                                 (store/set-webhooks-error! (shared/safe-http-error e))))
                       (.finally (fn []
                                   (store/set-webhooks-loading! false)))))

        delete! (fn [webhook-id]
                  (store/set-webhooks-loading! true)
                  (store/set-webhooks-error! nil)
                  (-> (api/delete-webhook! webhook-id)
                      (.then (fn [_]
                               (store/remove-webhook-item! webhook-id)))
                      (.catch (fn [e]
                                (store/set-webhooks-error! (shared/safe-http-error e))))
                      (.finally (fn []
                                  (store/set-webhooks-loading! false)))))

        initial (when (and (= modal-mode :edit) (seq (str editing-id)))
                  (first (filter #(= (str (:id %)) (str editing-id)) items)))]

    (react/useEffect
     (fn []
       (refresh!)
       js/undefined)
     #js [])

    [:div {:class "page"}
     [webhook-form-modal {:open? modal-open?
                          :mode modal-mode
                          :initial initial
                          :on-close close-modal!
                          :on-saved refresh!}]

     [:div {:class "page-header"}
      [:div
       [:div {:class "page-title"} "Webhooks"]
       [:div {:class "muted"}
        "Manage tenant-scoped outbound webhook endpoints and event subscriptions."]
       (when (seq error)
         [:div {:class "badge bad" :style {:marginTop "10px"}} error])]
      [:div {:class "row"}
       [:button {:class "btn"
                 :disabled loading?
                 :on-click (fn [_] (refresh!))}
        (if loading? "Refreshing…" "Refresh")]
       [:button {:class "btn primary"
                 :disabled loading?
                 :on-click (fn [_] (open-create!))}
        "New webhook"]]]

     [:div {:class "card"}
      [:div {:class "card-title"} "Endpoints"]
      (if (empty? items)
        [:div {:class "muted"} "No webhooks configured yet."]
        [:table {:class "table"}
         [:thead
          [:tr
           [:th "Name"]
           [:th "URL"]
           [:th "Enabled"]
           [:th "Auth"]
           [:th "Subs"]
           [:th "Created"]
           [:th {:style {:textAlign "right"}} "Actions"]]]
         [:tbody
          (for [item items]
            ^{:key (str "wh-" (:id item))}
            [webhook-row item {:on-edit open-edit! :on-delete delete!}])]])]]))

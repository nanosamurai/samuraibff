(ns samuraibff.ui.components.layout
  "Layout components: sidebar, breadcrumbs, topbar.

  These components are shared across pages and are intentionally kept free of
  page-specific state to avoid cycles."
  (:require
   [samuraibff.ui.auth :as auth]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]))

(def ^:private mobile-breakpoint-query
  "CSS media query used as the threshold for mobile layout.

  Must match the @media rule in `resources/public/index.html`."
  "(max-width: 768px)")

(def ^:private logo-src
  "Logo URL for browser-served and packaged Electron renderers."
  (if (= "file:" (some-> js/window .-location .-protocol))
    "img/nanosamurai_logo_finished_shoulders.svg"
    "/img/nanosamurai_logo_finished_shoulders.svg"))

(defn- nav-items
  "Return the navigation items for the sidebar/drawer.

  Inputs:
  - active-page: keyword

  Returns:
  - vector of item maps {:label string :route route-map :active? boolean}."
  [active-page]
  (cond-> [{:label "Sessions"
            :route {:page :recordings :params {}}
            :active? (= active-page :recordings)}
           {:label "Record"
            :route {:page :live :params {}}
            :active? (= active-page :live)}]
    (store/workflow-webhook-runtime-enabled?)
    (conj {:label "Webhooks"
           :route {:page :webhooks :params {}}
           :active? (= active-page :webhooks)}
          {:label "Workflows"
           :route {:page :workflows :params {}}
           :active? (= active-page :workflows)})

    true
    (into [{:label "Speakers"
            :route {:page :speakers :params {}}
            :active? (= active-page :speakers)}
           {:label "API Credentials"
            :route {:page :api-credentials :params {}}
            :active? (= active-page :api-credentials)}])))

(defn- sidebar-item
  [{:keys [active? label route on-click]}]
  [router/link {:route route
                :class (str "nav-item" (when active? " active"))
                :on-click (when (fn? on-click) on-click)}
   [:span {:class "nav-label"} label]])

(defn mobile-drawer
  "Off-canvas drawer navigation for small screens.

  Inputs:
  - route: {:page keyword :params map}
  - open?: boolean
  - on-close: (fn [] ...)

  Returns:
  - hiccup nodes (overlay + drawer) or nil when closed." 
  [{:keys [route open? on-close]}]
  (when (true? open?)
    (let [on-close (or on-close (fn [] nil))
          page (:page route)
          items (nav-items page)]
      [:div
       [:div {:class "drawer-overlay"
              :on-click (fn [_] (on-close))}]
       [:aside {:class "drawer open"
               :role "dialog"
               :aria-modal true}
        [:div {:class "drawer-header"}
         [:div {:style {:display "flex" :gap "10px" :alignItems "center"}}
          [:img {:class "logo" :src logo-src :alt "nanosamur.ai"}]
          [:div {:class "brand-name"} "nanosamur.ai"]]
         [:button {:class "btn ghost"
                   :title "Close"
                   :on-click (fn [_] (on-close))}
          "×"]]
        [:div {:class "sidebar-section"}
         [:div {:class "sidebar-title"} "Navigation"]
         (for [{:keys [label route active?]} items]
           ^{:key (str "drawer-item-" label)}
           [sidebar-item {:label label
                          :route route
                          :active? active?
                          :on-click (fn [_] (on-close))}])]]])))

(defn sidebar
  "Left navigation sidebar.

  Inputs:
  - route: {:page keyword :params map}

  Returns: hiccup." 
  [route]
  (let [page (:page route)
        items (nav-items page)]
    [:aside {:class "sidebar"}
     [:div {:class "sidebar-section"}
      [:div {:class "sidebar-title"} "Navigation"]
      (for [{:keys [label route active?]} items]
        ^{:key (str "sidebar-item-" label)}
        [sidebar-item {:label label :route route :active? active?}])]]))

(defn breadcrumbs
  "Breadcrumbs derived from current route.

  Inputs:
  - route: {:page keyword :params map}

  Returns: hiccup." 
  [route]
  (let [{:keys [page params]} route
        crumbs (case page
                 :recordings [{:label "Sessions" :route {:page :recordings :params {}}}]
                 :live [{:label "Sessions" :route {:page :recordings :params {}}}
                        {:label "Record" :route {:page :live :params {}}}]
                 :webhooks [{:label "Webhooks" :route {:page :webhooks :params {}}}]
                 :workflows [{:label "Workflows" :route {:page :workflows :params {}}}]
                 :speakers [{:label "Speakers" :route {:page :speakers :params {}}}]
                 :api-credentials [{:label "API Credentials" :route {:page :api-credentials :params {}}}]
                 :recording [{:label "Sessions" :route {:page :recordings :params {}}}
                             {:label (or (:session_id params) "Recording")
                              :route {:page :recording :params params}}]
                 [{:label "Sessions" :route {:page :recordings :params {}}}])]
    [:div {:class "breadcrumbs"}
     (for [[idx c] (map-indexed vector crumbs)]
       [:span {:class "crumb" :key (str "crumb-" idx "-" (:label c))}
        (when (pos? idx)
          [:span {:class "sep"} "/"])
        [router/link {:route (:route c) :class "crumb-link"}
         (:label c)]])]))

(defn topbar
  "Top application bar (logo + product name + breadcrumbs).

  Inputs:
  - route: {:page keyword :params map}

  Returns: hiccup." 
  [{:keys [route on-open-menu]}]
  (let [{:keys [status detail]} (hooks/use-atom store/auth*)
        user (get detail :user)
        tenant-name (get detail :tenant_name)
        mobile? (hooks/use-media-query mobile-breakpoint-query)]
    [:header {:class "topbar"}
     (when (and mobile? (fn? on-open-menu))
       [:button {:class "btn ghost menu-btn"
                 :title "Menu"
                 :on-click (fn [_] (on-open-menu))}
        "☰"])
     [:div {:class "brand"}
      [:img {:class "logo" :src logo-src :alt "nanosamur.ai"}]
      [:div {:class "brand-name"} "nanosamur.ai"]]
     [breadcrumbs route]
     [:div {:class "topbar-right"}
      (cond
        (= status :loading)
        [:span {:class "muted"} "auth: loading…"]

        (= status :authenticated)
        [:div {:class "row"}
         (when (seq (str tenant-name))
           [:span {:class "badge muted"} (str tenant-name)])
         [:span {:class "badge ok"}
          (or (:preferred_username user) (:email user) (:sub user) "user")]
         [:button {:class "btn"
                   :on-click (fn [_]
                               (-> (auth/logout!)
                                   (.then (fn [_] (auth/fetch-me!)))))}
          "Logout"]]

        :else
        [:div {:class "row"}
         [:span {:class "badge muted"} "anonymous"]
         [:button {:class "btn primary"
                   :on-click (fn [_]
                               (auth/login! (router/route->href route)))}
          "Login"]])]]))

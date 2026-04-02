(ns samuraibff.ui.components.layout
  "Layout components: sidebar, breadcrumbs, topbar.

  These components are shared across pages and are intentionally kept free of
  page-specific state to avoid cycles."
  (:require
   [samuraibff.ui.auth :as auth]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]))

(defn- sidebar-item
  [{:keys [active? label route]}]
  [router/link {:route route
                :class (str "nav-item" (when active? " active"))}
   [:span {:class "nav-label"} label]])

(defn sidebar
  "Left navigation sidebar.

  Inputs:
  - route: {:page keyword :params map}

  Returns: hiccup." 
  [route]
  (let [page (:page route)]
    [:aside {:class "sidebar"}
     [:div {:class "sidebar-section"}
      [:div {:class "sidebar-title"} "Navigation"]
      [sidebar-item {:label "Recordings"
                     :route {:page :recordings :params {}}
                     :active? (= page :recordings)}]
      [sidebar-item {:label "Live Recording"
                     :route {:page :live :params {}}
                     :active? (= page :live)}]
      [sidebar-item {:label "Speakers"
                     :route {:page :speakers :params {}}
                     :active? (= page :speakers)}]
      [sidebar-item {:label "API Credentials"
                     :route {:page :api-credentials :params {}}
                     :active? (= page :api-credentials)}]]]))

(defn breadcrumbs
  "Breadcrumbs derived from current route.

  Inputs:
  - route: {:page keyword :params map}

  Returns: hiccup." 
  [route]
  (let [{:keys [page params]} route
        crumbs (case page
                 :recordings [{:label "Recordings" :route {:page :recordings :params {}}}]
                 :live [{:label "Recordings" :route {:page :recordings :params {}}}
                        {:label "Live Recording" :route {:page :live :params {}}}]
                 :speakers [{:label "Speakers" :route {:page :speakers :params {}}}]
                 :api-credentials [{:label "API Credentials" :route {:page :api-credentials :params {}}}]
                 :recording [{:label "Recordings" :route {:page :recordings :params {}}}
                             {:label (or (:session_id params) "Recording")
                              :route {:page :recording :params params}}]
                 [{:label "Recordings" :route {:page :recordings :params {}}}])]
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
  [route]
  (let [{:keys [status detail]} (hooks/use-atom store/auth*)
        user (get detail :user)
        tenant-name (get detail :tenant_name)]
    [:header {:class "topbar"}
     [:div {:class "brand"}
      [:img {:class "logo" :src "/img/nonosamurai_art.jpg" :alt "nanosamur.ai"}]
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

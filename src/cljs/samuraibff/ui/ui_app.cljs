(ns samuraibff.ui.ui-app
  "Root application component.

  This namespace wires together the layout (topbar + sidebar) and the page
  components. It is intentionally focused on app composition and routing." 
  (:require
   [samuraibff.ui.auth :as auth]
   [samuraibff.ui.components.layout :as components.layout]
   [samuraibff.ui.components.pages.api-credentials :as pages.api-credentials]
   [samuraibff.ui.components.pages.live :as pages.live]
   [samuraibff.ui.components.pages.recording-detail :as pages.recording-detail]
   [samuraibff.ui.components.pages.recordings :as pages.recordings]
   [samuraibff.ui.components.pages.speakers :as pages.speakers]
    [samuraibff.ui.components.pages.webhook-defaults :as pages.webhook-defaults]
    [samuraibff.ui.components.pages.webhooks :as pages.webhooks]
   [samuraibff.ui.hooks :as hooks]
   [samuraibff.ui.router :as router]
   [samuraibff.ui.store :as store]
   ["react" :as react]))

(defn app
  "Root app component.

  Uses `store/route*` to decide which page to render.

  Returns: hiccup." 
  []
  (let [route (hooks/use-atom store/route*)
        {:keys [status detail]} (hooks/use-atom store/auth*)
        auth-required? (true? (get detail :auth-required?))

        drawer-open?* (react/useState false)
        drawer-open? (aget drawer-open?* 0)
        set-drawer-open! (aget drawer-open?* 1)
        open-drawer! (fn [] (set-drawer-open! true))
        close-drawer! (fn [] (set-drawer-open! false))]

    ;; Close drawer on Escape.
    (react/useEffect
     (fn []
       (let [handler (fn [e]
                       (when (and (true? drawer-open?)
                                  (= "Escape" (.-key e)))
                         (close-drawer!)))]
         (.addEventListener js/document "keydown" handler)
         (fn []
           (.removeEventListener js/document "keydown" handler))))
     #js [drawer-open?])
    (react/useEffect
     (fn []
       (when (and (= status :anonymous) auth-required?)
         ;; Keep it harder to poke around: force a full redirect to login.
         (auth/login! (router/route->href route)))
       js/undefined)
     #js [status auth-required? (:page route) (get-in route [:params :session_id])])

    [:div {:class "app"}
     [components.layout/topbar {:route route
                               :on-open-menu open-drawer!}]
     [components.layout/mobile-drawer {:route route
                                      :open? drawer-open?
                                      :on-close close-drawer!}]
     [:div {:class "body"}
      [components.layout/sidebar route]
      [:main {:class "main"}
       (case (:page route)
         :recordings [pages.recordings/recordings-page]
         :live [pages.live/live-recording-page]
         :recording [pages.recording-detail/recording-detail-page
                     {:session-id (get-in route [:params :session_id])}]
          :webhooks [pages.webhooks/webhooks-page]
          :webhook-defaults [pages.webhook-defaults/webhook-defaults-page]
         :speakers [pages.speakers/speakers-page]
         :api-credentials [pages.api-credentials/api-credentials-page]
         [pages.recordings/recordings-page])]]]))

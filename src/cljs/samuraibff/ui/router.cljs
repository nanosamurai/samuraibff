(ns samuraibff.ui.router
  "Tiny client-side router for the samuraibff UI.

  We intentionally keep routing dependency-free and explicit.

  Supported routes (real paths):
  - /recordings
  - /recordings/:session_id
  - /live
  - /speakers
  - /api-credentials
  - /webhooks
  - /webhooks-defaults
  - /workflows
  - /workflows-defaults

  The router stores the current route in `samuraibff.ui.store/route*`.

  Public API:
  - init-router!  : start listening to back/forward navigation
  - navigate!     : pushState + update route atom
  - href->route   : parse a path string
  - route->href   : build a path for a route map
  - link          : small <a> component that uses navigate!"
  (:require
    [io.factorhouse.hsx.core :as hsx]
    [clojure.string :as str]
    [samuraibff.ui.store :as store]))

(def ^:private known-pages
  #{:recordings :recording :live :speakers :api-credentials
    :webhooks
    :workflows})

(defn href->route
  "Parse a URL path into a route map.

  Inputs:
  - path: string, e.g. \"/recordings/123\" (query ignored)

  Returns:
  - {:page <keyword> :params <map>} where :page is one of:
    :recordings | :recording | :live | :speakers | :api-credentials

  Notes:
  - Unknown paths fall back to {:page :recordings}."
  [path]
  (let [path (or path "/")
        path (first (str/split path #"\\?"))
        segs (->> (str/split path #"/")
                  (remove str/blank?)
                  vec)]
    (cond
      (or (= [] segs)
          (= [""] segs)
          (= ["recordings"] segs))
      {:page :recordings :params {}}

      (= ["live"] segs)
      {:page :live :params {}}

      (= ["speakers"] segs)
      {:page :speakers :params {}}

      (= ["api-credentials"] segs)
      {:page :api-credentials :params {}}

      (= ["webhooks"] segs)
      {:page :webhooks :params {}}

      ;; legacy route (page removed): redirect to /webhooks
      (= ["webhooks-defaults"] segs)
      {:page :webhooks :params {}}

      (= ["workflows"] segs)
      {:page :workflows :params {}}

      ;; legacy route (page removed): redirect to /workflows
      (= ["workflows-defaults"] segs)
      {:page :workflows :params {}}

      (and (= "recordings" (first segs))
           (= 2 (count segs)))
      {:page :recording :params {:session_id (second segs)}}

      :else
      {:page :recordings :params {}})))

(defn route->href
  "Build an href path for a route.

  Inputs:
  - route: {:page keyword :params map}

  Returns: string (path)."
  [{:keys [page params]}]
  (case page
    :recordings "/recordings"
    :live "/live"
    :recording (str "/recordings/" (get params :session_id ""))
    :speakers "/speakers"
    :api-credentials "/api-credentials"
    :webhooks "/webhooks"
    :workflows "/workflows"
    "/recordings"))

(defn- current-path
  []
  (.-pathname (.-location js/window)))

(defn set-route-from-location!
  "Set store route from current window.location.

  Returns: nil."
  []
  (store/set-route! (href->route (current-path)))
  nil)

(defn init-router!
  "Initialize router listeners.

  This should be called once on app boot.

  Returns: nil."
  []
  (set-route-from-location!)
  (set! (.-onpopstate js/window)
        (fn [_]
          (set-route-from-location!)))
  nil)

(defn navigate!
  "Navigate to a route (pushState).

  Inputs:
  - route: {:page keyword :params map}

  Returns: nil."
  [route]
  (when-not (contains? known-pages (:page route))
    (throw (js/Error. (str "Unknown route page: " (pr-str (:page route))))))
  (let [href (route->href route)]
    (.pushState (.-history js/window) #js {} "" href)
    (store/set-route! route))
  nil)

(defn link
  "Render an <a> that navigates client-side.

  Inputs:
  - opts: map with keys:
    - :route (required) route map
    - :class (optional) string
    - :title (optional) string
    - :on-click (optional) fn(event) called after navigate!
  - children: hiccup nodes

  Returns: HSX element." 
  [{:keys [route class title on-click]} & children]
  (let [href (route->href route)]
    (into
      [:a {:href href
           :class class
           :title title
           :on-click (fn [e]
                       (.preventDefault e)
                       (navigate! route)
                       (when on-click (on-click e)))}]
      children)))

(defn memo-clear!
  "Clear HSX memoization cache (used by core reload hook)."
  []
  (hsx/memo-clear!))

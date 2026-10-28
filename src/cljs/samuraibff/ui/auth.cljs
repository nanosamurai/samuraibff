(ns samuraibff.ui.auth
  "OIDC/auth helpers for the CLJS UI.

  Backend-managed login stores the access token in an HttpOnly cookie through
  /auth/login and /auth/callback. Browser and Electron WebSockets use that
  cookie; browser storage is not an authentication source. The session is
  kept fresh through `samuraibff.ui.session-refresh`.

  Public API:
  - fetch-me!
  - login!
  - logout!"
  (:require
   [samuraibff.ui.env :as env]
   [samuraibff.ui.session-refresh :as session-refresh]
   [samuraibff.ui.store :as store]))

(defn fetch-me!
  "Fetch current user info from GET /api/me.

  Returns: Promise"
  []
  (store/set-auth-status! :loading nil)
  (-> (session-refresh/fetch (str (env/backend-base-url) "/api/me") #js {:method "GET"})
      (.then (fn [res]
               (if (.-ok res)
                 (.json res)
                 ;; Preserve status for callers (auth guard) instead of collapsing
                 ;; everything into a generic error.
                 (js/Promise.reject (js/Error. (str "HTTP " (.-status res)))))))
      (.then (fn [body]
               (let [authed? (boolean (aget body "authenticated"))
                     access (js->clj (aget body "access") :keywordize-keys true)]
                 (session-refresh/schedule! (aget body "access_expires_at"))
                 (if authed?
                   (store/set-auth-status! :authenticated {:user (js->clj (aget body "user") :keywordize-keys true)
                                                           :tenant_id (aget body "tenant_id")
                                                           :tenant_name (aget body "tenant_name")
                                                           :access access
                                                           :realtime_tracks (vec (js->clj (aget body "realtime_tracks")))
                                                           :default_tracks (js->clj (aget body "default_tracks") :keywordize-keys true)
                                                           :async_tracks (vec (js->clj (aget body "async_tracks") :keywordize-keys true))
                                                           :realtime_track_capabilities (vec (js->clj (aget body "realtime_track_capabilities") :keywordize-keys true))
                                                           :features (js->clj (aget body "features") :keywordize-keys true)})
                   (store/set-auth-status! :anonymous {:access access
                                                       :realtime_tracks (vec (js->clj (aget body "realtime_tracks")))
                                                       :default_tracks (js->clj (aget body "default_tracks") :keywordize-keys true)
                                                       :async_tracks (vec (js->clj (aget body "async_tracks") :keywordize-keys true))
                                                       :realtime_track_capabilities (vec (js->clj (aget body "realtime_track_capabilities") :keywordize-keys true))
                                                       :features (js->clj (aget body "features") :keywordize-keys true)}))
                 body)))
      (.catch (fn [e]
                ;; If /api/me is protected and returns 401/403, treat as anonymous,
                ;; but flag that auth is required so UI can auto-redirect.
                (let [msg (or (some-> e .-message str) "")
                      auth-required? (boolean (re-find #"HTTP\s+(401|403)" msg))]
                  (store/set-auth-status! :anonymous {:auth-required? auth-required?})
                  e)))))

(defn login!
  "Start the backend-managed login flow.

  Browser behavior:
  - navigate the current page to /auth/login.

  Electron behavior:
  - ask the main process to open an isolated, no-preload OIDC window;
  - reload the trusted BFF renderer after the shared auth cookie is set.

  Inputs:
  - next-path: internal application pathname, such as /live.

  Returns:
  - Electron: Promise resolving after reload is requested, or false on cancel.
  - Browser: nil after assigning window.location."
  [next-path]
  (let [next-path (or next-path "/recordings")
        electron-api (.-samuraibffElectron js/window)]
    (if (env/electron?)
      (if (some? (.-login electron-api))
        (do
          (store/set-auth-status! :loading nil)
          (-> (.login electron-api next-path)
              (.then (fn [_]
                       (.reload (.-location js/window))
                       true))
              (.catch (fn [error]
                        (store/set-auth-status! :anonymous
                                                {:auth-required? true
                                                 :login-cancelled? true})
                        (store/append-log! (str "[auth] Electron login did not complete: " error))
                        false))))
        (do
          (store/set-auth-status! :anonymous
                                  {:auth-required? true
                                   :login-cancelled? true})
          (store/append-log! "[auth] Electron shell does not support isolated login")
          (js/Promise.resolve false)))
      (let [url (str (env/backend-base-url)
                     "/auth/login?next="
                     (js/encodeURIComponent next-path))]
        (set! (.-location js/window) url)
        nil))))

(defn logout!
  "Logout by POSTing to /auth/logout (clears the BFF cookies).

  When the BFF returns `logout_url`, navigates there so the identity provider
  ends its single sign-on session too; otherwise the next login would silently
  sign the same person in again. The auth state is left untouched while the
  page leaves, so the automatic login does not race the logout redirect.
  Electron keeps the cookie-only logout: its main window may not navigate to
  the identity provider.
  Resolves to true when the page is leaving for the identity provider.

  Returns: Promise"
  []
  (-> (js/fetch (str (env/backend-base-url) "/auth/logout") #js {:method "POST"})
      (.then (fn [resp]
               (if (.-ok resp)
                 (-> (.json resp) (.catch (fn [_] #js {})))
                 #js {})))
      (.then (fn [body]
               (session-refresh/schedule! nil)
               (let [logout-url (some-> body (aget "logout_url") str)]
                 (store/append-log! "[auth] logged out")
                 (if (and (seq logout-url) (not (env/electron?)))
                   (do (set! (.-location js/window) logout-url) true)
                   (do (store/set-auth-status! :anonymous nil) false)))))
      (.catch (fn [e]
                (store/append-log! (str "[auth] logout failed: " e))
                (throw e)))))

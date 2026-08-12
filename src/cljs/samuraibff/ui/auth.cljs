(ns samuraibff.ui.auth
  "OIDC/auth helpers for the CLJS UI.

  We support a *hybrid* model:
  - preferred: backend-managed login that stores access token in HttpOnly cookie
    (via /auth/login + /auth/callback)
  - optional/dev: if `sessionStorage.access_token` exists, WS connections will
    also include it as `?token=...` (non-cookie clients)

  Public API:
  - fetch-me!
  - login!
  - logout!"
  (:require
    [samuraibff.ui.env :as env]
    [samuraibff.ui.store :as store]))

(defn fetch-me!
  "Fetch current user info from GET /api/me.

  Returns: Promise" 
  []
  (store/set-auth-status! :loading nil)
  (-> (js/fetch (str (env/backend-base-url) "/api/me") #js {:method "GET"})
      (.then (fn [res]
               (if (.-ok res)
                 (.json res)
                 ;; Preserve status for callers (auth guard) instead of collapsing
                 ;; everything into a generic error.
                 (js/Promise.reject (js/Error. (str "HTTP " (.-status res)))))))
      (.then (fn [body]
               (let [authed? (boolean (aget body "authenticated"))]
                 (if authed?
                   (store/set-auth-status! :authenticated {:user (js->clj (aget body "user") :keywordize-keys true)
                                                           :tenant_id (aget body "tenant_id")
                                                           :tenant_name (aget body "tenant_name")
                                                           :features (js->clj (aget body "features") :keywordize-keys true)})
                   (store/set-auth-status! :anonymous {:features (js->clj (aget body "features") :keywordize-keys true)}))
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
  "Logout by POSTing to /auth/logout (clears cookie).

  Returns: Promise" 
  []
  (-> (js/fetch (str (env/backend-base-url) "/auth/logout") #js {:method "POST"})
      (.then (fn [_]
               (store/set-auth-status! :anonymous nil)
               (store/append-log! "[auth] logged out")
               true))
      (.catch (fn [e]
                (store/append-log! (str "[auth] logout failed: " e))
                (throw e)))))

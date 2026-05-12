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
                                                           :tenant_name (aget body "tenant_name")})
                   (store/set-auth-status! :anonymous nil))
                 body)))
      (.catch (fn [e]
                ;; If /api/me is protected and returns 401/403, treat as anonymous,
                ;; but flag that auth is required so UI can auto-redirect.
                (let [msg (or (some-> e .-message str) "")
                      auth-required? (boolean (re-find #"HTTP\s+(401|403)" msg))]
                  (store/set-auth-status! :anonymous {:auth-required? auth-required?})
                  e)))))

(defn login!
  "Start login flow by navigating to /auth/login.

  We keep routing simple by full-page redirect.

  Inputs:
  - next-path: string (e.g. \"/live\")" 
  [next-path]
  (let [next-path (or next-path "/recordings")
        url (str (env/backend-base-url) "/auth/login?next=" (js/encodeURIComponent next-path))]
    (set! (.-location js/window) url)))

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

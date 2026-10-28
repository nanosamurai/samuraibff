(ns samuraibff.ui.session-refresh
  "Keep the signed-in session's access token fresh.

  The BFF keeps the refresh token in an HttpOnly cookie that only
  `POST /auth/refresh` receives; this namespace never sees a token. It
  refreshes shortly before the access token expires (expiry from `/api/me`)
  and once after an API call returns 401, then retries that call.

  When a refresh fails and no recording is running, the UI signs in again
  (silently while the identity provider's session lasts). During a recording
  the page stays put: open WebSockets do not need the token, and navigating
  away would stop the recording.

  Public API:
  - `schedule!`, `refresh!`, `fetch`"
  (:require
   [samuraibff.ui.env :as env]
   [samuraibff.ui.store :as store]))

(def ^:private lead-ms
  "Refresh this long before the access token expires."
  60000)

(def ^:private min-delay-ms
  "Never schedule a refresh sooner than this."
  5000)

(defonce ^:private timer* (atom nil))

(defonce ^:private in-flight* (atom nil))

(declare refresh!)

(defn schedule!
  "Schedule the next refresh for an access token expiring at epoch ms.

  Replaces any earlier schedule. A non-number clears it. Returns nil."
  [expires-at-ms]
  (some-> @timer* js/clearTimeout)
  (reset! timer* nil)
  (when (number? expires-at-ms)
    (let [delay (max min-delay-ms (- expires-at-ms (js/Date.now) lead-ms))]
      (reset! timer* (js/setTimeout (fn [] (refresh!)) delay))))
  nil)

(defn- signed-out!
  []
  (store/append-log! "[auth] session refresh failed")
  (when-not @store/running?*
    (store/set-auth-status! :anonymous {:auth-required? true})))

(defn refresh!
  "Refresh the session once. Concurrent callers share one request.

  Returns: Promise resolving to true when the session cookies were renewed."
  []
  (or @in-flight*
      (let [p (-> (js/fetch (str (env/backend-base-url) "/auth/refresh")
                            #js {:method "POST" :credentials "same-origin"})
                  (.then (fn [res]
                           (if (.-ok res)
                             (.then (.json res)
                                    (fn [body]
                                      (schedule! (aget body "access_expires_at"))
                                      true))
                             false)))
                  (.catch (fn [_] false))
                  (.then (fn [ok?]
                           (reset! in-flight* nil)
                           (when-not ok? (signed-out!))
                           ok?)))]
        (reset! in-flight* p)
        p)))

(defn fetch
  "Like `js/fetch` for BFF API calls: after a 401, refresh once and retry.

  Returns: Promise of the Response (the original 401 when refresh fails)."
  ([url] (fetch url nil))
  ([url opts]
   (-> (js/fetch url opts)
       (.then (fn [res]
                (if (= 401 (.-status res))
                  (.then (refresh!) (fn [ok?] (if ok? (js/fetch url opts) res)))
                  res))))))

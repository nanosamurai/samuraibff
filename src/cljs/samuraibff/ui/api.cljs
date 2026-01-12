(ns samuraibff.ui.api
  "HTTP API helpers for the samuraibff UI.

  Current endpoints:
  - POST /api/sessions -> {:session_id <uuid-string>}

  All functions return JS Promises.")

(defn- ensure-ok!
  "Ensure a fetch Response is OK.

  Inputs:
  - res: Fetch Response

  Returns: res if ok; throws JS Error otherwise."
  [res]
  (when-not (.-ok res)
    (throw (js/Error. (str "HTTP error " (.-status res)))))
  res)

(defn create-session!
  "Create a new session via backend.

  Returns:
  - Promise resolving to session-id string."
  []
  (-> (js/fetch "/api/sessions" #js {:method "POST"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (or (aget body "session_id")
                   (throw (js/Error. "Missing session_id in response")))))))

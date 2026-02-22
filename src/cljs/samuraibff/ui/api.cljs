(ns samuraibff.ui.api
  "HTTP API helpers for the samuraibff UI.

  Current endpoints:
  - POST /api/sessions -> {:session_id <uuid-string>}
  - GET /api/speakers
  - POST /api/speakers (multipart)
  - DELETE /api/speakers/:speaker_id

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

(defn list-speakers!
  "Fetch speakers for the current tenant.

  Returns:
  - Promise resolving to vector of speaker maps." 
  []
  (-> (js/fetch "/api/speakers")
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (or (aget body "items")
                   (throw (js/Error. "Missing items in response")))))))

(defn create-speaker!
  "Create a new speaker with a single WAV sample.

  Inputs:
  - label string
  - file JS File

  Returns:
  - Promise resolving to speaker map." 
  [label file]
  (let [data (js/FormData.)]
    (.append data "label" (or label ""))
    (.append data "sample" file)
    (-> (js/fetch "/api/speakers" #js {:method "POST"
                                       :body data})
        (.then ensure-ok!)
        (.then (fn [res] (.json res))))))

(defn delete-speaker!
  "Delete a speaker.

  Inputs:
  - speaker-id string

  Returns:
  - Promise resolving to response body." 
  [speaker-id]
  (-> (js/fetch (str "/api/speakers/" speaker-id) #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))))

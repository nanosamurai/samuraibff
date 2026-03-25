(ns samuraibff.ui.api
  "HTTP API helpers for the samuraibff UI.

  Current endpoints:
  - POST /api/sessions -> {:session_id <uuid-string>}
  - GET /api/recordings
  - GET /api/recordings/:session_id
  - GET /api/recordings/:session_id/audio
  - DELETE /api/recordings/:session_id
  - GET /api/speakers
  - POST /api/speakers (multipart)
  - DELETE /api/speakers/:speaker_id

  - GET /api/api-credentials
  - POST /api/api-credentials (create)
  - POST /api/api-credentials/:id/rotate
  - DELETE /api/api-credentials/:id

  All functions return JS Promises."
  (:require
    [clojure.string :as str]))

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

(defn list-recordings!
  "List sessions/recordings for the authenticated tenant.

  Returns:
  - Promise resolving to response map with keys:
      :items (vector)

  Throws on non-2xx." 
  ([]
   (list-recordings! {}))
  ([{:keys [limit offset]}]
   (let [params (cond-> {}
                  (some? limit) (assoc "limit" (str limit))
                  (some? offset) (assoc "offset" (str offset)))
         qs (when (seq params)
              (->> params
                   (map (fn [[k v]] (str (js/encodeURIComponent k)
                                        "="
                                        (js/encodeURIComponent v))))
                   (str/join "&")))
         url (str "/api/recordings" (when qs (str "?" qs)))]
     (-> (js/fetch url)
         (.then ensure-ok!)
         (.then (fn [res] (.json res)))
         (.then (fn [body]
                  (js->clj body :keywordize-keys true)))))))

(defn get-recording!
  "Fetch a single recording/session detail.

  Inputs:
  - session-id: string

  Returns:
  - Promise resolving to map:
      {:session {...}
       :transcripts {:refined [...] :final [...]}}
  " 
  [session-id]
  (-> (js/fetch (str "/api/recordings/" (js/encodeURIComponent (or session-id ""))))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn recording-audio-url
  "Return the audio playback URL for a recording.

  Inputs:
  - session-id: string

  Returns:
  - string URL (relative)" 
  [session-id]
  (str "/api/recordings/" (js/encodeURIComponent (or session-id "")) "/audio"))

(defn delete-recording!
  "Delete a session/recording (tenant-scoped).

  Inputs:
  - session-id: string

  Returns:
  - Promise resolving to response map." 
  [session-id]
  (-> (js/fetch (str "/api/recordings/" (js/encodeURIComponent (or session-id "")))
                #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

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

(defn list-api-credentials!
  "List API credentials for the authenticated tenant.

  Returns:
  - Promise resolving to response map with keys:
      :items (vector)

  Throws on non-2xx." 
  []
  (-> (js/fetch "/api/api-credentials")
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn create-api-credential!
  "Create a new API credential.

  Inputs:
  - name: string (human label)

  Returns:
  - Promise resolving to map:
      {:ok true
       :credential_id <uuid-string>
       :client_id <string>
       :client_secret <string>}

  Notes:
  - The secret is returned only once; callers must treat it as transient." 
  [name]
  (-> (js/fetch "/api/api-credentials"
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON #js {:name (or name "")})})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn rotate-api-credential!
  "Rotate secret for an API credential.

  Inputs:
  - credential-id: string (UUID)

  Returns:
  - Promise resolving to map:
      {:ok true :credential_id <uuid-string> :client_id <string> :client_secret <string>}

  Notes:
  - The new secret is returned only once; callers must treat it as transient." 
  [credential-id]
  (-> (js/fetch (str "/api/api-credentials/" (js/encodeURIComponent (or credential-id "")) "/rotate")
                #js {:method "POST"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn revoke-api-credential!
  "Revoke (disable) an API credential.

  Inputs:
  - credential-id: string (UUID)

  Returns:
  - Promise resolving to map (e.g. {:ok true ...})." 
  [credential-id]
  (-> (js/fetch (str "/api/api-credentials/" (js/encodeURIComponent (or credential-id "")))
                #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

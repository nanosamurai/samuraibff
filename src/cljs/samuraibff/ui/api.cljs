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
  - POST /api/speaker-enrollment/from-recording
  - DELETE /api/speakers/:speaker_id

  - GET /api/api-credentials
  - POST /api/api-credentials (create)
  - POST /api/api-credentials/:id/rotate
  - DELETE /api/api-credentials/:id

  - GET /api/webhooks
  - POST /api/webhooks
  - PUT /api/webhooks/:id
  - DELETE /api/webhooks/:id
  - GET /api/webhooks/defaults
  - PUT /api/webhooks/defaults

  - GET /api/workflows
  - POST /api/workflows
  - PUT /api/workflows/:id
  - DELETE /api/workflows/:id
  - GET /api/workflows/defaults
  - PUT /api/workflows/defaults

  - GET /api/sessions/:session_id/webhook-delivery-outcomes

  All functions return JS Promises."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.env :as env]))

(defn- api-url
  "Build absolute API URL.

  Inputs:
  - path: string starting with /api (or full path starting with /)

  Returns: string."
  [path]
  (let [base (env/backend-base-url)]
    (str base (str path))))

(defn- ensure-ok!
  "Ensure a fetch Response is OK.

  Inputs:
  - res: Fetch Response

  Returns: res if ok; throws JS Error otherwise."
  [res]
  (when-not (.-ok res)
    (throw (js/Error. (str "HTTP error " (.-status res)))))
  res)

(defn list-webhook-delivery-outcomes!
  "List latest webhook delivery outcomes for a session.

  Endpoint:
  - GET /api/sessions/:session_id/webhook-delivery-outcomes

  Inputs:
  - session-id: string

  Returns:
  - Promise resolving to map (keyword keys):
      {:ok true
       :tenant_id <uuid>
       :session_id <uuid>
       :items [ ... ]}"
  [session-id]
  (-> (js/fetch (api-url (str "/api/sessions/"
                              (js/encodeURIComponent (or session-id ""))
                              "/webhook-delivery-outcomes")))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn create-session!
  "Create a new session via backend.

  Returns:
  - Promise resolving to {:session_id <string> :title <string>}"
  ([]
   (create-session! {}))
  ([{:keys [title webhook_overrides workflow_overrides session_settings]}]
   (-> (js/fetch (api-url "/api/sessions")
                 #js {:method "POST"
                      :headers #js {"content-type" "application/json"}
                      :body (.stringify js/JSON
                                        (clj->js (cond-> {:title (or title "")}
                                                   (some? webhook_overrides)
                                                   (assoc :webhook_overrides webhook_overrides)

                                                   (some? workflow_overrides)
                                                   (assoc :workflow_overrides workflow_overrides)

                                                   (some? session_settings)
                                                   (assoc :session_settings session_settings))))})
       (.then ensure-ok!)
       (.then (fn [res] (.json res)))
       (.then (fn [body]
                (let [sid (aget body "session_id")
                      t (aget body "title")]
                  (when-not sid
                    (throw (js/Error. "Missing session_id in response")))
                  {:session_id sid
                   :title t}))))))

(defn finish-session!
  "Finish a session (explicit state machine transition).

  Endpoint:
  - POST /api/sessions/:session_id/finish

  Inputs:
  - session-id: string

  Returns:
  - Promise resolving to {:ok true :session_id <string> :status " finished "}"
  [session-id]
  (-> (js/fetch (api-url (str "/api/sessions/"
                              (js/encodeURIComponent (or session-id ""))
                              "/finish"))
                #js {:method "POST"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

;; --- Workflows ---

(defn list-workflows!
  "List tenant workflows.

  Endpoint:
  - GET /api/workflows

  Returns:
  - Promise resolving to {:ok true :tenant_id <uuid> :items [...]}."
  []
  (-> (js/fetch (api-url "/api/workflows"))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn create-workflow!
  "Create a workflow.

  Endpoint:
  - POST /api/workflows

  Inputs:
  - payload: map (see backend schema CreateWorkflowRequest)

  Returns:
  - Promise resolving to {:ok true :workflow_id <uuid>}."
  [payload]
  (-> (js/fetch (api-url "/api/workflows")
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON (clj->js (or payload {})))})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn update-workflow!
  "Update workflow.

  Endpoint:
  - PUT /api/workflows/:id

  Inputs:
  - workflow-id string
  - patch map (see backend schema UpdateWorkflowRequest)

  Returns:
  - Promise resolving to {:ok true}."
  [workflow-id patch]
  (-> (js/fetch (api-url (str "/api/workflows/" (js/encodeURIComponent (or workflow-id ""))))
                #js {:method "PUT"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON (clj->js (or patch {})))})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn delete-workflow!
  "Delete workflow.

  Endpoint:
  - DELETE /api/workflows/:id

  Returns:
  - Promise resolving to {:ok true}."
  [workflow-id]
  (-> (js/fetch (api-url (str "/api/workflows/" (js/encodeURIComponent (or workflow-id ""))))
                #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn get-workflow-defaults!
  "Get tenant workflow defaults.

  Endpoint:
  - GET /api/workflows/defaults

  Returns:
  - Promise resolving to {:ok true :tenant_id <uuid> :workflow_ids [...]}."
  []
  (-> (js/fetch (api-url "/api/workflows/defaults"))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn set-workflow-defaults!
  "Set tenant workflow defaults.

  Endpoint:
  - PUT /api/workflows/defaults

  Inputs:
  - workflow-ids: vector of uuid strings

  Returns:
  - Promise resolving to {:ok true}."
  [workflow-ids]
  (-> (js/fetch (api-url "/api/workflows/defaults")
                #js {:method "PUT"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON
                                       (clj->js {:workflow_ids (vec (or workflow-ids []))}))})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn rename-session!
  "Rename a session title.

  Inputs:
  - session-id: string
  - title: string

  Returns:
  - Promise resolving to response map {:ok boolean :session_id ... :title ...}"
  [session-id title]
  (-> (js/fetch (api-url (str "/api/sessions/" (js/encodeURIComponent (or session-id ""))))
                #js {:method "PATCH"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON #js {:title (or title "")})})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

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
         url (api-url (str "/api/recordings" (when qs (str "?" qs))))]
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
  (-> (js/fetch (api-url (str "/api/recordings/" (js/encodeURIComponent (or session-id "")))))
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
  (-> (js/fetch (api-url (str "/api/recordings/" (js/encodeURIComponent (or session-id ""))))
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
  (-> (js/fetch (api-url "/api/speakers"))
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
    (-> (js/fetch (api-url "/api/speakers") #js {:method "POST"
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
  (-> (js/fetch (api-url (str "/api/speakers/" speaker-id)) #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))))

(defn create-speaker-from-recording!
  "Create a new enrolled speaker by clipping a segment from an existing recording.

  Inputs:
  - {:keys [session-id start-s end-s label]}
    - session-id: string UUID
    - start-s: number (seconds)
    - end-s: number (seconds)
    - label: string

  Returns:
  - Promise resolving to response map (keyword keys)."
  [{:keys [session-id start-s end-s label]}]
  (-> (js/fetch (api-url "/api/speaker-enrollment/from-recording")
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON
                                       #js {:session_id (or session-id "")
                                            :start_s (double (or start-s 0.0))
                                            :end_s (double (or end-s 0.0))
                                            :label (or label "")})})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn list-api-credentials!
  "List API credentials for the authenticated tenant.

  Returns:
  - Promise resolving to response map with keys:
      :items (vector)

  Throws on non-2xx."
  []
  (-> (js/fetch (api-url "/api/api-credentials"))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

;; --- Webhooks ---

(defn list-webhooks!
  "List tenant webhooks.

  Returns:
  - Promise resolving to response map:
      {:ok true
       :tenant_id <uuid>
       :items [ ... ]}"
  []
  (-> (js/fetch (api-url "/api/webhooks"))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn create-webhook!
  "Create a webhook.

  Inputs:
  - payload: map (will be JSON encoded)

  Returns:
  - Promise resolving to response map."
  [payload]
  (-> (js/fetch (api-url "/api/webhooks")
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON (clj->js (or payload {})))})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn update-webhook!
  "Update a webhook.

  Inputs:
  - webhook-id: string UUID
  - patch: map (JSON)

  Returns:
  - Promise resolving to response map."
  [webhook-id patch]
  (-> (js/fetch (api-url (str "/api/webhooks/" (js/encodeURIComponent (or webhook-id ""))))
                #js {:method "PUT"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON (clj->js (or patch {})))})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn delete-webhook!
  "Delete a webhook.

  Inputs:
  - webhook-id string UUID

  Returns:
  - Promise resolving to response map."
  [webhook-id]
  (-> (js/fetch (api-url (str "/api/webhooks/" (js/encodeURIComponent (or webhook-id ""))))
                #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn get-webhook-defaults!
  "Get tenant webhook defaults.

  Returns:
  - Promise resolving to response map {:ok true :webhook_ids [..]}"
  []
  (-> (js/fetch (api-url "/api/webhooks/defaults"))
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

(defn set-webhook-defaults!
  "Set tenant webhook defaults.

  Inputs:
  - webhook-ids: vector of uuid strings

  Returns:
  - Promise resolving to response map."
  [webhook-ids]
  (-> (js/fetch (api-url "/api/webhooks/defaults")
                #js {:method "PUT"
                     :headers #js {"content-type" "application/json"}
                     :body (.stringify js/JSON
                                       (clj->js {:webhook_ids (vec (or webhook-ids []))}))})
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
  (-> (js/fetch (api-url "/api/api-credentials")
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
  (-> (js/fetch (api-url (str "/api/api-credentials/" (js/encodeURIComponent (or credential-id "")) "/rotate"))
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
  (-> (js/fetch (api-url (str "/api/api-credentials/" (js/encodeURIComponent (or credential-id ""))))
                #js {:method "DELETE"})
      (.then ensure-ok!)
      (.then (fn [res] (.json res)))
      (.then (fn [body]
               (js->clj body :keywordize-keys true)))))

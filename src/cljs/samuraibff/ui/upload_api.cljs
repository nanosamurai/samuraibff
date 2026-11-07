(ns samuraibff.ui.upload-api
  "Browser client for `POST /api/sessions/upload`.

  Uses XMLHttpRequest because fetch reports no upload progress. Requests
  follow the same conventions as `samuraibff.ui.api`: same-origin cookies
  only, one session refresh and retry after a 401, and account errors switch
  the UI to its access state page.

  Public API:
  - `upload-session!`"
  (:require
   [samuraibff.ui.access :as access]
   [samuraibff.ui.env :as env]
   [samuraibff.ui.permissions :as permissions]
   [samuraibff.ui.session-refresh :as session-refresh]
   [samuraibff.ui.urls :as urls]))

(defn- form-data
  "Build the multipart body: text fields first, then the file part."
  [file fields]
  (let [body (js/FormData.)]
    (doseq [[field-name value] fields]
      (.append body field-name value))
    (.append body "file" file)
    body))

(defn- send!
  "Send one upload request.

  Inputs: url string, FormData body, on-progress (fn [loaded total]) or nil,
  and an atom that receives the XMLHttpRequest so the caller can abort it.

  Returns: Promise resolving to {:status int :body map-or-nil}. Rejects with
  ex-info {:aborted? true} after abort and {:status nil} on network errors."
  [url body on-progress xhr*]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (reset! xhr* xhr)
       (.open xhr "POST" url)
       (set! (.-responseType xhr) "json")
       (when (fn? on-progress)
         (.addEventListener (.-upload xhr) "progress"
                            (fn [e]
                              (when (.-lengthComputable e)
                                (on-progress (.-loaded e) (.-total e))))))
       (.addEventListener xhr "load"
                          (fn [_]
                            (resolve {:status (.-status xhr)
                                      :body (js->clj (.-response xhr) :keywordize-keys true)})))
       (.addEventListener xhr "error"
                          (fn [_] (reject (ex-info "Upload network error" {:status nil}))))
       (.addEventListener xhr "abort"
                          (fn [_] (reject (ex-info "Upload cancelled" {:aborted? true}))))
       (.send xhr body)))))

(defn- checked-response
  "Return the success body or throw ex-info {:status int :code string?}.

  Only the short server error code of an error body is kept."
  [{:keys [status body]}]
  (if (<= 200 status 299)
    body
    (let [code (access/error-code body)]
      (permissions/note-access-error! status code)
      (throw (ex-info (str "HTTP error " status) {:status status :code code})))))

(defn upload-session!
  "Upload an audio or video file as a new session.

  Inputs (map):
  - :file         js/File chosen by the user
  - :fields       [[field-name value] ...] text fields from
                  `samuraibff.ui.upload-form/form-fields`
  - :on-progress  optional (fn [loaded-bytes total-bytes])

  Returns: {:promise Promise :abort! (fn [])}. The promise resolves to
  {:session_id string :title string}. It rejects with ex-info whose data is
  {:status int :code string?} for an HTTP error, {:status nil} for a network
  error, or {:aborted? true} after `abort!`. After a 401 the session is
  refreshed once and the file is sent again."
  [{:keys [file fields on-progress]}]
  (let [url (urls/api-url (env/backend-base-url) "/api/sessions/upload")
        xhr* (atom nil)
        aborted?* (atom false)
        attempt! #(send! url (form-data file fields) on-progress xhr*)
        promise (-> (attempt!)
                    (.then (fn [response]
                             (if (and (= 401 (:status response)) (not @aborted?*))
                               (.then (session-refresh/refresh!)
                                      (fn [ok?]
                                        (cond
                                          @aborted?* (throw (ex-info "Upload cancelled" {:aborted? true}))
                                          ok? (attempt!)
                                          :else response)))
                               response)))
                    (.then checked-response))]
    {:promise promise
     :abort! (fn []
               (reset! aborted?* true)
               (some-> @xhr* .abort))}))

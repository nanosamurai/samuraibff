(ns samuraibff.ui.access-api
  "HTTP helpers for session sharing, the colleague directory and People.

  Endpoints (same-origin, cookie authenticated):
  - GET  /api/sessions/:session_id/access
  - PUT  /api/sessions/:session_id/access
  - PUT  /api/sessions/:session_id/owner        (Admin, owner-unknown sessions)
  - GET  /api/directory?q=
  - GET  /api/people?q=&limit=&offset=          (Admin)
  - POST /api/people                            (Admin)
  - PATCH /api/people/:id                       (Admin)
  - POST /api/people/:id/setup-email            (Admin)

  All functions return Promises resolving to keywordized maps. Rejections are
  ex-info with {:status int :code string?}; see `samuraibff.ui.api/error-code`."
  (:require
   [clojure.string :as str]
   [samuraibff.ui.access :as access]
   [samuraibff.ui.api :as api]))

(defn- enc
  [value]
  (js/encodeURIComponent (str (or value ""))))

(defn- query-string
  "Encode a map of string keys to values, skipping nil/blank values."
  [params]
  (let [pairs (keep (fn [[k v]]
                      (when-not (str/blank? (str v))
                        (str (enc k) "=" (enc v))))
                    params)]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

(defn get-session-access!
  "Load the audience of one session.

  Returns: Promise of {:visibility :revision :is_owner :can_manage :owner
  :recipients? [{:id :display_name :email :suspended?}]}; recipients are only
  present for the owner. Inaccessible sessions reject with status 404."
  [session-id]
  (api/fetch-json! (str "/api/sessions/" (enc session-id) "/access")))

(defn put-session-access!
  "Replace the audience of one session (owner only).

  Inputs: session id, {:visibility string :recipient-ids [uuid] :revision int}.
  Returns: Promise of the new access map. A stale revision rejects with 409
  code `access-changed`."
  [session-id audience]
  (api/fetch-json! (str "/api/sessions/" (enc session-id) "/access")
                   {:method "PUT"
                    :body (access/share-request-body audience)}))

(defn assign-session-owner!
  "Give a session recorded before access control (owner unknown) an owner.

  Inputs: session id, {:owner-id uuid :revision int}.
  Returns: Promise of the caller's new access map. A stale revision rejects
  with 409 code `access-changed`."
  [session-id {:keys [owner-id revision]}]
  (api/fetch-json! (str "/api/sessions/" (enc session-id) "/owner")
                   {:method "PUT"
                    :body {:owner_id (str owner-id) :revision revision}}))

(defn search-directory!
  "Search active colleagues to share with.

  Inputs: query string (may be blank).
  Returns: Promise of {:items [{:id :display_name :email}]} (max 20)."
  [q]
  (api/fetch-json! (str "/api/directory" (query-string {"q" (str/trim (str (or q "")))}))))

(defn list-people!
  "List people of the Admin's organization.

  Inputs: {:q string? :limit int :offset int}.
  Returns: Promise of {:items [...] :total :active_admins :provisioning_enabled}."
  [{:keys [q limit offset]}]
  (api/fetch-json! (str "/api/people" (query-string {"q" (some-> q str str/trim)
                                                     "limit" limit
                                                     "offset" offset}))))

(defn add-person!
  "Add a person. Inputs: {:email string :name string? :role string?}.

  Returns: Promise of {:person {...} :setup_email \"sent\"|\"failed\"|...}."
  [{:keys [email name role]}]
  (api/fetch-json! "/api/people"
                   {:method "POST"
                    :body (cond-> {:email (str/trim (str email))}
                            (not (str/blank? (str name))) (assoc :name (str/trim (str name)))
                            (seq (str role)) (assoc :role (str role)))}))

(defn update-person!
  "Change a person's role and/or status, or retry a pending sign-in sync.

  Inputs: person id, {:role string?} and/or {:status \"active\"|\"suspended\"}.
  Returns: Promise of {:person {...} :upstream \"synced\"|\"pending\"|\"not_configured\"}."
  [person-id patch]
  (api/fetch-json! (str "/api/people/" (enc person-id))
                   {:method "PATCH"
                    :body (select-keys patch [:role :status])}))

(defn resend-setup-email!
  "Resend the setup email to a pending person.

  Returns: Promise of {:person {...} :setup_email string}."
  [person-id]
  (api/fetch-json! (str "/api/people/" (enc person-id) "/setup-email")
                   {:method "POST"}))

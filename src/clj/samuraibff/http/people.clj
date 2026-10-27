(ns samuraibff.http.people
  "HTTP handlers for Settings → People (Admin only).

  Endpoints (under /api, active human Admin required):
  - GET   /api/people                    paginated people of the Admin's tenant
  - POST  /api/people                    add a person (Keycloak provisioning gate)
  - PATCH /api/people/:id                change role / suspend / reactivate / retry sync
  - POST  /api/people/:id/setup-email    resend the setup email (pending only)

  Members, Builders and machine principals receive 403. No endpoint accepts a
  tenant id, Keycloak role or Admin REST path from the client.

  Public API:
  - `list-people-handler`, `add-person-handler`, `update-person-handler`,
    `resend-setup-email-handler`"
  (:require
   [clojure.string :as str]
   [org.corfield.logging4j2 :as log]
   [samuraibff.access.membership :as membership]
   [samuraibff.access.policy :as policy]
   [samuraibff.keycloak.users :as kc.users])
  (:import
   (java.util UUID)))

(defn- json-response
  [status body]
  {:status status :body body})

(defn- forbidden [] (json-response 403 {:ok false :message "forbidden"}))
(defn- unavailable [] (json-response 503 {:ok false :message "db-unavailable"}))

(defn- parse-long-param
  [value default lo hi]
  (try (max lo (min hi (Long/parseLong (str value))))
       (catch Exception _ default)))

(defn- person-id
  [req]
  (try (UUID/fromString (str (get-in req [:path-params :id])))
       (catch Exception _ nil)))

(defn- body-of
  [req]
  (or (get-in req [:parameters :body]) (:body-params req) {}))

(def ^:private error-responses
  {:provisioning-disabled [409 "provisioning-disabled"]
   :identity-unavailable [409 "person-unavailable"]
   :provisioning-failed [502 "provisioning-failed"]
   :not-found [404 "not-found"]
   :not-pending [409 "not-pending"]
   :rate-limited [429 "rate-limited"]
   :last-admin [409 "last-admin"]})

(defn- error-response
  [error]
  (let [[status message] (get error-responses error [400 "invalid-request"])]
    (json-response status {:ok false :message message})))

(defmacro ^:private with-admin
  "Run body only for an active Admin with a datasource; bind admin and ds."
  [[admin-sym ds-sym req db] & body]
  `(let [~admin-sym (:auth/principal ~req)
         ~ds-sym (:ds ~db)]
     (cond
       (not (policy/can-manage-people? ~admin-sym)) (forbidden)
       (nil? ~ds-sym) (unavailable)
       :else (do ~@body))))

(defn list-people-handler
  "Handler for GET /api/people?q=&limit=&offset=."
  [{:keys [db config]}]
  (fn [req]
    (with-admin [admin ds req db]
      (let [query (merge (:params req) (get-in req [:parameters :query]))
            q (some-> (or (:q query) (get query "q")) str str/trim not-empty)
            result (membership/list-people ds admin
                                           {:q (when q (subs q 0 (min 100 (count q))))
                                            :limit (parse-long-param (or (:limit query) (get query "limit")) 50 1 200)
                                            :offset (parse-long-param (or (:offset query) (get query "offset")) 0 0 1000000)})]
        (json-response 200 (assoc result
                                  :ok true
                                  :provisioning_enabled (kc.users/configured? config)))))))

(defn add-person-handler
  "Handler for POST /api/people. Body: access.schemas/AddPersonRequest."
  [{:keys [db config]}]
  (fn [req]
    (with-admin [admin ds req db]
      (let [{:keys [email name role]} (body-of req)
            result (membership/add-person! ds config admin {:email (str/lower-case (str email))
                                                           :name (some-> name str/trim not-empty)
                                                           :role (or role "member")})]
        (if (:ok? result)
          (do (log/info "Person added" {:tenant_id (str (:tenant-id admin))
                                        :principal_id (str (get-in result [:person :id]))})
              (json-response 200 {:ok true
                                  :person (membership/person->api admin (:person result))
                                  :setup_email (name (:setup-email result))}))
          (error-response (:error result)))))))

(defn update-person-handler
  "Handler for PATCH /api/people/:id. Body: access.schemas/UpdatePersonRequest.

  revoke-credentials! is (fn [tenant-id manager-id]) and revokes the machine
  credentials a person manages when they lose Builder rights."
  [{:keys [db config]} revoke-credentials!]
  (fn [req]
    (with-admin [admin ds req db]
      (if-let [pid (person-id req)]
        (let [{:keys [role status]} (body-of req)
              result (membership/update-person! ds config admin pid {:role role :status status}
                                                revoke-credentials!)]
          (if (:ok? result)
            (json-response 200 {:ok true
                                :person (membership/person->api admin (:person result))
                                :upstream (name (:upstream result))})
            (error-response (:error result))))
        (error-response :not-found)))))

(defn resend-setup-email-handler
  "Handler for POST /api/people/:id/setup-email."
  [{:keys [db config]}]
  (fn [req]
    (with-admin [admin ds req db]
      (if-let [pid (person-id req)]
        (let [result (membership/resend-setup-email! ds config admin pid)]
          (if (:ok? result)
            (json-response 200 {:ok true
                                :person (membership/person->api admin (:person result))
                                :setup_email (name (:setup-email result))})
            (error-response (:error result))))
        (error-response :not-found)))))

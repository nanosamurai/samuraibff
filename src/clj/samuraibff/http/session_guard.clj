(ns samuraibff.http.session-guard
  "Authorize session-scoped HTTP operations before any content is loaded.

  Read operations need `can-read` (owner, Everyone audience or a read grant).
  Mutations need `can-manage` (active owner, or the manager of the
  organization API key that owns the session). An inaccessible session is
  reported as not found, so responses never confirm that it exists. A reader
  who attempts a mutation receives 403 `read-only-access`.

  Public API:
  - `require-principal!`, `require-readable-session!`, `require-managed-session!`
  - `access-summary`, `error-response`"
  (:require
   [samuraibff.access.policy :as policy]
   [samuraibff.db.session-access :as session-access])
  (:import
   (java.util UUID)
   (javax.sql DataSource)))

(defn require-principal!
  "Return the active principal attached by `wrap-principal`.

  Throws ex-info {:type :samuraibff.http/forbidden} when absent or inactive."
  [req]
  (let [principal (:auth/principal req)]
    (when-not (policy/active? principal)
      (throw (ex-info "forbidden" {:type :samuraibff.http/forbidden})))
    principal))

(defn require-readable-session!
  "Return the session row when the request principal may read it.

  Inputs: DataSource, Ring request, session UUID.
  Throws ex-info {:type :samuraibff.http/not-found} otherwise."
  [^DataSource ds req ^UUID session-id]
  (let [principal (require-principal! req)]
    (or (session-access/find-accessible-session ds principal session-id)
        (throw (ex-info "not-found" {:type :samuraibff.http/not-found})))))

(defn require-managed-session!
  "Return the session row when the request principal owns it.

  Throws {:type :samuraibff.http/not-found} for an unreadable session and
  {:type :samuraibff.http/read-only} for a readable session owned by someone else."
  [^DataSource ds req ^UUID session-id]
  (let [principal (require-principal! req)
        session (require-readable-session! ds req session-id)]
    (when-not (policy/can-manage-session? principal session)
      (throw (ex-info "read-only" {:type :samuraibff.http/read-only})))
    session))

(defn access-summary
  "Return the caller-relative access fields for a session row.

  Output: {:visibility string :is_owner boolean :can_manage boolean
           :owner {:display_name string? :is_me boolean :kind string?}
           :via_api_key string?}. `:owner :kind` is \"api_key\" for sessions
  owned by an organization API key; `:via_api_key` names the API key that
  created the session."
  [principal session]
  (let [owner? (policy/owns-session? principal session)]
    (cond-> {:visibility (or (:visibility session) "private")
             :is_owner owner?
             :can_manage (policy/can-manage-session? principal session)
             :owner (cond-> {:display_name (or (:owner_name session) (:owner_email session))
                             :is_me owner?}
                      (= "service" (:owner_type session)) (assoc :kind "api_key"))}
      (:created_via_name session) (assoc :via_api_key (:created_via_name session)))))

(defn error-response
  "Map an access ex-info type to a fixed JSON response, or nil when unrelated."
  [type]
  (case type
    :samuraibff.http/not-found {:status 404 :body {:ok false :message "not-found"}}
    :samuraibff.http/read-only {:status 403 :body {:ok false :message "read-only-access"}}
    :samuraibff.http/forbidden {:status 403 :body {:ok false :message "forbidden"}}
    nil))

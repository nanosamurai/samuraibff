(ns samuraibff.http.definition-access
  "Shared authorization helpers for workflow and webhook definition routes.

  Rules come from `samuraibff.access.policy`; this namespace only applies them
  to HTTP requests and definition rows:
  - Only active humans read definitions. Services get 403.
  - A definition the caller may not view is reported as 404 `not-found`.
  - A definition the caller may view but not manage is reported as 403
    `forbidden`.
  - Builders create `personal` definitions owned by themselves. Admins choose
    the availability (default `published`) and own what they create.
  - Only Admins change availability (publish or unpublish) and defaults.

  Errors are thrown as ex-info with :type
  `:samuraibff.http/forbidden`, `:samuraibff.http/not-found` or
  `:samuraibff.http/invalid-default`; `error-response` maps them to JSON.

  Public API:
  - `availabilities`, `require-human!`, `creation-availability`,
    `authorize-manage!`, `availability-change`, `owner-views`, `owner-view`,
    `in-transaction`, `record-audit!`, `error-response`, `forbidden!`"
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.transaction :as jdbc.tx]
   [samuraibff.access.policy :as policy]
   [samuraibff.db.audit :as db.audit]
   [samuraibff.db.principals :as db.principals])
  (:import
   (java.util UUID)))

(def availabilities
  "Allowed definition availability values."
  #{"personal" "published"})

(defn forbidden!
  "Throw the fixed forbidden error. Never returns."
  []
  (throw (ex-info "Forbidden" {:type :samuraibff.http/forbidden})))

(defn- not-found!
  []
  (throw (ex-info "Not found" {:type :samuraibff.http/not-found})))

(defn require-human!
  "Return the request principal when it is an active human, else throw forbidden.

  Inputs: Ring request with :auth/principal.
  Returns: principal map (see `samuraibff.access.policy`)."
  [req]
  (let [principal (:auth/principal req)]
    (if (and (policy/human? principal) (instance? UUID (:tenant-id principal)))
      principal
      (forbidden!))))

(defn creation-availability
  "Return the availability string for a new definition.

  Inputs: principal map, requested availability string or nil.
  Returns: \"personal\" or \"published\".
  Throws forbidden when the principal may not author definitions, or when a
  Builder requests `published`."
  [principal requested]
  (cond
    (not (policy/can-author-integrations? principal)) (forbidden!)
    (policy/can-publish-definitions? principal) (or requested "published")
    (contains? #{nil "personal"} requested) "personal"
    :else (forbidden!)))

(defn authorize-manage!
  "Return row when the principal may manage it.

  Inputs: principal map, definition row or nil.
  Throws not-found when the row is absent or not visible, forbidden when it is
  visible but not manageable."
  [principal row]
  (cond
    (or (nil? row) (not (policy/can-view-definition? principal row))) (not-found!)
    (not (policy/can-manage-definition? principal row)) (forbidden!)
    :else row))

(defn availability-change
  "Return the DB patch for a requested availability change, or nil for no change.

  Inputs: principal map, current definition row, requested availability or nil.
  Unpublishing a definition without an owner makes the acting Admin its owner,
  because a personal definition always has an owner.
  Throws forbidden when a non-Admin requests a different availability."
  [principal row requested]
  (cond
    (or (nil? requested) (= requested (:availability row))) nil
    (not (policy/can-publish-definitions? principal)) (forbidden!)
    (= "personal" requested) {:availability "personal"
                              :owner_user_id (or (:owner_user_id row) (:id principal))}
    :else {:availability "published"}))

(defn owner-views
  "Load owner display data for definition rows.

  Inputs: connectable, tenant UUID, definition rows.
  Returns: map owner UUID -> principal row (only owners that exist)."
  [connectable ^UUID tenant-id rows]
  (into {}
        (keep (fn [owner-id]
                (when-let [row (db.principals/find-by-id connectable tenant-id owner-id)]
                  [owner-id row])))
        (distinct (keep :owner_user_id rows))))

(defn owner-view
  "Return the API owner object for a definition, or nil when it has no owner.

  Inputs: principal map, owner map from `owner-views`, owner UUID or nil.
  Returns: {:id string :display_name string? :is_me boolean} or nil."
  [principal owners owner-id]
  (when owner-id
    (let [row (get owners owner-id)]
      {:id (str owner-id)
       :display_name (or (:name row) (:email row))
       :is_me (= (str owner-id) (str (:id principal)))})))

(defn in-transaction
  "Call f with one transaction connection and commit when it returns.

  Nested `with-transaction` calls inside f join this transaction.
  Inputs: DataSource, fn of one connection argument. Returns f's value."
  [ds f]
  (binding [jdbc.tx/*nested-tx* :ignore]
    (jdbc/with-transaction [tx ds]
      (f tx))))

(defn record-audit!
  "Append a succeeded user audit event for a definition change.

  Inputs: connectable, principal map, action string, target type string,
  target id, small payload-free details map."
  [connectable principal action target-type target-id details]
  (db.audit/record! connectable {:tenant-id (:tenant-id principal)
                                 :actor-id (:id principal)
                                 :actor-kind "user"
                                 :action action
                                 :target-type target-type
                                 :target-id target-id
                                 :outcome "succeeded"
                                 :details details}))

(defn error-response
  "Map a definition access error type to a Ring response, or nil.

  Inputs: ex-data :type keyword."
  [type]
  (case type
    :samuraibff.http/forbidden {:status 403 :body {:ok false :message "forbidden"}}
    :samuraibff.http/not-found {:status 404 :body {:ok false :message "not-found"}}
    :samuraibff.http/invalid-default {:status 400 :body {:ok false :message "invalid-default"}}
    nil))

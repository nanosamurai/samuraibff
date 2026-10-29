(ns samuraibff.access.integrations
  "Use checks for workflow and webhook definitions selected by a session.

  A session may run a definition only when its owner may use it
  (`samuraibff.access.policy/can-use-definition?`): published definitions for
  every active human, personal definitions only for their own Builder/Admin
  owner. Selection is checked when the session is created and again when the
  routing snapshot is resolved for admission, so a later demotion,
  suspension or unpublishing stops new use. Using a definition never grants
  its author access to the session or its results.

  Public API:
  - `assert-overrides-usable!`, `owner-principal`, `usable-predicate`"
  (:require
   [samuraibff.access.policy :as policy]
   [samuraibff.access.principal :as access.principal]
   [samuraibff.db.principals :as db.principals]
   [samuraibff.db.webhooks :as db.webhooks]
   [samuraibff.db.workflows :as db.workflows])
  (:import
   (java.util UUID)))

(defn- parse-ids
  [ids]
  (->> (or ids [])
       (keep (fn [id] (try (UUID/fromString (str id)) (catch Exception _ nil))))
       set))

(defn- assert-usable!
  [principal definitions requested-ids]
  (let [by-id (into {} (map (juxt :id identity)) definitions)]
    (doseq [id requested-ids]
      (let [definition (get by-id id)]
        (when-not (and definition (policy/can-use-definition? principal definition))
          (throw (ex-info "definition-not-usable"
                          {:type :samuraibff.access/definition-not-usable})))))))

(defn assert-overrides-usable!
  "Reject explicitly selected definitions that the principal may not use.

  Inputs: DataSource, principal map, webhook overrides map or nil, workflow
  overrides map or nil (shapes per `schemas/CreateSessionRequest`).
  Unknown, foreign-tenant and unusable ids are reported identically.
  Throws ex-info {:type :samuraibff.access/definition-not-usable}."
  [ds principal webhook-overrides workflow-overrides]
  (let [tenant-id (:tenant-id principal)
        webhook-ids (parse-ids (:webhook_ids webhook-overrides))
        workflow-ids (parse-ids (:workflow_ids workflow-overrides))]
    (when (seq webhook-ids)
      (assert-usable! principal (db.webhooks/list-webhooks ds tenant-id) webhook-ids))
    (when (seq workflow-ids)
      (assert-usable! principal (db.workflows/list-workflows ds tenant-id) workflow-ids))
    nil))

(defn owner-principal
  "Load the current principal map of a session owner, or nil.

  Inputs: connectable, tenant UUID, owner UUID or nil."
  [connectable ^UUID tenant-id owner-id]
  (when owner-id
    (access.principal/row->principal (db.principals/find-by-id connectable tenant-id owner-id))))

(defn usable-predicate
  "Return a predicate over definition rows for a session owner.

  An absent or inactive owner can use nothing."
  [owner]
  (fn [definition]
    (policy/can-use-definition? owner definition)))

(ns samuraibff.db.audit
  "Append-only, payload-free access audit events (`access_audit_events`).

  Events record who changed membership, roles, credentials, definitions or
  recording audiences. They never contain tokens, secrets, audio, transcript
  text or prompts. `details` holds small, safe before/after values only.

  Public API:
  - `record!`"
  (:require
   [cheshire.core :as cheshire]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [samuraibff.util.uuid :as uuid])
  (:import
   (org.postgresql.util PGobject)))

(def ^:private actor-kinds #{"user" "service" "operator" "system"})
(def ^:private outcomes #{"succeeded" "denied" "failed" "pending"})

(defn- jsonb
  ^PGobject [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (cheshire/generate-string (or value {})))))

(defn record!
  "Append one audit event in the caller's connection or transaction.

  Inputs: connectable and a map
  {:tenant-id uuid :actor-id uuid? :actor-kind string :action string
   :target-type string :target-id any? :outcome string :details map?}.
  Returns nil. Throws for an unknown actor kind or outcome, so callers cannot
  silently write malformed audit records."
  [connectable {:keys [tenant-id actor-id actor-kind action target-type target-id outcome details]}]
  (when-not (and tenant-id (actor-kinds actor-kind) (outcomes outcome)
                 (string? action) (string? target-type))
    (throw (ex-info "Invalid audit event" {:type ::invalid-event :action action})))
  (jdbc/execute-one! connectable
                     (sql/format (-> (h/insert-into :access_audit_events)
                                     (h/values [{:id (uuid/uuid7)
                                                 :tenant_id tenant-id
                                                 :actor_user_id actor-id
                                                 :actor_kind actor-kind
                                                 :action action
                                                 :target_type target-type
                                                 :target_id (some-> target-id str)
                                                 :outcome outcome
                                                 :details (jsonb details)}]))))
  nil)

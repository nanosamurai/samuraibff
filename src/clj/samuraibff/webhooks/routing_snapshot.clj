(ns samuraibff.webhooks.routing-snapshot
  "Resolve session-scoped webhook routing snapshot for `sessions.meta`.

  This namespace implements RFC-0001 (webhook egress) phase 1 requirement:
  the BFF resolves tenant defaults + per-session overrides into an immutable
  session routing snapshot and publishes it to the compacted Kafka topic
  `sessions.meta`.

  The output is intentionally JSON-friendly (plain Clojure maps/vectors).

  Inputs are tenant-scoped and expect that the caller already authenticated
  the tenant and created the session id.

  Access:
  - Only webhooks the session owner may use are included
    (`samuraibff.access.integrations/usable-predicate`). Tenant defaults add
    published webhooks only. A session without an active human owner routes
    to no webhook.

  Security notes:
  - Secrets are never included as plaintext. We only include secret references.
  - This snapshot may include customer URLs; those are intentionally meant for
    the webhook dispatcher. Do not expose it via customer API without review.
  "
  (:require
   [samuraibff.access.integrations :as access.integrations]
   [samuraibff.db.webhooks :as db.webhooks])
  (:import
   (java.util UUID)))

(def ^:private supported-event-types
  "Event types supported in v1 per RFC."
  #{"transcript.refined.segment"
    "recording.finished"
    "transcript.final.ready"})

(defn- normalize-overrides
  "Normalize :webhook_overrides request payload.

  Input shape (conceptual):
  {:use_defaults boolean?
   :webhook_ids [uuid-string ...]?
   :disable_event_types [string ...]?}

  Returns:
  {:use-defaults? boolean
   :webhook-ids (set UUID)
   :disable-event-types (set string)}"
  [overrides]
  (let [use-defaults? (if (contains? overrides :use_defaults)
                        (boolean (:use_defaults overrides))
                        true)
        webhook-ids (->> (or (:webhook_ids overrides) [])
                         (keep (fn [s]
                                 (try (UUID/fromString (str s))
                                      (catch Exception _ nil))))
                         set)
        disable-event-types (->> (or (:disable_event_types overrides) [])
                                 (map str)
                                 (filter supported-event-types)
                                 set)]
    {:use-defaults? use-defaults?
     :webhook-ids webhook-ids
     :disable-event-types disable-event-types}))

(defn- webhook->target
  "Convert a webhook DB row to a routing snapshot target.

  Input:
  - webhook row map from db.webhooks/list-webhooks or find-webhook.

  Output:
  - JSON-friendly map."
  [w]
  (let [auth-type (some-> (:auth_type w) str)]
    {:webhook_id (str (:id w))
     :url (:url w)
     :enabled (boolean (:enabled w))
     :auth (cond
             (= auth-type "hmac") {:type "hmac"
                                   :secret_ref (:hmac_secret_ref w)}
             (= auth-type "oauth") {:type "oauth"
                                    :token_url (:oauth_token_url w)
                                    :client_id (:oauth_client_id w)
                                    :scopes (:oauth_scopes w)
                                    :client_secret_ref (:oauth_client_secret_ref w)}
             (= auth-type "api_key") {:type "api_key"
                                      :header_name (:api_key_header_name w)
                                      :prefix (:api_key_prefix w)
                                      :secret_ref (:api_key_ref w)}
             :else {:type "none"})
     :static_headers (or (:static_headers w) {})}))

(defn- selected?
  "True when a webhook row is selected for a session owner.

  Explicit selections need only be usable by the owner; defaults must also be
  published."
  [usable? default-ids explicit-ids w]
  (and (usable? w)
       (or (contains? explicit-ids (:id w))
           (and (contains? default-ids (:id w))
                (= "published" (:availability w))))))

(defn resolve-routing-snapshot
  "Resolve the routing snapshot for a session.

  Inputs:
  - ds: DataSource or connectable
  - tenant-id: UUID
  - session-id: UUID
  - webhook-overrides: map or nil (shape per schemas/CreateSessionRequest)
  - owner: principal map of the session owner (see
    `samuraibff.access.integrations/owner-principal`) or nil

  Returns:
  - routing map (JSON-friendly):
    {:targets_by_event_type {event-type [target ...] ...}}
  "
  [ds ^UUID tenant-id ^UUID _session-id webhook-overrides owner]
  (let [{:keys [use-defaults? webhook-ids disable-event-types]} (normalize-overrides (or webhook-overrides {}))
        {:keys [webhook_ids]} (if use-defaults?
                                (db.webhooks/get-defaults ds tenant-id)
                                {:webhook_ids []})
        default-ids (set webhook_ids)
        usable? (access.integrations/usable-predicate owner)
        webhooks (if (or (seq default-ids) (seq webhook-ids))
                   (->> (db.webhooks/list-webhooks ds tenant-id)
                        (filter #(selected? usable? default-ids webhook-ids %))
                        (filter :enabled)
                        vec)
                   [])
        targets-by-event
        (reduce
         (fn [acc w]
           (let [subs (db.webhooks/list-subscriptions ds tenant-id (:id w))
                 subs (->> subs (map str) (filter supported-event-types) (remove disable-event-types))
                 tgt (webhook->target w)]
             (reduce
              (fn [acc2 et]
                (update acc2 et (fnil conj []) tgt))
              acc
              subs)))
         {}
         webhooks)
        targets-by-event (reduce (fn [m et] (update m et #(vec (or % []))))
                                 targets-by-event
                                 supported-event-types)]
    {:targets_by_event_type targets-by-event}))

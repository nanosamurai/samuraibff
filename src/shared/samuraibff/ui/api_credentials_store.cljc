(ns samuraibff.ui.api-credentials-store
  "Pure state transitions for the API credentials management UI.

  Motivation:
  - We keep UI state in atoms in CLJS, but we want unit tests to run under the
    existing CLJ test runner.
  - Therefore this namespace is `.cljc` and contains pure functions only.

  Security:
  - `client_secret` is treated as transient UI data.
  - This module never logs and never persists secrets.

  State shape (map):
  {:items        [credential ...]
   :loading?     boolean
   :error        string?         ;; safe, user-facing
   :show-revoked? boolean
   :secret-modal {:open? boolean
                  :credential-id string?
                  :client-id     string?
                  :client-secret string? ;; kept only while modal open
                  :copied?       boolean}}
  "
  (:require
    [clojure.string :as str]))

(defn init-state
  "Return the initial api-credentials UI state." 
  []
  {:items []
   :loading? false
   :error nil
   :show-revoked? false
   :secret-modal {:open? false
                  :credential-id nil
                  :client-id nil
                  :client-secret nil
                  :copied? false}})

(defn set-loading
  "Set loading flag.

  Inputs:
  - state: map
  - loading?: boolean

  Returns updated state." 
  [state loading?]
  (assoc (or state (init-state)) :loading? (boolean loading?)))

(defn set-error
  "Set a safe user-facing error message.

  Inputs:
  - state: map
  - message: string? (nil clears)

  Returns updated state." 
  [state message]
  (assoc (or state (init-state)) :error (some-> message str not-empty)))

(defn set-items
  "Replace credential items.

  Inputs:
  - state: map
  - items: vector of maps (each item should include :id or :credential_id)

  Returns updated state." 
  [state items]
  (assoc (or state (init-state)) :items (vec (or items []))))

(defn toggle-show-revoked
  "Toggle whether revoked credentials are shown.

  Inputs:
  - state: map

  Returns updated state." 
  [state]
  (update (or state (init-state)) :show-revoked? not))

(defn open-secret-modal
  "Open the secret modal with a newly returned secret.

  Inputs:
  - state: map
  - {:keys [credential-id client-id client-secret]} strings

  Returns updated state.

  Notes:
  - This stores the `client-secret` only while the modal is open." 
  [state {:keys [credential-id client-id client-secret]}]
  (assoc (or state (init-state))
         :secret-modal {:open? true
                        :credential-id (some-> credential-id str not-empty)
                        :client-id (some-> client-id str not-empty)
                        :client-secret (some-> client-secret str)
                        :copied? false}))

(defn close-secret-modal
  "Close the secret modal and clear secret from state.

  Inputs:
  - state: map

  Returns updated state." 
  [state]
  (assoc (or state (init-state))
         :secret-modal {:open? false
                        :credential-id nil
                        :client-id nil
                        :client-secret nil
                        :copied? false}))

(defn mark-secret-copied
  "Set the secret modal copied indicator.

  Inputs:
  - state: map
  - copied?: boolean

  Returns updated state." 
  [state copied?]
  (assoc-in (or state (init-state)) [:secret-modal :copied?] (boolean copied?)))

(defn- item-id
  [item]
  (or (:id item)
      (:credential_id item)
      (:credential-id item)))

(defn mark-revoked
  "Mark a credential revoked in local items.

  Inputs:
  - state: map
  - credential-id: string/uuid (compared by string)

  Returns updated state.

  Notes:
  - We set `:revoked_at` to a truthy placeholder if absent (UI only)." 
  [state credential-id]
  (let [cid (some-> credential-id str)
        state (or state (init-state))]
    (update state :items
            (fn [items]
              (mapv (fn [it]
                      (if (= cid (some-> (item-id it) str))
                        (cond-> (assoc it :revoked_at (or (:revoked_at it) "revoked"))
                          (contains? it :revoked-at) (assoc :revoked-at (or (:revoked-at it) "revoked")))
                        it))
                    (vec (or items [])))))))

(defn visible-items
  "Return items filtered based on :show-revoked?.

  Inputs:
  - state map

  Returns vector of items." 
  [state]
  (let [state (or state (init-state))
        show-revoked? (true? (:show-revoked? state))]
    (->> (vec (or (:items state) []))
         (filterv (fn [it]
                    (let [revoked? (some? (or (:revoked_at it) (:revoked-at it)))]
                      (or show-revoked?
                          (not revoked?))))))))

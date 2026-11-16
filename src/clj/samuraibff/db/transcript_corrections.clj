(ns samuraibff.db.transcript-corrections
  "DB access for human corrections of final transcripts.

  Table `transcript_corrections` (migration 025) is append-only: this
  namespace inserts rows and never updates or deletes them. Rows go away only
  with their session (ON DELETE CASCADE).

  Appending takes a transaction-scoped advisory lock on the transcript, so
  corrections of one transcript get consecutive `seq` numbers. The unique
  (transcript_id, seq) constraint backs this up.

  Public API:
  - `list-for-session`, `list-for-transcript`
  - `append!`"
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [samuraibff.transcripts.correction-requests :as correction-requests]
   [samuraibff.transcripts.corrections :as corrections])
  (:import
   (java.util UUID)
   (javax.sql DataSource)
   (org.postgresql.util PGobject)))

(def ^:private columns
  [:c.id :c.transcript_id :c.seq :c.kind :c.segment_index :c.speaker_label
   :c.old_value :c.new_value :c.undoes_id :c.audio_start_s :c.audio_end_s
   :c.author_user_id :c.created_at
   [:u.name :author_name] [:u.email :author_email]])

(defn- decode-json
  "Decode a json/jsonb column value into Clojure data with keyword keys."
  [v]
  (cond
    (nil? v) nil
    (instance? PGobject v) (some-> ^PGobject v .getValue (cheshire/parse-string true))
    (string? v) (cheshire/parse-string v true)
    :else v))

(defn- ->jsonb
  ^PGobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (cheshire/generate-string (or x {})))))

(defn- decode-row
  [row]
  (-> row
      (update :old_value decode-json)
      (update :new_value decode-json)))

(defn list-for-session
  "Return all corrections of a session's transcripts, scoped to tenant.

  Inputs: DataSource, tenant UUID, session UUID.
  Returns a vector ordered by transcript and seq of maps with :id
  :transcript_id :seq :kind :segment_index :speaker_label :old_value
  :new_value :undoes_id :audio_start_s :audio_end_s :author_user_id
  :author_name :author_email :created_at (json values decoded)."
  [^DataSource ds ^UUID tenant-id ^UUID session-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id))
    (throw (ex-info "list-for-session missing required params" {:tenant-id tenant-id :session-id session-id})))
  (let [q (-> (apply h/select columns)
              (h/from [:transcript_corrections :c])
              (h/left-join [:app_users :u] [:= :u.id :c.author_user_id])
              (h/where [:= :c.tenant_id tenant-id] [:= :c.session_id session-id])
              (h/order-by :c.transcript_id :c.seq))]
    (mapv decode-row (jdbc/execute! ds (sql/format q) {:builder-fn rs/as-unqualified-lower-maps}))))

(defn list-for-transcript
  "Return the corrections of one transcript ordered by seq, scoped to tenant.

  Inputs: DataSource (or connection), tenant UUID, transcript UUID.
  Returns the same maps as `list-for-session`."
  [ds ^UUID tenant-id ^UUID transcript-id]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID transcript-id))
    (throw (ex-info "list-for-transcript missing required params" {:tenant-id tenant-id :transcript-id transcript-id})))
  (let [q (-> (apply h/select columns)
              (h/from [:transcript_corrections :c])
              (h/left-join [:app_users :u] [:= :u.id :c.author_user_id])
              (h/where [:= :c.tenant_id tenant-id] [:= :c.transcript_id transcript-id])
              (h/order-by :c.seq))]
    (mapv decode-row (jdbc/execute! ds (sql/format q) {:builder-fn rs/as-unqualified-lower-maps}))))

(defn- lock-transcript!
  "Take a transaction-scoped advisory lock for one transcript."
  [tx ^UUID transcript-id]
  (jdbc/execute! tx (sql/format {:select [[[:pg_advisory_xact_lock
                                             [:hashtextextended (str "transcript-corrections:" transcript-id) 0]]
                                            :locked]]})))

(defn- find-final-transcript
  "Return {:id :segments} of a final transcript of the session, or nil."
  [tx ^UUID tenant-id ^UUID session-id ^UUID transcript-id]
  (some-> (jdbc/execute-one! tx (sql/format {:select [:id :segments]
                                             :from [:session_transcripts]
                                             :where [:and [:= :id transcript-id]
                                                     [:= :tenant_id tenant-id]
                                                     [:= :session_id session-id]
                                                     [:= :type "final"]]})
                             {:builder-fn rs/as-unqualified-lower-maps})
          (update :segments #(vec (or (decode-json %) [])))))

(defn- find-speaker
  "Return the tenant's enrolled speaker {:id :label}, or nil."
  [tx ^UUID tenant-id speaker-id]
  (when-let [id (try (UUID/fromString (str speaker-id)) (catch Exception _ nil))]
    (jdbc/execute-one! tx (sql/format {:select [:id :label]
                                       :from [:speakers]
                                       :where [:and [:= :tenant_id tenant-id] [:= :id id]]})
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn- enrolled-ids
  "Return {label speaker-id} for labels that exactly one enrolled speaker of
  the tenant has."
  [tx ^UUID tenant-id labels]
  (if (empty? labels)
    {}
    (->> (jdbc/execute! tx (sql/format {:select [:label [[:min [:cast :id :text]] :id]]
                                        :from [:speakers]
                                        :where [:and [:= :tenant_id tenant-id] [:in :label (vec labels)]]
                                        :group-by [:label]
                                        :having [:= [:count :*] 1]})
                        {:builder-fn rs/as-unqualified-lower-maps})
         (into {} (map (juxt :label :id))))))

(defn- insert-row!
  [tx row]
  (let [values (cond-> (select-keys row [:id :tenant_id :session_id :transcript_id :seq :kind
                                         :segment_index :speaker_label :undoes_id
                                         :audio_start_s :audio_end_s :author_user_id])
                 :always (assoc :old_value (->jsonb (:old_value row))
                                :new_value (->jsonb (:new_value row)))
                 (string? (:undoes_id row)) (update :undoes_id #(UUID/fromString %)))]
    (jdbc/execute-one! tx (sql/format (-> (h/insert-into :transcript_corrections)
                                          (h/values [values])
                                          (h/returning :created_at)))
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn append!
  "Append one correction to a final transcript.

  Inputs:
  - ds: DataSource
  - {:keys [tenant-id session-id transcript-id author-id request]}
    UUIDs, plus the request map described in
    `samuraibff.transcripts.correction-requests/plan`

  In one transaction: lock the transcript, check it is a final transcript of
  the session, load its corrections and any named speakers, check the
  request and insert the row.

  Returns {:correction row :head_seq int} on success, or {:error keyword}
  (:transcript-not-found or a `plan` error)."
  [^DataSource ds {:keys [^UUID tenant-id ^UUID session-id ^UUID transcript-id author-id request]}]
  (when-not (and ds (instance? UUID tenant-id) (instance? UUID session-id) (instance? UUID transcript-id))
    (throw (ex-info "append! missing required params" {:tenant-id tenant-id :session-id session-id
                                                       :transcript-id transcript-id})))
  (jdbc/with-transaction [tx ds]
    (lock-transcript! tx transcript-id)
    (if-let [transcript (find-final-transcript tx tenant-id session-id transcript-id)]
      (let [stored (list-for-transcript tx tenant-id transcript-id)
            labels (corrections/machine-labels (:segments transcript))
            speaker (when (some? (:speaker_id request)) (find-speaker tx tenant-id (:speaker_id request)))
            {:keys [row error]} (correction-requests/plan {:segments (:segments transcript)
                                                           :corrections stored
                                                           :request request
                                                           :speaker speaker
                                                           :enrolled-ids (enrolled-ids tx tenant-id labels)})]
        (if error
          {:error error}
          (let [row (assoc row
                           :id (UUID/randomUUID)
                           :tenant_id tenant-id
                           :session_id session-id
                           :transcript_id transcript-id
                           :author_user_id author-id)
                {:keys [created_at]} (insert-row! tx row)]
            {:correction (assoc row :created_at created_at)
             :head_seq (:seq row)})))
      {:error :transcript-not-found})))

(defn display-name
  "Return the author name of a correction row: name, else email, else nil."
  [row]
  (or (not-empty (str/trim (str (:author_name row))))
      (not-empty (str/trim (str (:author_email row))))))

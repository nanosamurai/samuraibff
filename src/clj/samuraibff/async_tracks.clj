(ns samuraibff.async-tracks
  "Deployment-owned asynchronous choices, defaults and tenant-scoped selection."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def stages
  "Public stage, configuration key and stream-control key."
  [{:stage "refined" :config-key :refinement-tracks :control-key :refinement_tracks}
   {:stage "final" :config-key :final-tracks :control-key :final_tracks}])

(defn reject!
  "Reject invalid catalog/selection data without echoing it to a client or log."
  [reason]
  (throw (ex-info "Invalid asynchronous track selection"
                  {:type :samuraibff.final-tracks/invalid-plan :reason reason})))

(defn catalog
  "Validate one stage's operator JSON. Legacy entries default to selected.
  Optional tenant_ids restrict a secondary choice; the primary is shared."
  [config config-key]
  (let [refined? (= config-key :refinement-tracks)
        profile (if refined? "whisperx-medium-refined-r1" "whisperx-medium-final-r1")
        test-profile (if refined? "test-refined-r1" "test-final-r1")
        raw (get-in config [config-key :selections-json])
        entries (if (seq raw)
                  (try (json/parse-string-strict raw true)
                       (catch Exception _ (reject! :invalid-catalog)))
                  [{:track_id "whisperx" :profile_id profile :primary true
                    :display_name "WhisperX" :default_selected true}])
        allowed (cond-> #{profile}
                  (true? (get-in config [config-key :test-profile-enabled?])) (conj test-profile))
        keys-allowed #{:track_id :profile_id :primary :display_name :default_selected :tenant_ids}]
    (when-not
     (and (vector? entries) (<= 1 (count entries) 4)
          (= (count entries) (count (distinct (map :track_id entries))))
          (= 1 (count (filter :primary entries)))
          (every?
           (fn [{:keys [track_id profile_id primary display_name default_selected tenant_ids] :as entry}]
             (and (map? entry) (every? keys-allowed (keys entry))
                  (boolean? primary) (string? track_id)
                  (re-matches #"[a-z0-9][a-z0-9._-]{0,95}" track_id)
                  (contains? allowed profile_id)
                  (or (not (contains? entry :display_name))
                      (and (string? display_name) (<= 1 (count display_name) 80)
                           (not (str/blank? display_name))
                           (not (re-find #"[\p{Cntrl}]" display_name))))
                  (or (not (contains? entry :default_selected)) (boolean? default_selected))
                  (or (not (contains? entry :tenant_ids))
                      (and (not primary) (vector? tenant_ids) (<= 1 (count tenant_ids) 100)
                           (every? #(and (string? %)
                                         (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" %)) tenant_ids)))
                  (or (not primary) (not= false default_selected)))) entries))
      (reject! :invalid-catalog))
    (mapv #(merge {:display_name (:track_id %) :default_selected true} %) entries)))

(defn permitted
  "Return configured entries visible to a tenant; this is not worker health."
  [config config-key tenant-id]
  (filterv #(or (not (contains? % :tenant_ids))
                (some #{(str tenant-id)} (:tenant_ids %)))
           (catalog config config-key)))

(defn public-catalog
  "Return enabled, permitted choices without addresses, runtime data or ACLs."
  [config tenant-id]
  (vec (mapcat
        (fn [{:keys [stage config-key]}]
          (when (get-in config [config-key :enabled?])
            (map #(assoc (select-keys % [:track_id :profile_id :primary :display_name :default_selected])
                         :stage stage)
                 (permitted config config-key tenant-id)))) stages)))

(defn resolve-selection
  "Resolve permitted IDs in catalog order and choose one compatibility output.
  Prefer the operator primary when selected, otherwise the first selected entry.
  The frozen worker contract still contains exactly one primary per stage."
  [config config-key tenant-id requested]
  (let [entries (permitted config config-key tenant-id)
        ids (if (nil? requested) (mapv :track_id (filter :default_selected entries)) requested)
        available (set (map :track_id entries))
        selected (filterv #(contains? (set ids) (:track_id %)) entries)
        primary (:track_id (or (first (filter :primary selected)) (first selected)))]
    (when-not (and (vector? ids) (<= 1 (count ids) 4)
                   (= (count ids) (count (distinct ids)))
                   (every? available ids))
      (reject! :invalid-track-selection))
    (mapv #(assoc (select-keys % [:track_id :profile_id]) :primary (= primary (:track_id %))) selected)))

(defn requested-controls
  "Read optional stage ID lists from audio query parameters without retargeting
  a frozen plan. Catalog/tenant resolution happens only when a plan is created."
  [config params controls]
  (reduce
   (fn [acc {:keys [stage config-key control-key]}]
     (if-let [raw (or (get params control-key) (get params (name control-key)))]
       (let [ids (when (and (string? raw) (<= (count raw) 387))
                   (str/split raw #"," -1))]
         (when-not (and (get-in config [config-key :enabled?]) (get controls (keyword stage))
                        (<= 1 (count ids) 4) (= (count ids) (count (distinct ids)))
                        (every? #(re-matches #"[a-z0-9][a-z0-9._-]{0,95}" %) ids))
           (reject! :invalid-track-selection))
         (assoc acc control-key ids))
       acc)) controls stages))

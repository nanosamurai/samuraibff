(ns samuraibff.ui.output-settings
  "Pure output selection rules shared by settings and audio admission.")

(def control-keys
  "Stage to its explicit track selection control."
  {:realtime :realtime_tracks :refined :refinement_tracks :final :final_tracks})

(defn entries
  "Return safe catalog entries for a stage from the authenticated user detail."
  [detail stage]
  (if (= stage :realtime)
    (mapv (fn [id] {:track_id id :display_name id :default_selected true}) (:realtime_tracks detail))
    (filterv #(= (name stage) (:stage %)) (:async_tracks detail))))

(defn selected-ids
  "Resolve defaults only for an unset selection; an explicit empty list stays empty."
  [controls stage catalog]
  (let [requested (get controls (control-keys stage))
        ids (if (nil? requested) (set (map :track_id (filter :default_selected catalog))) (set requested))]
    (mapv :track_id (filter #(contains? ids (:track_id %)) catalog))))

(defn enabled?
  "Use selected tracks when configured, retaining the legacy switch otherwise."
  [controls stage catalog]
  (boolean (and (not (false? (get controls stage)))
                (if (seq catalog)
                  (seq (selected-ids controls stage catalog))
                  (let [requested (get controls (control-keys stage))]
                    (or (nil? requested) (seq requested)))))))

(defn select-track
  "Select or clear a track and derive the stage switch, remembering the last nonempty set."
  [controls stage catalog track-id checked?]
  (let [current (if (enabled? controls stage catalog) (selected-ids controls stage catalog) [])
        selected ((if checked? conj disj) (set current) track-id)
        ids (mapv :track_id (filter #(contains? selected (:track_id %)) catalog))]
    (cond-> (assoc controls stage (boolean (seq ids)) (control-keys stage) ids)
      (seq ids) (assoc-in [:remembered_tracks stage] ids)
      (and (empty? ids) (seq current)) (assoc-in [:remembered_tracks stage] current))))

(defn toggle-stage
  "Turn a stage off or restore its last selection, then defaults or the first track."
  [controls stage catalog checked?]
  (if (empty? catalog)
    (assoc controls stage (boolean checked?))
    (let [current (selected-ids controls stage catalog)
          allowed (set (map :track_id catalog))
          remembered (filterv allowed (get-in controls [:remembered_tracks stage]))
          defaults (selected-ids {} stage catalog)
          restored (or (seq current) (seq remembered) (seq defaults) [(:track_id (first catalog))])]
      (cond-> (assoc controls stage (boolean checked?) (control-keys stage) (if checked? (vec restored) []))
        (and (not checked?) (seq current)) (assoc-in [:remembered_tracks stage] current)))))

(defn effective-controls
  "Normalize admission switches and required audio retention without sending UI preferences."
  [controls detail]
  (let [normalized (reduce (fn [acc stage] (assoc acc stage (enabled? controls stage (entries detail stage))))
                           controls (keys control-keys))
        retained? (some #(and (get normalized %) (seq (entries detail %))) [:refined :final])]
    (cond-> (dissoc normalized :remembered_tracks)
      retained? (assoc :store_recording true))))

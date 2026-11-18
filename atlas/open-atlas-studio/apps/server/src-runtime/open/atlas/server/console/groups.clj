(ns open.atlas.server.console.groups
  "Instance-local organization only. Groups never change artifact coordinates,
   Studio workspaces, data-plane authorization or token grants."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.store :as store]))

(defn- path [runtime] (io/file (store/root (:instance runtime)) "graph-groups.edn"))
(defn registry [runtime] (or (store/read-edn (path runtime)) {:groups {} :members {}}))
(defn known-graphs [runtime]
  (into (set (map :graph (graph/shelf-versions (:graph runtime))))
        (for [[id r] (graph/lifecycle-records (:graph runtime)) :when (= :archived (:state r))] id)))
(defn- error [message] {:status :error :error message})
(defn valid-group? [runtime id] (or (nil? id) (contains? (:groups (registry runtime)) id)))

(defn summary [registry graph-ids]
  (let [counts (frequencies (map #(get-in registry [:members %]) graph-ids))]
    {:groups (mapv (fn [[id g]] (assoc g :id id :graph-count (get counts id 0)))
                   (sort-by (comp str/lower-case :name val) (:groups registry)))
     :ungrouped-count (get counts nil 0)}))

(defn- write! [runtime actor data]
  (if (seq (store/writable-findings (:instance runtime)))
    (error "This instance is not writable.")
    (do (store/write-edn! (path runtime) (assoc data :updated-by actor :updated-at (str (java.time.Instant/now))))
        {:status :ok})))

(defn save-group! [{:keys [runtime]} actor {:keys [id name]}]
  (store/with-lock (:instance runtime) :graph-maintenance
    (let [data (registry runtime) label (when (string? name) (str/trim name))]
      (cond
        (or (str/blank? label) (> (count label) 80)) (error "Group name must contain 1–80 characters.")
        (and id (not (contains? (:groups data) id))) (error "This Group no longer exists. Refresh the list.")
        (some (fn [[other g]] (and (not= other id) (= (str/lower-case label) (str/lower-case (:name g))))) (:groups data))
        (error "A Group with this name already exists.")
        :else (let [id (or id (str (random-uuid))) result (write! runtime actor (assoc-in data [:groups id] {:name label}))]
                (assoc result :group {:id id :name label}))))))

(defn remove-group! [{:keys [runtime]} actor {:keys [id]}]
  (store/with-lock (:instance runtime) :graph-maintenance
    (let [data (registry runtime)]
      (if-not (contains? (:groups data) id) (error "This Group no longer exists.")
        (write! runtime actor (-> data (update :groups dissoc id)
                                  (update :source-members #(into {} (remove (fn [[_ g]] (= id g))) %))
                                  (update :members #(into {} (remove (fn [[_ g]] (= id g))) %))))))))

(defn assign-sources!
  "The Source registry supplies the known IDs; Group metadata never stores
   connection details or changes immutable release bindings."
  [runtime actor source-ids group-id known]
  (store/with-lock (:instance runtime) :graph-maintenance
    (cond
      (not (and (vector? source-ids) (seq source-ids) (<= (count source-ids) 500) (every? known source-ids)))
      (error "Select existing Sources before moving them.")
      (not (valid-group? runtime group-id)) (error "The destination Group no longer exists.")
      :else (write! runtime actor (update (registry runtime) :source-members
                                         (fn [members] (reduce (fn [m id] (if group-id (assoc m id group-id) (dissoc m id)))
                                                               (or members {}) source-ids)))))))

(defn assign! [{:keys [runtime]} actor {:keys [graph-ids group-id]}]
  (store/with-lock (:instance runtime) :graph-maintenance
    (let [data (registry runtime) known (known-graphs runtime)]
      (cond
        (not (and (vector? graph-ids) (seq graph-ids) (<= (count graph-ids) 500) (every? known graph-ids)))
        (error "Select existing Graphs before moving them. Refresh if one was deleted.")
        (not (valid-group? runtime group-id)) (error "The destination Group no longer exists.")
        :else (write! runtime actor (update data :members
                                           (fn [members] (reduce (fn [m id] (if group-id (assoc m id group-id) (dissoc m id))) members graph-ids))))))))

(defn forget-graph! [runtime actor id]
  (store/with-lock (:instance runtime) :graph-maintenance
    (write! runtime actor (update (registry runtime) :members dissoc id))))

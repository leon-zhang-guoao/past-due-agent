(ns open.atlas.server.console.catalog
  "Bounded console read models. Lists never materialize artifact contents or
   embed nested token/version histories; details are explicitly requested."
  (:require [clojure.string :as str]
            [open.atlas.runtime.graph :as graph]
            [open.atlas.runtime.instance :as instance]
            [open.atlas.runtime.token :as token]
            [open.atlas.server.console.groups :as groups]))

(defn- instant [] (str (java.time.Instant/now)))
(defn- number-option [x default]
  (try (max 1 (Long/parseLong (str (or x default)))) (catch Exception _ default)))

(defn page
  "Stable ordering is supplied by the caller. Clamp stale/out-of-range pages,
   and cap size so malformed clients cannot accidentally request everything."
  [rows options]
  (let [rows (vec rows) size (min 50 (number-option (get options "size") 20))
        total (count rows) pages (max 1 (long (Math/ceil (/ total (double size)))))
        p (min pages (number-option (get options "page") 1))
        offset (* (dec p) size)]
    {:status :ok :items (subvec rows offset (min total (+ offset size)))
     :total total :page p :size size :pages pages}))

(defn- matches? [q text]
  (str/includes? (str/lower-case (str text)) (str/lower-case (str/trim (or q "")))))

(defn- graph-index [runtime]
  (reduce (fn [m {:keys [graph version]}] (update m graph (fnil conj []) version))
          (into {} (for [[qn r] (graph/lifecycle-records (:graph runtime))
                         :when (= :archived (:state r))] [qn []]))
          (graph/shelf-versions (:graph runtime))))

(defn- summary [runtime states graph-id versions records at]
  (let [serving (graph/serving-version (:graph runtime) graph-id)]
    {:graph-id graph-id :state (get-in states [graph-id :state] :active)
     :serving-version serving :latest-version (when (seq versions) (apply max versions))
     :enabled? (instance/graph-enabled? runtime graph-id)
     :version-count (count versions) :token-count (count records)
     :active-token-count (count (filter #(token/active? % at) records))}))

(defn graphs-page [{:keys [runtime]} options]
  (let [index (graph-index runtime) at (instant)
        records (vec (token/all-records (:token runtime)))
        by-graph (group-by :graph-id records)
        states (graph/lifecycle-records (:graph runtime))
        rows (mapv (fn [[id versions]] (summary runtime states id versions (get by-graph id) at)) index)
        group-data (groups/registry runtime)
        group-view (groups/summary group-data (keys index))
        selected-group (get options "group" "all")
        rows (mapv #(assoc % :group-id (get-in group-data [:members (:graph-id %)])) rows)
        counts {:graphs (count rows) :serving (count (filter :enabled? rows))
                :archived (count (filter #(= :archived (:state %)) rows))
                :tokens (count records) :active-tokens (count (filter #(token/active? % at) records))}
        state (get options "state" "all")
        rows (filter #(and (case selected-group "all" true "ungrouped" (nil? (:group-id %)) (= selected-group (:group-id %)))
                           (matches? (get options "q") (:graph-id %))
                           (case state
                             "active" (= :active (:state %))
                             "archived" (= :archived (:state %))
                             "serving" (:enabled? %)
                             "unpublished" (nil? (:serving-version %))
                             "paused" (and (:serving-version %) (not (:enabled? %)))
                             true)) rows)
        sort-key (case (get options "sort") "tokens" :active-token-count "versions" :version-count :graph-id)
        direction (if (= "desc" (get options "direction")) -1 1)
        rows (sort (fn [a b] (let [c (compare (sort-key a) (sort-key b))]
                               (if (zero? c) (compare (:graph-id a) (:graph-id b)) (* direction c)))) rows)]
    (merge (page rows options) group-view {:counts counts})))

(defn version-info [runtime graph-id version]
  (let [{:keys [manifest report]} (graph/shelf-metadata (:graph runtime) graph-id version)]
    {:version version :cut-at (get-in manifest [:provenance :cut-at])
     :cut-by (get-in manifest [:provenance :cut-by]) :content-hash (:content-hash manifest)
     :healthy? (or (= :contract-validated (:validation report)) (true? (get-in manifest [:health :structural-ok?])))
     :finding-count (count (get-in manifest [:health :findings]))}))

(defn- missing [] {:status :error :error "This Graph is no longer available. Return to the Graph list and refresh."
                  :findings [{:code :graph-unknown :severity :error :path [:graph-id]
                              :message "No such Graph on this instance."}]})

(defn graph-detail [{:keys [runtime]} options]
  (let [id (get options "graph-id") index (graph-index runtime)]
    (if-not (contains? index id) (missing)
      (let [records (filter #(= id (:graph-id %)) (token/all-records (:token runtime)))
            row (summary runtime (graph/lifecycle-records (:graph runtime)) id (get index id) records (instant))]
        {:status :ok :graph (assoc row :graph-uuid (when-let [v (or (:serving-version row) (:latest-version row))]
                                                       (graph/graph-uuid (:graph runtime) id v)) :group-id (get-in (groups/registry runtime) [:members id]))
         :latest (when-let [v (:latest-version row)] (version-info runtime id v))
         :serving (when-let [v (:serving-version row)] (version-info runtime id v))}))))

(defn versions-page [{:keys [runtime]} options]
  (let [id (get options "graph-id") index (graph-index runtime)]
    (if-not (contains? index id) (missing)
      (let [serving (graph/serving-version (:graph runtime) id)
            versions (sort > (filter #(matches? (get options "q") %) (get index id)))
            result (page versions options)]
        (assoc result :serving-version serving
               :items (mapv #(assoc (version-info runtime id %) :serving? (= serving %)) (:items result)))))))

(defn tokens-page [{:keys [runtime]} options]
  (let [at (instant) id (get options "graph-id") state (get options "state" "all")
        selected-group (get options "group" "all") membership (:members (groups/registry runtime))
        records (->> (token/all-records (:token runtime))
                     (map #(assoc % :group-id (get membership (:graph-id %))))
                     (filter #(case selected-group "all" true "ungrouped" (nil? (:group-id %)) (= selected-group (:group-id %))))
                     (filter #(or (str/blank? id) (= id (:graph-id %))))
                     (map #(assoc % :display-status (if (and (= :active (:status %)) (not (token/active? % at))) :expired (:status %))))
                     (filter #(and (matches? (get options "q") (str (:graph-id %) " " (:token-id %)))
                                   (or (= "all" state) (= state (name (:display-status %))))))
                     ;; Tie-break by ID so equal timestamps never skip/repeat rows.
                     (sort-by (juxt :created-at :token-id) #(compare %2 %1)))]
    (update (page records options) :items
            #(mapv (fn [r] (select-keys r [:token-id :graph-id :group-id :version :status :display-status :created-at :expires-at])) %))))

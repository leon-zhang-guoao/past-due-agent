(ns open.atlas.studio.ui.contract-blueprint
  "Read-only Blueprint projection of the saved Contract. No legacy pool writes."
  (:require [clojure.string :as str]))

(defn asset-name [x] (if (keyword? x) (subs (str x) 1) (str x)))

(defn output-attributes [output]
  (mapcat (fn [x]
            (cond (keyword? x) [x]
                  (map? x) (mapcat (fn [[k children]] (cons k (output-attributes children))) x)
                  :else [])) output))

(defn- output-hops [entry output resolver]
  (mapcat (fn [x]
            (cond
              (keyword? x) (when (and entry (not= entry (asset-name x)))
                             [{:from entry :to (asset-name x) :resolver resolver}])
              (map? x) (mapcat (fn [[k children]]
                                (concat (output-hops entry [k] resolver)
                                        (map #(assoc % :nested? true)
                                             (output-hops (asset-name k) children resolver)))) x)
              :else [])) output))

(defn document->blueprint [document]
  (let [entities (get-in document [:vocabulary :entities] {})
        attrs (get-in document [:vocabulary :attributes] {})
        resolvers (get-in document [:execution :resolvers] {})
        selected (get-in document [:execution :selection] {})
        bindings (get-in document [:execution :bindings] {})
        rows (mapv (fn [[id definition]]
                     (let [binding-id (get selected [:resolver id])
                           binding (get bindings binding-id)]
                       {:id id :definition definition :binding-id binding-id :binding binding
                        :input (:input definition) :parameters (:params definition) :output (:output definition)}))
                   (sort-by key resolvers))
        ;; Input-v2 descriptors retain their metadata in resolver rows; topology uses attribute identities.
        graph-rows (mapv #(update % :input (fn [xs] (mapv (fn [x] (if (map? x) (or (:attribute x) (ffirst x)) x)) xs))) rows)
        input-node (fn [input]
                     (case (count input)
                       0 nil
                       1 (asset-name (first input))
                       (str "#{" (str/join " " (sort (map asset-name input))) "}")))
        combinations (into {} (for [{:keys [input]} graph-rows :when (> (count input) 1)]
                                [(input-node input) input]))
        hops (mapcat (fn [{:keys [id input output]}]
                       (concat (when (> (count input) 1)
                                 (for [a input] {:from (asset-name a) :to (input-node input) :resolver id}))
                               (output-hops (input-node input)
                                            (remove (set input) output) id))) graph-rows)
        edges (->> hops (group-by (juxt :from :to))
                   (map (fn [[[from to] hs]]
                          {:from from :to to :resolvers (vec (sort (set (map :resolver hs))))
                           :nested? (boolean (some :nested? hs))}))
                   (sort-by (juxt :from :to)) vec)
        produced (frequencies (mapcat #(output-attributes (:output %)) rows))
        all-attrs (set (concat (keys attrs) (mapcat :input graph-rows)
                              (mapcat #(output-attributes (:output %)) rows)))
        nodes (vec (concat
                    (for [a (sort all-attrs)]
                      {:attribute (asset-name a) :weight (get produced a 0)
                       :reach (count (filter #(= (asset-name a) (:from %)) edges))
                       :external? (not (contains? attrs a))})
                    (for [[id _] (sort-by key combinations)]
                      {:attribute id :combination? true :weight 1 :reach 1})))]
    {:entities (mapv (fn [[id d]] {:id id :label (:entity/label d) :definition d})
                     (sort-by key entities))
     :attributes (mapv (fn [[id d]] {:id (asset-name id) :contract-id id
                                     :label (:attribute/label d) :definition d})
                       (sort-by key attrs))
     :aliases (mapv (fn [index definition]
                      {:id (str (asset-name (:alias/from definition)) " → " (asset-name (:alias/to definition)))
                       :contract-index index :definition definition
                       :label (:alias/description definition)})
                    (range) (get-in document [:vocabulary :aliases] []))
     :equivalences (mapv (fn [index definition]
                           {:id (str/join " ↔ " (map asset-name (:equivalence/attributes definition)))
                            :contract-index index :definition definition
                            :label (:equivalence/description definition)})
                         (range) (get-in document [:vocabulary :equivalences] []))
     :resolvers rows
     :mutations (mapv (fn [[id definition]]
                        (let [binding-id (get selected [:operation id])]
                          {:id id :definition definition :binding-id binding-id
                           :binding (get bindings binding-id)}))
                      (sort-by key (get-in document [:execution :operations] {})))
     :graph {:nodes nodes :edges edges}}))

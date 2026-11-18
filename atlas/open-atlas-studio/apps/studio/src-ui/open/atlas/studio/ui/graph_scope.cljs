(ns open.atlas.studio.ui.graph-scope)

(defn neighborhood
  "One hop around selected attributes, preserving compound-input members.
   Unknown selections yield no nodes rather than an unrelated full model."
  [{:keys [nodes edges]} seeds]
  (let [present (set (map :attribute nodes))
        roots (set (filter present seeds))
        adjacent (filterv #(or (roots (:from %)) (roots (:to %))) edges)
        visible (into roots (mapcat (juxt :from :to)) adjacent)
        combinations (set (map :attribute (filter #(and (:combination? %) (visible (:attribute %))) nodes)))
        members (filter #(combinations (:to %)) edges)
        visible (into visible (map :from) members)]
    {:nodes (filterv #(visible (:attribute %)) nodes)
     :edges (filterv #(and (visible (:from %)) (visible (:to %))) edges)}))

(defn filter-edges [graph kind]
  (update graph :edges #(filterv (fn [edge]
                                   (case kind
                                     :direct (not (:nested? edge))
                                     :nested (boolean (:nested? edge))
                                     true)) %)))

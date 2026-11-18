(ns open.atlas.studio.pathom.index-graph
  "The index graph: the whole model as one blueprint.

   The shape follows Pathom Viz's index explorer, because it is the right shape:
   nodes are attributes, not resolvers, and an edge from A to B means some
   resolver takes A and produces B. Resolvers live on the edges — one edge can
   carry several — which keeps the picture about the data model rather than about
   the plumbing.

   Each node carries two independent facts — weight (how many resolvers produce
   this attribute) and reach (how many attributes it leads to) — which the
   renderer maps to node size and ring thickness respectively."
  (:require [clojure.string :as str]
            [open.atlas.studio.config.normalize :as normalize]))

(def dto-version 1)

(def default-limit
  "Nodes returned before truncation. The CIS corpus measured 8,285 attributes; a
   browser can lay out a few hundred usefully, and beyond that the picture stops
   being a blueprint and starts being a texture."
  400)

(defn combination-id
  "The node id for a multi-attribute input.

   Pathom Viz gives an input combination a node of its own — its `multiNode` —
   rather than drawing every member separately into every output. That node is
   the resolver made visible: this set of attributes, together, is what opens the
   door."
  [input]
  (str "#{" (str/join " " (sort (map normalize/keyword->attr input))) "}"))

(defn- produced-attrs
  "Every attribute a resolver places in a result, paired with whether it arrives
   nested under a join."
  [{:keys [output-attrs join]}]
  (concat (map (fn [a] [a false]) (remove (set (:children join)) output-attrs))
          (when join
            (cons [(:key join) false]
                  (map (fn [a] [a true]) (:children join))))))

(defn- entry-node
  "Where a resolver's hops start: its single input, its input combination, or —
   for a global resolver — nothing at all."
  [input]
  (cond
    (> (count input) 1) (combination-id input)
    (seq input)         (normalize/keyword->attr (first input))))

(defn hops
  "The attribute-to-attribute hops one resolver makes.

   Three shapes, and the difference between them is the difference between a
   blueprint and a hairball:

   - **Several inputs are one door.** They collapse into a single combination
     node that the members point at, so a two-key resolver draws 2 + n edges
     instead of 2n.
   - **A join routes through its key.** `sso/order_no` gets you
     `sso/order_detail`, and the line items hang off *that* — not off
     `order_no`. Fanning the children straight out of the input loses the entity
     in the middle, which is the thing being modelled.
   - **An echoed input is not a derivation.** `WHERE id = :id ... SELECT id`
     hands the caller their own value back; drawing it invents a relationship,
     and between the parts of a compound key it invents one in each direction —
     2,086 such pairs in the CIS corpus."
  [{:keys [id input output-attrs join] :as resolver}]
  (let [consumed (set input)
        entry    (entry-node input)
        key-attr (some-> join :key normalize/keyword->attr)
        hop      (fn [from to nested?]
                   (when (and from to (not= from to))
                     {:from from :to to :resolver id :nested? nested?}))]
    (keep
     identity
     (concat
      ;; members -> the combination they form
      (when (> (count input) 1)
        (for [a input] (hop (normalize/keyword->attr a) entry false)))
      ;; the resolver's own flat outputs
      (for [[a nested?] (produced-attrs resolver)
            :when       (and (not nested?) (not (consumed a)))]
        (hop entry (normalize/keyword->attr a) false))
      ;; the join's children hang off the join, not off the door
      (for [c (:children join)]
        (hop key-attr (normalize/keyword->attr c) true))))))

(defn edges
  "attribute -> attribute, one entry per distinct pair, carrying every resolver
   that makes that hop."
  [resolvers]
  (->> (mapcat hops resolvers)
       (group-by (juxt :from :to))
       (map (fn [[[from to] entries]]
              (let [ids (vec (sort (distinct (map :resolver entries))))]
                {:from      from
                 :to        to
                 :resolvers ids
                 :nested?   (boolean (some :nested? entries))
                 :weight    (count ids)})))
       (sort-by (juxt :from :to))
       vec))

(defn- producers-by-attr
  "attribute -> the resolvers that place it in a result."
  [resolvers]
  (reduce (fn [acc {:keys [id] :as resolver}]
            (reduce (fn [m [attr _]] (update m attr (fnil conj []) id))
                    acc (produced-attrs resolver)))
          {} resolvers))

(defn- combinations
  "One node per multi-attribute input, described by its members."
  [resolvers]
  (into {}
        (for [{:keys [input]} resolvers
              :when (> (count input) 1)]
          [(combination-id input) (mapv normalize/keyword->attr (sort input))])))

(defn nodes
  "Every attribute in the model, with its weight and reach, plus a node for each
   input combination."
  [resolvers edges' declared-attrs]
  (let [producers  (producers-by-attr resolvers)
        produced   (frequencies (mapcat (comp (partial map first) produced-attrs) resolvers))
        consumed   (into #{} (mapcat :input) resolvers)
        ;; An ownership view must retain declared fields even when no resolver
        ;; happens to produce or consume them.
        all-attrs  (into (into (set (keys produced)) consumed) declared-attrs)
        combos     (combinations resolvers)
        reach      (frequencies (map :from edges'))
        combo-nodes
        (for [[id members] combos]
          ;; A combination produces nothing itself — it is a door, not a fact —
          ;; so it carries no namespace colour and the renderer draws it apart.
          {:attribute id :entity nil :weight 0 :reach (get reach id 0)
           :producers [] :members members :combination? true :input-only? true})]
    (->> all-attrs
         (map (fn [attr]
                (let [attr-str (normalize/keyword->attr attr)
                      weight   (get produced attr 0)
                      reach'   (get reach attr-str 0)]
                  ;; weight and reach stay separate: they are two different facts
                  ;; and the renderer shows them on two different channels (node
                  ;; size and ring thickness), exactly as Pathom Viz does. Merging
                  ;; them into one number here would throw that away.
                  {:attribute attr-str
                   :entity    (namespace attr)
                   :weight    weight
                   :reach     reach'
                   :producers (vec (sort (get producers attr)))
                   ;; An attribute nothing produces is an input the model expects
                   ;; from the caller — worth seeing at a glance.
                   :input-only? (zero? weight)})))
         (concat combo-nodes)
         (sort-by :attribute)
         vec)))

(defn neighborhood
  "The attributes within `depth` hops of `focus`, in either direction.

   At 3,000 attributes the whole blueprint is a texture, not a picture. What a
   modeller actually asks is 'what surrounds this one', so the graph's default is
   a neighbourhood and the whole model is the exception."
  [edges' focus depth]
  (let [adjacent (reduce (fn [acc {:keys [from to]}]
                           (-> acc
                               (update from (fnil conj #{}) to)
                               (update to (fnil conj #{}) from)))
                         {} edges')]
    (loop [seen #{focus} frontier #{focus} remaining (max depth 0)]
      (if (or (zero? remaining) (empty? frontier))
        seen
        (let [next-frontier (into #{} (comp (mapcat adjacent) (remove seen)) frontier)]
          (recur (into seen next-frontier) next-frontier (dec remaining)))))))

(defn- matches? [q {:keys [attribute entity]}]
  (let [q (str/lower-case q)]
    (or (str/includes? (str/lower-case attribute) q)
        (str/includes? (str/lower-case (str entity)) q))))

(defn build
  "The whole index as one graph, optionally filtered and always bounded.

   `q` keeps only attributes whose name or entity matches, plus the edges between
   them — a namespace at a time is how a large model is actually read. When the
   node budget bites, the heaviest nodes survive and `:truncated?` says so."
  ([config-set] (build config-set {}))
  ([{:keys [resolvers attributes]} {:keys [q focus depth limit] :or {limit default-limit depth 1}}]
   (let [all-edges (edges resolvers)
         all-nodes (nodes resolvers all-edges attributes)
         near      (when-not (str/blank? focus) (neighborhood all-edges focus depth))
         filtered  (cond->> all-nodes
                     near                 (filterv #(near (:attribute %)))
                     (not (str/blank? q)) (filterv #(matches? q %)))
         kept      (->> filtered
                        (sort-by (juxt (comp - :weight) (comp - :reach) :attribute))
                        (take limit)
                        (sort-by :attribute)
                        vec)
         kept-set  (into #{} (map :attribute) kept)
         kept-edges (filterv #(and (kept-set (:from %)) (kept-set (:to %))) all-edges)]
     {:dto-version dto-version
      :nodes       kept
      :edges       kept-edges
      :focus       (when-not (str/blank? focus) focus)
      :depth       (when near depth)
      :counts      {:attributes (count all-nodes)
                    :edges      (count all-edges)
                    :shown      (count kept)}
      :truncated?  (> (count filtered) (count kept))})))

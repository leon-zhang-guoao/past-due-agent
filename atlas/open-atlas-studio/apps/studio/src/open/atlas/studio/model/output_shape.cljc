(ns open.atlas.studio.model.output-shape
  "A resolver's `:output` as a tree, and the collection shapes a model declares.

   **One derivation, two consumers.** The Builder's catalog needs to say what a
   collection contains, and the resolver form needs to edit it; writing that
   twice is how the two would eventually disagree about the same `:output`.
   So this is `.cljc`: the server derives the catalog from it and the browser
   edits through it.

   ## Two facts that were being mixed

   An attribute definition (type or `:target`, label) and a resolver's
   output shape (which collection, which members, produced by whom) are
   different facts about different things. The catalog used to merge a
   synthesised `{:children …}` pseudo-definition into the attribute map, which
   made the shape look like a property of the attribute — and quietly stopped
   working the day collections became registered attributes, because the real
   definition won the merge. Shapes live here, on their own, keyed by producer.

   ## The tree

   `:output` is a vector whose entries are either an attribute keyword or a
   single-entry map `{parent [child …]}` (D2.16). As a tree:

       {:attribute kw :bind \"col\" :members nil}   ; a leaf
       {:attribute kw :bind nil    :members [leaf…]} ; a collection

   `:bind` is the result column the attribute reads, carried from
   `:source :field_mapping`. **A collection never carries one**: the columns in
   a SQL result are the members, and the collection is the join root that holds
   them. Identity bindings are absent, not written — local name = column is the
   default (D2.1)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn- join-entry
  "`{parent [child …]}` → `[parent children]`, or nil for a plain attribute."
  [entry]
  (when (map? entry)
    (let [[parent children] (first entry)]
      [parent (vec children)])))

(defn collection-root?
  "Whether this `:output` entry is a collection join rather than a plain
   attribute."
  [entry]
  (some? (join-entry entry)))

(defn entry->tree
  "A resolver entry → its output tree, with each bindable attribute carrying
   the column it reads from `:field_mapping`."
  [entry]
  (let [mapping (get-in entry [:source :field_mapping])
        leaf    (fn [a] {:attribute a :bind (get mapping a) :members nil})]
    (vec (for [o (:output entry)]
           (if-let [[parent children] (join-entry o)]
             {:attribute parent :bind nil :members (mapv leaf children)}
             (leaf o))))))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn- identity-binding?
  "A binding equal to the attribute's local name says nothing: it is the
   default, and a written default is not canonical form (D2.1)."
  [{:keys [attribute bind]}]
  (or (nil? bind)
      (str/blank? bind)
      (= bind (name attribute))))

(defn- node->output [{:keys [attribute members]}]
  (if members
    {attribute (mapv :attribute members)}
    attribute))

(defn- tree-bindings
  "Every non-identity binding in the tree, as `{attribute column}`.

   Collections are skipped even if a caller handed one a binding — the rule
   that a collection has no column is enforced here rather than trusted of the
   UI, so no edit path can smuggle one in."
  [tree]
  (into {}
        (for [node tree
              n    (if (:members node) (:members node) [node])
              :when (not (identity-binding? n))]
          [(:attribute n) (:bind n)])))

(defn tree->entry
  "Write a tree back into a resolver entry: `:output` and
   `:source :field_mapping` together.

   Both are rewritten from the whole tree, so a removed attribute's mapping
   goes with it and an edit to a plain output cannot strand a member's column.
   An empty mapping is removed rather than written empty."
  [entry tree]
  (let [mapping (tree-bindings tree)]
    (-> entry
        (assoc :output (mapv node->output tree))
        (update :source (fn [source]
                          (if (seq mapping)
                            (assoc (or source {}) :field_mapping mapping)
                            (dissoc source :field_mapping)))))))

;; ---------------------------------------------------------------------------
;; Collection shapes across a model
;; ---------------------------------------------------------------------------

(defn collection-shapes
  "Every collection output shape a model's resolvers declare — one entry per
   (collection, producer) pair.

   **Never unioned.** Two producers of the same collection with different
   members is a fact a reader has to see; merging them would invent a third
   member list that no resolver actually declares, which is exactly the second
   semantics this namespace exists to remove."
  [model]
  (vec (for [r (:resolvers model)
             o (:output r)
             :let [j (join-entry o)]
             :when j]
         {:collection (first j)
          :members (second j)
          :producer (str (:id r))})))

(defn shapes-by-collection
  "Shapes grouped for display: collection → producers, and whether they agree.

   `:consistent?` false is a shape conflict — the same collection produced with
   different members by different resolvers. It is reported, never resolved
   here: which one is right is a modelling question."
  [shapes]
  (into {}
        (for [[collection group] (group-by :collection shapes)]
          [collection
           {:producers (vec (sort-by :producer group))
            :consistent? (= 1 (count (distinct (map :members group))))}])))

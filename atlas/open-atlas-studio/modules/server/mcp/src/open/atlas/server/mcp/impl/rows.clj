(ns open.atlas.server.mcp.impl.rows
  "An EQL result tree → flat rows, with SQL LEFT-JOIN semantics.

   Migrated **verbatim** from `ent.dx.admin.ui.result-view` (the `row-axis` /
   `join-rows` pair). Pure, no I/O. It is carried over rather than rewritten
   because its edge cases are paid-for knowledge: the empty-collection rules
   below each record a specific failure that reached production once."
  (:require [clojure.string :as str]))

(defn row-axis
  "Decide what a result becomes:
     {:rows [maps…]}  a vector of maps, or a single-key collection wrapper
                      {k [maps…]} unwrapped to its vector;
     {:kv  map}       a single entity map;
     {:scalar v}      anything else.

   A single-key wrapper {k []} — an EMPTY collection — unwraps to {:rows []},
   NOT {:kv {k []}}: it is definitionally zero rows (a query that asked for a
   collection and got none back), not a one-field record whose one field
   happens to hold an empty list. `(every? map? v)` is vacuously true on an
   empty v regardless of what element shape it WOULD have carried, so
   `(or (empty? v) (every? map? v))` only WIDENS the empty case — a non-empty
   scalar collection {k [1 2 3]} still falls through to :kv, unchanged.

   Misclassifying the empty case as :kv used to reach the analyze step as ONE
   row shaped {} (join-rows' left-join keeps the parent row when a child
   collection is empty), which DuckDB then rejected outright ('Table must have
   at least one column!') instead of the intended zero-row aggregation — a
   legitimately empty window read as a crash."
  [result]
  (cond
    (and (sequential? result) (seq result) (every? map? result))
    {:rows (vec result)}

    (and (map? result) (= 1 (count result))
         (let [v (val (first result))]
           (and (sequential? v) (or (empty? v) (every? map? v)))))
    {:rows (vec (val (first result)))}

    ;; Independent ident joins are separate records, NOT sibling 1:1 joins.
    ;; Carry their input anchor for grouping; it does not prove detail success.
    (and (map? result) (seq result)
         (every? #(and (vector? %) (= 2 (count %)) (qualified-keyword? (first %))) (keys result))
         (every? map? (vals result)))
    {:rows (mapv (fn [[[attr id] value]] (merge {attr id} value)) result)}

    ;; Mixed ident/attribute roots have no single analysis grain. Do not merge
    ;; independent entities into one record (and silently overwrite columns).
    (and (map? result) (some vector? (keys result))) {:scalar result}
    (map? result) {:kv result}
    :else         {:scalar result}))

(defn join-rows
  "Denormalize ONE record into flat row maps — SQL LEFT-JOIN semantics:
   - scalar attrs stay as columns;
   - a 1:1 nested map merges its (recursively joined) fields into the row;
   - a nested collection of maps joins: one output row per child, parent columns
     repeated (recursively — grand-children multiply the same way); an EMPTY
     collection keeps the parent row alone (left join, not inner);
   - several sibling collections form their cartesian product (exactly what a
     SQL multi-join would return);
   - a collection of scalars stays as one EDN-text cell.
   Namespaced keys keep parent/child columns distinct."
  [m]
  (reduce-kv
   (fn [rows k v]
     (cond
       (and (sequential? v) (seq v) (every? map? v))
       (let [children (mapcat join-rows v)]
         (vec (for [r rows c children] (merge r c))))

       (map? v)
       (vec (for [r rows c (join-rows v)] (merge r c)))

       ;; empty collection → LEFT JOIN keeps the parent row, no column emitted
       (and (sequential? v) (empty? v))
       rows

       :else
       (mapv #(assoc % k v) rows)))
   [{}]
   m))

(defn bounded-flatten-result
  "Lazily expand at most budget+1 rows so callers can refuse excess without
   materializing sibling collections' entire Cartesian product.

   `on-row` (optional) is called once per materialized row and may throw to
   abort — that is how the end-to-end analysis deadline reaches INSIDE the
   flatten stage. A budget that is only checked after flattening is not a
   deadline for the flattening, which on a wide Cartesian product is exactly
   where the time goes. This namespace stays pure of the budget's shape: it
   takes a function, not a deadline."
  ([result budget] (bounded-flatten-result result budget nil))
  ([result budget on-row]
  (letfn [(expand [m]
            (reduce-kv
             (fn [rs k v]
               (cond
                 (and (sequential? v) (seq v) (every? map? v))
                 (for [r rs c (mapcat expand v)] (merge r c))
                 (map? v) (for [r rs c (expand v)] (merge r c))
                 (and (sequential? v) (empty? v)) rs
                 :else (map #(assoc % k v) rs)))
             [{}] m))]
    (let [{:keys [rows kv]} (row-axis result)]
      (when (or rows kv)
        (into [] (comp (take (inc budget))
                       (map (fn [row] (when on-row (on-row)) row)))
              (mapcat expand (or rows [kv]))))))))

(defn flatten-result
  "An EQL result → the flat rows a SQL table is built from, or nil when the
   result has no tabular shape at all (a bare scalar)."
  [result]
  (let [{:keys [rows kv]} (row-axis result)]
    (cond
      rows (vec (mapcat join-rows rows))
      kv   (join-rows kv)
      :else nil)))

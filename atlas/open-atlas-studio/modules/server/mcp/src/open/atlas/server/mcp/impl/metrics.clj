(ns open.atlas.server.mcp.impl.metrics
  "The declared-metric registry and the query one is computed from.

   A metric is the seventh concept (D2.21): it fixes a 口径 — the definition a
   number is computed by — so that a question matching a declared metric is
   answered from the DECLARED expression and never from a formula the agent
   improvised. That is the entire point: two people asking 'monthly sales' must
   get the same number, and the way to guarantee that is to make the definition
   a registered object rather than a prompt.

   Execution follows
   [metric-expression-duckdb-spill-v1](docs/contracts/metric-expression-duckdb-spill-v1.md)
   literally: materialise the `:entity`'s rows as a single table named `data`
   whose column names are attribute LOCAL names, then run
   `SELECT <dims>, <text> FROM data [GROUP BY <dims>]`."
  (:require [clojure.string :as str]))

(defn registry
  "The declared metrics of the pinned model, as rows an agent can choose from."
  [model]
  (vec (for [[id m] (sort-by key (:metrics model))]
         (cond-> {:metric id
                  :label (or (:label m) id)
                  :description (or (:description m) "")
                  :entity (:entity m)}
           (:grain m)      (assoc :grain (name (:grain m)))
           (seq (:dimensions m))
           (assoc :dimensions (mapv #(str (namespace %) "/" (name %)) (:dimensions m)))
           (seq (:synonyms m)) (assoc :synonyms (vec (:synonyms m)))))))

(defn find-metric
  "Match `s` against metric ids then synonyms, case-insensitively.
   Migrated from the legacy `find-metric`: an agent naming a 口径 the way a
   human said it must still land on the declared one, or it will reach for
   `analyze_result` and improvise the formula the metric exists to prevent."
  [model s]
  (let [q (str/lower-case (str/trim (str s)))]
    (some (fn [[id m]]
            (when (or (= q (str/lower-case id))
                      (some #(= q (str/lower-case (str %))) (:synonyms m)))
              [id m]))
          (:metrics model))))

(defn collection-of
  "The global collection join for an entity: the one rooted in the entity's own
   namespace whose target is that same entity. That is what a metric aggregates
   over — a metric has no anchor, so there must be a way to ask for *all* of
   them.

   D2.29: the catalog answers these from `:joins` (one attribute registry),
   where it used to answer from `:navigations`."
  [catalog entity]
  (first (for [[join target] (:joins catalog)
               :when (and (= entity (namespace join)) (= entity target))]
           join)))

(defn- local-names
  "The local names an expression mentions. Used to fetch exactly the columns
   `:text` needs rather than the whole entity — the contract restricts a
   reference to the entity's registered attributes and the declared
   dimensions, so anything else in the text is not a column and is ignored
   here (the health check is where an unknown name is refused)."
  [text]
  (set (re-seq #"[A-Za-z_][A-Za-z0-9_]*" (str text))))

(defn query-for
  "The EQL a metric's rows come from: its collection, carrying the dimensions
   plus whichever of the entity's attributes the expression names.

   Returns `{:query … :columns [dim-kw …]}` or `{:error …}`."
  [catalog {:keys [entity dimensions expression]}]
  (if-let [nav (collection-of catalog entity)]
    (let [wanted (local-names (:text expression))
          attrs  (vec (for [[a d] (:attributes catalog)
                            :when (and (= entity (:entity d))
                                       (wanted (name a)))]
                        a))
          dims   (vec dimensions)]
      (if (and (empty? attrs) (empty? dims))
        {:error (str "the expression names no attribute of " entity
                     " — a metric must aggregate over something")}
        {:query [{nav (vec (distinct (concat dims attrs)))}]
         :columns dims}))
    {:error (str "entity " (pr-str entity) " has no global collection in this graph, "
                 "so there is nothing for the metric to aggregate over")}))

(defn sql-for
  "`SELECT <dims>, <text> AS value FROM data [GROUP BY <dims>]` — the shape
   metric-expression-duckdb-spill-v1 §1 specifies. Grouping columns may only
   come from `:dimensions` (§4); `:grain` describes time-grain semantics and
   deliberately does NOT rewrite the expression, so a caller reading the number
   is reading the 口径 as written."
  [{:keys [expression]} dims group-by*]
  (let [cols (mapv name (filter (set dims) group-by*))
        sel  (str/join ", " (concat (map #(str "\"" % "\"") cols)
                                    [(str (:text expression) " AS value")]))]
    (str "SELECT " sel " FROM data"
         (when (seq cols)
           (str " GROUP BY " (str/join ", " (map #(str "\"" % "\"") cols))
                " ORDER BY " (str/join ", " (map #(str "\"" % "\"") cols)))))))

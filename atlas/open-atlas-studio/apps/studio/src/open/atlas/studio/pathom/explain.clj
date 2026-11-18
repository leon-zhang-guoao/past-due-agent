(ns open.atlas.studio.pathom.explain
  "The plan DTO, for both Explain and Run (ADR-015 §5, ADR-015-A).

   Explain computes the run graph from the index and the seed entity's shape and
   invokes no connector — it is safe against a production source, which is why it
   is a separate action from Run. Run produces the same DTO from Pathom's run
   stats, plus a per-step outcome, so one graph shows both what would happen and
   what did."
  (:require [clojure.string :as str]
            [com.wsscode.pathom3.connect.planner :as pcp]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.format.shape-descriptor :as pfsd]
            [edn-query-language.core :as eql]
            [open.atlas.studio.config.normalize :as normalize]))

(def dto-version 1)

(def ^:private resolver-prefix
  ;; `open.atlas.common.engine.impl.compile/compile-tier*` names every
  ;; compiled resolver `(symbol "open.atlas.common.engine.generated" id)`
  ;; (doc 89 Phase 2 — this was the legacy Studio compiler's own resolver
  ;; namespace before the switch).
  "open.atlas.common.engine.generated/")

(defn- resolver-id
  "Pathom op-names are internal; the DTO exposes the config's resolver id."
  [op-name]
  (let [s (str op-name)]
    (if (.startsWith s resolver-prefix)
      (subs s (count resolver-prefix))
      s)))

(defn- round2 [ms] (/ (Math/round (* 100.0 (double ms))) 100.0))

(defn- shape->attrs [shape]
  (mapv normalize/keyword->attr (sort (keys (or shape {})))))

(defn- step-dto
  "One plan step. A step without a resolver is a branch node — Pathom's AND/OR
   grouping — and says so, rather than appearing as a nameless row."
  [{::pcp/keys [node-id expects input run-next node-parents] :as node}]
  (let [op-name (:com.wsscode.pathom3.connect.operation/op-name node)]
    {:step        node-id
     :kind        (case (pcp/node-kind node)
                    ::pcp/node-resolver :resolver
                    ::pcp/node-and      :and
                    ::pcp/node-or       :or
                    :unknown)
     :resolver    (some-> op-name resolver-id)
     :input       (shape->attrs input)
     :output      (shape->attrs expects)
     :next        run-next
     :branches    (vec (sort (pcp/node-branches node)))
     :depends-on  (vec (sort node-parents))}))

(defn- run-graph
  "Compute the plan. Pathom verifies the plan and throws when an attribute has no
   path; that is exactly what a user asks Explain about, so the graph is recovered
   from the exception (which carries a thunk for it) and reported instead."
  [env entity query]
  (try
    (pcp/compute-run-graph
     (assoc env
            :edn-query-language.ast/node (eql/query->ast query)
            ::pcp/available-data (pfsd/data->shape-descriptor (or entity {}))))
    (catch clojure.lang.ExceptionInfo e
      (let [{::pcp/keys [graph-fn unreachable-paths]} (ex-data e)]
        (if (or graph-fn unreachable-paths)
          (merge (when graph-fn (try (graph-fn) (catch Exception _ nil)))
                 {::pcp/unreachable-paths unreachable-paths})
          (throw e))))))

(defn- node-label
  "pathom-viz labels a resolver node with its operation and a branch node with its
   kind; the DTO carries the same so the client does no naming logic."
  [{:keys [kind resolver step]}]
  (case kind
    :resolver (or resolver (str "step " step))
    :and      "AND"
    :or       "OR"
    (str "step " step)))

(defn- step-status
  "One step's outcome, from Pathom's run stats.

   Values never enter the DTO: `node-resolver-output` holds business data, so the
   DTO carries its attribute count and timing, not the data itself. `base` is the
   run's start, so timings come out as offsets a timeline can draw rather than as
   absolute clock readings."
  [stats base]
  (cond
    (nil? stats) {:status :not-run}

    (::pcr/node-error stats)
    {:status :error
     :error  (let [e (::pcr/node-error stats)]
               (if (instance? Throwable e) (ex-message e) (str e)))}

    :else
    (let [output   (::pcr/node-resolver-output stats)
          start    (::pcr/resolver-run-start-ms stats)
          finish   (::pcr/resolver-run-finish-ms stats)]
      {:status       (if (seq output) :success :empty-output)
       :output-count (count output)
       :start-ms     (when (and start base) (round2 (- start base)))
       :elapsed-ms   (when (and start finish) (round2 (- finish start)))})))

(defn- graph-dto
  "The plan as nodes and edges, in the shape a graph renderer wants.

   Two edge kinds, matching Pathom's two relationships: `:next` is sequencing
   (run this after that) and `:branch` is grouping (this AND/OR node fans out to
   these)."
  [steps root]
  {:nodes (mapv (fn [{:keys [step kind status] :as s}]
                  (cond-> {:id    step
                           :kind  kind
                           :label (node-label s)
                           :root? (= step root)}
                    status (assoc :status status)))
                steps)
   :edges (into []
                (mapcat (fn [{:keys [step next branches]}]
                          (cond-> (mapv (fn [b] {:from step :to b :kind :branch}) branches)
                            next (conj {:from step :to next :kind :next}))))
                steps)})

(defn plan-dto
  "Turn a run graph into the plan DTO. `node-stats` is Pathom's per-node run stats
   when the plan was executed, and nil when it was only planned; `base` is the
   run's start time, for timeline offsets."
  ([graph node-stats] (plan-dto graph node-stats nil))
  ([graph node-stats base]
   (let [nodes (::pcp/nodes graph)
         steps (vec (sort-by :step
                             (map (fn [node]
                                    (let [step (step-dto node)]
                                      (cond-> step
                                        ;; Only a resolver has an outcome. A branch
                                        ;; node produces nothing by definition, so
                                        ;; scoring it by its output painted every
                                        ;; AND as "empty output" — it keeps its own
                                        ;; colour instead.
                                        (and node-stats (= :resolver (:kind step)))
                                        (merge (step-status (get node-stats (:step step)) base)))))
                                  (vals nodes))))]
     {:dto-version dto-version
      :root        (::pcp/root graph)
      :steps       steps
      :graph       (graph-dto steps (::pcp/root graph))
      :attributes  (mapv (fn [[attr node-ids]]
                           {:attribute (normalize/keyword->attr attr)
                            :steps     (vec (sort node-ids))})
                         (sort-by (comp normalize/keyword->attr key) (::pcp/index-attrs graph)))
      :unreachable (mapv normalize/keyword->attr
                         (sort (keys (or (::pcp/unreachable-paths graph) {}))))
      :counts      {:steps (count nodes)}
      :total-ms    (when (and base (seq node-stats))
                     (let [finish (apply max (keep ::pcr/node-run-finish-ms (vals node-stats)))]
                       (/ (Math/round (* 100.0 (- finish base))) 100.0)))})))

;; ---------------------------------------------------------------------------
;; Nested runs
;;
;; Pathom plans and runs each nested entity on its own graph, and attaches that
;; graph's stats to the entity map's metadata. A query four joins deep therefore
;; runs dozens of little graphs, and the root's stats describe only the top
;; level: reading the root alone reported 3 ms and two resolvers for a run that
;; took 29 ms across seven.
;; ---------------------------------------------------------------------------

(def timeline-limit
  "Invocations carried in the timeline. A nested query runs one graph per row, so
   this is the one part of the DTO that grows with the *data* rather than with
   the model — a 2,000-row join would otherwise put 2,000 bars on a chart nobody
   can read."
  300)

(defn- path-segment
  "A join key as text. Not every key on the way down is an attribute — an ident
   entry is a vector — so anything that is not a keyword is printed as itself."
  [k]
  (if (keyword? k) (normalize/keyword->attr k) (pr-str k)))

(defn- run-stats-tree
  "Every run-stats in a result, with the join path it ran under.

   The path is the shape — `[:customer/msos :mso/vpos]` — not the position, so
   the twelve rows of a join collapse into one path that ran twelve times."
  [result]
  (let [out (volatile! [])]
    (letfn [(walk [x path]
              (cond
                (map? x)
                (do (when-let [stats (::pcr/run-stats (meta x))]
                      (vswap! out conj {:path path :stats stats}))
                    (doseq [[k v] x
                            :when (or (map? v) (sequential? v))]
                      (walk v (conj path k))))

                (sequential? x) (doseq [v x] (walk v path))))]
      (walk result [])
      @out)))

(defn- merge-step
  "One step's stats across every row a nested plan ran for.

   Summed, not averaged: what the reader wants from `demo_vpo_order_detail ran 5
   times` is the 5 ms it cost altogether. The start is the earliest, so the step
   still sits where the work began."
  [steps]
  (let [present (filter :start-ms steps)]
    (cond-> (first steps)
      (seq present)
      (assoc :start-ms     (apply min (map :start-ms present))
             :elapsed-ms   (round2 (reduce + (keep :elapsed-ms present)))
             :output-count (reduce + (keep :output-count present))
             :runs         (count present)
             :status       (or (some #(when (= :error (:status %)) :error) present)
                               (some #(when (= :success (:status %)) :success) present)
                               (:status (first present)))))))

(defn- merge-plans
  "Several runs of the same plan, as one plan. Every run has the same steps —
   only the numbers differ."
  [plans]
  (let [by-step (group-by :step (mapcat :steps plans))]
    (assoc (first plans)
           :steps (vec (sort-by :step (map (comp merge-step val) by-step)))
           :runs  (count plans))))

(defn- combined-graph
  "Every level of a nested run as one graph.

   A level's steps keep their own numbering, so the ids are qualified by level —
   `L2#3` — and the levels are stitched together at the join: the parent step
   that *produces* the join attribute gets an edge to the nested plan's root,
   labelled with the join and how many rows it ran for. That edge is the answer to
   'and then what happened', which tabs make you go and look for."
  [levels]
  (let [by-path (into {} (map-indexed (fn [i {:keys [path]}] [path i])) levels)
        qualify (fn [i id] (str "L" i "#" id))]
    {:steps (vec (for [[i {:keys [plan path depth runs]}] (map-indexed vector levels)
                       step (:steps plan)]
                   (assoc step
                          :id    (qualify i (:step step))
                          :level i
                          :depth (or depth 0)
                          :runs  runs
                          :path  path)))
     :graph
     {:nodes (vec (for [[i {:keys [plan path depth runs]}] (map-indexed vector levels)
                        node (:nodes (:graph plan))]
                    (assoc node
                           :id    (qualify i (:id node))
                           :root? (and (zero? i) (:root? node))
                           :level i
                           :depth (or depth 0)
                           :runs  runs
                           :path  path)))
      :edges (vec (concat
                  (for [[i {:keys [plan]}] (map-indexed vector levels)
                        edge (:edges (:graph plan))]
                    (assoc edge :from (qualify i (:from edge))
                                :to   (qualify i (:to edge))))
                  (for [[i {:keys [path plan runs]}] (map-indexed vector levels)
                        :when (seq path)
                        :let  [parent-i (get by-path (vec (butlast path)))
                               parent   (:plan (nth levels parent-i))
                               join     (last path)
                               ;; Whoever put the join attribute in the result is
                               ;; where the nested run hangs; if the plan does not
                               ;; say (a join the caller supplied), the parent's
                               ;; root stands in.
                               from     (or (seq (->> (:attributes parent)
                                                      (filter #(= join (:attribute %)))
                                                      (mapcat :steps)))
                                            [(:root parent)])]
                        step  from]
                    {:from  (qualify parent-i step)
                     :to    (qualify i (:root plan))
                     :kind  :nested
                     :label (str (last (str/split join #"/")) " ×" runs)})))}}))

(defn plan-of-result
  "The plan DTO for a query that has already run, from the whole result tree.

   One graph, all levels. The root plan is grafted to the plan each nested entity
   ran, at the step that produced the join, so the picture reads the way the query
   does: customer, then its orders, then each order's line items. `:nested` keeps
   the levels separable, and `:timeline` carries every invocation on the root's
   clock, so a nested query's N+1 shape shows up as itself rather than as a single
   fast-looking step."
  [result]
  (let [entries (run-stats-tree result)]
    (when-let [root (first (filter (comp empty? :path) entries))]
      (let [base    (::pcr/graph-run-start-ms (:stats root))
            plan-at (fn [{:keys [stats]}]
                      (plan-dto stats (::pcr/node-run-stats stats) base))
            nested  (->> (remove (comp empty? :path) entries)
                         (group-by :path)
                         (sort-by (juxt (comp count key) (comp str key)))
                         (mapv (fn [[path group]]
                                 (let [plans (mapv plan-at group)]
                                   {:path  (mapv path-segment path)
                                    :depth (count path)
                                    :runs  (count plans)
                                    :plan  (merge-plans plans)})))
                         ;; A leaf entity — a line item whose every attribute the
                         ;; parent already returned — runs an empty graph. Pathom
                         ;; records one per row; there were 52 of them here, and
                         ;; not one of them ran anything.
                         (filterv #(some :resolver (:steps (:plan %)))))
            calls   (->> entries
                         (mapcat (fn [{:keys [path] :as entry}]
                                   (for [step (:steps (plan-at entry))
                                         :when (:start-ms step)]
                                     (assoc step
                                            :depth (count path)
                                            :path  (mapv path-segment path)))))
                         (sort-by :start-ms)
                         vec)
            total   (->> (map :stats entries)
                         (mapcat (comp vals ::pcr/node-run-stats))
                         (keep ::pcr/node-run-finish-ms)
                         (reduce max 0))
            combined (combined-graph
                      (into [{:path [] :depth 0 :runs 1 :plan (plan-at root)}] nested))]
        (assoc (plan-at root)
               :nested     nested
               :graph      (:graph combined)
               ;; The table is the graph in rows, so it lists every level too.
               :steps      (:steps combined)
               :timeline   (vec (take timeline-limit calls))
               :timeline-truncated? (> (count calls) timeline-limit)
               :counts     {:steps  (count (::pcp/nodes (:stats root)))
                            :calls  (count calls)
                            :graphs (count entries)}
               :total-ms   (when (pos? total) (round2 (- total base))))))))

(defn- join-children
  "For a join in the query, what the entity behind it will already hold when its
   own plan starts.

   Pathom hands a nested entity whatever the parent resolver put in it, so the
   sub-plan's available data is the join's *declared* children — union over every
   resolver that produces this join key, since more than one may."
  [resolvers join-key]
  (into #{}
        (comp (filter #(= join-key (:key (:join %))))
              (mapcat (comp :children :join)))
        resolvers))

(defn- explain-level
  "Plan one level and, for every join in it, the level beneath — the recursion
   Pathom performs at run time and the planner does not.

   `compute-run-graph` plans exactly one graph: the root. Everything below a join
   is planned when the runner gets there, which is why Explain reported two steps
   for a query that runs ten. Here each join is followed with the shape its parent
   will have filled in, so Explain and Run describe the same work."
  [env resolvers available query path depth]
  (when (and (< depth 6) (seq query))
    (let [graph (run-graph env (zipmap available (repeat nil)) query)
          plan  (plan-dto graph nil)
          joins (keep (fn [x] (when (map? x) (first x))) query)]
      (into [{:path (mapv path-segment path) :depth depth :runs 1 :plan plan}]
            (mapcat (fn [[k sub]]
                      (explain-level env resolvers
                                     (join-children resolvers k)
                                     sub (conj path k) (inc depth))))
            joins))))

(defn explain
  "Plan `query` against `env`, seeded with `entity`'s shape, without running it —
   every level of it, not only the root.

   An unreachable attribute is reported rather than thrown: 'nothing can produce
   this' is the answer the user came for."
  [env resolvers {:keys [entity query]}]
  (let [all    (explain-level env resolvers (set (keys entity)) query [] 0)
        ;; A join whose children the parent already returns plans to nothing —
        ;; the same empty graph the runner produces for a leaf entity, and it has
        ;; no root to graft an edge onto.
        levels (into [(first all)]
                     (filter #(and (:root (:plan %)) (seq (:steps (:plan %)))))
                     (rest all))
        root   (:plan (first levels))]
    (if (< (count levels) 2)
      root
      (let [combined (combined-graph levels)]
        (assoc root
               :graph  (:graph combined)
               :steps  (:steps combined)
               :nested (vec (rest levels))
               :counts (assoc (:counts root) :levels (count levels)))))))

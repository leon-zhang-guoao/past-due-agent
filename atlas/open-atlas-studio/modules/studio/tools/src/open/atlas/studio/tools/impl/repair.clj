(ns open.atlas.studio.tools.impl.repair
  "**A defect carries its own repair** (8.4.21 裁定十五 ①, owner 2026-09-04).

   One function, `repair-for`, and ONE place that infers a repair. Every face
   that shows a finding an agent could act on — `inspect_graph`'s `:compile`,
   `publish_graph`'s check, the Builder-mounted `execute_eql` refusal and
   `query_model` — calls it. The ruling says so in as many words: 一个形状,
   一个产生处. Two inferences of «which link makes this reachable» would drift
   the day one of them learned about a new link shape.

   ## What a `:repair` is

   `{:tool \"add_relation\" :args {…} :then [{:tool \"remove_objects\" :args {…}}]}`
   — a tool call with its arguments PREFILLED, which the agent may send as it
   stands. It is a suggestion and not an action: the plan/apply discipline is
   untouched, so sending it returns a plan and a person still nods (裁定十五
   ⑤).

   `:args` are the DECLARED parameter names (`via`, `name`),
   not this layer's internal ones — the whole value of a prefilled call is that
   it goes back out over the same door it came in through.

   ## Why the message grows a sentence

   Where a link was found, `repair-for` appends one sentence naming that link
   to `:message`. The refusal is produced by the engine, which cannot see the
   pool's links (D1.8: `common` does not depend on `studio`), so the sentence
   that makes the refusal actionable — «a link X ↔ Y already exists» — can only
   be written here. `:code`, `:path` and `:refs` are never touched (裁定十五 ②).

   ## Structural names are defaulted, not asked

   `collection_name` defaults to the child relation's name and the resolver id
   is generated. D2.26「命名是人的活」is about TERMS and concepts; whether a
   collection is called `posts` or `all_posts` is not a modelling judgement,
   and stopping to ask for it is the friction 裁定十五 was raised over."
  (:require [clojure.string :as str]
            [open.atlas.studio.pool :as pool]))

(defn resolver-relation
  "The relation a sql resolver reads, from the FROM clause of the statement it
   already carries — its own statement is the only place that says which table
   it means, so nothing asks the caller again.

   It lives HERE and `impl.intent` calls it: `impl.intent` reaches this ns
   through schema evidence → `query_model` → here, so a require the other way
   round is a load cycle. One reader, one regex (CLAUDE.md「不要各写一份」)."
  [entry]
  (some-> (:query (:source entry))
          (->> (re-find #"(?i)\bFROM\s+([A-Za-z_][A-Za-z0-9_.]*)"))
          second))

(defn context
  "The `ctx-ish` every consumer passes: the pool as a dialect model, the graph
   whose name goes into the prefilled args, and whatever else the caller knows
   (`:entity-namespaces`, for a query that named a starting entity).

   Built HERE and not at each call site so that «which snapshot of the pool did
   the repair read» has one answer. A host with neither `:db` nor `:conn` gets
   a context with no model, and `repair-for` then returns findings untouched."
  ([ctx graph] (context ctx graph nil))
  ([ctx graph extra]
   (let [db (or (:db ctx) (some-> (:conn ctx) pool/db))]
     (merge {:graph graph :model (some-> db pool/as-dialect)} extra))))

;; ---------------------------------------------------------------------------
;; Facts read off the model
;; ---------------------------------------------------------------------------

(defn- attr-ns [akw] (some-> akw namespace))

(defn- identity-attr?
  "Is this attribute its entity's identity key (D2.29: only a scalar attribute
   carries `:identity?`)?"
  [model akw]
  (boolean (:identity? (get-in model [:attributes akw]))))

(defn- entity-of-resolver
  "The namespace a resolver's `:output` produces, or nil when it straddles
   several (then no single child entity is being collected and the machine has
   nothing to infer from)."
  [entry]
  (let [nss (into #{} (keep attr-ns) (filter keyword? (tree-seq coll? seq (:output entry))))]
    (when (= 1 (count nss)) (first nss))))

(defn- link-between
  "The link that makes `child-ns` reachable from one of `parent-nss`, as
   `{:parent <identity attr> :fk <child attr>}`, or nil.

   The test is the ruling's: one end is the identity key of an entity and the
   other end is a column of the child entity. `parent-nss` nil means «any
   entity» — that is `query_model`'s question, where no query named a starting
   point.

   Deterministic: the candidates are ordered by their printed endpoint pair, so
   two calls on the same pool answer with the same link."
  [model child-ns parent-nss]
  ;; **A compound-key parent is skipped, not half-suggested** (owner
  ;; 2026-09-07, doc 106 §106.14 item 14: links between compound keys are not
  ;; expressed this round). One link names ONE attribute pair, so a parent
  ;; recognised by two attributes together would be reached by half its key —
  ;; an under-constrained join that answers the wrong parent's rows. Saying
  ;; nothing is the honest answer; suggesting it is a wrong repair.
  (let [compound? (->> (:attributes model)
                       (filter (fn [[k d]] (and (:identity? d) (not (:target d)) (namespace k))))
                       (group-by (comp namespace key))
                       (into #{} (comp (filter #(> (count (val %)) 1)) (map key))))]
   (->> (:links model)
       (keep (fn [{:keys [from to]}]
               (let [pair (fn [p c]
                            (when (and (identity-attr? model p)
                                       (= child-ns (attr-ns c))
                                       (not= child-ns (attr-ns p))
                                       (not (contains? compound? (attr-ns p)))
                                       (or (nil? parent-nss)
                                           (contains? parent-nss (attr-ns p))))
                              {:parent p :fk c}))]
                 (or (pair from to) (pair to from)))))
       (sort-by (fn [{:keys [parent fk]}] (str (symbol parent) "|" (symbol fk))))
       first)))

(defn- entity-name
  "The last segment of an entity's namespace — the word a person uses (`posts`
   for `cis.posts`). Model names are allowed in a message; values never are."
  [ns-str]
  (last (str/split (str ns-str) #"\.")))

(defn- link-sentence [{:keys [parent fk]}]
  (str " A link " (symbol parent) " <-> " (symbol fk)
       " exists; one child collection makes it reachable (see :repair)."))

;; ---------------------------------------------------------------------------
;; The three repairs
;; ---------------------------------------------------------------------------

(defn- add-relation-args
  "The prefilled `add_relation` call for «hang this child entity under its
   parent» — the shape 裁定十三 added, 裁定十五 fills in for the agent, and
   8.4.25 裁定十七 renamed: the sentence is «every parent has many children»,
   and the tool that says it is `add_relation` (need-grammar-v1 B1)."
  [graph child-ns relation {:keys [parent fk]}]
  (cond-> {:parent (clojure.core/namespace parent)
           :child child-ns
           :kind "many"
           :via (name fk)
           ;; Defaulted, not asked (see ns docstring).
           :name relation}
    ;; The table is named only when it is not what the child thing is called;
    ;; `add_relation` derives it otherwise.
    (not= relation (last (str/split (str child-ns) #"\."))) (assoc :from relation)
    graph (assoc :graph graph)))

(defn- repair-list-without-anchor
  [{:keys [model graph]} finding]
  (let [rid   (second (:path finding))
        entry (some #(when (= rid (:id %)) %) (:resolvers model))
        child (entity-of-resolver entry)
        ;; The relation the broken resolver already reads: its own FROM clause
        ;; is the only statement of which table it means (same reason
        ;; `add_attribute` reads it rather than asking again).
        rel   (or (resolver-relation entry) (entity-name child))
        link  (when child (link-between model child nil))]
    (when link
      {:finding (update finding :message str (link-sentence link))
       :repair {:tool "add_relation"
                :args (add-relation-args graph child rel link)
                ;; The anchorless one is REPLACED, not kept beside its
                ;; replacement: leaving it would leave the same rows behind a
                ;; resolver that compiles to nothing.
                :then [(cond-> {:tool "remove_objects" :args {:objects [rid]}}
                         graph (update :args assoc :graph graph))]}})))

(defn- repair-mixed-output
  "The delta the projection already computed (8.4.14 put it in `:expected`),
   turned into the call that sends it."
  [{:keys [graph]} finding]
  (when-let [attrs (seq (get-in finding [:expected :recipe-delta :add :attributes]))]
    {:finding finding
     :repair {:tool "change_graph_members"
              :args (cond-> {:add (mapv #(if (keyword? %) (str (symbol %)) (str %)) attrs)}
                      graph (assoc :graph graph))}}))

(defn- repair-unreachable
  "`:engine.request/unreachable` — the attributes a query asked for and no
   path reaches. With a link, the same child-collection repair; without one,
   the `add_link` TEMPLATE and a sentence saying a person must name its two
   ends (裁定十五 ①: the machine never guesses which two columns relate)."
  [{:keys [model graph entity-namespaces]} finding]
  (let [attrs  (keep #(if (keyword? %) % (keyword (str/replace (str %) #"^:" "")))
                     (:attributes finding))
        child  (first (distinct (keep attr-ns attrs)))]
    (when child
      (if-let [link (link-between model child (not-empty (set entity-namespaces)))]
        {:finding (update finding :message str (link-sentence link))
         :repair {:tool "add_relation"
                  :args (add-relation-args graph child (entity-name child) link)}}
        {:finding (update finding :message str
                          (str " No link relates " (entity-name child)
                               " to what this query starts from, so the two columns that"
                               " relate them have to be named by a person (see :repair)."))
         :repair {:tool "add_link"
                  :args (cond-> {:from "" :to ""} graph (assoc :graph graph))
                  :note (str "name the two attributes that relate these entities: `from` is the "
                             "parent's identity attribute, `to` is the column on "
                             (entity-name child) " holding it")}}))))

;; ---------------------------------------------------------------------------
;; The two read-back repairs (doc 106 §106.5, P8a)
;;
;; They come from `dialect/need-view`, which is pure and knows no tool names —
;; a `common` module naming a Builder tool would be the dependency D1.8 draws
;; the line against. So the criterion states the defect and this ns, which is
;; already the ONE place a repair is inferred, states the call that fixes it.
;; ---------------------------------------------------------------------------

(defn- attr-of-finding
  "The attribute a need-view finding points at: `:path` is `[:attributes akw]`."
  [finding]
  (let [a (second (:path finding))]
    (when (keyword? a) a)))

(defn- repair-identity-undeclared
  "«This reads as the identity and nothing declares it» — one call declares it.
   Nothing is guessed: the criterion already named the attribute, and
   `update_attribute` is a merge patch, so the rest of the entry survives."
  [{:keys [graph]} finding]
  (when-let [akw (attr-of-finding finding)]
    {:finding finding
     :repair {:tool "update_attribute"
              :args (cond-> {:attribute (str (symbol akw)) :identity true}
                      graph (assoc :graph graph))}}))

(defn- repair-join-misplaced
  "«This relation hangs on the child» — the join belongs in the namespace the
   producing resolver reads from (§106.5 位置规则).

   The repair re-points the RESOLVER's output at a join attribute in the parent
   namespace and then removes the misplaced one. The new attribute has to exist
   before the resolver can name it, and no tool creates a bare join attribute,
   so the note says so rather than the args pretending otherwise — a repair the
   machine had to guess at is worse than none (see `repair-for`)."
  [{:keys [model graph]} finding]
  (when-let [akw (attr-of-finding finding)]
    (let [parent (first (:expected finding))
          target (get-in model [:attributes akw :target])
          producer (some (fn [r]
                           (when (some #(and (map? %) (contains? % akw)) (:output r))
                             r))
                         (:resolvers model))
          rid    (:id producer)
          ;; The children the branch already collects travel unchanged — only
          ;; the branch KEY moves. Rewriting the leaves too would be a second
          ;; change hiding inside a repair for the first.
          leaves (some #(when (and (map? %) (contains? % akw)) (get % akw))
                       (:output producer))
          moved  (when parent (str parent "/" (name akw)))]
      (when (and rid moved)
        {:finding finding
         :repair {:tool "update_resolver"
                  :args (cond-> {:resolver rid
                                 :output {moved (vec (map #(if (keyword? %) (str (symbol %)) %)
                                                          (or leaves [])))}}
                          graph (assoc :graph graph))
                  :note (str "the relation belongs on " parent " (it starts there); create "
                             moved " as a join onto " target
                             " first, point this resolver's output at it, then remove "
                             (str (symbol akw)))
                  :then [(cond-> {:tool "remove_objects"
                                  :args {:objects [(str (symbol akw))]}}
                           graph (update :args assoc :graph graph))]}}))))

;; ---------------------------------------------------------------------------
;; The two «you said this twice» repairs (doc 84 §8.4.25)
;; ---------------------------------------------------------------------------

(defn- repair-already-said
  "«This sentence was already said, and what you say now differs» — the way
   forward is to change the resolver it wrote, not to say the sentence again.
   The id is in `:got` and the keys that differ are in `:expected`, so nothing
   is guessed here."
  [{:keys [graph]} finding]
  (when-let [rid (:got finding)]
    {:finding finding
     :repair {:tool "update_resolver"
              :args (cond-> {:resolver (str rid)} graph (assoc :graph graph))
              :note (str "change what is already there instead of saying it again: "
                         "`update_resolver` is a merge patch over "
                         (str/join ", " (map #(str (symbol (str %))) (:expected finding)))
                         "; a field this thing does not have yet is `add_attribute`, "
                         "which adds to the resolver that already produces it")}}))

(defn- producer-entries [model ids]
  (mapv (fn [id] (some #(when (= id (:id %)) %) (:resolvers model))) ids))

(defn- second-sentence
  "Of two resolver ids, the one an identical SECOND sentence wrote, or nil.

   The test is the generator's own naming: a repeat takes the first one's id
   plus `_from_<relation>` (`impl.intent`'s `resolver-id`), so the newer of
   the two is the one whose id extends the other's. Two ids that are not
   related that way are two sentences somebody wrote deliberately, and this
   answers nothing rather than proposing which of them to delete."
  [ids]
  (let [[a b] ids]
    (cond (str/starts-with? (str b) (str a "_from_")) b
          (str/starts-with? (str a) (str b "_from_")) a)))

(defn- repair-multiple-producers
  "`:cut.structure/multiple-producers` where both producers read the SAME
   source the same way — «you said the same sentence twice» (release notes 0.2
   defect 13). The cut refuses every attribute of the thing and says nothing
   about the repeat; the repair removes the newer of the two.

   Producers that differ in what they read are NOT this case: two genuine
   answers for one attribute on one anchor is a modelling decision, and
   deleting one of them is not the machine's call."
  [{:keys [model graph]} finding]
  (let [ids (vec (:expected finding))]
    (when (= 2 (count ids))
      (let [[a b] (producer-entries model ids)]
        (when (and a b
                   (= (:source a) (:source b))
                   (= (:source-ref a) (:source-ref b))
                   (= (vec (:input a)) (vec (:input b))))
          (when-let [newer (second-sentence ids)]
            {:finding (update finding :message str
                              (str " Both read the same source in the same way, which is one"
                                   " sentence said twice; the second one is "
                                   newer " (see :repair)."))
             :repair {:tool "remove_objects"
                      :args (cond-> {:objects [(str newer)]}
                              graph (assoc :graph graph))}}))))))

;; ---------------------------------------------------------------------------
;; The one entry point
;; ---------------------------------------------------------------------------

(def ^:private by-code
  {:engine.compile/list-without-anchor repair-list-without-anchor
   :scope/mixed-output                 repair-mixed-output
   :engine.request/unreachable         repair-unreachable
   :dialect.need/identity-undeclared   repair-identity-undeclared
   :dialect.need/join-misplaced        repair-join-misplaced
   :tools.intent/already-said          repair-already-said
   :cut.structure/multiple-producers   repair-multiple-producers})

(defn repair-for
  "finding × `{:model :graph :entity-namespaces}` → the finding with `:repair`
   attached when one can be computed, and the finding UNCHANGED otherwise.

   Unchanged is the honest answer: a `:repair` the machine had to guess at is
   worse than none, because an agent sends it as it stands."
  [finding {:keys [model] :as ctx}]
  (let [f (get by-code (:code finding))]
    (if (and f model (not (:repair finding)))
      (if-let [{:keys [finding repair]} (try (f ctx finding)
                                             ;; A repair is a convenience: a
                                             ;; model shape it cannot read must
                                             ;; never take the refusal with it.
                                             (catch Exception _ nil))]
        (assoc finding :repair repair)
        finding)
      finding)))

(defn attach
  "`repair-for` over a findings collection. The vector is returned as a vector
   even when nothing was attached, so a caller's response shape does not
   depend on whether a repair was found."
  [findings ctx]
  (mapv #(repair-for % ctx) (or findings [])))

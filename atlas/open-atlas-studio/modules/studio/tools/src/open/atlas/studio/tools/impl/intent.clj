(ns open.atlas.studio.tools.impl.intent
  "The **intent planners** (8.4.14, owner 2026-09-03).

   ## What this layer is for

   The owner's two principles: a parameter says what a person would say
   (which graph, which table, which columns, which two ends), and the
   *format* questions — EDN keywords, entry key order, SQL assembly, recipe
   member shape — are generated in here rather than tried out by the caller
   one refusal at a time. One intent is one tool, not a sequence the agent has
   to compose.

   ## plan and apply are ONE function

   Every tool returns a plan by default and writes when `apply: true`, and the
   apply path **re-plans from the same arguments**. It does not accept an
   action list from the caller — the discipline `retired source generator` already
   holds itself to: the moment a caller can hand in actions, «these were
   generated from the requirement» is a sentence nobody guarantees.

   That is also why the plan shape is `local edit planner`'s: `:actions`,
   `:recipe-delta`, `:findings`, `:fill`. A face that learned to read one of
   them can read the other.

   ## The write is not atomic, and says so

   The pool and the graph registry are two stores. The order is **pool first,
   recipe second** and a failed recipe update is reported as
   `:tools.intent/recipe-not-updated` carrying the revisions that DID land —
   no rollback, because the pool write is a legal state on its own (D3.19: the
   pool is the object's home, a recipe is a list over it). The owner accepted
   this non-atomicity on 2026-09-03.

   ## Names in, ids out

   A local name is resolved through `resolve_target`'s four verdicts — the
   same four, not a second matcher — so an ambiguous name is refused with
   every candidate listed and nothing is written. Every result carries the
   fully-qualified ids of what it created or changed, so the caller's next
   call can name them directly."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools.impl.finding :as f]
            [open.atlas.studio.tools.impl.repair :as repair]
            [open.atlas.studio.tools.impl.resolve-target :as rt]))

;; ---------------------------------------------------------------------------
;; Small shared parts
;; ---------------------------------------------------------------------------

(defn- errors? [findings] (boolean (some #(= :error (:severity %)) findings)))

(def class->type
  "Connector column classes that can be stated as Semantic attribute types."
  {:string :string :integer :integer :decimal :decimal :boolean :boolean
   :date :date :datetime :datetime :json :json})

(def default-limit
  "Conservative bounded pagination for manually authored collection resolvers."
  {:default 50 :max 500})

(defn- action-pool-entry [model type id]
  (case type
    :attribute (get (:attributes model) id ::absent)
    :namespace (get (:namespaces model) id ::absent)
    :resolver  (or (first (filter #(= id (:id %)) (:resolvers model))) ::absent)
    :link      (or (first (filter #(= (vec id) [(:from %) (:to %)]) (:links model))) ::absent)
    ::absent))

(defn- already-written-action?
  [model {:keys [type id entry]}]
  (let [existing (action-pool-entry model type id)]
    (and (not= ::absent existing)
         (= (select-keys existing (keys entry)) entry))))

(defn- already-in-pool [model actions]
  (vec (filter #(already-written-action? model %) actions)))

(defn source-config
  "The pool's source entry, RESOLVED for this host — the single one both the
   structure-reading path (`describe_source`, `add_resolver`'s plan) and the
   api probe hand to a connector.

   Read from `as-dialect` rather than from `object`, because a connector wants
   the **dialect shape** of a source entry; the pool's bucketing is the pool's
   own business.

   **Why the resolution happens here.** A declared `${VAR}` is not a
   connection field: the query path resolves it through the host's
   `:resolve-source` before compiling an env, and a structure read that skips
   that step refuses with `unresolved ${VAR}` for exactly the credentials the
   Studio's credential store holds. Credential resolution stays 一处 —
   `credentials/resolve-config`, per 8.4.18 裁定十一 — so this applies the
   host's function and never grows a second lookup of its own. Absent (a test
   with no host), the entry passes through untouched."
  [ctx db source-id]
  (when-let [entry (get-in (pool/as-dialect db) [:sources source-id])]
    (if-let [resolve-source (:resolve-source ctx)]
      (resolve-source source-id entry)
      entry)))

(defn- action [op type id entry rev]
  (cond-> {:action op :type type :id id :expected_revision rev}
    entry (assoc :entry entry)))

(defn- revision-of [db type id]
  (or (:pool/revision (pool/object db type id)) 0))

(defn- attr-kw [ns-str n] (keyword ns-str (name n)))

(defn- leaf-attrs
  "Every attribute keyword in a resolver `:output` tree — branches (join
   attributes, D2.29) and leaves alike. Both live in `:attributes`, so both
   are recipe members."
  [tree]
  (cond
    (keyword? tree) #{tree}
    (map? tree)     (into (set (filter keyword? (keys tree)))
                          (mapcat leaf-attrs) (vals tree))
    (sequential? tree) (into #{} (mapcat leaf-attrs) tree)
    :else #{}))

(def ^:private empty-delta {:add {} :remove {}})

(defn- delta-add [delta kind ids]
  (if (seq ids) (update-in delta [:add kind] (fnil into #{}) ids) delta))

(defn- delta-remove [delta kind ids]
  (if (seq ids) (update-in delta [:remove kind] (fnil into #{}) ids) delta))

(defn- delta-empty? [d]
  (every? empty? (vals (merge (:add d) (:remove d)))))

;; ---------------------------------------------------------------------------
;; Name resolution — `resolve_target`'s four verdicts, reused verbatim
;; ---------------------------------------------------------------------------

(defn resolve-name
  "A word, a qualified id, or an id the caller read off an earlier result →
   `{:id …}` or `{:findings […]}`.

   A qualified keyword (or a string spelling one) is taken as an id and only
   checked for existence: item 5 of 8.4.14 makes «words in, ids out» a
   contract, so the id this layer just handed back must be accepted on the
   next call without another `resolve_target` round trip.

   Anything else goes through `rt/resolve-target`, and ONLY `:exact-unique`
   yields an id. `:exact-ambiguous` refuses with every candidate — the
   ambiguity is not broken by ranking here any more than it is there."
  [db type query {:keys [abbreviations path]}]
  (let [as-kw (cond
                (qualified-keyword? query) query
                (and (string? query) (str/includes? query "/")
                     (not (str/blank? query)))
                (keyword (str/replace-first query #"^:" ""))
                :else nil)
        path  (or path [type])]
    (cond
      ;; A registry id (resolver, term, unit …) is a plain string and has no
      ;; second spelling — the pool either holds it or does not.
      (and (contains? #{:resolver :mutation :metric :term :unit :namespace} type)
           (string? query))
      (if (pool/object db type query)
        {:id query}
        {:findings [(f/make :tools.intent/name-unresolved :error path
                            "the pool holds no object of this type under this id"
                            :got query)]})

      (and as-kw (pool/object db type as-kw)) {:id as-kw}

      :else
      (let [model (pool/as-dialect db)
            idx   (rt/index (pool/objects db) (:terms model) abbreviations)
            r     (rt/resolve-target idx (str (if as-kw (symbol as-kw) query))
                                     {:type type :abbreviations abbreviations})]
        (case (:verdict r)
          :exact-unique {:id (second (:target r))}
          :exact-ambiguous
          {:findings [(f/make :tools.intent/name-ambiguous :error path
                              (str "this name matches several objects; it is not disambiguated "
                                   "by ranking — name one of the candidates exactly")
                              :got (mapv (fn [[t id]] [t (str (if (keyword? id) (symbol id) id))])
                                         (:candidates r)))]}
          ;; `:fuzzy` and `:none` are one refusal on a WRITE face: neither
          ;; carries an id, and a near miss that wrote something would be the
          ;; worst outcome of the three.
          {:findings [(f/make :tools.intent/name-unresolved :error path
                              "no object of this type resolves from this name"
                              :got (str query)
                              :expected (when (seq (:candidates r))
                                          (mapv (fn [[_ id]] (str (if (keyword? id) (symbol id) id)))
                                                (:candidates r))))]})))))


;; ---------------------------------------------------------------------------
;; Decoding the NESTED positions — 8.4.14 裁定六 principle 1
;; ---------------------------------------------------------------------------
;;
;; `add_link` and `add_attribute` take an attribute as a word and resolve it in
;; here; the edit tools took the same word nested inside `:order`, `:eql-params`
;; or an `:output` tree and handed it to the pool as the JSON string it arrived
;; as. A 2026-09-04 session spent its whole budget on that asymmetry: every
;; spelling of an attribute inside `order` — bare, colon-prefixed, EDN — planned
;; cleanly and was refused at APPLY with `:dialect.schema/invalid-shape`, and the
;; agent gave up on declarative ordering and wrote ORDER BY into the SQL, losing
;; the caller-overridable window the dialect exists to declare.
;;
;; So: ONE pass over every nested position, before the entry is built, reusing
;; `resolve_target`'s four verdicts for names and the dialect's own vocabularies
;; for the words that must be keywords. No second matcher, no second word list.

(def order-directions
  "The two words `:order`'s `:default` may end with (dialect-v1 §3). Not part of
   `dialect/vocabulary` — it is one tuple position, not a vocabulary of the
   model — but it is coerced like one."
  #{:asc :desc})

(defn- vocab-word
  "A vocabulary member the caller spelled as a plain string -> the keyword the
   dialect wants: \"asc\", \":asc\" and :asc are one value.

   A word no vocabulary holds passes through UNTOUCHED, so the dialect refuses
   it by name (with its own vocabulary in :expected) instead of this layer
   minting a second refusal for the same defect."
  [allowed v]
  (let [k (cond (keyword? v) v
                (string? v)  (keyword (str/replace-first v #"^:" ""))
                :else        nil)]
    (if (and k (contains? allowed k)) k v)))

(defn- name-string
  "A nested reference as a word `resolve-name` can take: a qualified keyword
   stays one, an unqualified keyword becomes its name (`:posts` is a local name
   somebody typed, not a namespace-less id), anything else is left alone."
  [v]
  (cond
    (qualified-keyword? v) v
    (keyword? v)           (name v)
    :else                  v))

(defn- attr-ref
  "One nested attribute reference -> its qualified keyword.

   The SAME four verdicts a top-level name gets: ambiguous refuses with every
   candidate, unknown refuses with the near misses, and only `:exact-unique`
   yields an id. Findings accumulate in `acc`; a position that did not resolve
   comes back unchanged, so the refusal and the plan agree about what was sent."
  [db opts path acc v]
  (let [r (resolve-name db :attribute (name-string v)
                        {:abbreviations (:abbreviations opts) :path path})]
    (if (seq (:findings r))
      (do (vswap! acc into (:findings r)) v)
      (:id r))))

(defn- kmap
  "Map keys as keywords. A JSON object arrives keywordised already; an EDN
   string may not, and one spelling per key is the point of this pass."
  [m]
  (when (map? m)
    (into {} (for [[k v] m]
               [(if (string? k) (keyword (str/replace-first k #"^:" "")) k) v]))))

(defn- decode-output
  "An `:output` tree: leaves are attribute references, and branch KEYS are join
   attributes (D2.29). Both are decoded the same way because both are
   registrations in `:attributes` — the same reason `leaf-attrs` treats them
   alike."
  [db opts path acc tree]
  (cond
    (map? tree)        (into {} (for [[k v] tree]
                                  [(attr-ref db opts path acc k)
                                   (decode-output db opts path acc v)]))
    (sequential? tree) (mapv #(decode-output db opts path acc %) tree)
    :else              (attr-ref db opts path acc tree)))

(defn- decode-order [db opts acc order]
  (let [o (kmap order)]
    (cond-> o
      (contains? o :allowed)
      (assoc :allowed (mapv #(attr-ref db opts [:order :allowed] acc %) (:allowed o)))
      (contains? o :default)
      (assoc :default
             (let [[a d] (:default o)]
               [(attr-ref db opts [:order :default] acc a)
                (vocab-word order-directions d)])))))

(defn- decode-eql-param [db opts pname acc param]
  (let [p (kmap param)]
    (cond-> p
      (contains? p :attribute)
      (assoc :attribute (attr-ref db opts [:eql-params pname :attribute] acc (:attribute p)))
      (contains? p :op)   (update :op   #(vocab-word (:ops dialect/vocabulary) %))
      (contains? p :type) (update :type #(vocab-word (:attribute-types dialect/vocabulary) %)))))

(defn- decode-eql-params
  "`{param-name {…}}`. The NAMES are keywords in the dialect too (D2.20's
   reserved four are keywords), so the map's keys go through `kmap` and its
   values through the param decoder."
  [db opts acc params]
  (into {} (for [[pname param] (kmap params)]
             [pname (decode-eql-param db opts pname acc param)])))

(defn- as-int
  "A window bound spelled as a JSON string: \"50\" is fifty in every client that
   has ever sent it. A string that is not a number is left for the dialect."
  [v]
  (if (and (string? v) (re-matches #"\s*\d+\s*" v)) (parse-long (str/trim v)) v))

(defn decode-resolver-patch
  "A resolver patch as the wire delivered it -> the patch the dialect wants.
   `{:patch … :findings […]}`.

   Every nested attribute reference is resolved and every vocabulary word
   coerced HERE, once, before `plan-update-resolver` lays the patch over the
   current entry."
  [db opts patch]
  (let [acc (volatile! [])
        p (cond-> patch
            (contains? patch :input)
            (assoc :input (mapv #(attr-ref db opts [:input] acc %) (:input patch)))

            (contains? patch :output)
            (assoc :output (decode-output db opts [:output] acc (:output patch)))

            (contains? patch :order)
            (assoc :order (decode-order db opts acc (:order patch)))

            (contains? patch :eql-params)
            (assoc :eql-params (decode-eql-params db opts acc (:eql-params patch)))

            (contains? patch :limit)
            (assoc :limit (into {} (for [[k v] (kmap (:limit patch))] [k (as-int v)])))

            (contains? patch :cache)
            (assoc :cache (into {} (for [[k v] (kmap (:cache patch))] [k (as-int v)]))))]
    {:patch p :findings @acc}))

(defn decode-attribute-patch
  "An attribute patch, same pass. `:replaced-by` is an attribute REFERENCE
   (D2.22) and resolves like every other one — spelling it out in full was the
   caller's job only because nobody had done it here."
  [db opts patch]
  (let [acc (volatile! [])
        p (cond-> patch
            (contains? patch :replaced-by)
            (assoc :replaced-by (attr-ref db opts [:replaced_by] acc (:replaced-by patch)))
            (contains? patch :type)
            (update :type #(vocab-word (:attribute-types dialect/vocabulary) %))
            (contains? patch :format)
            (update :format #(vocab-word (:formats dialect/vocabulary) %)))]
    {:patch p :findings @acc}))

;; ---------------------------------------------------------------------------
;; Which source, which namespace — the two things a modeller usually omits
;; ---------------------------------------------------------------------------

(defn graph-sources
  "The sources the graph's own resolvers reference. `add_resolver` takes the
   one when there is exactly one — «the source» is unambiguous in a graph
   built from one database, which is the common case, and refusing to guess
   between two is the whole point of not ranking them."
  [model members]
  (let [attrs (set (:attributes members))
        ns*   (set (:namespaces members))]
    (into (sorted-set)
          (keep (fn [r]
                  (let [out (leaf-attrs (:output r))]
                    (when (and (:source-ref r)
                               (some #(or (contains? attrs %)
                                          (contains? ns* (namespace %)))
                                     out))
                      (:source-ref r)))))
          (:resolvers model))))

(defn decide-source [model members explicit]
  (cond
    explicit {:source explicit}
    :else
    (let [in-graph (graph-sources model members)
          all      (keys (:sources model))
          cands    (or (not-empty in-graph) (set all))]
      (if (= 1 (count cands))
        {:source (first cands)}
        {:findings [(f/make :tools.intent/source-undecidable :error [:source]
                            (str "this graph names " (count cands) " sources, so which one this "
                                 "relation comes from is not the machine's to pick; name it")
                            :got (vec (sort cands)))]}))))

(defn- namespace-prefix
  "The dotted prefix the graph's namespaces agree on — «按图内兄弟 resolver
   惯例推» in the only form that is a fact rather than a taste: the longest
   common dotted prefix of the namespaces this graph already holds.

   Two namespaces that share nothing produce no prefix, and then the caller is
   asked for one. A machine-invented namespace would be a naming act, and
   naming is the owner's (D2.26)."
  [members]
  (let [segs (map #(str/split % #"\.") (sort (:namespaces members)))]
    (when (seq segs)
      (let [common (reduce (fn [a b]
                             (mapv first (take-while (fn [[x y]] (= x y)) (map vector a b))))
                           (first segs) (rest segs))]
        (when (seq common) (str/join "." common))))))

(defn decide-namespace [members explicit relation]
  (cond
    explicit {:namespace explicit}
    :else
    (if-let [p (namespace-prefix members)]
      {:namespace (str p "." relation)}
      {:findings [(f/make :tools.intent/namespace-undecidable :error [:namespace]
                          (str "the namespaces in this graph share no common prefix, so there is "
                               "no convention to follow; name the namespace")
                          :got (vec (sort (:namespaces members))))]})))

;; ---------------------------------------------------------------------------
;; add_resolver
;; ---------------------------------------------------------------------------

(defn- relation-of [description name*]
  (some #(when (= name* (:name %)) %) (:relations description)))

(defn- typed-columns
  "The columns the machine may assert a `:type` for, and the ones it may not —
   the second list is reported, never guessed at (D2.14, same table
   `retired source generator` uses)."
  [rel]
  {:typed   (filterv #(contains? class->type (:class %)) (:columns rel))
   :untyped (filterv #(not (contains? class->type (:class %))) (:columns rel))})

(defn- sql-select [cols] (str/join ", " (map :name cols)))

(defn- ns-slug [ns-str] (str/replace ns-str "." "_"))

(defn- source-slug
  "The place a resolver reads, as an identifier fragment: a sql table name, or
   an api endpoint path with its `{placeholder}` segments dropped — those name
   the key, not the place, and `/flow/processors/{id}/status` and
   `/flow/processors/{+id}/status` are one endpoint written two ways."
  [place]
  (-> (str place)
      (str/replace #"\{[^}]*\}" "")
      (str/replace #"[^A-Za-z0-9]+" "_")
      (str/replace #"^_+|_+$" "")
      str/lower-case))

(defn- join-root
  "The single join branch a `:result_type \"list\"` resolver hangs its rows on,
   or nil. The engine decides compilability on exactly this shape
   (`engine/impl/compile`'s `:engine.compile/list-without-anchor`); this layer
   asks the same question BEFORE writing, so a plan cannot produce a resolver
   the engine will drop (8.4.19 裁定十三 ②)."
  [tree]
  (when (and (vector? tree) (= 1 (count tree)) (map? (first tree)))
    (let [m (first tree)]
      (when (= 1 (count m)) (key (first m))))))

(defn- row-tree
  "ONE row's share of a resolver's `:output`: the whole tree, or — when the
   rows hang under a single join root (D2.8/D2.16) — that branch's children."
  [output]
  (if-let [jr (join-root output)] (vec (get (first output) jr)) (vec output)))

(defn- with-row-branch
  "Add one branch to the tree ONE ROW is shaped by, leaving a join root where
   it is: the «belongs to» key belongs beside the child's own fields, not
   beside the collection that carries them."
  [output branch]
  (if-let [jr (join-root output)]
    [{jr (conj (vec (get (first output) jr)) branch)}]
    (conj (vec output) branch)))

(defn compile-shape-findings
  "A resolver entry → the compile-class findings its SHAPE would produce.

   The `:code` is the ENGINE's, verbatim, and deliberately not a `tools.*`
   one: an agent that met `:engine.compile/list-without-anchor` in a summary
   or in a publish check must meet the same code here, or the same defect has
   three names (finding-v1 §6 registers codes per module; this carries one
   rather than minting a second). `f/make` is not used for the same reason —
   its registry is this layer's own.

   Only the shape half is judged here. Whether a source is executable is a
   fact about the host's connectors, which a plan does not know."
  [entry path]
  (when (and (= "list" (:result_type entry)) (nil? (join-root (:output entry))))
    [{:code :engine.compile/list-without-anchor
      :severity :error
      :path path
      :message (str "a :result_type \"list\" resolver needs ONE join attribute at the head of "
                    ":output to carry the rows; without it the engine compiles nothing for it "
                    "and the resolver is present in the draft but absent from every query")
      :refs ["D2.8" "D2.16" "8.4.19"]}]))

(defn normalize-outputs
  "`outputs` as the planner reads them (8.4.22 裁定十六 ②).

   An entry is either a column name — `\"name\"` — or a nested value,
   `{name, path}`, where `path` is a JSONPath relative to one record
   (`$.address.city` → the attribute `city`). Both spellings of a map key are
   accepted because the same tool is called over JSON (string keys) and from
   the Builder's own UI (keyword keys); a map with no usable name is left for
   the planner to refuse by name."
  [outputs]
  (vec (for [o outputs]
         (if (map? o)
           {:name (some-> (or (:name o) (get o "name")) name)
            :path (some-> (or (:path o) (get o "path")) str)}
           {:name (name o) :path nil}))))

(defn filters->eql-params
  "`filters` -> a dialect `:eql-params` block (doc 106 §106.12 item 8.3).

   A parameterised query has no sentence in the grammar and gets none
   (need-grammar-v1 §4: what the twelve cannot say is advanced, and is shown
   in Pathom's own words) — but a caller who already knows that word should
   not have to reach it through an `update_resolver` after the fact. So this
   carries the block verbatim and decides nothing about it, beyond qualifying
   a bare attribute name with `ns-str` the way every other id in a plan is
   spelled by the planner rather than by the caller.

   One function, because BOTH collection shapes take the parameter: an
   endpoint-backed collection and one that sits inside its parent's record
   (doc 106 §106.12 item 10.2 — the second silently dropped the block and only
   a query afterwards said `unknown-param`)."
  [ns-str filters]
  (not-empty
   (into {}
         (for [[pname spec] filters
               :let [a (str (or (:attribute spec) (get spec "attribute")))
                     o (or (:op spec) (get spec "op"))
                     d (or (:description spec) (get spec "description"))]]
           [(keyword (name pname))
            (cond-> {:attribute (if (str/includes? a "/")
                                  (keyword (str/replace a #"^:" ""))
                                  (attr-kw ns-str a))}
              o (assoc :op (keyword (name o)))
              d (assoc :description (str d)))]))))

;; ---------------------------------------------------------------------------
;; «The same sentence said twice» (doc 84 §8.4.25, release notes 0.2 defect 13)
;; ---------------------------------------------------------------------------

(defn- read-locator
  "WHAT a resolver reads — the source it reads through, and the relation or
   endpoint it reads there — with nothing about WHICH columns it takes.

   Held apart from the entry comparison on purpose: two sentences about one
   thing, from one place, keyed the same way ARE the same sentence even when
   their attribute lists differ. Comparing whole entries instead would let a
   half-different repeat read as a new source and quietly write a second
   producer of the same attributes — the defect this exists to stop."
  [entry]
  (let [s (:source entry)
        t (str (:type s))]
    [(:source-ref entry)
     t
     (case t
       "sql" (repair/resolver-relation entry)
       "api" (str (:endpoint s))
       nil)]))

(defn- sentence-of
  "The identity of the SENTENCE an entry says: what it reads, what it is keyed
   on, and whether it answers one row or a list. Everything else — which
   attributes, which limit, which label — is what was said ABOUT that thing
   and belongs to `differing-keys`."
  [entry]
  [(read-locator entry) (vec (:input entry)) (str (:result_type entry))])

(defn- delta-minus-members
  "The delta with everything the graph's recipe ALREADY names dropped.

   Only the repeated-sentence path uses it: a plan that creates objects is
   naming things the recipe cannot already hold, while a repeat names objects
   that are all in the pool and MAY be in the recipe already. Without this the
   second `apply` would go on reporting a recipe write that changed nothing."
  [delta members]
  (update delta :add
          (fn [add]
            (reduce-kv (fn [m k ids]
                         (let [ids' (into #{} (remove (set (get members k))) ids)]
                           (cond-> m (seq ids') (assoc k ids'))))
                       {} (or add {})))))

(defn said-before
  "The resolver an EARLIER, identical sentence already wrote, or nil.

   ai-semantic-contract.md: 「同一个来源说第二次是同一条 resolver，不是
   第三条」. `add_attribute`'s second source (doc 106 §106.12 8.1) and the
   `retired source generator` wizard (13.4) have held that since they were written;
   `add_thing` and `add_relation` did not, and a re-run of one sentence grew a
   SECOND by-key resolver (`…_from_<relation>`) producing the same attributes
   — `:produced-twice` in `inspect_graph`, `:cut.structure/multiple-producers`
   at the cut, and no refusal anywhere saying «you said this twice»."
  [model entry]
  (first (filter #(= (sentence-of %) (sentence-of entry)) (:resolvers model))))

(defn- differing-keys
  "The keys over which the pool's entry and the one this run would write
   disagree, compared over the keys the GENERATOR writes — the same rule as
   `retired source generator`'s `already-written?`: everything the enrichment pass adds
   afterwards (a label, a description, synonyms) is somebody describing the
   object, not a second author of it.

   `:id` is excluded because the two ids differ BY CONSTRUCTION: the repeated
   sentence took the `…_from_<relation>` name precisely because the first one
   was there."
  [existing entry]
  (vec (sort-by pr-str
                (for [k (keys (dissoc entry :id))
                      :when (not= (get existing k) (get entry k))]
                  k))))

(defn fold-said-before
  "**The same sentence, said a second time** (doc 84 §8.4.25, release notes 0.2
   defect 13) — pool × the graph's recipe × an assembled plan → either that
   plan unchanged, the «already said» plan, or the plan with a refusal.

   It runs on the FINISHED plan rather than inside `plan-add-resolver`, and
   that is the whole design: `add_relation` assembles its resolver in two
   halves (`merge-relation-plans` folds the «belongs to» branch into the entry
   the first half only planned), so the entry the pool holds after one apply is
   not the entry the base planner alone produces. Comparing too early reported
   a byte-identical repeat as a difference in `:output` and `:source`.

   Three answers, and the middle one is the reason this is not a set
   difference:

   - **no resolver of this sentence in the pool** → the plan, untouched;
   - **there, and equal** → no pool action at all, and a recipe delta only for
     what this graph does not name yet. `already said — nothing to write`;
   - **there, and different** → `:tools.intent/already-said`. Writing it would
     make this tool the second author of one thing's key, which is what the
     cut refuses as `:cut.structure/multiple-producers` — with, until this,
     nothing anywhere saying «you said this twice».

   Equality is `retired source generator`'s `already-written?` rule (doc 106 §106.12 item
   13.4): over the keys the GENERATOR writes, so a label or a description
   somebody added afterwards is not a second author. `:id` is excluded because
   the two ids differ by construction — the repeat took the
   `…_from_<relation>` name precisely because the first one was there."
  [model members plan]
  (let [ract (first (filter #(and (= :create (:action %)) (= :resolver (:type %)))
                            (:actions plan)))
        said (when ract (said-before model (:entry ract)))]
    (if-not said
      plan
      (let [diff   (differing-keys said (:entry ract))
            others (vec (remove #(= % ract) (:actions plan)))
            ;; Everything ELSE the plan would write must already be in the
            ;; pool too, written the same way. A repeat whose namespace or
            ;; attributes are somehow missing is not «already said» — it is a
            ;; half-written earlier attempt, and it must be allowed to finish.
            pending (remove (set (already-in-pool model others)) others)]
        (cond
          (seq diff)
          ;; The actions go with it: a plan whose every action would create the
          ;; second producer is not «the plan this refusal carries», it is the
          ;; write the refusal exists to stop.
          (-> plan
              (assoc :actions [] :fill [])
              (update :findings (fnil conj [])
                  (f/make :tools.intent/already-said :error [:resolver]
                          (str "this sentence was already said, and it wrote resolver "
                               (:id said) "; what is being said now differs from it, so "
                               "this would become a second producer of the same thing")
                          :got (:id said)
                          :expected (mapv #(str (symbol %)) diff)
                          :refs ["ai-semantic-contract.md" "doc 84 §8.4.25" "D2.11"])))

          (seq pending) plan

          :else
          (let [created (:created plan)
                delta   (-> empty-delta
                            (delta-add :namespaces (when-let [n (:namespace created)] [n]))
                            (delta-add :attributes (:attributes created))
                            (delta-minus-members members))]
            (-> plan
                (assoc :actions [] :fill [])
                (assoc :recipe-delta delta)
                (assoc :existing {:resolver (:id said)
                                  :attributes (vec (:attributes created))
                                  :namespace (:namespace created)})
                (assoc-in [:created :resolver] (:id said)))))))))

(defn plan-add-resolver
  "`{:graph :relation :source :input :outputs :namespace :collection-name
     :parent :foreign-key}` × the pool → the plan that adds one resolver to
   one graph.

   `description` is the source's structure (`describe_source` for a sql
   source) or the probe's inferred structure (裁定七) — this function does not
   care which produced it, which is what lets the caller not know either.

   **Three shapes, and the caller picks by saying what they have** (8.4.19
   裁定十三 ①):

   - `parent` → the parent entity's CHILD COLLECTION: one join attribute on
     the parent's namespace, and a batched list resolver keyed on the child's
     `foreign_key`. This is D2.29's commonest navigation, and until 裁定十三
     it could not be said here at all — a modeller reaching for it hand-edited
     a by-key resolver into a `list` with no join root, which the engine
     compiles to nothing (that incident is the ruling's evidence);
   - `input` empty → the unanchored collection, whose head is a **join
     attribute** rather than a navigation (D2.29): one attribute on the same
     namespace whose `:target` is that namespace;
   - `input` non-empty → the by-key lookup.

   `parent` and `input` are mutually exclusive: with a parent the input IS the
   parent key, and a caller naming both has said two different things about
   the same slot."
  [db {:keys [members model description]} {:keys [relation source input outputs
                                                  prefer-outputs
                                                  namespace collection-name
                                                  parent foreign-key api static
                                                  identity-columns filters
                                                  url-bound]}]
  (let [rel (relation-of description relation)]
    (cond
      (and parent (seq input))
      {:actions [] :recipe-delta empty-delta :fill []
       :findings [(f/make :tools.intent/mutually-exclusive :error [:parent]
                          (str "a child collection is keyed on the parent, so its :input is the "
                               "parent attribute; naming `input` as well says two things about "
                               "the same slot")
                          :got (mapv str input)
                          :refs ["8.4.19 裁定十三 ①"])]}

      (nil? rel)
      {:actions [] :recipe-delta empty-delta :fill []
       :findings [(f/make :tools.intent/unknown-relation :error [:relation]
                          "this source does not carry a relation by that name"
                          :got relation
                          :expected (mapv :name (:relations description)))]}

      :else
      (let [{:keys [typed untyped]} (typed-columns rel)
            ;; **A URL-bound attribute is nameable wherever a column is**
            ;; (owner 2026-09-07, doc 106 §106.14 items 11 and 17): the source
            ;; takes that value from the query rather than from the record, so
            ;; «this thing is recognised by the host AND by its own id» names
            ;; something real, and refusing it as «this relation carries no
            ;; typeable column by that name» was true and beside the point.
            ;; Keyed by BOTH spellings — a caller writes the qualified id
            ;; because the attribute is another thing's, while a generated
            ;; `:input` reads back the local one.
            bound-by-name (into {} (mapcat (fn [a] [[(name a) a] [(str (symbol a)) a]]))
                                (or url-bound []))
            bound-names (set (keys bound-by-name))
            ;; 8.4.22 裁定十六 ②: an output is a column name OR a `{name,
            ;; path}` nested value; by here the api branch has already turned
            ;; the latter into a column of `description`, so both are just
            ;; names to select by.
            wanted   (when (seq outputs) (set (map :name (normalize-outputs outputs))))
            ;; `prefer-outputs` is a PREFERENCE, not a demand: names the
            ;; relation does not carry are simply absent rather than refused.
            ;; A caller who NAMED fields is told when one is missing
            ;; (`:outputs`); a caller narrowing to what is already modelled
            ;; asked for the intersection (doc 106 §106.12 item 5 ③).
            preferred (when (and (nil? wanted) (seq prefer-outputs))
                        (let [ps (set (map name prefer-outputs))]
                          (not-empty (filterv #(contains? ps (:name %)) typed))))
            chosen   (cond wanted (filterv #(contains? wanted (:name %)) typed)
                           preferred preferred
                           :else typed)
            missing  (when wanted (set/difference wanted (set (map :name chosen))
                                                 bound-names))
            ;; **The batch key is SELECTED even when it is not an output**
            ;; (measured 2026-09-07, doc 106 §106.15 Build A). `align-batch`
            ;; buckets the flat rows by the `:child-fk` COLUMN read out of each
            ;; row, so a statement that does not select it answers rows the
            ;; connector cannot attribute to any parent — every one of them
            ;; lands under the nil key and every parent gets nothing. It became
            ;; reachable with need-grammar-v1 §3.3 (a second source's `:output`
            ;; holds only the new attributes), which is a rule about the
            ;; ATTRIBUTE list; the SELECT list is a different list, and this is
            ;; the one place that difference is decided.
            ;; Folded over EVERY fk column of a compound key (doc 106 §106.14
            ;; item 14) — each one has to be in the SELECT or the row cannot be
            ;; attributed to a parent.
            select-of (fn [cols fk]
                        (if (and fk (not (some #(= fk (:name %)) cols)))
                          (conj (vec cols) {:name fk})
                          (vec cols)))
            in-cols  (vec (keep (fn [c] (some #(when (= (name c) (:name %)) %) typed))
                                (or input [])))
            ;; **A key bound in the URL is already bound** (owner 2026-09-07,
            ;; doc 106 §106.14 item 11, the A2 addendum). A source whose
            ;; `:base_url` names `{nifi.instance/host}` picks its instance from
            ;; that attribute, so a second source under it — `/flow/about` —
            ;; need not echo the key in its RECORDS to be keyed on it. Before
            ;; this, such a sentence was refused with «this relation carries no
            ;; typeable column by that name»: true, and beside the point, since
            ;; the value never came from the record.
            ;; «Declared» counts what THIS plan declares: the sentence that
            ;; creates the roster thing is the same one that first names the
            ;; attribute its own host is bound to.
            will-exist? (fn [a]
                          (or (get-in model [:attributes a])
                              (contains? (set (map #(attr-kw namespace (:name %)) chosen)) a)))
            bad-in   (set/difference (set (map name (or input [])))
                                     (set (map :name in-cols))
                                     bound-names)
            ;; The resolver's `:input`, in the caller's order: a column of the
            ;; relation, or the attribute the URL binds. It CARRIES the bound
            ;; attribute, because that is what makes the binding visible — to
            ;; the cut (D2.2), to the recipe, and to Pathom's index, which is
            ;; the only thing between a query and a stray `{…}` URL. The
            ;; alternative — an implicit source-level input the engine adds —
            ;; would put the reachability of a resolver somewhere the model
            ;; cannot be read from, which is the second semantics D1.8 forbids.
            input-attrs (let [named (keep (fn [c]
                                            (or (bound-by-name (name c))
                                                (some #(when (= (name c) (:name %))
                                                         (attr-kw namespace (:name %)))
                                                      typed)))
                                          (or input []))]
                          ;; A URL-bound attribute the caller did not name is
                          ;; added: every request on such a source needs a
                          ;; value for it, so a lookup that did not ask for one
                          ;; is a request with a hole in its host. Only a KEYED
                          ;; shape gains it — a root collection on a templated
                          ;; source has no key to hang it off, and validation
                          ;; refuses that entry by name rather than this
                          ;; quietly turning a collection into a lookup.
                          (vec (distinct (concat named
                                                 (when (seq input)
                                                   (filter will-exist? (distinct (vals bound-by-name))))))))
            ;; A source keyed on an attribute NOTHING declares cannot be read
            ;; at all — every request under it needs that value. Refused here,
            ;; naming the source: writing the reference anyway earns
            ;; `:dialect.invariant/unregistered-reference` from the pool
            ;; (D2.2), which is true and says nothing about the source.
            ;; The three shapes. `child?` wins over `collection?` because a
            ;; parent's collection is anchored on that parent — the whole
            ;; point of the third shape.
            child?      (some? parent)
            collection? (and (not child?) (empty? (or input [])))
            ;; **A templated source has no true root collection** (owner
            ;; 2026-09-07, doc 106 §106.12 item 9.6; need-grammar-v1 §3.5 last
            ;; bullet). «All of the X» on a source whose host comes from an
            ;; attribute means «all the X of THIS instance», so the unanchored
            ;; shape is generated as the CHILD COLLECTION of the thing that
            ;; owns the bound attribute. Before this it was written as an
            ;; `:input []` resolver that could never run — the URL kept its
            ;; `{…}` and only a validation query afterwards said
            ;; `source execution failed`.
            bound-attrs (vec (sort (distinct (vals bound-by-name))))
            templated-root? (and collection? (seq bound-attrs))
            ;; The owner is the namespace the bound attribute lives in: the
            ;; instance, in the source's own words.
            owner-ns    (when templated-root?
                          (clojure.core/namespace (first bound-attrs)))
            undeclared-bound (when (or (seq input) parent templated-root?)
                               (sort (remove will-exist? (distinct (vals bound-by-name)))))
            ;; `clojure.core/namespace`, spelled out: this fn's own
            ;; `namespace` parameter (the CHILD's) shadows it.
            parent-ns   (when child? (clojure.core/namespace (if (sequential? parent)
                                                               (first parent)
                                                               parent)))
            ;; **One fk column per part of the parent key** (owner 2026-09-07,
            ;; doc 106 §106.14 item 14): `foreign-key` may be a list, paired
            ;; position by position with `parent`. One key stays one column and
            ;; every statement below is byte-identical to before.
            fk-names    (cond (nil? foreign-key)        []
                              (sequential? foreign-key) (mapv name foreign-key)
                              :else                     [(name foreign-key)])
            fk-cols     (when child?
                          (vec (keep (fn [n] (some #(when (= n (:name %)) %) typed))
                                     fk-names)))
            fk-col      (first fk-cols)
            parents     (cond (nil? parent)        []
                              (sequential? parent) (vec parent)
                              :else                [parent])
            ns-str    namespace
            attr      #(attr-kw ns-str %)
            ;; A child collection is named after the CHILD TABLE by default —
            ;; `users/posts`, the sentence the modeller already says — while
            ;; the unanchored set keeps its `all_` prefix (retired source generator uses
            ;; the same two conventions).
            join-name (or collection-name (if child? relation (str "all_" relation)))
            join-attr (cond child?          (keyword parent-ns join-name)
                            templated-root? (keyword owner-ns join-name)
                            :else           (attr join-name))
            existing-ns (boolean (get-in model [:namespaces ns-str]))
            new-attrs (vec (for [c (sort-by :name chosen)
                                 :when (not (get-in model [:attributes (attr (:name c))]))]
                             c))
            ;; Named after what the lookup is keyed ON — the bound attribute
            ;; included, so a URL-bound lookup is not `…_by_` with nothing
            ;; after it.
            ;; **The LOCAL name of each part** (owner 2026-09-07, doc 106
            ;; §106.14 item 18): a caller may now name a CARRIED attribute as
            ;; part of the key — `identity ["nifi.instance/host", "id"]` — and
            ;; the qualified spelling would put a `.` and a `/` inside a
            ;; resolver id. `bound-by-name` already holds both spellings, so
            ;; this reads the local half of whichever one was written; an
            ;; unqualified column name is unchanged, byte for byte.
            by-key-id   (str (ns-slug ns-str) "_by_"
                             (str/join "_" (map (fn [c]
                                                  (let [n (name c)]
                                                    (if-let [a (bound-by-name n)]
                                                      (name a)
                                                      n)))
                                                (or input []))))
            resolver-id (cond
                          child?          (str (ns-slug parent-ns) "_" join-name)
                          templated-root? (str (ns-slug owner-ns) "_" join-name)
                          collection?     (str (ns-slug ns-str) "_" join-name)
                          ;; A2's second source (need-grammar-v1 A2): one thing
                          ;; fetched by the same key from another place. The id
                          ;; is derived from the namespace, so it would collide
                          ;; with the first lookup; the place it reads from is
                          ;; what tells them apart. For sql that place IS
                          ;; `relation` (the table); for api `relation` is the
                          ;; THING's name, one value for every endpoint — so a
                          ;; second and a third source of one thing collided on
                          ;; `…_from_<thing>` and the third sentence could not
                          ;; be said at all (an instance reads four endpoints).
                          ;; The endpoint is what the comment above already
                          ;; claimed told them apart.
                          (some #(= by-key-id (:id %)) (:resolvers model))
                          (str by-key-id "_from_"
                               (source-slug (if api (or (:endpoint api) (:path api)) relation)))
                          :else       by-key-id)
            ;; **The api shapes** (8.4.22 裁定十六 ②). Everything above this
            ;; line — which columns, which join attribute, which attributes to
            ;; register — is the same decision for both kinds of source; only
            ;; the operation block differs, so the two meet here rather than in
            ;; two planners.
            api?      (some? api)
            ;; A1's source may be a hand-written list (need-grammar-v1 A1,
            ;; static-v1): the rows ship WITH the model and are hashed into
            ;; the version, so there is no connection entry and no
            ;; `:source-ref` — the data is not somewhere else.
            static?   (some? static)
            static-rows (when static?
                          (let [names (mapv :name chosen)]
                            (mapv (fn [r]
                                    (into (sorted-map)
                                          (for [n names
                                                :let [v (get r (keyword n) (get r n ::absent))]
                                                :when (not= v ::absent)]
                                            [(attr n) v])))
                                  (:rows static))))
            ;; A compound key matches on ALL of its parts at once, which
            ;; static-v1's `:match-on` spells as a vector (owner 2026-09-07,
            ;; doc 106 §106.14 item 14); one key stays the bare keyword.
            static-block (fn [] (cond-> {:type "static" :data static-rows}
                                  (= 1 (count in-cols))
                                  (assoc :match-on (attr (:name (first in-cols))))
                                  (> (count in-cols) 1)
                                  (assoc :match-on (mapv #(attr (:name %)) in-cols))))
            field-map (when api?
                        (into {} (for [c chosen]
                                   [(attr (:name c)) (or (:json-path c)
                                                         (str "$." (:name c)))])))
            ;; **The endpoint STORED is a template, the one PROBED was not**
            ;; (doc 106 §106.12 item 5 ②). A keyed api thing is fetched at
            ;; `/processors/{id}`, which cannot be called until a key is known;
            ;; the probe was given one real value, and what the model keeps is
            ;; the template with api-v1's attribute-qualified placeholder,
            ;; `/processors/{nifi.flow.processor/id}`. Qualifying it needs the
            ;; namespace and the key, which are decided here rather than by the
            ;; caller — the same reason no other id in a plan is spelled by a
            ;; caller. Without this the modeller had to `update_resolver` after
            ;; every single keyed api thing.
            ;; The key the stored template is qualified against: a child
            ;; collection hangs under the PARENT's key
            ;; (`/process-groups/{nifi.flow.group/id}/processors`), a by-key
            ;; thing under its own (doc 106 §106.12 item 7.1).
            ;; **One key per placeholder, in order** (owner 2026-09-07, doc 106
            ;; §106.14 item 14): `/namespaces/{ns}/pods/{name}` is a compound
            ;; key's path, and each `{…}` is qualified with its own part. A
            ;; single-key path has one of each and is unchanged.
            template-keys (if child?
                            (vec parents)
                            (mapv #(attr-kw ns-str (:name %)) in-cols))
            api-endpoint (let [e   (str (or (:endpoint api) (:path api)))
                               phs (vec (re-seq #"\{[^{}]*\}" e))]
                           (reduce (fn [acc [ph k]]
                                     ;; `+` says how the value is ENCODED
                                     ;; (api-v1 reserved expansion, doc 106
                                     ;; §106.14 item 8), not what it binds to,
                                     ;; so qualifying the name must carry the
                                     ;; marker across. Dropping it would
                                     ;; silently re-encode a slashed key value
                                     ;; back into one path segment.
                                     (str/replace acc ph
                                                  (str "{" (when (str/starts-with? ph "{+") "+")
                                                       (symbol k) "}")))
                                   e
                                   (map vector phs template-keys)))
            api-block (fn [] (cond-> {:type "api"
                                      :method (or (:method api) "GET")
                                      :endpoint api-endpoint
                                      :field_mapping field-map}
                               (seq (:params api)) (assoc :query_params (:params api))
                               (:data-path api) (assoc :data_path (:data-path api))))
            entry-base
            (cond
              child?
              {:id resolver-id
               ;; Anchored on the parent — no `:curated?`: D2.13 asks for a
               ;; signature on the list that converges on nothing, and this
               ;; one converges on the parent rows it was asked for.
               ;;
               ;; Plus what the CONNECTION binds (§106.14 item 11: «every
               ;; resolver on such a source gains this input»). A child
               ;; collection is the one keyed shape whose `:input` the caller
               ;; never names — it is the parent's key — so without this line
               ;; `/process-groups/{id}/processors` over a host-templated
               ;; source went out with `{nifi.instance/host}` unresolved and
               ;; every «has many» on such a source was dead on arrival
               ;; (measured rebuilding nifi-local v12). Only an attribute that
               ;; will exist is added; one that will not is refused above.
               ;; A compound parent key contributes EVERY part (§106.14 item
               ;; 14), so the whole key travels together into the child.
               :input (vec (distinct (concat parents
                                             (filter will-exist?
                                                     (distinct (vals bound-by-name))))))
               :output [{join-attr (mapv #(attr (:name %)) chosen)}]
               :result_type "list"
               :source (if api?
                         (api-block)
                         (let [fkn  (mapv :name fk-cols)
                               ph   (str (str/join "_" fkn) "s")
                               ;; A compound parent key compares as a ROW VALUE
                               ;; — `(a, b) IN ((?,?), …)` — so the batch is
                               ;; still ONE statement (doc 106 §106.14 item 14).
                               lhs  (if (= 1 (count fkn))
                                      (first fkn)
                                      (str "(" (str/join ", " fkn) ")"))]
                           {:type "sql"
                            :query (str "SELECT "
                                        (sql-select (reduce select-of chosen fkn))
                                        " FROM " relation
                                        " WHERE " lhs " IN (:" ph ")")
                            ;; D2.4: one statement per parent batch. Without this
                            ;; block the same model is a query per parent row.
                            :batch {:parent-key (if (= 1 (count parents))
                                                  (first parents)
                                                  (vec parents))
                                    :child-fk (if (= 1 (count fkn)) (first fkn) fkn)
                                    :placeholder ph}}))
               :source-ref source
               :limit default-limit}

              templated-root?
              ;; Keyed on what the connection binds, and on nothing else: the
              ;; instance IS the anchor here, so there is no `:curated?` (D2.13
              ;; asks for a signature on a list that converges on nothing, and
              ;; this one converges on the instance it was asked for).
              {:id resolver-id
               :input bound-attrs
               :output [{join-attr (mapv #(attr (:name %)) chosen)}]
               :result_type "list"
               :source (cond static? (static-block)
                             api?    (api-block)
                             :else   {:type "sql"
                                      :query (str "SELECT " (sql-select chosen)
                                                  " FROM " relation)})
               :source-ref source
               :limit default-limit}

              collection?
              {:id resolver-id
               ;; D2.13: an unanchored list carries the owner's ruling. The
               ;; caller asking for one IS that ruling — the same act
               ;; `retired source generator` records.
               :curated? true
               :input []
               :output [{join-attr (mapv #(attr (:name %)) chosen)}]
               :result_type "list"
               :source (cond static? (static-block)
                             api?    (api-block)
                             :else   {:type "sql"
                                      :query (str "SELECT " (sql-select chosen)
                                                  " FROM " relation)})
               :source-ref source
               :limit default-limit}

              :else
              {:id resolver-id
               :input input-attrs
               :output (mapv #(attr (:name %)) chosen)
               :result_type "single"
               :source
               (cond
                 static? (static-block)
                 api?    (api-block)
                 :else
                 ;; **A compound key is one row-value comparison**, and stays
                 ;; batchable: `WHERE (a, b) IN ((?,?), …)` (owner 2026-09-07,
                 ;; doc 106 §106.14 item 14). The AND-equality form is kept for
                 ;; a multi-input resolver that is NOT the thing's key — those
                 ;; are reached one anchor at a time and have no batch to run.
                 (let [ins       (mapv :name in-cols)
                       id-names  (set (map name (or identity-columns [])))
                       compound? (and (> (count ins) 1)
                                      (= (set ins) id-names))
                       ph        (str (str/join "_" ins) "s")
                       lhs       (if (= 1 (count ins))
                                   (first ins)
                                   (str "(" (str/join ", " ins) ")"))]
                   (cond-> {:type "sql"
                            :query (if (or compound? (= 1 (count ins)))
                                     (str "SELECT "
                                          (sql-select (reduce select-of chosen ins))
                                          " FROM " relation
                                          " WHERE " lhs " IN (:" ph ")")
                                     (str "SELECT " (sql-select chosen) " FROM " relation
                                          " WHERE "
                                          (str/join " AND " (map #(str % " = :" %) ins))))}
                     (= 1 (count ins))
                     (assoc :batch {:parent-key (attr (first ins))
                                    :child-fk (first ins)
                                    :placeholder ph})
                     compound?
                     (assoc :batch {:parent-key (mapv attr ins)
                                    :child-fk ins
                                    :placeholder ph}))))
               :source-ref source})
            eql-params (filters->eql-params ns-str filters)
            ;; static-v1 has no connection entry: the rows ARE the model, so
            ;; a `:source-ref` would point at a source that does not exist.
            entry (cond-> entry-base
                    static? (dissoc :source-ref)
                    eql-params (assoc :eql-params eql-params))
            findings
            (vec (concat
                  ;; Every part of a compound parent key is checked (doc 106
                  ;; §106.14 item 14); one key is the same single check.
                  (for [pk parents
                        :when (nil? (get-in model [:attributes pk]))]
                    (f/make :tools.intent/name-unresolved :error [:parent]
                            "the pool holds no attribute under this id"
                            :got (str (symbol pk))))
                  ;; api has no such statement to write: the endpoint under the
                  ;; parent already says which parent these rows hang under
                  ;; (need-grammar-v1 B1-api), so the child's own field is
                  ;; optional there and required over sql.
                  (when (and child? (not api?)
                             (not= (count fk-cols) (count fk-names)))
                    [(f/make :tools.intent/unknown-column :error [:foreign_key]
                             (str "a child collection is keyed on the child's own column pointing "
                                  "at the parent; this relation carries no typeable column by "
                                  "that name")
                             :got (vec (remove (set (map :name fk-cols)) fk-names))
                             :expected (mapv :name typed))])
                  (when (seq missing)
                    [(f/make :tools.intent/unknown-column :error [:outputs]
                             "this relation carries no typeable column by that name"
                             :got (vec (sort missing))
                             :expected (mapv :name typed))])
                  (when (seq undeclared-bound)
                    [(f/make :tools.intent/name-unresolved :error [:source]
                             (str "this source is reached at a host it takes from an attribute, "
                                  "and nothing declares that attribute yet; say what that thing "
                                  "is before reading anything under it")
                             :got (mapv #(str (symbol %)) undeclared-bound)
                             :refs ["api-v1 :base_url placeholders"
                                    "doc 106 §106.14 item 11"])])
                  (when (seq bad-in)
                    [(f/make :tools.intent/unknown-column :error [:input]
                             "this relation carries no typeable column by that name"
                             :got (vec (sort bad-in))
                             :expected (mapv :name typed))])
                  (when (and (empty? chosen) (empty? missing))
                    [(f/make :tools.intent/type-undecidable :error [:relation]
                             (str "no column of this relation has a dialect type the machine may "
                                  "assert, so there is nothing it can register")
                             :got (mapv :name untyped) :refs ["D2.14"])])
                  (for [c untyped]
                    (f/make :tools.intent/type-undecidable :warning [:relation relation (:name c)]
                            "this column's class has no dialect type; it is left out rather than guessed at"
                            :got (str (:class c)) :refs ["D2.14"]))
                  ;; An id already taken by the resolver THIS SENTENCE wrote
                  ;; is not a name clash — it is the sentence being said again
                  ;; (doc 84 §8.4.25), and `fold-said-before` answers it on the
                  ;; finished plan. Saying «that name is taken» here would send
                  ;; the caller renaming a thing they already have. Any other
                  ;; resolver holding the id is the clash it always was.
                  (when (and (some #(= resolver-id (:id %)) (:resolvers model))
                             (not (said-before model entry)))
                    [(f/make :tools.intent/name-ambiguous :error [:resolver]
                             "this graph already holds a resolver with the id this plan would create"
                             :got resolver-id)])
                  ;; 8.4.19 裁定十三 ②: a plan never produces a shape the
                  ;; engine drops. Last in the list because it judges the
                  ;; entry the branches above assembled.
                  (compile-shape-findings entry [:resolvers resolver-id :output])))
            actions
            (when-not (errors? findings)
              (vec (concat
                    (when-not existing-ns
                      [(action :create :namespace ns-str {} 0)])
                    (for [c new-attrs]
                      (action :create :attribute (attr (:name c))
                              (cond-> {:type (get class->type (:class c))}
                                ;; A1 (need-grammar-v1 §3): the column the
                                ;; person named as the thing's identity is
                                ;; marked whether or not the source declares a
                                ;; primary key — an api endpoint declares none,
                                ;; and «用 id 认» is the sentence they said.
                                (or (contains? (set (:primary-key rel)) (:name c))
                                    (contains? (set (map name (or identity-columns [])))
                                               (:name c)))
                                (assoc :identity? true))
                              0))
                    (when (and (or collection? child?)
                               (not (get-in model [:attributes join-attr])))
                      ;; D2.29: the edge IS one join attribute. For a child
                      ;; collection it hangs on the PARENT and targets the
                      ;; child's namespace; for the unanchored set both ends
                      ;; are the same namespace.
                      [(action :create :attribute join-attr
                               {:target ns-str :cardinality :one-to-many}
                               0)])
                    [(action :create :resolver resolver-id entry 0)])))]
        (cond->
         {:actions (vec actions)
         ;; 8.4.22 裁定十六 ②: a list-shaped api interface pages, and neither
         ;; the page size nor the upstream's names for it can be read from one
         ;; sample. They are `:fill` positions rather than guesses — the
         ;; reserved names of api-v1's eql-param block are the starting point.
         :fill (if (and api? (or collection? child?))
                 [[:resolver "limit" (str default-limit)]
                  [:source "pagination" "_limit / _offset (api-v1 eql-param :field)"]]
                 [])
         :recipe-delta (-> empty-delta
                           (delta-add :namespaces (when-not existing-ns [ns-str]))
                           (delta-add :attributes
                                      (cond-> (mapv #(attr (:name %)) chosen)
                                        (or collection? child?) (conj join-attr))))
         :created {:resolver resolver-id
                   :attributes (vec (sort (cond-> (mapv #(attr (:name %)) chosen)
                                            (or collection? child?) (conj join-attr))))
                   :namespace ns-str}
         :findings findings}
          ;; What the summary needs and the actions cannot show: this list is
          ;; «all the X of ONE instance», not the root set (item 9.6). A child
          ;; collection's entry and this one have the same shape, so the
          ;; distinction is carried here rather than guessed at — and the key
          ;; is ABSENT for every other shape, so a plan that was not this is
          ;; byte-identical to before.
          templated-root?
          (assoc :bound-collection {:resolver resolver-id :owner owner-ns
                                    :of (str/replace join-name #"^all_" "")}))))))

;; ---------------------------------------------------------------------------
;; add_attribute
;; ---------------------------------------------------------------------------

(def resolver-relation
  "The relation a sql resolver reads — `impl.repair`'s, because the repair of
   an anchorless list reads the same clause and 8.4.21 裁定十五 ① allows the
   inference exactly one home (the require runs this way round because
   `impl.repair` is loaded before this ns)."
  repair/resolver-relation)

(defn- output-namespace
  "The namespace a resolver's outputs live in. A resolver whose outputs
   straddle namespaces has no single answer, and the caller is asked.

   Only what the resolver produces DIRECTLY counts — the top-level scalar
   leaves and the branch keys themselves — which is `dialect/direct-output-keys`,
   the same rule P8a's «produced-twice» applies. Leaves nested under a branch
   are the child's own attributes and belong to the child's namespace: counting
   them meant that a thing which had gained §106.14 item 5's `{A/b [B/k]}`
   branch could never take another field again, since its by-key resolver now
   straddled two namespaces (doc 106 §106.12 item 5 ①, measured 2026-09-06)."
  [entry]
  (let [nss (into #{} (keep namespace)
                  (dialect/direct-output-keys (row-tree (:output entry))))]
    (when (= 1 (count nss)) (first nss))))

(defn- append-select
  "Add one column to a SELECT list. Textual, and deliberately so: the resolver
   holds a statement, not a parsed query, and rewriting it through a parser
   would be this layer holding an opinion about SQL that sql-v1 holds
   already. A statement it cannot read is refused, never rewritten blind."
  [q column]
  (let [m (re-find #"(?is)^(\s*SELECT\s+)(.+?)(\s+FROM\s+.*)$" q)]
    (when m
      (str (nth m 1) (nth m 2) ", " column (nth m 3)))))

(defn plan-add-attribute
  "One more field on a resolver that already exists.

   `relation` names the structure `description` describes, for the callers that
   know it: an api resolver reads an ENDPOINT and `resolver-relation` — which
   reads a SELECT's FROM clause — has nothing to answer with, so the second
   source's fields could not be typed from the probe (doc 106 §106.12 item
   8.1). Omitted, the statement still answers, which is every sql caller."
  [db {:keys [model description]} {:keys [resolver attribute column type label description*
                                          values relation]}]
  (let [entry (some #(when (= resolver (:id %)) %) (:resolvers model))
        rev   (revision-of db :resolver resolver)
        ns-str (output-namespace entry)
        col    (or column attribute)
        rel    (or relation (resolver-relation entry))
        ;; Matched by the COLUMN, and — when the column was written as a
        ;; JSONPath — by the attribute's own name, which is the name the probe
        ;; was asked to type that path under (doc 106 §106.12 item 10.3, the
        ;; same `{name path}` device `add_thing` already uses). Without this
        ;; the mapping was written and the type was not, so a JSONPath column
        ;; was refused `:type-undecidable` unless the caller stated the type.
        cols*   (when description (:columns (relation-of description (or rel ""))))
        src-col (when cols*
                  (or (some #(when (= col (:name %)) %) cols*)
                      (some #(when (= (name attribute) (:name %)) %) cols*)))
        type*  (or type (get class->type (:class src-col)))
        attr   (when ns-str (attr-kw ns-str attribute))
        q      (get-in entry [:source :query])
        new-q  (when q (append-select q col))
        ;; An api resolver reads a field by JSONPath, so a field whose name
        ;; differs from the record's key is only reachable through
        ;; `:field_mapping` — registering the attribute without it left the
        ;; mapping to a second `update_resolver` call (doc 106 §106.12
        ;; item 5 ④). A `column` already spelled as a path is taken as one.
        api-entry? (= "api" (some-> (get-in entry [:source :type]) name))
        new-map (when (and api-entry? attr)
                  (assoc (get-in entry [:source :field_mapping] {})
                         attr (if (str/starts-with? (str col) "$") (str col) (str "$." col))))
        findings
        (vec (concat
              (when (nil? ns-str)
                [(f/make :tools.intent/namespace-undecidable :error [:resolver resolver]
                         (str "this resolver's outputs straddle namespaces, so which one the new "
                              "attribute belongs to is not the machine's to pick")
                         :got (vec (sort (keep namespace
                                               (dialect/direct-output-keys
                                                (row-tree (:output entry)))))))])
              (when (nil? type*)
                [(f/make :tools.intent/type-undecidable :error [:type]
                         (str "the source structure does not give this column a dialect type; "
                              "state it")
                         :expected (mapv str (sort (keys class->type)))
                         :refs ["D2.14"])])
              (when (and q (nil? new-q))
                [(f/make :tools.intent/unknown-column :error [:resolver resolver]
                         (str "this resolver's statement has no SELECT list this layer can extend; "
                              "add the column to it with execute_action")
                         :refs ["sql-v1"])])
              (when (and attr (get-in model [:attributes attr]))
                [(f/make :tools.intent/name-ambiguous :error [:attribute]
                         "the pool already holds an attribute under this id"
                         :got (str (symbol attr)))])))
        actions
        (when-not (errors? findings)
          [(action :create :attribute attr
                   (cond-> {:type type*}
                     label (assoc :label label)
                     description* (assoc :description description*)
                     ;; A2's local code list (need-grammar-v1 §3): the values
                     ;; belong to THIS column. A vocabulary shared across
                     ;; things is a concept instead — C3, on the term.
                     (seq values) (assoc :values values))
                   0)
           (action :update :resolver resolver
                   (-> entry
                       ;; A row's own field goes beside the row's other
                       ;; fields — inside the join root when there is one
                       ;; (D2.16), never beside the collection carrying them.
                       (assoc :output (with-row-branch (:output entry) attr))
                       (cond-> new-q (assoc-in [:source :query] new-q))
                       (cond-> new-map (assoc-in [:source :field_mapping] new-map)))
                   rev)])]
    {:actions (vec actions)
     ;; D2.26: the machine does not name a concept. A label the caller did not
     ;; send is a position for a person, listed and NOT blocking — an absent
     ;; label is a legal dialect state, and the enrichment pass is where it is
     ;; filled (`describe_objects`).
     :fill (vec (concat (when-not label [[:attribute attr :label]])
                        (when-not description* [[:attribute attr :description]])))
     :recipe-delta (delta-add empty-delta :attributes (when attr [attr]))
     :created {:attribute attr :resolver resolver}
     :findings findings}))

;; ---------------------------------------------------------------------------
;; add_link
;; ---------------------------------------------------------------------------

(defn plan-add-link
  "D2.3: a link is stated from the «one» end, its identity is the endpoint
   pair, and a `:bi` link normalizes its two ends in lexicographic order —
   that normalization is what stops A↔B and B↔A becoming two links for one
   relationship."
  [db {:keys [model]} {:keys [from to direction cardinality]}]
  (let [bi? (not= :one direction)
        [a b] (if bi? (vec (sort-by (comp str symbol) [from to])) [from to])
        id [a b]
        entry (cond-> {:from a :to b :direction (if bi? :bi :one-way)}
                cardinality (assoc :cardinality cardinality))
        findings
        (vec (concat
              (when (= from to)
                [(f/make :tools.intent/name-ambiguous :error [:to]
                         "a link's two ends are two different attributes"
                         :got (str (symbol from)))])
              (when (some #(= id [(:from %) (:to %)]) (:links model))
                [(f/make :tools.intent/name-ambiguous :error [:from]
                         "this pool already holds a link between these two attributes"
                         :got (mapv (comp str symbol) id))])))]
    {:actions (if (errors? findings) [] [(action :create :link id entry 0)])
     :fill []
     ;; A link is pulled into a projection by closure once both its endpoints
     ;; are in (scope-projection-v1 §2.6), so naming it in a recipe would add
     ;; nothing a cut reads. 8.4.14 says so in the same words.
     :recipe-delta empty-delta
     :created {:link id}
     :findings findings}))


;; ---------------------------------------------------------------------------
;; add_relation, the «belongs to / has one» half (need-grammar-v1 B2 / B4)
;; ---------------------------------------------------------------------------

(defn- fk-path-of
  "Where the parent's identity is read from in THIS resolver: the same place
   the child's own foreign-key field is read from.

   sql answers a column name, api a JSONPath, and both already sit in the
   entry — declared in `:field_mapping`, or the D2.1 identity default (the
   attribute's local name). Reading it off the entry is why no caller has to
   spell it: the fk is already produced by this resolver, which is the
   precondition this planner checks."
  [entry via]
  (let [declared (get-in entry [:source :field_mapping])]
    (or (some-> (get declared via) name)
        (if (= "api" (get-in entry [:source :type]))
          (str "$." (name via))
          (name via)))))

(defn- produces?
  "Does this resolver produce the child's foreign-key field(s) in each row?
   `{A/b [B/k …]}` is filled from the row the resolver already reads, so a
   resolver that does not read EVERY fk cannot carry the branch — a compound
   key filled from half its columns points at no parent (owner 2026-09-07,
   doc 106 §106.14 item 14)."
  [entry vias]
  (let [leaves (leaf-attrs (row-tree (:output entry)))]
    (every? #(contains? leaves %) vias)))

(defn- self-relation?
  "Parent and child in ONE namespace — «every group belongs to a parent
   group». The branch's leaf is then the SAME attribute kw as the resolver's
   own identity output (doc 106 §106.12 item 7.5)."
  [join-attr parent]
  (= (clojure.core/namespace join-attr) (clojure.core/namespace parent)))

(defn- attach-belongs-to
  "One resolver entry -> the same entry with the `{A/b [B/k]}` branch and the
   fk -> `B/k` mapping, or nil when it already carries it (106.14 item 5).

   **A self relation gets a branch-scoped mapping** (§106.14 item 6, owner
   2026-09-07): one flat row cannot hold two values under one attribute kw, so
   `{A/parent {A/k \"…parentGroupId\"}}` gives the branch its own scope and the
   top-level identity mapping is left untouched. Writing it flat is what made
   the later write shadow the identity and answer every group's own id with
   its parent's."
  [entry join-attr parents vias]
  (when-not (contains? (leaf-attrs (:output entry)) join-attr)
    ;; **Every part of the parent's key is a leaf of the branch**, mapped from
    ;; its own fk column (owner 2026-09-07, doc 106 §106.14 item 14): a branch
    ;; that named half a compound key would leave the child's `{A/b}` unusable
    ;; as an anchor into the parent's by-key resolver.
    (let [declared (get-in entry [:source :field_mapping] {})
          pairs    (map vector parents vias)
          self?    (self-relation? join-attr (first parents))]
      (-> entry
          (assoc :output (with-row-branch (:output entry) {join-attr (vec parents)}))
          (assoc-in [:source :field_mapping]
                    (if self?
                      (assoc declared join-attr
                             (into {} (for [[pk via] pairs]
                                        [pk (fk-path-of entry via)])))
                      (reduce (fn [m [pk via]] (assoc m pk (fk-path-of entry via)))
                              declared pairs)))))))

(defn plan-add-relation-one
  "The child's ONE join attribute at the parent (need-grammar-v1 §3 B2/B4).

   B2 rides along with B1's collection; B4 states it ALONE — «每个 A 有一个 B»
   is a reference, not a membership, and the reverse «B has many A» is a
   different sentence which B4 deliberately does not say (doc 106 §106.3 B4:
   an instance has a root group, but a group does not have many instances).

   The attribute hangs on the CHILD namespace — that is where the sentence
   starts — and targets the parent's namespace with `:cardinality :one-to-one`
   (D2.29: the edge IS one join attribute).

   **It is PRODUCED by the child's own resolvers** (doc 106 §106.14 item 5,
   owner 2026-09-06): every resolver that reads the child's rows gains the
   branch `{A/b [B/k]}` and the mapping from the foreign key to `B/k`. This is
   D2.16 (output is a tree) applied to D2.29 (a branch IS an attribute): the
   parent's identity value is already in the child's row, so the engine walks
   into the parent's by-key resolver with no second read. Without it the
   attribute is produced by nobody and the cut refuses the graph with
   `cut.attribute/unregistered-use` (D2.2) — the measurement that
   need-grammar-v1 §5.1 now states as the canonical form.

   `pending` carries entries the SAME sentence is creating (B1's child
   collection), which are amended and returned under `:amended` rather than
   updated: one sentence is one write."
  [db {:keys [model]} {:keys [parent via name via-pending? pending]}]
  ;; `parent` and `via` may each be a LIST — a compound parent key, paired
  ;; position by position (owner 2026-09-07, doc 106 §106.14 item 14). One key
  ;; reads back as a one-element vector, so the plan below is written once.
  (let [parents   (cond (nil? parent) [] (sequential? parent) (vec parent) :else [parent])
        vias      (cond (nil? via) [] (sequential? via) (vec via) :else [via])
        parent-ns (when (seq parents) (clojure.core/namespace (first parents)))
        child-ns  (when (seq vias) (clojure.core/namespace (first vias)))
        join-name (or (some-> name clojure.core/name)
                      (when parent-ns (last (str/split parent-ns #"\."))))
        join-attr (when (and child-ns join-name) (attr-kw child-ns join-name))
        ;; Deterministic order (need-grammar-v1 §2 invariant 1): the pool's
        ;; resolvers by id, then the ones this sentence is creating.
        pool-hits (when join-attr
                    (filterv #(produces? % vias) (sort-by :id (:resolvers model))))
        pend-hits (when join-attr (filterv #(produces? % vias) (vec pending)))
        amended   (into {} (for [e pend-hits
                                 :let [e' (attach-belongs-to e join-attr parents vias)]
                                 :when e']
                             [(:id e') e']))
        updates   (vec (for [e pool-hits
                             :let [e' (attach-belongs-to e join-attr parents vias)]
                             :when e']
                         (action :update :resolver (:id e) e'
                                 (revision-of db :resolver (:id e)))))
        findings
        (vec (concat
              (for [pk parents
                    :when (nil? (get-in model [:attributes pk]))]
                (f/make :tools.intent/name-unresolved :error [:parent]
                        "the pool holds no attribute under this id"
                        :got (some-> pk symbol str)))
              ;; `via-pending?`: the child's own field is being created by the
              ;; SAME sentence (B1 registers the child's columns), so it is not
              ;; in the pool yet and its absence is not a defect.
              (when-not via-pending?
                (for [v vias
                      :when (nil? (get-in model [:attributes v]))]
                  (f/make :tools.intent/name-unresolved :error [:via]
                          "the pool holds no attribute under this id"
                          :got (some-> v symbol str))))
              ;; **Unless it is the one THIS sentence wrote** (doc 84
              ;; §8.4.25): «every customer has many orders» said twice must
              ;; reach `fold-said-before` with its two halves assembled, and
              ;; refusing here left the resolver half unamended — so the
              ;; repeat compared as a difference in `:output` and `:source`
              ;; and read as a conflict. The test is the same one
              ;; `retired source generator` applies: there, and equal over the keys this
              ;; generator writes.
              (when (and join-attr
                         (let [e (get-in model [:attributes join-attr])]
                           (and e (not= (select-keys e [:target :cardinality])
                                        {:target parent-ns :cardinality :one-to-one}))))
                [(f/make :tools.intent/name-ambiguous :error [:name]
                         "the pool already holds an attribute under the id this plan would create"
                         :got (str (symbol join-attr))
                         :refs ["D2.29"])])
              ;; 106.14 item 5: a join attribute nobody produces is refused at
              ;; the cut (D2.2). Say the thing first — this key is read out of
              ;; the child's own rows, so there must be a way of fetching them
              ;; that reads the field pointing at the parent.
              (when (and join-attr (empty? pool-hits) (empty? pend-hits))
                [(f/make :tools.intent/name-unresolved :error [:via]
                         (str "nothing fetches this thing in a way that reads the field "
                              "pointing at the parent, so no resolver could produce this key "
                              "and the graph would not publish; say how the thing is fetched "
                              "first (add_thing / add_attribute)")
                         :got (mapv #(str (symbol %)) vias)
                         :refs ["need-grammar-v1 B2" "D2.2" "106.14 第 5 条"])])))
        actions (when-not (errors? findings)
                  ;; The join attribute is created only when it is not already
                  ;; there; a repeat of this sentence amends and creates
                  ;; nothing (doc 84 §8.4.25).
                  (into (if (get-in model [:attributes join-attr])
                          []
                          [(action :create :attribute join-attr
                                   {:target parent-ns :cardinality :one-to-one}
                                   0)])
                        updates))]
    {:actions (vec actions)
     :amended (if (errors? findings) {} amended)
     :fill []
     ;; The parent's identity is now an `:output` leaf of a child resolver, so
     ;; the cut needs it in the selection too (scope-projection-v1 §4:
     ;; `:scope/mixed-output` is what an unlisted leaf earns).
     :recipe-delta (delta-add empty-delta :attributes
                              (when join-attr (into [join-attr] parents)))
     :created {:attribute join-attr}
     :findings findings}))

(defn api-lookup-of
  "The api operation block a thing is fetched BY ITS OWN KEY with, and the
   source it reads. An in-record collection is read from the very same request
   as the host record — that is what «in its own record» means — so no second
   endpoint is asked of the caller: the endpoint is already written on the
   parent's own lookup, and it is that record which is read once when the
   child's fields have to be typed (doc 106 §106.12 item 10.4)."
  [model id-attr]
  ;; The key is AMONG its inputs, not the first of them: over a host-templated
  ;; source every resolver also takes the URL's attribute (§106.14 item 11),
  ;; and a thing recognised by `[host id]` (item 17) puts that one first — a
  ;; positional test then found no lookup at all (`existing-source-resolver`
  ;; had already been corrected the same way, §106.12 item 9.3).
  (first (sort-by :id (filter #(and (some #{id-attr} (:input %))
                                    (= "single" (:result_type %))
                                    (= "api" (get-in % [:source :type])))
                              (:resolvers model)))))

(defn- branch-producers
  "Resolvers whose `:output` already carries this join attribute — «the same
   collection, said twice». Named in the refusal rather than counted: the
   caller's next move is to look at THAT resolver."
  [model join-attr]
  (filterv #(contains? (leaf-attrs (:output %)) join-attr)
           (sort-by :id (:resolvers model))))

(defn plan-add-in-record-collection
  "«每个 B 有很多 A，就在它自己的记录里» — need-grammar-v1 B1, the third
   implementation (owner 2026-09-07, doc 106 §106.14 item 15).

   The rows are an ARRAY inside the parent's own record (`$.processors[*]`),
   so there is no endpoint of their own: the plan
   reuses the parent's by-key operation block and adds one branch to its
   `:field_mapping` — `{B/as {:_from \"$.processors[*]\" A/id \"$.id\"}}`,
   whose leaves are read against ONE element (api-v1 «记录内集合»).

   The child's fields come from the parent's own sampled record when one was
   read (`description`, doc 106 §106.12 item 10.4): the elements of the array
   ARE the child's rows, so they type it exactly as a probe types any other
   thing — which is what removes the scaffold `add_thing` the v13 rebuild had
   to say and then delete. Without a sample they are the fields the child is
   ALREADY registered with; either way `attributes` narrows, and a field with
   no evidence for a type is refused rather than guessed at (D2.14).

   No `:limit`: the cardinality is fixed by the host record, which is the
   D2.8 exemption this shape earns (§106.14 item 15)."
  [db {:keys [model description]} {:keys [parent child-ns from name attributes
                                          filters url-bound]}]
  (let [parent-ns (some-> parent clojure.core/namespace)
        join-name (or (some-> name clojure.core/name)
                      (last (str/split (str child-ns) #"\.")))
        join-attr (keyword parent-ns join-name)
        host      (api-lookup-of model parent)
        child-rel (last (str/split (str child-ns) #"\."))
        ;; The sampled ELEMENTS of the array, typed (item 10.4). `:typed` only:
        ;; a field the sample disagreed about is reported, never asserted
        ;; (D2.14, the same table every other probe reads).
        probed    (when description
                    (:typed (typed-columns (relation-of description child-rel))))
        registered (vec (sort (for [[k v] (:attributes model)
                                    :when (and (= child-ns (clojure.core/namespace k))
                                               (not (contains? v :target)))]
                                (clojure.core/name k))))
        known     (if probed (vec (sort (map :name probed))) registered)
        named     (when (seq attributes) (normalize-outputs attributes))
        chosen    (if named named (mapv (fn [n] {:name n :path nil}) known))
        ;; **A `{name path}` entry says where its value is, and is matched
        ;; THERE** (doc 106 §106.12 item 7.2's ruling, applied to this branch
        ;; too): the path is the evidence, the name is only what the attribute
        ;; will be called. Matching a renamed field by its NAME made
        ;; `{"name": "auto_terminate", "path": "$.autoTerminate"}` an
        ;; `unknown-column` — true of the element's keys and beside the point,
        ;; since the caller never claimed the element carries that name
        ;; (measured rebuilding nifi-local v15).
        probe-of  (fn [c] (when probed
                            (or (when (:path c)
                                  (some #(when (= (:path c) (:json-path %)) %) probed))
                                (some #(when (= (:name c) (:name %)) %) probed))))
        unknown   (when named
                    (vec (sort (keep (fn [c]
                                       (when-not (if probed
                                                   (probe-of c)
                                                   (contains? (set known) (:name c)))
                                         (:name c)))
                                     named))))
        leaves    (mapv #(attr-kw child-ns (:name %)) chosen)
        ;; A probed column already knows the JSONPath it was read at, relative
        ;; to one element; a caller's `{name path}` wins over it, as everywhere.
        field-map (into (sorted-map)
                        (for [c chosen]
                          [(attr-kw child-ns (:name c))
                           (or (:path c) (:json-path (probe-of c))
                               (str "$." (:name c)))]))
        existing-ns (boolean (get-in model [:namespaces child-ns]))
        new-attrs (vec (for [c (sort-by :name chosen)
                             :let [a (attr-kw child-ns (:name c))]
                             :when (nil? (get-in model [:attributes a]))]
                         c))
        eql-params (filters->eql-params child-ns filters)
        producers (branch-producers model join-attr)
        resolver-id (str (ns-slug parent-ns) "_" join-name)
        entry (when host
                (cond->
                 {:id resolver-id
                 ;; The parent's key, plus whatever the CONNECTION binds
                 ;; (§106.14 item 11) — the request is the parent's request.
                 :input (vec (distinct (concat [parent]
                                               (filter #(get-in model [:attributes %])
                                                       (or url-bound [])))))
                 :output [{join-attr leaves}]
                 :result_type "list"
                 :source (assoc-in (:source host) [:field_mapping join-attr]
                                   (into (sorted-map) (cons [:_from from] field-map)))
                 :source-ref (:source-ref host)}
                ;; item 10.2: the block reaches `:eql-params` here exactly as
                ;; it does on the endpoint-backed branch. Dropping it silently
                ;; left the narrowing to be discovered at query time, as
                ;; `unknown-param` — a refusal that names neither the sentence
                ;; nor the parameter that was thrown away.
                 eql-params (assoc :eql-params eql-params)))
        findings
        (vec (concat
              (when (nil? (get-in model [:attributes parent]))
                [(f/make :tools.intent/name-unresolved :error [:parent]
                         "the pool holds no attribute under this id"
                         :got (some-> parent symbol str))])
              (when (nil? host)
                [(f/make :tools.intent/name-unresolved :error [:parent]
                         (str "a collection that sits inside a record is read by the request "
                              "that reads the record, and nothing fetches this thing from an "
                              "API by its own key yet; say how the thing is fetched first "
                              "(add_thing)")
                         :got (some-> parent symbol str)
                         :refs ["need-grammar-v1 B1" "doc 106 §106.14 item 15"])])
              (when (seq unknown)
                [(f/make :tools.intent/unknown-column :error [:attributes]
                         (str "these are not among the fields the elements of this array "
                              "carry")
                         :got unknown :expected known
                         :refs ["D2.14" "doc 106 §106.14 item 15"])])
              (when (and (empty? chosen) (empty? unknown))
                [(f/make :tools.intent/name-unresolved :error [:sample]
                         (str "nothing is known about this thing's fields yet; give one real "
                              "value of the parent's key and the parent's own record is read "
                              "once, so the elements of this array can be typed")
                         :got (str child-ns)
                         :refs ["doc 106 §106.12 item 10.4" "doc 106 §106.14 item 15"])])
              ;; One producer per branch (§106.14 item 15): the same collection
              ;; read once from the record and once from an endpoint of its own
              ;; is two answers to one question, and the cut has no way to
              ;; prefer either.
              (when (seq producers)
                [(f/make :tools.intent/name-ambiguous :error [:name]
                         "this collection is already produced by another resolver"
                         :got (mapv :id producers)
                         :refs ["doc 106 §106.14 item 15"])])
              (when (and (empty? producers) (get-in model [:attributes join-attr]))
                [(f/make :tools.intent/name-ambiguous :error [:name]
                         "the pool already holds an attribute under the id this plan would create"
                         :got (str (symbol join-attr))
                         :refs ["D2.29"])])
              (when (some #(= resolver-id (:id %)) (:resolvers model))
                [(f/make :tools.intent/name-ambiguous :error [:resolver]
                         "this graph already holds a resolver with the id this plan would create"
                         :got resolver-id)])
              (when entry (compile-shape-findings entry [:resolvers resolver-id :output]))))
        actions (when-not (errors? findings)
                  (vec (concat
                        (when-not existing-ns
                          [(action :create :namespace child-ns {} 0)])
                        ;; The class comes from the PROBED column: `chosen`
                        ;; carries the caller's `{name path}` shape and knows
                        ;; nothing about types.
                        (for [c new-attrs]
                          (action :create :attribute (attr-kw child-ns (:name c))
                                  {:type (get class->type
                                              (:class (or (probe-of c) c)))}
                                  0))
                        [(action :create :attribute join-attr
                                 {:target child-ns :cardinality :one-to-many} 0)
                         (action :create :resolver resolver-id entry 0)])))]
    {:actions (vec actions)
     ;; 裁定七: everything a probe inferred is a guess and waits for a person.
     :fill (if probed
             (vec (for [c new-attrs]
                    [:attribute (:name c) :type
                     (get-in (or (probe-of c) c) [:inferred :from])]))
             [])
     :recipe-delta (-> empty-delta
                       (delta-add :namespaces (when-not existing-ns [child-ns]))
                       (delta-add :attributes (cons join-attr leaves)))
     :created {:resolver resolver-id :attribute join-attr
               :namespace child-ns
               :attributes (vec (sort leaves))}
     :findings findings}))

(defn merge-relation-plans
  "B1's plan and the «belongs to» half of the SAME sentence -> one plan.

   Two applies for one sentence is the half-applied state every intent tool
   refuses to create (8.4.14), so the branch the second half adds is folded
   into the resolver the first half is still only PLANNING to create."
  [base one]
  (let [amended (:amended one)
        actions (mapv (fn [a]
                        (if-let [e (and (= :resolver (:type a)) (get amended (:id a)))]
                          (assoc a :entry e)
                          a))
                      (vec (:actions base)))]
    (-> base
        (assoc :actions (into actions (:actions one)))
        (assoc :fill (into (vec (:fill base)) (:fill one)))
        (assoc :findings (into (vec (:findings base)) (:findings one)))
        (assoc :recipe-delta
               (merge-with (fn [x y] (merge-with into x y))
                           (:recipe-delta base) (:recipe-delta one)))
        (update-in [:created :attributes]
                   (fn [as] (vec (sort (conj (set as) (:attribute (:created one))))))))))

;; ---------------------------------------------------------------------------
;; add_attribute, the computed branch (need-grammar-v1 A3)
;; ---------------------------------------------------------------------------

(defn plan-add-computed-attribute
  "«X 有个算出来的属性 c，由 a、b 算» (A3) — one derived resolver plus the
   attribute it produces.

   A derived resolver carries no `:source-ref`: derived-v1 computes from the
   inputs already on the record and reaches no source at all, which is why
   this sentence needs no `from`."
  [db {:keys [model]} {:keys [attribute type inputs expression label description]}]
  (let [ns-str (clojure.core/namespace attribute)
        rid (str "derived_" (ns-slug ns-str) "_" (clojure.core/name attribute))
        entry {:id rid
               :input (vec inputs)
               :output [attribute]
               :result_type "single"
               :source {:type "derived" :expr (str expression)}}
        unknown (vec (remove #(get-in model [:attributes %]) inputs))
        findings
        (vec (concat
              (when (nil? type)
                [(f/make :tools.intent/type-undecidable :error [:type]
                         (str "a computed attribute has no source column to read a type from, "
                              "so its type is stated rather than inferred")
                         :expected (mapv str (sort (keys class->type)))
                         :refs ["D2.14"])])
              (when (empty? inputs)
                [(f/make :tools.intent/name-unresolved :error [:computed_from]
                         "a computed attribute is computed FROM something; name the attributes it reads")])
              (when (str/blank? (str expression))
                [(f/make :tools.intent/type-undecidable :error [:expression]
                         "there is no expression to compute this attribute with")])
              (for [a unknown]
                (f/make :tools.intent/name-unresolved :error [:computed_from]
                        "the pool holds no attribute under this id"
                        :got (str (symbol a))))
              (when (get-in model [:attributes attribute])
                [(f/make :tools.intent/name-ambiguous :error [:name]
                         "the pool already holds an attribute under this id"
                         :got (str (symbol attribute)))])
              (when (some #(= rid (:id %)) (:resolvers model))
                [(f/make :tools.intent/name-ambiguous :error [:name]
                         "this graph already holds a resolver with the id this plan would create"
                         :got rid)])))
        actions
        (when-not (errors? findings)
          [(action :create :attribute attribute
                   (cond-> {:type type}
                     label (assoc :label label)
                     description (assoc :description description))
                   0)
           (action :create :resolver rid entry 0)])]
    {:actions (vec actions)
     :fill (vec (concat (when-not label [[:attribute attribute :label]])
                        (when-not description [[:attribute attribute :description]])))
     :recipe-delta (delta-add empty-delta :attributes [attribute])
     :created {:attribute attribute :resolver rid}
     :findings findings}))

;; ---------------------------------------------------------------------------
;; add_metric (need-grammar-v1 A4, D2.21)
;; ---------------------------------------------------------------------------

(defn plan-add-metric
  "One metric entry, from the sentence «X 的数字口径 m 是 ⟨表达式⟩» (A4).

   The `:expression` is wrapped in D2.21's carried shape — `{:contract
   :duckdb-spill/v1 :text …}` — here rather than by the caller: which contract
   an expression is written against is a fact about this platform's metric
   engine, not something a person says, and asking for it is the encoding
   question 8.4.14 principle 1 keeps inside the tool.

   The entity is the thing's namespace, and every dimension must be an
   attribute of the pool: a metric naming an attribute nobody registered is
   refused by the dialect's own invariant later, and refusing it here names
   the position instead."
  [db {:keys [model]} {:keys [id entity expression dimensions label description]}]
  (let [entry (cond-> {:entity entity
                       :expression {:contract :duckdb-spill/v1 :text (str expression)}}
                (seq dimensions) (assoc :dimensions (vec dimensions))
                label (assoc :label label)
                description (assoc :description description))
        unknown (vec (remove #(get-in model [:attributes %]) (or dimensions [])))
        findings
        (vec (concat
              (when (nil? (get-in model [:namespaces entity]))
                [(f/make :tools.intent/name-unresolved :error [:thing]
                         "the pool holds no namespace under this name, so nothing can be measured over it"
                         :got entity)])
              (when (str/blank? (str expression))
                [(f/make :tools.intent/type-undecidable :error [:expression]
                         "a metric is a definition of a number; there is nothing here to define it with"
                         :refs ["D2.21"])])
              (for [a unknown]
                (f/make :tools.intent/name-unresolved :error [:dimensions]
                        "the pool holds no attribute under this id"
                        :got (str (symbol a))))
              (when (contains? (:metrics model) id)
                [(f/make :tools.intent/name-ambiguous :error [:name]
                         "this pool already holds a metric under this id"
                         :got id)])))
        actions (when-not (errors? findings)
                  [(action :create :metric id entry (revision-of db :metric id))])]
    {:actions (vec actions)
     ;; D2.26 again: the words are a person's. An unlabelled metric is a legal
     ;; dialect state, so this is a `:fill` position and not a refusal.
     :fill (vec (concat (when-not label [[:metric id :label]])
                        (when-not description [[:metric id :description]])))
     :recipe-delta (delta-add empty-delta :metrics [id])
     :created {:metric id}
     :findings findings}))

;; ---------------------------------------------------------------------------
;; The three removals
;; ---------------------------------------------------------------------------

(def ^:private type->recipe-kind
  "A pool type → the recipe group naming it. `:source` has none: a source
   enters a projection through the objects that reference it."
  {:namespace :namespaces :attribute :attributes :resolver :resolvers
   :mutation :mutations :link :links :metric :metrics :term :terms :unit :units})

(defn plan-remove
  "One named object → what removing it does. The closure, the `:blocked-by`
   list and the `:cascade` are the pool's (`object-removal`) — this adds the
   recipe side, which is the only part the pool has no opinion about (D3.19).

   What is removed is what the CALLER named, plus what the pool says cannot
   survive it. It is never a machine's guess at what nobody wants any more —
   that question is the workspace sweep the root CLAUDE.md still lists as
   unruled, and nothing here touches it.

   `also-removed` is the rest of the SAME batch (doc 106 §106.12 item 8.4):
   references are read from the pool minus it, so a resolver and the
   attributes only it produces are one call rather than three rounds."
  ([db type id] (plan-remove db type id #{}))
  ([db type id also-removed]
  (let [r (pool/object-removal db type id also-removed)
        ;; What the plan actually removes is what its ACTIONS name — the pool
        ;; reports `:cascade` and `:orphaned` as explanations, and reading the
        ;; recipe delta off an explanation rather than off the operations
        ;; would be a second answer to «what is going away».
        objs (mapv (juxt :type :id) (:actions r))
        delta (reduce (fn [d [t oid]]
                        (if-let [k (type->recipe-kind t)] (delta-remove d k [oid]) d))
                      empty-delta objs)]
    {:actions (vec (:actions r))
     :fill []
     :recipe-delta delta
     :removed (vec objs)
     :blocked-by (vec (:blocked-by r))
     :cascade (vec (:cascade r))
     :orphaned (vec (:orphaned r))
     :findings
     (vec (concat
           (:findings r)
           (when (seq (:blocked-by r))
             [(f/make :tools.intent/removal-blocked :error [type]
                      (str "something still names this object; removing it would leave that "
                           "reference dangling. Remove or edit the objects listed first")
                      :got (mapv (fn [[t oid]] [t (str (if (keyword? oid) (symbol oid) oid))])
                                 (:blocked-by r)))])))})))

;; ---------------------------------------------------------------------------
;; describe_objects — the enrichment pass, one transaction (item 2)
;; ---------------------------------------------------------------------------

(defn plan-describe-objects
  "`[{:type :id :label :description :synonyms} …]` → the update actions that
   write those words onto the objects, revisions read here.

   D2.26 leaves naming to a person; this does not take it back. It carries
   what a person wrote for twenty objects in one transaction instead of forty
   calls, which is the shape of the work, not a change to who decides."
  [db entries]
  (let [planned
        (for [[i {:keys [type id label description synonyms]}] (map-indexed vector entries)]
          (let [obj (pool/object db type id)]
            (if (nil? obj)
              {:findings [(f/make :tools.intent/name-unresolved :error [:objects i]
                                  "the pool holds no object of this type and id"
                                  :got (str (if (keyword? id) (symbol id) id)))]}
              {:action (action :update type id
                               (cond-> (:pool/entry obj)
                                 label (assoc :label label)
                                 description (assoc :description description)
                                 (seq synonyms) (assoc :synonyms (vec synonyms)))
                               (:pool/revision obj))
               :id [type id]})))]
    {:actions (vec (keep :action planned))
     :fill []
     :recipe-delta empty-delta
     :described (vec (keep :id planned))
     :findings (vec (mapcat :findings planned))}))

;; ---------------------------------------------------------------------------
;; apply — one path for every tool above
;; ---------------------------------------------------------------------------

(defn- verification-query
  "A `limit 1` draft query over what was just written, for item 3's «fold the
   verification into apply».

   Only a ROOT COLLECTION can be queried without an entity, so that is what
   this looks for; when the write anchored nothing queryable the result says
   so with an `:info` rather than inventing a query whose failure would mean
   nothing."
  [model touched-attrs]
  (let [joins (for [a (sort-by str touched-attrs)
                    :let [e (get-in model [:attributes a])]
                    :when (and (:target e) (= :one-to-many (:cardinality e)))]
                a)
        join (first joins)]
    (when join
      (let [leaf (first (sort-by str (for [r (:resolvers model)
                                           :when (some #{join} (leaf-attrs (:output r)))
                                           l (leaf-attrs (:output r))
                                           :when (not= l join)]
                                       l)))]
        (when leaf [{(list join {:limit 1}) [leaf]}])))))

(defn preflight
  "Run the pool's OWN dry path over a plan's actions, and fold what it would
   refuse into the plan's findings.

   **Why a plan validates what apply would write.** Before this, a plan was a
   shape check by the planner and nothing else: the pool first saw the actions
   at apply, so a caller could read a clean plan, send `apply: true` and be
   refused by the dialect — which is exactly what happened on 2026-09-04 to a
   session trying to declare an `:order`, three spellings in a row. A preview
   that cannot say no is not a preview.

   It is `pool/candidate-actions` and not a check written here: the same
   `batch/prepare` plus the same speculative diff `execute-actions` runs, so
   plan and apply cannot come to disagree about what an action means. The
   finding a caller reads at plan is byte-for-byte the one apply would have
   given.

   Actions are NOT dropped when this refuses — `apply-plan!` already declines
   any plan carrying an error, and a caller looking at a refusal needs to see
   what it is about. Apply re-plans through this same function, so its actions
   stay identical to the plan's."
  [conn plan]
  (if (or (nil? conn) (errors? (:findings plan)) (empty? (:actions plan)))
    plan
    (let [r (pool/candidate-actions conn (:actions plan))]
      (cond-> plan
        (seq (:findings r)) (update :findings #(into (vec %) (:findings r)))))))

;; ---------------------------------------------------------------------------
;; The plan's summary (doc 106 §106.7 P8a)
;; ---------------------------------------------------------------------------

(defn- ns-of [akw] (some-> akw namespace))

(defn- join-line
  "One join attribute, said the way `add_relation`'s two kinds differ: WHERE
   the attribute lands (doc 106 §106.12 item 8.5).

   `parent` and `child` mean the same in `kind many` and `kind one` — what
   changes is which of them owns the new key, and a caller who cannot see that
   from the plan re-reads the contract or swaps the two arguments. So the
   summary names the attribute in full and the namespace it hangs on."
  [akw card target]
  (str "Add relation attribute " (symbol akw) " on " (clojure.core/namespace akw)
       (if (= :one-to-one card) " — has one " " — has many ")
       (or target "")))

(defn- summarize-resolver
  "One created resolver, in the four words: a resolver whose output is a join
   branch IS a relation; one that produces a namespace's own attributes is that
   thing being made readable."
  [{:keys [entry id]} bound-collection]
  (let [out    (:output entry)
        branch (some #(when (map? %) (first %)) out)
        ;; «All the X of ONE instance» (doc 106 §106.12 item 9.6): the entry
        ;; looks like any other child collection, and this is the only place a
        ;; reader learns that the sentence they said («all the X») came back
        ;; anchored on the instance.
        bound? (= id (:resolver bound-collection))]
    (if branch
      (let [[bkw children] branch
            card (:cardinality entry)
            ;; «就在它自己的记录里» (§106.14 item 15): the rows come out of the
            ;; host record's own array, which is the one thing about this
            ;; relation a reader cannot see from the attribute's name.
            in-record? (contains? (get-in entry [:source :field_mapping bkw] {}) :_from)]
        (cond-> (join-line bkw card
                           (or (:target entry)
                               (ns-of (first (filter keyword? (flatten (vec children)))))))
          in-record? (str " — in its own record")
          bound?     (str " — all " (:of bound-collection)
                          " of one " (:owner bound-collection))))
      (let [leaves (filter keyword? out)
            ns'    (first (distinct (keep ns-of leaves)))
            ;; **Say what recognises one of them** (owner 2026-09-07, doc 106
            ;; §106.14 item 14). The `:input` of a by-key resolver IS the key,
            ;; and with a compound key the summary is the only place the
            ;; modeller sees that it takes two fields together.
            keys*  (mapv clojure.core/name (or (:input entry) []))]
        (if ns'
          (str "Create thing " ns' " with " (count leaves)
               (if (= 1 (count leaves)) " attribute" " attributes")
               (when (seq keys*)
                 (str ", recognised by "
                      (if (= 1 (count keys*))
                        (first keys*)
                        (str (str/join ", " (butlast keys*)) " and " (last keys*))))))
          (str "Add resolver " (:id entry)))))))

(defn- plural [n one] (str n " " one (when (not= 1 n) "s")))

(declare summarize*)

(defn summarize
  "≤3 short English lines saying what this plan does, derived from `:actions`
   and `:recipe-delta` (doc 106 §106.7 P8a).

   **Derived, never free text from a caller**: a summary a tool could be told
   to print is a sentence that can disagree with the plan under it, and a
   reader who trusts the sentence over the actions is exactly who this is for.
   It adds no information — everything here is already in the plan — it only
   spares a person from reading action maps to find out whether the plan is the
   one they asked for.

   Three, because a summary that has to be scrolled is a second plan."
  [plan]
  (if (:existing plan)
    ;; **A repeat says so in one line** (doc 84 §8.4.25). Derived like every
    ;; other line here: `:existing` is set by the planner only when nothing is
    ;; to be written, and the recipe delta is the only thing that can still
    ;; happen — a caller who said the sentence again while naming a graph the
    ;; objects were not in.
    [(if (pos? (reduce + 0 (map count (vals (:add (:recipe-delta plan))))))
       "already in the pool · added to the graph"
       "already said — nothing to write")]
    (summarize* plan)))

(defn- summarize* [plan]
  (let [acts     (:actions plan)
        creates  (filter #(= :create (:action %)) acts)
        by-type  (group-by :type creates)
        removals (count (filter #(= :delete (:action %)) acts))
        attrs    (filter #(not (contains? (:entry %) :target))
                         (get by-type :attribute))
        ;; A join attribute a created resolver already carries at the head of
        ;; its `:output` is announced by that resolver's line; saying it again
        ;; here was one of three lines spent twice on one relation.
        announced (into #{} (for [r (get by-type :resolver)
                                  o (:output (:entry r))
                                  :when (map? o)]
                              (ffirst o)))
        joins    (remove #(contains? announced (:id %))
                         (filter #(contains? (:entry %) :target)
                                 (get by-type :attribute)))
        delta    (:recipe-delta plan)
        added    (reduce + 0 (map count (vals (:add delta))))
        fills    (count (:fill plan))
        lines
        (concat
         (map #(summarize-resolver % (:bound-collection plan)) (get by-type :resolver))
         (for [t (get by-type :term)] (str "Define concept " (:id t)))
         ;; The join attributes this plan CREATES, each said where it lands
         ;; (item 8.5). One line per relation while they fit in the three; more
         ;; than that and the count stands in, as it always did.
         (if (<= (count joins) 2)
           (for [j joins] (join-line (:id j) (:cardinality (:entry j))
                                     (:target (:entry j))))
           [(str "Add " (plural (count joins) "relation"))])
         (when (seq attrs)
           [(let [ns' (first (distinct (keep (comp ns-of :id) attrs)))]
              (str "Add " (plural (count attrs) "attribute")
                   (when ns' (str " to " ns'))))])
         (for [l (get by-type :link)] (str "Link " (name (:from (:entry l))) " to "
                                           (name (:to (:entry l)))))
         (when (pos? removals) [(str "Remove " (plural removals "object"))])
         (when (pos? added) [(str (plural added "object") " into the graph's recipe")])
         (when (pos? fills)
           [(str (plural fills (if (every? #(= :label (last %)) (:fill plan))
                                 "label" "field"))
                 " to fill")]))]
    (vec (take 3 (remove nil? lines)))))

(defn stamp-workspace
  "Stamp every pool action in a plan with the workspace it acts on behalf of
   (D3.22).

   The value comes from the CALLER's context — an MCP token's workspace, a
   browser session's current workspace — and from nowhere else. There is
   deliberately no `workspace` tool parameter: a caller that could name its own
   workspace would be naming its own permissions.

   Stamped once, before `preflight`, so the plan a caller reads and the actions
   `apply-plan!` writes are the same bytes (the acceptance test asserts it)."
  [plan workspace]
  (update plan :actions #(mapv (fn [a] (assoc a :workspace workspace)) (or % []))))

(defn apply-plan!
  "Write a plan: pool first, recipe second, then verify.

   `deps` is `{:conn :provenance :recipe! :evaluate! :graph}` — the two
   effectful ends are injected by the declaration layer, which got them from
   the host, so this function holds no authority of its own.

   Returns the plan, plus `:written?`, `:revisions`, `:mutations`,
   `:recipe-applied?` and `:statements`. Everything that went wrong on the way
   is in `:findings`; nothing is rolled back, because each of the three steps
   leaves a state the next one only adds to."
  [plan {:keys [conn provenance recipe! evaluate! graph model]}]
  (cond
    (errors? (:findings plan))
    (assoc plan :written? false
           :findings (conj (vec (:findings plan))
                           (f/make :tools.intent/blocked-plan :error [:apply]
                                   (str "this plan carries an error, so it is not applied; a "
                                        "half-applied intent is not a smaller version of it"))))

    ;; **A repeat is not a failed apply** (doc 84 §8.4.25). Every object this
    ;; sentence names is already in the pool, written the way this tool writes
    ;; it, and the graph already names them: there is nothing left to do and
    ;; nothing went wrong. Answering `:tools.intent/blocked-plan` here would
    ;; make «I said that already» read as an error and send the caller looking
    ;; for a defect — it is checked BEFORE the blanket «this plan would write
    ;; nothing» for exactly that reason.
    (and (:existing plan) (empty? (:actions plan)) (delta-empty? (:recipe-delta plan)))
    (assoc plan :written? false :recipe-applied? true)

    (and (empty? (:actions plan)) (delta-empty? (:recipe-delta plan)))
    (assoc plan :written? false
           :findings (conj (vec (:findings plan))
                           (f/make :tools.intent/blocked-plan :error [:apply]
                                   "this plan would write nothing")))

    :else
    ;; **A recipe-only plan writes no pool action** — `add_to_graph` and
    ;; `remove_from_graph` change a list over the pool and touch no object
    ;; (D3.19). Treating an empty action vector as «nothing to do» would have
    ;; made those two tools impossible to apply.
    (let [w (if (seq (:actions plan))
              (pool/execute-actions conn (:actions plan) provenance)
              {:revisions [] :mutations [] :findings []})
          written? (contains? w :revisions)
          plan (assoc plan
                      :written? written?
                      :revisions (:revisions w)
                      :mutations (:mutations w)
                      :findings (into (vec (:findings plan)) (:findings w)))]
      (if-not written?
        plan
        (let [delta (:recipe-delta plan)
              rec (when (and recipe! (not (delta-empty? delta)))
                    (recipe! {:graph graph :delta delta}))
              refused (filter #(= :error (:severity %)) (:findings rec))
              plan (cond
                     (nil? rec) (assoc plan :recipe-applied? (delta-empty? delta))

                     ;; **A recipe-only plan that was refused wrote nothing at
                     ;; all**, so the service's refusal IS the answer and
                     ;; travels whole. Wrapping it in «the pool write landed
                     ;; and the recipe did not» would state something untrue
                     ;; about a call that touched no pool object.
                     (and (seq refused) (empty? (:actions plan)))
                     (assoc plan :written? false :recipe-applied? false
                            :findings (into (vec (:findings plan)) (:findings rec)))

                     (seq refused)
                     (assoc plan :recipe-applied? false
                            :findings
                            (conj (into (vec (:findings plan)) (:findings rec))
                                  ;; **Not a rollback.** The pool write is a
                                  ;; legal state on its own (D3.19) and the
                                  ;; revisions that landed are named here so
                                  ;; the caller can finish the job rather than
                                  ;; repeat it.
                                  (f/make :tools.intent/recipe-not-updated :error [:recipe]
                                          (str "the pool write landed and the recipe change did "
                                               "not; the objects exist but this graph does not "
                                               "name them yet. Nothing was rolled back")
                                          :got {:revisions (vec (:revisions w))
                                                :delta delta})))
                     :else (assoc plan :recipe-applied? true))]
          ;; Item 3: verification is part of the apply, so a refusal is
          ;; visible in the same turn the write happened in.
          (if-not evaluate!
            plan
            ;; **The model as the write LEFT it**, not as it was planned
            ;; against: the join attribute this query starts from was created
            ;; by this very write, so the pre-write projection does not carry
            ;; it and the verification would silently never run.
            (let [q (verification-query (pool/as-dialect (pool/db conn))
                                        (get-in plan [:recipe-delta :add :attributes]))]
              (if-not q
                (assoc plan :statements []
                       :findings (conj (vec (:findings plan))
                                       (f/make :tools.intent/verify-failed :info [:verify]
                                               (str "nothing this write added can be queried "
                                                    "without an entity, so no verification query "
                                                    "was run"))))
                (let [r (try (evaluate! {:graph graph :query (vec q) :limit 1})
                             (catch Exception e {:findings [(f/make :tools.intent/verify-failed
                                                                    :warning [:verify]
                                                                    (ex-message e))]}))]
                  (assoc plan
                         :statements (vec (:statements r))
                         :verified? (empty? (filter #(= :error (:severity %)) (:findings r)))
                         :findings
                         (into (vec (:findings plan))
                               ;; A failed verification is a finding, never a
                               ;; rollback: the write is already a fact, and
                               ;; undoing it would hide the very thing the
                               ;; caller has to see.
                               (for [fd (:findings r)]
                                 (f/make :tools.intent/verify-failed :warning [:verify]
                                         (str "the post-write draft query was refused: "
                                              (:message fd))
                                         :got (str (:code fd)))))))))))))))

;; ---------------------------------------------------------------------------
;; assign_term / unassign_term (8.4.14「词条的两条意图工具」, owner 2026-09-03)
;; ---------------------------------------------------------------------------

(def term-carried-fields
  "The fields a `:term` binding carries, and which the bound attribute must
   therefore NOT also state (D2.26: binding seals the semantics, and the term
   is the single source of truth). `:type`, `:identity?` and `:unit` stay
   local — they are physical and governance facts about THIS column, not the
   concept's."
  [:label :description :synonyms :values :format])

(comment
  ;; `string-coded-values` lived here: a code list whose keys arrived as
  ;; keywords, restated with STRING keys when the bound attributes were
  ;; `:type :string`. It is gone (owner ruling 2026-09-07, doc 106 §106.14
  ;; item 9): the same defect at :headers, :query_params and :field_mapping
  ;; made it one mechanism, driven by the contract schema and applied once at
  ;; the tool face (`toolkit` `:coerce` + `dialect/coerce-json-keys`). Two
  ;; consequences worth stating, since this ad-hoc version had neither:
  ;; the coercion no longer reads the bound attributes' types — it reads the
  ;; contract, which is what JSON's string-only keys are a fact about — and a
  ;; position the contract grows tomorrow is covered without an edit here.
  )

(defn plan-assign-term
  "Bind one term onto the attributes a person named.

   Three things this does that the hand path keeps getting wrong:

   - the local semantic fields are **stripped**, and each stripped field is
     listed with its current value so the person sees what they are giving up
     — that step is the one most often forgotten, and its omission is a
     schema refusal much later;
   - a local `:label` that DIFFERS from the term's is never silently
     overwritten: it goes to `:fill`, because «these two names disagree» is a
     question about meaning and D2.26 leaves meaning to a person;
   - `definition` is required when the term is not registered and REFUSED when
     it is. Accepting it for an existing term would be a second place the
     term's words come from."
  [db {:keys [model]} {:keys [term attributes definition contexts]}]
  (let [existing (get-in model [:terms term])
        term-rev (revision-of db :term term)
        ;; **Saying the same thing twice is not a conflict** (doc 106 §106.12
        ;; item 8.6). Re-running a build over a pool that already holds the
        ;; concept meant deleting the term first, because ANY `definition` for
        ;; an existing term was refused. A definition that is WORD FOR WORD what
        ;; is registered says nothing new, so the plan is the bindings alone;
        ;; a DIFFERENT one is still refused — D2.26's one source of truth is
        ;; about disagreement, not about repetition.
        same-definition? (boolean (and existing (seq definition)
                                       (= definition
                                          (select-keys existing (keys definition)))))
        entries  (for [a attributes]
                   (let [obj (pool/object db :attribute a)]
                     {:id a :entry (:pool/entry obj) :revision (:pool/revision obj)
                      :missing? (nil? obj)}))
        stripped (vec (for [{:keys [id entry]} entries
                            k term-carried-fields
                            :when (contains? entry k)]
                        {:attribute id :field k :value (get entry k)}))
        label-conflicts
        (vec (for [{:keys [id entry]} entries
                   :let [l (:label entry)]
                   :when (and l (:label existing) (not= l (:label existing)))]
               {:attribute id :local-label l :term-label (:label existing)}))
        findings
        (vec (concat
              (for [{:keys [id missing?]} entries :when missing?]
                (f/make :tools.intent/name-unresolved :error [:attributes]
                        "the pool holds no attribute under this id"
                        :got (str (symbol id))))
              (when (and existing (seq definition) (not same-definition?))
                [(f/make :tools.intent/name-ambiguous :error [:definition]
                         (str "this term is already registered with different words, and a "
                              "term has one source of truth for them; send the definition it "
                              "already has, or edit the term itself to change them")
                         :got term :refs ["D2.26"])])
              (when (and (not existing) (empty? definition))
                [(f/make :tools.intent/name-unresolved :error [:definition]
                         (str "this term is not registered yet, so its definition must come with "
                              "the binding — an attribute may not bind a term that does not exist")
                         :got term :refs ["D2.26"])])))
        actions
        (when-not (errors? findings)
          (vec (concat
                (when-not existing
                  [(action :create :term term definition term-rev)])
                (for [{:keys [revision] :as e} entries]
                  ;; An attribute entry is a **merge patch** (pool `:update
                  ;; :merge`), where an explicit nil deletes the key. Sending
                  ;; the entry minus those keys would leave every one of them
                  ;; in place, and the very next save would be refused for
                  ;; writing both the term and the words it carries (D2.26).
                  (action :update :attribute (:id e)
                          (cond-> (assoc (zipmap term-carried-fields (repeat nil)) :term term)
                            ;; C4 (need-grammar-v1 §3): «在 X 里 a 是购买方» —
                            ;; the local role a binding plays, D2.27's
                            ;; `:term-context`, and mandatory when one
                            ;; namespace binds the same term twice.
                            (get contexts (:id e))
                            (assoc :term-context (get contexts (:id e))))
                          revision)))))]
    {:actions (vec actions)
     ;; A disagreement between the two names is a position for a person, and
     ;; `:fill` is where those live — non-blocking, exactly as an absent label
     ;; is (8.4.14「`:fill` 非空时 apply 仍可执行」).
     :fill (vec (for [c label-conflicts] [:attribute (:attribute c) :label]))
     :label-conflicts label-conflicts
     :stripped stripped
     ;; A bound term must be inside the graph's visibility (D2.26), or the cut
     ;; refuses a `:term` pointing at an unregistered entry.
     :recipe-delta (delta-add empty-delta :terms [term])
     :created {:term term :attributes (vec attributes)}
     :findings findings}))

(defn plan-unassign-term
  "Take the binding off, and copy the term's words back down.

   Copying back is not an afterthought: an attribute that loses `:term` and
   keeps nothing is a bare column with no description at all, which is a worse
   model than either of the two states around it. The term itself is never
   deleted — other attributes may still bind it, and deleting somebody else's
   registry entry is not what «unassign this attribute» asked for."
  [db {:keys [model]} {:keys [attributes]}]
  (let [entries (for [a attributes]
                  (let [obj (pool/object db :attribute a)]
                    {:id a :entry (:pool/entry obj) :revision (:pool/revision obj)
                     :missing? (nil? obj)}))
        findings
        (vec (concat
              (for [{:keys [id missing?]} entries :when missing?]
                (f/make :tools.intent/name-unresolved :error [:attributes]
                        "the pool holds no attribute under this id"
                        :got (str (symbol id))))
              (for [{:keys [id entry missing?]} entries
                    :when (and (not missing?) (nil? (:term entry)))]
                (f/make :tools.intent/name-unresolved :error [:attributes]
                        "this attribute binds no term, so there is nothing to unassign"
                        :got (str (symbol id))))))
        actions
        (when-not (errors? findings)
          (vec (for [{:keys [id entry revision]} entries
                     :let [t (get-in model [:terms (:term entry)])]]
                 ;; Same merge-patch discipline as `assign`: `:term nil`
                 ;; deletes the binding, and the term's words are written in
                 ;; the same patch so the attribute is never momentarily bare.
                 (action :update :attribute id
                         (merge {:term nil} (select-keys t term-carried-fields))
                         revision))))]
    {:actions (vec actions)
     :fill []
     ;; The recipe is untouched: the term may still be bound elsewhere in this
     ;; graph, and taking it out of the recipe on one unbinding would break
     ;; those. 8.4.14 says so in the same words.
     :recipe-delta empty-delta
     :created {:attributes (vec attributes)}
     :findings findings}))

;; ---------------------------------------------------------------------------
;; api source: fields inferred from ONE sample (裁定七)
;; ---------------------------------------------------------------------------

(defn- sample-class
  "A sample VALUE → the dialect class this layer guesses. A guess, and the
   plan says so: every inferred field lands in `:fill` for a person to accept
   before apply."
  [v]
  (cond
    (boolean? v) :boolean
    (integer? v) :integer
    (number? v)  :decimal
    (string? v)  (cond
                   (re-matches #"\d{4}-\d{2}-\d{2}" v) :date
                   (re-matches #"\d{4}-\d{2}-\d{2}[T ].*" v) :datetime
                   :else :string)
    ;; A null or an empty array carries no evidence at all. Listed as
    ;; undecided rather than dropped: a field silently missing from the plan
    ;; is a field nobody knows to ask about (裁定七).
    :else nil))

(defn- path-get
  "One JSONPath of the `$.a.b` / `$.a[0].b` shape against a parsed body.

   **Why a walker lives here at all**: 8.4.22 裁定十六 asks the api branch to
   use the connector's own `locate-rows`, and `studio/tools` may not require
   `connector-api` (D1.8 — its deps declare the SPI seam, and which connectors
   exist is the host's decision). The host therefore INJECTS the connector's
   locator as `:locate-rows`, and this is the fallback for a host that
   injected none — same syntax, same result on the shapes a probe sample can
   hold."
  [data path]
  (let [segs (->> (-> (str path)
                      (str/replace #"^\$\.?" "")
                      (str/replace #"\[(\d+)\]" ".$1"))
                  (#(str/split % #"\."))
                  (remove str/blank?))]
    (reduce (fn [acc seg]
              (cond
                (nil? acc) (reduced nil)
                (re-matches #"\d+" seg) (when (sequential? acc)
                                           (nth (vec acc) (parse-long seg) nil))
                (map? acc) (or (get acc (keyword seg)) (get acc seg))
                :else (reduced nil)))
            data segs)))

(defn- rows-at
  "The value at `path` in a probe sample, located by the CONNECTOR when the
   host injected its locator (`:locate-rows`) and by `path-get` otherwise. One
   locator per call, never two answers about where the rows are."
  [locate sample path]
  (cond
    (or (nil? path) (= "$" path)) sample
    locate (locate sample path)
    :else (path-get sample path)))

(defn- array-of-objects? [v]
  (and (sequential? v) (seq v) (map? (first v))))

(defn infer-data-path
  "The heuristic `:data_path` (8.4.22 裁定十六 ②): the JSONPath of the first
   array whose first element is an object, searched breadth-first with keys in
   name order so the answer does not depend on map iteration.

   Returned rather than applied silently — a caller that did not say where the
   records are is told which path was chosen, and the plan lists it under
   `:fill`."
  [sample]
  (letfn [(search [node path depth]
            (cond
              (array-of-objects? node) path
              (> depth 6) nil
              (map? node) (some (fn [[k v]]
                                  (search v (str path "." (name k)) (inc depth)))
                                (sort-by (comp str key) node))
              :else nil))]
    (search sample "$" 0)))

(defn- sample-rows
  "The rows in a LOCATED probe sample: an array is the rows, a lone object is
   one row."
  [located]
  (cond
    (sequential? located) (vec located)
    (map? located) [located]
    :else []))

(def code-value-candidate-max
  "How few distinct string values make a field a CODE-VALUE candidate
   (补裁 2026-09-04). A candidate, not a verdict: it lands in `:fill` by name,
   because a thousand records are evidence about a thousand records."
  12)

(defn- merged-column
  "One field, inferred from ALL the sampled records (补裁 2026-09-04).

   `values` holds this field's value in every sampled record, with `::missing`
   where the record had no such key. One record cannot tell an optional field
   from a mandatory one, a nullable field from a full one, or a code list from
   free text — a thousand can, and each answer here says how much of the
   sample it rests on (`:seen n :of total`).

   The VALUES never leave this map: findings and audits carry the counts and
   the names (finding-v1 §5)."
  [nm json-path values]
  (let [total    (count values)
        present  (remove #(= ::missing %) values)
        seen     (count present)
        non-nil  (remove nil? present)
        classes  (distinct (keep sample-class non-nil))
        c        (when (= 1 (count classes)) (first classes))
        strings  (when (= :string c) (distinct non-nil))
        code?    (boolean (and (seq strings)
                               (<= (count strings) code-value-candidate-max)
                               (< (count strings) (count non-nil))))]
    (cond-> {:name nm :class (or c :unknown)
             :sql-type nil
             ;; A field some record lacked, or some record left null, is
             ;; nullable — a fact one record could never have shown.
             :nullable? (boolean (or (< seen total) (some nil? present)))
             :position 0
             :json-path json-path
             :seen seen :of total
             :inferred {:from (cond
                                (> (count classes) 1) "inconsistent"
                                (seq non-nil) (str c)
                                (some #(and (sequential? %) (empty? %)) present) "empty array"
                                (pos? seen) "null"
                                :else "absent")
                        :decided (boolean c)}}
      code? (assoc :code-values? true :distinct-values (count strings)))))

(defn rows->description
  "A HAND-WRITTEN list of rows -> the same `describe-schema` shape the sql and
   api branches plan from (need-grammar-v1 A1's third source, static-v1).

   The rows are evidence a person WROTE, so the fields are read off them the
   same way the probe reads its sample — one table of classes, one place that
   decides what a value's dialect type is (D2.14). Nothing is called: a static
   source is the model, not a place to reach.

   The rows are refused rather than guessed at when they are not a list of
   maps; that refusal belongs to the caller, which knows the parameter's name."
  [rows relation]
  (let [names (sort (distinct (mapcat (comp (partial map name) keys) rows)))
        cols (vec (for [nm names]
                    (merged-column nm nil
                                   (mapv #(get % (keyword nm) (get % nm ::missing)) rows))))]
    {:capabilities {:primary-keys false :foreign-keys false :views false}
     :keys-read? false
     :findings []
     :relations [{:name relation :schema nil :kind :table
                  :columns (vec (map-indexed (fn [i c] (assoc c :position (inc i)
                                                              :json-path nil))
                                             cols))
                  :primary-key [] :foreign-keys []}]}))

(defn probe->description
  "A probe result → the same `describe-schema` shape `add_resolver` plans
   from, so the sql and api branches meet at one planner and the caller never
   learns there was a fork.

   `data-path` locates the records in the response (8.4.22 裁定十六 ②); absent,
   `infer-data-path`'s heuristic decides and the chosen path is reported back
   under `:probe`. `nested` are the `{name, path}` outputs the caller asked
   for: each becomes a column of its own, typed from the value at that path in
   the sampled records, so a nested value is planned exactly like a flat one.

   Fields are MERGED across every sampled record (补裁 2026-09-04): the keys
   are the union, the type is the consistent class of the non-null values, and
   anything the sample disagreed about is `:unknown` and waits for a person.

   `:class` is inferred from values, and every inferred field carries the
   evidence — the sample value's SHAPE, never the value — under `:inferred`."
  [{:keys [sample truncated? records-path]} relation path
   {:keys [data-path nested locate-rows]}]
  (let [chosen-path (or data-path records-path (infer-data-path sample))
        ;; 8.4.23 裁定十七: a TRUNCATED probe hands back records ALREADY
        ;; located by the connector — locating again inside them would find
        ;; nothing and report a fieldless relation.
        located (if truncated? sample (rows-at locate-rows sample chosen-path))
        rows (sample-rows located)
        ;; Keys in name order, so the answer does not depend on map iteration
        ;; or on which record happened to arrive first.
        names (sort (distinct (mapcat (comp (partial map name) keys) rows)))
        col (fn [nm json-path values] (merged-column nm json-path values))
        flat (vec (for [nm names]
                    (col nm (str "$." nm)
                         (mapv #(get % (keyword nm) ::missing) rows))))
        ;; A nested output names a value no top-level key carries; typed from
        ;; the same records, so its `:fill` position reads like the others'.
        deep (vec (for [{:keys [name path]} nested
                        :when (and name path)]
                    (col name path (mapv #(path-get % path) rows))))
        cols (into flat (remove (fn [c] (some #(= (:name c) (:name %)) flat)) deep))]
    {:capabilities {:primary-keys false :foreign-keys false :views false}
     :keys-read? false
     :probe {:path path :rows (count rows) :fields (count cols)
             :data-path chosen-path
             ;; :probe = the streaming read reported where it found the
             ;; records (裁定十七); :heuristic = they were searched for in a
             ;; whole sample; :caller = the caller said so.
             :data-path-from (cond data-path :caller
                                   records-path :probe
                                   :else :heuristic)
             :truncated? (boolean truncated?)}
     :findings []
     :relations [{:name relation :schema nil :kind :table
                  :columns cols :primary-key [] :foreign-keys []}]}))

(defn probe-fill
  "Every field a probe inferred, as `:fill` positions (裁定七: what the probe
   produced is a guess, not a fact — all of it waits for a person).

   A code-value CANDIDATE is a second position on the same field (补裁
   2026-09-04): the sample held few distinct strings, which is a reason to
   look, not a code list. The count of distinct values travels; the values
   themselves never do."
  [description relation]
  (vec (mapcat (fn [c]
                 (cond-> [[:attribute (:name c) :type (get-in c [:inferred :from])]]
                   (:code-values? c)
                   (conj [:attribute (:name c) :code-values
                          (str (:distinct-values c) " distinct values in "
                               (:seen c) " sampled records")])))
               (:columns (relation-of description relation)))))

(defn probe-audit
  "The one audit record a probe writes (裁定七).

   It carries the source id, the path, the NAMES of the parameters, the status
   code, how many BYTES were read, whether the probe stopped early (8.4.23
   裁定十七) and how many fields came out. It carries no response body — that is business data — and no resolved credential value:
   `${VAR}` references are resolved by the connector at call time and appear
   in no result, finding or log (same discipline as P1b)."
  [{:keys [source path params data-path data-path-from records]} result field-count]
  {:probe/source source
   :probe/path path
   :probe/param-keys (vec (sort (map name (keys params))))
   :probe/status (:status result)
   ;; `:probe/response-bytes` is the size of the WHOLE answer and is present
   ;; only when the whole answer was read; `:probe/bytes-read` is always the
   ;; bytes this probe pulled off the socket (8.4.23 裁定十七: the ceiling
   ;; bounds the read, not the response).
   :probe/response-bytes (:bytes result)
   :probe/bytes-read (:bytes-read result)
   :probe/truncated? (boolean (:truncated? result))
   ;; How many records the inference rests on (补裁 2026-09-04). The connector
   ;; counts them when it located them; a whole-body read is counted by the
   ;; caller, from the rows at the chosen path.
   :probe/records (or (:records result) records)
   :probe/fields field-count
   ;; A LOCATION, not content: which path the rows were read from, and whether
   ;; the caller said so or the heuristic decided (8.4.22 裁定十六 ②).
   :probe/data-path data-path
   :probe/data-path-from data-path-from})

;; ---------------------------------------------------------------------------
;; add_to_graph / remove_from_graph —— `update_recipe` 的接替者（裁定八）
;; ---------------------------------------------------------------------------

(defn resolve-any
  "A name → `{:id [type id]}` or `{:findings […]}`, WITHOUT the caller having
   to say which kind of object it is.

   `add_to_graph` is the one intent whose vocabulary is «put these things in
   this graph», and a person saying that does not sort them into eight groups
   first — the groups are the recipe's shape, not the request's. So the type
   is found here, and an id that names objects of two different types is
   ambiguous exactly the way a repeated local name is: refused with both
   candidates, never picked between."
  [db query {:keys [abbreviations path]}]
  (let [path (or path [:objects])
        as-kw (cond
                (qualified-keyword? query) query
                (and (string? query) (str/includes? query "/")
                     (not (str/starts-with? query "\"")))
                (keyword (str/replace-first query #"^:" ""))
                :else nil)
        direct (vec (for [t [:attribute :link :resolver :mutation :metric
                             :namespace :term :unit :source]
                          :let [id (if (= :attribute t) as-kw query)]
                          :when (and id (pool/object db t id))]
                      [t id]))]
    (cond
      (= 1 (count direct)) {:id (first direct)}

      (seq direct)
      {:findings [(f/make :tools.intent/name-ambiguous :error path
                          "this id names objects of more than one type; name the type by using its own tool"
                          :got (mapv (fn [[t id]] [t (str (if (keyword? id) (symbol id) id))])
                                     direct))]}

      :else
      (let [model (pool/as-dialect db)
            idx (rt/index (pool/objects db) (:terms model) abbreviations)
            r (rt/resolve-target idx (str (if as-kw (symbol as-kw) query))
                                 {:abbreviations abbreviations})]
        (case (:verdict r)
          :exact-unique {:id (:target r)}
          :exact-ambiguous
          {:findings [(f/make :tools.intent/name-ambiguous :error path
                              "this name matches several objects; name one of the candidates exactly"
                              :got (mapv (fn [[t id]] [t (str (if (keyword? id) (symbol id) id))])
                                         (:candidates r)))]}
          {:findings [(f/make :tools.intent/name-unresolved :error path
                              "no pool object resolves from this name"
                              :got (str query))]})))))

(defn plan-graph-membership
  "`[[type id] …]` × direction → the recipe delta alone. **No pool action** —
   `:actions` is empty on purpose, and the apply path knows that a plan with a
   delta and no action is a recipe-only write.

   `remove_from_graph` deletes nothing: D3.19 makes the pool the object's home
   and a graph a list over it, so taking a name off the list is the whole act.
   The three `remove_*` tools are where deletion lives, and they say so in
   their own names."
  [op pairs]
  (let [delta (reduce (fn [d [t id]]
                        (if-let [k (type->recipe-kind t)]
                          (if (= :add op) (delta-add d k [id]) (delta-remove d k [id]))
                          d))
                      empty-delta pairs)
        ;; A source is reached through the objects that reference it
        ;; (scope-projection-v1 §2.5), so no recipe names one. Saying so is
        ;; the answer; silently dropping it would report success for a change
        ;; that never happened.
        unlistable (filterv (comp nil? type->recipe-kind first) pairs)]
    {:actions []
     :fill []
     :recipe-delta delta
     :members (vec pairs)
     :findings (vec (for [[t id] unlistable]
                      (f/make :tools.intent/name-unresolved :error [:objects]
                              (str "no recipe group names an object of this type; a source enters "
                                   "a projection through the objects that reference it")
                              :got [t (str id)]
                              :refs ["scope-projection-v1 §2.5"])))}))

;; ---------------------------------------------------------------------------
;; update_resolver / update_attribute / define_term / update_entity (裁定八)
;; ---------------------------------------------------------------------------

(def removable-source-keys
  "The operation-block keys whose ABSENCE is a legal source (api-v1 / sql-v1
   op tables mark them optional). Removing a required key would leave a block
   that no connector can compile, so the set is closed and named rather than
   «anything the caller lists»."
  #{:data_path :query_params :json_body :batch :field_mapping :params :limit})

(defn plan-update-resolver
  "Change what an existing resolver DOES: its statement, its source block, its
   page window, its ordering, its declared parameters, its input and output.

   A resolver entry is **replaced** wholesale by the pool (`:update :replace`),
   which is the sharp edge this tool exists to blunt: `execute_action` with a
   patch silently drops every key the patch does not carry — a `:description`
   edit that loses the `:query` is a legal write with zero findings. Here the
   current entry is read and the named keys are laid over it, so an edit is an
   edit."
  [db {:keys [model]} {:keys [resolver patch remove-keys]}]
  (let [entry (some #(when (= resolver (:id %)) %) (:resolvers model))
        rev (revision-of db :resolver resolver)
        source-patch (:source patch)
        ;; A merge cannot say «drop this key», and the JSON face refuses an
        ;; explicit null, so the sentence «this source no longer has a
        ;; data_path» had no spelling at all (doc 106 §106.12 item 7.4).
        ;; `remove` is a CLOSED list — the keys whose absence is legal.
        entry* (cond-> (merge (dissoc entry :source) (dissoc patch :source))
                 ;; The source block is merged, not replaced: «change the
                 ;; query» must not take the `:batch` block with it, and
                 ;; `:batch` is what stands between this resolver and the N+1
                 ;; D2.4 exists to prevent.
                 :always (assoc :source (apply dissoc
                                               (merge (:source entry) source-patch)
                                               (or remove-keys []))))
        findings
        (vec (concat
              (when (nil? entry)
                [(f/make :tools.intent/name-unresolved :error [:resolver]
                         "the pool holds no resolver under this id"
                         :got resolver)])
              (when (and entry (empty? (dissoc patch :source)) (empty? source-patch)
                         (empty? remove-keys))
                [(f/make :tools.intent/blocked-plan :error [:resolver]
                         "no change was named; say which part of the resolver to change")])
              (when-let [bad (seq (remove removable-source-keys (or remove-keys [])))]
                [(f/make :tools.intent/unknown-column :error [:remove]
                         "this source key may not be removed; only optional ones can be"
                         :got (mapv str (sort-by str bad))
                         :expected (mapv str (sort-by str removable-source-keys)))])
              ;; 8.4.19 裁定十三 ②: an edit that turns a resolver into a shape
              ;; the engine cannot compile is refused HERE. The engine's own
              ;; refusal arrives as a warning at compile time and the resolver
              ;; is dropped — present in the draft, absent from every query,
              ;; which is the state the ruling's incident spent a session in.
              (when entry
                (compile-shape-findings entry* [:resolvers resolver :output]))))]
    {:actions (if (errors? findings) [] [(action :update :resolver resolver entry* rev)])
     :fill []
     :recipe-delta empty-delta
     :created {:resolver resolver}
     :findings findings}))

(defn plan-update-attribute
  "Change an attribute's PHYSICAL and governance facts — type, format, closed
   value set, identity, deprecation and its replacement.

   Its words are not here: label, description and synonyms belong to
   `describe_objects`, and to the term when one is bound (D2.26). Splitting
   them this way is what makes «binding seals the semantics» checkable — a
   tool that wrote both could not tell a caller which half was refused."
  [db {:keys [model]} {:keys [attribute patch]}]
  (let [entry (get-in model [:attributes attribute])
        rev (revision-of db :attribute attribute)
        findings
        (vec (concat
              (when (nil? entry)
                [(f/make :tools.intent/name-unresolved :error [:attribute]
                         "the pool holds no attribute under this id"
                         :got (str (symbol attribute)))])
              (when (and entry (empty? patch))
                [(f/make :tools.intent/blocked-plan :error [:attribute]
                         "no change was named; say which fact about the attribute to change")])
              (when (and entry (contains? patch :type) (:target entry))
                [(f/make :tools.intent/type-undecidable :error [:type]
                         (str "this is a join attribute (it carries :target), and a join has no "
                              ":type — the two are mutually exclusive")
                         :refs ["D2.29"])])))]
    ;; A merge patch: only the named keys travel, and an explicit nil deletes.
    {:actions (if (errors? findings) [] [(action :update :attribute attribute patch rev)])
     :fill []
     :recipe-delta empty-delta
     :created {:attribute attribute}
     :findings findings}))

(def term-definition-keys
  "What a term entry carries (dialect-v1 §3). Anything else is refused rather
   than dropped: a caller who sent `:unit` on a term believes the unit is
   recorded, and D2.26 says exactly why it is not — a unit is a physical fact
   about one column, not about the concept."
  #{:label :description :synonyms :format :values :deprecated? :replaced-by})

(defn plan-define-term
  "Register a term, or edit the definition of one that exists — WITHOUT
   binding anything.

   Separate from `assign_term` because the two acts have different audiences:
   defining is glossary work, binding is modelling work, and a term with no
   binding is a legitimate state (`query_model` reports it as a gap to go and
   fill, not as garbage)."
  [db {:keys [model]} {:keys [term definition]}]
  (let [existing (get-in model [:terms term])
        rev (revision-of db :term term)
        unknown (remove term-definition-keys (keys definition))
        findings
        (vec (concat
              (when (seq unknown)
                [(f/make :tools.intent/name-unresolved :error [:definition]
                         "a term carries only these keys; the others belong on the attribute"
                         :got (vec (sort (map str unknown)))
                         :expected (mapv str (sort term-definition-keys))
                         :refs ["D2.26" "dialect-v1 §3"])])
              (when (and (not existing) (not (:label definition)))
                [(f/make :tools.intent/name-unresolved :error [:definition :label]
                         "a new term needs a label; it is the concept's name and the machine may not pick one"
                         :refs ["D2.26"])])))]
    {:actions (if (errors? findings)
                []
                [(action (if existing :update :create) :term term definition
                         (if existing rev 0))])
     :fill (vec (when-not (:description definition) [[:term term :description]]))
     ;; A term the graph will bind must be inside its visibility, and a term
     ;; defined for this graph's use belongs in its recipe either way.
     :recipe-delta (delta-add empty-delta :terms [term])
     :created {:term term}
     :findings findings}))

(defn plan-update-entity
  "Change a namespace's description of itself — its label, its kind, its
   synonyms. A namespace IS the entity in this Builder's vocabulary (D2.5), so
   this is «rename what this entity is called», not a structural change; the
   entry is otherwise purely descriptive since D2.29."
  [db {:keys [model]} {:keys [namespace patch]}]
  (let [entry (get-in model [:namespaces namespace])
        rev (revision-of db :namespace namespace)
        findings
        (vec (concat
              (when (nil? entry)
                [(f/make :tools.intent/name-unresolved :error [:namespace]
                         "the pool holds no namespace under this name"
                         :got namespace)])
              (when (and entry (empty? patch))
                [(f/make :tools.intent/blocked-plan :error [:namespace]
                         "no change was named; say what about the entity to change")])))]
    {:actions (if (errors? findings) [] [(action :update :namespace namespace patch rev)])
     :fill []
     :recipe-delta empty-delta
     :created {:namespace namespace}
     :findings findings}))

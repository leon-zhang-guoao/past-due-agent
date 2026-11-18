(ns open.atlas.server.mcp.impl.catalog
  "The discovery half of the MCP surface: what is in this graph.

   Migrated from `ent.dx.mcp.entities` / `ent.dx.mcp.attr-search`.  The
   **rendered shape is kept verbatim** — `{:entity :attribute :type
   :description …}` is what an agent has been reading for a long time, and
   changing it is a change to a working interface, not an improvement.

   What changed is where the catalog comes from.  The legacy reader took it
   off a live global atom (`hot-reload/current-catalog`), which is the legacy
   stack's whole world.  Here it comes from the runtime: a token names a
   graph, `env/for-request` pins one version, and `engine/catalog` is that
   version's single public catalog.  So discovery answers for **the published
   artifact the caller holds a token for** — the same thing `answer` queries,
   not a second, differently-sourced view of it (D1.13 forbids the second
   view; a catalog that disagreed with the query path would be exactly that).

   The engine catalog is attribute-centric (`{:attributes {kw {… :entity ns}}}`)
   while the rendering below is entity-centric, so this ns regroups.  That
   regrouping is the whole adapter.

   `search_attributes` is the one answer here that is NOT a regrouping of the
   catalog: it runs the real search index (`open.atlas.common.search`), wired
   here per D7.10 — see `search-attributes` below."
  (:require [clojure.string :as str]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.search :as search]))

;; ---------------------------------------------------------------------------
;; Rendering (shape migrated verbatim — an agent reads these keys)
;; ---------------------------------------------------------------------------

(defn- attr-name
  "Render a keyword as ns/name, or just name when unnamespaced."
  [kw]
  (if-let [ns' (namespace kw)] (str ns' "/" (name kw)) (name kw)))

(defn- param-entry
  "One declared parameter, rendered with name, type, whether it is required,
   its description and its closed value set (contract 2.0 §3.1). A 1.4
   `:filters` entry carries `:attribute` instead of `:type`; its type is the
   bound attribute's, read off the catalog rather than defaulted to string —
   the catalog is the one place a type is declared (D2.18)."
  [attrs [pk pv]]
  (let [bound (some->> (:attribute pv) (get attrs) :type)]
    (cond-> {:name (name pk)
             :type (name (or (:type pv) bound :string))}
      (:required? pv)       (assoc :required true)
      (:description pv)     (assoc :description (:description pv))
      (some? (:default pv)) (assoc :default (:default pv))
      (seq (:allowed pv))   (assoc :allowed (vec (:allowed pv))))))

(defn- producer-keys
  "What the preferred producer of a key declares beyond its parameters:
   `:priority` (2.0 §4.1, absent reads as 0 and is not rendered) and `:batch`
   (2.0 §3.2). Both are rendered only when set so a 1.x row keeps its shape."
  [d]
  (cond-> {}
    (seq (:input d)) (assoc :required_inputs (mapv attr-name (:input d)))
    (seq (:eql-params d)) (assoc :parameter_semantics "Per-call controls; optional unless required; do not establish graph paths")
    (contains? d :priority) (assoc :priority (:priority d))
    (:batch? d)             (assoc :batch true)))

(defn- attr-entry
  "One attribute row.  Enrichment (`:values` code→label, `:synonyms` NL
   aliases, `:unit`, `:format`) is added only when present, so a bare
   attribute keeps its prior shape.  The LLM sees the closed value set and the
   aliases wherever it discovers an attribute — that is what stops it from
   inventing codes.

   Since contract 2.0 the row also carries what the attribute's preferred
   producer declares (`:params`, `:priority`, `:batch`), when the key has a
   declaration at all — an agent choosing between overlapping resolvers
   (§4) or passing a free parameter (§3.1) reads it here, not off a binding."
  [attrs attr {:keys [entity type description values synonyms unit format]} d]
  (cond-> {:entity      entity
           :attribute   (attr-name attr)
           :type        (if type (name type) "string")
           :description (or description "")}
    (seq values)   (assoc :values (into {} (for [[code v] values]
                                             [code (or (:label v) (str code))])))
    (seq synonyms) (assoc :synonyms (vec synonyms))
    unit           (assoc :unit unit)
    format         (assoc :format (name format))
    (seq (:eql-params d)) (assoc :params (mapv #(param-entry attrs %) (:eql-params d)))
    d              (merge (producer-keys d))))

(defn- identity-attrs
  "The identity attribute names of one entity, in catalog order — the keys
   an agent writes to pick a union branch (2.0 §6.5: EQL's own union
   spelling discriminates on the branch entity's identity attribute)."
  [attrs entity]
  (vec (for [[a d] attrs :when (and (= entity (:entity d)) (:identity? d))]
         (attr-name a))))

(defn- union-description
  "A union join reaches several entities (2.0 §6.1): the catalog answers a
   vector of targets instead of one string, and the row must say so — an
   agent that nests into it as a plain join gets rows of several shapes and
   no way to tell them apart. The description names every target, states
   that each record is exactly one of them, and spells the nesting form with
   the identity keys so the branch syntax is copied, not guessed."
  [attrs join targets]
  (let [branches (map (fn [t] [t (identity-attrs attrs t)]) targets)]
    (str "→ " (str/join " | " targets)
         " (union: each record is exactly one of these entities, discriminated"
         " by its identity attribute; nest as {:" (attr-name join) " {"
         (str/join " " (map (fn [[_ ids]] (str ":" (or (first ids) "<identity>") " [...]"))
                                       branches))
         "}})")))

(defn- join-entry
  "One join row: an attribute holding rows of another entity, which the caller
   may nest into.  `:params` carries the resolver's declared narrowing so the
   agent does not guess parameter names.

   The **rendered shape is unchanged** by D2.29 (`:type \"join\"`,
   `:description \"→ target\"`, `:params`, `:limit`, `:order`) — what changed is
   only where the engine keeps these: one `:attributes` registry instead of a
   per-namespace navigation registry, rendered out as catalog `:joins`.  An
   agent that has been reading these rows must not be able to tell.

   Contract 2.0 adds three things a single-target 1.x row never carries, so
   they are additive: a union's `:targets` with the description above
   (§6.1), the declared `:recursion_depth` ceiling of a recursive join (§6.4
   — the gate clamps a deeper request to it, so the agent should know the
   number before asking), and the producer's `:priority` / `:batch`."
  [attrs join target decls]
  (let [d (get decls join)
        union? (vector? target)]
    (cond-> {:entity      (namespace join)
             :attribute   (attr-name join)
             :type        "join"
             :description (if union?
                            (union-description attrs join target)
                            (str "→ " target))}
      union?
      (assoc :targets target)
      (seq (:eql-params d))
      (assoc :params (mapv #(param-entry attrs %) (:eql-params d)))
      (:limit d) (assoc :limit (:limit d))
      (:order d) (assoc :order (pr-str (:order d)))
      (:recursion-depth d) (assoc :recursion_depth (:recursion-depth d))
      d (merge (producer-keys d)))))

;; ---------------------------------------------------------------------------
;; The three discovery answers
;; ---------------------------------------------------------------------------

(defn- entity-rows
  [cat]
  (let [by-entity (group-by (comp :entity val) (:attributes cat))]
    (vec (for [[ename {:keys [label description kind]}] (:entities cat)
               ;; An entity with nothing reachable in it is not a place the
               ;; agent can go; listing it only invites a query that cannot
               ;; be answered.
               :when (seq (get by-entity ename))]
           (cond-> {:entity ename
                    :description (if (seq (str description))
                                   (str (or label ename) " — " description)
                                   (or label ename))
                    :attribute_count (count (get by-entity ename))}
             kind (assoc :kind (name kind))
             (seq (keep (fn [[a d]] (when (and (= ename (:entity d)) (:identity? d)) a))
                        (:attributes cat)))
             (assoc :identity_keys
                    (mapv (comp attr-name key)
                          (filter (fn [[_ d]] (and (= ename (:entity d)) (:identity? d)))
                                  (:attributes cat)))))))))

(defn entity-registry
  "Vector of entity descriptors for `list_entities`.

   The two-argument arity marks each row `:thing true` when the MODEL reads it
   back as a thing — one namespace, an identity (one attribute, or SEVERAL
   together: doc 106 §106.14 item 14), something that produces it (doc 106
   §106.5). The criterion is `dialect/need-view`'s and not a second
   one written here: §106.5 rules that the Builder's `things` view and this
   list must agree, because an agent told a namespace is an entity here and a
   fragment there will query the one that does not exist.

   `:identity_keys` comes from the SAME view when a model is at hand. It has
   always been a vector, but the catalog can only see attributes filed UNDER
   this entity, and since item 18 part of a key may be another thing's
   attribute carried into these rows — the templated host. Read off the
   catalog alone, `[host id]` came back as `[id]`, and an agent keying on it
   would ask for one instance's row and be answered by whichever host the
   query happened to bind. The one-argument arity (no model) keeps the
   catalog's own answer, which is all it can honestly give.

   **Additive, and the rows are otherwise untouched**: `:entity`,
   `:description`, `:attribute_count` and `:kind` are what an agent has been
   reading for a long time, and P8a is a read-only round."
  ([cat] (entity-registry cat nil))
  ([cat model]
   (let [view  (when model (dialect/need-view model (spi/carried-attributes model)))
         ident (into {} (for [t (:things view)] [(:name t) (:identity t)]))]
     (mapv (fn [row]
             (if-let [keys* (get ident (:entity row))]
               (cond-> (assoc row :thing true)
                 (seq keys*) (assoc :identity_keys keys*))
               row))
           (entity-rows cat)))))

(defn attributes-for-entities
  "Scalar attributes plus joins for the named entities — `list_attributes`.
   An unknown name yields nothing rather than an error: the agent is
   exploring, and a hard failure on one bad guess loses the good ones too.

   Catalog `:attributes` is **scalar-only** since D2.29, so each attribute
   yields exactly ONE row: the declared collections (`all_parts`, `all_msos`, …)
   used to appear twice — once as a `:type \"json\"` attribute row and once as a
   navigation row — and an agent reading both had to decide which of the two it
   was looking at.  One kind, one row."
  [cat decls entity-names]
  (let [wanted (set entity-names)
        attrs  (:attributes cat)]
    (vec (concat
          (for [[attr d] attrs :when (wanted (:entity d))]
            (attr-entry attrs attr d (get decls attr)))
          (for [[join target] (:joins cat) :when (wanted (namespace join))]
            (join-entry attrs join target decls))))))

;; ---------------------------------------------------------------------------
;; search_attributes — the real index (D7.10), not a second search
;; ---------------------------------------------------------------------------

(def ^:private index-cache-limit
  "How many graph versions keep a live index. Each index holds an in-memory
   SQLite connection, and a server serves few versions at a time — a bound is
   here so a long-running instance that rolls versions does not accumulate one
   connection per version ever served."
  4)

(defonce ^:private indexes
  ;; `{[graph-id version] (delay index)}`. A delay, not the index itself: two
  ;; concurrent first calls on the same version must build ONE index, or the
  ;; loser's SQLite connection is leaked with no handle left to close it.
  (atom {}))

(defn- retire! [ds]
  (doseq [d ds] (when (realized? d) (search/close-index! @d))))

(defn search-index
  "The search index for one pinned version, built once and cached under
   `cache-key` = `[graph-id version]`.

   **Rebuilt from the loaded artifact, never shipped with it** (D7.10): the
   index is derived data, fully determined by the model, and shipping it would
   make the same graph's artifact differ byte-for-byte across tokenizer
   versions — which is D4.3's identity. The cache key is the version, so a new
   version simply misses and builds; there is no invalidation to get wrong, and
   `search` itself refuses a revision mismatch rather than answering staler
   (common/search 裁定 ④).

   The index is built from the SAME compiled env's model the catalog is read
   from, so search and catalog cannot disagree about what exists — a hit the
   catalog then denies as `:unknown-attribute` is indistinguishable, to the
   agent, from a name it made up (D2.28)."
  [cache-key model revision]
  (let [d (delay (search/build-index model revision))
        [old new*] (swap-vals! indexes
                               (fn [m]
                                 (cond
                                   (contains? m cache-key)       m
                                   (>= (count m) index-cache-limit) {cache-key d}
                                   :else                         (assoc m cache-key d))))]
    (when-not (identical? old new*)
      (retire! (vals (apply dissoc old (keys new*)))))
    @(get new* cache-key)))

(defn- binding-row [{:keys [attribute term-context]}]
  (cond-> {:entity (namespace attribute) :attribute (attr-name attribute)}
    term-context (assoc :term_context term-context)))

(defn- search-hit
  "One search hit, rendered for the agent.

   **A term hit is ONE row carrying its bindings**, not one row per bound
   attribute (D7.11): `created_by` binds 381 attributes in the real CIS, and
   expanding it would bury every other hit under 381 identical ones. `:match`
   says which bucket it came from — `exact-name` is identity, the rest are
   BM25 order within their type. **No score is rendered**, because none is
   returned: no threshold means the same thing twice (common/search 裁定 ②)."
  [{:keys [type bucket id label entity term term-context binding-count bindings]}]
  (let [base (cond-> {:kind (name type) :match (name bucket)}
               label (assoc :label label))]
    (case type
      :term      (assoc base :term (str id)
                        :binding_count binding-count
                        :bindings (mapv binding-row bindings))
      :entity    (assoc base :entity (str id))
      :attribute (cond-> (assoc base :entity entity :attribute (attr-name id))
                   term         (assoc :term (str term))
                   term-context (assoc :term_context term-context)))))

(defn search-attributes
  "`search_attributes` — locate a business concept in the graph.

   Backed by `open.atlas.common.search` (D7.10). It replaced a substring scan
   this module carried over from `attr-search` — lowercase `includes?`, a 0-3
   rank, and a silent `take 50`. That scan was a **second match layer** beside
   the one common/search exists to be the only copy of (common/search 裁定 ③),
   it flooded on multi-bound terms that D7.11 solves structurally, and its
   truncation broke 「超限拒绝而非截断」: an agent cannot tell a cut list from
   a complete one, and it is reading the list precisely to choose among them.

   Over the envelope this returns the structured finding instead of a partial
   answer — the finding names the limit and the actual count, and narrowing
   the query is the caller's move."
  [cache-key model revision query limit]
  (let [idx (search-index cache-key model revision)
        r   (search/search idx query revision (cond-> {} limit (assoc :max-hits limit)))]
    (if (seq (:findings r))
      {:error "the search was refused" :findings (vec (:findings r))}
      {:query query :hits (mapv search-hit (:hits r))})))

;; ---------------------------------------------------------------------------
;; Source of truth
;; ---------------------------------------------------------------------------

(defn- producer-facts
  "What contract 2.0 lets a definition say about itself beyond its
   parameters, read for rendering only: `:priority` (§4.4, on the model
   resolver) and `:batch?` (§3.2 — the model carries no such key; its
   lowering is the source's `:batch` block, which is therefore what
   \"declares batch\" here). Keyed by resolver id."
  [model]
  (into {}
        (for [r (:resolvers model)]
          [(:id r) (cond-> {:input (:input r)}
                     (contains? r :priority) (assoc :priority (:priority r))
                     (get-in r [:source :batch]) (assoc :batch? true))])))

(defn of
  "`(of compiled)` → `{:catalog … :param-decls …}` for the pinned version.
   Both come off the SAME compiled env the query path uses — reading them
   from anywhere else is how a catalog starts disagreeing with what a query
   actually returns.

   `:param-decls` is the engine's per-key declaration of the PREFERRED
   producer (2.0 §4), widened here with what the rendering needs and the
   engine's gate does not: that producer's `:priority` and `:batch?`, and
   the `:recursion-depth` the bundle declares for a recursive join (§6.4).
   Widened, not rebuilt — the keys the gate checks stay the engine's own."
  [compiled]
  (let [bundle (:engine/bundle compiled)
        facts  (producer-facts (:engine/model compiled))
        depths (:recursion-decls bundle)]
    {:catalog     (engine/catalog compiled)
     ;; A recursive join nested inside another resolver's output has a
     ;; depth but no declaration of its own; it still gets a (depth-only)
     ;; entry, so the row can say the ceiling.
     :param-decls (merge-with merge
                              (into {}
                                    (for [[k d] (:param-decls bundle)]
                                      [k (merge d (get facts (:resolver-id d)))]))
                              (into {} (for [[k n] depths] [k {:recursion-depth n}])))}))

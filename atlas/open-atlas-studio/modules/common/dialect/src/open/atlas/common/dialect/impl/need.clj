(ns open.atlas.common.dialect.impl.need
  "**Reading a model back as the four words** — thing / attribute / relation /
   concept (doc 106 §106.5, P8a).

   One implementation, two callers. The data plane's `list_entities` has been
   using the approximation «namespace = entity» and the Builder's
   `inspect_graph` needs the same answer: §106.5 says in as many words that a
   second criterion would make one graph five things in the Builder and six
   entities over MCP, and an agent would then go query the one that does not
   exist. It lives in `common/dialect` because it reads NOTHING but a dialect
   model — no pool, no runtime, no catalog — and because `common` is the only
   root both callers may depend on (D1.8).

   Pure and deterministic: same model in, byte-identical maps out. Every
   collection is ordered by the printed name so two calls can be diffed.

   ## The criterion (§106.5)

   - **Thing** — a namespace with an `:identity? true` attribute AND at least
     one resolver whose `:output` produces an attribute of it. SEVERAL
     `:identity? true` attributes in one namespace are ONE compound key, so
     `:identity` is always a vector (owner 2026-09-07, doc 106 §106.14 item
     14). Where the identity was never declared, SHAPE is evidence: a
     `:result_type \"single\"` resolver whose `:input` is entirely attributes
     of the namespace is a
     by-id read, and that whole input is the identity. The 2026-09-06 NiFi
     check (§106.13) is why this branch exists — read literally, a
     15-namespace graph came back as ONE thing and fourteen fragments. Shape
     evidence produces a finding rather than a silent promotion: `:identity?`
     stays a declaration, and the finding points out who has not written it.
   - **Attribute** — a non-join attribute in a thing's namespace.
   - **Relation** — a join attribute (`:target`); `:cardinality` says «has
     many» or «belongs to / has one». A join attribute must hang on the
     `:input` namespace of the resolver that produces it; one hanging on the
     CHILD namespace is reported for repair instead of read as a relation of
     the child.
   - **Root collection** — a resolver with empty `:input` producing
     `{X/all_x [...]}`. It is the thing's list entry point, NOT a relation:
     nothing owns it.
   - **Concept** — the `:terms` registry plus each attribute's `:term` /
     `:term-context` (D2.26).
   - **Fragment** — everything that reads back as none of the above, each
     carrying the `:kind` of fragment it is: §106.5 makes the fragment count a
     graph's distance from the user's language, and a count without kinds
     cannot be acted on. One kind is **legal**: a keyless namespace some
     relation points at (a queue history, a sampling point) is a `:series`
     carrying the `:parent` that reaches it — grammatically A1 «all of the X»
     plus B1's sub-collection, and not a defect (owner 2026-09-07, doc 106
     §106.14 item 10).

   Nothing here REFUSES: a model that reads back badly is still a model. The
   two findings are `:warning` — they name a defect and its repair (8.4.21),
   they do not withhold the view."
  (:require [clojure.string :as str]
            [open.atlas.common.dialect.impl.finding :as f]))

;; ---------------------------------------------------------------------------
;; Reading the model
;; ---------------------------------------------------------------------------

(defn- kw->str [kw] (when kw (str (symbol kw))))

(defn- join? [attr-def] (or (contains? attr-def :target) (contains? attr-def :targets)))

(defn- attr-ns [akw] (some-> akw namespace))

(defn output-top-keys
  "What a resolver produces DIRECTLY: the scalar keys at the top level of
   `:output`, plus the branch keys at the top level.

   Leaves nested UNDER a branch are deliberately not counted as produced here
   — they are the child's own attributes, listed so a caller knows what nesting
   into the branch returns. Counting them would make every child attribute
   «produced by two resolvers» the moment a parent collects the child, which is
   the ordinary shape of a relation and not a fragment."
  [output]
  (into #{} (mapcat (fn [node] (if (map? node) (keys node) [node])))
        (when (coll? output) output)))

(defn- output-namespaces
  "Every namespace a resolver's `:output` mentions, at any depth."
  [r]
  (into #{} (comp (filter keyword?) (keep attr-ns))
        (tree-seq coll? seq (:output r))))

(defn- namespaces-produced
  "Namespaces some resolver produces at all — the thing test's second half: a
   namespace nothing produces is not a place an agent can go."
  [resolvers]
  (into #{} (mapcat output-namespaces) resolvers))

(defn- declared-identities
  "namespace → the VECTOR of its declared identity attributes, ordered by
   printed name.

   Several `:identity? true` attributes in one namespace ARE the key — a
   compound identity, no second dialect key (owner 2026-09-07, doc 106
   §106.14 item 14). One attribute is the degenerate case, and reads back as a
   one-element vector; before the ruling, two declarations named no thing at
   all and the namespace fell to the shape branch or to fragments."
  [model]
  (into {}
        (for [[ns' pairs] (group-by (comp attr-ns key)
                                    (filter (fn [[_ d]] (and (:identity? d) (not (join? d))))
                                            (:attributes model)))]
          [ns' (vec (sort-by kw->str (map key pairs)))])))

(defn- shape-identities
  "namespace → the attribute VECTOR a by-id resolver reads it by (§106.5 形状证据).

   A `:result_type \"single\"` resolver whose `:input` is ENTIRELY attributes of
   one namespace it also produces is a by-id read, and that whole input is the
   identity — widened from «exactly one attribute» so a compound key reads back
   as shape evidence too (owner 2026-09-07, doc 106 §106.14 item 14). An input
   that also carries an attribute of some OTHER namespace is not evidence: that
   resolver is reached from elsewhere, and its own namespace's key is not what
   the whole input names.

   **A CARRIED attribute is not «some other namespace»** (owner 2026-09-07, doc
   106 §106.14 item 18): an attribute the engine writes into these very rows —
   the templated host, an ancestor's key — is in the `:input` because the row
   holds it, so it is set aside before the single-namespace test and re-joined
   by `identity-vector`. Without this every thing on a templated source lost
   its shape evidence the moment the host joined the input.

   Two different candidates for one namespace mean the shape says nothing
   definite, so nothing is inferred — evidence that points two ways is not
   evidence."
  [model carried]
  (let [cands (for [r (:resolvers model)
                    :let [in (:input r)]
                    :when (and (= "single" (str (:result_type r)))
                               (seq in)
                               (every? keyword? in))
                    ns' (output-namespaces r)
                    :let [own (vec (remove (set (get carried ns')) in))]
                    :when (and (seq own)
                               (every? #(= ns' (attr-ns %)) own)
                               (not-any? #(join? (get-in model [:attributes %])) own))]
                [ns' (vec (sort-by kw->str own))])]
    (into {}
          (for [[ns' pairs] (group-by first cands)
                :let [attrs (distinct (map second pairs))]
                :when (= 1 (count attrs))]
            [ns' (first attrs)]))))

(defn by-key-resolver-of
  "The resolver that reads ONE thing of `ns'` by its key — the evidence item 18
   reads a compound identity out of.

   «By key» is: a `:result_type \"single\"` resolver that produces the
   namespace, takes every one of its identity attributes in, and takes in
   nothing else except attributes of that namespace or attributes CARRIED into
   its rows. Deterministic by id when a thing is fetched from several places,
   for the same reason the write side picks the first: which of my own lookups
   names the key is not a modelling judgement."
  [model carried ns' own-ids]
  (let [want (set own-ids)
        ok?  (into want (get carried ns'))]
    (first (sort-by :id
                    (filter (fn [r]
                              (let [in (:input r)]
                                (and (= "single" (str (:result_type r)))
                                     (seq in)
                                     (every? keyword? in)
                                     (contains? (output-namespaces r) ns')
                                     ;; every part of the key is taken in …
                                     (every? (set in) want)
                                     ;; … and nothing else but this namespace's
                                     ;; own attributes or a carried one.
                                     (every? #(or (= ns' (attr-ns %)) (ok? %)) in))))
                            (:resolvers model))))))

(defn- identity-vector
  "The full key of `ns'` — its own declared identity attributes UNION the
   attributes carried into its rows that its by-key resolver takes in, in that
   resolver's `:input` order (owner 2026-09-07, doc 106 §106.14 item 18).

   Before the ruling `[host id]` was «writable but not readable back»: the
   write side put the host into the lookup's `:input`, and this view answered
   `[id]` because the host lives in another namespace. The declaration did not
   change — listing it in the `:input` IS how it is said — only the criterion
   widened by one clause. A thing with no by-key resolver (a root collection
   keyed by a declared attribute, §106.12 item 7.7) keeps its own key, sorted.

   `:input` order, not sorted: the input is the order the modeller said the key
   in, and it is the order every template, `WHERE` clause and `:match-on`
   already uses (item 14)."
  [model carried ns' own-ids]
  (if-let [r (by-key-resolver-of model carried ns' own-ids)]
    (let [keep? (into (set own-ids) (get carried ns'))]
      (filterv keep? (:input r)))
    (vec own-ids)))

(defn- root-collections
  "join attribute → the id of the resolver that produces it with NO `:input`.

   These are entry points, not relations (§106.5): `{X/all_x [...]}` off an
   empty input is «the list of all X», and no thing owns it."
  [model]
  (into {}
        (for [r (:resolvers model)
              :when (empty? (:input r))
              b (output-top-keys (:output r))
              :when (join? (get-in model [:attributes b]))]
          [b (:id r)])))

(defn- join-producers
  "join attribute → `{:resolver id :input-namespaces #{ns …}}` for the resolver
   that produces it from a non-empty `:input` — the placement rule's evidence."
  [model]
  (into {}
        (for [r (:resolvers model)
              :when (seq (:input r))
              b (output-top-keys (:output r))
              :when (join? (get-in model [:attributes b]))]
          [b {:resolver (:id r)
              :input-namespaces (into #{} (keep attr-ns) (:input r))}])))

(defn- cardinality->kind
  "`:one-to-many` → «has many», `:one-to-one` → «belongs to / has one».

   An undeclared cardinality reads as `:many`: a join with no cardinality is a
   collection everywhere else in the stack, and answering nil here would make
   each caller invent its own default (CLAUDE.md「不要各写一份」)."
  [c]
  (if (= :one-to-one c) :one :many))

;; ---------------------------------------------------------------------------
;; The view
;; ---------------------------------------------------------------------------

(defn- relation-entry [akw attr-def]
  ;; Semantic contract 2.0 §6.1: a union relation reaches several things; the
  ;; view says so as a sorted vector rather than picking one.
  {:name (kw->str akw)
   :kind (cardinality->kind (:cardinality attr-def))
   :target (or (:target attr-def) (vec (sort (:targets attr-def))))})

(defn- concepts-of
  "The `:terms` registry with the attributes bound to each (D2.26). A term
   nothing binds is still a concept — it was registered on purpose."
  [model]
  (let [bound (group-by (comp :term val)
                        (filter (comp :term val) (:attributes model)))]
    (vec (for [[tid tdef] (sort-by (comp str key) (:terms model))]
           {:concept tid
            :definition (select-keys tdef [:label :description :type :format :unit])
            :attributes (vec (sort (map (comp kw->str key) (get bound tid))))
            :contexts (into (sorted-map)
                            (for [[akw d] (get bound tid)
                                  :when (:term-context d)]
                              [(kw->str akw) (:term-context d)]))}))))

(defn need-view
  "Dialect model → `{:things :fragments :concepts :findings}` (§106.5).

   `carried` is `{namespace #{attribute …}}` — what the engine writes into the
   rows of each namespace rather than reading out of a record (doc 106 §106.14
   items 13 and 17). Item 18 makes such an attribute eligible to be part of a
   thing's key, so the view needs it; deciding WHICH attributes those are is a
   source-dialect judgement behind the SPI, and every caller therefore passes
   `open.atlas.common.connector.spi/carried-attributes` of the same model.
   The one-argument arity answers `{}` — correct for every model with no
   templated source and no in-record collection, and the shape a caller with
   no connector on its classpath can still ask for."
  ([model] (need-view model {}))
  ([model carried]
   (let [attrs        (:attributes model)
        resolvers    (:resolvers model)
        produced-nss (namespaces-produced resolvers)
        declared     (declared-identities model)
        shaped       (shape-identities model carried)
        roots        (root-collections model)
        jprod        (join-producers model)
        ;; Declared beats shape: `:identity?` is the statement, the shape is
        ;; only evidence for a statement nobody made.
        own-ident    (merge shaped declared)
        ;; The FULL key, item 18: own declarations plus whatever the by-key
        ;; resolver carries in. `own-ident` stays the basis for everything that
        ;; asks «is this namespace a thing» and for the series test, because
        ;; those turn on the namespace having a key of its own.
        ident        (into {} (for [[ns' own] own-ident]
                                [ns' (identity-vector model carried ns' own)]))
        thing-nss    (into (sorted-set) (filter produced-nss) (keys own-ident))
        by-ns        (group-by (comp attr-ns key) attrs)
        misplaced    (for [[akw p] jprod
                           :let [ns' (attr-ns akw)]
                           :when (and (seq (:input-namespaces p))
                                      (not (contains? (:input-namespaces p) ns')))]
                       {:attribute akw :producer p})
        misplaced-set (into #{} (map :attribute) misplaced)
        things
        (vec (for [ns' thing-nss
                   :let [own  (get by-ns ns')
                         ;; A misplaced join is NOT listed as a relation of the
                         ;; namespace it wrongly hangs on: reading it there
                         ;; would make the defect look like a modelled fact.
                         rels (for [[akw d] own
                                    :when (and (join? d)
                                               (not (contains? roots akw))
                                               (not (contains? misplaced-set akw)))]
                                (relation-entry akw d))
                         list-attr (first (sort (for [[akw _] own
                                                      :when (contains? roots akw)]
                                                  (kw->str akw))))]]
               (cond-> {:name ns'
                        ;; A VECTOR, always — one element for a single key
                        ;; (doc 106 §106.14 item 14).
                        :identity (mapv kw->str (get ident ns'))
                        :identity-declared? (contains? declared ns')
                        :attributes (vec (sort (for [[akw d] own
                                                     :when (not (join? d))]
                                                 (kw->str akw))))
                        :relations (vec (sort-by :name rels))
                        :list? (some? list-attr)}
                 list-attr (assoc :list list-attr))))

        ;; ── fragments, one `:kind` each ────────────────────────────────────
        root-by-ns (into {} (for [[akw _] roots] [(attr-ns akw) (kw->str akw)]))
        ;; **A series is a legal fragment, not a defect** (owner 2026-09-07,
        ;; doc 106 §106.14 item 10). A namespace with no identity that some
        ;; relation POINTS AT — a queue history, a sampling point — is what A1
        ;; «所有的 X» plus B1's sub-collection reads back as: the parent names
        ;; it, and the points themselves are told apart by nothing. Naming the
        ;; parent is the whole difference between «this hangs off connection»
        ;; and «nobody can reach this», so it is carried on the fragment.
        series-parents
        (into {}
              ;; Deterministic when two relations point at one namespace: the
              ;; first by printed name, like every other ordering here.
              (for [[ns' pairs] (group-by (comp :target val)
                                          (filter (fn [[akw d]]
                                                    (and (join? d)
                                                         (:target d)
                                                         (not (contains? roots akw))))
                                                  attrs))
                    ;; Only a KEYLESS fragment is a series: a namespace that
                    ;; does have an identity and still reads back as a fragment
                    ;; is missing a producer, and calling that a series would
                    ;; rename a defect (the ruling says `:no-identity` in as
                    ;; many words).
                    :when (and ns' (not (contains? own-ident (str ns'))))]
                [(str ns') (first (sort (map (comp kw->str key) pairs)))]))
        frag-ns
        (for [ns' (sort (distinct (concat (map str (keys (:namespaces model)))
                                          (keep attr-ns (keys attrs)))))
              :when (not (contains? thing-nss ns'))]
          ;; A root collection on a fragment is still the way in: reporting the
          ;; fragment WITHOUT it would hide the one thing a caller can do here.
          (cond-> {:kind (if (series-parents ns') :series :no-identity)
                   :name ns'
                   :why (if (series-parents ns')
                          (str "no identity of its own, and " (series-parents ns')
                               " points at it — a series read through its parent")
                          (str "nothing declares :identity? here and no single-result resolver "
                               "reads it by one of its own attributes"))}
            (series-parents ns') (assoc :parent (series-parents ns'))
            (root-by-ns ns') (assoc :list (root-by-ns ns'))))

        derived-only
        (let [with-source (into #{} (comp (filter #(or (:source %) (:source-ref %)))
                                          (mapcat #(output-top-keys (:output %))))
                                resolvers)
              derived     (into #{} (comp (remove #(or (:source %) (:source-ref %)))
                                          (mapcat #(output-top-keys (:output %))))
                                resolvers)]
          (for [akw (sort-by kw->str (remove with-source derived))]
            {:kind :derived-without-source
             :name (kw->str akw)
             :why (str "only a resolver with no source produces this attribute, so nothing "
                       "outside the model supplies it")}))

        ;; **Part of an identity that is also a foreign key is not produced
        ;; twice** (owner 2026-09-07, doc 106 §106.14 item 14). When a key
        ;; attribute of one thing is ALSO the leaf a relation on some other
        ;; namespace reads the parent by (D2.29's `{A/b [B/k]}`), two
        ;; resolvers legitimately carry it: the thing's own, and the child
        ;; that already holds the parent's key in its row. That is the
        ;; canonical belongs-to, not an unstated choice of producer.
        fk-identity
        (let [id-attrs (into #{} cat (vals own-ident))]
          (into #{}
                (for [r resolvers
                      node (tree-seq coll? seq (:output r))
                      :when (map? node)
                      [bkw leaves] node
                      :let [bns (attr-ns bkw)]
                      leaf (filter keyword? (tree-seq coll? seq leaves))
                      :when (and (contains? id-attrs leaf)
                                 bns
                                 (not= bns (attr-ns leaf)))]
                  leaf)))

        multi
        (let [counts (frequencies (mapcat #(output-top-keys (:output %)) resolvers))]
          (for [akw (sort-by kw->str (keys (filter #(and (> (val %) 1)
                                                         (not (fk-identity (key %))))
                                                   counts)))]
            {:kind :produced-twice
             :name (kw->str akw)
             :why (str "produced directly by " (get counts akw)
                       " resolvers, so which of them answers is not stated")}))

        findings
        (concat
         ;; One finding per undeclared attribute, not per namespace: the
         ;; repair declares ONE attribute (`:identity?` is an attribute key),
         ;; so a compound key needs one call per part.
         (for [ns' (sort (keys shaped))
               :when (and (contains? thing-nss ns') (not (contains? declared ns')))
               akw (get shaped ns')]
           (f/make :dialect.need/identity-undeclared :warning
                   [:attributes akw]
                   (str "this reads as the identity of " ns'
                        " — a single-result resolver reads that namespace by it — "
                        "but nothing declares it")
                   :expected "mark :identity? true"
                   :got (kw->str akw)
                   :refs ["doc 106 §106.5" "D2.29"]))
         (for [{:keys [attribute producer]} (sort-by (comp kw->str :attribute) misplaced)]
           (f/make :dialect.need/join-misplaced :warning
                   [:attributes attribute]
                   (str "this relation hangs on " (attr-ns attribute)
                        " but the resolver that produces it reads "
                        (str/join ", " (sort (:input-namespaces producer)))
                        " — a relation belongs to the thing it starts from, "
                        "so move it to the parent namespace")
                   :expected (vec (sort (:input-namespaces producer)))
                   :got (kw->str attribute)
                   :refs ["doc 106 §106.5"])))]
    {:things things
     :fragments (vec (concat frag-ns derived-only multi))
     :concepts (concepts-of model)
     :findings (vec (f/sort-findings findings))})))

(defn thing-namespaces
  "Just the SET of namespaces that read back as things — what `list_entities`
   needs, without building the rest of the view."
  [model]
  (into (sorted-set) (map :name) (:things (need-view model))))

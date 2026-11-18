(ns open.atlas.common.semantic-execution
  "Pure execution-definition validation; no compilation, credentials or IO.
   Connector-specific implementation validation is supplied by the caller."
  (:require [malli.core :as m]
            [clojure.set :as set]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.semantic :as semantic]))

(def ^:private id [:string {:min 1}])
(def ^:private attr [:fn qualified-keyword?])

(def ^:private Policy
  "doc 107 §107.3 semantic half, closed. Malli literal rather than a require of
   the dialect's private schema ns: `:retry_on` is bounded by the single
   vocabulary definition, which is what must not be copied."
  [:map {:closed true}
   [:max_retries {:optional true} nat-int?]
   [:retry_on {:optional true} [:set (into [:enum] (sort dialect/transient-failure-categories))]]
   [:status-map {:optional true}
    [:map-of [:int {:min 400 :max 599}]
     (into [:enum] (sort dialect/status-map-targets))]]
   ;; §107.7: chunk concurrency, the modeller's knowledge about the upstream.
   [:batch {:optional true}
    [:map {:closed true} [:concurrency {:optional true} pos-int?]]]])

(def paging-strategies
  "How an upstream says «there is more» (doc 107 §107.6, owner 2026-09-18).

   `:none` is «this endpoint does not page», and stays the default so nothing
   already published changes meaning. The other four are the shapes real SaaS
   list endpoints actually use; api-v1 had only offset, which is why most of
   them could not be modelled at all."
  #{:none :offset :page :cursor :link-header})

(def paging-schema
  "Closed. `:strategy` alone is not enough to drive a loop -- each strategy
   needs different keys, and which ones is checked by `paging-findings` rather
   than here: a schema can say `:param` is a string, only a rule can say that
   an `:offset` strategy without one is a declaration that cannot be executed."
  [:map {:closed true}
   [:strategy (into [:enum] (sort paging-strategies))]
   [:param {:optional true} [:string {:min 1}]]
   [:next-path {:optional true} [:string {:min 1}]]
   [:size-param {:optional true} [:string {:min 1}]]
   [:page-size {:optional true} [:int {:min 1 :max 10000}]]
   [:snapshot-path {:optional true} [:string {:min 1}]]])

(def collection-schema
  [:map {:closed true}
   [:ordering [:= :source-defined]]
   ;; `:none` stays legal as the bare keyword: every published graph spells it
   ;; that way, and a rewrite of settled corpus to say the same thing in a new
   ;; shape buys nothing (dialect-v1 §6).
   [:paging [:or [:= :none] paging-schema]]
   [:completeness [:= :not-guaranteed]]
   [:bounds [:map {:closed true}
             [:max-items [:int {:min 1 :max 10000}]]
             [:max-response-bytes [:int {:min 1 :max 4194304}]]
             [:timeout-ms [:int {:min 1 :max 30000}]]
             ;; §107.6: an explicit ceiling on REQUESTS, empty pages included.
             ;; Without it a sparse filter that answers empty-with-a-next
             ;; forever is bounded only by `:timeout-ms`, i.e. by how long the
             ;; caller is willing to wait rather than by anything declared.
             [:max-pages {:optional true} [:int {:min 1 :max 10000}]]]]
   ;; Optional ONLY where an adapter really implements a caller-controlled limit.
   [:limit {:optional true} [:map {:closed true} [:default pos-int?] [:max pos-int?]]]])

(defn paging-strategy
  "The declared strategy of a `:collection` block, `:none` when it does not page."
  [collection]
  (let [p (:paging collection)]
    (cond (map? p) (:strategy p) (= :none p) :none :else :none)))

(defn paging-findings
  "What each strategy needs to be executable (doc 107 §107.6).

   Refused at write time rather than at the first query: a `:cursor` strategy
   with no `:next-path` cannot read the next cursor out of any response, so the
   loop would stop after one page and silently answer a truncated collection --
   which is the one outcome D2.9 forbids above all others."
  [path collection]
  (let [p (:paging collection)
        strategy (paging-strategy collection)
        needs (fn [k why]
                (when-not (get p k)
                  [{:code :execution/collection-mode :severity :error
                    :path (conj (vec path) :paging k)
                    :message (str (name strategy) " paging needs " k ": " why)}]))]
    (when (map? p)
      (vec
       (concat
        (case strategy
          (:offset :page) (concat (needs :param "the request has to carry the offset or page number")
                                  (needs :size-param "a page of unknown size cannot be counted toward :max-items"))
          :cursor (needs :next-path "there is nowhere to read the next cursor from")
          ;; `:link-header` reads `Link: rel=next`, so a `:param` or
          ;; `:next-path` would be a declaration nothing consults.
          :link-header (for [k [:param :next-path]
                             :when (get p k)]
                         {:code :execution/collection-mode :severity :error
                          :path (conj (vec path) :paging k)
                          :message (str "link-header paging takes its next page from the "
                                        "Link header; " k " would be ignored")})
          nil)
        ;; §107.6: the request ceiling is not optional once there IS a loop.
        (when (and (not= :none strategy)
                   (nil? (get-in collection [:bounds :max-pages])))
          [{:code :execution/collection-mode :severity :error
            :path (conj (vec path) :bounds :max-pages)
            :message "a paging strategy needs :bounds :max-pages: empty pages count, and nothing else bounds the number of requests"}]))))))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §2 (doc 109 §109.8 items 1-2, owner 2026-09-18): `:input`
;; ---------------------------------------------------------------------------
;;
;; Three element forms. ① a qualified keyword (1.x unchanged); ② a CLOSED map
;; `{:attribute a :optional? true}` -- `:optional? false` is refused because it
;; would be a second spelling of ①; ③ a single-key map `{ref-attr [children]}`
;; whose children are ① or ③ again, never ②: optional lives at the root level
;; only. The four pure functions below are the ONLY reading of this syntax --
;; the compiler, the validator and the connectors all go through them, so the
;; 1.x flat vector and the 2.0 vector answer the same questions.

(defn- optional-input? [x]
  (and (map? x) (= #{:attribute :optional?} (set (keys x)))
       (qualified-keyword? (:attribute x)) (true? (:optional? x))))

(defn- nested-input? [x]
  (and (map? x) (= 1 (count x)) (qualified-keyword? (ffirst x))
       (let [children (val (first x))]
         (and (vector? children) (seq children)
              (every? #(or (qualified-keyword? %) (nested-input? %)) children)))))

(defn- input-v2? [x]
  (and (vector? x)
       (every? #(or (qualified-keyword? %) (optional-input? %) (nested-input? %)) x)))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §6 (doc 109 §109.8 item 3, owner 2026-09-18): `:output` nodes
;; ---------------------------------------------------------------------------
;;
;; Two node forms join the 1.x leaf and join. ④ a union `{ref {entity-id
;; [subtree]}}` -- the key is a multi-target ref, each branch is the subtree
;; answered when the record is that Entity; ⑤ a recursion `{ref n}` -- the key
;; is a self-referencing ref and `n` the depth ceiling the author declares.
;; The readers below are the ONLY parsing of this syntax: the validator, the
;; contract layer and the compiler all go through them.

(declare output-v2?)

(defn- node-key [node] (if (keyword? node) node (ffirst node)))
(defn- node-value [node] (when (map? node) (val (first node))))

(defn- single-key-node? [x]
  (and (map? x) (= 1 (count x)) (qualified-keyword? (ffirst x))))

(defn- join-node? [x] (and (single-key-node? x) (vector? (node-value x))))

(defn- union-node? [x]
  (and (single-key-node? x)
       (let [branches (node-value x)]
         (and (map? branches) (seq branches)
              (every? string? (keys branches))
              (every? output-v2? (vals branches))))))

(defn- recursion-node? [x]
  (and (single-key-node? x) (integer? (node-value x))))

(defn- output-v2? [x]
  (and (vector? x)
       (every? #(or (qualified-keyword? %)
                    (and (join-node? %) (output-v2? (node-value %)))
                    (union-node? %)
                    (recursion-node? %))
               x)))

(defn- subtrees
  "The vectors one node opens: a join's children, a union's branches, none for
   a leaf or a recursion node (whose shape is this level's own)."
  [node]
  (let [v (node-value node)]
    (cond (vector? v) [v] (map? v) (vals v) :else [])))

(defn output-attrs
  "Every attribute an `:output` names: leaves, join keys and their subtrees,
   union keys and every branch's subtree, recursion keys. A recursion node adds
   its key and nothing below it -- it reuses this level's already-mapped
   attributes (contract 2.0 §6.3)."
  [output]
  (mapcat (fn [node] (cons (node-key node) (mapcat output-attrs (subtrees node)))) output))

(defn- keys-where [pred output]
  (into #{} (mapcat (fn [node] (concat (when (pred node) [(node-key node)])
                                       (mapcat #(keys-where pred %) (subtrees node)))))
        output))

(defn output-union
  "The set of union (④) keys anywhere in an `:output`."
  [output]
  (keys-where union-node? output))

(defn output-recursion
  "The set of recursion (⑤) keys anywhere in an `:output`."
  [output]
  (keys-where recursion-node? output))

(defn recursion-depths
  "`{ref depth}` for every recursion node in an `:output`; the smallest depth
   wins when one key is declared twice."
  [output]
  (reduce (fn [acc node]
            (let [below (apply merge-with min {} (map recursion-depths (subtrees node)))]
              (cond-> (merge-with min acc below)
                (recursion-node? node) (update (node-key node) (fnil min Long/MAX_VALUE) (node-value node)))))
          {} output))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §3 (doc 109 §109.8 items 5 and 7, owner 2026-09-18): `:params`
;; and `:batch?`
;; ---------------------------------------------------------------------------

(def param-types
  "Contract 2.0 §3.1: the attribute type vocabulary minus `:ref` and `:json`.
   A free parameter is a scalar the caller types in; a ref or a json blob is
   neither."
  [:string :integer :decimal :boolean :date :datetime])

(def ^:private ParamDecl
  "Contract 2.0 §3.1, closed. `:allowed` and `:default` are `:any` here because
   a schema cannot say «the same type as the sibling `:type`»; that is
   `param-findings` below (`:execution/parameter-value`)."
  [:map {:closed true}
   [:type (into [:enum] param-types)]
   [:required? {:optional true} [:= true]]
   [:description {:optional true} id]
   [:allowed {:optional true} [:vector {:min 1} :any]]
   [:default {:optional true} :any]])

(defn- definition
  "One read (`:resolver`) or write (`:operation`) definition, Semantic contract
   2.0 §2-§4 and §6. Standalone since 2026-09-19 (task book PR1): it no longer
   walks the retired 1.0-1.4 schemas it used to be derived from.
   `:batch?` is admitted on a write definition by the schema so that the rule
   can refuse it with `:execution/batch-shape` and a path, rather than as an
   anonymous schema error; `:priority` is not, because an Operation's
   `:returns` never counts as produced (§4.1) and there is nothing to rank."
  [effect]
  (cond-> [:map {:closed true}
           [:label id] [:description {:optional true} id]
           [:effect [:= effect]]
           ;; §2: the three `:input` element forms.
           [:input [:fn input-v2?]]
           [:result/cardinality (if (= effect :read) [:enum :one :many] [:enum :none :one :many])]
           [:pagination {:optional true} [:map {:closed true} [:default pos-int?] [:max pos-int?]]]
           [:sort {:optional true} [:map {:closed true} [:allowed [:vector attr]]
                                   [:default [:tuple attr [:enum :asc :desc]]]]]
           [:filters {:optional true} [:map-of :keyword
                                       [:map {:closed true} [:attribute attr]
                                        [:operator [:enum :eq :in :lt :lte :gt :gte :like]]
                                        [:required? {:optional true} :boolean]
                                        [:description {:optional true} id]]]]
           [:idempotency {:optional true} [:enum :request-key :natural]]
           ;; §6.2: the union and recursion nodes, on a read's `:output` and on
           ;; a write's `:returns` alike.
           [:output [:fn output-v2?]]
           [:curated? {:optional true} :boolean]
           [:collection {:optional true} collection-schema]
           ;; §3.
           [:params {:optional true} [:map-of :keyword ParamDecl]]
           [:batch? {:optional true} [:= true]]]
    ;; §4.
    (= effect :read) (conj [:priority {:optional true} [:int {:min 0}]])))

(def execution-schema-v2
  "The one execution schema, Semantic contract 2.0."
  [:map {:closed true}
   [:resolvers [:map-of id (definition :read)]]
   [:operations [:map-of id (definition :write)]]
   [:sources [:map-of id
              [:map {:closed true}
               [:source/kind id]
               [:source/effects [:set {:min 1} [:enum :read :write]]]
               ;; doc 107 §107.3 (owner 2026-09-18): the SEMANTIC half of the
               ;; policy rides with the artifact -- whether this source may be
               ;; retried is knowledge about the source, so the modeller
               ;; declares it. The capacity half (timeouts, bulkhead, breaker,
               ;; backoff) stays on the deployment's Source Registry entry and
               ;; may only TIGHTEN what is declared here.
               ;;
               ;; **Namespaced**, because a slot entry is a `#:source{...}` map
               ;; and every key in it is (owner ruling on §107.3's shorthand).
               ;; The BINDING-level override is plain `:policy` -- it lives
               ;; inside `:binding/implementation`, whose keys are unnamespaced.
               [:source/policy {:optional true} Policy]]]]
   [:bindings [:map-of id
               [:map {:closed true}
                [:binding/for [:tuple [:enum :resolver :operation] id]]
                [:binding/source id]
                [:binding/effect [:enum :read :write]]
                [:binding/implementation :map]
                [:binding/input-map [:map-of attr id]]
                [:binding/output-map [:map-of attr id]]
                [:binding/parameter-map {:optional true} [:map-of :keyword id]]]]]
   [:selection [:map-of [:tuple [:enum :resolver :operation] id] id]]
   [:metrics {:optional true} [:map-of id
                               [:map {:closed true}
                                [:entity id] [:label id]
                                [:description {:optional true} id]
                                [:expression [:map {:closed true} [:contract [:= :duckdb-spill/v1]] [:text id]]]
                                [:dimensions {:optional true} [:vector attr]]
                                [:grain {:optional true} [:enum :day :week :month :quarter :year]]]]]])

(defn- iso-parsable? [f s]
  (and (string? s)
       (try (f s) true (catch Exception _ false))))

(defn param-value-of-type?
  "Contract 2.0 §3.1: does `v` belong to the parameter type `t`? Dates and
   datetimes are ISO-8601 strings (the only spelling EDN and JSON share) or the
   corresponding java.time values."
  [t v]
  (case t
    :string (string? v)
    :integer (integer? v)
    :decimal (number? v)
    :boolean (boolean? v)
    :date (or (instance? java.time.LocalDate v)
              (iso-parsable? #(java.time.LocalDate/parse %) v))
    :datetime (or (instance? java.time.Instant v)
                  (instance? java.time.OffsetDateTime v)
                  (instance? java.time.LocalDateTime v)
                  (iso-parsable? #(java.time.OffsetDateTime/parse %) v)
                  (iso-parsable? #(java.time.Instant/parse %) v)
                  (iso-parsable? #(java.time.LocalDateTime/parse %) v))
    false))

(defn input-roots
  "The root attribute of every `:input` element, in declaration order: ① itself,
   ②'s `:attribute`, ③'s key. For a 1.x flat vector this is the vector itself."
  [input]
  (mapv #(cond (keyword? %) %
               (contains? % :attribute) (:attribute %)
               :else (ffirst %))
        input))

(defn input-optional
  "The set of roots declared optional (form ②)."
  [input]
  (into #{} (comp (filter optional-input?) (map :attribute)) input))

(defn input-nested
  "`{root children}` for every nested element (form ③)."
  [input]
  (into {} (comp (filter #(and (map? %) (not (contains? % :attribute)))) (map first)) input))

(defn input-attrs
  "Every attribute an `:input` names, roots and subtrees alike -- what has to
   exist in the vocabulary."
  [input]
  (mapcat #(cond (keyword? %) [%]
                 (contains? % :attribute) [(:attribute %)]
                 :else (let [[a children] (first %)] (cons a (input-attrs children))))
          input))

(defn- finding [code path] {:code code :severity :error :path path})

(defn- tree-findings
  "Walk one EQL tree (an `:output`, or a 2.0 nested `:input` subtree) and report
   duplicates, owner mismatches and shape errors under the caller's codes.
   `leaf-ref?` decides whether a bare `:ref` attribute is a shape error: it is
   in an output (a join must open a branch), but a 2.0 `:input` may name a ref
   attribute as a plain root exactly as 1.x could (contract 2.0 §2.1 ①).

   Contract 2.0 §6.2 adds two node forms. A union ④ is legal only on a
   multi-target ref, with exactly the ref's `:attribute/targets` as branch keys
   (`:execution/union-branches`); each branch is validated as that Entity's
   subtree and must carry its identity attribute
   (`:execution/union-identity-required`), because that attribute is what the
   executor discriminates on. A recursion ⑤ is legal only on a ref that reaches
   its own Entity (`:execution/recursion-target`), with a depth of 1..16
   (`:execution/recursion-depth`). A multi-target ref written as a plain join,
   or a single-target ref written as a union, is `:execution/output-shape` like
   any other shape error. Whether the single root join of a `:many` definition
   may be ④ or ⑤ (it may not) is judged by `validate-execution`, which knows
   the cardinality."
  [vocabulary tree owner path {:keys [duplicate owner-code shape leaf-ref?] :as codes}]
  (let [attributes (:attributes vocabulary)
        entities (:entities vocabulary)
        ks (map node-key tree)]
    (concat
     (when (not= (count ks) (count (distinct ks)))
       [(finding duplicate path)])
     (mapcat (fn [[i node]]
               (let [a (node-key node)
                     d (get attributes a) p (conj path i)
                     ref? (= :ref (:attribute/type d))
                     multi? (contains? d :attribute/targets)]
                 (concat
                  (when (and owner (not= owner (namespace a)))
                    [(finding owner-code p)])
                  (cond
                    (nil? d) []                                  ; reported as attribute-reference

                    (keyword? node)
                    (when (and leaf-ref? ref?) [(finding shape p)])

                    (not ref?) [(finding shape p)]

                    (union-node? node)
                    (if-not multi?
                      [(finding shape p)]
                      (let [branches (node-value node)
                            targets (:attribute/targets d)]
                        (concat
                         (when (not= (set (keys branches)) targets)
                           [(finding :execution/union-branches p)])
                         (mapcat (fn [[e sub]]
                                   (when (contains? targets e)
                                     (concat
                                      (when-not (every? (set (filter keyword? sub))
                                                        (get-in entities [e :entity/identity]))
                                        [(finding :execution/union-identity-required (conj p a e))])
                                      (tree-findings vocabulary sub e (conj p a e) codes))))
                                 (sort-by key branches)))))

                    (recursion-node? node)
                    (cond
                      ;; Self-reference: the ref reaches the Entity whose record
                      ;; this level describes -- the key's own namespace, which
                      ;; the owner rule above already holds to `owner`.
                      (not= (namespace a) (:attribute/target d)) [(finding :execution/recursion-target p)]
                      (not (<= 1 (node-value node) 16)) [(finding :execution/recursion-depth p)])

                    multi? [(finding shape p)]

                    :else
                    (tree-findings vocabulary (node-value node) (:attribute/target d) (conj p a) codes)))))
             (map-indexed vector tree)))))

(def ^:private output-codes
  {:duplicate :execution/duplicate-output :owner-code :execution/output-owner
   :shape :execution/output-shape :leaf-ref? true})

(def ^:private nested-input-codes
  ;; Contract 2.0 §2.1 ③: a nested key must be a `:ref` attribute and each
  ;; subtree attribute must belong to the ref's target Entity -- the `:output`
  ;; owner rule applied to an input.
  {:duplicate :execution/duplicate-input :owner-code :execution/input-owner
   :shape :execution/nested-input-root :leaf-ref? false})

(defn- output-findings [vocabulary output owner path]
  (tree-findings vocabulary output owner path output-codes))

(defn- nested-input-findings
  "Contract 2.0 §2.1 ③ over the nested elements of an `:input`: each nested
   root at its own index, its subtree under `[... i root]`."
  [vocabulary input path]
  (mapcat (fn [[i node]]
            (when (and (map? node) (not (contains? node :attribute)))
              (let [[a children] (first node)
                    d (get-in vocabulary [:attributes a]) p (conj path i)]
                (cond
                  (nil? d) []                                   ; reported as attribute-reference
                  (not= :ref (:attribute/type d)) [(finding :execution/nested-input-root p)]
                  :else (tree-findings vocabulary children (:attribute/target d) (conj p a)
                                       nested-input-codes)))))
          (map-indexed vector input)))

(def ^:private reserved-parameters #{:limit :offset :order_by :sort_dir})

(def allowed-param-types
  "Contract 2.0 §3.1 (owner 2026-09-18): the types a parameter may enumerate
   with `:allowed` -- dialect `CodeKey`'s value face. A decimal, date or
   datetime has no finite code list to enumerate."
  #{:string :integer :boolean})

(defn- param-findings
  "Contract 2.0 §3.1 over one definition's `:params`: no reserved name, no
   overlap with `:filters`, `:allowed` only on an enumerable type with elements
   of the declared type, `:default` of the declared type, `:default` and
   `:required?` mutually exclusive, and `:default` a member of `:allowed` when
   both are written. Paths land on the offending key so an author sees which
   of several parameters is wrong."
  [target {:keys [params filters]}]
  (mapcat (fn [[p {:keys [type allowed default required?] :as decl}]]
            (let [at [target :params p]]
              (concat
               (when (reserved-parameters p)
                 [(finding :execution/reserved-parameter at)])
               (when (contains? filters p)
                 [(finding :execution/parameter-collision at)])
               (when (and allowed (or (not (allowed-param-types type))
                                      (not-every? #(param-value-of-type? type %) allowed)))
                 [(finding :execution/parameter-value (conj at :allowed))])
               (when (and (contains? decl :default)
                          (or required?
                              (not (param-value-of-type? type default))
                              (and allowed (not (some #(= default %) allowed)))))
                 [(finding :execution/parameter-value (conj at :default))]))))
          params))

(defn- batch-parent-keys
  "The attributes a `:batch :parent-key` names: one keyword or a compound
   vector (sql-v1 / api-v1). Read here rather than through `spi/batch-keys`
   because the dialect depends on no connector (D1.8)."
  [parent-key]
  (cond (keyword? parent-key) [parent-key]
        (sequential? parent-key) (vec parent-key)
        :else []))

(defn- batch-findings
  "Contract 2.0 §3.2: the definition says `:batch? true` iff the SELECTED
   Binding carries a physical `:batch` block, and that block's `:parent-key`
   names input roots. Each finding lands on the side that is missing."
  [target definition bid binding]
  (let [batch (get-in binding [:binding/implementation :batch])
        at-block [:bindings bid :binding/implementation :batch]]
    (concat
     (when (and (:batch? definition) (nil? batch))
       [(finding :execution/batch-mismatch at-block)])
     (when (and batch (not (:batch? definition)))
       [(finding :execution/batch-mismatch [target :batch?])])
     (when (and (:batch? definition) (map? batch)
                (not-every? (set (input-roots (:input definition)))
                            (batch-parent-keys (:parent-key batch))))
       [(finding :execution/batch-mismatch (conj at-block :parent-key))]))))

(defn produced-keys
  "What a read Resolver PRODUCES in the sense of contract 2.0 §4.1: the
   top-level scalar keys and the top-level join roots of its `:output`. Leaves
   under a join are the child Entity's own attributes and are not counted --
   the same reading `dialect/direct-output-keys` has always given the need
   view, so «overlap» here and «two producers» there are one test."
  [output]
  (dialect/direct-output-keys output))

(defn overlapping-resolvers
  "The pairs of read Resolvers whose produced keys intersect, as
   `[[id-a id-b] #{attr …}]` with `id-a < id-b`. Operations never take part:
   their `:output` is a `:returns`, not a producer (contract 2.0 §4.1)."
  [resolvers]
  (let [ids (sort (keys resolvers))]
    (for [[a & rest] (take-while seq (iterate next ids))
          b rest
          :let [shared (set/intersection
                        (produced-keys (get-in resolvers [a :output]))
                        (produced-keys (get-in resolvers [b :output])))]
          :when (seq shared)]
      [[a b] shared])))

(defn- priority-findings
  "Contract 2.0 §4.1: two Resolvers that produce the same attribute must be
   ranked, so equal `:priority` (absent reads as 0) on an overlapping pair is
   refused on BOTH sides -- the author fixes whichever is wrong, and a finding
   on one of them would send them to the other. Two candidates the planner
   cannot order would be answered by whichever it happened to plan first,
   which is exactly the «second semantics» D1.8 forbids."
  [resolvers]
  (mapcat (fn [[[a b] _]]
            (when (= (get-in resolvers [a :priority] 0) (get-in resolvers [b :priority] 0))
              [(finding :execution/priority-tie [[:resolver a] :priority])
               (finding :execution/priority-tie [[:resolver b] :priority])]))
          (overlapping-resolvers resolvers)))

(defn- checker-finding? [x]
  (and (map? x) (keyword? (:code x)) (keyword? (:severity x)) (vector? (:path x))))

(defn- implementation-findings
  "Run one connector checker over a Binding and normalise its answer.
   Checker protocol (docs/contracts/semantic-execution-v1.md): a map
   {:findings [...]} carries the connector's own codes, severities, messages
   and binding-relative paths, which are re-rooted under [:bindings bid] so an
   author can see what is wrong; legacy true means valid. false, nil, any other
   shape or a missing checker fail closed as :execution/implementation-unsupported.
   A throwing checker is a platform defect, not an authoring error, so it is
   reported as :execution/checker-crashed naming only the exception class:
   the message may carry credentials or values (finding-v1 never echoes values)."
  [checker bid binding]
  (let [at [:bindings bid :binding/implementation]]
    (if-not checker
      [(finding :execution/implementation-unsupported at)]
      (let [result (try {:ok (checker binding)}
                        (catch Throwable t {:crash (.getName (class t))}))]
        (cond
          (:crash result)
          [(assoc (finding :execution/checker-crashed at)
                  :message (str "Implementation checker threw " (:crash result)))]

          (true? (:ok result)) []

          (and (map? (:ok result)) (contains? (:ok result) :findings)
               (sequential? (:findings (:ok result)))
               (every? checker-finding? (:findings (:ok result))))
          (mapv (fn [f]
                  (cond-> (select-keys f [:code :severity :path :message])
                    true (update :path #(into [:bindings bid] %))
                    (not (string? (:message f))) (dissoc :message)))
                (:findings (:ok result)))

          :else [(finding :execution/implementation-unsupported at)])))))

(defn- checker-binding
  "The Binding a connector checker is handed. Under contract 2.0 §2.2 a
   connector must refuse the input forms it cannot bind, and a Binding alone
   does not say which of its input-map roots are optional or nested -- so the
   definition's answer rides along as `:binding/input-optional` (a set) and
   `:binding/input-nested` (`{root children}`), each present only when
   non-empty. Contract 2.0 §6.3 adds the output side the same way:
   `:binding/output-union` (the union keys) and `:binding/output-recursion`
   (the recursion keys), so a connector can refuse the output forms it cannot
   carry. A 1.x definition never has any of the four, so a 1.x checker sees the
   Binding byte-for-byte as before."
  [binding definition]
  (let [optional (input-optional (:input definition))
        nested (input-nested (:input definition))
        union (output-union (:output definition))
        recursion (output-recursion (:output definition))]
    (cond-> binding
      (seq optional) (assoc :binding/input-optional optional)
      (seq nested) (assoc :binding/input-nested nested)
      (seq union) (assoc :binding/output-union union)
      (seq recursion) (assoc :binding/output-recursion recursion))))

(defn validate-execution
  "Validate publication selections against a complete vocabulary closure.
   implementation-checkers maps Source kind to a pure function taking a Binding
   (see implementation-findings for the accepted return shapes). Missing,
   throwing or rejecting checkers fail closed. Success is definition
   validation only: runtime authorization and deployment capabilities are separate.

   `options` are passed on to `validate-vocabulary`; `:contract` (the
   document's descriptor) is accepted for the caller's convenience and, when it
   names a retired 1.x descriptor, refused with `:contract/version-retired`
   rather than read as 2.0 (task book PR1, owner 2026-09-19). An absent
   descriptor means «a bare fragment», which is read as 2.0."
  ([vocabulary execution implementation-checkers]
   (validate-execution vocabulary execution implementation-checkers {}))
  ([vocabulary execution implementation-checkers options]
  (let [vf (:findings (semantic/validate-vocabulary vocabulary options))
        explanation (m/explain execution-schema-v2 execution)]
    (cond
      (seq vf) {:findings vf}
      explanation {:findings (mapv #(finding :execution/schema (:in %)) (:errors explanation))}
      :else
      (let [{:keys [resolvers operations sources bindings selection]} execution
            definitions (into {} (concat (map (fn [[k v]] [[:resolver k] v]) resolvers)
                                         (map (fn [[k v]] [[:operation k] v]) operations)))
            attributes (:attributes vocabulary)
            errors
            (concat
             (for [rid (keys resolvers) :when (contains? operations rid)]
               (finding :execution/id-collision [:operations rid]))
             ;; Contract 2.0 §4.1: overlap between definitions is legal and
             ;; must be decidable.
             (priority-findings resolvers)
             ;; Inputs define dependency paths; execution controls belong in :params.
             (for [[ref definition] definitions
                   [i item] (map-indexed vector (:input definition))
                   :when (optional-input? item)]
               {:code :execution/optional-input-retired :severity :error
                :path [ref :input i]
                :message "Graph Input is a required dependency. Move optional execution controls to :params and :binding/parameter-map; Parameters do not participate in path planning."})
             (mapcat (fn [[mid metric]]
                       (concat
                        (when-not (contains? (:entities vocabulary) (:entity metric))
                          [(finding :execution/metric-entity [:metrics mid :entity])])
                        (for [a (:dimensions metric)
                              :when (not (contains? attributes a))]
                          (finding :execution/attribute-reference [:metrics mid :dimensions a]))))
                     (:metrics execution))
             (mapcat
              (fn [[target {:keys [input output] :as definition}]]
                (concat
                 (output-findings vocabulary output nil [target :output])
                 (when (and (= :many (:result/cardinality definition))
                            (nil? (:collection definition))
                            (or (nil? (:pagination definition)) (nil? (:sort definition))))
                   [(finding :execution/bounds-required [target :pagination])])
                 (when (and (:collection definition)
                            (or (not= :many (:result/cardinality definition))
                                (not= :read (:effect definition))
                                (:pagination definition) (:sort definition)
                                (and (get-in definition [:collection :limit])
                                     (not (<= (get-in definition [:collection :limit :default])
                                              (get-in definition [:collection :limit :max])
                                              (get-in definition [:collection :bounds :max-items]))))))
                   [(finding :execution/collection-mode [target :collection])])
                 ;; doc 107 §107.6: per-strategy requirements. A strategy that
                 ;; cannot be executed must not be publishable -- it would stop
                 ;; after one page and answer a truncated collection as if it
                 ;; were the whole one (D2.9).
                 (when (:collection definition)
                   (paging-findings [target :collection] (:collection definition)))
                 (when (and (:pagination definition)
                            (or (not= :many (:result/cardinality definition))
                                (> (get-in definition [:pagination :default]) (get-in definition [:pagination :max]))))
                   [(finding :execution/pagination [target :pagination])])
                 (when (and (:sort definition)
                            (or (empty? (get-in definition [:sort :allowed]))
                                (not (some #{(first (get-in definition [:sort :default]))}
                                           (get-in definition [:sort :allowed])))))
                   [(finding :execution/sort [target :sort])])
                 (when (and (= :read (:effect definition)) (:idempotency definition))
                   [(finding :execution/idempotency [target :idempotency])])
                 (for [a (concat (get-in definition [:sort :allowed])
                                 (map :attribute (vals (:filters definition))))
                       :when (or (not (contains? attributes a)) (= :ref (:attribute/type (attributes a))))]
                   (finding :execution/query-attribute [target a]))
                 (for [p (keys (:filters definition)) :when (reserved-parameters p)]
                   (finding :execution/reserved-parameter [target :filters p]))
                 ;; Contract 2.0 §3.1: free parameters. Unreachable under 1.x,
                 ;; whose schema has no `:params` key.
                 (param-findings target definition)
                 ;; Contract 2.0 §3.2: batching is a READ optimisation over a
                 ;; to-one or to-many result; a write saying `:batch? true` is
                 ;; a configuration error, not a silently ignored flag.
                 (when (and (:batch? definition)
                            (or (not= :read (:effect definition))
                                (not (#{:one :many} (:result/cardinality definition)))))
                   [(finding :execution/batch-shape [target :batch?])])
                 ;; Contract 2.0 §6.2: the single root join of a `:many`
                 ;; definition carries the rows and must be a plain join; a
                 ;; union or a recursion there has no row set to hang.
                 (when (and (= :many (:result/cardinality definition))
                            (let [n (first output)]
                              (and (map? n) (= 1 (count n)) (not (vector? (val (first n))))
                                   (contains? attributes (ffirst n)))))
                   [(finding :execution/output-shape [target :output 0])])
                 (when (or (and (= :none (:result/cardinality definition)) (seq output))
                           (and (not= :none (:result/cardinality definition)) (empty? output))
                           (and (= :many (:result/cardinality definition))
                                (not (and (= 1 (count output)) (map? (first output))
                                          (= :many (get-in attributes [(ffirst (first output)) :attribute/cardinality]))))))
                   [(finding :execution/result-cardinality [target :result/cardinality])])
                 ;; Contract 2.0 §2.1: roots must be distinct whichever form
                 ;; spells them; for a 1.x flat vector the roots ARE the vector.
                 (let [roots (input-roots input)]
                   (when (not= (count roots) (count (distinct roots)))
                     [(finding :execution/duplicate-input [target :input])]))
                 (for [a (concat (input-attrs input) (output-attrs output)) :when (not (contains? attributes a))]
                   (finding :execution/attribute-reference [target a]))
                 (nested-input-findings vocabulary input [target :input])
                 (when (not (contains? selection target))
                   [(finding :execution/binding-required [:selection target])])) )
              definitions)
             (for [[target bid] selection
                   :when (or (not (contains? definitions target))
                             (not (contains? bindings bid))
                             (not= target (:binding/for (bindings bid))))]
               (finding :execution/selection [:selection target]))
             (mapcat
              (fn [[bid binding]]
                (let [target (:binding/for binding)
                      definition (definitions target)
                      source (sources (:binding/source binding))
                      checker (get implementation-checkers (:source/kind source))]
                  (concat
                   (when-not definition [(finding :execution/definition-reference [:bindings bid :binding/for])])
                   (when-not source [(finding :execution/source-reference [:bindings bid :binding/source])])
                   (when (and definition (not= (:effect definition) (:binding/effect binding)))
                     [(finding :execution/effect-mismatch [:bindings bid :binding/effect])])
                   (when (and source (not (contains? (:source/effects source) (:binding/effect binding))))
                     [(finding :execution/source-effect [:bindings bid :binding/effect])])
                   ;; Contract 2.0 §2.2: the input-map keys ARE the roots --
                   ;; optional and nested roots included, subtree attributes
                   ;; excluded. The physical layer has no «optional».
                   (when (and definition
                              (not= (set (input-roots (:input definition)))
                                    (set (keys (:binding/input-map binding)))))
                     [(finding :execution/input-map [:bindings bid :binding/input-map])])
                   ;; 1.4: a parameter-map, when present, names exactly the
                   ;; filters. Contract 2.0 §3.1 tightens it for a definition
                   ;; with `:params`: the map is then REQUIRED and names the
                   ;; union of filters and params. Physical names never repeat.
                   (let [pm (:binding/parameter-map binding)
                         expected (set (concat (keys (:filters definition)) (keys (:params definition))))]
                     (when (and definition
                                (if (seq (:params definition))
                                  (or (nil? pm)
                                      (not= expected (set (keys pm)))
                                      (not= (count pm) (count (set (vals pm)))))
                                  (and pm
                                       (or (not= expected (set (keys pm)))
                                           (not= (count pm) (count (set (vals pm))))))))
                       [(finding :execution/parameter-map [:bindings bid :binding/parameter-map])]))
                   ;; Contract 2.0 §3.2: definition and SELECTED binding agree
                   ;; about batching. Alternative bindings are not reconciled.
                   (when (and definition (= bid (get selection target)))
                     (batch-findings target definition bid binding))
                   ;; Contract 2.0 §3.1 (owner 2026-09-18): api's batch path
                   ;; takes no eql-params (api-v1), so an api Resolver cannot be
                   ;; both batched and parameterised -- refused rather than the
                   ;; parameters being dropped on the way to the source.
                   (when (and definition (= bid (get selection target))
                              (= "api" (:source/kind source))
                              (:batch? definition) (seq (:params definition)))
                     [(finding :execution/batch-params-unsupported [target :params])])
                   (when (and definition
                              (not= (set (output-attrs (:output definition)))
                                    (set (keys (:binding/output-map binding)))))
                     [(finding :execution/output-map [:bindings bid :binding/output-map])])
                   (when source
                     (implementation-findings checker bid (checker-binding binding definition))))))
              bindings))
            findings (vec (sort-by (juxt (comp pr-str :path) :code) errors))]
        (if (seq findings) {:findings findings}
            {:execution execution :findings []}))))))

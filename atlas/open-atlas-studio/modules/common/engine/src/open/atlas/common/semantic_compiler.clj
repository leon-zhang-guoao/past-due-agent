(ns open.atlas.common.semantic-compiler
  "Development compilation bridge. Uses the existing dialect normalization and
   engine, never a second execution engine. Not mounted on any runtime endpoint."
  (:require [open.atlas.common.dialect :as dialect]
            [open.atlas.common.semantic-execution :as execution]
            [open.atlas.common.semantic-static]
            [open.atlas.common.engine :as engine]))

(defn- refuse
  "Abort compilation with a structured finding (finding-v1: name the place,
   state the expectation, leak no values). The catch in `compile-candidate`
   surfaces exactly this map, so a contract author sees WHICH source has no
   connection or WHICH attribute contradicts its term -- not one opaque code.

   Before this, every throw here was `(ex-info \"...\" {})` and the catch
   mapped all of them, plus any genuine crash, to `:semantic.compile/adapter-
   rejected` with an empty path: three authoring errors and a platform defect
   wore the same label, and a Studio user staring at the Release tab had no
   way to tell them apart (2026-09-15, E2E_Trace)."
  [code path message]
  (throw (ex-info message {:finding {:code code :severity :error :path path :message message}})))

(defn- lowered-input
  "Contract 2.0 §2.4: the dialect model keeps `:input` as the flat vector of
   roots -- every consumer that reads it (placeholder binding, row-security's
   `bound-attributes`, need derivation) stays byte-for-byte -- and carries which
   roots are optional / nested in two keys written only when non-empty, so a
   1.x document lowers to exactly what it lowered to before."
  [entry input]
  (let [optional (execution/input-optional input)
        nested (execution/input-nested input)]
    (cond-> entry
      (seq optional) (assoc :input-optional optional)
      (seq nested) (assoc :input-nested nested))))

(defn- lowered-output
  "Contract 2.0 §6.5: the dialect model carries the union and recursion nodes
   in EQL's own spelling. A union's branch keys change from Entity ids to each
   branch Entity's identity attribute (exactly one, by `:semantic/union-identity`)
   -- that is the key Pathom discriminates a record on; a recursion node keeps
   its integer; leaves and joins are untouched, so a 1.x output lowers to
   itself."
  [vocabulary output]
  (mapv (fn [node]
          (if (keyword? node)
            node
            (let [[k v] (first node)]
              (cond
                (vector? v) {k (lowered-output vocabulary v)}
                (map? v) {k (into {} (map (fn [[e sub]]
                                            [(first (get-in vocabulary [:entities e :entity/identity]))
                                             (lowered-output vocabulary sub)]))
                                  v)}
                :else node))))
        output))

(defn- lowered-eql-params
  "The dialect `:eql-params` map of one definition: the 1.4 `:filters` entries
   exactly as before (typed when the binding's parameter-map names the filter,
   attribute-bound otherwise), merged with the contract 2.0 §3.1 `:params`
   entries `{:type :required? :description :default :allowed}` -- present keys
   only, never `:attribute` or `:op`. The two key spaces are disjoint by
   `validate-execution` (`:execution/parameter-collision`), so the merge cannot
   shadow.

   Neither kind writes api-v1's `:field`: 1.4 never has, the api connector
   addresses a parameter by its own name and lets `:query_params` templates
   reference it, and nifi reads `:binding/parameter-map` in its own lowering.
   A second carrier for the physical name here would be exactly the «same
   thing, two spellings» doc 109 §109.3.5 exists to end."
  [vocabulary binding definition]
  (let [pm (:binding/parameter-map binding)
        filters (into {} (map (fn [[p f]]
                                [p (cond-> {(if (contains? pm p) :type :attribute)
                                            (if (contains? pm p)
                                              (get-in vocabulary [:attributes (:attribute f) :attribute/type])
                                              (:attribute f))
                                            :op (:operator f)}
                                     (:required? f) (assoc :required? true)
                                     (:description f) (assoc :description (:description f)))]))
                      (:filters definition))
        params (into {} (map (fn [[p decl]]
                               [p (select-keys decl [:type :required? :description :default :allowed])]))
                     (:params definition))]
    (merge filters params)))

(defn- output-attributes
  "Every attribute keyword in a 2.0 output tree: leaves, join roots and their
   subtrees, union branches; a recursion node repeats attributes already seen."
  [output]
  (mapcat (fn [node]
            (cond
              (keyword? node) [node]
              (map? node) (mapcat (fn [[k sub]]
                                    (cons k (cond
                                              (vector? sub) (output-attributes sub)
                                              (map? sub) (mapcat output-attributes (vals sub))
                                              :else nil)))
                                  node)
              :else nil))
          output))

(defn compile-candidate
  "Compile validated fragments with explicit connector adapters and deployment
   connections. adapters: kind -> {:validate Binding->{:findings [...]} (legacy boolean accepted) :lower Binding->map}.
   Returns no candidate on validation/normalization/compiler failure. This is a
   development API, not publication, record validation or operation authorization.

   `options` `:contract` is the document's descriptor: contract 2.0 definitions
   are read with the 2.0 `:input` forms (`semantic-execution/execution-schema-v2`);
   without it, or under any 1.x descriptor, `:input` is the 1.x flat vector."
  ([vocabulary definitions adapters connections]
   (compile-candidate vocabulary definitions adapters connections {}))
  ([vocabulary definitions adapters connections options]
  (let [checked (execution/validate-execution vocabulary definitions
                                             (update-vals adapters :validate)
                                             (select-keys options [:contract]))
        collection-errors
        (for [[rid d] (:resolvers definitions) :when (:collection d)
              :let [b (get-in definitions [:bindings (get-in definitions [:selection [:resolver rid]])])
                    kind (get-in definitions [:sources (:binding/source b) :source/kind])
                    adapter (get adapters kind)
                    limit-support (:collection-limit? adapter)
                    limit-supported? (if (fn? limit-support) (limit-support b) limit-support)]
              :when (or (not (:source-collection? adapter))
                        (and (get-in d [:collection :limit]) (not limit-supported?)))]
          {:code :semantic.compile/collection-unsupported :severity :error :path [:resolvers rid :collection]})
        isolation-errors
        (for [[rid d] (:resolvers definitions)
              :let [b (get-in definitions [:bindings (get-in definitions [:selection [:resolver rid]])])]
              :when (and (get-in b [:binding/implementation :batch :isolate-statuses])
                         (or (not= :one (:result/cardinality d)) (not-every? keyword? (:output d))))]
          {:code :semantic.compile/batch-isolation-shape :severity :error :path [:resolvers rid :output]
           :message "Batch isolation requires a flat to-one resolver output"})
        checked (update checked :findings into (concat collection-errors isolation-errors))]
    (if (seq (:findings checked)) checked
      (try
        (let [identity-attrs (set (mapcat :entity/identity (vals (:entities vocabulary))))
              attrs (into {} (map (fn [[a d]]
                                    [a (cond-> {:label (:attribute/label d)}
                                         (not= :ref (:attribute/type d)) (assoc :type (:attribute/type d))
                                         (:attribute/target d) (assoc :target (:attribute/target d))
                                         ;; Contract 2.0 §6.5: a multi-target ref lowers to `:targets`.
                                         (:attribute/targets d) (assoc :targets (:attribute/targets d))
                                         (:attribute/description d) (assoc :description (:attribute/description d))
                                         (:attribute/term d) (assoc :term (:attribute/term d))
                                         (identity-attrs a) (assoc :identity? true))])) (:attributes vocabulary))
              attrs (into {} (map (fn [[a target]]
                                    [a (reduce (fn [out field]
                                                 (let [key (keyword "attribute" (name field))]
                                                   (if (contains? (get-in vocabulary [:attributes a]) key)
                                                     (if (and (= field :listed?) (get-in vocabulary [:attributes a key])) out
                                                       (assoc out field (get-in vocabulary [:attributes a key]))) out)))
                                               target [:format :unit :values :synonyms :listed? :deprecated? :replaced-by])])) attrs)
              attrs (into {} (for [[a d] attrs]
                               [a (if-let [term (get-in vocabulary [:terms (:term d)])]
                                    (reduce (fn [out k]
                                              (let [tk (keyword "term" (name k))]
                                                (if (and (contains? term tk) (contains? out k))
                                                  (if (= (get term tk) (get out k)) (dissoc out k)
                                                    (refuse :semantic.compile/term-override [:attributes a k]
                                                            (str "the attribute sets " (name k) " to a value different from its term's; "
                                                                 "remove the attribute-level value or align it with the term"))) out)))
                                            d [:label :description :synonyms :format :values]) d)]))
              lower (fn [kind [rid d]]
                      (let [bid (get-in definitions [:selection [kind rid]])
                            b (get-in definitions [:bindings bid])
                            sid (:binding/source b)
                            source (get-in definitions [:sources sid])
                            _ (when-let [root (get-in b [:binding/implementation :result-root])]
                                (when-not (and (= :many (:result/cardinality d))
                                               (= root (ffirst (first (:output d)))))
                                  (refuse :semantic.compile/result-root-mismatch
                                          [:bindings bid :binding/implementation :result-root]
                                          (str "result-root is only valid on a :many resolver and must name the root "
                                               "of its single output branch"))))
                            ;; `spi/semantic-adapter` answers nil for a kind no installed
                            ;; connector claims, and callers pass that nil straight in --
                            ;; so a contract naming a connector this instance lacks used
                            ;; to die here as a NullPointerException.
                            lower-fn (get-in adapters [(:source/kind source) :lower])
                            _ (when-not lower-fn
                                (refuse :semantic.compile/adapter-missing [:sources sid :source/kind]
                                        "no connector for this source kind is installed on this instance"))
                            operation (lower-fn b)
                            operation (if (contains? operation :params)
                                        (update operation :params #(into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) %))
                                        operation)
                            base {:id rid :description (or (:description d) (:label d))
                                  :result_type ({:one "single" :many "list" :none "none"} (:result/cardinality d))
                                  :source operation}
                            base (cond-> base
                                   (contains? d :curated?) (assoc :curated? (:curated? d))
                                   (not (#{"derived" "static"} (:source/kind source))) (assoc :source-ref sid)
                                   (:pagination d) (assoc :limit (:pagination d))
                                   (:collection d) (assoc :limit {:default (get-in d [:collection :bounds :max-items])
                                                                 :max (get-in d [:collection :bounds :max-items])})
                                   (get-in d [:collection :limit]) (assoc :limit (get-in d [:collection :limit]))
                                   (:sort d) (assoc :order (:sort d))
                                   (or (:filters d) (seq (:params d)))
                                   (assoc :eql-params (lowered-eql-params vocabulary b d)))]
                        (if (= kind :resolver)
                          (lowered-input (cond-> (assoc base :input (execution/input-roots (:input d))
                                                        :output (lowered-output vocabulary (:output d)))
                                           ;; Contract 2.0 §4.4: present only when
                                           ;; the definition wrote it, so a 1.x
                                           ;; document lowers byte-for-byte.
                                           (contains? d :priority) (assoc :priority (:priority d)))
                                         (:input d))
                          (let [optional (execution/input-optional (:input d))]
                            (cond-> (lowered-input
                                     (assoc base :params (into {} (map (fn [a] [a (if (optional a) {} {:required? true})]))
                                                                 (execution/input-roots (:input d))))
                                     (:input d))
                              (= :natural (:idempotency d)) (assoc :idempotency :natural)
                              (seq (:output d)) (assoc :returns (lowered-output vocabulary (:output d))))))))
              model {:attributes attrs
                     :namespaces (into {} (map (fn [[eid d]] [eid (cond-> {:label (:entity/label d)}
                                                                 (:entity/description d) (assoc :description (:entity/description d))
                                                                 (:entity/notes d) (assoc :notes (:entity/notes d))
                                                                 (:entity/kind d) (assoc :kind (:entity/kind d))
                                                                 (:entity/synonyms d) (assoc :synonyms (:entity/synonyms d)))])) (:entities vocabulary))
                     :terms (into {} (map (fn [[tid d]] [tid (into {} (map (fn [[k v]] [(keyword (name k)) v])) d)])) (:terms vocabulary))
                     :units (into {} (map (fn [[uid d]] [uid (into {} (map (fn [[k v]] [(keyword (name k)) v])) d)])) (:units vocabulary))
                     :links (vec (concat (map (fn [a] (cond-> {:from (:alias/from a) :to (:alias/to a) :direction :one-way}
                                                                  (contains? a :alias/description) (assoc :description (:alias/description a)))) (:aliases vocabulary))
                                         (mapcat (fn [e] (let [[root & rest] (:equivalence/attributes e)]
                                                          (map (fn [a] (cond-> {:from root :to a}
                                                                        (contains? e :equivalence/description) (assoc :description (:equivalence/description e)))) rest))) (:equivalences vocabulary))))
                     :metrics (or (:metrics definitions) {})
                     :sources (into {} (comp (remove (fn [[_ d]] (#{"derived" "static"} (:source/kind d))))
                                            (map (fn [[sid d]]
                                              (when-not (contains? connections sid)
                                                (refuse :semantic.compile/missing-connection [:sources sid]
                                                        (str "the contract declares this source but this deployment has no "
                                                             "connection configured for it; configure the source before publishing")))
                                              [sid (assoc (get connections sid) :type (:source/kind d))]))) (:sources definitions))
                     :resolvers (mapv #(lower :resolver %) (:resolvers definitions))
                     :mutations (mapv #(lower :operation %) (:operations definitions))}
              normalized (dialect/read-dialect (pr-str model))]
          (if (seq (filter #(= :error (:severity %)) (:findings normalized)))
            {:findings (mapv #(select-keys % [:code :severity :path]) (:findings normalized))}
            (let [collections (into {} (keep (fn [[rid d]] (when (:collection d) [rid (:collection d)]))) (:resolvers definitions))
                  ;; Declared scalar types of everything a definition returns. A
                  ;; connector that cannot hand back the strict 2.0 value classes
                  ;; natively (sql: Double, java.sql.Date, text; api: :json objects
                  ;; with keyword keys) converts by it; each connector picks the
                  ;; types it converts. Legacy dialect models never pass here, so
                  ;; they are unaffected.
                  output-types (fn [output]
                                 (into {}
                                       (keep (fn [a] (let [t (get-in vocabulary [:attributes a :attribute/type])]
                                                       (when (#{:decimal :date :datetime :json} t) [a t]))))
                                       (output-attributes output)))
                  with-types (fn [r output]
                               (let [types (output-types output)]
                                 (cond-> r (seq types) (assoc :semantic/output-types types))))
                  runtime-model (-> (:model normalized)
                                    (assoc :semantic/conditional-references (:references vocabulary))
                                    (update :mutations #(mapv (fn [m] (with-types m (get-in definitions [:operations (:id m) :output]))) %))
                                    (update :resolvers #(mapv (fn [r]
                                                               (let [b (get-in definitions [:bindings (get-in definitions [:selection [:resolver (:id r)]])])
                                                                     r (with-types r (get-in definitions [:resolvers (:id r) :output]))
                                                                     r (cond-> r (get-in b [:binding/implementation :context])
                                                                         (assoc :semantic/output-context (get-in b [:binding/implementation :context]))
                                                                         (get-in b [:binding/implementation :transforms])
                                                                         (assoc :semantic/output-transforms (get-in b [:binding/implementation :transforms])))]
                                                                 (if-let [c (get collections (:id r))]
                                                                   (cond-> (assoc (dissoc r :limit :order) :semantic/collection c)
                                                                     (:limit c) (assoc :limit (:limit c))) r))) %)))
                  compiled (engine/compile-complete runtime-model)]
              (if (:compiled compiled)
                (assoc compiled :semantic/vocabulary vocabulary :semantic/execution definitions)
                {:findings (mapv #(select-keys % [:code :severity :path]) (:findings compiled))}))))
        (catch Exception e
          (if-let [finding (:finding (ex-data e))]
            {:findings [finding]}
            ;; A crash with no finding attached is a platform defect, not an
            ;; authoring error. Say so, and name the exception CLASS only: its
            ;; message may carry connection strings or row values (D2.9 / finding-v1).
            {:findings [{:code :semantic.compile/adapter-rejected :severity :error :path []
                         :message (str "compilation failed unexpectedly (" (.getName (class e)) "); "
                                       "this is a platform defect rather than a contract error -- "
                                       "report it together with the graph id")}]})))))))

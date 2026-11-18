(ns open.atlas.common.semantic-contract
  "Complete semantic document contract. Pure EDN read/validate/write and exact
   dependency/capability checks; not an Artifact store or runtime loader."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.set :as set]
            [malli.core :as m]
            [open.atlas.common.contract :as version]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.semantic :as vocabulary]
            [open.atlas.common.semantic-execution :as execution]))

(def id [:string {:min 1}])
(def digest [:re #"sha256:[0-9a-f]{64}"])
(def release [:and [:re #"[A-Za-z0-9][A-Za-z0-9._-]*"]
              [:fn #(not (contains? #{"latest" "head" "main" "current" "draft"} (str/lower-case %)))]] )
(def capability [:fn qualified-keyword?])
(def dependency-schema
  [:map {:closed true} [:graph/id id] [:release/version release] [:content/hash digest]])
(def query-parameter-schema
  [:map {:closed true}
   [:parameter/label id]
   [:parameter/description id]
   [:parameter/required? :boolean]
   [:parameter/sample [:or :string :int :boolean :keyword]]])
(defn- eql-example-text? [text]
  (try
    (let [reader (java.io.PushbackReader. (java.io.StringReader. text))
          eof (Object.)
          value (edn/read {:eof eof :readers {}
                           :default (fn [_ _] (throw (ex-info "tag" {})))} reader)
          tail (edn/read {:eof eof :readers {}
                          :default (fn [_ _] (throw (ex-info "tag" {})))} reader)]
      (and (vector? value) (seq value) (identical? eof tail)))
    (catch Exception _ false)))
(def query-example-schema
  [:map {:closed true}
   [:example/id id]
   [:example/title id]
   [:example/description id]
   [:example/eql [:and id [:fn eql-example-text?]]]
   [:example/entity [:map-of [:fn qualified-keyword?] :any]]
   [:example/parameters [:map-of :keyword query-parameter-schema]]
   [:example/requires [:set capability]]
   [:example/expected [:map {:closed true}
                       [:result/cardinality [:enum :one :many]]
                       [:result/fields [:vector [:fn qualified-keyword?]]]]]])
;; Descriptive contract metadata; never an authorization or execution policy.
(def usage-guide-schema
  [:map {:closed true}
   [:guide/purpose {:optional true} id]
   [:guide/asset-scope {:optional true} id]
   [:guide/applicability {:optional true} id]
   [:guide/limitations {:optional true} [:vector id]]
   [:guide/composition {:optional true} id]
   [:guide/examples {:optional true} [:vector id]]])

(def document-schema-v2
  "The one document schema: Semantic contract 2.0 (doc 109 §109.8, owner
   2026-09-18). Standalone since 2026-09-19 (task book
   «contract-upgrade-and-retirement» PR1): the 1.0-1.4 schemas it used to be
   derived from are retired, and their content is written here in full.
   Closed-world ownership (2.0 §1, inherited from 1.4): a document contains
   exactly one Graph's assets; dependencies carry the rest as separate
   immutable snapshots in a Graph package."
  [:map {:closed true}
   [:contract version/descriptor-schema]
   [:workspace [:map {:closed true} [:workspace/id id]]]
   [:graph [:map {:closed true}
            [:graph/id id] [:graph/label id]
            [:graph/exposure [:enum :internal :restricted]]
            [:graph/entities [:vector id]] [:graph/resolvers [:vector id]]
            [:graph/operations [:vector id]]
            [:graph/dependencies [:vector dependency-schema]]
            [:graph/query-examples {:optional true} [:vector query-example-schema]]
            [:graph/terms [:vector id]]
            [:graph/usage-guide {:optional true} usage-guide-schema]
            [:graph/query-budget {:optional true}
             [:map {:closed true} [:execution-ms [:int {:min 1 :max 900000}]]]]]]
   [:vocabulary vocabulary/vocabulary-schema-v2]
   [:execution execution/execution-schema-v2]
   [:ownership [:map {:closed true}
                [:entities [:map-of id id]] [:resolvers [:map-of id id]] [:operations [:map-of id id]]
                [:terms [:map-of id id]]]]
   [:publication [:map {:closed true}
                  [:release/version release]
                  [:source/revision [:or nat-int? id]]
                  [:required-capabilities [:set capability]]]]])

(defn- failure [code path] {:code code :severity :error :path path})
(def ^:private max-contract-bytes (* 4 1024 1024))
(def ^:private max-dependency-depth 64)
(def ^:private utf-8-bom "\ufeff")

(defn- byte-size [text]
  (try
    (alength (.getBytes ^String text "UTF-8"))
    (catch Exception _ Long/MAX_VALUE)))

(defn- strip-utf8-bom [text]
  (if (and (string? text) (.startsWith text utf-8-bom))
    (subs text 1)
    text))
(defn required-capabilities [document]
  (let [e (:execution document) v (:vocabulary document)]
    (into #{:semantic/core-v1}
          (concat
           (when (some #(get-in % [:binding/implementation :batch :isolate-statuses]) (vals (:bindings e)))
             [:query/batch-isolation-v1])
           ;; doc 107 §107.5/§107.9 item 7 (owner 2026-09-18): a graph that says
           ;; how its upstream spells failure must be REFUSED by a runtime that
           ;; would read those statuses the old way -- silently falling back to
           ;; «every non-2xx is a source error» would turn a declared missing
           ;; row into an outage. The capability IMPLIES
           ;; `:query/batch-isolation-v1` (a runtime that honours a status map
           ;; necessarily isolates batches), so a status-map graph does not
           ;; declare both; a graph still written against the old key keeps
           ;; declaring the old capability and keeps loading.
           (when (or (some #(get-in % [:binding/implementation :status-map]) (vals (:bindings e)))
                     (some #(get-in % [:binding/implementation :policy :status-map]) (vals (:bindings e)))
                     (some #(get-in % [:source/policy :status-map]) (vals (:sources e))))
             [:connector/api-status-map-v1])
           ;; doc 107 §107.3/§107.9 item 7 (owner 2026-09-18): a graph that
           ;; declares a policy must be REFUSED by a runtime that would ignore
           ;; it. Before the closed key face, `:resilience` was accepted and
           ;; dropped -- shipping such a graph to an old runtime silently
           ;; removes the retry the modeller declared, which is the precise
           ;; failure §107.1 documents. `:timeout_ms` alone does not derive it:
           ;; every runtime has always honoured that one.
           (when (or (some #(seq (:source/policy %)) (vals (:sources e)))
                     (some #(seq (get-in % [:binding/implementation :policy])) (vals (:bindings e)))
                     (some #(seq (dissoc (:resilience %) :timeout_ms)) (vals (:sources e))))
             [:source/policy-v1])
           (when (get-in document [:graph :graph/query-budget]) [:graph/query-budget-v1])
           ;; 2.0 §1: every document is one Graph's closed-world snapshot.
           [:graph/single-owner-v1 :deployment/source-slot-v1 :artifact/embedded-closure-v1]
           ;; Contract 2.0 §2: a graph whose `:input` uses an optional or nested
           ;; element must be REFUSED by a runtime that reads `:input` as a flat
           ;; vector -- it would plan a required input where the modeller said
           ;; optional, or hand a connector a bare ref where it expects rows.
           (when (some (fn [d] (some map? (:input d)))
                       (concat (vals (:resolvers e)) (vals (:operations e))))
             [:execution/input-v2])
           ;; Contract 2.0 §3.1: a graph declaring free parameters must be
           ;; REFUSED by a runtime that lowers only `:filters` -- it would
           ;; compile the resolver without them and the gate would then refuse
           ;; every call that passes one as «undeclared».
           (when (some #(seq (:params %)) (concat (vals (:resolvers e)) (vals (:operations e))))
             [:execution/params-v2])
           ;; Contract 2.0 §3.2: `:batch?` is reconciled against the binding's
           ;; `:batch` block; a runtime that does not know the key would read
           ;; the definition as a schema error, so the graph names the need.
           (when (some :batch? (concat (vals (:resolvers e)) (vals (:operations e))))
             [:execution/batch-v2])
           ;; Contract 2.0 §4: a graph that RANKS two producers of one
           ;; attribute must be REFUSED by a runtime that plans them unranked --
           ;; it would answer from whichever it planned first and never fall
           ;; back. Derived from a written `:priority`, and from overlap itself
           ;; (overlap IS the 2.0 semantics even when every rank is the
           ;; default).
           (when (or (some #(contains? % :priority) (vals (:resolvers e)))
                     (seq (execution/overlapping-resolvers (:resolvers e))))
             [:execution/priority-v2])
           ;; Contract 2.0 §6: a graph whose `:output` opens a union, or whose
           ;; vocabulary names a multi-target ref, must be REFUSED by a runtime
           ;; that reads a join as single-target -- it would plan one branch's
           ;; attributes against records of the other. A recursion node must be
           ;; refused by a runtime that would read `{ref n}` as a schema error
           ;; or, worse, plan an unbounded recursion.
           (when (or (some #(seq (execution/output-union (:output %)))
                           (concat (vals (:resolvers e)) (vals (:operations e))))
                     (some #(contains? % :attribute/targets) (vals (:attributes v))))
             [:execution/output-union-v2])
           (when (some #(seq (execution/output-recursion (:output %)))
                       (concat (vals (:resolvers e)) (vals (:operations e))))
             [:execution/output-recursion-v2])
           (when (seq (:operations e)) [:operation/write-v1])
           (when (seq (:aliases v)) [:semantic/alias-v1])
           (when (seq (:equivalences v)) [:semantic/equivalence-v1])
           (when (seq (:references v)) [:semantic/conditional-reference-v1])
           (when (seq (:metrics e)) [:metric/duckdb-spill-v1])
           (when (seq (get-in document [:graph :graph/dependencies])) [:graph/dependencies-v1])
           (for [[_ s] (:sources e)] (keyword "connector" (:source/kind s)))
           ;; doc 107 §107.6 (owner 2026-09-18): a graph whose collection PAGES
           ;; must be refused by a runtime that would fetch page one and answer
           ;; it as the whole collection -- a truncation the consumer cannot
           ;; see. `:query/pagination-v1` (offset/limit parameter renaming) is a
           ;; different, untouched thing.
           (mapcat (fn [d] (concat (when (and (:collection d)
                                              (not= :none (execution/paging-strategy (:collection d))))
                                     [:query/paging-strategy-v1])
                                   (when (:pagination d) [:query/pagination-v1])
                                   (when (:collection d) [:query/source-collection-v1])
                                   (when (:sort d) [:query/sort-v1])
                                   (when (seq (:filters d)) [:query/filter-v1])))
                   (concat (vals (:resolvers e)) (vals (:operations e))))))))

(defn validate-document
  "Validate self-contained resolved document structure. Every execution and
   vocabulary reference must resolve locally; external definitions included in
   a resolved closure retain their IDs. Dependency hashes are checked separately.
   checkers are actual connector implementation validators, never inferred.
   A retired descriptor (legacy, 1.0-1.4) is refused with
   `:contract/version-retired` before anything else is read (task book PR1,
   owner 2026-09-19); a descriptor of another family is `:contract/unsupported`."
  [document checkers]
  (cond
    (and (map? document) (version/retired? (:contract document)))
    {:findings [version/version-retired]}
    (m/explain document-schema-v2 document)
    {:findings (mapv #(failure :contract/schema (:in %)) (:errors (m/explain document-schema-v2 document)))}
    (not= version/semantic-v2 (:contract document))
    {:findings [(failure :contract/unsupported [:contract])]}
    :else
    (let [result (execution/validate-execution (:vocabulary document) (:execution document) checkers
                                               {:contract (:contract document) :allow-unresolved-terms? true})
          graph (:graph document)
          registries {:entities (get-in document [:vocabulary :entities])
                      :resolvers (get-in document [:execution :resolvers])
                      :operations (get-in document [:execution :operations])
                      :terms (or (get-in document [:vocabulary :terms]) {})}
          ;; 2.0 §1 closed-world ownership: every asset in the document is the
          ;; document's own Graph's, so the membership lists are the registries.
          comparisons (mapv (fn [kind] [(keyword "graph" (name kind)) (keys (get registries kind))])
                            [:entities :resolvers :operations :terms])
          deps (:graph/dependencies graph)
          examples (:graph/query-examples graph)
          errors (concat
                  (:findings result)
                  (for [[kind entries] registries
                        :let [owners (get-in document [:ownership kind])]
                        :when (or (not= (set (keys entries)) (set (keys owners)))
                                  (not-every? #(= (:graph/id graph) %) (vals owners)))]
                    (failure :contract/ownership [:ownership kind]))
                  (for [[field ids] comparisons
                        :let [declared (get graph field)]
                        :when (or (not= (count declared) (count (distinct declared)))
                                  (not= (set declared) (set ids)))]
                    (failure :contract/graph-membership [:graph field]))
                  (when (or (not= (count deps) (count (distinct (map :graph/id deps))))
                            (some #(= (:graph/id graph) (:graph/id %)) deps))
                    [(failure :contract/dependency-conflict [:graph :graph/dependencies])])
                  (for [[i example-id] (map-indexed vector (get-in graph [:graph/usage-guide :guide/examples]))
                        :when (not (contains? (set (map :example/id examples)) example-id))]
                    (failure :contract/usage-guide-example
                             [:graph :graph/usage-guide :guide/examples i]))
                  (when (not= (count examples) (count (distinct (map :example/id examples))))
                    [(failure :contract/query-example-duplicate [:graph :graph/query-examples])])
                  (for [[i example] (map-indexed vector examples)
                        :when (not (set/subset? (:example/requires example)
                                                (get-in document [:publication :required-capabilities])))]
                    (failure :contract/query-example-capability
                             [:graph :graph/query-examples i :example/requires]))
                  (when-not (set/subset? (required-capabilities document)
                                        (get-in document [:publication :required-capabilities]))
                    [(failure :contract/capability-undeclared [:publication :required-capabilities])]))]
      (if (seq errors) {:findings (vec errors)} {:document document :findings []}))))

(defn read-document
  "Read exactly one bounded EDN form. Unknown tags, trailing forms and bad
   markers fail closed. A document without a descriptor is the retired legacy
   form and is refused, not dispatched to the dialect reader (task book PR1).
   No exceptions or raw input values are echoed."
  [text checkers]
  (try
    (if (not (string? text))
      {:findings [(failure :contract/unreadable [])]}
      (if (> (byte-size text) max-contract-bytes)
        {:findings [(failure :contract/input-limit [])]}
      (let [clean-text (strip-utf8-bom text)
            reader (java.io.PushbackReader. (java.io.StringReader. clean-text))
            eof (Object.) opts {:eof eof :readers {} :default (fn [_ _] (throw (ex-info "tag" {})))}
            document (edn/read opts reader)
            tail (edn/read opts reader)
            identified (version/identify document)]
        (cond
          (not (identical? eof tail)) {:findings [(failure :contract/trailing-form [])]}
          (seq (:findings identified)) identified
          :else (validate-document document checkers)))))
    (catch Exception _ {:findings [(failure :contract/unreadable [])]})))

(defn write-document [document checkers]
  (let [result (validate-document document checkers)]
    (if (:document result)
      (try
        (let [text (dialect/canonical-str document)]
          (if (> (byte-size text) max-contract-bytes)
            {:findings [(failure :contract/input-limit [])]}
            {:text text :findings []}))
        (catch Exception _ {:findings [(failure :contract/unwritable [])]}))
      result)))

(defn content-hash [canonical-text]
  (str "sha256:" (apply str (map #(format "%02x" (bit-and 255 %))
                                (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                         (.getBytes ^String canonical-text "UTF-8"))))))

(defn verify-closure
  "Validate exact dependencies from a supplied immutable snapshot map keyed by
   [graph-id release-version]. Entries are documents, never current draft pointers.
   Checks each document, computed content hash, reference locator and cycles.
   No networking, fallback, upgrades, or permission grants."
  [root snapshot checkers]
  (letfn [(visit [document expected visiting]
            (let [result (validate-document document checkers)]
              (if (seq (:findings result)) (:findings result)
                (let [locator [(get-in document [:graph :graph/id]) (get-in document [:publication :release/version])]
                      encoded (write-document document checkers)
                      hash (when (:text encoded) (content-hash (:text encoded)))]
                  (cond
                    (seq (:findings encoded)) (:findings encoded)
                    (> (count visiting) max-dependency-depth) [(failure :contract/dependency-depth [:graph :graph/dependencies])]
                    (contains? visiting locator) [(failure :contract/dependency-cycle [:graph :graph/dependencies])]
                    (and expected (or (not= locator [(:graph/id expected) (:release/version expected)])
                                      (not= hash (:content/hash expected))))
                    [(failure :contract/dependency-integrity [:graph :graph/dependencies])]
                    :else (mapcat (fn [ref]
                                    (if-let [child (get snapshot [(:graph/id ref) (:release/version ref)])]
                                      ;; 2.0 §1 packages keep child snapshots distinct. The
                                      ;; parent names only the exact locator and digest; it
                                      ;; does not duplicate the child's asset registries.
                                      (visit child ref (conj visiting locator))
                                      [(failure :contract/dependency-missing [:graph :graph/dependencies])]))
                                  (get-in document [:graph :graph/dependencies])))))))]
    (let [findings (vec (visit root nil #{}))]
      (if (seq findings) {:findings findings} {:document root :findings []}))))

(defn check-support [document supported]
  (let [missing (set/difference (get-in document [:publication :required-capabilities]) supported)]
    (if (seq missing)
      {:findings [(failure :contract/capability-unsupported [:publication :required-capabilities])]}
      {:findings []})))

(defn validate-deliverable
  "New-delivery profile: a valid 2.0 document with optional usage guidance.
   Since 2026-09-19 every readable document is 2.0, so this is
   `validate-document`; the name stays as the delivery face callers reach for.
   This does not prove execution or business truth."
  [document checkers]
  (validate-document document checkers))

(defn usage-catalog
  "Derived, local asset inventory. No duplicate authored inventory; dependencies
   remain exact snapshot references, not merged or implicitly authorized assets."
  [document]
  {:guide (get-in document [:graph :graph/usage-guide])
   :assets {:entities (get-in document [:graph :graph/entities])
            :attributes (vec (sort (keys (get-in document [:vocabulary :attributes]))))
            :terms (get-in document [:graph :graph/terms])
            :resolvers (get-in document [:graph :graph/resolvers])
            :operations (get-in document [:graph :graph/operations])
            :metrics (vec (sort (keys (get-in document [:execution :metrics]))))}
   :dependencies (get-in document [:graph :graph/dependencies])
   :examples (get-in document [:graph :graph/query-examples])})

(ns open.atlas.studio.semantic-package-service
  "Plan and import a portable Semantic Contract 1.4 Graph package into the
   Studio authoring repository. Import never carries Source instances or
   credentials and never publishes or activates a Runtime Graph."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-graph-package :as package]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.graph-service :as guards]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.semantic-service :as semantic]))

(defn- refusal [code path]
  {:findings [{:code code :severity :error :path path}]})

(defn- slug [value]
  (let [s (-> (str value) str/lower-case (str/replace #"[^a-z0-9._-]+" "-")
              (str/replace #"(^-+|-+$)" ""))]
    (if (seq s) s "imported-graph")))

(defn- default-targets [workspace root-graph root-id documents]
  (into {}
        (map (fn [document]
               (let [id (get-in document [:graph :graph/id])]
                 [id (if (= id root-id)
                       root-graph
                       (graph/qualified-name workspace
                         (slug (get-in document [:graph :graph/label]))))])))
        documents))

(defn- current-revision [db workspace definition]
  (or (:revision (pool/semantic-draft db workspace (:graph/id definition))) 0))

(declare rewrite-documents)

(def ^:private namespace-pattern #"[A-Za-z][A-Za-z0-9_.-]*")

(defn- leaf [id] (last (str/split (str id) #"[/.]")))
(defn- rename-keys [m replacements]
  (into (empty m) (map (fn [[k v]] [(get replacements k k) v])) m))
(defn- replace-attrs [value attribute-map]
  (walk/postwalk-replace attribute-map value))
(defn- update-present [m k f]
  (if (contains? m k) (update m k f) m))

(defn- document-maps [document new-namespace]
  (if (str/blank? (or new-namespace ""))
    {:entities {} :attributes {} :resolvers {} :operations {} :terms {}}
    (let [entities (into {} (map (fn [id] [id (str new-namespace "." (leaf id))]))
                         (keys (get-in document [:ownership :entities])))
          attributes (into {}
                           (for [attribute (keys (get-in document [:vocabulary :attributes]))
                                 :let [owner (namespace attribute) target (get entities owner)]
                                 :when target]
                             [attribute (keyword target (name attribute))]))]
      {:entities entities
       :attributes attributes
       :resolvers (into {} (map (fn [id] [id (str new-namespace "/" (leaf id))]))
                        (keys (get-in document [:ownership :resolvers])))
       :operations (into {} (map (fn [id] [id (str new-namespace "/" (leaf id))]))
                         (keys (get-in document [:ownership :operations])))
       :terms (into {} (map (fn [id] [id (str new-namespace "/" (leaf id))]))
                    (keys (get-in document [:ownership :terms])))})))

(defn- rewrite-definition [definition attribute-map]
  (cond-> (-> definition
              (update :input #(mapv (fn [x] (get attribute-map x x)) %))
              (update :output replace-attrs attribute-map))
    (:sort definition)
    (update :sort (fn [sort]
                    (cond-> sort
                      (:allowed sort) (update :allowed #(mapv (fn [x] (get attribute-map x x)) %))
                      (:default sort) (update :default #(vector (get attribute-map (first %) (first %))
                                                               (second %))))))
    (:filters definition)
    (update :filters #(into {} (map (fn [[k v]]
                                      [k (update v :attribute (fn [x] (get attribute-map x x)))]) %)))))

(defn- rewrite-example [example attribute-map]
  (let [query (:example/eql example)]
    (try
      (-> example
          (update :example/entity #(rename-keys % attribute-map))
          (assoc :example/eql (pr-str (replace-attrs (edn/read-string query) attribute-map)))
          (update-in [:example/expected :result/fields]
                     #(mapv (fn [x] (get attribute-map x x)) %)))
      (catch Exception e
        (throw (ex-info "Package query example contains unreadable EDN"
                        {:code :semantic.package/example-edn
                         :example (:example/id example)} e))))))

(defn- remap-document [document {:keys [entities attributes resolvers operations terms]}]
  (let [definition #(rewrite-definition % attributes)
        target-id (fn [[kind id]]
                    [kind (case kind
                            :resolver (get resolvers id id)
                            :operation (get operations id id)
                            id)])]
    (cond->
     (-> document
        (update-in [:graph :graph/entities] #(mapv (fn [x] (get entities x x)) %))
        (update-in [:graph :graph/resolvers] #(mapv (fn [x] (get resolvers x x)) %))
        (update-in [:graph :graph/operations] #(mapv (fn [x] (get operations x x)) %))
        (update-in [:graph :graph/terms] #(mapv (fn [x] (get terms x x)) %))
        (update-in [:graph :graph/query-examples] #(mapv (fn [x] (rewrite-example x attributes)) %))
        (update-in [:vocabulary :entities]
                   #(rename-keys
                     (into {} (map (fn [[id value]]
                                     [id (-> value
                                             (update :entity/identity replace-attrs attributes)
                                             (update :entity/attributes replace-attrs attributes))])) %)
                     entities))
        (update-in [:vocabulary :attributes]
                   #(rename-keys
                     (into {} (map (fn [[id value]]
                                     [id (-> value
                                             (update-present :attribute/term (fn [x] (get terms x x)))
                                             (update-present :attribute/target (fn [x] (get entities x x)))
                                             (update-present :attribute/replaced-by (fn [x] (get attributes x x)))
                                             (update-present :attribute/identity-component (fn [x] (get attributes x x))))])) %)
                     attributes))
        (update-in [:vocabulary :terms] #(rename-keys % terms))
        (update-in [:vocabulary :aliases] #(replace-attrs % attributes))
        (update-in [:vocabulary :equivalences] #(replace-attrs % attributes))
        (update-in [:vocabulary :references] #(replace-attrs % attributes))
        (update-in [:execution :resolvers]
                   #(rename-keys (into {} (map (fn [[id value]] [id (definition value)])) %) resolvers))
        (update-in [:execution :operations]
                   #(rename-keys (into {} (map (fn [[id value]] [id (definition value)])) %) operations))
        (update-in [:execution :bindings]
                   (fn [bindings]
                     (into {} (map (fn [[id value]]
                                     [id (-> value
                                             (update :binding/for target-id)
                                             (update :binding/input-map #(rename-keys % attributes))
                                             (update :binding/output-map #(rename-keys % attributes)))]) bindings))))
        (update-in [:execution :selection]
                   #(into {} (map (fn [[target binding]] [(target-id target) binding])) %))
        (update-in [:ownership :entities] #(rename-keys % entities))
        (update-in [:ownership :resolvers] #(rename-keys % resolvers))
        (update-in [:ownership :operations] #(rename-keys % operations))
        (update-in [:ownership :terms] #(rename-keys % terms)))
      (get-in document [:execution :metrics])
      (update-in [:execution :metrics]
                 (fn [metrics]
                   (into {} (map (fn [[id value]]
                                   [id (cond-> (update value :entity #(get entities % %))
                                         (:dimensions value)
                                         (update :dimensions replace-attrs attributes))]) metrics)))))))

(defn- prepared-package [read namespace-remaps checkers]
  (let [documents (mapv :document (get-in read [:package :graphs]))
        ids (set (map #(get-in % [:graph :graph/id]) documents))
        remaps (into {} (remove (comp str/blank? val)) namespace-remaps)]
    (cond
      (some #(not (contains? ids %)) (keys remaps))
      (refusal :semantic.package/namespace-graph [:namespace-remaps])
      (some #(not (re-matches namespace-pattern %)) (vals remaps))
      (refusal :semantic.package/namespace [:namespace-remaps])
      :else
      (try
        (let [per-document (into {} (map (fn [d]
                                           [(get-in d [:graph :graph/id])
                                            (document-maps d (get remaps (get-in d [:graph :graph/id])))]) documents))
              global-maps (apply merge-with merge (vals per-document))
              remapped (mapv (fn [d] (remap-document d global-maps)) documents)
              identity-ids (zipmap ids ids)
              workspace (get-in (first documents) [:workspace :workspace/id])
              rewritten (rewrite-documents remapped identity-ids workspace checkers)
              root-id (get-in read [:package :root :graph/id])
              built (package/build (get rewritten root-id) (vec (vals rewritten)) checkers)]
          (if (seq (:findings built))
            (select-keys built [:findings])
            {:documents (mapv rewritten (map #(get-in % [:graph :graph/id]) documents))
             :namespace-remaps remaps
             :candidate-digest (:package-digest built)
             :candidate-text (:text built)
             :findings []}))
        (catch clojure.lang.ExceptionInfo e
          (refusal (or (:code (ex-data e)) :semantic.package/namespace-rewrite)
                   [:namespace-remaps]))))))

(defn plan
  "Return a reviewable import plan. `targets`, when supplied, maps portable
   graph IDs to local qualified Graph names. Existing targets are updated as
   drafts; absent targets are created during apply."
  [conn caller {:keys [text digest root-graph targets namespace-remaps]} checkers]
  (let [digest (or digest (when (string? text) (contract/content-hash text)))
        read (package/read-package text digest checkers)]
    (if (seq (:findings read)) (select-keys read [:findings])
      (let [prepared (prepared-package read (or namespace-remaps {}) checkers)
            db (pool/db conn)
            workspace (:workspace caller)
            documents (:documents prepared)
            graph-ids (mapv #(get-in % [:graph :graph/id]) documents)
            root-id (get-in read [:package :root :graph/id])
            targets (or targets (default-targets workspace root-graph root-id documents))
            malformed (or (not= (set graph-ids) (set (keys targets)))
                          (not= (count targets) (count (distinct (vals targets))))
                          (some (fn [[_ qn]]
                                  (not= workspace (first (graph/parse-qualified-name qn)))) targets))]
        (cond
          (seq (:findings prepared)) prepared
          (or (not (string? root-graph)) (str/blank? root-graph))
          (refusal :semantic.package/root-target [:root-graph])
          (not= (count graph-ids) (count (distinct graph-ids)))
          (refusal :semantic.package/multiple-versions [:graphs])
          malformed (refusal :semantic.package/targets [:targets])
          :else
          (try
            (let [items
                  (mapv (fn [id]
                          (let [qn (get targets id)
                                definition (graph/graph db qn)]
                            (if definition
                              (do (guards/require-editable! db caller definition)
                                  {:package-graph id :target qn :mode :update
                                   :expected-revision (current-revision db workspace definition)})
                              {:package-graph id :target qn :mode :create
                               :expected-revision 0}))) graph-ids)
                  _ (when (some #(= :create (:mode %)) items)
                      (guards/require-workspace-owner! db caller))
                  target-ids (set (keep (fn [{:keys [target]}]
                                          (some-> (graph/graph db target) :graph/id)) items))
                  existing-assets (apply set/union #{}
                                         (for [{:keys [graph document]} (pool/semantic-drafts db)
                                               :when (not (contains? target-ids graph))]
                                           (semantic/document-asset-ids document)))
                  candidate-assets (apply set/union #{}
                                          (map semantic/document-asset-ids documents))
                  conflicts (set/intersection existing-assets candidate-assets)
                  _ (when (seq conflicts)
                      (throw (ex-info "Namespace conflicts with another Graph"
                                      {:code :semantic.package/namespace-conflict
                                       :conflicts conflicts})))
                  base {:package-digest digest :candidate-digest (:candidate-digest prepared)
                        :namespace-remaps (:namespace-remaps prepared)
                        :namespace-preview
                        (mapv (fn [[id namespace]]
                                {:package-graph id :namespace namespace
                                 :assets (count (semantic/document-asset-ids
                                                (some #(when (= id (get-in % [:graph :graph/id])) %) documents)))})
                              (:namespace-remaps prepared))
                        :root root-id :targets targets
                        :graphs items :source-bindings-required
                        (vec (for [document documents
                                   [slot source] (get-in document [:execution :sources])
                                   :when (not (contains? #{"derived" "static"} (:source/kind source)))]
                               {:graph (get-in document [:graph :graph/id])
                                :slot slot :kind (:source/kind source)}))
                        :publishes? false :activates? false :imports-credentials? false}]
              (assoc base :plan-id (contract/content-hash (pr-str base)) :findings []))
            (catch clojure.lang.ExceptionInfo e
              (if (= :semantic.package/namespace-conflict (:code (ex-data e)))
                {:findings [{:code :semantic.package/namespace-conflict :severity :error
                             :path [:namespace-remaps]
                             :conflicts (vec (sort-by pr-str (:conflicts (ex-data e))))}]}
                (refusal :semantic.package/not-permitted [:targets])))))))))

(defn rewrite-documents
  "Rewrite portable Graph IDs to local immutable Graph IDs, including exact
   dependency hashes. Child documents are rewritten before their parents."
  [documents local-ids workspace checkers]
  (let [by-id (into {} (map (fn [d] [(get-in d [:graph :graph/id]) d])) documents)
        rewritten (atom {})]
    (letfn [(rewrite-one [old-id]
              (or (get @rewritten old-id)
                  (let [document (get by-id old-id)
                        child-refs
                        (mapv (fn [dependency]
                                (let [child (rewrite-one (:graph/id dependency))
                                      written (contract/write-document child checkers)]
                                  {:graph/id (get local-ids (:graph/id dependency))
                                   :release/version (get-in child [:publication :release/version])
                                   :content/hash (contract/content-hash (:text written))}))
                              (get-in document [:graph :graph/dependencies]))
                        local-id (get local-ids old-id)
                        result (-> document
                                   (assoc :workspace {:workspace/id workspace})
                                   (assoc-in [:graph :graph/id] local-id)
                                   (assoc-in [:graph :graph/dependencies] child-refs)
                                   (update :ownership
                                           (fn [ownership]
                                             (into {} (map (fn [[kind owners]]
                                                             [kind (into {} (map (fn [[asset _]] [asset local-id])) owners)]))
                                                   ownership))))]
                    (swap! rewritten assoc old-id result)
                    result)))]
      (doseq [id (keys by-id)] (rewrite-one id))
      @rewritten)))

(defn apply!
  "Apply a previously reviewed plan. Re-planning makes repository changes or
   draft revision changes invalidate the plan before any write begins."
  [conn caller {:keys [plan-id] :as request} checkers provenance]
  (let [planned (plan conn caller request checkers)]
    (cond
      (seq (:findings planned)) planned
      (not= plan-id (:plan-id planned)) (refusal :semantic.package/plan-changed [:plan-id])
      :else
      (let [read (package/read-package (:text request) (:package-digest planned) checkers)
            prepared (prepared-package read (:namespace-remaps planned) checkers)
            documents (:documents prepared)
            workspace (:workspace caller)]
        ;; All format, ownership, dependency and target checks completed above.
        ;; Create empty authoring Graph entries first; no model assets or Source
        ;; instances are imported into the legacy pool.
        (doseq [{:keys [target mode]} (:graphs planned) :when (= mode :create)
                :let [[_ name] (graph/parse-qualified-name target)]]
          (let [created (graph/register-graph! conn {:workspace workspace :name name :members {}
                                                     :created-at (str (java.time.Instant/now))})]
            (when (seq (:findings created))
              (throw (ex-info "Graph import creation failed" {:result created})))))
        (let [db (pool/db conn)
              local-ids (into {} (map (fn [[portable qn]]
                                        [portable (:graph/id (graph/graph db qn))]))
                              (:targets planned))
              rewritten (rewrite-documents documents local-ids workspace checkers)
              validation (mapv #(contract/validate-document % checkers) (vals rewritten))]
          (if-let [bad (some #(when (seq (:findings %)) %) validation)]
            (select-keys bad [:findings])
            (do
              (doseq [{:keys [package-graph expected-revision]} (:graphs planned)]
                (pool/save-semantic-draft! conn workspace (get local-ids package-graph)
                                           expected-revision (get rewritten package-graph)
                                           checkers provenance))
              {:imported? true :package-digest (:package-digest planned)
               :root (get (:targets planned) (:root planned))
               :graphs (:graphs planned) :source-bindings-required (:source-bindings-required planned)
               :published? false :activated? false :findings []})))))))

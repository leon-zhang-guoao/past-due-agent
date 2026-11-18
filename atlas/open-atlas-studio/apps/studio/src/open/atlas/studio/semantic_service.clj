(ns open.atlas.studio.semantic-service
  "The authenticated semantic authoring service shared by GUI and MCP."
  (:require [open.atlas.common.contract :as version]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.runtime.env :as runtime-env]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-protocol :as protocol]
            ;; Connector namespaces are required for their side effect only:
            ;; loading one installs its spi/semantic-adapter defmethod.
            [open.atlas.common.connector.sql]
            [open.atlas.common.connector.api]
            [open.atlas.common.semantic-static]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.derived]
            [open.atlas.studio.graph-service :as guards]
            [open.atlas.studio.graph :as studio-graph]
            [open.atlas.studio.pool :as pool]))

(def adapters
  "One entry per installed Source kind, taken from the connector's own
   registration rather than re-spelled here, so Studio and runtime/env
   (closure.clj) validate and lower a Binding through the same adapter."
  (into {} (for [kind ["api" "nifi" "static" "sql" "derived"]]
             [kind (spi/semantic-adapter kind)])))
(def checkers (update-vals adapters :validate))

(defn publishability
  "Read-only proof that a saved Semantic draft has every physical Source it
   names and can be compiled by the runtime. Stored Source entries are returned
   for the cut step; resolved credentials never leave this function."
  [db workspace graph-id resolve-source]
  (let [draft (pool/semantic-draft db workspace graph-id)
        document (:document draft)
        sources (get-in document [:execution :sources])
        resolved
        (reduce-kv
         (fn [result id source]
           (if (contains? #{"derived" "static"} (:source/kind source))
             result
             (let [stored (pool/object db :source id)
                   expected (:source/kind source)
                   actual (get-in stored [:pool/entry :type])]
               (cond
                 (not= workspace (:pool/workspace stored))
                 (update result :findings conj
                         {:code :semantic.source/unavailable :severity :error
                          :path [:execution :sources id]
                          :message (str "Source " id " is not registered in this workspace.")})

                 (not= expected actual)
                 (update result :findings conj
                         {:code :semantic.source/kind-mismatch :severity :error
                          :path [:execution :sources id :source/kind]
                          :message (str "Source " id " is registered as " actual
                                        ", but the Contract requires " expected ".")})

                 :else
                 (try
                   (-> result
                       (assoc-in [:entries id] (:pool/entry stored))
                       (assoc-in [:connections id] (resolve-source id (:pool/entry stored))))
                   (catch Exception e
                     (update result :findings conj
                             {:code :semantic.source/configuration-unavailable :severity :error
                              :path [:execution :sources id]
                              :message (str "Source " id " could not be resolved: " (ex-message e))})))))))
         {:entries {} :connections {} :findings []}
         (or sources {}))
        candidate (when (and document (empty? (:findings resolved)))
                    (runtime-env/for-semantic-document
                     document {:adapters adapters :connections (:connections resolved)}))
        findings (vec (concat
                       (when-not document
                         [{:code :semantic.draft/missing :severity :error :path [:graph]
                           :message "No saved Semantic Contract draft exists for this Graph."}])
                       (:findings resolved)
                       (:findings candidate)))]
    {:would-cut? (empty? findings)
     :health-findings findings
     :entries (:entries resolved)
     :revision (:revision draft)}))

(defn empty-document [workspace graph label]
  (let [document
        {:contract version/semantic-v2
         :workspace {:workspace/id workspace}
         :graph {:graph/id graph :graph/label label :graph/exposure :restricted
                 :graph/entities [] :graph/resolvers [] :graph/operations []
                 :graph/dependencies [] :graph/terms []}
         :vocabulary {:entities {} :attributes {} :terms {} :units {}
                      :aliases [] :equivalences [] :references []}
         :execution {:resolvers {} :operations {} :sources {} :bindings {} :selection {}}
         :ownership {:entities {} :resolvers {} :operations {} :terms {}}
         :publication {:release/version "1.0.0" :source/revision 0
                       :required-capabilities #{}}}]
    ;; Compute the exact 2.0 capability floor from the same function used by
    ;; validation. A newly introduced mandatory capability cannot silently
    ;; leave the empty-Graph path behind again.
    (assoc-in document [:publication :required-capabilities]
              (contract/required-capabilities document))))

(defn- refused [code path] {:findings [{:code code :severity :error :path path}]})

(defn- published-content-artifact?
  "True only for the old published `content.edn` envelope. This is format
   classification, not a compatibility reader: a published artifact lacks the
   workspace, Graph ownership and Contract metadata required by draft import."
  [text]
  (try
    (and (string? text)
         (<= (alength (.getBytes ^String text "UTF-8")) (* 4 1024 1024))
         (let [value (edn/read-string {:readers {}
                                       :default (fn [_ _] (throw (ex-info "tag" {})))}
                                      text)]
           (and (map? value)
                (= 1 (:artifact/format-version value))
                (map? (:objects value))
                (vector? (:references value)))))
    (catch Exception _ false)))

(defn- published-content-refusal []
  {:findings
   [{:code :semantic.import/published-content-not-contract
     :severity :error
     :path [:contract]
     :message (str "This file is a legacy published content.edn artifact, not a Semantic Contract. "
                   "Generate an atlas-semantic Contract document for the target workspace and Graph ID, "
                   "then import that document here. No Graph content was changed.")}]})

(defn duplicate-document
  "Copy a Contract 2.0 Graph into a new Graph and namespace. Only assets owned
   by the source Graph are renamed. External Graph dependencies and external
   Term references remain exact; Source Slots remain logical declarations."
  [document new-graph-id new-namespace checkers]
  (if-not (and (= version/semantic-v2 (:contract document))
               (string? new-namespace) (re-matches #"[A-Za-z][A-Za-z0-9_.-]*" new-namespace))
    (refused :semantic.duplicate/input [:namespace])
    (let [leaf #(last (str/split % #"[/.]"))
          entity-map (into {} (map (fn [id] [id (str new-namespace "." (leaf id))]))
                           (keys (get-in document [:ownership :entities])))
          resolver-map (into {} (map (fn [id] [id (str new-namespace "/" (leaf id))]))
                             (keys (get-in document [:ownership :resolvers])))
          operation-map (into {} (map (fn [id] [id (str new-namespace "/" (leaf id))]))
                              (keys (get-in document [:ownership :operations])))
          term-map (into {} (map (fn [id] [id (str new-namespace "/" (leaf id))]))
                         (keys (get-in document [:ownership :terms])))
          attribute-map
          (into {} (for [attribute (keys (get-in document [:vocabulary :attributes]))
                         :let [entity (namespace attribute) replacement (get entity-map entity)]
                         :when replacement]
                     [attribute (keyword replacement (name attribute))]))
          replacements (merge entity-map resolver-map operation-map term-map attribute-map)
          collision? (some (fn [m] (not= (count m) (count (distinct (vals m)))))
                           [entity-map resolver-map operation-map term-map attribute-map])]
      (if collision?
        (refused :semantic.duplicate/namespace-conflict [:namespace])
        (let [copied (-> (walk/postwalk-replace replacements document)
                         (assoc-in [:graph :graph/id] new-graph-id)
                         (assoc-in [:graph :graph/label]
                                   (str (get-in document [:graph :graph/label]) " copy"))
                         (update :ownership
                                 (fn [ownership]
                                   (into {} (map (fn [[kind owners]]
                                                   [kind (into {} (map (fn [[asset _]] [asset new-graph-id])) owners)]))
                                         ownership)))
                         ;; EQL examples are stored as EDN text, so postwalk alone
                         ;; cannot rewrite their owned attribute identifiers.
                         (update-in [:graph :graph/query-examples]
                                    (fn [examples]
                                      (mapv (fn [example]
                                              (if (string? (:example/eql example))
                                                (update example :example/eql
                                                        #(->> (edn/read-string %)
                                                              (walk/postwalk-replace replacements)
                                                              pr-str))
                                                example)) examples)))
                         (assoc-in [:publication :release/version] "1")
                         (assoc-in [:publication :source/revision] 0))]
          (contract/validate-document copied checkers))))))

(defn merge-documents
  "Copy each source into a distinct namespace, then combine the copied drafts.
   Conflicting shared source declarations are refused rather than overwritten."
  [documents graph-id label namespace-prefix]
  (let [copies (mapv (fn [i document]
                       (duplicate-document document graph-id (str namespace-prefix ".g" i) checkers))
                     (range) documents)
        findings (vec (mapcat :findings copies))
        docs (mapv :document copies)
        source-conflicts (->> docs (mapcat #(get-in % [:execution :sources]))
                              (group-by first)
                              (keep (fn [[id entries]] (when (> (count (distinct (map second entries))) 1) id))))
        prepared (mapv (fn [i document]
                         (let [prefix (str "g" i "/")
                               bindings (get-in document [:execution :bindings])
                               ids (into {} (map (fn [id] [id (str prefix id)])) (keys bindings))]
                           (-> document
                               (update-in [:execution :bindings] #(into {} (map (fn [[id value]] [(ids id) value])) %))
                               (update-in [:execution :selection] #(update-vals % ids))
                               (update-in [:graph :graph/query-examples]
                                          #(mapv (fn [e] (update e :example/id (partial str prefix))) %)))))
                       (range) docs)
        registry-conflicts
        (for [path [[:vocabulary :entities] [:vocabulary :attributes] [:vocabulary :terms]
                    [:execution :resolvers] [:execution :operations] [:execution :bindings] [:execution :metrics]]
              [id entries] (group-by first (mapcat #(get-in % path) prepared))
              :when (> (count (distinct (map second entries))) 1)]
          (conj path id))
        combine (fn combine [a b]
                  (cond (and (map? a) (map? b)) (merge-with combine a b)
                        (and (set? a) (set? b)) (into a b)
                        (and (vector? a) (vector? b)) (vec (distinct (concat a b)))
                        (= a b) a
                        :else b))]
    (cond
      (seq findings) {:findings findings}
      (seq source-conflicts) {:findings [{:code :semantic.merge/source-conflict :severity :error
                                         :path [:execution :sources]
                                         :message "Selected Graphs have incompatible declarations for the same Source ID."}]}
      (seq registry-conflicts) {:findings [{:code :semantic.merge/asset-conflict :severity :error
                                           :path (first registry-conflicts)
                                           :message "Selected Graphs contain conflicting asset definitions; resolve them before merging."}]}
      :else
      (let [merged (reduce combine prepared)
            document (-> merged
                         (assoc-in [:graph :graph/label] label)
                         ;; A source guide describes its own graph, not the merged graph.
                         (update :graph dissoc :graph/usage-guide)
                         (assoc-in [:publication :required-capabilities] (contract/required-capabilities merged)))]
        (contract/validate-document document checkers)))))

(defn duplicate-plan
  "Describe the exact source snapshot and requested destination. The fingerprint
   binds apply to both; validation and namespace conflicts are checked by the host."
  [definition draft request]
  (let [document (:document draft)
        semantic? (pos? (or (:revision draft) 0))
        ownership (:ownership document)
        basis {:source definition :draft draft
               :destination (select-keys request [:name :namespace])}]
    {:plan-token (contract/content-hash (dialect/canonical-str basis))
     :source-revision (or (:revision draft) 0)
     :mode (if semantic? :semantic :legacy)
     :target (str (:graph/workspace definition) "/" (:name request))
     :namespace (:namespace request)
     :counts (if semantic?
               {:entities (count (:entities ownership))
                :attributes (count (get-in document [:vocabulary :attributes]))
                :resolvers (count (:resolvers ownership))
                :operations (count (:operations ownership))
                :terms (count (:terms ownership))
                :bindings (count (get-in document [:execution :bindings]))}
               (into {} (map (fn [[kind ids]] [kind (count ids)])) (:graph/members definition)))
     :dependencies (vec (get-in document [:graph :graph/dependencies]))
     :external-terms (vec (remove #(contains? (:terms ownership) %)
                                  (get-in document [:graph :graph/terms])))
     :source-slots (vec (sort (keys (get-in document [:execution :sources]))))
     :findings []}))

(defn rebind-document-context
  "Rebind only the source Contract's own context. Ownership values pointing at
   any external Graph remain exact. Validation is deliberately the caller's
   next step so this transformation can be audited independently."
  [document workspace graph-id]
  (let [source-workspace (get-in document [:workspace :workspace/id])
        source-graph (get-in document [:graph :graph/id])
        adopted (-> document
                    (assoc-in [:workspace :workspace/id] workspace)
                    (assoc-in [:graph :graph/id] graph-id)
                    (update :ownership
                            (fn [ownership]
                              (update-vals
                               ownership
                               (fn [owners]
                                 (into {}
                                       (map (fn [[asset owner]]
                                              [asset (if (= owner source-graph)
                                                       graph-id owner)]))
                                       owners)))))
                    (assoc-in [:publication :source/revision] 0))]
    {:document adopted
     :source-context {:workspace source-workspace :graph source-graph}
     :target-context {:workspace workspace :graph graph-id}}))

(defn adopt-document
  "Prepare one standalone Contract for the Graph selected by the authenticated
   caller. Asset IDs and Source Slots stay exact; only the containing workspace,
   Graph ID, and source-owned values are rebound. The result is validated as a
   complete Contract before it can be shown or saved."
  [document workspace graph-id checkers]
  (let [{:keys [document source-context target-context]}
        (rebind-document-context document workspace graph-id)]
    (assoc (contract/validate-document document checkers)
           :source-context source-context
           :target-context target-context)))

(defn- read-import-form
  "Read a Semantic Contract as one bounded EDN form before execution validation.
   Import must be able to replace an absent or foreign `:sources` registry, so
   the normal closed-world validator runs only after that replacement."
  [text]
  (try
    (if (or (not (string? text))
            (> (alength (.getBytes ^String text "UTF-8")) (* 4 1024 1024)))
      (refused :contract/input-limit [])
      (let [reader (java.io.PushbackReader. (java.io.StringReader. text))
            eof (Object.)
            opts {:eof eof :readers {}
                  :default (fn [_ _] (throw (ex-info "tag" {})))}
            document (edn/read opts reader)
            tail (edn/read opts reader)]
        (cond
          (not (identical? eof tail)) (refused :contract/trailing-form [])
          (not (map? document)) (refused :contract/unreadable [])
          :else {:document document :findings []})))
    (catch Exception _ (refused :contract/unreadable []))))

(defn replace-connector-kind-capabilities
  "Swap the connector KIND capabilities (`:connector/<kind>`, one per Source
   slot) for the kinds of `new-sources`, keeping every other capability.

   The `connector` namespace is not only kinds: doc 107 names feature
   capabilities there too (`:connector/api-status-map-v1`, §107.9 item 7).
   Stripping the whole namespace and re-adding the kinds silently dropped
   those, and the import then refused its own document with
   `:contract/capability-undeclared` (seen on the SE graph, 2026-09-18).
   Only the kind capabilities of the previous and the new slot sets are
   replaced; the rest of the declared set is the author's."
  [caps old-sources new-sources]
  (let [kind-cap (fn [sources] (into #{} (map (comp #(keyword "connector" %) :source/kind val)) sources))]
    (-> (set caps)
        (set/difference (kind-cap old-sources))
        (into (kind-cap new-sources)))))

(defn carry-slot-policy
  "The imported slot's `:source/policy` follows its bindings onto the Graph
   Source they were mapped to. Connection fields are the deployment's and are
   discarded here; the policy is the modeller's semantic half (doc 107 §107.3)
   and lives in the Graph, so dropping it at import is the «declared and
   silently ignored» state §107.1 forbids — the SE v7 import lost its
   `:retry_on` this way on 2026-09-18 while still declaring `:source/policy-v1`.
   `imported-slot` is the entry the binding named in the imported document;
   nil or a policy-less slot leaves `source` as it is. Two imported slots that
   land on one Graph Source with DIFFERENT policies are refused by the caller."
  [source imported-slot]
  (if-let [policy (:source/policy imported-slot)]
    (assoc source :source/policy policy)
    source))

(defn- workspace-source-options [db workspace current]
  (let [graph-source-order (vec (keys (get-in current [:execution :sources])))
        graph-source-index (zipmap graph-source-order (range))]
    (->> (pool/objects db workspace)
         (keep (fn [{:pool/keys [type id entry]}]
                 (when (= :source type)
                   {:id id :label (:label entry) :type (:type entry)
                    :graph-source? (contains? graph-source-index id)
                    :graph-source-index (get graph-source-index id)})))
         ;; Existing Graph Sources keep the order chosen in the Graph. The rest
         ;; are only an inventory for the explicit "Add from Workspace" action.
         (sort-by (fn [{:keys [id graph-source? graph-source-index]}]
                    (if graph-source?
                      [0 graph-source-index]
                      [1 id])))
         vec)))

(defn- binding-compatible? [kind binding]
  (when-let [checker (get checkers kind)]
    (try
      (let [result (checker binding)]
        ;; Current adapters return findings; retain legacy boolean adapters.
        ;; Missing or malformed findings must not make a Source compatible.
        (or (true? result)
            (and (map? result)
                 (sequential? (:findings result))
                 (empty? (:findings result)))))
      (catch Exception _ false))))

(defn- graph-sources-view [db workspace current]
  (let [document (:document current)
        bindings (vals (get-in document [:execution :bindings]))
        used-by (reduce (fn [m binding]
                          (update m (:binding/source binding) (fnil conj [])
                                  (second (:binding/for binding))))
                        {} bindings)
        options (workspace-source-options db workspace document)
        option-by-id (into {} (map (juxt :id identity)) options)
        graph-ids (vec (remove #(contains? #{"static" "derived"}
                                            (get-in document [:execution :sources % :source/kind]))
                               (keys (get-in document [:execution :sources]))))]
    {:revision (:revision current)
     :graph-sources
     (mapv (fn [id]
             (let [registered (get option-by-id id)
                   definition (get-in document [:execution :sources id])]
               (merge {:id id
                       :type (:source/kind definition)
                       :effects (:source/effects definition)
                       :used-by (vec (sort (get used-by id)))
                       :registered? (boolean registered)}
                      (select-keys registered [:label]))))
           graph-ids)
     :available-sources (mapv #(select-keys % [:id :label :type]) options)
     :workspace-sources
     (mapv #(select-keys % [:id :label :type])
           (remove :graph-source? options))
     :findings []}))

(defn- replace-graph-sources
  "Replace the Graph's explicit Source selection. A Source still named by a
   Binding cannot be removed; adding one stores only its registered identity,
   connector kind and read capability, never connection configuration."
  [db workspace current source-ids]
  (let [source-ids (vec (distinct (filter string? source-ids)))
        registered (into {} (keep (fn [{:pool/keys [type id entry]}]
                                    (when (= :source type) [id entry])))
                         (pool/objects db workspace))
        missing (vec (remove #(contains? registered %) source-ids))
        bindings (vals (get-in current [:document :execution :bindings]))
        used-by (reduce (fn [m binding]
                          (update m (:binding/source binding) (fnil conj [])
                                  (second (:binding/for binding))))
                        {} bindings)
        existing-sources (get-in current [:document :execution :sources])
        local-source? #(contains? #{"static" "derived"} (:source/kind (get existing-sources %)))
        removed-in-use (vec (sort (filter #(and (not (local-source? %))
                                                (seq (get used-by %))
                                                (not (some #{%} source-ids)))
                                          (keys existing-sources))))
        findings (vec
                  (concat
                   (for [id missing]
                     {:code :semantic.graph-source/source-unavailable
                      :severity :error :path [:source_ids]
                      :message (str "Source " id " is not registered in this Workspace.")})
                   (for [id removed-in-use]
                     {:code :semantic.graph-source/source-in-use
                      :severity :error :path [:source_ids]
                      :message (str "Source " id " is still used by Resolver bindings: "
                                    (str/join ", " (sort (get used-by id))) ".")})))]
    (if (seq findings)
      {:findings findings}
      (let [local-sources (filter (comp local-source? key) existing-sources)
            sources (into (into (array-map)
                                (for [id source-ids
                                      :let [kind (:type (get registered id))]]
                                  [id (or (get existing-sources id)
                                          {:source/kind kind :source/effects #{:read}})]))
                          local-sources)
            document (-> (:document current)
                         (assoc-in [:execution :sources] sources)
                         (update-in [:publication :required-capabilities]
                                    replace-connector-kind-capabilities
                                    (get-in current [:document :execution :sources]) sources))]
        {:document document :findings []}))))

(defn- remap-graph-sources [db workspace current replacements]
  (let [document (:document current)
        sources (get-in document [:execution :sources])
        options (into {} (map (juxt :id identity)) (workspace-source-options db workspace document))
        invalid (some (fn [[from to]]
                        (or (nil? (get sources from))
                            (nil? (get options to))
                            (not= (:source/kind (get sources from)) (:type (get options to))))) replacements)]
    (if invalid
      {:findings [{:code :semantic.graph-source/incompatible-connection :severity :error :path [:source_replacements]
                   :message "Choose a registered Workspace connection of the same source type."}]}
      {:document
       (-> document
           (update-in [:execution :bindings]
                      #(update-vals % (fn [binding]
                                        (update binding :binding/source (fn [id] (get replacements id id))))))
           (assoc-in [:execution :sources]
                     (reduce-kv (fn [result id definition]
                                  (assoc result (get replacements id id) definition)) {} sources)))
       :findings []})))

(defn- selected-bindings
  "One active Binding per definition. A missing selection is recoverable only
   when the imported document contains exactly one Binding for that target."
  [document]
  (let [execution (:execution document)
        bindings (:bindings execution)
        targets (concat (map (partial vector :resolver) (keys (:resolvers execution)))
                        (map (partial vector :operation) (keys (:operations execution))))]
    (mapv
     (fn [target]
       (let [selected (get-in execution [:selection target])
             candidates (vec (sort (for [[bid binding] bindings
                                         :when (= target (:binding/for binding))]
                                     bid)))
             binding-id (or selected (when (= 1 (count candidates)) (first candidates)))]
         {:target target :binding-id binding-id :binding (get bindings binding-id)}))
     targets)))

(defn- source-plan
  "Replace imported Source identity with the target Graph's registered Sources.
   Imported connection fields are discarded; alternative Bindings are preserved.
   Resolver implementations and their semantic input/output maps stay exact."
  [db workspace current document requested additions]
  (let [options (workspace-source-options db workspace current)
        registered-source-ids (set (map :id options))
        graph-source-ids (set (map :id (filter :graph-source? options)))
        additions (vec (distinct (filter string? additions)))
        added-source-ids (set additions)
        allowed-source-ids (into graph-source-ids added-source-ids)
        selections (selected-bindings document)
        selected-ids (set (keep :binding-id selections))
        binding-selections (concat selections
                                   (for [[bid binding] (get-in document [:execution :bindings])
                                         :when (not (selected-ids bid))]
                                     {:target (:binding/for binding) :binding-id bid :binding binding}))
        rows
        (mapv
         (fn [{:keys [target binding-id binding]}]
           (let [[target-kind target-id] target
                 definition (get-in document [:execution (if (= :resolver target-kind)
                                                           :resolvers :operations) target-id])
                 imported-source (:binding/source binding)
                 imported-kind (get-in document [:execution :sources imported-source :source/kind])
                 local-kinds (vec (filter #(binding-compatible? % binding) ["derived" "static"]))
                 local-kind (cond
                              (some #{imported-kind} local-kinds) imported-kind
                              (= 1 (count local-kinds)) (first local-kinds))
                 compatible-workspace (if local-kind []
                                          (vec (filter #(binding-compatible? (:type %) binding) options)))
                 compatible (vec (filter #(contains? allowed-source-ids (:id %))
                                         compatible-workspace))
                 requested-key (cond
                                 (contains? requested [:binding binding-id]) [:binding binding-id]
                                 (contains? requested target) target
                                 (and (= :resolver target-kind) (contains? requested target-id)) target-id)
                 requested? (some? requested-key)
                 requested-id (get requested requested-key)
                 default-id (:id (first compatible))
                 selected-id (if requested? requested-id default-id)
                 selected-option (first (filter #(= selected-id (:id %)) compatible))
                 status (cond
                          (nil? binding) :binding-missing
                          local-kind :not-required
                          (empty? compatible-workspace) :no-compatible-source
                          (and (empty? compatible) (not requested?)) :source-selection-required
                          (and requested?
                               (contains? registered-source-ids requested-id)
                               (not (contains? allowed-source-ids requested-id))) :source-not-added
                          (nil? selected-option) :source-incompatible
                          :else :ready)]
             {:target target-kind
              :resolver-id (when (= :resolver target-kind) target-id)
              :operation-id (when (= :operation target-kind) target-id)
              :label (or (:label definition) target-id)
              :binding-id binding-id
              :external-source imported-source
              :requires-source? (nil? local-kind)
              :local-kind local-kind
              :required-kind (or local-kind
                                 (when (binding-compatible? imported-kind binding) imported-kind)
                                 (first (sort (filter #(binding-compatible? % binding) (keys checkers)))))
              :selected-source (when-not local-kind selected-id)
              :options compatible
              :workspace-options compatible-workspace
              :status status}))
         binding-selections)
        source-findings
        (vec
         (keep
          (fn [{:keys [target resolver-id operation-id status selected-source]}]
            (when-not (contains? #{:ready :not-required} status)
              {:code (case status
                       :binding-missing :semantic.import/binding-missing
                       :no-compatible-source :semantic.import/no-compatible-source
                       :source-selection-required :semantic.import/source-selection-required
                       :source-not-added :semantic.import/source-not-added
                       :source-incompatible :semantic.import/source-incompatible)
               :severity :error
               :path [:source-mappings (or resolver-id operation-id)]
               :message (case status
                          :binding-missing "This definition has no unambiguous active Binding."
                          :no-compatible-source "No registered Source in this Workspace supports this Binding implementation."
                          :source-selection-required "This Resolver needs a Source. Add a compatible Workspace Source to the Graph before importing."
                          :source-not-added (str "Selected Source " selected-source
                                                 " has not been explicitly added to this Graph.")
                          :source-incompatible (str "Selected Source " selected-source
                                                    " is unavailable or incompatible with this Binding."))}))
          rows))
        active (into {} (keep (fn [{:keys [binding-id binding]}]
                                (when (and binding-id binding) [binding-id binding]))) binding-selections)
        selection-map (into {} (keep (fn [{:keys [target binding-id]}]
                                       (when binding-id [target binding-id]))) selections)
        row-by-binding (into {} (map (juxt :binding-id identity)) rows)
        mapped-bindings
        (into (sorted-map)
              (for [[bid binding] active
                    :let [target (:binding/for binding)
                          row (row-by-binding bid)
                          source-id (or (:local-kind row)
                                        (when (= :ready (:status row)) (:selected-source row))
                                        (str "unbound:" bid))]]
                [bid (assoc binding :binding/source source-id)]))
        mapped-sources
        (reduce
         (fn [sources [bid binding]]
           (let [target (:binding/for binding)
                 row (row-by-binding bid)
                 id (:binding/source binding)
                 kind (or (:local-kind row)
                          (:type (first (filter #(= id (:id %)) (:options row))))
                          (:required-kind row))]
             (if-not kind sources
               (update sources id
                       (fn [source]
                         (carry-slot-policy
                          (cond-> {:source/kind kind
                                   :source/effects (conj (or (:source/effects source) #{})
                                                         (:binding/effect binding))}
                            (:source/policy source) (assoc :source/policy (:source/policy source)))
                          (get-in document [:execution :sources (:external-source row)])))))))
         ;; Graph Sources are selected independently of this imported file.
         ;; Keep the whole selected set, even when this Contract uses only a
         ;; subset; import may add to it only through explicit source_additions.
         (into (array-map)
               (for [{:keys [id type]} options
                     :when (contains? allowed-source-ids id)]
                 [id (or (get-in current [:execution :sources id])
                         {:source/kind type :source/effects #{:read}})]))
         mapped-bindings)
        mapped-document
        (-> document
            (assoc-in [:execution :bindings] mapped-bindings)
            (assoc-in [:execution :selection] selection-map)
            (assoc-in [:execution :sources] mapped-sources)
            (update-in [:publication :required-capabilities]
                       replace-connector-kind-capabilities
                       (get-in current [:execution :sources]) mapped-sources))
        mappings (into {} (keep (fn [{:keys [resolver-id selected-source requires-source?]}]
                                  (when (and resolver-id requires-source? selected-source)
                                    [resolver-id selected-source]))) rows)]
    {:document mapped-document
     :source-options options
     :resolver-sources (vec (filter :resolver-id rows))
     :operation-sources (vec (filter :operation-id rows))
     :source-mappings mappings
     :source-additions additions
     :source-findings source-findings
     :source-mapping-ready? (empty? source-findings)}))

(defn- prepare-import
  [db workspace graph-id current text requested additions]
  (if (published-content-artifact? text)
    (published-content-refusal)
    (let [read (read-import-form text)]
      (if-not (:document read)
        read
        (let [source-document (:document read)]
          (cond
            (contains? source-document :package/family)
            {:findings [{:code :semantic.import/package-not-supported
                         :severity :error :path [:package/family]
                         :message "Model import accepts single-Graph .edn files only. Select the model file, not a .package.edn Graph Package. No draft was changed."}]}
            ;; Task book «contract-upgrade-and-retirement» PR1 (owner
            ;; 2026-09-19): a legacy or 1.x document is refused by name, with
            ;; the skill that rebuilds it; nothing is read from it.
            (version/retired? (:contract source-document))
            {:findings [version/version-retired]}
            (and (zero? (:revision current))
                 (not= version/semantic-v2 (:contract source-document)))
            {:findings [{:code :semantic.import/current-version-required
                         :severity :error :path [:contract]
                         :message "A new Graph must begin with Semantic Contract 2.0."}]}
            :else
            (let [{:keys [document source-context target-context]}
                  (rebind-document-context source-document workspace graph-id)
              planned (source-plan db workspace (:document current) document
                                   (or requested {}) (or additions []))
              checked (contract/validate-document (:document planned) checkers)
              findings (vec (:findings checked))
              document (or (:document checked) (:document planned))]
          (cond-> (assoc planned
                         :document document
                         :source-context source-context
                         :target-context target-context
                         :findings findings)
            (empty? findings)
            (assoc :text (:text (contract/write-document document checkers)))))))))))

(defn- resolver-sources-view [db workspace current]
  (let [document (:document current)
        requested (into {} (for [[bid binding] (get-in document [:execution :bindings])]
                              [[:binding bid] (:binding/source binding)]))
        plan (source-plan db workspace document document requested [])]
    (assoc (select-keys plan [:resolver-sources :operation-sources :source-options :source-findings])
           :revision (:revision current))))

(defn- configure-resolver-sources
  "Update the same persisted bindings as Object Review. Explicit per-binding
   assignments win over a per-kind default. A per-kind default SETS EVERY
   binding of that kind, replacing what it pointed at (owner, 2026-10-01): the
   old rule filled only unbound slots, so «use alpha for all sql» silently left
   the ones already bound to something else, and the operator had to find and
   redo each by hand. Derived and static bindings need no registered Source and
   are never touched. Validate every assignment before any save, and never
   persist Workspace credentials."
  [db workspace current binding-sources defaults]
  (let [document (:document current)
        options (into {} (map (juxt :id identity)) (workspace-source-options db workspace document))
        bindings (get-in document [:execution :bindings])
        choices (merge
                 (into {} (keep (fn [[bid binding]]
                                  (let [old (:binding/source binding)
                                        kind (get-in document [:execution :sources old :source/kind])
                                        selected (get defaults kind)]
                                    (when (and selected (not (contains? #{"derived" "static"} kind)))
                                      [bid selected]))) bindings))
                 binding-sources)
        invalid (for [[bid id] choices
                      :let [binding (get bindings bid) option (get options id)]
                      :when (or (nil? binding) (nil? option)
                                (not (binding-compatible? (:type option) binding)))]
                  {:code :semantic.import/source-incompatible :severity :error
                   :path [:binding_sources bid]
                   :message "Choose a compatible Source registered in this Workspace."})]
    (if (seq invalid)
      {:findings (vec invalid)}
      (let [updated (reduce-kv
                     (fn [d bid id]
                       (let [binding (get-in d [:execution :bindings bid])
                             slot (get-in d [:execution :sources (:binding/source binding)])]
                         (-> d
                             (assoc-in [:execution :bindings bid :binding/source] id)
                             (update-in [:execution :sources id]
                                        (fn [existing]
                                          (carry-slot-policy
                                           (cond-> {:source/kind (:type (get options id))
                                                     :source/effects (conj (or (:source/effects existing) #{}) (:binding/effect binding))}
                                             (:source/policy existing) (assoc :source/policy (:source/policy existing)))
                                           slot)))))) document choices)
            used (set (map :binding/source (vals (get-in updated [:execution :bindings]))))
            updated (update-in updated [:execution :sources]
                               (fn [sources] (into {} (remove (fn [[id _]] (and (not (contains? options id)) (not (used id)) (not (contains? #{"derived" "static"} (:source/kind (get sources id))))))) sources)))]
        (contract/validate-document updated checkers)))))

(defn document-asset-ids [document]
  (set (concat (map (partial vector :entity) (keys (get-in document [:vocabulary :entities])))
               (map (partial vector :attribute) (keys (get-in document [:vocabulary :attributes])))
               (map (partial vector :term) (keys (get-in document [:vocabulary :terms])))
               (map (partial vector :resolver) (keys (get-in document [:execution :resolvers])))
               (map (partial vector :operation) (keys (get-in document [:execution :operations]))))))

(defn prepare-lookup
  "Build one explicit single-record SQL definition. Identity and nullability
   come from author input, not guesses from physical source names."
  [document {:keys [entity-id label identity-name identity-type value-name value-type value-nullable? source-id resolver-id sql-text] :as lookup}]
  (let [graph (get-in document [:graph :graph/id])]
    (if-not (and (= #{:entity-id :label :identity-name :identity-type :value-name :value-type :value-nullable? :source-id :resolver-id :sql-text} (set (keys lookup)))
                 (every? #(and (string? %) (seq %))
                         [entity-id label identity-name value-name source-id resolver-id sql-text])
                 (not-any? #(re-find #"[/\s]" %) [entity-id identity-name value-name])
                 (contains? #{:integer :string} identity-type)
                 (contains? #{:integer :string :boolean} value-type)
                 (boolean? value-nullable?) (not= identity-name value-name)
                 (not (contains? (get-in document [:vocabulary :entities]) entity-id))
                 (not (contains? (get-in document [:execution :resolvers]) resolver-id))
                 (not (contains? (get-in document [:execution :bindings]) resolver-id))
                 (or (nil? (get-in document [:execution :sources source-id]))
                     (= {:source/kind "sql" :source/effects #{:read}}
                        (get-in document [:execution :sources source-id]))))
      (refused :semantic.studio/lookup-input [:lookup])
      (let [id (keyword entity-id identity-name) value (keyword entity-id value-name)
            proposed (-> document
                         (assoc-in [:vocabulary :entities entity-id]
                                   {:entity/label label :entity/identity [id] :entity/attributes [id value]})
                         (assoc-in [:vocabulary :attributes id]
                                   {:attribute/label identity-name :attribute/type identity-type :attribute/nullable? false})
                         (assoc-in [:vocabulary :attributes value]
                                   {:attribute/label value-name :attribute/type value-type :attribute/nullable? value-nullable?})
                         (assoc-in [:execution :resolvers resolver-id]
                                   {:label label :effect :read :input [id] :output [value] :result/cardinality :one})
                         (assoc-in [:execution :sources source-id] {:source/kind "sql" :source/effects #{:read}})
                         (assoc-in [:execution :bindings resolver-id]
                                   {:binding/for [:resolver resolver-id] :binding/source source-id :binding/effect :read
                                    :binding/implementation {:query sql-text}
                                    :binding/input-map {id identity-name} :binding/output-map {value value-name}})
                         (assoc-in [:execution :selection [:resolver resolver-id]] resolver-id)
                         (assoc-in [:ownership :entities entity-id] graph)
                         (assoc-in [:ownership :resolvers resolver-id] graph)
                         (update-in [:graph :graph/entities] conj entity-id)
                         (update-in [:graph :graph/resolvers] conj resolver-id)
                         (update-in [:publication :required-capabilities] conj :connector/sql))]
        (contract/validate-document proposed checkers)))))

(defn- draft-candidate
  "The draft document compiled against its live Source connections — the
   environment a Query Studio preview runs on. Throws when a Source is not
   this workspace's or not the declared kind."
  [db workspace document resolve-source]
  (let [connections
        (into {} (for [[id source] (get-in document [:execution :sources])
                       :when (not (#{"derived" "static"} (:source/kind source)))]
                   (let [stored (pool/object db :source id)]
                     (when-not (and (= workspace (:pool/workspace stored))
                                    (= (:source/kind source) (get-in stored [:pool/entry :type])))
                       (throw (ex-info "Source unavailable" {})))
                     [id (resolve-source id (:pool/entry stored))])))]
    (runtime-env/for-semantic-document document {:adapters adapters :connections connections})))

(defn service
  "Caller is authenticated by the host, never read from request arguments.
   Source configurations are resolved by the same credential resolver as legacy Studio.

   `:query-stats?` (construction option) makes `:query` also return the
   engine's run-stats, from which Query Studio draws Plan and Trace. It is set
   by the HTTP host for its own preview and never read from request arguments,
   so a `semantic_draft` call over MCP cannot ask for (or be sent) the
   unscrubbed result."
  ([conn caller resolve-source] (service conn caller resolve-source {}))
  ([conn caller resolve-source {:keys [cut-artifact! query-stats?]}]
  (fn [action {:keys [graph text expected_revision query entity lenient? lookup mutation params
                      source_mappings source_additions source_ids source_replacements binding_sources default_sources]
               :as request} provenance]
    (let [db (pool/db conn)
          definition (guards/require-graph! db caller graph)
          ;; A semantic document includes implementations. Require editor even
          ;; for reads, like the existing release/editor face.
          _ (guards/require-editable! db caller definition)
          workspace (:workspace caller)
          graph-id (:graph/id definition)
          current (or (pool/semantic-draft db workspace graph-id)
                      {:document (empty-document workspace graph-id (or (:graph/label definition) graph))
                       :revision 0 :findings []})]
      (case action
        :resolver-sources (resolver-sources-view db workspace current)
        :configure-resolver-sources
        (if-not (= expected_revision (:revision current))
          (refused :semantic.draft/revision-conflict [:expected_revision])
          (let [prepared (configure-resolver-sources db workspace current (or binding_sources {}) (or default_sources {}))]
            (if (seq (:findings prepared)) prepared
              (let [saved (pool/save-semantic-draft! conn workspace graph-id expected_revision
                                                     (:document prepared) checkers provenance)]
                (if (seq (:findings saved)) saved
                  (assoc (resolver-sources-view (pool/db conn) workspace saved) :updated? true))))))
        :prepare-lookup (assoc (prepare-lookup (:document current) lookup) :revision (:revision current) :proposal? true)
        :graph-sources (graph-sources-view db workspace current)
        :update-graph-sources
        (if-not (= expected_revision (:revision current))
          (refused :semantic.draft/revision-conflict [:expected_revision])
          (let [remapped (remap-graph-sources db workspace current source_replacements)
                prepared (if (seq (:findings remapped)) remapped
                             (replace-graph-sources db workspace (assoc current :document (:document remapped)) source_ids))]
            (if (seq (:findings prepared))
              prepared
              (assoc (pool/save-semantic-draft! conn workspace graph-id expected_revision
                                                (:document prepared) checkers provenance)
                     :updated? true))))
        :prepare-import
        (assoc (prepare-import db workspace graph-id current text source_mappings source_additions)
               :proposal? true :revision (:revision current))
        :read (assoc current :text (:text (contract/write-document (:document current) checkers)))
        (:validate :import :save)
        (let [prepared-import (when (= action :import)
                                (prepare-import db workspace graph-id current text
                                                source_mappings source_additions))
              read (if prepared-import
                     (if (or (seq (:findings prepared-import))
                             (some #(contains? #{:semantic.import/binding-missing :semantic.import/source-incompatible :semantic.import/source-not-added} (:code %)) (:source-findings prepared-import)))
                       {:findings (vec (concat (:findings prepared-import)
                                               (:source-findings prepared-import)))}
                       {:document (:document prepared-import) :findings []})
                     (if (published-content-artifact? text)
                       (published-content-refusal)
                       (contract/read-document text checkers)))
              document (:document read)]
          (cond
            (nil? document) (if (seq (:findings read)) (select-keys read [:findings])
                               (refused :semantic.studio/legacy-document [:contract]))
            (not= workspace (get-in document [:workspace :workspace/id])) (refused :semantic.draft/workspace-mismatch [:workspace])
            (not= graph-id (get-in document [:graph :graph/id])) (refused :semantic.draft/graph-mismatch [:graph])
            (= action :validate) (assoc (select-keys read [:findings]) :valid? true)
            (and (zero? (:revision current))
                 (not= version/semantic-v2 (:contract document)))
            (refused :semantic.draft/current-version-required [:contract])
            :else (let [saved (locking conn
                      ;; A deletion may have committed since the initial read.
                      (let [fresh (guards/require-graph! (pool/db conn) caller graph)]
                        (when-not (= graph-id (:graph/id fresh))
                          (throw (ex-info "Graph was replaced; reload before saving" {:code :semantic.draft/graph-changed}))))
                      (pool/save-semantic-draft! conn workspace graph-id expected_revision document checkers provenance))]
                    (cond-> saved
                      (and (= action :import) (empty? (:findings saved)))
                      (assoc :imported? true :source-findings (:source-findings prepared-import))))))
        :cut-artifact
        (cond
          (nil? cut-artifact!) (refused :semantic.studio/publish-not-enabled [])
          (not= expected_revision (:revision current)) (refused :semantic.draft/revision-conflict [:expected_revision])
          :else
          (let [assessment (publishability db workspace graph-id resolve-source)]
            (if-not (:would-cut? assessment)
              {:findings (:health-findings assessment)}
              (cut-artifact! {:graph graph :workspace workspace :graph-id graph-id
                              :expected-revision expected_revision
                              :entries (:entries assessment)}))))
        :execute
        (cond
          (not= :ui (:entrypoint provenance)) (refused :semantic.studio/gui-write-only [])
          (zero? (:revision current)) (refused :semantic.studio/save-first [])
          (not= expected_revision (:revision current)) (refused :semantic.draft/revision-conflict [:expected_revision])
          :else
          (try
            (let [document (:document current)
                  connections (into {} (for [[id source] (get-in document [:execution :sources])
                                             :when (not (#{"derived" "static"} (:source/kind source)))]
                                         (let [stored (pool/object db :source id)]
                                           (when-not (and (= workspace (:pool/workspace stored))
                                                          (= (:source/kind source) (get-in stored [:pool/entry :type])))
                                             (throw (ex-info "Source unavailable" {})))
                                           [id (resolve-source id (:pool/entry stored))])))
                  candidate (runtime-env/for-semantic-document document {:adapters adapters :connections connections})]
              (if (:env candidate)
                (assoc (runtime-env/execute-semantic-candidate candidate {:mutation mutation :params params})
                       :revision (:revision current))
                (select-keys candidate [:findings])))
            (catch Exception _ {:operation/status :unknown
                                :message "Write outcome is unknown; inspect the receipt before retrying."})))
        :query
        (let [document (:document current)
              supported runtime-env/semantic-preview-capabilities
              findings (:findings (contract/check-support document supported))]
          (cond
            (zero? (:revision current)) (refused :semantic.studio/save-first [])
            (not= expected_revision (:revision current)) (refused :semantic.draft/revision-conflict [:expected_revision])
            (seq (:findings (protocol/validate-query-request {:graph/id graph-id :request/id "studio-preview" :query query :entity (or entity {}) :lenient? (boolean lenient?)}))) (refused :semantic.studio/query-shape [:query])
            (seq findings) {:findings findings}
            :else
            (try
              (let [candidate (draft-candidate db workspace document resolve-source)]
                (if (:env candidate)
                  (assoc (runtime-env/query-semantic-candidate candidate {:query query :entity (or entity {}) :lenient? (boolean lenient?)}
                                                                {:stats? (boolean query-stats?)})
                         :revision (:revision current))
                  (select-keys candidate [:findings])))
              (catch Exception _ (refused :semantic.studio/query-unavailable [:query])))))
        ;; The draft's catalog, for checking that a saved query names only
        ;; attributes this graph has (proposal studio-saved-queries §3.2):
        ;; the same compile a preview runs on, so «unknown here» means
        ;; unknown when queried.
        :catalog
        (if (zero? (:revision current))
          (refused :semantic.studio/save-first [])
          (try
            (let [candidate (draft-candidate db workspace (:document current) resolve-source)]
              (if (:env candidate)
                {:catalog (engine/catalog (:env candidate)) :revision (:revision current)}
                (select-keys candidate [:findings])))
            (catch Exception _ (refused :semantic.studio/query-unavailable [:graph]))))
        (refused :semantic.studio/unknown-action [:action])))))
)

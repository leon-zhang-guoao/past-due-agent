(ns open.atlas.common.semantic-execution-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-execution :as execution]))

(def vocabulary
  {:entities {"case" {:entity/label "Case" :entity/identity [:case/id]
                      :entity/attributes [:case/id :case/title :case/notes]}
              "note" {:entity/label "Note" :entity/identity [:note/id]
                      :entity/attributes [:note/id]}}
   :attributes {:case/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :case/title {:attribute/label "Title" :attribute/type :string :attribute/nullable? false}
                :case/notes {:attribute/label "Notes" :attribute/type :ref :attribute/nullable? false
                             :attribute/target "note" :attribute/cardinality :many}
                :note/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}}})

(def sample
  {:resolvers {"lookup" {:label "Lookup" :effect :read :result/cardinality :one :input [:case/id]
                         :output [:case/title {:case/notes [:note/id]}]}}
   :operations {"create" {:label "Create" :effect :write :result/cardinality :one :input [:case/title] :output [:case/id]}}
   :sources {"support" {:source/kind "test" :source/effects #{:read :write}}}
   :bindings {"lookup-test" {:binding/for [:resolver "lookup"] :binding/source "support"
                             :binding/effect :read :binding/implementation {:fixture :lookup}
                             :binding/input-map {:case/id "id"}
                             :binding/output-map {:case/title "title" :case/notes "notes" :note/id "id"}}
              "create-test" {:binding/for [:operation "create"] :binding/source "support"
                             :binding/effect :write :binding/implementation {:fixture :create}
                             :binding/input-map {:case/title "title"} :binding/output-map {:case/id "id"}}}
   :selection {[:resolver "lookup"] "lookup-test" [:operation "create"] "create-test"}})

(def checkers {"test" #(contains? #{:lookup :create} (get-in % [:binding/implementation :fixture]))})
(defn validate [x] (execution/validate-execution vocabulary x checkers))
(defn codes [x] (set (map :code (:findings (validate x)))))

(deftest cardinality-cannot-be-inferred-from-output
  (is (contains? (codes (update-in sample [:resolvers "lookup"] dissoc :result/cardinality)) :execution/schema))
  (is (contains? (codes (assoc-in sample [:operations "create" :result/cardinality] :none)) :execution/result-cardinality))
  (is (contains? (codes (assoc-in sample [:resolvers "lookup" :result/cardinality] :many)) :execution/result-cardinality)))

(deftest output-trees-must-match-entity-relationships
  (is (contains? (codes (assoc-in sample [:resolvers "lookup" :output] [:case/notes])) :execution/output-shape))
  (is (contains? (codes (assoc-in sample [:resolvers "lookup" :output] [{:case/notes [:case/id]}])) :execution/output-owner))
  (is (contains? (codes (assoc-in sample [:resolvers "lookup" :output] [:case/title :case/title])) :execution/duplicate-output)))

(deftest nested-reads-and-explicit-writes-stay-separate
  (is (= sample (:execution (validate sample))))
  (is (contains? (codes (assoc-in sample [:resolvers "lookup" :effect] :write)) :execution/schema)))

(deftest invalid-publication-does-not-return-partial-execution
  (doseq [[x code] [[(update sample :selection dissoc [:resolver "lookup"]) :execution/binding-required]
                    [(assoc-in sample [:selection [:resolver "lookup"]] "create-test") :execution/selection]
                    [(update sample :sources dissoc "support") :execution/source-reference]
                    [(assoc-in sample [:bindings "create-test" :binding/effect] :read) :execution/effect-mismatch]
                    [(assoc-in sample [:sources "support" :source/effects] #{:read}) :execution/source-effect]
                    [(assoc-in sample [:bindings "lookup-test" :binding/input-map] {}) :execution/input-map]
                    [(assoc-in sample [:bindings "lookup-test" :binding/output-map] {}) :execution/output-map]
                    [(assoc-in sample [:operations "create" :input] [:missing/key]) :execution/attribute-reference]]]
    (is (contains? (codes x) code))
    (is (nil? (:execution (validate x))))))

(deftest unsupported-implementations-fail-closed-without-leaking
  (doseq [registry [{} {"test" (constantly false)} {"test" (constantly nil)}
                    {"test" (constantly {:findings "not-a-vector"})}]]
    (let [result (execution/validate-execution vocabulary sample registry)]
      (is (nil? (:execution result)))
      (is (seq (:findings result)))
      (is (every? #(= :execution/implementation-unsupported (:code %)) (:findings result)))))
  (let [result (execution/validate-execution
                vocabulary sample {"test" (fn [_] (throw (ex-info "credential-value" {:secret "credential-value"})))})]
    (is (nil? (:execution result)))
    (is (every? #(= :execution/checker-crashed (:code %)) (:findings result)))
    (is (= [:bindings "create-test" :binding/implementation] (:path (first (:findings result)))))
    (is (.contains (:message (first (:findings result))) "ExceptionInfo"))
    (is (not (.contains (pr-str result) "credential-value")))))

(deftest connector-findings-are-surfaced-under-the-binding
  (let [checker (fn [binding]
                  (if (= :lookup (get-in binding [:binding/implementation :fixture]))
                    {:findings [{:code :connector.source/placeholder-mismatch :severity :error
                                 :path [:binding/implementation :query]
                                 :message "Placeholder :id is not bound."
                                 :value "secret-value"}]}
                    {:findings []}))
        result (execution/validate-execution vocabulary sample {"test" checker})
        f (first (:findings result))]
    (is (nil? (:execution result)))
    (is (= 1 (count (:findings result))))
    (is (= {:code :connector.source/placeholder-mismatch :severity :error
            :path [:bindings "lookup-test" :binding/implementation :query]
            :message "Placeholder :id is not bound."}
           f))
    (is (not (.contains (pr-str result) "secret-value"))))
  (is (= sample (:execution (execution/validate-execution vocabulary sample {"test" (constantly {:findings []})})))))

(deftest parameter-map-keys-must-equal-filter-keys
  (let [with-filter (assoc-in sample [:resolvers "lookup" :filters]
                              {:title {:attribute :case/title :operator :eq}})]
    (is (empty? (:findings (validate (assoc-in with-filter [:bindings "lookup-test" :binding/parameter-map] {:title "t"})))))
    (doseq [pm [{:other "t"} {:title "t" :extra "e"} {}]]
      (let [result (validate (assoc-in with-filter [:bindings "lookup-test" :binding/parameter-map] pm))]
        (is (some #(= [:execution/parameter-map [:bindings "lookup-test" :binding/parameter-map]]
                      [(:code %) (:path %)])
                  (:findings result)))))))

(deftest binding-effect-must-agree-with-definition-and-source
  (let [result (validate (assoc-in sample [:bindings "create-test" :binding/effect] :read))]
    (is (some #(= [:execution/effect-mismatch [:bindings "create-test" :binding/effect]]
                  [(:code %) (:path %)]) (:findings result))))
  (let [result (validate (assoc-in sample [:sources "support" :source/effects] #{:read}))]
    (is (some #(= [:execution/source-effect [:bindings "create-test" :binding/effect]]
                  [(:code %) (:path %)]) (:findings result)))
    (is (not-any? #(= :execution/source-effect (:code %))
                  (filter #(= "lookup-test" (second (:path %))) (:findings result))))))

(deftest source-is-not-a-deployment-config
  (is (contains? (codes (assoc-in sample [:sources "support" :credential] "private")) :execution/schema))
  (is (contains? (codes (assoc-in sample [:sources "support" :endpoint] "private")) :execution/schema)))

(deftest eql-parameter-binding-is-distinct-from-entity-input
  (let [with-filter (-> sample
                        (assoc-in [:resolvers "lookup" :filters]
                                  {:title {:attribute :case/title :operator :eq :required? true}})
                        (assoc-in [:bindings "lookup-test" :binding/parameter-map]
                                  {:title "query-title"}))]
    (is (empty? (:findings (validate with-filter))))
    (is (= {:case/id "id"}
           (get-in with-filter [:bindings "lookup-test" :binding/input-map])))
    (is (contains? (codes (assoc-in with-filter
                                    [:bindings "lookup-test" :binding/parameter-map]
                                    {:wrong "query-title"}))
                   :execution/parameter-map))))

(def source-collection
  {:ordering :source-defined :paging :none :completeness :not-guaranteed
   :bounds {:max-items 100 :max-response-bytes 65536 :timeout-ms 5000}})

(def collection-sample
  (-> sample
      (assoc-in [:resolvers "lookup" :result/cardinality] :many)
      (assoc-in [:resolvers "lookup" :output] [{:case/notes [:note/id]}])
      (assoc-in [:resolvers "lookup" :collection] source-collection)
      (update-in [:bindings "lookup-test" :binding/output-map] dissoc :case/title)))

(deftest bounded-source-collection-is-an-explicit-alternative
  (is (empty? (:findings (validate collection-sample))))
  (doseq [bad [(assoc-in collection-sample [:resolvers "lookup" :sort] {:allowed [:note/id] :default [:note/id :asc]})
               (assoc-in collection-sample [:resolvers "lookup" :pagination] {:default 5 :max 10})
               (assoc-in collection-sample [:resolvers "lookup" :collection :limit] {:default 5 :max 101})]]
    (is (contains? (codes bad) :execution/collection-mode)))
  (doseq [[path value] [[[:paging] :offset] [[:completeness] :complete]
                       [[:bounds :max-items] 0] [[:bounds :timeout-ms] 30001]]]
    (is (contains? (codes (assoc-in collection-sample (into [:resolvers "lookup" :collection] path) value)) :execution/schema))))

;; ---------------------------------------------------------------------------
;; doc 107 §107.3 (owner 2026-09-18): the Source slot's semantic policy
;; ---------------------------------------------------------------------------

(deftest a-slot-policy-is-closed-and-only-names-transient-categories
  (is (empty? (codes (assoc-in sample [:sources "support" :source/policy]
                               {:max_retries 2 :retry_on #{:rate_limited :unavailable}}))))
  ;; The capacity half belongs to the deployment, not to the artifact -- a
  ;; graph carrying `:timeout_ms` would be a modeller setting operations policy
  ;; for every deployment that ever installs it.
  (doseq [k [:timeout_ms :bulkhead :circuit_breaker :backoff_ms :jitter :nonsense]]
    (is (contains? (codes (assoc-in sample [:sources "support" :source/policy] {k 1})) :execution/schema)
        (str k)))
  (doseq [cat [:source :mapping :timeout :unauthorized :not-found :input-rejected
               :circuit_open :bulkhead_rejected :cancelled]]
    (is (contains? (codes (assoc-in sample [:sources "support" :source/policy] {:retry_on #{cat}}))
                   :execution/schema)
        (str cat " is not transient: D6.4 does not let a retry be declared on it"))))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §2 (doc 109 §109.8 items 1-2, owner 2026-09-18): `:input` forms
;; ---------------------------------------------------------------------------

(defn- validate-v2 [x]
  (execution/validate-execution vocabulary x checkers {:contract version/semantic-v2}))
(defn- codes-v2 [x] (set (map :code (:findings (validate-v2 x)))))

(def ^:private optional-title {:attribute :case/title :optional? true})

(def ^:private v2-sample
  (-> sample
      (assoc-in [:resolvers "lookup" :input] [:case/id :case/title {:case/notes [:note/id]}])
      (assoc-in [:bindings "lookup-test" :binding/input-map]
                {:case/id "id" :case/title "title" :case/notes "notes"})))

(deftest v2-accepts-optional-and-nested-inputs-and-keeps-the-roots-in-the-input-map
  (is (= v2-sample (:execution (validate-v2 v2-sample))) (pr-str (:findings (validate-v2 v2-sample))))
  (let [input (get-in v2-sample [:resolvers "lookup" :input])]
    (is (= [:case/id :case/title :case/notes] (execution/input-roots input)))
    (is (= #{} (execution/input-optional input)))
    (is (= {:case/notes [:note/id]} (execution/input-nested input)))
    (is (= [:case/id :case/title :case/notes :note/id] (vec (execution/input-attrs input)))))
  (testing "a 1.x flat vector answers the same questions unchanged"
    (is (= [:case/id] (execution/input-roots [:case/id])))
    (is (= #{} (execution/input-optional [:case/id])))
    (is (= {} (execution/input-nested [:case/id])))
    (is (= [:case/id] (vec (execution/input-attrs [:case/id]))))))

(deftest v2-input-forms-are-closed
  (testing ":optional? false is refused -- write ① or ②, not a third spelling"
    (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input 1] {:attribute :case/title :optional? false}))
                   :execution/schema)))
  (testing "a subtree may not contain an optional element"
    (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input 2] {:case/notes [optional-title]}))
                   :execution/schema)))
  (testing "an optional map with extra keys is refused"
    (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input 1] {:attribute :case/title :optional? true :x 1}))
                   :execution/schema)))
  (testing "an empty subtree is refused"
    (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input 2] {:case/notes []}))
                   :execution/schema))))

(deftest v2-roots-must-be-distinct-across-forms
  (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input] [:case/id {:attribute :case/id :optional? true}]))
                 :execution/duplicate-input)))

(deftest v2-nested-roots-must-be-refs-owned-by-their-target
  (testing "a nested key that is not a :ref attribute"
    (let [result (validate-v2 (assoc-in v2-sample [:resolvers "lookup" :input 2] {:case/title [:note/id]}))]
      (is (some #(= [:execution/nested-input-root [[:resolver "lookup"] :input 2]] [(:code %) (:path %)])
                (:findings result)) (pr-str (:findings result)))))
  (testing "a subtree attribute outside the ref's target Entity"
    (let [result (validate-v2 (assoc-in v2-sample [:resolvers "lookup" :input 2] {:case/notes [:case/title]}))]
      (is (some #(= [:execution/input-owner [[:resolver "lookup"] :input 2 :case/notes 0]] [(:code %) (:path %)])
                (:findings result)) (pr-str (:findings result)))))
  (testing "a subtree attribute unknown to the vocabulary"
    (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input 2] {:case/notes [:note/missing]}))
                   :execution/attribute-reference))))

(deftest v2-input-map-keys-are-exactly-the-roots
  (testing "an optional root is still a physical binding"
    (is (contains? (codes-v2 (update-in v2-sample [:bindings "lookup-test" :binding/input-map] dissoc :case/title))
                   :execution/input-map)))
  (testing "a subtree attribute is not a binding"
    (is (contains? (codes-v2 (assoc-in v2-sample [:bindings "lookup-test" :binding/input-map :note/id] "note_id"))
                   :execution/input-map))))

(deftest v2-forms-are-read-without-a-descriptor-and-refused-under-a-retired-one
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): the
  ;; 1.x schema is retired; a bare fragment is 2.0, an old descriptor is refused.
  (is (empty? (:findings (validate v2-sample))) (pr-str (:findings (validate v2-sample))))
  (is (= [version/version-retired]
         (:findings (execution/validate-execution vocabulary v2-sample checkers
                                                 {:contract {:contract/family "atlas-semantic" :contract/version "1.3.0"}})))))

(deftest the-checker-learns-which-roots-are-optional-or-nested
  (let [seen (atom [])
        spy (fn [b] (swap! seen conj b) {:findings []})]
    (testing "a 2.0 definition's answer rides on the Binding"
      (execution/validate-execution vocabulary v2-sample {"test" spy} {:contract version/semantic-v2})
      (let [lookup (some #(when (= [:resolver "lookup"] (:binding/for %)) %) @seen)
            create (some #(when (= [:operation "create"] (:binding/for %)) %) @seen)]
        (is (nil? (:binding/input-optional lookup)))
        (is (= {:case/notes [:note/id]} (:binding/input-nested lookup)))
        (is (not (contains? create :binding/input-optional)))
        (is (not (contains? create :binding/input-nested)))))
    (testing "a 1.x Binding reaches the checker byte-for-byte"
      (reset! seen [])
      (execution/validate-execution vocabulary sample {"test" spy})
      (is (= (set (vals (:bindings sample))) (set @seen))))))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §3 (doc 109 §109.8 items 5 and 7, owner 2026-09-18): `:params`
;; and `:batch?`
;; ---------------------------------------------------------------------------

(def ^:private keyword-param {:type :string :required? true :description "Full-text term"})
(def ^:private scope-param {:type :string :allowed ["standalone" "cluster"] :default "standalone"})

(def ^:private v2-params
  "1.x-shaped inputs, so only §3 is under test here."
  (-> sample
      (assoc-in [:resolvers "lookup" :params] {:keyword keyword-param :scope scope-param})
      (assoc-in [:bindings "lookup-test" :binding/parameter-map] {:keyword "q" :scope "scope"})))

(defn- finding-at? [result code path]
  (some #(= [code path] [(:code %) (:path %)]) (:findings result)))

(deftest v2-accepts-free-params-alongside-filters
  (is (= v2-params (:execution (validate-v2 v2-params))) (pr-str (:findings (validate-v2 v2-params))))
  (testing "filters and params coexist when their key spaces are disjoint"
    (let [both (-> v2-params
                   (assoc-in [:resolvers "lookup" :filters] {:title {:attribute :case/title :operator :eq}})
                   (assoc-in [:bindings "lookup-test" :binding/parameter-map] {:keyword "q" :scope "scope" :title "t"}))]
      (is (empty? (:findings (validate-v2 both))) (pr-str (:findings (validate-v2 both))))))
  (testing ":params and :batch? are read without a descriptor, exactly as under 2.0"
    (is (= (:findings (validate v2-params)) (:findings (validate-v2 v2-params))))
    (is (= (:findings (validate (assoc-in sample [:resolvers "lookup" :batch?] true)))
           (:findings (validate-v2 (assoc-in sample [:resolvers "lookup" :batch?] true)))))))

(deftest v2-param-declarations-are-closed
  (testing ":type is required and excludes :ref and :json"
    (is (contains? (codes-v2 (assoc-in v2-params [:resolvers "lookup" :params :keyword] {:required? true})) :execution/schema))
    (doseq [t [:ref :json]]
      (is (contains? (codes-v2 (assoc-in v2-params [:resolvers "lookup" :params :keyword :type] t)) :execution/schema))))
  (testing ":required? false is not a spelling"
    (is (contains? (codes-v2 (assoc-in v2-params [:resolvers "lookup" :params :keyword :required?] false)) :execution/schema)))
  (testing "an empty :allowed says nothing"
    (is (contains? (codes-v2 (assoc-in v2-params [:resolvers "lookup" :params :scope :allowed] [])) :execution/schema)))
  (testing "unknown keys are refused"
    (is (contains? (codes-v2 (assoc-in v2-params [:resolvers "lookup" :params :keyword :field] "q")) :execution/schema))))

(deftest v2-param-names-are-neither-reserved-nor-filters
  (let [r (validate-v2 (assoc-in v2-params [:resolvers "lookup" :params :limit] {:type :integer}))]
    (is (finding-at? r :execution/reserved-parameter [[:resolver "lookup"] :params :limit]) (pr-str (:findings r))))
  (let [r (validate-v2 (assoc-in v2-params [:resolvers "lookup" :filters]
                                 {:keyword {:attribute :case/title :operator :like}}))]
    (is (finding-at? r :execution/parameter-collision [[:resolver "lookup"] :params :keyword]) (pr-str (:findings r)))))

(deftest v2-param-values-match-the-declared-type
  (let [at (fn [k] [[:resolver "lookup"] :params :scope k])]
    (testing ":allowed elements"
      (is (finding-at? (validate-v2 (assoc-in v2-params [:resolvers "lookup" :params :scope :allowed] ["ok" 2]))
                       :execution/parameter-value (at :allowed))))
    (testing ":default"
      (is (finding-at? (validate-v2 (assoc-in v2-params [:resolvers "lookup" :params :scope :default] 1))
                       :execution/parameter-value (at :default))))
    (testing ":default and :required? are mutually exclusive, reported at :default"
      (is (finding-at? (validate-v2 (assoc-in v2-params [:resolvers "lookup" :params :scope :required?] true))
                       :execution/parameter-value (at :default))))
    (testing "dates and datetimes are ISO-8601 strings"
      ;; Contract 2.0 §3.1 amendment (owner 2026-09-18): `:allowed` is for
      ;; enumerable types only, so a date parameter carries a `:default` alone
      ;; -- the `:allowed` half of this case moved to semantic-output-v2-test.
      (let [ok (assoc-in v2-params [:resolvers "lookup" :params :scope]
                         {:type :date :default "2026-09-18"})
            bad (assoc-in v2-params [:resolvers "lookup" :params :scope] {:type :datetime :default "yesterday"})]
        (is (empty? (:findings (validate-v2 ok))) (pr-str (:findings (validate-v2 ok))))
        (is (finding-at? (validate-v2 bad) :execution/parameter-value (at :default)))))
    (testing "the predicate is public for the compiler and connectors"
      (is (execution/param-value-of-type? :decimal 1.5))
      (is (execution/param-value-of-type? :integer 3))
      (is (not (execution/param-value-of-type? :integer 1.5)))
      (is (execution/param-value-of-type? :boolean false))
      (is (execution/param-value-of-type? :datetime "2026-09-18T10:00:00Z"))
      (is (not (execution/param-value-of-type? :string :kw))))))

(deftest v2-parameter-map-is-required-and-names-the-union
  (let [at [:bindings "lookup-test" :binding/parameter-map]]
    (testing "a definition with :params needs a parameter-map"
      (is (finding-at? (validate-v2 (update-in v2-params [:bindings "lookup-test"] dissoc :binding/parameter-map))
                       :execution/parameter-map at)))
    (testing "keys == filters ∪ params"
      (is (finding-at? (validate-v2 (assoc-in v2-params [:resolvers "lookup" :filters]
                                              {:title {:attribute :case/title :operator :eq}}))
                       :execution/parameter-map at))
      (is (finding-at? (validate-v2 (assoc-in v2-params [:bindings "lookup-test" :binding/parameter-map] {:keyword "q"}))
                       :execution/parameter-map at)))
    (testing "physical names stay distinct"
      (is (finding-at? (validate-v2 (assoc-in v2-params [:bindings "lookup-test" :binding/parameter-map]
                                              {:keyword "q" :scope "q"}))
                       :execution/parameter-map at)))
    (testing "without :params the map stays optional"
      (is (empty? (:findings (validate-v2 sample)))))))

(def ^:private batch-block {:parent-key :case/id :child-fk "id" :placeholder "ids"})

(deftest v2-batch-is-declared-on-the-definition-and-reconciled-with-the-binding
  (let [with-flag (assoc-in sample [:resolvers "lookup" :batch?] true)
        with-block (assoc-in sample [:bindings "lookup-test" :binding/implementation :batch] batch-block)
        agreed (assoc-in with-flag [:bindings "lookup-test" :binding/implementation :batch] batch-block)]
    (testing "both sides present: clean"
      (is (empty? (:findings (validate-v2 agreed))) (pr-str (:findings (validate-v2 agreed)))))
    (testing "the definition says batch, the selected binding has no block"
      (is (finding-at? (validate-v2 with-flag) :execution/batch-mismatch
                       [:bindings "lookup-test" :binding/implementation :batch])))
    (testing "the binding has a block, the definition says nothing"
      (is (finding-at? (validate-v2 with-block) :execution/batch-mismatch [[:resolver "lookup"] :batch?])))
    (testing "the block's :parent-key must be an input root"
      (let [r (validate-v2 (assoc-in agreed [:bindings "lookup-test" :binding/implementation :batch :parent-key] :note/id))]
        (is (finding-at? r :execution/batch-mismatch
                         [:bindings "lookup-test" :binding/implementation :batch :parent-key]) (pr-str (:findings r))))
      (testing "a compound parent-key is read key by key"
        (let [r (validate-v2 (assoc-in agreed [:bindings "lookup-test" :binding/implementation :batch :parent-key] [:case/id :note/id]))]
          (is (finding-at? r :execution/batch-mismatch
                           [:bindings "lookup-test" :binding/implementation :batch :parent-key])))))
    (testing "an unselected alternative binding is not reconciled"
      (let [alt (assoc-in agreed [:bindings "lookup-alt"]
                          (update (get-in agreed [:bindings "lookup-test"]) :binding/implementation dissoc :batch))]
        (is (empty? (:findings (validate-v2 alt))) (pr-str (:findings (validate-v2 alt))))))
    (testing "a bare fragment (no descriptor) is read as 2.0 and reconciled the same way"
      (is (finding-at? (validate with-block) :execution/batch-mismatch [[:resolver "lookup"] :batch?])))))

(deftest v2-batch-is-a-read-shape
  (let [r (validate-v2 (assoc-in sample [:operations "create" :batch?] true))]
    (is (finding-at? r :execution/batch-shape [[:operation "create"] :batch?]) (pr-str (:findings r))))
  (testing ":batch? false is not a spelling"
    (is (contains? (codes-v2 (assoc-in sample [:resolvers "lookup" :batch?] false)) :execution/schema))))

;; Contract 2.0 §4 (doc 109 §109.8 item 4, owner 2026-09-18): several Resolvers
;; per attribute, `:priority`
;; ---------------------------------------------------------------------------

(def ^:private cached-lookup
  "A second producer of `:case/title` -- the same anchor, a different Binding."
  {:label "Lookup (cache)" :effect :read :result/cardinality :one :input [:case/id]
   :output [:case/title]})

(def ^:private v2-overlap
  (-> sample
      (assoc-in [:resolvers "lookup-cache"] cached-lookup)
      (assoc-in [:bindings "cache-test"] {:binding/for [:resolver "lookup-cache"] :binding/source "support"
                                          :binding/effect :read :binding/implementation {:fixture :lookup}
                                          :binding/input-map {:case/id "id"}
                                          :binding/output-map {:case/title "title"}})
      (assoc-in [:selection [:resolver "lookup-cache"]] "cache-test")))

(deftest v2-produced-keys-are-the-top-level-keys-only
  (is (= #{:case/title :case/notes} (execution/produced-keys [:case/title {:case/notes [:note/id]}])))
  (is (= [[["lookup" "lookup-cache"] #{:case/title}]]
         (execution/overlapping-resolvers (:resolvers v2-overlap))))
  (testing "a subtree leaf is the child's attribute, not a second production"
    (is (empty? (execution/overlapping-resolvers
                 {"a" {:output [{:case/notes [:note/id]}]} "b" {:output [:note/id]}})))))

(deftest v2-overlap-with-distinct-priorities-is-accepted
  (let [ranked (assoc-in v2-overlap [:resolvers "lookup-cache" :priority] 10)]
    (is (empty? (:findings (validate-v2 ranked))) (pr-str (:findings (validate-v2 ranked)))))
  (testing "the other side may rank itself explicitly, including at 0"
    (let [ranked (-> v2-overlap
                     (assoc-in [:resolvers "lookup-cache" :priority] 3)
                     (assoc-in [:resolvers "lookup" :priority] 0))]
      (is (empty? (:findings (validate-v2 ranked)))))))

(deftest v2-overlap-with-equal-priorities-is-a-tie-on-both-sides
  (testing "both default 0"
    (let [r (validate-v2 v2-overlap)]
      (is (finding-at? r :execution/priority-tie [[:resolver "lookup"] :priority]) (pr-str (:findings r)))
      (is (finding-at? r :execution/priority-tie [[:resolver "lookup-cache"] :priority]))))
  (testing "both written 5"
    (let [r (validate-v2 (-> v2-overlap
                             (assoc-in [:resolvers "lookup-cache" :priority] 5)
                             (assoc-in [:resolvers "lookup" :priority] 5)))]
      (is (finding-at? r :execution/priority-tie [[:resolver "lookup"] :priority]))
      (is (finding-at? r :execution/priority-tie [[:resolver "lookup-cache"] :priority])))))

(deftest v2-priority-is-a-non-negative-integer-on-a-read-definition
  (is (contains? (codes-v2 (assoc-in sample [:resolvers "lookup" :priority] -1)) :execution/schema))
  (is (contains? (codes-v2 (assoc-in sample [:resolvers "lookup" :priority] 1.5)) :execution/schema))
  (testing "an Operation's :output is a :returns, so it takes no rank"
    (is (contains? (codes-v2 (assoc-in sample [:operations "create" :priority] 1)) :execution/schema)))
  (testing "a lone resolver may still write it"
    (is (empty? (:findings (validate-v2 (assoc-in sample [:resolvers "lookup" :priority] 7)))))))

(deftest v2-an-operation-returning-a-resolver-s-attribute-is-not-an-overlap
  ;; `create` returns `:case/id`; a read producing `:case/id` next to it is no tie.
  (let [with-id (-> sample
                    (assoc-in [:resolvers "lookup" :output] [:case/id :case/title {:case/notes [:note/id]}])
                    (assoc-in [:bindings "lookup-test" :binding/output-map :case/id] "id"))]
    (is (empty? (:findings (validate-v2 with-id))) (pr-str (:findings (validate-v2 with-id))))))

(deftest v2-priority-and-overlap-are-read-the-same-with-or-without-a-descriptor
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19): the
  ;; 1.x schema is retired, so a bare fragment is read as 2.0.
  (is (empty? (:findings (validate (assoc-in sample [:resolvers "lookup" :priority] 1)))))
  (testing "two producers of one attribute at equal rank are a tie, descriptor or not"
    (is (contains? (codes v2-overlap) :execution/priority-tie) (pr-str (:findings (validate v2-overlap)))))
  (testing "an explicit retired descriptor is refused, not read as 2.0"
    (is (= [version/version-retired]
           (:findings (execution/validate-execution vocabulary sample checkers
                                                   {:contract {:contract/family "atlas-semantic" :contract/version "1.4.0"}}))))))

(deftest optional-input-is-refused-but-optional-parameters-remain-supported
  (is (contains? (codes-v2 (assoc-in v2-sample [:resolvers "lookup" :input 1] optional-title))
                 :execution/optional-input-retired))
  (let [x (-> sample
              (assoc-in [:resolvers "lookup" :params] {:search {:type :string :description "Optional search control"}})
              (assoc-in [:bindings "lookup-test" :binding/parameter-map] {:search "search"}))]
    (is (empty? (:findings (validate-v2 x))))))

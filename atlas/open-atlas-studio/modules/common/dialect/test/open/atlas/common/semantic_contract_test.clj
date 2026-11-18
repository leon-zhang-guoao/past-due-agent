(ns open.atlas.common.semantic-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.contract :as version]
            [open.atlas.common.semantic-contract :as contract]))

(def ^:private retired-1-4 {:contract/family "atlas-semantic" :contract/version "1.4.0"})
(def ^:private retired-1-0 {:contract/family "atlas-semantic" :contract/version "1.0.0"})
(def ^:private retired-1-1 {:contract/family "atlas-semantic" :contract/version "1.1.0"})
(def ^:private retired-1-2 {:contract/family "atlas-semantic" :contract/version "1.2.0"})
(def ^:private retired-1-3 {:contract/family "atlas-semantic" :contract/version "1.3.0"})

(defn- retired-finding? [result]
  (= [version/version-retired] (:findings result)))

(def document
  {:contract version/semantic-v2
   :workspace {:workspace/id "support-dev"}
   :graph {:graph/id "support" :graph/label "Support" :graph/exposure :restricted
           :graph/entities ["support.case"] :graph/resolvers ["lookup"] :graph/operations [] :graph/dependencies []
           :graph/terms []}
   :vocabulary {:entities {"support.case" {:entity/label "Case" :entity/identity [:support.case/id]
                                           :entity/attributes [:support.case/id :support.case/title]}}
                :attributes {:support.case/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                             :support.case/title {:attribute/label "Title" :attribute/type :string :attribute/nullable? false}}}
   :execution {:resolvers {"lookup" {:label "Lookup" :effect :read :result/cardinality :one
                                     :input [:support.case/id] :output [:support.case/title]}}
               :operations {} :sources {"db" {:source/kind "sql" :source/effects #{:read}}}
               :bindings {"lookup-sql" {:binding/for [:resolver "lookup"] :binding/source "db" :binding/effect :read
                                        :binding/implementation {:query "SELECT title FROM cases WHERE id = :id"}
                                        :binding/input-map {:support.case/id "id"} :binding/output-map {:support.case/title "title"}}}
               :selection {[:resolver "lookup"] "lookup-sql"}}
   :ownership {:entities {"support.case" "support"} :resolvers {"lookup" "support"} :operations {} :terms {}}
   :publication {:release/version "1.0.0" :source/revision 1
                 :required-capabilities #{:semantic/core-v1 :connector/sql :graph/single-owner-v1
                                          :deployment/source-slot-v1 :artifact/embedded-closure-v1}}})

;; A test-only structural checker. Connector execution/SQL validation has its own integration suite.
(def checkers {"sql" #(string? (get-in % [:binding/implementation :query]))})
(defn read-doc [text] (contract/read-document text checkers))

(deftest canonical-document-roundtrip-and-stable-content-hash
  (let [first-text (:text (contract/write-document document checkers))
        read-result (read-doc first-text)]
    (is (= document (:document read-result)))
    (is (= first-text (:text (contract/write-document (:document read-result) checkers))))
    (is (= first-text (:text (contract/write-document (into (sorted-map) document) checkers))))
    (is (re-matches #"sha256:[0-9a-f]{64}" (contract/content-hash first-text)))))

(deftest missing-capabilities-and-ownership-fail-closed
  (doseq [d [(assoc-in document [:publication :required-capabilities] #{})
             (assoc-in document [:graph :graph/resolvers] [])
             (assoc-in document [:ownership :entities "support.case"] "unknown")
             (assoc-in document [:publication :release/version] "latest")
             (assoc-in document [:contract :contract/version] "1.0.1")]]
    (is (seq (:findings (contract/validate-document d checkers))))
    (is (nil? (:text (contract/write-document d checkers))))))

(deftest malformed-input-does-not-downgrade-or-leak
  (doseq [text ["{} {}" "#secret \"private-value\"" "{:contract nil}" "{:contract/family \"atlas-semantic\"}" ""]]
    (is (seq (:findings (read-doc text))))
    (is (not (.contains (pr-str (read-doc text)) "private-value"))))
  (testing "a descriptor-less document is the retired legacy form; it is refused, not read by the dialect reader"
    (is (retired-finding? (read-doc "{}")))
    (is (retired-finding? (read-doc "{:unknown true}")))
    (is (nil? (:model (read-doc "{}"))))))

(deftest robust-input-handling
  (is (= :contract/input-limit (-> (read-doc (str (repeat 4200000 "x"))) :findings first :code)))
  (is (= :contract/unreadable (-> (read-doc 123) :findings first :code)))
  (is (= :contract/descriptor-invalid (-> (read-doc "\ufeff{:contract nil}") :findings first :code))))

(deftest writer-does-not-produce-a-document-beyond-reader-budget
  (let [large (assoc-in document [:graph :graph/label] (apply str (repeat (* 4 1024 1024) "x")))
        result (contract/write-document large checkers)]
    (is (= :contract/input-limit (-> result :findings first :code)))
    (is (nil? (:text result)))
    (is (seq (:findings (contract/verify-closure large {} checkers))))))

(deftest dependency-locks-use-computed-hash-and-exact-release
  ;; 2.0 §1 closed-world ownership: the root names the child by exact locator
  ;; and digest and holds none of its assets.
  (let [child (-> document
                  (assoc-in [:graph :graph/id] "provider")
                  (assoc-in [:ownership :entities "support.case"] "provider")
                  (assoc-in [:ownership :resolvers "lookup"] "provider"))
        hash (contract/content-hash (:text (contract/write-document child checkers)))
        root (-> document
                 (assoc-in [:graph :graph/entities] [])
                 (assoc-in [:graph :graph/resolvers] [])
                 (assoc :vocabulary {:entities {} :attributes {}})
                 (assoc :execution {:resolvers {} :operations {} :sources {} :bindings {} :selection {}})
                 (assoc :ownership {:entities {} :resolvers {} :operations {} :terms {}})
                 (assoc-in [:graph :graph/dependencies] [{:graph/id "provider" :release/version "1.0.0" :content/hash hash}])
                 (update-in [:publication :required-capabilities] conj :graph/dependencies-v1))]
    (is (empty? (:findings (contract/verify-closure root {["provider" "1.0.0"] child} checkers)))
        (pr-str (:findings (contract/verify-closure root {["provider" "1.0.0"] child} checkers))))
    (is (seq (:findings (contract/verify-closure root {} checkers))))
    (is (seq (:findings (contract/verify-closure root {["provider" "1.0.0"] (assoc-in child [:publication :source/revision] 2)} checkers))))
    (testing "a retired child snapshot is refused, not read"
      (is (some #(= :contract/version-retired (:code %))
                (:findings (contract/verify-closure root {["provider" "1.0.0"] (assoc child :contract retired-1-4)} checkers)))))
    (is (seq (:findings (contract/check-support root #{:semantic/core-v1 :connector/sql}))))))

(deftest metadata-fields-are-read-under-2-0-and-refused-under-a-retired-descriptor
  (let [d (-> document
              (assoc-in [:vocabulary :entities "support.case" :entity/notes] "Preserve author notes")
              (assoc-in [:vocabulary :terms "case-kind"]
                        {:term/label "Case kind" :term/description "Original explanation"
                         :term/format :code})
              (assoc-in [:graph :graph/terms] ["case-kind"])
              (assoc-in [:ownership :terms] {"case-kind" "support"})
              (assoc-in [:vocabulary :attributes :support.case/title-copy]
                        {:attribute/label "Copy" :attribute/type :string :attribute/nullable? false})
              (update-in [:vocabulary :entities "support.case" :entity/attributes] conj :support.case/title-copy)
              (assoc-in [:vocabulary :aliases]
                        [{:alias/from :support.case/title :alias/to :support.case/title-copy
                          :alias/direction :one-way :alias/description "Original relationship explanation"}])
              (update-in [:publication :required-capabilities] conj :semantic/alias-v1))
        encoded (contract/write-document d checkers)]
    (is (empty? (:findings encoded)) (pr-str (:findings encoded)))
    (is (= d (:document (read-doc (:text encoded)))))
    (is (retired-finding? (contract/validate-document (assoc d :contract retired-1-1) checkers)))
    (is (empty? (:findings (contract/validate-document document checkers))))
    (is (not= (contract/content-hash (:text encoded))
              (contract/content-hash (:text (contract/write-document
                                            (assoc-in d [:vocabulary :aliases 0 :alias/description] "Changed") checkers)))))))

(deftest curated-definition-is-authoring-metadata
  (let [d (assoc-in document [:execution :resolvers "lookup" :curated?] true)
        encoded (contract/write-document d checkers)]
    (is (empty? (:findings encoded)))
    (is (= d (:document (read-doc (:text encoded)))))
    (is (retired-finding? (contract/validate-document (assoc d :contract retired-1-1) checkers)))))

(deftest conditional-references-require-an-explicit-capability
  (let [d (-> document
              (update-in [:vocabulary :entities "support.case" :entity/attributes] conj :support.case/foreign-id)
              (assoc-in [:vocabulary :attributes :support.case/foreign-id]
                        {:attribute/label "Foreign ID" :attribute/type :integer :attribute/nullable? true})
              (assoc-in [:vocabulary :references]
                        [{:reference/from :support.case/foreign-id :reference/to :support.case/id
                          :reference/direction :two-way :reference/on-null :omit}])
              (update-in [:publication :required-capabilities] conj :semantic/conditional-reference-v1))
        encoded (contract/write-document d checkers)]
    (is (empty? (:findings encoded)))
    (is (= d (:document (read-doc (:text encoded)))))
    (doseq [old [retired-1-0 retired-1-1]]
      (is (retired-finding? (contract/validate-document (assoc d :contract old) checkers))))
    (doseq [invalid [(update-in d [:publication :required-capabilities] disj :semantic/conditional-reference-v1)
                     (assoc-in d [:vocabulary :references 0 :reference/on-null] :copy)
                     (assoc-in d [:vocabulary :references 0 :reference/to] :support.case/title)
                     (assoc-in d [:vocabulary :references 0 :reference/from] :support.case/missing)
                     (assoc-in d [:vocabulary :attributes :support.case/foreign-id :attribute/type] :string)]]
      (is (seq (:findings (contract/validate-document invalid checkers)))))))

(deftest source-collections-require-an-explicit-capability
  (let [candidate (-> document
                      (update-in [:vocabulary :entities "support.case" :entity/attributes] conj :support.case/all)
                      (assoc-in [:vocabulary :attributes :support.case/all]
                                {:attribute/label "Cases" :attribute/type :ref :attribute/nullable? false
                                 :attribute/target "support.case" :attribute/cardinality :many})
                      (assoc-in [:execution :resolvers "lookup" :input] [])
                      (assoc-in [:execution :resolvers "lookup" :result/cardinality] :many)
                      (assoc-in [:execution :resolvers "lookup" :output] [{:support.case/all [:support.case/id :support.case/title]}])
                      (assoc-in [:execution :resolvers "lookup" :collection]
                                {:ordering :source-defined :paging :none :completeness :not-guaranteed
                                 :bounds {:max-items 100 :max-response-bytes 65536 :timeout-ms 5000}})
                      (assoc-in [:execution :bindings "lookup-sql" :binding/input-map] {})
                      (assoc-in [:execution :bindings "lookup-sql" :binding/output-map]
                                {:support.case/all "all" :support.case/id "id" :support.case/title "title"}))
        checker {"sql" (constantly true)}
        declared (update-in candidate [:publication :required-capabilities] conj :query/source-collection-v1)]
    (is (some #(= :contract/capability-undeclared (:code %)) (:findings (contract/validate-document candidate checker))))
    (is (empty? (:findings (contract/validate-document declared checker))))
    (doseq [old [retired-1-0 retired-1-1 retired-1-2]]
      (is (retired-finding? (contract/validate-document (assoc declared :contract old) checker))
          "the retired readers refuse by descriptor, before any schema is consulted"))))

(deftest query-examples-are-capability-bound
  (let [example {:example/id "case-by-id"
                 :example/title "Case by ID"
                 :example/description "Load one case from its known identity."
                 :example/eql "[:support.case/title]"
                 :example/entity {:support.case/id 7}
                 :example/parameters {}
                 :example/requires #{:connector/sql}
                 :example/expected {:result/cardinality :one
                                    :result/fields [:support.case/title]}}
        latest (assoc-in document [:graph :graph/query-examples] [example])]
    (is (empty? (:findings (contract/validate-document latest checkers))))
    (is (some #(= :contract/query-example-duplicate (:code %))
              (:findings (contract/validate-document
                          (assoc-in latest [:graph :graph/query-examples] [example example]) checkers))))
    (is (some #(= :contract/query-example-capability (:code %))
              (:findings (contract/validate-document
                          (assoc-in latest [:graph :graph/query-examples 0 :example/requires]
                                    #{:connector/nifi}) checkers))))
    (is (retired-finding? (contract/validate-document (assoc latest :contract retired-1-2) checkers)))))

(deftest every-document-is-a-single-owned-graph
  ;; 2.0 §1, inherited from 1.4 and now the only ownership model.
  (let [v14 document]
    (is (empty? (:findings (contract/validate-document v14 checkers))))
    (is (retired-finding? (contract/validate-document (assoc v14 :contract retired-1-4) checkers))
        "the 1.4 reader that introduced this model is itself retired")
    (is (some #(= :contract/ownership (:code %))
              (:findings (contract/validate-document
                          (assoc-in v14 [:ownership :entities "support.case"] "dependency") checkers))))
    (let [second-source (-> v14
                            (assoc-in [:execution :sources "other"]
                                      {:source/kind "sql" :source/effects #{:read}}))]
      (is (empty? (:findings (contract/validate-document second-source checkers)))
          "one Graph may use multiple external Source Slots"))
    (is (empty? (:findings
                 (contract/validate-document
                  (-> v14
                      (assoc-in [:execution :sources "derived"]
                                {:source/kind "derived" :source/effects #{:read}})
                      (update-in [:publication :required-capabilities] conj :connector/derived))
                  (assoc checkers "derived" (constantly true)))))
        "a pure computation source is not an external deployment slot")))


(deftest isolated-batches-require-an-explicit-runtime-capability
  ;; 2.0 §3.2 reconciles the block with the definition, so the definition says
  ;; `:batch? true` and the graph names `:execution/batch-v2` as well.
  (let [candidate (-> document
                      (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :batch]
                                {:isolate-statuses #{400}})
                      (assoc-in [:execution :resolvers "lookup" :batch?] true))
        declared (update-in candidate [:publication :required-capabilities]
                            conj :query/batch-isolation-v1 :execution/batch-v2)]
    (is (contains? (contract/required-capabilities candidate) :query/batch-isolation-v1))
    (is (seq (:findings (contract/validate-document candidate checkers))))
    (is (empty? (:findings (contract/validate-document declared checkers))))
    (is (seq (:findings (contract/check-support declared #{:semantic/core-v1 :connector/sql}))))))

(deftest a-declared-policy-requires-an-explicit-runtime-capability
  ;; doc 107 §107.9 item 7 (owner 2026-09-18). Before the closed key face a
  ;; `:resilience` block was accepted and dropped; shipping such a graph to an
  ;; old runtime would silently remove the retry the modeller declared, which
  ;; is precisely the failure §107.1 documents. So the graph says what it needs
  ;; and an old runtime refuses it rather than degrading.
  (doseq [candidate [(assoc-in document [:execution :sources "db" :source/policy]
                               {:max_retries 2 :retry_on #{:connection}})
                     (assoc-in document [:execution :bindings "lookup-sql"
                                         :binding/implementation :policy]
                               {:max_retries 1})]]
    (let [declared (update-in candidate [:publication :required-capabilities]
                              conj :source/policy-v1)]
      (is (contains? (contract/required-capabilities candidate) :source/policy-v1))
      (is (seq (:findings (contract/validate-document candidate checkers)))
          "undeclared: the graph must say what it needs")
      (is (empty? (:findings (contract/validate-document declared checkers))))
      (is (seq (:findings (contract/check-support declared #{:semantic/core-v1 :connector/sql})))
          "a runtime without the capability refuses the graph, it does not degrade it")))
  (testing "a bare :timeout_ms does not derive it -- every runtime has honoured that one"
    (is (not (contains? (contract/required-capabilities
                         (assoc-in document [:execution :sources "db" :resilience]
                                   {:timeout_ms 5000}))
                        :source/policy-v1)))
    (is (contains? (contract/required-capabilities
                    (assoc-in document [:execution :sources "db" :resilience]
                              {:timeout_ms 5000 :max_retries 2}))
                   :source/policy-v1)))
  (is (not (contains? (contract/required-capabilities document) :source/policy-v1))))

(deftest a-status-map-requires-its-own-capability-and-implies-batch-isolation
  ;; doc 107 §107.5 / §107.9 item 7 (owner 2026-09-18). A runtime that would
  ;; read those statuses the old way must REFUSE the graph: silently falling
  ;; back to "every non-2xx is a source error" turns a declared missing row
  ;; into an outage, which is the kind of quiet degradation §107.1 is about.
  (let [candidate (assoc-in document [:execution :bindings "lookup-sql"
                                      :binding/implementation :status-map]
                            {400 :not-found})
        declared (update-in candidate [:publication :required-capabilities]
                            conj :connector/api-status-map-v1)
        caps (contract/required-capabilities candidate)]
    (is (contains? caps :connector/api-status-map-v1))
    (is (not (contains? caps :query/batch-isolation-v1))
        "implied, not declared twice: a status-map graph does not also name the old key")
    (is (seq (:findings (contract/validate-document candidate checkers))))
    (is (empty? (:findings (contract/validate-document declared checkers))))
    (is (seq (:findings (contract/check-support declared #{:semantic/core-v1 :connector/sql})))
        "an old runtime refuses it rather than reading the statuses its own way"))

  (testing "a slot-level :policy :status-map derives it too"
    (is (contains? (contract/required-capabilities
                    (assoc-in document [:execution :sources "db" :source/policy :status-map]
                              {418 :source}))
                   :connector/api-status-map-v1)))

  (testing "a graph still written against the superseded key keeps its old capability"
    ;; dialect-v1 §6: a rewrite is not a deletion. `:isolate-statuses` still
    ;; derives `:query/batch-isolation-v1` and nothing more, so an already
    ;; published artifact loads unchanged.
    (let [old (assoc-in document [:execution :bindings "lookup-sql"
                                  :binding/implementation :batch]
                        {:isolate-statuses #{400}})
          caps (contract/required-capabilities old)]
      (is (contains? caps :query/batch-isolation-v1))
      (is (not (contains? caps :connector/api-status-map-v1)))))

  (is (not (contains? (contract/required-capabilities document)
                      :connector/api-status-map-v1))))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §2 (doc 109 §109.8 items 1-2, owner 2026-09-18)
;; ---------------------------------------------------------------------------

(def ^:private v14-caps
  "The capability floor of a document using no 2.0-only form."
  #{:semantic/core-v1 :connector/sql :graph/single-owner-v1
    :deployment/source-slot-v1 :artifact/embedded-closure-v1})

(def ^:private v20
  "The base document with an optional input."
  (-> document
      (assoc-in [:execution :resolvers "lookup" :input]
                [:support.case/id {:attribute :support.case/title :optional? true}])
      (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :query]
                "SELECT title FROM cases WHERE id = :id AND (:title IS NULL OR title = :title)")
      (assoc-in [:execution :bindings "lookup-sql" :binding/input-map]
                {:support.case/id "id" :support.case/title "title"})
      (assoc-in [:publication :required-capabilities] (conj v14-caps :execution/input-v2))))

(deftest version-20-reads-the-new-input-forms-under-its-own-capability
  (is (some #(= :execution/optional-input-retired (:code %)) (:findings (contract/validate-document v20 checkers)))
      (pr-str (:findings (contract/validate-document v20 checkers))))
  (testing "the capability is derived from the input forms, not from the descriptor"
    (is (contains? (contract/required-capabilities v20) :execution/input-v2))
    (is (not (contains? (contract/required-capabilities
                         (assoc-in v20 [:execution :resolvers "lookup" :input] [:support.case/id]))
                        :execution/input-v2))
        "a 2.0 document with 1.x inputs needs no new capability")
    (is (some #(= :contract/capability-undeclared (:code %))
              (:findings (contract/validate-document
                          (update-in v20 [:publication :required-capabilities] disj :execution/input-v2)
                          checkers)))))
  (testing "a runtime without the capability refuses the graph instead of reading it flat"
    (is (= :contract/capability-unsupported
           (-> (contract/check-support v20 v14-caps) :findings first :code))))
  (testing "2.0 carries the single-owner semantics and delivery profile"
    (is (contains? (contract/required-capabilities v20) :graph/single-owner-v1))
    (is (some #(= :execution/optional-input-retired (:code %)) (:findings (contract/validate-deliverable v20 checkers))))
    (is (some #(= :execution/optional-input-retired (:code %)) (:findings (contract/verify-closure v20 {} checkers)))))
  (testing "the same document under a 1.4 descriptor is refused as retired, whatever its forms"
    (is (retired-finding? (contract/validate-document
                           (-> v20 (assoc :contract retired-1-4)
                               (update-in [:publication :required-capabilities] disj :execution/input-v2))
                           checkers)))))

;; Contract 2.0 §3 (doc 109 §109.8 items 5 and 7): `:params` / `:batch?` capabilities
;; ---------------------------------------------------------------------------

(def ^:private v20-flat
  "A 2.0 document with 1.x inputs, so §3 alone decides which capabilities it needs."
  (-> v20
      (assoc-in [:execution :resolvers "lookup" :input] [:support.case/id])
      (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :query] "SELECT title FROM cases WHERE id = :id")
      (assoc-in [:execution :bindings "lookup-sql" :binding/input-map] {:support.case/id "id"})
      (assoc-in [:publication :required-capabilities] v14-caps)))

(deftest version-20-free-params-and-batch-each-name-their-own-capability
  (is (empty? (:findings (contract/validate-document v20-flat checkers)))
      (pr-str (:findings (contract/validate-document v20-flat checkers))))
  (is (not-any? #{:execution/params-v2 :execution/batch-v2} (contract/required-capabilities v20-flat))
      "a 2.0 document using neither key needs neither capability")
  (testing ":params derives :execution/params-v2 and must be declared"
    (let [with-params (-> v20-flat
                          (assoc-in [:execution :resolvers "lookup" :params] {:keyword {:type :string :required? true}})
                          (assoc-in [:execution :bindings "lookup-sql" :binding/parameter-map] {:keyword "keyword"}))
          declared (update-in with-params [:publication :required-capabilities] conj :execution/params-v2)]
      (is (contains? (contract/required-capabilities with-params) :execution/params-v2))
      (is (not (contains? (contract/required-capabilities with-params) :execution/batch-v2)))
      (is (some #(= :contract/capability-undeclared (:code %))
                (:findings (contract/validate-document with-params checkers))))
      (is (empty? (:findings (contract/validate-document declared checkers)))
          (pr-str (:findings (contract/validate-document declared checkers))))))
  (testing ":batch? derives :execution/batch-v2 and must be declared"
    (let [with-batch (-> v20-flat
                         (assoc-in [:execution :resolvers "lookup" :batch?] true)
                         (assoc-in [:execution :bindings "lookup-sql" :binding/implementation :batch]
                                   {:parent-key :support.case/id :placeholder "ids"}))
          declared (update-in with-batch [:publication :required-capabilities] conj :execution/batch-v2)]
      (is (contains? (contract/required-capabilities with-batch) :execution/batch-v2))
      (is (some #(= :contract/capability-undeclared (:code %))
                (:findings (contract/validate-document with-batch checkers))))
      (is (empty? (:findings (contract/validate-document declared checkers)))
          (pr-str (:findings (contract/validate-document declared checkers))))
      (testing "a runtime without it refuses the graph"
        (is (= :contract/capability-unsupported
               (-> (contract/check-support declared (conj v14-caps :execution/params-v2))
                   :findings first :code)))))))
  (testing "round trip"
    (let [text (:text (contract/write-document document checkers))]
      (is (= document (:document (read-doc text))))))

;; Contract 2.0 §4 (doc 109 §109.8 item 4): `:priority` capability
;; ---------------------------------------------------------------------------

(defn- with-cache-lookup
  "A second producer of `:support.case/title` from the same anchor."
  [doc priority]
  (-> doc
      (assoc-in [:execution :resolvers "lookup-cache"]
                (cond-> {:label "Lookup (cache)" :effect :read :result/cardinality :one
                         :input [:support.case/id] :output [:support.case/title]}
                  priority (assoc :priority priority)))
      (assoc-in [:execution :bindings "cache-sql"]
                {:binding/for [:resolver "lookup-cache"] :binding/source "db" :binding/effect :read
                 :binding/implementation {:query "SELECT title FROM cases_cache WHERE id = :id"}
                 :binding/input-map {:support.case/id "id"} :binding/output-map {:support.case/title "title"}})
      (assoc-in [:execution :selection [:resolver "lookup-cache"]] "cache-sql")
      (update-in [:graph :graph/resolvers] conj "lookup-cache")
      (assoc-in [:ownership :resolvers "lookup-cache"] "support")))

(deftest version-20-ranked-producers-name-their-own-capability
  (is (not (contains? (contract/required-capabilities v20-flat) :execution/priority-v2))
      "one producer per attribute needs no rank and no capability")
  (testing "a written :priority derives it and must be declared"
    (let [ranked (with-cache-lookup v20-flat 10)
          declared (update-in ranked [:publication :required-capabilities] conj :execution/priority-v2)]
      (is (contains? (contract/required-capabilities ranked) :execution/priority-v2))
      (is (some #(= :contract/capability-undeclared (:code %))
                (:findings (contract/validate-document ranked checkers))))
      (is (empty? (:findings (contract/validate-document declared checkers)))
          (pr-str (:findings (contract/validate-document declared checkers))))
      (testing "a runtime without it refuses the graph"
        (is (= :contract/capability-unsupported
               (-> (contract/check-support declared v14-caps) :findings first :code))))
      (testing "round trip"
        (is (= declared (:document (read-doc (:text (contract/write-document declared checkers)))))))))
  (testing "overlap on a 2.0 document derives it even at default rank -- and is then a tie"
    (let [unranked (with-cache-lookup v20-flat nil)]
      (is (contains? (contract/required-capabilities unranked) :execution/priority-v2))
      (is (some #(= :execution/priority-tie (:code %))
                (:findings (contract/validate-document
                            (update-in unranked [:publication :required-capabilities] conj :execution/priority-v2)
                            checkers))))))
  (testing "the same overlap under a 1.4 descriptor is refused as retired, not read the old way"
    (let [v14 (-> (with-cache-lookup (assoc document :contract retired-1-4) nil)
                  (assoc-in [:publication :required-capabilities] v14-caps))]
      (is (retired-finding? (contract/validate-document v14 checkers)))))
  (testing "a 1.4 descriptor with the key is refused as retired, before any schema"
    (is (retired-finding? (contract/validate-document
                           (-> v20-flat (assoc :contract retired-1-4)
                               (assoc-in [:execution :resolvers "lookup" :priority] 1))
                           checkers)))))

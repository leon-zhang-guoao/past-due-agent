(ns open.atlas.server.mcp.catalog-v2-test
  "Contract 2.0 on the discovery face (task book «PR3 MCP join 渲染»): what
   `list_attributes` renders for a union join (§6.1), a recursive join
   (§6.4) and a resolver that declares free `:params` (§3.1), `:batch?`
   (§3.2) and `:priority` (§4). Snapshot assertions on the rendered rows —
   an agent reads these keys verbatim, so the exact text is the contract.

   Rendering only: the rows are read off `engine/compile-complete`'s result
   through `catalog/of`, the same path the served tool takes, so what this
   pins is what an agent sees, not a hand-built catalog."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.server.mcp.impl.catalog :as catalog]))

;; A test-only source type on the REAL SPI dispatch (same precedent as the
;; engine's `output-v2-test`), with batch support so a `:batch` block
;; compiles instead of being dropped as `:engine.compile/batch-unsupported`.
(defmethod spi/compile-source "test-mcp" [_ _ _] (fn [_ _] nil))
(defmethod spi/compile-batch-source "test-mcp" [_ _ _] (fn [inputs _] (mapv (constantly nil) inputs)))

(def ^:private sql-source {:type "sql" :query "SELECT 1"})

(def ^:private model-text
  (pr-str
   {:namespaces {"t.ticket" {:label "Ticket"} "t.device" {:label "Device"}
                 "t.person" {:label "Person"} "t.group" {:label "Group"}}
    :attributes {:t.ticket/id {:type :integer :identity? true :format :id :label "ID"}
                 ;; 2.0 §6.5 lowering of `:attribute/targets`.
                 :t.ticket/subject {:targets #{"t.device" "t.person"} :label "Subject"}
                 :t.device/id {:type :integer :identity? true :format :id :label "ID"}
                 :t.device/name {:type :string :format :text :label "Name"}
                 :t.person/id {:type :integer :identity? true :format :id :label "ID"}
                 :t.person/email {:type :string :format :text :label "Email"}
                 :t.person/tier {:type :string :format :code :label "Tier"}
                 :t.group/id {:type :string :identity? true :format :id :label "ID"}
                 :t.group/name {:type :string :format :text :label "Name"}
                 :t.group/children {:cardinality :one-to-many :target "t.group" :label "Children"}}
    :sources {"src" {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}}
    :resolvers [{:id "ticket_by_id" :input [:t.ticket/id]
                 :output [:t.ticket/id {:t.ticket/subject {:t.device/id [:t.device/id :t.device/name]
                                                           :t.person/id [:t.person/id :t.person/email]}}]
                 :result_type "single"
                 :source {:type "static" :match-on :t.ticket/id :data []}}
                {:id "group_by_id" :input [:t.group/id] :output [:t.group/id :t.group/name]
                 :result_type "single" :source sql-source :source-ref "src"}
                ;; The recursion node sits on the resolver that answers the
                ;; direct children (2.0 §6.3); depth ceiling 3.
                {:id "group_children" :input [:t.group/id]
                 :output [{:t.group/children [:t.group/id :t.group/name {:t.group/children 3}]}]
                 :result_type "list" :limit {:default 50 :max 50}
                 :source sql-source :source-ref "src"}
                ;; Two producers of `:t.person/email` (§4.1): the batched one
                ;; outranks the plain one, so its declarations are the ones
                ;; rendered. Its `:eql-params` are the §3.1 lowering of
                ;; `:params` — typed, no `:attribute`, no `:op`.
                {:id "person_by_cache" :input [:t.person/id] :output [:t.person/email]
                 :result_type "single" :priority 10
                 :eql-params {:keyword {:type :string :required? true :description "full-text term"}
                              :scope {:type :string :allowed ["standalone" "cluster"] :default "standalone"}}
                 :source (assoc sql-source :batch {:parent-key :t.person/id :child-fk "id" :placeholder ":ids"})
                 :source-ref "src"}
                {:id "person_by_erp" :input [:t.person/id] :output [:t.person/email :t.person/tier]
                 :result_type "single" :source sql-source :source-ref "src"}]}))

(def ^:private rows
  (delay
    (let [{:keys [model findings]} (dialect/read-dialect model-text)
          _ (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
          model (-> model
                    (update :sources update-vals #(assoc % :type "test-mcp"))
                    (update :resolvers (fn [rs] (mapv #(if (= "static" (get-in % [:source :type])) %
                                                           (assoc-in % [:source :type] "test-mcp")) rs))))
          result (engine/compile-complete model)
          _ (assert (:compiled result) (pr-str (:findings result)))
          {:keys [catalog param-decls]} (catalog/of (:compiled result))]
      (into {} (map (juxt :attribute identity))
            (catalog/attributes-for-entities catalog param-decls
                                             ["t.ticket" "t.person" "t.group"])))))

;; ---------------------------------------------------------------------------

(deftest a-union-join-names-every-target-and-spells-the-branch-form
  "§6.1: the catalog answers a vector of targets, and the row says so in
   words an agent can act on — every target, «exactly one of these», and
   the nesting form with each branch's identity key already filled in."
  (is (= {:entity "t.ticket"
          :attribute "t.ticket/subject"
          :type "join"
          :description (str "→ t.device | t.person (union: each record is exactly one of these "
                            "entities, discriminated by its identity attribute; nest as "
                            "{:t.ticket/subject {:t.device/id [...] :t.person/id [...]}})")
          :targets ["t.device" "t.person"]}
         (get @rows "t.ticket/subject"))))

(deftest a-recursive-join-carries-its-declared-depth-ceiling
  "§6.4: the gate clamps a deeper request to the declaration, so the number
   is on the row for the agent to read before asking. A single-target join
   keeps the `→ target` description byte-for-byte."
  (is (= {:entity "t.group"
          :attribute "t.group/children" :required_inputs ["t.group/id"]
          :type "join"
          :description "→ t.group"
          :limit {:default 50 :max 50}
          :recursion_depth 3}
         (get @rows "t.group/children"))))

(deftest a-scalar-row-carries-its-preferred-producer-s-declarations
  "§3.1 / §3.2 / §4: the ranked producer wins (`:priority 10` over the
   unranked one), and its free parameters render with name, type, whether
   required, description and allowed values; `:batch` says it is evaluated
   for a set of inputs at once."
  (let [row (get @rows "t.person/email")]
    (is (= {:entity "t.person" :attribute "t.person/email" :required_inputs ["t.person/id"]
            :parameter_semantics "Per-call controls; optional unless required; do not establish graph paths" :type "string"
            :description "" :format "text" :priority 10 :batch true}
           (dissoc row :params)))
    (is (= [{:name "keyword" :type "string" :required true :description "full-text term"}
            {:name "scope" :type "string" :default "standalone" :allowed ["standalone" "cluster"]}]
           (sort-by :name (:params row)))))
  (testing "an attribute only the unranked producer answers renders no producer keys"
    (is (= {:entity "t.person" :attribute "t.person/tier" :required_inputs ["t.person/id"] :type "string"
            :description "" :format "code"}
           (get @rows "t.person/tier")))))

(deftest a-1-x-row-is-untouched
  "Nothing 2.0 adds may reach a row whose producer declares none of it:
   an agent that has been reading these rows must not be able to tell."
  (is (= {:entity "t.group" :attribute "t.group/name" :required_inputs ["t.group/id"] :type "string"
          :description "" :format "text"}
         (get @rows "t.group/name"))))

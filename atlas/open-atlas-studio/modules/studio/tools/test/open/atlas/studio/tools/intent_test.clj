(ns open.atlas.studio.tools.intent-test
  "The intent tools' contract (8.4.14 P7).

   What these tests are for, in the order the exit conditions state them:
   plan and apply run ONE planner (asserted byte for byte, because two
   implementations of «what this writes» is exactly how a preview starts
   lying); an ambiguous local name refuses with every candidate and writes
   nothing; a read-only key sees the plan and is refused the apply; an api
   source reached with a read-only key issues no HTTP request at all (8.4.22
   裁定十六 ① moved that gate onto the write key); and the probe's audit record
   carries no response body.

   The pool here is small and hand-written rather than the demo model: what is
   under test is what the planner GENERATES, and a fixture with two tables
   makes the generated entry readable in the failure output."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.tools :as tools]
            [open.atlas.studio.tools.impl.intent :as intent]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; A source whose structure is known, and one that must never be called
;; ---------------------------------------------------------------------------

(def ^:private probe-calls
  "Every probe the tests provoked. The `no :probe key` case asserts this stays
   EMPTY — «refused» must mean the request was never made, not that its result
   was thrown away."
  (atom []))

(def ^:private describe-configs
  "Every source entry `describe-schema` was handed. The connector is the far
   side of the seam under test in `resolve-source-reaches-the-connector-test`:
   what matters is the entry that ARRIVES here, not what the pool stored."
  (atom []))

(defn- fake-describe
  "The structure the test source exposes. Installed on the SPI only for the
   duration of a test and then taken back off — a `defmethod` at the top level
   would register \"sql\" for the whole JVM and the real connector-sql's own
   describe tests, running later in the aggregate, would get this one."
  [source-config _ opts]
  (swap! describe-configs conj source-config)
  (let [rels [{:name "customers" :schema nil :kind :table
               :columns [{:name "cust_no" :class :integer :sql-type "INTEGER"
                          :nullable? false :position 1}
                         {:name "cust_name" :class :string :sql-type "TEXT"
                          :nullable? true :position 2}
                         {:name "blob_col" :class :binary :sql-type "BLOB"
                          :nullable? true :position 3}]
               :primary-key ["cust_no"] :foreign-keys []}
              {:name "orders" :schema nil :kind :table
               :columns [{:name "order_no" :class :integer :sql-type "INTEGER"
                          :nullable? false :position 1}
                         {:name "cust_no" :class :integer :sql-type "INTEGER"
                          :nullable? true :position 2}
                         {:name "total" :class :decimal :sql-type "NUMERIC"
                          :nullable? true :position 3}]
               :primary-key ["order_no"] :foreign-keys []}]
        names (set (:names opts))]
    {:capabilities {:primary-keys true :foreign-keys true :views true}
     :keys-read? true
     :findings []
     :relations (if (seq names) (filterv #(names (:name %)) rels) rels)}))

(defn- fake-connection-bound
  "A stand-in for the api connector's `connection-bound-attributes` (doc 106
   §106.14 item 11). This module depends on the SPI and on no connector (D1.8),
   so the seam is registered here the way `fake-describe` stands in for a real
   `describe-schema`: the POSITION rule is api-v1's and is tested there; what
   is tested here is what the write side does with the answer."
  [src]
  (into #{} (map #(keyword (subs % 1 (dec (count %)))))
        (re-seq #"\{[a-zA-Z][^{}]*/[^{}]*\}" (str (:base_url src)))))

(.addMethod ^clojure.lang.MultiFn spi/connection-bound-attributes "api" fake-connection-bound)

(defn with-sql-structure
  "Install `fake-describe` for the \"sql\" source type, and put back whatever
   was there before."
  [f]
  (let [prev (get-method spi/describe-schema "sql")]
    (.addMethod ^clojure.lang.MultiFn spi/describe-schema "sql" fake-describe)
    (try (f)
         (finally
           (if (and prev (not= prev (get-method spi/describe-schema :default)))
             (.addMethod ^clojure.lang.MultiFn spi/describe-schema "sql" prev)
             (remove-method spi/describe-schema "sql"))))))

;; ---------------------------------------------------------------------------
;; The pool
;; ---------------------------------------------------------------------------

(def model
  {:sources {"db" {:type "sql" :jdbc_url "jdbc:sqlite:secret.db"}
             ;; A source whose credential is a `${VAR}` reference, the shape a
             ;; Studio credential store fills in. No graph resolver names it,
             ;; so it changes no other test's decision.
             "db-env" {:type "sql" :jdbc_url "${DB_JDBC_URL}"}
             "api" {:type "api" :base_url "https://example.invalid"}
             ;; One source, many like-for-like instances: the host is a
             ;; query-time binding (owner 2026-09-07, doc 106 §106.14 item 11).
             "api-tpl" {:type "api"
                        :base_url "https://{nifi.instance/host}:9443/nifi-api"}}
   :namespaces {"shop.customers" {:label "客户"}
                "shop.orders" {:label "订单"}}
   :attributes {:shop.customers/cust_no {:type :integer :identity? true}
                :shop.customers/cust_name {:type :string}
                :shop.orders/order_no {:type :integer :identity? true}
                :shop.orders/cust_no {:type :integer}
                ;; Registered, and produced by NO resolver — the pool state
                ;; 106.14 第 5 条 refuses to build a «belongs to» key on: the
                ;; key is read out of the child's rows, and nothing reads this
                ;; field.
                :shop.orders/ref_no {:type :integer}
                ;; The same LOCAL name on two entities — the only source of an
                ;; `:exact-ambiguous` verdict, and the reason `add_link`
                ;; refuses "cust_no" rather than picking one.
                :shop.orders/total {:type :decimal}}
   :resolvers [{:id "shop_customers_by_cust_no"
                :input [:shop.customers/cust_no]
                :output [:shop.customers/cust_no :shop.customers/cust_name]
                :result_type "single"
                :source {:type "sql"
                         :query "SELECT cust_no, cust_name FROM customers WHERE cust_no IN (:cust_nos)"}
                :source-ref "db"}
               {:id "shop_orders_by_order_no"
                :input [:shop.orders/order_no]
                :output [:shop.orders/order_no :shop.orders/cust_no :shop.orders/total]
                :result_type "single"
                :source {:type "sql"
                         :query "SELECT order_no, cust_no, total FROM orders WHERE order_no IN (:order_nos)"}
                :source-ref "db"}]})

(def members
  {:namespaces #{"shop.customers" "shop.orders"}
   :attributes #{:shop.customers/cust_no :shop.customers/cust_name
                 :shop.orders/order_no :shop.orders/cust_no :shop.orders/total
                 :shop.orders/ref_no}
   :resolvers #{} :mutations #{} :links #{} :metrics #{} :terms #{} :units #{}})

(def ^:dynamic *conn* nil)
(def ^:dynamic *recipe* nil)

(defn with-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "intent"))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)
          objs (pool/dialect->objects model)
          r (pool/import-pool! conn {:pool/version 1
                                     :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo") objs)})]
      (assert (empty? (:findings r)) (pr-str (:findings r)))
      (reset! probe-calls [])
      (reset! describe-configs [])
      (binding [*conn* conn *recipe* (atom members)]
        (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(use-fixtures :each with-sql-structure with-pool)

;; ---------------------------------------------------------------------------
;; The host's ends, faked exactly as far as the tools reach into them
;; ---------------------------------------------------------------------------

(defn- graph-service []
  {:graph-members (fn [_] {:graph "ws/g" :members @*recipe*})
   :apply-recipe-delta!
   (fn [{:keys [delta]}]
     (swap! *recipe*
            (fn [m]
              (as-> m m*
                (reduce-kv (fn [a k ids] (update a k (fnil into #{}) ids)) m* (:add delta))
                (reduce-kv (fn [a k ids] (update a k #(into #{} (remove (set ids)) %)) )
                           m* (:remove delta)))))
     {:graph "ws/g"})})

(defn- ctx [& {:as extra}]
  (merge {:conn *conn*
          :scope #{:read :write}
          ;; D3.22: the workspace the ctx carries is what stamps every action.
          :workspace "demo"
          :provenance {:entrypoint :mcp :token-id "t-1" :actor "tester"}
          :graph-service (graph-service)}
         extra))

(defn- call [c tool args]
  (tools/call (tools/registry (:scope c)) c tool args))

(defn- codes [r] (set (map :code (:findings r))))
(defn- errors [r] (filter #(= :error (:severity %)) (:findings r)))

;; ---------------------------------------------------------------------------
;; Exit condition: plan and apply run ONE planner
;; ---------------------------------------------------------------------------

(deftest plan-and-apply-write-the-same-actions-test
  (testing "add_thing: the applied actions are byte-identical to the plan's"
    (let [args {:graph "ws/g" :name "orders" :from "orders" :identity "cust_no"
                :namespace "shop.orders2"}
          plan (call (ctx) "add_thing" args)
          done (call (ctx) "add_thing" (assoc args :apply true))]
      (is (empty? (errors plan)) (pr-str (:findings plan)))
      (is (seq (:actions plan)))
      (is (= (pr-str (:actions plan)) (pr-str (:actions done)))
          "apply re-planned from the same arguments and got the same actions")
      (is (true? (:written? done)))
      (is (= (count (:actions done)) (count (:revisions done))))))

  (testing "the plan alone writes nothing"
    (let [before (count (pool/objects (pool/db *conn*)))]
      (call (ctx) "add_thing" {:name "customers" :from "customers" :namespace "shop.cust2" :graph "ws/g"})
      (is (= before (count (pool/objects (pool/db *conn*))))))))

(deftest add-resolver-generates-a-legal-entry-test
  (let [r (call (ctx) "add_thing" {:name "customers" :from "customers" :namespace "shop.cust2" :graph "ws/g" :apply true})
        res (first (filter #(= :resolver (:type %)) (:actions r)))
        entry (:entry res)]
    (is (true? (:written? r)))
    (testing "a collection resolver's head is ONE join attribute (D2.29)"
      (is (= 1 (count (:output entry))))
      (is (map? (first (:output entry))))
      (is (= :shop.cust2/all_customers (ffirst (first (:output entry))))))
    (testing "an unanchored list carries the owner's ruling (D2.13) and a page window"
      (is (true? (:curated? entry)))
      (is (map? (:limit entry))))
    (testing "a column with no dialect type is left out, and SAID so (D2.14)"
      (is (not (str/includes? (get-in entry [:source :query]) "blob_col")))
      (is (contains? (codes r) :tools.intent/type-undecidable)))
    (testing "the recipe gained the namespace and the attributes — the step whose
              omission is what :scope/mixed-output was refusing"
      (is (contains? (:namespaces @*recipe*) "shop.cust2"))
      (is (contains? (:attributes @*recipe*) :shop.cust2/all_customers))
      (is (true? (:recipe-applied? r))))))

(deftest add-attribute-extends-resolver-and-recipe-test
  (let [r (call (ctx) "add_attribute" {:graph "ws/g"
                                       :resolver "shop_customers_by_cust_no"
                                       :attribute "blob_col" :type :string
                                       :apply true})
        db (pool/db *conn*)
        entry (:pool/entry (pool/object db :resolver "shop_customers_by_cust_no"))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (some #{:shop.customers/blob_col} (:output entry)))
    (is (str/includes? (get-in entry [:source :query]) "blob_col"))
    (is (contains? (:attributes @*recipe*) :shop.customers/blob_col))
    (testing "a label nobody wrote is a :fill position, not a refusal (D2.26)"
      (is (some #(= :label (last %)) (:fill r)))
      (is (empty? (errors r))))))

;; ---------------------------------------------------------------------------
;; The plan's summary (doc 106 §106.7 P8a)
;; ---------------------------------------------------------------------------

(deftest every-plan-summarises-itself-test
  "≤3 short lines, derived from `:actions` and `:recipe-delta` — never text a
   caller handed in. A plan without one would make a reader open the action
   maps to find out whether it is the plan they asked for."
  (testing "a new thing says what it creates"
    (let [r (call (ctx) "add_thing" {:graph "ws/g" :name "customers" :from "customers"
                                     :namespace "shop.cust3"})]
      (is (vector? (:summary r)))
      (is (<= 1 (count (:summary r)) 3))
      (is (every? string? (:summary r)))
      ;; 8.5: the line names the attribute IN FULL and the namespace it lands
      ;; on — «parent» and «child» mean the same in both kinds of relation, and
      ;; which of the two ends up holding the key is what a reader needs.
      (is (some #(= "Add relation attribute shop.cust3/all_customers on shop.cust3 — has many shop.cust3" %)
                (:summary r)))))
  (testing "an added attribute counts itself and the label left to fill"
    (let [r (call (ctx) "add_attribute" {:graph "ws/g"
                                         :resolver "shop_customers_by_cust_no"
                                         :attribute "blob_col" :type :string})]
      (is (some #(re-find #"^Add 1 attribute to shop.customers" %) (:summary r)))
      (is (some #(re-find #"^\d+ fields? to fill$" %) (:summary r)))))
  (testing "a removal says so"
    (let [r (call (ctx) "remove_objects" {:graph "ws/g" :objects ["shop_orders_by_order_no"]})]
      (is (some #(re-find #"^Remove \d+ object" %) (:summary r)))))
  (testing "a term is a concept"
    (let [r (call (ctx) "define_concept" {:graph "ws/g" :concept "cust_no"
                                         :attributes ["shop.customers/cust_no"]
                                         :definition {:label "客户编号" :type "string"}})]
      (is (some #(= "Define concept cust_no" %) (:summary r)))))
  (testing "the summary never exceeds three lines"
    (let [r (call (ctx) "add_thing" {:graph "ws/g" :name "customers" :from "customers"
                                     :namespace "shop.cust4"})]
      (is (<= (count (:summary r)) 3)))))

;; ---------------------------------------------------------------------------
;; Exit condition: an ambiguous local name refuses with ALL candidates,
;; and writes nothing
;; ---------------------------------------------------------------------------

(deftest ambiguous-local-name-refuses-with-every-candidate-test
  (let [before (count (pool/objects (pool/db *conn*)))
        r (call (ctx) "add_link" {:graph "ws/g" :from "cust_no" :to "order_no"
                                  :apply true})
        fnd (first (filter #(= :tools.intent/name-ambiguous (:code %)) (:findings r)))]
    (is (contains? (codes r) :tools.intent/name-ambiguous))
    (is (= 2 (count (:got fnd))) "both candidates are listed; ambiguity is never ranked")
    (is (= before (count (pool/objects (pool/db *conn*)))) "nothing was written")))

(deftest add-link-normalizes-a-bidirectional-pair-test
  (let [r (call (ctx) "add_link" {:graph "ws/g"
                                  :from ":shop.orders/cust_no"
                                  :to ":shop.customers/cust_no"
                                  :cardinality :one-to-many :apply true})
        id (:id (first (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= [:shop.customers/cust_no :shop.orders/cust_no] id)
        "D2.3: the two ends are ordered, so A↔B and B↔A are one link")
    (testing "a link needs no recipe change: closure pulls it in"
      (is (empty? (:links @*recipe*))))
    (testing "the same link a second time is refused, not duplicated"
      (is (seq (errors (call (ctx) "add_link"
                             {:graph "ws/g" :from ":shop.customers/cust_no"
                              :to ":shop.orders/cust_no" :apply true})))))))

;; ---------------------------------------------------------------------------
;; Exit condition: a read-only key sees the plan and is refused the apply
;; ---------------------------------------------------------------------------

(deftest read-only-key-plans-but-does-not-write-test
  (let [c (ctx :scope #{:read})
        before (count (pool/objects (pool/db *conn*)))
        plan (call c "add_thing" {:name "customers" :from "customers" :namespace "shop.cust3" :graph "ws/g"})
        applied (call c "add_thing" {:name "customers" :from "customers" :namespace "shop.cust3" :graph "ws/g" :apply true})]
    (is (seq (:actions plan)) "the plan itself is visible to a read-only key")
    (is (empty? (errors plan)))
    (is (false? (:written? applied)))
    (is (contains? (codes applied) :tools.intent/blocked-plan))
    (is (seq (:actions applied)) "the refusal carries the plan it would have written")
    (is (= before (count (pool/objects (pool/db *conn*)))))))

;; ---------------------------------------------------------------------------
;; The removals
;; ---------------------------------------------------------------------------

(deftest remove-objects-refuses-while-something-produces-it-test
  (let [r (call (ctx) "remove_objects"
                {:graph "ws/g" :objects [":shop.customers/cust_name"] :apply true})]
    (is (contains? (codes r) :tools.intent/removal-blocked))
    (is (seq (:blocked-by r)) "the list of what still names it travels with the refusal")
    (is (empty? (:actions r)))
    (is (some? (pool/object (pool/db *conn*) :attribute :shop.customers/cust_name)))))

(deftest remove-objects-cascades-and-sweeps-the-recipe-test
  (let [args {:graph "ws/g" :objects ["shop_orders_by_order_no"]}
        plan (call (ctx) "remove_objects" args)
        done (call (ctx) "remove_objects" (assoc args :apply true))]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (true? (:written? done)))
    (is (nil? (pool/object (pool/db *conn*) :resolver "shop_orders_by_order_no")))
    (testing "the attributes nothing else produces go with it, and leave the recipe"
      (is (seq (:cascade done)))
      (is (not (contains? (:attributes @*recipe*) :shop.orders/total))))))

(deftest remove-objects-takes-a-link-in-either-order-test
  (call (ctx) "add_link" {:graph "ws/g" :from ":shop.customers/cust_no"
                          :to ":shop.orders/cust_no" :apply true})
  ;; A link has no name of its own — it IS its endpoint pair (D2.3) — so it is
  ;; named by a two-element entry, and either order finds the same link.
  (let [r (call (ctx) "remove_objects"
                {:graph "ws/g"
                 :objects [[":shop.orders/cust_no" ":shop.customers/cust_no"]]
                 :apply true})]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (nil? (pool/object (pool/db *conn*) :link
                           [:shop.customers/cust_no :shop.orders/cust_no])))))

(deftest remove-objects-takes-several-kinds-in-one-transaction-test
  "裁定九 merged the three `remove_*` into one, so «remove these» is one plan
   and one apply: applying them one at a time would let the second refuse
   after the first was already written."
  (call (ctx) "add_link" {:graph "ws/g" :from ":shop.customers/cust_no"
                          :to ":shop.orders/cust_no" :apply true})
  (let [args {:graph "ws/g"
              :objects ["shop_orders_by_order_no"
                        [":shop.customers/cust_no" ":shop.orders/cust_no"]]}
        plan (call (ctx) "remove_objects" args)]
    (is (empty? (errors plan)) (pr-str (:findings plan)))
    (is (= #{:resolver :link :attribute} (set (map first (:removed plan))))
        "the kinds are worked out from the names, not asked for")
    (let [done (call (ctx) "remove_objects" (assoc args :apply true))]
      (is (true? (:written? done)))
      (is (nil? (pool/object (pool/db *conn*) :resolver "shop_orders_by_order_no")))
      (is (nil? (pool/object (pool/db *conn*) :link
                             [:shop.customers/cust_no :shop.orders/cust_no]))))))

(deftest remove-objects-reports-every-unresolved-name-at-once-test
  (let [r (call (ctx) "remove_objects"
                {:graph "ws/g" :objects ["no_such_thing" "also_not_here"]})]
    (is (= 2 (count (errors r))) "one round trip per mistake, not per name")
    (is (= #{:tools.intent/name-unresolved} (codes r)))
    (is (empty? (:actions r)))))

;; ---------------------------------------------------------------------------
;; describe_objects
;; ---------------------------------------------------------------------------

(deftest describe-objects-writes-many-in-one-transaction-test
  ;; No `graph` argument: enrichment is about pool objects, and a recipe is
  ;; not involved. The closed key set refuses one rather than ignoring it.
  (let [args {:objects [{:type :attribute :id :shop.customers/cust_name
                         :label "客户名称" :description "客户的显示名。"}
                        {:type :attribute :id :shop.orders/total
                         :label "订单金额" :synonyms ["总额"]}]}
        plan (call (ctx) "describe_objects" args)
        done (call (ctx) "describe_objects" (assoc args :apply true))
        db (pool/db *conn*)]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (= "客户名称" (:label (:pool/entry (pool/object db :attribute :shop.customers/cust_name)))))
    (is (= ["总额"] (:synonyms (:pool/entry (pool/object db :attribute :shop.orders/total)))))
    (testing "an object the pool does not hold refuses the whole batch"
      (let [r (call (ctx) "describe_objects"
                    {:objects [{:type :attribute :id :shop.nope/x :label "x"}]
                     :apply true})]
        (is (contains? (codes r) :tools.intent/name-unresolved))
        (is (false? (:written? r)))))))

;; ---------------------------------------------------------------------------
;; assign_term / unassign_term (owner, 2026-09-03)
;; ---------------------------------------------------------------------------

(deftest assign-term-registers-strips-and-reports-test
  (let [args {:graph "ws/g" :term "shop.party/customer_no"
              :attributes [":shop.customers/cust_no" ":shop.orders/cust_no"]
              :definition {:label "客户编号" :description "客户的稳定标识。"}}
        plan (call (ctx) "define_concept" args)
        done (call (ctx) "define_concept" (assoc args :apply true))
        db (pool/db *conn*)]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (true? (:written? done)) (pr-str (:findings done)))
    (is (some? (pool/object db :term "shop.party/customer_no")))
    (is (= "shop.party/customer_no"
           (:term (:pool/entry (pool/object db :attribute :shop.customers/cust_no)))))
    (testing "the term enters the graph's recipe — a bound term outside it fails the cut"
      (is (contains? (:terms @*recipe*) "shop.party/customer_no")))
    (testing "saying the SAME definition again is the bindings alone (item 8.6)"
      (let [r (call (ctx) "define_concept"
                    (assoc args :attributes [":shop.orders/cust_no"]))]
        (is (empty? (errors r)) (pr-str (:findings r)))
        (is (empty? (filter #(= :term (:type %)) (:actions r)))
            "nothing is written to the concept itself — it already says this")
        (is (seq (filter #(= :attribute (:type %)) (:actions r))))))
    (testing "a DIFFERENT definition is still refused: one source of truth (D2.26)"
      (let [r (call (ctx) "define_concept"
                    (assoc args :attributes [":shop.orders/cust_no"]
                           :definition {:label "另一个名字"}))]
        (is (contains? (codes r) :tools.intent/name-ambiguous))
        (is (empty? (:actions r)))))))

(deftest assign-term-strips-local-fields-and-flags-a-label-clash-test
  ;; Give one attribute its own words first, so the binding has something to
  ;; take away and something to disagree with.
  (call (ctx) "describe_objects"
        {:objects [{:type :attribute :id :shop.customers/cust_no
                    :label "客户号" :description "本表的客户号。"}]
         :apply true})
  (call (ctx) "define_concept"
        {:graph "ws/g" :term "shop.party/customer_no"
         :attributes [":shop.orders/cust_no"]
         :definition {:label "客户编号" :description "客户的稳定标识。"}
         :apply true})
  (let [plan (call (ctx) "define_concept"
                   {:graph "ws/g" :term "shop.party/customer_no"
                    :attributes [":shop.customers/cust_no"]})]
    (testing "every stripped local field is listed with its current value"
      (is (= #{:label :description} (set (map :field (:stripped plan)))))
      (is (some #(= "客户号" (:value %)) (:stripped plan))))
    (testing "a local label that disagrees with the term's is never overwritten silently"
      (is (= 1 (count (:label-conflicts plan))))
      (is (some #(= :label (last %)) (:fill plan)))
      (is (empty? (errors plan)) ":fill does not block apply (8.4.14)"))
    (testing "applying it does strip them"
      (call (ctx) "define_concept"
            {:graph "ws/g" :term "shop.party/customer_no"
             :attributes [":shop.customers/cust_no"] :apply true})
      (let [e (:pool/entry (pool/object (pool/db *conn*) :attribute :shop.customers/cust_no))]
        (is (nil? (:label e)))
        (is (nil? (:description e)))
        (is (= "shop.party/customer_no" (:term e)))))))

(deftest unassign-term-copies-the-semantics-back-test
  (call (ctx) "define_concept"
        {:graph "ws/g" :term "shop.party/customer_no"
         :attributes [":shop.customers/cust_no"]
         :definition {:label "客户编号" :description "客户的稳定标识。" :synonyms ["客户号"]}
         :apply true})
  (let [r (call (ctx) "unassign_term" {:graph "ws/g"
                                       :attributes [":shop.customers/cust_no"]
                                       :apply true})
        e (:pool/entry (pool/object (pool/db *conn*) :attribute :shop.customers/cust_no))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (nil? (:term e)))
    (testing "the words come back — an unbound attribute with no description is worse"
      (is (= "客户编号" (:label e)))
      (is (= "客户的稳定标识。" (:description e)))
      (is (= ["客户号"] (:synonyms e))))
    (testing "the term itself survives: other attributes may still bind it"
      (is (some? (pool/object (pool/db *conn*) :term "shop.party/customer_no"))))
    (testing "unassigning what binds nothing is refused, not a silent no-op"
      (is (contains? (codes (call (ctx) "unassign_term"
                                  {:graph "ws/g" :attributes [":shop.orders/total"]}))
                     :tools.intent/name-unresolved)))))

;; ---------------------------------------------------------------------------
;; 裁定七 — the api probe
;; ---------------------------------------------------------------------------

(defn- probe-fn
  "A probe stand-in that RECORDS being called. The point of the no-key test is
   that this list stays empty."
  [sample]
  (fn [src request]
    (swap! probe-calls conj {:src (:type src) :request request})
    {:status 200 :bytes 128 :sample sample}))

(deftest probe-with-a-read-only-key-issues-no-request-test
  ;; 8.4.22 裁定十六 ①: the gate is the WRITE key, not a client declaration.
  ;; A read-only key is still refused, and refused BEFORE the request.
  (let [c (ctx :scope #{:read} :probe-source (probe-fn [{:id 1}]))
        r (call c "add_thing" {:name "invoices" :from "/invoices" :namespace "shop.inv" :graph "ws/g" :source "api"})]
    (is (contains? (codes r) :tools.intent/probe-required))
    (is (empty? @probe-calls) "a read-only key means no HTTP request, not a discarded one")))

(deftest probe-follows-the-write-key-test
  ;; The exit condition of 裁定十六 ①: an agent that declares no capability at
  ;; all gets a plan, because its key may write this workspace.
  (let [c (ctx :probe-source (probe-fn [{:id 7 :name "acme"}]))
        r (call c "add_thing" {:name "invoices" :from "/invoices" :namespace "shop.inv" :graph "ws/g" :source "api"})]
    (is (not (contains? (codes r) :tools.intent/probe-required)))
    (is (= 1 (count @probe-calls)))
    (is (seq (:actions r)))))

(deftest probe-infers-fields-and-leaves-them-all-to-a-person-test
  (let [c (ctx :scope #{:read :write :probe}
               :probe-source (probe-fn [{:id 7 :name "acme" :created_at "2026-09-03"
                                         :note nil :active true}]))
        r (call c "add_thing" {:name "invoices" :from "/invoices" :namespace "shop.inv" :graph "ws/g" :source "api" :params {:page 1}})]
    (is (= 1 (count @probe-calls)) "one call, one page")
    (testing "every inferred field is a position for a person (裁定七)"
      (is (= 5 (count (filter #(= :attribute (first %)) (:fill r))))))
    (testing "the audit record carries counts and NO response body"
      (let [a (:probe r)]
        (is (= "api" (:probe/source a)))
        (is (= "/invoices" (:probe/path a)))
        (is (= ["page"] (:probe/param-keys a)) "parameter NAMES, never their values")
        (is (= 200 (:probe/status a)))
        (is (= 128 (:probe/response-bytes a)))
        (is (= 5 (:probe/fields a)))
        (is (not (str/includes? (pr-str a) "acme")) "no response content anywhere in it")))
    (testing "a value with no evidence is listed as undecided rather than dropped"
      (is (some #(= "null" (last %)) (:fill r))))))

(deftest api-data-path-and-nested-outputs-test
  ;; 8.4.22 裁定十六 ②: the records live under a path, a nested value is an
  ;; output of its own, and the api branch writes an api operation block —
  ;; through the SAME planner the sql branch uses.
  (let [sample {:data {:items [{:id 1 :name "a" :address {:city "X"}}]}}
        c (ctx :probe-source (probe-fn sample))
        r (call c "add_thing" {:name "users" :from "/users" :attributes ["id" "name" {:name "city" :path "$.address.city"}] :namespace "shop.users" :graph "ws/g" :source "api" :data_path "$.data.items"})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= "api" (get-in entry [:source :type])))
    (is (= "/users" (get-in entry [:source :endpoint])))
    (is (= "$.data.items" (get-in entry [:source :data_path])))
    (testing "the nested value is mapped by its JSONPath, the flat ones by their key"
      (is (= {:shop.users/id "$.id"
              :shop.users/name "$.name"
              :shop.users/city "$.address.city"}
             (get-in entry [:source :field_mapping]))))
    (testing "a list-shaped api interface leaves paging to a person"
      (is (some #(= "pagination" (second %)) (:fill r)))
      (is (some #(= "limit" (second %)) (:fill r))))))

(deftest api-data-path-heuristic-is-reported-test
  ;; Absent `data_path` the heuristic decides — and SAYS which path it chose,
  ;; in the plan and in the audit record (8.4.22 裁定十六 ②).
  (let [c (ctx :probe-source (probe-fn {:data {:items [{:id 1 :name "a"}]}}))
        r (call c "add_thing" {:name "users" :from "/users" :namespace "shop.users2" :graph "ws/g" :source "api"})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (= "$.data.items" (get-in entry [:source :data_path])))
    (is (= "$.data.items" (:probe/data-path (:probe r))))
    (is (= :heuristic (:probe/data-path-from (:probe r))))
    (is (some #(= "data_path" (second %)) (:fill r))
        "the chosen path waits for a person like every other inference")))

(defn- truncated-probe-fn
  "A probe stand-in answering the way the STREAMING probe answers a body that
   is bigger than the ceiling (8.4.23 裁定十七 + 补裁): the records it read,
   the path it found them at, and no `:bytes` — the size of an answer nobody
   read to the end is not knowable."
  [records records-path]
  (fn [src request]
    (swap! probe-calls conj {:src (:type src) :request request})
    {:status 200 :bytes-read 4096 :sample (vec records) :records (count records)
     :records-path records-path :truncated? true}))

(deftest a-truncated-probe-plans-from-its-one-record-test
  ;; 8.4.23 裁定十七: the sample is a single record, ALREADY located — the
  ;; planner must not look for `$.products` inside it and conclude the
  ;; relation has no fields.
  (let [c (ctx :probe-source (truncated-probe-fn [{:id 1 :name "widget"}] "$.products"))
        r (call c "add_thing" {:name "products" :from "/catalog" :namespace "shop.prod" :graph "ws/g" :source "api"})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= 2 (count (filter #(= :attribute (first %)) (:fill r))))
        "both fields of the one record")
    (testing "the resolver reads its rows from where the probe found them"
      (is (= "$.products" (get-in entry [:source :data_path]))))
    (testing "the audit says how much was read and that it stopped early"
      (let [a (:probe r)]
        (is (= 4096 (:probe/bytes-read a)))
        (is (true? (:probe/truncated? a)))
        (is (= 1 (:probe/records a)) "how many records the inference rests on")
        (is (nil? (:probe/response-bytes a))
            "the size of the whole answer is unknown, so it is not claimed")
        (is (= :probe (:probe/data-path-from a)))
        (is (not (str/includes? (pr-str a) "widget")) "no response content anywhere in it")))))

(deftest a-caller-given-data-path-survives-a-truncated-probe-test
  (let [c (ctx :probe-source (truncated-probe-fn [{:id 1 :sku "x"}] "$.products"))
        r (call c "add_thing" {:name "products" :from "/catalog" :namespace "shop.prod2" :graph "ws/g" :source "api" :data_path "$.products"})]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= "$.products" (:data_path (:request (first @probe-calls))))
        "the path the caller gave is what the probe was told")
    (is (= :caller (:probe/data-path-from (:probe r))))))

(deftest probe-over-the-ceiling-refuses-rather-than-truncates-test
  (let [c (ctx :scope #{:read :write :probe}
               :probe-source (fn [_ _] {:status 200 :bytes-read (* 256 1024)
                                        :over-limit? true}))
        r (call c "add_thing" {:name "invoices" :from "/invoices" :namespace "shop.inv" :graph "ws/g" :source "api"})]
    (is (contains? (codes r) :tools.intent/probe-refused))
    (is (empty? (:actions r)))
    (testing "and the refusal names the way out (8.4.23 裁定十七)"
      (is (some #(str/includes? (str (:message %)) "data_path")
                (:findings r))))))

;; ---------------------------------------------------------------------------
;; The recipe half is reported when it fails, and nothing is rolled back
;; ---------------------------------------------------------------------------

(deftest a-failed-recipe-update-is-reported-not-rolled-back-test
  (let [c (ctx :graph-service {:graph-members (fn [_] {:members @*recipe*})
                               :apply-recipe-delta!
                               (fn [_] (throw (ex-info "recipe store is down" {})))})
        r (call c "add_thing" {:name "customers" :from "customers" :namespace "shop.cust4" :graph "ws/g" :apply true})]
    (is (true? (:written? r)) "the pool write is a legal state on its own (D3.19)")
    (is (false? (:recipe-applied? r)))
    (is (contains? (codes r) :tools.intent/recipe-not-updated))
    (is (some? (pool/object (pool/db *conn*) :namespace "shop.cust4"))
        "nothing was rolled back")))

;; ---------------------------------------------------------------------------
;; Verification folded into apply (item 3)
;; ---------------------------------------------------------------------------

(deftest apply-runs-a-limit-one-draft-query-test
  (let [seen (atom nil)
        c (ctx :draft-service {:evaluate! (fn [args]
                                            (reset! seen args)
                                            {:statements [{:sql "SELECT …" :rows 1}]
                                             :findings []})})
        r (call c "add_thing" {:name "customers" :from "customers" :namespace "shop.cust5" :graph "ws/g" :apply true})]
    (is (true? (:written? r)))
    (is (= 1 (count (:statements r))) "the statements come back with the write")
    (is (= "ws/g" (:graph @seen)))
    (let [node (ffirst (first (:query @seen)))]
      (is (list? node) "the collection is called with parameters")
      (is (= {:limit 1} (second node)) "the query asks for one row"))))

(deftest a-failed-verification-is-a-finding-not-a-rollback-test
  (let [c (ctx :draft-service {:evaluate! (fn [_] {:findings [{:code :engine/no-path
                                                               :severity :error
                                                               :path [] :message "no path"}]})})
        r (call c "add_thing" {:name "customers" :from "customers" :namespace "shop.cust6" :graph "ws/g" :apply true})]
    (is (true? (:written? r)))
    (is (some? (pool/object (pool/db *conn*) :namespace "shop.cust6")))
    (is (contains? (codes r) :tools.intent/verify-failed))
    (is (empty? (errors r)) "a failed check on a landed write is a warning, not a refusal")))

;; ---------------------------------------------------------------------------
;; Refusals leak nothing, and every code is registered
;; ---------------------------------------------------------------------------

(deftest refusals-are-well-formed-and-leak-no-configuration-test
  (doseq [r [(call (ctx) "add_link" {:graph "ws/g" :from "cust_no" :to "order_no"})
             (call (ctx) "add_thing" {:name "nope" :from "nope" :namespace "shop.x" :graph "ws/g"})
             (call (ctx) "add_attribute" {:graph "ws/g" :resolver "nope"
                                          :attribute "x"})
             (call (ctx) "remove_resolver" {:graph "ws/g" :resolver "nope"})]]
    (is (seq (errors r)) "a refusal is never silent")
    (is (every? tools/finding? (:findings r)) "finding-v1 §2 closed key set")
    (is (not (str/includes? (pr-str (:findings r)) "jdbc:sqlite"))
        "no configuration value in a refusal (B4 exit condition 2)")))

(deftest resolve-source-reaches-the-connector-test
  (testing "with `:resolve-source`, the connector sees the RESOLVED entry"
    (let [c (ctx :resolve-source
                 (fn [_id entry]
                   (cond-> entry
                     (= "${DB_JDBC_URL}" (:jdbc_url entry))
                     (assoc :jdbc_url "jdbc:sqlite:resolved.db"))))
          r (call c "describe_source" {:source "db-env"})]
      (is (empty? (errors r)) (pr-str (:findings r)))
      (is (= ["jdbc:sqlite:resolved.db"] (mapv :jdbc_url @describe-configs)))))

  (testing "add_thing's plan reads the relation through the SAME resolution"
    (reset! describe-configs [])
    (let [c (ctx :resolve-source (fn [_id entry] (assoc entry :jdbc_url "jdbc:sqlite:resolved.db")))
          r (call c "add_thing" {:name "customers" :from "customers" :namespace "shop.cust3" :graph "ws/g" :source "db-env"})]
      (is (empty? (errors r)) (pr-str (:findings r)))
      (is (= ["jdbc:sqlite:resolved.db"] (mapv :jdbc_url @describe-configs)))))

  (testing "without it (a test with no host), the declared entry passes through unchanged"
    (reset! describe-configs [])
    (let [r (call (ctx) "describe_source" {:source "db-env"})]
      (is (empty? (errors r)) (pr-str (:findings r)))
      (is (= ["${DB_JDBC_URL}"] (mapv :jdbc_url @describe-configs))))))

(deftest source-and-namespace-are-asked-for-never-invented-test
  (testing "several sources in the pool and none named: refused, not ranked"
    (let [r (intent/decide-source model {:namespaces #{} :attributes #{}} nil)]
      (is (= :tools.intent/source-undecidable (:code (first (:findings r)))))))
  (testing "namespaces that share a prefix give the convention"
    (is (= "shop.invoices" (:namespace (intent/decide-namespace members nil "invoices")))))
  (testing "namespaces that share nothing: asked for"
    (is (= :tools.intent/namespace-undecidable
           (:code (first (:findings (intent/decide-namespace
                                     {:namespaces #{"a.b" "c.d"}} nil "x"))))))))

;; ---------------------------------------------------------------------------
;; 裁定八 / 裁定九 — the one recipe tool that replaces update_recipe
;; ---------------------------------------------------------------------------

(deftest change-graph-members-changes-the-recipe-and-no-pool-object-test
  (let [before (count (pool/objects (pool/db *conn*)))
        _ (swap! *recipe* update :attributes disj :shop.orders/total)
        plan (call (ctx) "change_graph_members"
                   {:graph "ws/g" :add [":shop.orders/total"]})
        done (call (ctx) "change_graph_members"
                   {:graph "ws/g" :add [":shop.orders/total"] :apply true})]
    (is (empty? (errors plan)) (pr-str (:findings plan)))
    (is (empty? (:actions plan)) "a recipe edit runs no pool action (D3.19)")
    (is (= #{:shop.orders/total} (get-in plan [:recipe-delta :add :attributes])))
    (is (true? (:recipe-applied? done)))
    (is (contains? (:attributes @*recipe*) :shop.orders/total))
    (is (= before (count (pool/objects (pool/db *conn*)))) "no object was created")))

(deftest change-graph-members-never-deletes-the-object-test
  (let [done (call (ctx) "change_graph_members"
                   {:graph "ws/g" :remove [":shop.customers/cust_name"] :apply true})]
    (is (true? (:recipe-applied? done)))
    (is (not (contains? (:attributes @*recipe*) :shop.customers/cust_name)))
    (is (some? (pool/object (pool/db *conn*) :attribute :shop.customers/cust_name))
        "removing from a graph deletes nothing — remove_objects is the tool that does")))

(deftest change-graph-members-does-both-halves-in-one-plan-test
  "裁定九 merged `add_to_graph` and `remove_from_graph`: one edit of one list,
   said the way a person says it — these in, those out."
  (let [done (call (ctx) "change_graph_members"
                   {:graph "ws/g"
                    :add ["shop_orders_by_order_no"]
                    :remove [":shop.customers/cust_name"]
                    :apply true})]
    (is (empty? (errors done)) (pr-str (:findings done)))
    (is (contains? (:resolvers @*recipe*) "shop_orders_by_order_no"))
    (is (not (contains? (:attributes @*recipe*) :shop.customers/cust_name)))))

(deftest change-graph-members-refuses-a-call-that-names-nothing-test
  (let [r (call (ctx) "change_graph_members" {:graph "ws/g"})]
    (is (contains? (codes r) :tools.intent/blocked-plan))))

(deftest membership-takes-words-and-works-out-the-kind-test
  (let [r (call (ctx) "change_graph_members"
                {:graph "ws/g" :add ["cust_name" "shop_orders_by_order_no" "shop.orders"]})]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= {:attributes #{:shop.customers/cust_name}
            :resolvers #{"shop_orders_by_order_no"}
            :namespaces #{"shop.orders"}}
           (get-in r [:recipe-delta :add]))
        "three words, three different recipe groups, none of them named by the caller")))

(deftest membership-refuses-an-ambiguous-name-and-changes-nothing-test
  (let [before @*recipe*
        r (call (ctx) "change_graph_members"
                {:graph "ws/g" :add ["cust_no"] :apply true})]
    (is (contains? (codes r) :tools.intent/name-ambiguous))
    (is (= before @*recipe*) "an ambiguous name is never picked between")))

;; ---------------------------------------------------------------------------
;; 裁定八 — the four edit tools
;; ---------------------------------------------------------------------------

(deftest update-resolver-keeps-the-keys-it-was-not-told-about-test
  "The sharp edge this tool blunts: a resolver entry is REPLACED by the pool,
   so a patch through `execute_action` silently drops everything it omits."
  (let [args {:graph "ws/g" :resolver "shop_customers_by_cust_no"
              :limit {:default 10 :max 100}}
        plan (call (ctx) "update_resolver" args)
        done (call (ctx) "update_resolver" (assoc args :apply true))
        e (:pool/entry (pool/object (pool/db *conn*) :resolver "shop_customers_by_cust_no"))]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (true? (:written? done)) (pr-str (:findings done)))
    (is (= {:default 10 :max 100} (:limit e)))
    (testing "and everything it was not told about survived"
      (is (= [:shop.customers/cust_no] (:input e)))
      (is (str/includes? (get-in e [:source :query]) "FROM customers"))
      (is (= "single" (:result_type e))))))

(deftest update-resolver-merges-the-source-block-test
  (let [r (call (ctx) "update_resolver"
                {:graph "ws/g" :resolver "shop_customers_by_cust_no"
                 :query "SELECT cust_no, cust_name FROM customers WHERE cust_no IN (:cust_nos) AND active = 1"
                 :apply true})
        e (:pool/entry (pool/object (pool/db *conn*) :resolver "shop_customers_by_cust_no"))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (str/includes? (get-in e [:source :query]) "active = 1"))
    (is (= "sql" (get-in e [:source :type]))
        "changing the query does not take the rest of the source block with it")))

(deftest update-resolver-refuses-a-call-that-names-no-change-test
  (let [r (call (ctx) "update_resolver" {:graph "ws/g" :resolver "shop_customers_by_cust_no"})]
    (is (contains? (codes r) :tools.intent/blocked-plan))
    (is (empty? (:actions r)))))

(deftest update-attribute-changes-facts-not-words-test
  (let [args {:graph "ws/g" :attribute ":shop.orders/total" :deprecated true}
        plan (call (ctx) "update_attribute" args)
        done (call (ctx) "update_attribute" (assoc args :apply true))
        e (:pool/entry (pool/object (pool/db *conn*) :attribute :shop.orders/total))]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (true? (:deprecated? e)))
    (is (= :decimal (:type e)) "a merge patch: what was not named survived")
    (testing "the dialect's own refusal travels whole — this layer invents no second wording"
      (let [r (call (ctx) "update_attribute"
                    {:graph "ws/g" :attribute ":shop.orders/total"
                     :replaced_by ":shop.orders/order_no" :apply true})]
        (is (contains? (codes r) :dialect.invariant/replaced-by-invalid))
        (is (false? (:written? r)))))
    (testing "there is no label parameter — words go through describe_objects (D2.26)"
      (is (contains? (codes (call (ctx) "update_attribute"
                                  {:graph "ws/g" :attribute ":shop.orders/total"
                                   :label "金额"}))
                     :toolkit.args/malformed)))))

(deftest update-attribute-refuses-typing-a-join-test
  (call (ctx) "add_thing" {:name "customers" :from "customers" :namespace "shop.cust7" :graph "ws/g" :apply true})
  (let [r (call (ctx) "update_attribute" {:graph "ws/g"
                                          :attribute ":shop.cust7/all_customers"
                                          :type :string :apply true})]
    (is (contains? (codes r) :tools.intent/type-undecidable))
    (is (false? (:written? r)))))

(deftest describe-objects-defines-a-term-without-binding-test
  "裁定九 folded `define_term` into `describe_objects`: a term is an object
   whose words you are writing, and naming it here REGISTERS it."
  (let [args {:graph "ws/g"
              :objects [{:type :term :id "shop.party/order_no"
                         :label "订单号" :description "订单的稳定标识。"}]}
        plan (call (ctx) "describe_objects" args)
        done (call (ctx) "describe_objects" (assoc args :apply true))
        db (pool/db *conn*)]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (= "订单号" (:label (:pool/entry (pool/object db :term "shop.party/order_no")))))
    (testing "the term enters the graph's recipe — a bound term outside it fails the cut"
      (is (contains? (:terms @*recipe*) "shop.party/order_no")))
    (testing "nothing was bound: defining is glossary work, binding is assign_term"
      (is (nil? (:term (:pool/entry (pool/object db :attribute :shop.orders/order_no))))))
    (testing "editing an existing term goes through the same tool"
      (let [r (call (ctx) "describe_objects"
                    {:graph "ws/g"
                     :objects [{:type :term :id "shop.party/order_no"
                                :label "订单编号" :description "订单的稳定标识。"}]
                     :apply true})]
        (is (true? (:written? r)) (pr-str (:findings r)))
        (is (= "订单编号" (:label (:pool/entry (pool/object (pool/db *conn*) :term
                                                            "shop.party/order_no")))))))))

(deftest describe-objects-refuses-a-key-a-term-does-not-carry-test
  (let [r (call (ctx) "describe_objects"
                {:graph "ws/g"
                 :objects [{:type :term :id "shop.party/x" :label "x" :unit "USD"}]})]
    (is (contains? (codes r) :tools.intent/name-unresolved))
    (is (str/includes? (pr-str (:findings r)) "unit")
        "the refused key is named — a caller who sent it believes it was recorded")
    (is (empty? (:actions r))))
  (testing "a new term with no label is refused: the machine may not name a concept"
    (is (seq (errors (call (ctx) "describe_objects"
                           {:graph "ws/g"
                            :objects [{:type :term :id "shop.party/y"
                                       :description "…"}]})))))
  (testing "a term entry with no graph is refused rather than filed nowhere"
    (is (contains? (codes (call (ctx) "describe_objects"
                                {:objects [{:type :term :id "shop.party/z" :label "z"}]}))
                   :tools.intent/name-unresolved))))

(deftest describe-objects-changes-how-an-entity-describes-itself-test
  "裁定九 folded `update_entity` in too — a namespace IS the entity (D2.5), and
   what it carries is words, plus its `kind`."
  (let [args {:objects [{:type :namespace :id "shop.orders"
                         :label "销售订单" :synonyms ["订单"]}]}
        plan (call (ctx) "describe_objects" args)
        done (call (ctx) "describe_objects" (assoc args :apply true))
        e (:pool/entry (pool/object (pool/db *conn*) :namespace "shop.orders"))]
    (is (= (pr-str (:actions plan)) (pr-str (:actions done))))
    (is (= "销售订单" (:label e)))
    (is (= ["订单"] (:synonyms e)))
    (testing "an entity the pool does not hold is refused, not created"
      (is (contains? (codes (call (ctx) "describe_objects"
                                  {:objects [{:type :namespace :id "shop.nope" :label "x"}]
                                   :apply true}))
                     :tools.intent/name-unresolved)))))

(deftest describe-objects-writes-every-kind-in-one-transaction-test
  "The point of the merge: one call for the words, whatever the objects are."
  (let [r (call (ctx) "describe_objects"
                {:graph "ws/g"
                 :objects [{:type :attribute :id :shop.orders/total :label "订单金额"}
                           {:type :namespace :id "shop.orders" :label "销售订单"}
                           {:type :term :id "shop.party/amount" :label "金额"}]
                 :apply true})
        db (pool/db *conn*)]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "订单金额" (:label (:pool/entry (pool/object db :attribute :shop.orders/total)))))
    (is (= "销售订单" (:label (:pool/entry (pool/object db :namespace "shop.orders")))))
    (is (= "金额" (:label (:pool/entry (pool/object db :term "shop.party/amount")))))
    (is (= 3 (count (:revisions r))) "one transaction, three objects")))

;; ---------------------------------------------------------------------------
;; 8.4.19 裁定十三: the parent's child collection, and the anchorless list
;; ---------------------------------------------------------------------------

(deftest add-resolver-plans-a-parents-child-collection-test
  "The third shape. `orders` hangs off `shop.customers/cust_no` by its own
   `cust_no` column — the sentence «a customer's orders», which before this
   ruling could not be said on this face at all."
  (let [r (call (ctx) "add_relation" {:parent "shop.customers/cust_no" :child "shop.orders2" :via "cust_no" :from "orders" :graph "ws/g" :apply true})
        res (first (filter #(= :resolver (:type %)) (:actions r)))
        entry (:entry res)
        join (first (filter #(and (= :attribute (:type %))
                                  (= :shop.customers/orders2 (:id %)))
                            (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "the join attribute lives on the PARENT and targets the child"
      (is (some? join) (pr-str (:actions r)))
      (is (= {:target "shop.orders2" :cardinality :one-to-many} (:entry join))))
    (testing "the resolver is anchored on the parent key and batches"
      (is (= [:shop.customers/cust_no] (:input entry)))
      (is (= "list" (:result_type entry)))
      (is (= :shop.customers/orders2 (ffirst (first (:output entry)))))
      (is (= {:parent-key :shop.customers/cust_no :child-fk "cust_no"
              :placeholder "cust_nos"}
             (get-in entry [:source :batch])))
      (is (str/includes? (get-in entry [:source :query]) "WHERE cust_no IN (:cust_nos)")))
    (testing "an anchored list signs for nothing — D2.13 is about the unanchored one"
      (is (nil? (:curated? entry))))
    (testing "the recipe gained the join attribute and the child's columns"
      (is (contains? (:attributes @*recipe*) :shop.customers/orders2))
      (is (contains? (:attributes @*recipe*) :shop.orders2/total)))))

(deftest add-relation-one-refuses-an-endpoint-test
  "need-grammar-v1 B4: «has one» states a reference between things already
   modelled and fetches nothing, so an endpoint to read children from is an
   answer to a question it did not ask."
  (let [r (call (ctx) "add_relation" {:parent "shop.customers" :child "shop.orders"
                                      :kind :one :via "cust_no" :from "/orders"
                                      :graph "ws/g"})]
    (is (contains? (codes r) :tools.intent/mutually-exclusive) (pr-str (:findings r)))
    (is (empty? (:actions r)))))

(deftest add-resolver-refuses-an-unknown-foreign-key-with-candidates-test
  (let [r (call (ctx) "add_relation" {:parent "shop.customers/cust_no" :child "shop.orders2" :via "customer_no" :from "orders" :graph "ws/g"})
        e (first (filter #(= :tools.intent/unknown-column (:code %)) (errors r)))]
    (is (some? e) (pr-str (:findings r)))
    (is (= [:via] (:path e)))
    (is (= ["order_no" "cust_no" "total"] (:expected e))
        "the columns it does carry — a candidate list, not «no such column»")
    (is (empty? (:actions r)))))

(deftest update-resolver-refuses-a-list-without-an-anchor-test
  "8.4.19 ②: the shape the ruling's incident was hand-edited into — a list
   whose `:output` is flat, which the engine compiles to nothing. The plan
   refuses with the ENGINE's own code, because that is the code the summary
   and the publish check use for the same defect."
  (let [_ (call (ctx) "add_thing" {:name "orders" :from "orders" :namespace "shop.orders2" :graph "ws/g" :apply true})
        r (call (ctx) "update_resolver"
                {:graph "ws/g" :resolver "shop_orders2_all_orders"
                 :output [:shop.orders2/order_no :shop.orders2/total]})
        e (first (filter #(= :engine.compile/list-without-anchor (:code %)) (errors r)))]
    (is (some? e) (pr-str (:findings r)))
    (is (= [:resolvers "shop_orders2_all_orders" :output] (:path e)))
    (is (empty? (:actions r)))
    (testing "and the same edit keeping a single join root at the head is allowed"
      (let [ok (call (ctx) "update_resolver"
                     {:graph "ws/g" :resolver "shop_orders2_all_orders"
                      :output [{:shop.orders2/all_orders [:shop.orders2/order_no]}]})]
        (is (empty? (errors ok)) (pr-str (:findings ok)))))))

;; ---------------------------------------------------------------------------
;; The encoding is the tool's job (8.4.14 裁定六 principle 1), and a plan
;; validates what apply would write
;; ---------------------------------------------------------------------------

(deftest update-resolver-decodes-nested-attribute-references-test
  "The 2026-09-04 incident, verbatim: an agent declaring `:order` on a resolver
   was refused at APPLY with `:dialect.schema/invalid-shape` for every spelling
   of the attribute it tried, while the PLAN accepted all of them — so it fell
   back to ORDER BY in SQL and lost the caller-overridable window. Both
   spellings must now arrive as keywords, and `\"asc\"` as `:asc`."
  (doseq [[label allowed default]
          [["qualified" ["shop.customers/cust_name"] ["shop.customers/cust_name" "asc"]]
           ["local name" [":shop.customers/cust_name"] ["cust_name" "asc"]]]]
    (testing label
      (let [args {:graph "ws/g" :resolver "shop_customers_by_cust_no"
                  :order {:allowed allowed :default default}}
            plan (call (ctx) "update_resolver" args)
            done (call (ctx) "update_resolver" (assoc args :apply true))
            e (:pool/entry (pool/object (pool/db *conn*) :resolver "shop_customers_by_cust_no"))]
        (is (empty? (errors plan)) (pr-str (:findings plan)))
        (is (= (pr-str (:actions plan)) (pr-str (:actions done)))
            "apply re-planned from the same arguments and got the same actions")
        (is (true? (:written? done)) (pr-str (:findings done)))
        (is (= {:allowed [:shop.customers/cust_name]
                :default [:shop.customers/cust_name :asc]}
               (:order e)))))))

(deftest update-resolver-refuses-an-unknown-name-inside-order-test
  "A nested name gets the SAME four verdicts as a top-level one: it is refused
   at plan, with the near misses, and nothing is written."
  (let [r (call (ctx) "update_resolver"
                {:graph "ws/g" :resolver "shop_customers_by_cust_no"
                 :order {:allowed ["cust_nome"]}})
        e (first (filter #(= :tools.intent/name-unresolved (:code %)) (errors r)))]
    (is (some? e) (pr-str (:findings r)))
    (is (= [:order :allowed] (:path e)))
    (is (= "cust_nome" (:got e)) "the refusal names what was sent, unchanged")
    (is (empty? (:actions r))))

  (testing "and an ambiguous one refuses with every candidate, not a ranking"
    (let [r (call (ctx) "update_resolver"
                  {:graph "ws/g" :resolver "shop_customers_by_cust_no"
                   :order {:allowed ["cust_no"]}})]
      (is (contains? (codes r) :tools.intent/name-ambiguous) (pr-str (:findings r)))
      (is (empty? (:actions r))))))

(deftest update-resolver-decodes-eql-params-test
  (let [r (call (ctx) "update_resolver"
                {:graph "ws/g" :resolver "shop_customers_by_cust_no"
                 :eql_params {:cust_name {:attribute "shop.customers/cust_name" :op "eq"}}
                 :apply true})
        e (:pool/entry (pool/object (pool/db *conn*) :resolver "shop_customers_by_cust_no"))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= {:cust_name {:attribute :shop.customers/cust_name :op :eq}}
           (:eql-params e)))))

(deftest update-attribute-decodes-replaced-by-as-a-name-test
  "`:replaced-by` is an attribute reference like any other (D2.22); it took a
   fully qualified spelling only because nothing decoded it."
  (let [r (call (ctx) "update_attribute"
                {:graph "ws/g" :attribute ":shop.orders/cust_no"
                 :deprecated true :replaced_by "order_no" :apply true})
        e (:pool/entry (pool/object (pool/db *conn*) :attribute :shop.orders/cust_no))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= :shop.orders/order_no (:replaced-by e)))))

(deftest a-plan-is-refused-by-what-would-refuse-the-apply-test
  "Bug 2: the plan runs the pool's own dry path, so a shape apply would refuse
   is refused AT PLAN with the same finding — and the apply of the same call
   carries that finding too and writes nothing."
  (let [args {:graph "ws/g" :resolver "shop_customers_by_cust_no"
              ;; A window bound the dialect requires to be a positive integer.
              ;; The planner has no opinion about it; the pool does.
              :limit {:default 0 :max 100}}
        plan (call (ctx) "update_resolver" args)
        done (call (ctx) "update_resolver" (assoc args :apply true))]
    (is (contains? (codes plan) :dialect.schema/invalid-shape) (pr-str (:findings plan)))
    (is (= (pr-str (:actions plan)) (pr-str (:actions done)))
        "the refusal did not change what was planned")
    (is (false? (:written? done)))
    (is (contains? (codes done) :dialect.schema/invalid-shape)
        "apply gives the same code the plan gave")
    (is (= 1 (:pool/revision (pool/object (pool/db *conn*) :resolver "shop_customers_by_cust_no")))
        "nothing was written")))

;; ---------------------------------------------------------------------------
;; 补裁 2026-09-04 — the sample is N records, and inference merges them
;; ---------------------------------------------------------------------------

(deftest inference-merges-every-sampled-record-test
  ;; One record cannot show an optional field, a null, or a small value set.
  ;; A sample can, and each answer says how much of the sample it rests on.
  (let [records (vec (for [i (range 100)]
                       (cond-> {:id i :status (nth ["open" "closed" "void"] (mod i 3))}
                         (< i 30) (assoc :note (when (even? i) (str "n" i))))))
        c (ctx :probe-source (truncated-probe-fn records "$.items"))
        r (call c "add_thing" {:name "orders" :from "/orders" :namespace "shop.ord" :graph "ws/g" :source "api"})]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= 100 (:probe/records (:probe r))))
    (testing "the fill positions carry the merged evidence"
      (let [fill (:fill r)]
        ;; Every field of the union is a position, `note` included — it exists
        ;; in 30 of the 100 records and would be invisible in a sample of one.
        (is (some #(= "note" (second %)) fill))
        (is (some #(= "status" (second %)) fill))
        ;; A string field with few distinct values is a CODE-VALUE candidate,
        ;; by name and count — never by value (finding-v1 §5).
        (is (some #(and (= "status" (second %)) (= :code-values (nth % 2))) fill))
        (is (not (str/includes? (pr-str fill) "closed"))
            "the values themselves never reach a plan position")))
    (testing "and the attributes of the union are all registered"
      (is (some #(and (= :attribute (:type %)) (= :shop.ord/note (:id %))) (:actions r))
          "an optional field is in the model — a sample of one would have missed it"))))

(deftest a-field-the-sample-disagrees-about-is-unknown-test
  (let [c (ctx :probe-source (truncated-probe-fn [{:id 1 :amount 10}
                                                  {:id 2 :amount "ten"}]
                                                 "$.items"))
        r (call c "add_thing" {:name "orders" :from "/orders" :namespace "shop.ord2" :graph "ws/g" :source "api"})]
    (is (some #(and (= "amount" (second %)) (= "inconsistent" (last %))) (:fill r))
        "an inconsistent type is undecided and waits for a person, not guessed")))

(deftest sample-size-reaches-the-probe-test
  (let [c (ctx :probe-source (truncated-probe-fn [{:id 1}] "$.items"))
        _ (call c "add_thing" {:name "orders" :from "/orders" :namespace "shop.ord3" :graph "ws/g" :source "api" :sample_size 5})]
    (is (= 5 (:sample_size (:request (first @probe-calls)))))))

;; ---------------------------------------------------------------------------
;; declare_source (8.4.24 裁定十八)
;;
;; What is testable HERE is the FACE: which request the host's one service
;; receives, and what this layer refuses before reaching it. The three guarded
;; steps themselves — validate, health, write — are the host's, and they are
;; tested where a real connection can be opened (`apps/studio`).
;; ---------------------------------------------------------------------------

(def ^:private declare-calls (atom []))

(defn- declare-source-fn
  "The host's service, faked as far as the tool reaches into it."
  [& {:keys [written?] :or {written? true}}]
  (fn [request]
    (swap! declare-calls conj request)
    (cond-> {:source (:id request)
             :action {:action (if (:expected-revision request) :update :create)
                      :type :source :id (:id request)
                      :entry (:entry request)
                      :expected_revision (or (:expected-revision request) 0)}
             :fill []
             :health {:status :ok :latency-ms 3}
             :connected? true
             :findings []
             :written? false}
      (and (:apply? request) written?) (assoc :written? true :revision 1))))

(defn- with-declare [c] (assoc c :declare-source (declare-source-fn)))

(deftest declare-source-plans-without-applying-test
  (reset! declare-calls [])
  (let [r (call (with-declare (ctx)) "declare_source"
                {:id "warehouse" :entry {:type "sql" :jdbc_url "jdbc:sqlite:x.db"}})]
    (is (false? (:written? r)))
    (is (= 1 (count @declare-calls)))
    (is (false? (:apply? (first @declare-calls))) "a plan asks the host for a plan")
    (testing "`verify` defaults to the safe half (裁定十八)"
      (is (true? (:verify? (first @declare-calls)))))
    (testing "an omitted expected_revision is a CREATE, and the plan says so"
      (is (= :create (get-in r [:action :action])))
      (is (= 0 (get-in r [:action :expected_revision]))))))

(deftest declare-source-apply-replans-from-the-same-arguments-test
  (reset! declare-calls [])
  (let [args {:id "warehouse" :entry {:type "sql" :jdbc_url "jdbc:sqlite:x.db"}
              :expected_revision 4 :verify false}
        plan (call (with-declare (ctx)) "declare_source" args)
        applied (call (with-declare (ctx)) "declare_source" (assoc args :apply true))
        [planned re-planned] @declare-calls]
    (is (true? (:written? applied)))
    (is (= 2 (count @declare-calls)))
    (is (= (dissoc planned :apply?) (dissoc re-planned :apply?))
        "P7: apply re-plans from the same arguments, never from a handed-in action")
    (is (= [false true] [(:apply? planned) (:apply? re-planned)]))
    (is (false? (:verify? planned)) "verify=false travels to the host as given")
    (is (= 4 (:expected-revision planned)) "a given revision is an UPDATE of that revision")
    (is (= (:action plan) (:action applied)))))

(deftest declare-source-refuses-a-read-only-key-and-writes-nothing-test
  (reset! declare-calls [])
  (let [r (call (with-declare (ctx :scope #{:read})) "declare_source"
                {:id "warehouse" :entry {:type "sql" :jdbc_url "jdbc:sqlite:x.db"}
                 :apply true})]
    (is (false? (:written? r)))
    (is (contains? (codes r) :tools.intent/blocked-plan))
    (is (some? (:action r)) "the refusal carries the plan it would have written")
    (is (every? (complement :apply?) @declare-calls)
        "the host is never asked to write on a read-only key")))

(deftest declare-source-refuses-a-scalar-entry-before-the-host-test
  (reset! declare-calls [])
  (let [r (call (with-declare (ctx)) "declare_source" {:id "warehouse" :entry "sql"})]
    ;; The shape is the TOOLKIT's judgement, not a third vocabulary here — and
    ;; what the test is about is the COST: nothing reaches the host, so no
    ;; connection is opened for an entry that was never an entry.
    (is (contains? (codes r) :toolkit.args/invalid))
    (is (empty? @declare-calls) "a refusal at this layer costs no health check")))

(deftest declare-source-on-a-host-that-registers-no-sources-test
  (let [r (call (ctx) "declare_source" {:id "warehouse" :entry {:type "sql"}}) ]
    (is (contains? (codes r) :tools.source/unavailable))
    (is (nil? (:written? r)))))

;; ---------------------------------------------------------------------------
;; need-grammar-v1 — one test per sentence (8.4.25 裁定十七, doc 106 §106.7 P8b)
;;
;; The twelve sentences are a CONTRACT, so what is asserted here is the one
;; canonical shape each of them compiles to, and the determinism that makes a
;; plan reviewable at all: same words twice, same bytes.
;; ---------------------------------------------------------------------------

(deftest a1-add-thing-names-a-key-and-a-way-to-fetch-one-test
  (let [r (call (ctx) "add_thing" {:graph "ws/g" :name "orders" :from "orders"
                                   :identity "cust_no" :namespace "shop.byc"
                                   :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        key-attr (first (filter #(= :shop.byc/cust_no (:id %)) (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "A1: the field the person said recognises one of them is marked as the key"
      (is (true? (:identity? (:entry key-attr)))))
    (testing "and the way to fetch one is keyed on it"
      (is (= [:shop.byc/cust_no] (:input entry)))
      (is (= "single" (:result_type entry))))
    (testing "A2: every typeable field comes with it"
      (is (contains? (set (:output entry)) :shop.byc/total)))))

(deftest a-sentence-compiles-to-the-same-bytes-twice-test
  "need-grammar-v1 §4 invariant 1. The translator is a compiler, not a model:
   two identical sentences over one pool produce byte-identical plans."
  (doseq [[tool args]
          [["add_thing" {:graph "ws/g" :name "orders" :from "orders"
                         :identity "cust_no" :namespace "shop.det1"}]
           ["add_relation" {:graph "ws/g" :parent "shop.customers" :child "shop.det2"
                            :via "cust_no" :from "orders"}]
           ["add_metric" {:graph "ws/g" :thing "shop.orders" :name "order_total"
                          :expression "sum(total)" :dimensions ["cust_no"]}]]]
    (let [a (call (ctx) tool args)
          b (call (ctx) tool args)]
      (is (empty? (errors a)) (str tool " " (pr-str (:findings a))))
      (is (= (pr-str (:actions a)) (pr-str (:actions b))) tool)
      (is (= (pr-str (:recipe-delta a)) (pr-str (:recipe-delta b))) tool))))

(deftest a2-a-field-from-another-place-is-a-second-lookup-on-one-thing-test
  "need-grammar-v1 A2, doc 106 §106.13's first finding: eight name spaces cut
   by endpoint were really the fields of three things. Saying it this way, the
   second source lands on the SAME thing, keyed the same way."
  (let [r (call (ctx) "add_attribute" {:graph "ws/g" :thing "shop.customers"
                                       :name "total" :from "orders" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "a SECOND resolver, keyed on the thing's own key"
      (is (= [:shop.customers/cust_no] (:input entry)))
      (is (= "single" (:result_type entry))))
    (testing "and it produces the new field ONLY — the key keeps one producer"
      ;; §106.14 item 7 (owner 2026-09-07): a second by-key resolver that
      ;; re-produced the key earned `cut.structure/multiple-producers`, so the
      ;; contract moved rather than the check.
      (is (= #{:shop.customers/total} (set (:output entry)))))))

(deftest a3-a-computed-field-reads-no-source-test
  (let [r (call (ctx) "add_attribute" {:graph "ws/g" :thing "shop.customers"
                                       :name "display" :type :string
                                       :computed_from ["cust_no" "cust_name"]
                                       :expression "(str cust_no \" \" cust_name)"
                                       :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "derived" (get-in entry [:source :type])))
    (is (= [:shop.customers/cust_no :shop.customers/cust_name] (:input entry)))
    (is (= [:shop.customers/display] (:output entry)))
    (is (nil? (:source-ref entry)) "a derived resolver reaches no source")))

(deftest b1-sql-and-api-are-two-realizations-of-one-sentence-test
  (testing "sql: 8.4.19's three-piece set, batched on the child's own field"
    (let [r (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                       :child "shop.sqlkids" :via "cust_no"
                                       :from "orders" :apply true})
          entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (is (= "list" (:result_type entry)))
      (is (= [:shop.customers/cust_no] (:input entry)))
      (is (= {:parent-key :shop.customers/cust_no :child-fk "cust_no"
              :placeholder "cust_nos"}
             (get-in entry [:source :batch])))))
  (testing "api: an endpoint under the parent, and no batch block"
    (let [c (ctx :probe-source (probe-fn [{:id 1 :name "a"}]))
          r (call c "add_relation" {:graph "ws/g" :parent "shop.customers"
                                    :child "shop.apikids" :source "api"
                                    :from "/customers/{id}/orders" :sample "7"
                                    :apply true})
          entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (is (= "api" (get-in entry [:source :type])))
      ;; §106.12 item 7.1: probed with the sample, stored as api-v1's
      ;; attribute-qualified template — the PARENT's key, which is what a
      ;; child collection hangs under.
      (is (= "/customers/{shop.customers/cust_no}/orders"
             (get-in entry [:source :endpoint])))
      (is (nil? (get-in entry [:source :batch])) "an endpoint under the parent needs none")
      (is (= [:shop.customers/cust_no] (:input entry))))))

(deftest b1-over-sql-without-the-childs-field-is-refused-test
  (let [r (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                      :child "shop.nokids" :from "orders"})]
    (is (contains? (codes r) :tools.intent/unknown-column))
    (is (= [:via] (:path (first (errors r)))))
    (is (every? tools/finding? (:findings r)) "finding-v1 §2 closed key set")
    (is (empty? (:actions r)))))

(deftest b4-has-one-makes-no-reverse-collection-test
  "need-grammar-v1 B4: a reference, not a membership. An instance has a root
   group; a group does not have many instances."
  (let [r (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                      :child "shop.orders" :kind :one
                                      :via "cust_no" :apply true})
        attrs (filter #(= :attribute (:type %)) (:actions r))
        rs    (filter #(= :resolver (:type %)) (:actions r))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= 1 (count attrs)) (pr-str (:actions r)))
    (is (= :shop.orders/customers (:id (first attrs))))
    (is (= {:target "shop.customers" :cardinality :one-to-one} (:entry (first attrs))))
    (testing "nothing on the parent — no reverse collection was invented"
      (is (not-any? #(= "shop.customers" (namespace (:id %))) attrs)))
    (testing "the key is PRODUCED by the child's own resolver (106.14 第 5 条)"
      (is (= 1 (count rs)) (pr-str rs))
      (is (= :update (:action (first rs))) "an existing way of fetching orders, amended")
      (let [e (:entry (first rs))]
        (is (= {:shop.orders/customers [:shop.customers/cust_no]} (last (:output e)))
            "D2.16: one more branch on the tree, whose leaf is the parent's key")
        (is (= "cust_no" (get-in e [:source :field_mapping :shop.customers/cust_no]))
            "the parent's key is read from the foreign-key column")))
    (testing "and both attributes are in the recipe — no leaf outside the cut"
      (is (contains? (:attributes @*recipe*) :shop.orders/customers))
      (is (contains? (:attributes @*recipe*) :shop.customers/cust_no)))))

(deftest b4-without-a-resolver-that-reads-the-field-is-refused-test
  "106.14 第 5 条: no plan may create a join attribute no resolver produces —
   that graph is refused at the cut with `cut.attribute/unregistered-use`
   (D2.2). Say the thing first."
  (let [r (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                      :child "shop.orders" :kind :one
                                      :via "shop.orders/ref_no"})]
    (is (seq (errors r)))
    (is (= [:via] (:path (first (errors r)))))
    (is (every? tools/finding? (:findings r)))
    (is (empty? (:actions r)))))

(deftest b2-rides-along-with-b1-when-the-childs-field-is-known-test
  "doc 106 §106.3 B1 «f 存在时顺带生成 A/b», restored by §106.14 item 5: one
   sentence, one write, and the way back is produced by the same rows."
  (let [r (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                      :child "shop.sqlkids" :via "cust_no"
                                      :from "orders" :apply true})
        entry (:entry (first (filter #(and (= :resolver (:type %))
                                           (= :create (:action %)))
                                     (:actions r))))
        join  (last (get (first (:output entry)) :shop.customers/sqlkids))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "the collection's ROWS carry the branch, beside the child's fields"
      (is (= {:shop.sqlkids/customers [:shop.customers/cust_no]} join))
      (is (= "cust_no" (get-in entry [:source :field_mapping :shop.customers/cust_no]))))
    (testing "the child's own key back is in the recipe (invariant 3)"
      (is (contains? (:attributes @*recipe*) :shop.sqlkids/customers)))))

(deftest a4-a-metric-carries-the-contract-the-caller-never-typed-test
  (let [r (call (ctx) "add_metric" {:graph "ws/g" :thing "shop.orders"
                                    :name "order_total" :expression "sum(total)"
                                    :dimensions ["cust_no"] :label "订单总额"
                                    :apply true})
        m (first (filter #(= :metric (:type %)) (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "shop.orders" (:entity (:entry m))))
    (is (= {:contract :duckdb-spill/v1 :text "sum(total)"} (:expression (:entry m))))
    (is (= [:shop.orders/cust_no] (:dimensions (:entry m))))
    (testing "and it is named in the recipe — invariant 3, no pool object outside it"
      (is (contains? (:metrics @*recipe*) "order_total")))
    (testing "a dimension nobody registered is refused, not written"
      (let [bad (call (ctx) "add_metric" {:graph "ws/g" :thing "shop.orders"
                                         :name "other" :expression "sum(total)"
                                         :dimensions ["nope"]})]
        (is (seq (errors bad)))
        (is (every? tools/finding? (:findings bad)))
        (is (empty? (:actions bad)))))))

(deftest c4-a-concept-binding-carries-its-local-role-test
  (let [r (call (ctx) "define_concept"
                {:graph "ws/g" :concept "shop.party/acct"
                 :attributes [":shop.orders/cust_no"]
                 :definition {:label "账号" :description "结算账号。"}
                 :values {1 {:label "一"}}
                 :contexts {":shop.orders/cust_no" "buyer"}
                 :apply true})
        db (pool/db *conn*)
        attr (:pool/entry (pool/object db :attribute :shop.orders/cust_no))
        term (:pool/entry (pool/object db :term "shop.party/acct"))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "shop.party/acct" (:term attr)))
    (testing "C4: the role this binding plays HERE (D2.27)"
      (is (= "buyer" (:term-context attr))))
    (testing "C3: the codes live on the concept, once"
      (is (= {1 {:label "一"}} (:values term))))))

;; ---------------------------------------------------------------------------
;; doc 106 §106.12 items 5 and 6 — the write-face gaps measured on the E5 v8
;; rebuild (2026-09-06), fixed 2026-09-07
;; ---------------------------------------------------------------------------

(deftest a-thing-that-belongs-to-something-can-still-take-a-field-test
  "§106.12 item 5 ①: §106.14 item 5 puts the PARENT's key under a branch of the
   child's own by-key resolver, and counting branch leaves made that resolver
   straddle two namespaces — so a thing that belonged to something could never
   take another field. Only DIRECT outputs decide the namespace, which is the
   rule P8a's «produced-twice» already applied (`dialect/direct-output-keys`)."
  (let [rel (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                        :child "shop.orders" :kind :one
                                        :via "cust_no" :apply true})
        r   (call (ctx) "add_attribute" {:graph "ws/g" :thing "shop.orders"
                                         :name "ref" :column "total" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? rel)) (pr-str (:findings rel)))
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (not (contains? (codes r) :tools.intent/namespace-undecidable)))
    (testing "the field lands on the CHILD, beside its own fields"
      (is (contains? (set (:output entry)) :shop.orders/ref))
      (is (= :decimal (:type (:entry (first (filter #(= :attribute (:type %)) (:actions r)))))))
      (testing "and the «belongs to» branch is left where it was (D2.16)"
        (is (some #(and (map? %) (contains? % :shop.orders/customers)) (:output entry)))))))

(deftest a-keyed-api-thing-stores-the-template-it-was-probed-with-test
  "§106.12 item 5 ②: the endpoint a keyed api thing is fetched at is a
   template, and a template cannot be called. One real key is probed with and
   the model keeps `{namespace/key}` — no `update_resolver` second step."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :name "acme"}]))
        r (call c "add_thing" {:graph "ws/g" :name "procs" :namespace "shop.proc"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "the ONE call went out with the real key substituted"
      (is (= ["/processors/7"] (mapv #(get-in % [:request :path]) @probe-calls))))
    (testing "and what is stored is api-v1's attribute-qualified template"
      (is (= "/processors/{shop.proc/id}" (get-in entry [:source :endpoint])))
      (is (= [:shop.proc/id] (:input entry))))))

(deftest a-keyed-api-collection-endpoint-is-a-root-collection-test
  "§106.12 item 7.7 (fixed 2026-09-07): «all the bulletins, recognised by id»
   is A1 + a root collection. The endpoint has no `/{id}` to template, so
   there is no by-key resolver — but the key is still declared, and refusing
   the sentence forced a two-step `add_thing` + `update_attribute`."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :name "a"}]))
        r (call c "add_thing" {:graph "ws/g" :name "procs" :namespace "shop.proc2"
                               :identity "id" :from "/processors" :source "api"
                               :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        idattr (first (filter #(= :shop.proc2/id (:id %)) (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (empty? (:input entry)) "a root collection takes no key as input")
    (is (= "list" (:result_type entry)))
    (is (true? (:identity? (:entry idattr))) "the key is declared all the same")
    (is (= "/processors" (get-in entry [:source :endpoint])))))

(deftest a-templated-api-path-without-a-sample-is-refused-test
  (let [c (ctx :probe-source (probe-fn [{:id "7"}]))
        r (call c "add_thing" {:graph "ws/g" :name "procs" :namespace "shop.proc3"
                               :identity "id" :from "/processors/{id}" :source "api"})]
    (is (contains? (codes r) :tools.intent/probe-refused))
    (is (= [:sample] (:path (first (errors r)))))
    (is (empty? @probe-calls))
    (is (empty? (:actions r)))))

(deftest a-child-collection-over-api-keeps-to-the-childs-known-fields-test
  "§106.12 item 5 ③: a collection endpoint answers whatever the record holds,
   and registering all of it put camelCase junk in the pool beside the fields
   the child was modelled with. Over api the default is what the child thing
   already has; `attributes` names them explicitly."
  (testing "default: the child thing's already registered fields, and no others"
    (let [c (ctx :probe-source (probe-fn [{:order_no 1 :cust_no 2 :zIndex 9
                                           :versionedFlowState "x"}]))
          r (call c "add_relation" {:graph "ws/g" :parent "shop.customers"
                                    :child "shop.orders" :source "api"
                                    :from "/customers/{id}/orders" :sample "7"
                                    :apply true})
          entry (:entry (first (filter #(and (= :resolver (:type %))
                                             (= :create (:action %)))
                                       (:actions r))))
          rows  (set (get (first (:output entry)) :shop.customers/orders))]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (is (contains? rows :shop.orders/order_no))
      (is (not (contains? rows :shop.orders/zIndex)) "no camelCase junk from the endpoint")
      (is (not (contains? rows :shop.orders/versionedFlowState)))))
  (testing "`attributes` names them, and a new child still takes what it names"
    (let [c (ctx :probe-source (probe-fn [{:id 1 :name "a" :zIndex 9}]))
          r (call c "add_relation" {:graph "ws/g" :parent "shop.customers"
                                    :child "shop.apikids2" :source "api"
                                    :attributes ["id" "name"]
                                    :from "/customers/{id}/kids" :sample "7"
                                    :apply true})
          entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
          rows  (set (get (first (:output entry)) :shop.customers/apikids2))]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (is (= #{:shop.apikids2/id :shop.apikids2/name} rows)))))

(deftest a1-a-thing-may-come-from-a-hand-written-list-test
  "§106.12 item 6: A1's ⟨源⟩ may be a hand-written list (static-v1), and no
   tool could say it. The rows ship WITH the model — no connection entry, so
   no `:source-ref` — and the key becomes the list's `:match-on`."
  (let [r (call (ctx) "add_thing"
                {:graph "ws/g" :name "instances" :namespace "shop.inst"
                 :identity "host"
                 :rows [{:host "localhost" :env "dev"}
                        {:host "nifi-01.corp.local" :env "prod"}]
                 :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "static" (get-in entry [:source :type])))
    (is (nil? (:source-ref entry)) "a hand-written list is not somewhere else")
    (is (= :shop.inst/host (get-in entry [:source :match-on])))
    (is (= [{:shop.inst/env "dev" :shop.inst/host "localhost"}
            {:shop.inst/env "prod" :shop.inst/host "nifi-01.corp.local"}]
           (get-in entry [:source :data]))
        "rows keyed by the attribute ids they became")
    (is (true? (:identity? (:entry (first (filter #(= :shop.inst/host (:id %))
                                                  (:actions r)))))))
    (testing "without a key it is «all of them», and then there is no match-on"
      (let [r2 (call (ctx) "add_thing"
                     {:graph "ws/g" :name "envs" :namespace "shop.env"
                      :rows [{:code "dev"} {:code "prod"}]})
            e2 (:entry (first (filter #(= :resolver (:type %)) (:actions r2))))]
        (is (empty? (errors r2)) (pr-str (:findings r2)))
        (is (= "list" (:result_type e2)))
        (is (nil? (get-in e2 [:source :match-on])))))
    (testing "rows and a source say two things about where it comes from"
      (is (contains? (codes (call (ctx) "add_thing"
                                  {:graph "ws/g" :name "x" :namespace "shop.x"
                                   :from "customers" :rows [{:a 1}]}))
                     :tools.intent/mutually-exclusive)))
    (testing "and neither one is refused by name, not by a NullPointerException"
      (is (contains? (codes (call (ctx) "add_thing"
                                  {:graph "ws/g" :name "y" :namespace "shop.y"}))
                     :tools.intent/name-unresolved)))))

(deftest an-api-attribute-carries-its-field-mapping-test
  "§106.12 item 5 ④: an api resolver reads a field by JSONPath, so registering
   the attribute without the mapping left it unreadable until a second call."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :name "acme"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "procs" :namespace "shop.proc4"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        r (call c "add_attribute" {:graph "ws/g" :thing "shop.proc4"
                                   :name "run_status" :column "runStatus"
                                   :type :string :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "$.runStatus" (get-in entry [:source :field_mapping :shop.proc4/run_status])))))

(deftest c3-string-codes-survive-the-json-boundary-test
  "§106.12 item 5 ⑤: JSON has only string object keys, so a `:string`
   attribute's code list arrived as keywords and was refused outright by
   D2.27's type check. The types are read off the very attributes being bound
   — nothing else about how EDN reaches this layer is decided here."
  (let [r (call (ctx) "define_concept"
                {:graph "ws/g" :concept "shop.party/name_code"
                 :attributes [":shop.customers/cust_name"]
                 :definition {:label "名称码"}
                 :values {:RUNNING {:label "运行中"} :STOPPED {:label "已停"}}
                 :apply true})
        term (:pool/entry (pool/object (pool/db *conn*) :term "shop.party/name_code"))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= {"RUNNING" {:label "运行中"} "STOPPED" {:label "已停"}} (:values term)))
    (testing "an integer-keyed list on an integer attribute is untouched"
      (let [r2 (call (ctx) "define_concept"
                     {:graph "ws/g" :concept "shop.party/num_code"
                      :attributes [":shop.orders/order_no"]
                      :definition {:label "号码"}
                      :values {1 {:label "一"}} :apply true})
            t2 (:pool/entry (pool/object (pool/db *conn*) :term "shop.party/num_code"))]
        (is (true? (:written? r2)) (pr-str (:findings r2)))
        (is (= {1 {:label "一"}} (:values t2)))))))

;; ---------------------------------------------------------------------------
;; doc 106 §106.12 item 7 — the write-face gaps the v9 rebuild measured
;; (2026-09-07). Every one of them cost `update_resolver` calls or a refusal
;; that named a parameter the tool does not declare.
;; ---------------------------------------------------------------------------

(deftest a-second-source-over-api-is-templated-and-probed-once-test
  "7.1: `sample` belongs to every sentence that reads an api path UNDER a key,
   not just `add_thing`. Twelve of the v9 rebuild's `update_resolver` calls
   existed only to put the template back after a literal probe."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :runStatus "RUNNING"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "proc" :namespace "shop.proc"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        _ (reset! probe-calls [])
        r (call c "add_attribute" {:graph "ws/g" :thing "shop.proc"
                                   :name "runStatus"
                                   :from "/processors/{id}/state" :sample "7"
                                   :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= ["/processors/7/state"] (mapv #(get-in % [:request :path]) @probe-calls))
        "the ONE call went out with the real key substituted")
    (is (= "/processors/{shop.proc/id}/state" (get-in entry [:source :endpoint]))
        "and what is stored is api-v1's attribute-qualified template")
    (is (= [:shop.proc/id] (:input entry)))
    (testing "and it produces the new field only — the key keeps one producer (§106.14 item 7)"
      (is (= #{:shop.proc/runStatus} (set (:output entry)))))))

(deftest a-templated-second-source-without-a-sample-is-refused-test
  (let [c (ctx :probe-source (probe-fn [{:id "7"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "proc" :namespace "shop.proc"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        _ (reset! probe-calls [])
        r (call c "add_attribute" {:graph "ws/g" :thing "shop.proc"
                                   :name "run_status" :from "/processors/{id}/state"})]
    (is (contains? (codes r) :tools.intent/probe-refused))
    (is (= [:sample] (:path (first (errors r)))))
    (is (empty? @probe-calls) "refused before any request")))

(deftest a-second-sources-column-names-the-source-field-not-the-attribute-test
  "7.2: `column` was accepted and ignored — the field was matched by `name`,
   so a NiFi `runStatus` could only be registered under that spelling."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :runStatus "RUNNING"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "proc" :namespace "shop.proc"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        r (call c "add_attribute" {:graph "ws/g" :thing "shop.proc"
                                   :name "run_status" :column "runStatus"
                                   :from "/processors/{id}/state"
                                   :sample "7" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= #{:shop.proc/run_status} (set (:output entry))))
    (is (= "$.runStatus" (get-in entry [:source :field_mapping :shop.proc/run_status])))))

(deftest the-second-source-is-read-off-the-thing-not-the-graph-test
  "7.3: with two sources in the graph the tool refused at `:path [:source]` —
   a parameter `add_attribute` does not declare (8.4.25). The thing is already
   fetched from somewhere, and that somewhere is written on its resolvers."
  (let [c (ctx :probe-source (probe-fn [{:id 1 :name "a"}]))]
    (call c "add_thing" {:graph "ws/g" :name "apionly" :namespace "shop.apionly"
                         :from "/things" :source "api" :apply true})
    (let [r (call (ctx) "add_attribute" {:graph "ws/g" :thing "shop.customers"
                                         :name "total" :from "orders" :apply true})
          entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (is (= "db" (:source-ref entry)) "the source the customer is already fetched from"))))

(deftest a-self-relation-branch-carries-its-own-mapping-scope-test
  "§106.14 item 6 (owner 2026-09-07): «every group belongs to a parent group».
   Written flat, the parent's id mapped onto the SAME attribute kw as the
   group's own and the later write shadowed the identity."
  (let [c (ctx :probe-source (probe-fn [{:id "g2" :parent_id "g1" :name "ingest"}]))]
    (call c "add_thing" {:graph "ws/g" :name "grp" :namespace "shop.grp"
                         :identity "id" :from "/groups/{id}" :sample "g2"
                         :source "api" :apply true})
    (let [r (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.grp"
                                        :child "shop.grp" :kind :one
                                        :via "parent_id" :name "parent"
                                        :apply true})
          upd (first (filter #(and (= :resolver (:type %)) (= :update (:action %)))
                             (:actions r)))
          entry (:entry upd)]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (testing "the branch declares its own scope, the top-level identity is untouched"
        (is (= "$.id" (get-in entry [:source :field_mapping :shop.grp/id])))
        (is (= {:shop.grp/id "$.parent_id"}
               (get-in entry [:source :field_mapping :shop.grp/parent]))))
      (testing "and the branch is in the output tree"
        (is (some #(= {:shop.grp/parent [:shop.grp/id]} %) (:output entry)))))))

(deftest update-resolver-can-remove-a-source-key-test
  "7.4: the source block is MERGED, and the JSON face refuses an explicit
   null, so a key once written could never be taken back."
  (let [c (ctx :probe-source (probe-fn [{:id 1 :name "a"}]))]
    (call c "add_thing" {:graph "ws/g" :name "apionly2" :namespace "shop.apionly2"
                         :from "/things" :source "api" :data_path "$"
                         :apply true})
    (let [rid "shop_apionly2_all_apionly2"
          r (call (ctx) "update_resolver" {:graph "ws/g" :resolver rid
                                           :remove ["data_path"] :apply true})
          entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
      (is (true? (:written? r)) (pr-str (:findings r)))
      (is (not (contains? (:source entry) :data_path))))
    (testing "a key whose absence is not legal is refused, and the legal ones named"
      (let [r (call (ctx) "update_resolver" {:graph "ws/g"
                                             :resolver "shop_customers_by_cust_no"
                                             :remove ["query"]})]
        (is (contains? (codes r) :tools.intent/unknown-column))
        (is (= [:remove] (:path (first (errors r)))))
        (is (empty? (:actions r)))))))

;; ---------------------------------------------------------------------------
;; doc 106 §106.12 item 8 — the seven gaps the v10 rebuild measured (2026-09-07)
;; ---------------------------------------------------------------------------

(deftest a2-several-fields-from-one-endpoint-are-one-second-source-test
  "8.1: «the processor has fields from /flow/processors/{id}/status» said twice
   is ONE more way of fetching the thing, not two. The generated resolver id is
   derived from the namespace and the place, so the second sentence collided
   with the first on `name-ambiguous` and the caller had no way to name the
   resolver just made — `resolver` was declared and ignored."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :runStatus "RUNNING"
                                         :activeThreadCount 3}]))
        _ (call c "add_thing" {:graph "ws/g" :name "procs" :namespace "shop.p81"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        a (call c "add_attribute" {:graph "ws/g" :thing "shop.p81" :name "run_status"
                                   :column "runStatus" :from "/status/{id}"
                                   :sample "7" :apply true})
        b (call c "add_attribute" {:graph "ws/g" :thing "shop.p81" :name "threads"
                                   :column "activeThreadCount" :from "/status/{id}"
                                   :sample "7" :apply true})
        model (pool/as-dialect (pool/db *conn*))
        seconds (filter #(= "/status/{shop.p81/id}" (get-in % [:source :endpoint]))
                        (:resolvers model))
        entry (first seconds)]
    (is (true? (:written? a)) (pr-str (:findings a)))
    (is (true? (:written? b)) (pr-str (:findings b)))
    (is (= 1 (count seconds)) "one endpoint, one resolver")
    (is (= #{:shop.p81/run_status :shop.p81/threads} (set (:output entry))))
    (is (= {:shop.p81/run_status "$.runStatus"
            :shop.p81/threads "$.activeThreadCount"}
           (get-in entry [:source :field_mapping])))
    (testing "8.2: the second source does not produce the key a second time"
      (is (not (some #{:shop.p81/id} (:output entry))))
      (let [kinds (dialect/need-view model)]
        (is (empty? (filter #(and (= :produced-twice (:kind %))
                                  (str/starts-with? (:name %) "shop.p81/"))
                            (:fragments kinds)))
            (pr-str (:fragments kinds)))))))

(deftest a-batched-statement-selects-the-key-it-buckets-on-test
  "Measured on the 106.15 sql build, 2026-09-07: `align-batch` groups the flat
   rows by the `:child-fk` COLUMN it reads out of each row, so a statement that
   does not select it answers rows belonging to nobody. §3.3 (a second source's
   `:output` holds only the new attributes) is a rule about the ATTRIBUTE list;
   the SELECT list is a different list."
  (let [r (call (ctx) "add_attribute" {:graph "ws/g" :thing "shop.customers"
                                       :name "total" :from "orders"
                                       :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        q (get-in entry [:source :query])]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= [:shop.customers/total] (:output entry))
        "the key is not re-produced as an attribute (§3.3)")
    (is (= "cust_no" (get-in entry [:source :batch :child-fk])))
    (is (re-find #"(?i)SELECT\s+total,\s*cust_no\s+FROM" q) q)))

(deftest a2-second-source-honours-an-explicit-resolver-test
  "8.1's other half: `resolver` names the lookup to extend, rather than being
   accepted and ignored."
  (let [c (ctx :probe-source (probe-fn [{:id "7" :name "acme"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "procs" :namespace "shop.p82"
                               :identity "id" :from "/processors/{id}" :sample "7"
                               :source "api" :apply true})
        c2 (ctx :probe-source (probe-fn [{:id "7" :extra "x"}]))
        r (call c2 "add_attribute" {:graph "ws/g" :thing "shop.p82" :name "extra"
                                   :from "/other/{id}" :sample "7"
                                   :resolver "shop_p82_by_id" :apply true})
        entry (:pool/entry (pool/object (pool/db *conn*) :resolver "shop_p82_by_id"))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (some #{:shop.p82/extra} (:output entry))
        "the named resolver is the one that grew")))

(deftest a-collection-carries-its-query-filters-test
  "8.3: `params` are the query string of the ONE call this tool makes;
   `filters` are what a QUERY may narrow the collection by. The grammar says
   neither sentence — the tool carries the Pathom word so that saying it does
   not cost an `update_resolver`."
  (let [r (call (ctx) "add_thing"
                {:graph "ws/g" :name "orders" :from "orders" :namespace "shop.f83"
                 :filters {"cust" {"attribute" "cust_no" "op" "eq"
                                   "description" "only this customer's orders"}}
                 :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= {:cust {:attribute :shop.f83/cust_no :op :eq
                   :description "only this customer's orders"}}
           (:eql-params entry)))))

(deftest a-resolver-and-its-own-attributes-go-in-one-call-test
  "8.4: `:blocked-by` is computed against the pool MINUS the whole batch, so
   the resolver and the attributes only it produces are one call rather than
   three rounds. The order is decided here, not by the caller."
  (let [r (call (ctx) "remove_objects"
                {:graph "ws/g"
                 :objects ["shop_orders_by_order_no" ":shop.orders/total"]
                 :apply true})
        db (pool/db *conn*)]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (nil? (pool/object db :resolver "shop_orders_by_order_no")))
    (is (nil? (pool/object db :attribute :shop.orders/total)))
    (testing "an object named twice — once said, once cascaded — is deleted once"
      (is (= 1 (count (filter #(= [:attribute :shop.orders/total] [(:type %) (:id %)])
                              (:actions r))))))))

(deftest a-relation-says-where-its-key-lands-test
  "8.5: `parent` and `child` mean the SAME thing in both kinds; what differs is
   which of them owns the new key. The same pair said `many` and then `one`
   produces the parent's collection and the child's key back, with no swapping
   of arguments — and each plan's summary names the attribute and its
   namespace."
  (let [many (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                         :child "shop.orders" :via "cust_no"
                                         :apply true})
        one  (call (ctx) "add_relation" {:graph "ws/g" :parent "shop.customers"
                                         :child "shop.orders" :kind :one
                                         :via "cust_no" :name "buyer"})
        db (pool/db *conn*)]
    (is (true? (:written? many)) (pr-str (:findings many)))
    (testing "`many`: the collection lands on the PARENT"
      (is (some? (pool/object db :attribute :shop.customers/orders)))
      (is (some #(re-find #"^Add relation attribute shop\.customers/orders on shop\.customers"
                          %)
                (:summary many))
          (pr-str (:summary many))))
    (testing "`one`: the key back lands on the CHILD, same arguments"
      (is (empty? (errors one)) (pr-str (:findings one)))
      (is (= :shop.orders/buyer
             (:id (first (filter #(= :attribute (:type %)) (:actions one))))))
      (is (some #(re-find #"^Add relation attribute shop\.orders/buyer on shop\.orders — has one"
                          %)
                (:summary one))
          (pr-str (:summary one))))))

(deftest a-refusal-names-a-parameter-this-tool-declares-test
  "8.8: `plan-add-resolver` speaks `relation` / `input` / `outputs`, and no
   sentence tool declares any of them. finding-v1 §2 makes `:path` the position
   to FIX, so a refusal pointing at a parameter the caller never sent is one
   they cannot act on."
  (let [declared (fn [tool]
                   (set (map (comp keyword :name)
                             (:params (some #(when (= tool (:name %)) %)
                                            (tools/declarations))))))]
    (testing "add_thing: an unknown table is refused at `from`, not at `relation`"
      (let [r (call (ctx) "add_thing" {:graph "ws/g" :name "nope" :from "no_such_table"
                                       :namespace "shop.n88"})]
        (is (seq (errors r)))
        (is (every? #(contains? (declared "add_thing") (first (:path %))) (errors r))
            (pr-str (map :path (errors r))))))
    (testing "add_thing: a field the table does not carry is refused at `attributes`"
      (let [r (call (ctx) "add_thing" {:graph "ws/g" :name "customers" :from "customers"
                                       :namespace "shop.n88b" :attributes ["nope"]})]
        (is (= [:attributes] (:path (first (errors r)))))))
    (testing "add_attribute: the same defect is refused at `name`"
      (let [r (call (ctx) "add_attribute" {:graph "ws/g" :thing "shop.customers"
                                           :name "nope" :from "orders"})]
        (is (seq (errors r)))
        (is (every? #(contains? (declared "add_attribute") (first (:path %))) (errors r))
            (pr-str (map :path (errors r))))))))

;; ---------------------------------------------------------------------------
;; {+attr} — a key value that is itself a path (owner 2026-09-07,
;; doc 106 §106.14 item 8 / OQ-PATH-KEY-ENCODING)
;; ---------------------------------------------------------------------------

(deftest a-slashed-key-under-a-plain-placeholder-is-refused-test
  "The graph built, cut, and 404-ed on every keyed query, because `{attr}`
   encodes the whole value into one path segment. The write side refuses and
   points at api-v1's `{+attr}` rather than choosing it."
  (let [c (ctx :probe-source (probe-fn [{:full_name "apache/nifi"}]))
        r (call c "add_thing" {:graph "ws/g" :name "repos" :namespace "gh.repo"
                               :identity "full_name" :from "/repos/{full_name}"
                               :sample "apache/nifi" :source "api"})]
    (is (contains? (codes r) :tools.intent/placeholder-encoding))
    (is (= [:from] (:path (first (errors r)))))
    (testing "the refusal shows the spelling that works, and never the sample"
      (is (= "/repos/{+full_name}" (:expected (first (errors r)))))
      (is (not (str/includes? (pr-str (errors r)) "apache/nifi"))))
    (is (empty? @probe-calls))
    (is (empty? (:actions r)))))

(deftest a-reserved-placeholder-is-stored-as-given-test
  "`{+attr}` is accepted, probed with the real value, and qualified WITH the
   marker — dropping the `+` would silently re-encode the value."
  (let [c (ctx :probe-source (probe-fn [{:full_name "apache/nifi" :stars 1}]))
        r (call c "add_thing" {:graph "ws/g" :name "repos" :namespace "gh.repo2"
                               :identity "full_name" :from "/repos/{+full_name}"
                               :sample "apache/nifi" :source "api" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= ["/repos/apache/nifi"] (mapv #(get-in % [:request :path]) @probe-calls)))
    (is (= "/repos/{+gh.repo2/full_name}" (get-in entry [:source :endpoint])))
    (is (= [:gh.repo2/full_name] (:input entry)))))

;; ---------------------------------------------------------------------------
;; A host-templated :base_url (owner 2026-09-07, doc 106 §106.14 item 11)
;; ---------------------------------------------------------------------------

(deftest a-url-bound-key-need-not-be-echoed-by-the-records-test
  "«X has fields from another place» over a source whose HOST is the key.

   `/flow/about` answers a version and no `host` field, and before this ruling
   the planner refused the sentence with «this relation carries no typeable
   column by that name» — true of the record, and beside the point: the value
   is bound in the URL, not read out of the row."
  (let [c (ctx :probe-source (probe-fn [{:host "nifi-01" :name "local"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "instance" :namespace "nifi.instance"
                               :identity "host" :from "/instances/{host}"
                               :sample "nifi-01" :source "api-tpl" :apply true})
        c2 (ctx :probe-source (probe-fn [{:version "2.11.0" :build_tag "nifi-2.11.0"}]))
        r (call c2 "add_attribute" {:graph "ws/g" :thing "nifi.instance" :name "version"
                                    :from "/flow/about" :sample "nifi-01" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "the resolver is keyed on the attribute the URL binds"
      (is (= [:nifi.instance/host] (:input entry)))
      (is (= "single" (:result_type entry)))
      (is (= "/flow/about" (get-in entry [:source :endpoint]))))
    (testing "and nothing maps the key: it never came from the record"
      (is (= {:nifi.instance/version "$.version"} (get-in entry [:source :field_mapping])))
      (is (= [:nifi.instance/version] (:output entry))))
    (testing "the probe called ONE instance, named by `sample`"
      (is (= [{:nifi.instance/host "nifi-01"}]
             (mapv #(get-in % [:request :bindings])
                   (filter #(= "/flow/about" (get-in % [:request :path])) @probe-calls)))))
    (testing "the URL binding excuses the KEY and nothing else — a field the
              endpoint does not carry is still refused, and not by guessing a type"
      (let [c3 (ctx :probe-source (probe-fn [{:version "2.11.0"}]))
            bad (call c3 "add_attribute" {:graph "ws/g" :thing "nifi.instance" :name "nope"
                                          :from "/flow/about" :sample "nifi-01"})]
        (is (seq (errors bad)))
        (is (= :tools.intent/type-undecidable (:code (first (errors bad))))
            (pr-str (errors bad)))))))

(deftest a-thing-can-be-recognised-by-the-bound-host-and-its-own-key-test
  "«[host id]»: doc 106 §106.14 item 17's other consequence. A status record is
   the same id on two instances and two different records, so the host is part
   of what recognises one — and the host is bound in the URL, never echoed by
   the record. Before this the sentence was refused with «this relation carries
   no typeable column by that name», naming the very attribute the source is
   reached through."
  (let [c (ctx :probe-source (probe-fn [{:host "nifi-01"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "inst" :namespace "nifi.instance"
                               :identity "host" :from "/instances/{host}"
                               :sample "nifi-01" :source "api-tpl" :apply true})
        cs (ctx :probe-source (probe-fn [{:id "g1" :queued 7}]))
        r (call cs "add_thing" {:graph "ws/g" :name "gstatus" :namespace "nifi3.gstatus"
                                :identity ["nifi.instance/host" "id"]
                                :from "/groups/{id}/status" :source "api-tpl"
                                :sample {"nifi.instance/host" "nifi-01" "id" "g1"}
                                :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        made  (into #{} (map :id) (filter #(= :attribute (:type %)) (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "both parts are the lookup's :input, in the order they were said"
      (is (= [:nifi.instance/host :nifi3.gstatus/id] (:input entry))))
    (testing "the bound half registers no attribute — it is another thing's key"
      (is (not (contains? made :nifi3.gstatus/host)))
      (is (not (contains? made :nifi.instance/host)))
      (is (contains? made :nifi3.gstatus/id)))
    (testing "and it is mapped from nothing: it never came from the record"
      (is (nil? (get-in entry [:source :field_mapping :nifi.instance/host]))))
    (testing "the resolver id names the LOCAL half of each part (item 18): the
              qualified spelling would put a `.` and a `/` inside an id"
      (is (= "nifi3_gstatus_by_host_id" (:id entry))))
    (testing "«said» and «read back» are now the same sentence (item 18): the
              same key comes out of `need-view`, carried half included"
      (let [model (pool/as-dialect (pool/db *conn*))
            view  (dialect/need-view model (spi/carried-attributes model))
            t     (first (filter #(= "nifi3.gstatus" (:name %)) (:things view)))]
        (is (= ["nifi.instance/host" "nifi3.gstatus/id"] (:identity t)))
        (is (true? (:identity-declared? t)))))))

(deftest an-instance-with-templated-fields-can-have-one-of-something-test
  "Item 11.5: once the instance is a THING carrying the fields of its templated
   endpoints, «the instance has one root group» is an ordinary `kind one` — it
   was refused before, because the root record was a fragment with no identity."
  (let [c (ctx :probe-source (probe-fn [{:host "nifi-01"}]))
        _ (call c "add_thing" {:graph "ws/g" :name "inst" :namespace "nifi.instance"
                               :identity "host" :from "/instances/{host}"
                               :sample "nifi-01" :source "api-tpl" :apply true})
        cg (ctx :probe-source (probe-fn [{:id "g1" :name "root"}]))
        gr (call cg "add_thing" {:graph "ws/g" :name "group" :namespace "nifi2.group"
                                :identity "id" :from "/groups/{id}"
                                :sample "g1" :source "api-tpl" :apply true})
        _ (is (true? (:written? gr)) (pr-str (:findings gr)))
        ca (ctx :probe-source (probe-fn [{:root_group_id "g1"}]))
        _ (call ca "add_attribute" {:graph "ws/g" :thing "nifi.instance"
                                    :name "root_group_id" :from "/flow/process-groups/root"
                                    :sample "nifi-01" :apply true})
        r (call (ctx) "add_relation" {:graph "ws/g" :parent "nifi2.group"
                                      :child "nifi.instance" :kind :one
                                      :via "root_group_id" :name "root_group"
                                      :apply true})
        attr (first (filter #(and (= :attribute (:type %))
                                  (= :nifi.instance/root_group (:id %)))
                            (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= {:target "nifi2.group" :cardinality :one-to-one} (select-keys (:entry attr)
                                                                        [:target :cardinality])))))


(deftest a-child-collection-on-a-templated-source-is-keyed-on-the-host-too-test
  "§106.14 item 11 says EVERY resolver on a host-templated source takes the
   bound attribute as an `:input`. A child collection is the one keyed shape
   whose `:input` the caller never names — it is the parent's key — and it was
   the one shape that did not get it: `/groups/{id}/processors` went out with
   `{nifi.instance/host}` still in the URL, so every «has many» on such a
   source failed with `:mapping` (measured rebuilding nifi-local v12,
   2026-09-07)."
  (let [ci (ctx :probe-source (probe-fn [{:host "nifi-01" :name "local"}]))
        ;; The roster declares the attribute the URL binds; without it the
        ;; source is keyed on something nothing produces, and the planner says
        ;; so instead of writing a resolver that can never run.
        ri (call ci "add_thing" {:graph "ws/g" :name "inst" :namespace "nifi.instance"
                                 :identity "host" :from "/instances/{host}"
                                 :sample "nifi-01" :source "api-tpl" :apply true})
        _ (is (true? (:written? ri)) (pr-str (:findings ri)))
        c (ctx :probe-source (probe-fn [{:id "g1" :name "root"}]))
        rg (call c "add_thing" {:graph "ws/g" :name "group" :namespace "nifi3.group"
                                :identity "id" :from "/groups/{id}"
                                :sample {"nifi.instance/host" "nifi-01" "id" "g1"}
                                :source "api-tpl" :apply true})
        _ (is (true? (:written? rg)) (pr-str (:findings rg)))
        cp (ctx :probe-source (probe-fn [{:id "p1" :name "ControlRate"}]))
        r (call cp "add_relation" {:graph "ws/g" :parent "nifi3.group" :child "nifi3.processor"
                                   :from "/groups/{id}/processors"
                                   :sample {"nifi.instance/host" "nifi-01" "id" "g1"}
                                   :source "api-tpl" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= "list" (:result_type entry)))
    (is (= [:nifi3.group/id :nifi.instance/host] (:input entry))
        "the parent's key first, then what the connection binds")))

(deftest all-the-x-on-a-templated-source-is-a-child-collection-of-the-instance-test
  "«All the X» on a host-templated source means «all the X of THIS instance»
   (owner 2026-09-07, doc 106 §106.12 item 9.6; need-grammar-v1 §3.5 last
   bullet). Before this it was written as an `:input []` root collection whose
   URL kept its `{nifi.instance/host}` — a resolver that could never run, and
   only a validation query afterwards said `source execution failed`."
  (let [ci (ctx :probe-source (probe-fn [{:host "nifi-01" :name "local"}]))
        ri (call ci "add_thing" {:graph "ws/g" :name "inst" :namespace "nifi.instance"
                                 :identity "host" :from "/instances/{host}"
                                 :sample "nifi-01" :source "api-tpl" :apply true})
        _ (is (true? (:written? ri)) (pr-str (:findings ri)))
        cb (ctx :probe-source (probe-fn [{:id "b1" :message "queue full"}]))
        r (call cb "add_thing" {:graph "ws/g" :name "bulletin" :namespace "nifi4.bulletin"
                                :identity "id" :from "/flow/bulletin-board"
                                :sample "nifi-01" :source "api-tpl" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        join  (first (filter #(and (= :attribute (:type %)) (:target (:entry %)))
                             (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "the join attribute hangs on the thing that owns the bound attribute"
      (is (= :nifi.instance/all_bulletin (:id join)))
      (is (= {:target "nifi4.bulletin" :cardinality :one-to-many}
             (select-keys (:entry join) [:target :cardinality]))))
    (testing "a list keyed on the instance, not an unanchored root set"
      (is (= "list" (:result_type entry)))
      (is (= [:nifi.instance/host] (:input entry)))
      (is (nil? (:curated? entry)) "anchored on the instance; D2.13 asks nothing of it")
      (is (= [{:nifi.instance/all_bulletin
               [:nifi4.bulletin/id :nifi4.bulletin/message]}]
             (:output entry)))
      (is (some? (:limit entry))))
    (testing "the identity is still declared on the CHILD namespace"
      (is (true? (:identity? (:entry (first (filter #(= :nifi4.bulletin/id (:id %))
                                                    (:actions r))))))))
    (testing "the join attribute is in the recipe delta"
      (is (contains? (set (get-in r [:recipe-delta :add :attributes]))
                     :nifi.instance/all_bulletin)))
    (testing "the summary says whose bulletins these are"
      (is (some #(str/includes? % "all bulletin of one nifi.instance") (:summary r))
          (pr-str (:summary r))))))

(deftest all-the-x-on-a-templated-source-with-no-instance-yet-is-refused-test
  "Nothing owns `nifi.instance/host` yet, so there is nothing to hang the
   collection on — say the instance first (item 9.6)."
  (let [c (ctx :probe-source (probe-fn [{:id "b1" :message "queue full"}]))
        r (call c "add_thing" {:graph "ws/g" :name "bulletin" :namespace "nifi5.bulletin"
                               :identity "id" :from "/flow/bulletin-board"
                               :sample "nifi-01" :source "api-tpl"})]
    (is (seq (errors r)))
    (is (= :tools.intent/name-unresolved (:code (first (errors r)))) (pr-str (errors r)))
    (is (= ["nifi.instance/host"] (:got (first (errors r)))))
    (is (empty? (:actions r)))))

(deftest a-plain-source-still-produces-a-true-root-collection-test
  "The change is confined to templated sources: with nothing bound in the URL,
   «all the X» is the unanchored set it always was — `:input []`, `:curated?`,
   and the join attribute on the thing's own namespace."
  (let [c (ctx :probe-source (probe-fn [{:id "b1" :message "queue full"}]))
        r (call c "add_thing" {:graph "ws/g" :name "bulletin" :namespace "nifi6.bulletin"
                               :identity "id" :from "/flow/bulletin-board"
                               :source "api" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        join  (first (filter #(and (= :attribute (:type %)) (:target (:entry %)))
                             (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= :nifi6.bulletin/all_bulletin (:id join)))
    (is (= [] (:input entry)))
    (is (true? (:curated? entry)))
    (is (= "list" (:result_type entry)))
    (is (nil? (:bound-collection r)) "no marker on a plan that is not this shape")))
;; ---------------------------------------------------------------------------
;; Compound keys on the tool face (owner 2026-09-07, doc 106 §106.14 item 14)
;; ---------------------------------------------------------------------------

(deftest a1-identity-may-name-several-fields-test
  "«Recognised by namespace AND name» — the K8s shape. `identity` takes a
   list, every part is declared, the lookup takes all of them, and static's
   `:match-on` becomes the vector that matches on all of them at once."
  (let [r (call (ctx) "add_thing"
                {:graph "ws/g" :name "pods" :namespace "k8s.pod"
                 :identity ["namespace" "name"]
                 :rows [{:namespace "default" :name "web-1" :phase "Running"}
                        {:namespace "kube-system" :name "web-1" :phase "Pending"}]
                 :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        id-of (fn [a] (:identity? (:entry (first (filter #(= a (:id %)) (:actions r))))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (testing "both parts are declared the key — that IS the compound identity"
      (is (true? (id-of :k8s.pod/namespace)))
      (is (true? (id-of :k8s.pod/name)))
      (is (nil? (id-of :k8s.pod/phase))))
    (testing "the lookup is keyed on the whole of it"
      (is (= [:k8s.pod/namespace :k8s.pod/name] (:input entry)))
      (is (= "single" (:result_type entry)))
      (is (= [:k8s.pod/namespace :k8s.pod/name] (get-in entry [:source :match-on]))))
    (testing "and the plan says what recognises one, in words"
      (is (some #(str/includes? (str %) "recognised by namespace and name")
                (:summary r))
          (pr-str (:summary r))))))

(deftest a1-one-field-still-reads-as-one-field-test
  "The degenerate case is unchanged: a bare string, a scalar `:match-on`, and
   a summary that names one field."
  (let [r (call (ctx) "add_thing"
                {:graph "ws/g" :name "envs2" :namespace "shop.env2"
                 :identity "code" :rows [{:code "dev"}] :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= :shop.env2/code (get-in entry [:source :match-on])) "a keyword, not a vector")
    (is (some #(str/includes? (str %) "recognised by code") (:summary r)))))

(deftest add-relation-via-takes-one-field-per-part-of-the-key-test
  "«Every pod belongs to a namespace» where the parent's key is compound: the
   child needs one field per part, and a half-named key is refused rather than
   producing a branch that reaches no parent."
  (let [c (ctx)
        _ (call c "add_thing" {:graph "ws/g" :name "hosts" :namespace "k8s.host"
                               :identity ["zone" "hostname"]
                               :rows [{:zone "a" :hostname "h1"}] :apply true})
        r (call c "add_relation" {:graph "ws/g" :parent "k8s.host"
                                  :child "k8s.pod2" :kind :one :via "zone"})]
    (is (contains? (codes r) :tools.intent/type-undecidable) (pr-str (:findings r)))
    (is (= ["k8s.host/hostname" "k8s.host/zone"]
           (:expected (first (errors r))))
        "the refusal names the whole key, in the order list_entities reports it")))


;; ---------------------------------------------------------------------------
;; In-record collections — doc 106 §106.14 item 15 (owner 2026-09-07)
;; ---------------------------------------------------------------------------

(defn- with-group-and-processor
  "A group fetched by its own key, and a processor thing whose fields are
   already registered — the pool an in-record collection needs, since it has
   nothing to probe."
  [ns-g ns-p]
  (let [cg (ctx :probe-source (probe-fn [{:id "g1" :name "root"}]))
        rg (call cg "add_thing" {:graph "ws/g" :name "group" :namespace ns-g
                                 :identity "id" :from "/groups/{id}"
                                 :sample "g1" :source "api" :apply true})
        cp (ctx :probe-source (probe-fn [{:id "p1" :name "ControlRate"}]))
        rp (call cp "add_thing" {:graph "ws/g" :name "processor" :namespace ns-p
                                 :identity "id" :from "/processors/{id}"
                                 :sample "p1" :source "api" :apply true})]
    [rg rp]))

(deftest a-collection-inside-the-parents-own-record-test
  "«每个 B 有很多 A，就在它自己的记录里»: `from` written as a JSONPath. No
   endpoint of its own, so nothing is probed and no `sample` is asked for; the
   plan reuses the parent's by-key operation block and adds ONE branch to its
   `:field_mapping`."
  (let [[rg rp] (with-group-and-processor "rec.group" "rec.proc")
        _ (is (true? (:written? rg)) (pr-str (:findings rg)))
        _ (is (true? (:written? rp)) (pr-str (:findings rp)))
        before (count @probe-calls)
        r (call (ctx) "add_relation" {:graph "ws/g" :parent "rec.group" :child "rec.proc"
                                      :from "$.processors[*]" :source "api" :apply true})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        attr  (first (filter #(= :attribute (:type %)) (:actions r)))]
    (is (true? (:written? r)) (pr-str (:findings r)))
    (is (= before (count @probe-calls)) "an in-record collection calls nothing")
    (is (= {:target "rec.proc" :cardinality :one-to-many}
           (select-keys (:entry attr) [:target :cardinality])))
    (is (= :rec.group/proc (:id attr)))
    (testing "the parent's own endpoint, its key as :input, and the branch"
      (is (= "list" (:result_type entry)))
      (is (= [:rec.group/id] (:input entry)))
      (is (= "/groups/{rec.group/id}" (get-in entry [:source :endpoint])))
      (is (= {:_from "$.processors[*]"
              :rec.proc/id "$.id" :rec.proc/name "$.name"}
             (get-in entry [:source :field_mapping :rec.group/proc]))))
    (testing "no :limit — the host record bounds it (D2.8 exemption)"
      (is (nil? (:limit entry))))
    (testing "the branch and its leaves all reach the recipe"
      (is (= #{:rec.group/proc :rec.proc/id :rec.proc/name}
             (get-in r [:recipe-delta :add :attributes]))))
    (testing "the summary says where the rows come from"
      (is (some #(str/includes? % "in its own record") (:summary r))
          (pr-str (:summary r))))))

(deftest an-in-record-collection-narrows-to-named-fields-test
  (let [_ (with-group-and-processor "rec2.group" "rec2.proc")
        r (call (ctx) "add_relation" {:graph "ws/g" :parent "rec2.group" :child "rec2.proc"
                                      :from "$.processors[*]" :source "api" :name "processors"
                                      :attributes ["id" {:name "name" :path "$.component.name"}]})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= {:_from "$.processors[*]"
            :rec2.proc/id "$.id" :rec2.proc/name "$.component.name"}
           (get-in entry [:source :field_mapping :rec2.group/processors])))
    (testing "a field the thing is not registered with is refused, not guessed"
      (let [bad (call (ctx) "add_relation" {:graph "ws/g" :parent "rec2.group"
                                            :child "rec2.proc" :from "$.processors[*]" :source "api"
                                            :name "procs2" :attributes ["zIndex"]})]
        (is (= [:attributes] (:path (first (errors bad)))))
        (is (= :tools.intent/unknown-column (:code (first (errors bad)))))))))

(deftest an-in-record-collection-is-planned-deterministically-test
  (let [_ (with-group-and-processor "rec3.group" "rec3.proc")
        args {:graph "ws/g" :parent "rec3.group" :child "rec3.proc" :from "$.processors[*]" :source "api"}
        a (call (ctx) "add_relation" args)
        b (call (ctx) "add_relation" args)]
    (is (= (:actions a) (:actions b)))
    (is (= (:recipe-delta a) (:recipe-delta b)))))

(deftest one-producer-per-collection-test
  "«同一分支只允许一个产出者»: the same collection said twice — once from the
   record, once from an endpoint of its own — is refused NAMING the resolver
   that already produces it."
  (let [_ (with-group-and-processor "rec4.group" "rec4.proc")
        first-r (call (ctx) "add_relation" {:graph "ws/g" :parent "rec4.group"
                                           :child "rec4.proc" :from "$.processors[*]" :source "api"
                                           :apply true})
        _ (is (true? (:written? first-r)) (pr-str (:findings first-r)))
        again (call (ctx) "add_relation" {:graph "ws/g" :parent "rec4.group"
                                          :child "rec4.proc" :from "$.processors[*]" :source "api"})
        e (first (errors again))]
    (is (= :tools.intent/name-ambiguous (:code e)) (pr-str (errors again)))
    (is (= ["rec4_group_proc"] (:got e)))
    (is (empty? (:actions again)))))

;; --- doc 106 §106.12 items 10.2 / 10.3 / 10.4 -------------------------------

(deftest an-in-record-collection-carries-filters-test
  "10.2: `filters` reached `:eql-params` on the endpoint-backed branch and was
   dropped on this one — silently, so the narrowing only failed at query time
   as `unknown-param`."
  (let [_ (with-group-and-processor "rec6.group" "rec6.proc")
        r (call (ctx) "add_relation"
                {:graph "ws/g" :parent "rec6.group" :child "rec6.proc"
                 :from "$.processors[*]" :source "api"
                 :filters {"named" {"attribute" "name" "op" "eq"
                                    "description" "只看这个名字的"}}})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= {:named {:attribute :rec6.proc/name :op :eq :description "只看这个名字的"}}
           (:eql-params entry)))))

(deftest an-in-record-collection-types-its-child-from-the-parents-record-test
  "10.4: the elements of the array ARE the child's rows, so one read of the
   parent's own record types them — no scaffold `add_thing` for the child,
   said and then deleted."
  (let [cg (ctx :probe-source (probe-fn [{:id "g1" :name "root"}]))
        _  (call cg "add_thing" {:graph "ws/g" :name "group" :namespace "rec7.group"
                                 :identity "id" :from "/groups/{id}"
                                 :sample "g1" :source "api" :apply true})
        ;; The parent's record, re-read once: its `processors` array is what
        ;; the child is typed from.
        cp (ctx :probe-source (probe-fn {:processors [{:id "p1" :name "ControlRate"
                                                       :zIndex 3}]}))
        r  (call cp "add_relation"
                 {:graph "ws/g" :parent "rec7.group" :child "rec7.proc"
                  :from "$.processors[*]" :source "api" :sample "g1"})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        attrs (into {} (for [a (:actions r) :when (= :attribute (:type a))]
                         [(:id a) (:entry a)]))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (testing "the parent's OWN endpoint is what was called, with the sample key"
      (is (= "/groups/g1" (get-in (last @probe-calls) [:request :path])))
      (is (= "$.processors" (get-in (last @probe-calls) [:request :data_path]))))
    (testing "every field of an element is registered, typed from the sample"
      (is (= {:type :string} (get attrs :rec7.proc/id)))
      (is (= {:type :string} (get attrs :rec7.proc/name)))
      (is (= {:type :integer} (get attrs :rec7.proc/zIndex))))
    (testing "and the branch maps them relative to one element"
      (is (= {:_from "$.processors[*]"
              :rec7.proc/id "$.id" :rec7.proc/name "$.name"
              :rec7.proc/zIndex "$.zIndex"}
             (get-in entry [:source :field_mapping :rec7.group/proc]))))
    (testing "an inferred type is a guess and waits for a person (裁定七)"
      (is (seq (:fill r))))))

(deftest an-in-record-collection-narrows-a-probed-child-test
  (let [cg (ctx :probe-source (probe-fn [{:id "g1"}]))
        _  (call cg "add_thing" {:graph "ws/g" :name "group" :namespace "rec8.group"
                                 :identity "id" :from "/groups/{id}"
                                 :sample "g1" :source "api" :apply true})
        cp (ctx :probe-source (probe-fn {:processors [{:id "p1" :name "n" :zIndex 3}]}))
        r  (call cp "add_relation"
                 {:graph "ws/g" :parent "rec8.group" :child "rec8.proc"
                  :from "$.processors[*]" :source "api" :sample "g1"
                  :attributes ["id" "name"]})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= {:_from "$.processors[*]" :rec8.proc/id "$.id" :rec8.proc/name "$.name"}
           (get-in entry [:source :field_mapping :rec8.group/proc])))
    (testing "a `{name path}` entry names a value inside one element, as in add_thing"
      (let [r2 (call cp "add_relation"
                     {:graph "ws/g" :parent "rec8.group" :child "rec8.proc"
                      :from "$.processors[*]" :source "api" :sample "g1" :name "procs3"
                      :attributes [{:name "z_index" :path "$.zIndex"}]})
            e2 (:entry (first (filter #(= :resolver (:type %)) (:actions r2))))
            a2 (first (filter #(= :rec8.proc/z_index (:id %)) (:actions r2)))]
        (is (empty? (errors r2)) (pr-str (:findings r2)))
        (is (= :integer (get-in a2 [:entry :type])))
        (is (= {:_from "$.processors[*]" :rec8.proc/z_index "$.zIndex"}
               (get-in e2 [:source :field_mapping :rec8.group/procs3])))))
    (testing "a field the elements do not carry is refused, not guessed"
      (let [bad (call cp "add_relation"
                      {:graph "ws/g" :parent "rec8.group" :child "rec8.proc"
                       :from "$.processors[*]" :source "api" :sample "g1"
                       :name "procs2" :attributes ["nope"]})]
        (is (= :tools.intent/unknown-column (:code (first (errors bad)))))
        (is (= [:attributes] (:path (first (errors bad)))))))))

(deftest the-probe-locates-the-array-under-the-parents-data-path-test
  "`:_from` is relative to the RECORD, and the record is wherever the parent's
   own `data_path` put it — so the probe looks for the array at the two joined,
   not at the response root."
  (let [cg (ctx :probe-source (probe-fn {:component {:id "g1"}}))
        _  (call cg "add_thing" {:graph "ws/g" :name "group" :namespace "recA.group"
                                 :identity "id" :from "/groups/{id}" :data_path "$.component"
                                 :sample "g1" :source "api" :apply true})
        cp (ctx :probe-source (probe-fn {:component {:processors [{:id "p1"}]}}))
        r  (call cp "add_relation" {:graph "ws/g" :parent "recA.group" :child "recA.proc"
                                    :from "$.processors[*]" :source "api" :sample "g1"})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= "$.component.processors" (get-in (last @probe-calls) [:request :data_path])))
    (testing "what is STORED stays record-relative"
      (is (= "$.processors[*]"
             (get-in entry [:source :field_mapping :recA.group/proc :_from]))))))

(deftest an-in-record-field-may-be-renamed-by-its-path-test
  "A `{name path}` entry says WHERE its value is; the name is only what the
   attribute will be called (doc 106 §106.12 item 7.2, applied to the in-record
   branch). Matching such an entry by its NAME refused
   `{\"name\": \"auto_terminate\", \"path\": \"$.autoTerminate\"}` as an
   `unknown-column` — true of the element's keys, and beside the point
   (measured rebuilding nifi-local v15)."
  (let [cg (ctx :probe-source (probe-fn {:component {:id "p1"}}))
        _  (call cg "add_thing" {:graph "ws/g" :name "proc" :namespace "recR.proc"
                                 :identity "id" :from "/processors/{id}"
                                 :data_path "$.component" :sample "p1"
                                 :source "api" :apply true})
        cp (ctx :probe-source
                (probe-fn {:component {:relationships [{:name "failure"
                                                        :autoTerminate true}]}}))
        r  (call cp "add_relation" {:graph "ws/g" :parent "recR.proc"
                                    :child "recR.rel" :name "relationships"
                                    :from "$.relationships[*]" :source "api"
                                    :sample "p1"
                                    :attributes ["name"
                                                 {"name" "auto_terminate"
                                                  "path" "$.autoTerminate"}]})
        entry (:entry (first (filter #(= :resolver (:type %)) (:actions r))))
        made  (into {} (for [a (:actions r) :when (= :attribute (:type a))]
                         [(:id a) (:entry a)]))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (testing "the renamed field is mapped at the path it was given"
      (is (= "$.autoTerminate"
             (get-in entry [:source :field_mapping :recR.proc/relationships
                            :recR.rel/auto_terminate]))))
    (testing "and TYPED from the element the path points at, not left nil"
      (is (= :boolean (:type (get made :recR.rel/auto_terminate)))))))

(deftest an-in-record-collection-with-nothing-known-names-the-sample-test
  "10.4's refusal: with no sample and a child nothing declares, the way out is
   one real key of the PARENT — the refusal has to say so."
  (let [cg (ctx :probe-source (probe-fn [{:id "g1"}]))
        _  (call cg "add_thing" {:graph "ws/g" :name "group" :namespace "rec9.group"
                                 :identity "id" :from "/groups/{id}"
                                 :sample "g1" :source "api" :apply true})
        r  (call (ctx) "add_relation" {:graph "ws/g" :parent "rec9.group" :child "rec9.proc"
                                       :from "$.processors[*]" :source "api"})
        e  (first (errors r))]
    (is (= [:sample] (:path e)) (pr-str (:findings r)))
    (is (empty? (:actions r)))))

(deftest a-jsonpath-column-is-typed-from-the-probe-test
  "10.3: `add_attribute column` written as a JSONPath mapped correctly and was
   not typed — `add_thing`'s `{name path}` already types the same shape."
  (let [c1 (ctx :probe-source (probe-fn [{:id "p1" :name "ControlRate"}]))
        _  (call c1 "add_thing" {:graph "ws/g" :name "proc" :namespace "jp.proc"
                                 :identity "id" :from "/processors/{id}"
                                 :sample "p1" :source "api" :apply true})
        c2 (ctx :probe-source (probe-fn [{:id "p1" :runStatus "RUNNING"
                                          :status {:aggregateSnapshot {:queued 12}}}]))
        ;; The first sentence about this endpoint CREATES the second-source
        ;; resolver; the second one extends it — the branch where a JSONPath
        ;; column was mapped and left untyped.
        _  (call c2 "add_attribute" {:graph "ws/g" :thing "proc" :name "run_status"
                                     :from "/processors/{id}/status" :sample "p1"
                                     :column "$.runStatus" :apply true})
        r  (call c2 "add_attribute" {:graph "ws/g" :thing "proc" :name "queued"
                                     :from "/processors/{id}/status" :sample "p1"
                                     :column "$.status.aggregateSnapshot.queued"})
        attr (first (filter #(= :attribute (:type %)) (:actions r)))]
    (is (empty? (errors r)) (pr-str (:findings r)))
    (is (= :integer (get-in attr [:entry :type])))
    (is (= :jp.proc/queued (:id attr)))))

(deftest an-in-record-collection-refuses-what-it-cannot-mean-test
  (let [_ (with-group-and-processor "rec5.group" "rec5.proc")
        refuse (fn [args] (first (errors (call (ctx) "add_relation"
                                               (merge {:graph "ws/g" :parent "rec5.group"
                                                       :child "rec5.proc"
                                                       :from "$.processors[*]" :source "api"}
                                                      args)))))]
    (testing "rows already in the parent's record need no field pointing back"
      (is (= [:via] (:path (refuse {:via "group_id"})))))
    (testing "a JSON column of a database source is not read this way"
      (is (= [:from] (:path (first (errors (call (ctx) "add_relation"
                                                 {:graph "ws/g" :parent "shop.customers"
                                                  :child "shop.orders"
                                                  :source "sql"
                                                  :from "$.orders[*]"})))))))))

(deftest add-relation-refuses-an-unknown-relation-with-candidates-test
  "0.2 acceptance defect 9. Without `from`, `add_relation` guesses the table
   from the child's last segment; when the guess is wrong the refusal must name
   the tables that ARE there. It could not before: `structure-of` read the
   source with `:names [relation]`, so the description carried nothing to list
   and `:expected` came back empty — a refusal that withheld exactly the list
   the caller needed (finding-v1 «给出期望»)."
  (let [r (call (ctx) "add_relation" {:parent "shop.customers/cust_no"
                                      :child "shop.line" :via "cust_no"
                                      :graph "ws/g"})
        e (first (filter #(= :tools.intent/unknown-relation (:code %)) (errors r)))]
    (is (some? e) (pr-str (:findings r)))
    (is (= "line" (:got e)) "the guessed table name is said out loud")
    (is (= ["customers" "orders"] (:expected e))
        "and every relation the source does carry")
    (is (empty? (:actions r)))))

;; ---------------------------------------------------------------------------
;; «The same sentence said twice» — 0.2 acceptance defect 13, doc 84 §8.4.25
;; ---------------------------------------------------------------------------

(deftest add-thing-said-twice-is-the-same-resolver-test
  "build-graph-and-tokens.md §3: 「同一个来源说第二次是同一条 resolver，不是
   第三条」. Before this, the second apply wrote `…_by_<k>_from_<relation>`
   producing the SAME attributes, and the graph could no longer be cut
   (`:cut.structure/multiple-producers` on every one of them)."
  (let [args {:graph "ws/g" :name "orders" :from "orders" :identity "order_no"
              :namespace "shop.twice"}
        first* (call (ctx) "add_thing" (assoc args :apply true))
        again  (call (ctx) "add_thing" args)
        again! (call (ctx) "add_thing" (assoc args :apply true))]
    (is (true? (:written? first*)))
    (testing "the repeat plans nothing and names the resolver that is there"
      (is (= [] (:actions again)))
      (is (empty? (errors again)) (pr-str (:findings again)))
      (is (= "shop_twice_by_order_no" (:resolver (:existing again))))
      (is (= ["already said — nothing to write"] (:summary again))))
    (testing "and applying it writes nothing, without calling that an error"
      (is (false? (:written? again!)))
      (is (empty? (errors again!)) (pr-str (:findings again!)))
      (is (= 1 (count (filter #(str/starts-with? (:id %) "shop_twice")
                              (:resolvers (pool/as-dialect (pool/db *conn*))))))
          "no second producer of the same attributes"))))

(deftest add-thing-said-twice-differently-is-refused-test
  "Same source, same table, same key — a different attribute list. Writing it
   would make this tool the second author of the thing's key, so it is refused
   with the existing resolver named and a repair."
  (let [args {:graph "ws/g" :name "orders" :from "orders" :identity "order_no"
              :namespace "shop.twice2"}
        _ (call (ctx) "add_thing" (assoc args :apply true))
        r (call (ctx) "add_thing" (assoc args :attributes ["order_no" "cust_no"]))
        e (first (filter #(= :tools.intent/already-said (:code %)) (errors r)))]
    (is (some? e) (pr-str (:findings r)))
    (is (= "shop_twice2_by_order_no" (:got e)) "the resolver already there is named")
    (is (seq (:expected e)) "and the keys that differ")
    (is (= [] (:actions r)) "and the plan carries no action: the write is what the refusal stops")
    (is (= "update_resolver" (:tool (:repair e))))
    (is (= "shop_twice2_by_order_no" (:resolver (:args (:repair e)))))))

(deftest add-relation-said-twice-is-the-same-resolver-test
  "The same idempotence for the «has many» half of `add_relation`: one parent,
   one child, one source said twice is one child-collection resolver."
  (let [args {:graph "ws/g" :parent "shop.customers/cust_no" :child "shop.rel2"
              :via "cust_no" :from "orders"}
        first* (call (ctx) "add_relation" (assoc args :apply true))
        again  (call (ctx) "add_relation" args)]
    (is (true? (:written? first*)) (pr-str (:findings first*)))
    (is (= [] (:actions again)) (pr-str (:findings again)))
    (is (empty? (errors again)) (pr-str (:findings again)))
    (is (some? (:resolver (:existing again))))))

(deftest said-twice-into-another-graph-files-it-there-test
  "A repeat is still the way a modeller files objects the pool already holds
   into a graph that does not name them yet — the plan is recipe-only."
  (let [args {:graph "ws/g" :name "orders" :from "orders" :identity "order_no"
              :namespace "shop.twice3"}]
    (call (ctx) "add_thing" (assoc args :apply true))
    (reset! *recipe* members)
    (let [again (call (ctx) "add_thing" args)]
      (is (= [] (:actions again)))
      (is (seq (get-in again [:recipe-delta :add :attributes])))
      (is (= ["already in the pool · added to the graph"] (:summary again)))
      (let [done (call (ctx) "add_thing" (assoc args :apply true))]
        (is (true? (:recipe-applied? done)))
        (is (contains? (:namespaces @*recipe*) "shop.twice3"))))))

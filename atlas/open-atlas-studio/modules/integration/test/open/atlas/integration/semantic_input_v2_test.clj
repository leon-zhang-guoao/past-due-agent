(ns open.atlas.integration.semantic-input-v2-test
  "Semantic contract 2.0 §2 (doc 109 §109.8 items 1-2, owner 2026-09-18) end to
   end: one 2.0 document -- SQLite orders and items, a derived order total over a
   NESTED input, an OPTIONAL email filter on the order lookup -- compiled through
   `compile-candidate` and queried through the real engine, sql and derived
   connectors. The same fragments under a 1.4 descriptor are refused as a
   schema error rather than read flat (contract 2.0 §7)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.derived :as derived]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.contract :as version]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-contract :as contract]))

(def ^:private vocabulary
  {:entities {"order" {:entity/label "Order" :entity/identity [:order/id]
                       :entity/attributes [:order/id :order/status :order/email :order/items :order/total]}
              "item" {:entity/label "Item" :entity/identity [:item/id]
                      :entity/attributes [:item/id :item/qty]}}
   :attributes {:order/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :order/status {:attribute/label "Status" :attribute/type :string :attribute/nullable? false}
                :order/email {:attribute/label "Customer email" :attribute/type :string :attribute/nullable? true}
                :order/items {:attribute/label "Items" :attribute/type :ref :attribute/nullable? false
                              :attribute/target "item" :attribute/cardinality :many}
                :order/total {:attribute/label "Total quantity" :attribute/type :integer :attribute/nullable? false}
                :item/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :item/qty {:attribute/label "Quantity" :attribute/type :integer :attribute/nullable? false}}})

(def ^:private definitions
  {:resolvers {"order_lookup" {:label "Order by id, optionally narrowed by customer email"
                               :effect :read :result/cardinality :one
                               ;; ② an optional root: absent -> NULL in the statement (sql-v1).
                               :input [:order/id]
                               :params {:email {:type :string :description "Optional email filter"}}
                               :output [:order/status]}
               "order_items" {:label "Items of an order" :effect :read :result/cardinality :many
                              :input [:order/id] :output [{:order/items [:item/id :item/qty]}]
                              :pagination {:default 100 :max 100}
                              :sort {:allowed [:item/id] :default [:item/id :asc]}}
               "order_total" {:label "Total quantity" :effect :read :result/cardinality :one
                              ;; ③ a nested root: the planner resolves the items first.
                              :input [{:order/items [:item/qty]}] :output [:order/total]}}
   :operations {}
   :sources {"shop" {:source/kind "sql" :source/effects #{:read}}
             "calc" {:source/kind "derived" :source/effects #{:read}}}
   :bindings {"lookup-sql" {:binding/for [:resolver "order_lookup"] :binding/source "shop" :binding/effect :read
                            :binding/implementation {:query "SELECT status FROM orders WHERE id = :id AND (:email IS NULL OR email = :email)"}
                            :binding/input-map {:order/id "id"}
                            :binding/parameter-map {:email "email"}
                            :binding/output-map {:order/status "status"}}
              "items-sql" {:binding/for [:resolver "order_items"] :binding/source "shop" :binding/effect :read
                           :binding/implementation {:query "SELECT id, qty FROM items WHERE order_id = :id"}
                           :binding/input-map {:order/id "id"}
                           :binding/output-map {:order/items "items" :item/id "id" :item/qty "qty"}}
              "total-derived" {:binding/for [:resolver "order_total"] :binding/source "calc" :binding/effect :read
                               :binding/implementation {:expr "(reduce + 0 (map :item/qty items))"}
                               :binding/input-map {:order/items "items"}
                               :binding/output-map {:order/total "total"}}}
   :selection {[:resolver "order_lookup"] "lookup-sql"
               [:resolver "order_items"] "items-sql"
               [:resolver "order_total"] "total-derived"}})

(def ^:private adapters
  {"sql" (spi/semantic-adapter "sql")
   "derived" (spi/semantic-adapter "derived")})

(defn- with-shop-db [f]
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE orders (id INTEGER PRIMARY KEY, status TEXT, email TEXT)"])
        (jdbc/execute! connection ["CREATE TABLE items (id INTEGER PRIMARY KEY, order_id INTEGER, qty INTEGER)"])
        (jdbc/execute! connection ["INSERT INTO orders VALUES (1, 'open', 'a@b'), (2, 'closed', NULL)"])
        (jdbc/execute! connection ["INSERT INTO items VALUES (10, 1, 2), (11, 1, 3), (12, 2, 7)"])
        (f url)
        (finally (sql/close-all-pools!))))))

(deftest a-2-0-document-runs-optional-and-nested-inputs-through-the-real-connectors
  (with-shop-db
    (fn [url]
      (let [candidate (compiler/compile-candidate vocabulary definitions adapters {"shop" {:jdbc_url url}}
                                                  {:contract version/semantic-v2})
            q (fn [entity query]
                (engine/query (:compiled candidate) {:graph "shop" :version 1} {:entity entity :query query}))]
        (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
        (when (:compiled candidate)
          (testing "optional email: absent binds NULL, so the guard passes"
            (let [answer (q {:order/id 1} [:order/status])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= "open" (get-in answer [:engine/data :order/status])))))
          (testing "optional email: present, it narrows"
            (is (= "open" (get-in (q {:order/id 1} '[(:order/status {:email "a@b"})]) [:engine/data :order/status])))
            (let [miss (q {:order/id 1} '[(:order/status {:email "nobody"})])]
              (is (= :rejected (:engine/status miss)) (pr-str miss))
              (is (not (contains? (:engine/data miss) :order/status)) "a mismatching required scalar has no fabricated status")))
          (testing "nested items: the planner fetches the items, derived aggregates the vector"
            (let [answer (q {:order/id 1} [:order/total])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= 5 (get-in answer [:engine/data :order/total]))))
            (is (= 7 (get-in (q {:order/id 2} [:order/total]) [:engine/data :order/total]))))
          (testing "the 1.x-shaped consumers still see the flat roots"
            (is (= #{:order/id}
                   (spi/bound-attributes {:type "sql" :query (get-in definitions [:bindings "lookup-sql" :binding/implementation :query])
                                          :params {"id" :order/id "email" :email}
                                          :input [:order/id] :eql-params {:email {:type :string}}})))))))))

(def ^:private retired-1-4 {:contract/family "atlas-semantic" :contract/version "1.4.0"})

(deftest the-same-fragments-are-read-without-a-descriptor-and-refused-under-a-retired-one
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19).
  (with-shop-db
    (fn [url]
      (let [compile (fn [options] (compiler/compile-candidate vocabulary definitions adapters {"shop" {:jdbc_url url}} options))]
        (is (some? (:compiled (compile {}))) (pr-str (:findings (compile {}))))
        (let [result (compile {:contract retired-1-4})]
          (is (nil? (:compiled result)))
          (is (= [version/version-retired] (:findings result))))))))

(deftest the-2-0-document-carries-its-capability-through-the-contract
  (let [document {:contract version/semantic-v2
                  :workspace {:workspace/id "shop-dev"}
                  :graph {:graph/id "shop" :graph/label "Shop" :graph/exposure :internal
                          :graph/entities ["order" "item"]
                          :graph/resolvers ["order_lookup" "order_items" "order_total"]
                          :graph/operations [] :graph/dependencies [] :graph/terms []}
                  :vocabulary vocabulary
                  :execution definitions
                  :ownership {:entities {"order" "shop" "item" "shop"}
                              :resolvers {"order_lookup" "shop" "order_items" "shop" "order_total" "shop"}
                              :operations {} :terms {}}
                  :publication {:release/version "1.0.0" :source/revision 1
                                :required-capabilities #{:semantic/core-v1 :connector/sql :connector/derived
                                                         :graph/single-owner-v1 :deployment/source-slot-v1
                                                         :artifact/embedded-closure-v1 :query/pagination-v1
                                                         :query/sort-v1 :execution/input-v2 :execution/params-v2}}}
        checkers (update-vals adapters :validate)]
    (is (empty? (:findings (contract/validate-document document checkers)))
        (pr-str (:findings (contract/validate-document document checkers))))
    (is (some #(= :contract/capability-undeclared (:code %))
              (:findings (contract/validate-document
                          (update-in document [:publication :required-capabilities] disj :execution/input-v2)
                          checkers))))
    (is (= [version/version-retired]
           (:findings (contract/validate-document (assoc document :contract retired-1-4) checkers))))))

(ns open.atlas.integration.semantic-priority-v2-test
  "Semantic contract 2.0 §4 (doc 109 §109.8 item 4, owner 2026-09-18) end to
   end: one 2.0 document over SQLite in which a cache table (priority 10) and
   an ERP table (priority 0) produce the same customer attributes, compiled
   through `compile-candidate` and queried through the real engine and sql
   connector. The query answers from the cache; dropping the cache table makes
   the same query fall back to the ERP with the fallback info issue and the
   cache resolver's own failure on record, and the statement receipts (the
   audit's `:statements :by-resolver`) show the sequence actually run. A
   candidate whose row-security wiring fails refuses the whole cut, so a
   fallback cannot route around authorization."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.contract :as version]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.studio.version :as studio-version]))

(def ^:private vocabulary
  {:entities {"customer" {:entity/label "Customer" :entity/identity [:customer/id]
                          :entity/attributes [:customer/id :customer/name :customer/tier :customer/credit]}}
   :attributes {:customer/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :customer/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                :customer/tier {:attribute/label "Tier" :attribute/type :string :attribute/nullable? false}
                :customer/credit {:attribute/label "Credit" :attribute/type :integer :attribute/nullable? false}}})

(def ^:private definitions
  {:resolvers {"customer-by-cache" {:label "Customer from the cache table" :effect :read :result/cardinality :one
                                    :input [:customer/id] :output [:customer/name :customer/tier]
                                    :priority 10}
               "customer-by-erp" {:label "Customer from the ERP table" :effect :read :result/cardinality :one
                                  :input [:customer/id] :output [:customer/name :customer/tier :customer/credit]}}
   :operations {}
   :sources {"shop" {:source/kind "sql" :source/effects #{:read}}}
   :bindings {"cache-sql" {:binding/for [:resolver "customer-by-cache"] :binding/source "shop" :binding/effect :read
                           :binding/implementation {:query "SELECT name, tier FROM customer_cache WHERE id = :id"}
                           :binding/input-map {:customer/id "id"}
                           :binding/output-map {:customer/name "name" :customer/tier "tier"}}
              "erp-sql" {:binding/for [:resolver "customer-by-erp"] :binding/source "shop" :binding/effect :read
                         :binding/implementation {:query "SELECT name, tier, credit FROM customer_erp WHERE id = :id"}
                         :binding/input-map {:customer/id "id"}
                         :binding/output-map {:customer/name "name" :customer/tier "tier" :customer/credit "credit"}}}
   :selection {[:resolver "customer-by-cache"] "cache-sql"
               [:resolver "customer-by-erp"] "erp-sql"}})

(def ^:private adapters {"sql" (spi/semantic-adapter "sql")})
(def ^:private v2 {:contract version/semantic-v2})

(defn- with-shop-db [f]
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE customer_cache (id INTEGER PRIMARY KEY, name TEXT, tier TEXT)"])
        (jdbc/execute! connection ["CREATE TABLE customer_erp (id INTEGER PRIMARY KEY, name TEXT, tier TEXT, credit INTEGER)"])
        (jdbc/execute! connection ["INSERT INTO customer_cache VALUES (1, 'Acme (cache)', 'gold')"])
        (jdbc/execute! connection ["INSERT INTO customer_erp VALUES (1, 'Acme (erp)', 'gold', 100), (2, 'Globex', 'silver', 50)"])
        (f url connection)
        (finally (sql/close-all-pools!))))))

(defn- issues-of [answer code] (filter #(= code (:code %)) (:engine/issues answer)))

(deftest a-2-0-document-answers-from-the-cache-and-falls-back-to-the-erp
  (with-shop-db
    (fn [url connection]
      (let [candidate (compiler/compile-candidate vocabulary definitions adapters {"shop" {:jdbc_url url}} v2)
            receipts (atom [])
            q (fn [entity query]
                (reset! receipts [])
                (binding [spi/*statement-trace* #(swap! receipts conj %)]
                  (engine/query (:compiled candidate) {:graph "shop" :version 1} {:entity entity :query query})))]
        (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
        (when (:compiled candidate)
          (testing "the cache table answers, the ERP is never asked"
            (let [answer (q {:customer/id 1} [:customer/name :customer/tier])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= "Acme (cache)" (get-in answer [:engine/data :customer/name])))
              (is (nil? (:engine/issues answer)))
              (is (= ["customer-by-cache"] (mapv :resolver-id @receipts)))))
          (testing "an attribute only the ERP produces is planned there directly"
            (let [answer (q {:customer/id 1} [:customer/credit])]
              (is (= 100 (get-in answer [:engine/data :customer/credit])) (pr-str answer))
              (is (nil? (:engine/issues answer)))))
          (testing "a row the cache does not hold falls back to the ERP without a failure"
            (let [answer (q {:customer/id 2} [:customer/name])]
              (is (= "Globex" (get-in answer [:engine/data :customer/name])) (pr-str answer))
              (is (= 1 (count (issues-of answer :engine.execution/fallback))))
              (is (empty? (issues-of answer :engine.execution/failed)) "a miss is an answer, not a fault")))
          (testing "the cache table gone: the same query falls back, and both facts are on record"
            (jdbc/execute! connection ["DROP TABLE customer_cache"])
            (let [answer (q {:customer/id 1} [:customer/name :customer/tier])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= "Acme (erp)" (get-in answer [:engine/data :customer/name])))
              (is (not (:engine/partial? answer)) "a fallback is a complete answer")
              (let [fallbacks (issues-of answer :engine.execution/fallback)
                    failures (issues-of answer :engine.execution/failed)]
                (is (= #{[:customer/name] [:customer/tier]} (set (map :path fallbacks))) (pr-str (:engine/issues answer)))
                (is (every? #(= [:info "customer-by-erp" ["customer-by-cache"]]
                                [(:severity %) (:resolver-id %) (:tried %)]) fallbacks))
                (is (seq failures))
                (is (every? #(= [:error "customer-by-cache"] [(:severity %) (:resolver-id %)]) failures))
                (is (every? #(keyword? (:error/category %)) failures))
                (is (not-any? #(re-find #"customer_cache|SELECT|Acme" (pr-str %)) (:engine/issues answer))
                    "no statement text and no value leaves the engine (finding-v1)"))
              (testing "the audit's statement receipts carry the sequence actually run"
                (is (= ["customer-by-cache" "customer-by-erp"] (mapv :resolver-id @receipts)))
                (is (some? (:error-category (first @receipts))) "the failed attempt says why")
                (is (nil? (:error-category (second @receipts))))))))))))

(def ^:private retired-1-4 {:contract/family "atlas-semantic" :contract/version "1.4.0"})

(deftest the-same-fragments-are-read-without-a-descriptor-and-refused-under-a-retired-one
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19).
  (with-shop-db
    (fn [url _]
      (let [compile (fn [options] (compiler/compile-candidate vocabulary definitions adapters {"shop" {:jdbc_url url}} options))]
        (is (some? (:compiled (compile {}))) (pr-str (:findings (compile {}))))
        (let [result (compile {:contract retired-1-4})]
          (is (nil? (:compiled result)))
          (is (= [version/version-retired] (:findings result))))))))

(deftest an-unranked-overlap-is-a-tie-and-does-not-compile
  (with-shop-db
    (fn [url _]
      (let [result (compiler/compile-candidate vocabulary (update-in definitions [:resolvers "customer-by-cache"] dissoc :priority)
                                               adapters {"shop" {:jdbc_url url}} v2)]
        (is (nil? (:compiled result)))
        (is (= 2 (count (filter #(= :execution/priority-tie (:code %)) (:findings result)))) (pr-str (:findings result)))))))

(deftest the-2-0-document-carries-its-capability-through-the-contract
  (let [document {:contract version/semantic-v2
                  :workspace {:workspace/id "shop-dev"}
                  :graph {:graph/id "shop" :graph/label "Shop" :graph/exposure :internal
                          :graph/entities ["customer"]
                          :graph/resolvers ["customer-by-cache" "customer-by-erp"]
                          :graph/operations [] :graph/dependencies [] :graph/terms []}
                  :vocabulary vocabulary
                  :execution definitions
                  :ownership {:entities {"customer" "shop"}
                              :resolvers {"customer-by-cache" "shop" "customer-by-erp" "shop"}
                              :operations {} :terms {}}
                  :publication {:release/version "1.0.0" :source/revision 1
                                :required-capabilities #{:semantic/core-v1 :connector/sql
                                                         :graph/single-owner-v1 :deployment/source-slot-v1
                                                         :artifact/embedded-closure-v1 :execution/priority-v2}}}
        checkers (update-vals adapters :validate)]
    (is (empty? (:findings (contract/validate-document document checkers)))
        (pr-str (:findings (contract/validate-document document checkers))))
    (is (some #(= :contract/capability-undeclared (:code %))
              (:findings (contract/validate-document
                          (update-in document [:publication :required-capabilities] disj :execution/priority-v2)
                          checkers))))
    (is (= [version/version-retired]
           (:findings (contract/validate-document (assoc document :contract retired-1-4) checkers))))))

;; ---------------------------------------------------------------------------
;; Contract 2.0 §4.2: row security is checked per candidate
;; ---------------------------------------------------------------------------

(defn- read-model! [m]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str m))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:private empty-closure {:nodes {} :order [] :reference-attributes {}})

(defn- rowsec-candidates
  "Two ranked producers of a tenant-partitioned order, both declaring
   `:row-security`; `erp-query` decides whether the fallback binds the tenant."
  [erp-query]
  (read-model!
   {:attributes {:r.o/id        {:type :integer :format :id :identity? true :label "O"}
                 :r.o/tenant_id {:type :string :format :text :label "Tenant"}
                 :r.o/amount    {:type :decimal :format :currency :label "Amount"}}
    :namespaces {"r.o" {:kind :master :label "O"}}
    :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
    :resolvers  [{:id "o_by_cache" :input [:r.o/id :r.o/tenant_id] :output [:r.o/amount]
                  :result_type "single" :priority 10
                  :source {:type "sql" :query "SELECT amount FROM orders_cache WHERE id = :id AND tenant_id = :tenant_id"}
                  :source-ref "db" :row-security {:attribute :r.o/tenant_id}}
                 {:id "o_by_erp" :input [:r.o/id :r.o/tenant_id] :output [:r.o/amount]
                  :result_type "single"
                  :source {:type "sql" :query erp-query}
                  :source-ref "db" :row-security {:attribute :r.o/tenant_id}}]}))

(deftest a-candidate-that-fails-row-security-wiring-refuses-the-whole-cut
  (testing "the fallback candidate binds no tenant: the cut is refused on it, by name"
    (let [r (studio-version/health (rowsec-candidates "SELECT amount FROM orders_erp WHERE id = :id")
                                   empty-closure {})
          f (first (filter #(= :rowsec.wiring/not-bound (:code %)) (:findings r)))]
      (is (some? f) (pr-str (:findings r)))
      (is (= :error (:severity f)) "an error refuses the cut -- there is no version to fall back inside")
      (is (= [:resolvers "o_by_erp" :row-security] (:path f)) "the unwired candidate, not the graph")
      (is (= ["resolver:o_by_cache"] (:row-security-verified (:health r)))
          "only the wired candidate is verified; the D5.14 gate would refuse the closure")))
  (testing "both wired: the row-security check passes for both candidates"
    (let [r (studio-version/health (rowsec-candidates "SELECT amount FROM orders_erp WHERE id = :id AND tenant_id = :tenant_id")
                                   empty-closure {})]
      (is (not-any? #(= "rowsec.wiring" (namespace (:code %))) (:findings r)) (pr-str (:findings r)))
      (is (= ["resolver:o_by_cache" "resolver:o_by_erp"] (:row-security-verified (:health r))))
      ;; D2.11 amendment (owner 2026-09-18, contract 2.0 §4.1): two "single"
      ;; resolvers on one anchor are legal when their :priority values differ
      ;; (10 vs the default 0 here), so the cut no longer refuses the pair.
      (is (not-any? #(= :cut.structure/multiple-producers (:code %)) (:findings r))
          (pr-str (:findings r))))))

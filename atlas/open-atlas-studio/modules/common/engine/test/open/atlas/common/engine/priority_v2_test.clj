(ns open.atlas.common.engine.priority-v2-test
  "Semantic contract 2.0 §4 (doc 109 §109.8 item 4, owner 2026-09-18) at the
   engine seam: two resolvers produce the same attribute, the dialect
   `:priority` becomes `::pco/priority`, and the planner's OR node answers from
   the preferred one, falling back to the next when it fails or answers
   nothing. The fallback is reported from Pathom's own run-stats -- an
   `:engine.execution/fallback` info issue at the attribute path, plus the
   preferred resolver's failure as an `:engine.execution/failed` finding --
   without marking the answer partial. Both candidates failing is the 1.x
   refusal, untouched."
  (:require [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

(def ^:private behaviour
  "What the preferred `cache` source does on its next call."
  (atom :ok))
(def ^:private calls (atom []))

;; A test-only source type on the REAL SPI dispatch (same precedent as
;; `fake-connector`): the engine walks the same path it walks for sql.
(defmethod spi/compile-source "test-priority" [_source-config _connection {:keys [id]}]
  (fn [input-params _eql-params]
    (swap! calls conj id)
    (case id
      "cust_cache" (case @behaviour
                     :ok {:t.cust/name "Acme (cache)" :t.cust/tier "gold"}
                     :throw (spi/fail! :connection "cache is down")
                     :miss nil)
      "cust_erp" (case @behaviour
                   :both-fail (spi/fail! :source "erp is down")
                   {:t.cust/name "Acme (erp)" :t.cust/tier "gold" :t.cust/credit 100})
      "order_customers" [{:t.cust/id 1} {:t.cust/id 2}])))

(def ^:private source {:type "sql" :query "SELECT 1"})

(def ^:private model-text
  (pr-str
   {:namespaces {"t.cust" {:label "Customer"} "t.order" {:label "Order"}}
    :attributes {:t.cust/id {:type :integer :identity? true :format :id :label "ID"}
                 :t.cust/name {:type :string :format :text :label "Name"}
                 :t.cust/tier {:type :string :format :text :label "Tier"}
                 :t.cust/credit {:type :integer :format :number :label "Credit"}
                 :t.order/id {:type :integer :identity? true :format :id :label "Order"}
                 :t.order/customers {:cardinality :one-to-many :target "t.cust" :label "Customers"}}
    :sources {"src" {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}}
    :resolvers [;; 2.0 §4.4 lowering: the cache ranks above the ERP.
                {:id "cust_cache" :input [:t.cust/id] :output [:t.cust/name :t.cust/tier]
                 :result_type "single" :priority 10 :source source :source-ref "src"}
                {:id "cust_erp" :input [:t.cust/id] :output [:t.cust/name :t.cust/tier :t.cust/credit]
                 :result_type "single" :source source :source-ref "src"}
                {:id "order_customers" :input [:t.order/id]
                 :output [{:t.order/customers [:t.cust/id]}]
                 :result_type "list" :limit {:default 10 :max 10}
                 :source source :source-ref "src"}]}))

(def ^:private compiled
  (delay
    (let [{:keys [model findings]} (dialect/read-dialect model-text)
          _ (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
          model (-> model
                    (update :sources update-vals #(assoc % :type "test-priority"))
                    (update :resolvers (fn [rs] (mapv #(assoc-in % [:source :type] "test-priority") rs))))
          result (engine/compile-complete model)]
      (assert (:compiled result) (pr-str (:findings result)))
      (:compiled result))))

(defn- pco-priority [id]
  (-> @compiled :engine/bundle :env ::pci/index-resolvers
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config ::pco/priority))

(defn- ask
  ([query] (ask {:t.cust/id 1} query))
  ([entity query]
   (reset! calls [])
   (engine/query @compiled {:graph "t" :version 1} {:entity entity :query query})))

(defn- issues-of [answer code] (filter #(= code (:code %)) (:engine/issues answer)))

(deftest the-dialect-reader-accepts-priority-and-the-engine-writes-pco-priority
  (let [{:keys [model findings]} (dialect/read-dialect model-text)
        by-id (into {} (map (juxt :id identity)) (:resolvers model))]
    (is (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    (is (= 10 (get-in by-id ["cust_cache" :priority])))
    (is (not (contains? (get by-id "cust_erp") :priority)) "absent, not 0"))
  (is (= 10 (pco-priority "cust_cache")))
  (is (nil? (pco-priority "cust_erp")))
  (testing "the preferred resolver's declarations win the shared key in :param-decls"
    (is (= "cust_cache" (get-in @compiled [:engine/bundle :param-decls :t.cust/name :resolver-id])))
    (is (= "cust_erp" (get-in @compiled [:engine/bundle :param-decls :t.cust/credit :resolver-id]))
        "a key only one of them produces keeps its own producer")))

(deftest the-preferred-candidate-answers-and-nothing-falls-back
  (reset! behaviour :ok)
  (let [a (ask [:t.cust/name])]
    (is (= :ok (:engine/status a)) (pr-str a))
    (is (= "Acme (cache)" (get-in a [:engine/data :t.cust/name])))
    (is (= ["cust_cache"] @calls) "the ERP is never asked")
    (is (nil? (:engine/issues a)))
    (is (not (:engine/partial? a)))))

(deftest a-failing-preferred-candidate-falls-back-and-both-facts-are-reported
  (reset! behaviour :throw)
  (let [a (ask [:t.cust/name])]
    (is (= :ok (:engine/status a)) (pr-str a))
    (is (= "Acme (erp)" (get-in a [:engine/data :t.cust/name])))
    (is (= ["cust_cache" "cust_erp"] @calls) "tried in priority order, each once")
    (testing "the fallback is an info issue at the attribute path, naming resolver ids only"
      (let [[f :as fs] (issues-of a :engine.execution/fallback)]
        (is (= 1 (count fs)) (pr-str (:engine/issues a)))
        (is (= :info (:severity f)))
        (is (= [:t.cust/name] (:path f)))
        (is (= "cust_erp" (:resolver-id f)))
        (is (= ["cust_cache"] (:tried f)))
        (is (not (re-find #"Acme|down" (:message f))) "no values, no upstream text")))
    (testing "the preferred resolver's failure stays on record by the 1.x rule"
      (let [[f :as fs] (issues-of a :engine.execution/failed)]
        (is (= 1 (count fs)) (pr-str (:engine/issues a)))
        (is (= :error (:severity f)))
        (is (= [:t.cust/name] (:path f)))
        (is (= "cust_cache" (:resolver-id f)))
        (is (= :connection (:error/category f)))))
    (testing "a fallback is not partiality -- the answer is complete"
      (is (not (:engine/partial? a)))
      (is (= :unknown (:engine/completeness a))))))

(deftest a-preferred-candidate-that-answers-nothing-falls-back-without-a-failure
  (reset! behaviour :miss)
  (let [a (ask [:t.cust/name :t.cust/tier])]
    (is (= :ok (:engine/status a)) (pr-str a))
    (is (= "Acme (erp)" (get-in a [:engine/data :t.cust/name])))
    (is (= ["cust_cache" "cust_erp"] @calls))
    (is (= [[:t.cust/name] [:t.cust/tier]] (sort (map :path (issues-of a :engine.execution/fallback))))
        "one fallback per attribute the OR node was expected to give")
    (is (empty? (issues-of a :engine.execution/failed)) "a miss is an answer, not a fault")))

(deftest both-candidates-failing-is-the-1x-refusal
  (reset! behaviour :both-fail)
  (let [a (ask [:t.cust/name])]
    (reset! behaviour :ok)
    (is (= :rejected (:engine/status a)) (pr-str a))
    (is (= :engine.execution/failed (:code a)))
    (is (= ["cust_cache" "cust_erp"] @calls) "each candidate ran exactly once -- a fallback is not a retry")))

(deftest a-fallback-inside-a-collection-is-reported-per-row
  (reset! behaviour :throw)
  (let [a (ask {:t.order/id 7} [{:t.order/customers [:t.cust/name]}])]
    (reset! behaviour :ok)
    (is (= :ok (:engine/status a)) (pr-str a))
    (is (= ["Acme (erp)" "Acme (erp)"] (mapv :t.cust/name (get-in a [:engine/data :t.order/customers]))))
    (is (= [[:t.order/customers 0 :t.cust/name] [:t.order/customers 1 :t.cust/name]]
           (sort (map :path (issues-of a :engine.execution/fallback))))
        "the same ordinal paths lenient issues use")))

(deftest a-single-producer-model-reports-no-fallback
  ;; 1.x behaviour: an OR node with one path taken is not a fallback.
  (reset! behaviour :ok)
  (let [a (ask [:t.cust/credit])]
    (is (= 100 (get-in a [:engine/data :t.cust/credit])) (pr-str a))
    (is (nil? (:engine/issues a)))))

(deftest the-need-view-treats-two-producers-of-one-attribute-as-the-ordinary-shape
  ;; Task book §2.2: recorded, not changed. The need view already accepts the
  ;; same attribute reachable through several resolvers (the demo model has 28
  ;; such attributes), so two ranked producers are no fragment either.
  (let [{:keys [model]} (dialect/read-dialect model-text)
        view (dialect/need-view model)]
    (is (empty? (filter #(= :error (:severity %)) (:findings view))) (pr-str (:findings view)))
    (is (not-any? #(re-find #"cust_cache|cust_erp" (pr-str %)) (:fragments view))
        "neither candidate is reported as a fragment")))

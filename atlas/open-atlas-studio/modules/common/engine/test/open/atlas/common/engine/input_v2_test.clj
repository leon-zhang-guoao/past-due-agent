(ns open.atlas.common.engine.input-v2-test
  "Semantic contract 2.0 §2 (doc 109 §109.8 items 1-2, owner 2026-09-18) at the
   engine seam: the dialect model keeps `:input` flat and carries
   `:input-optional` / `:input-nested`; only the `::pco/input` construction
   reads them. The planner then behaves as Pathom documents -- an unreachable
   optional input leaves the key ABSENT, a nested input arrives as a vector of
   maps holding exactly the requested children -- and the connector sees the
   input map that §2.3 promises."
  (:require [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

(def ^:private seen (atom []))

;; A test-only source type on the REAL SPI dispatch (same precedent as
;; `fake-connector`): the engine walks the same path it walks for sql.
(defmethod spi/compile-source "test-input-v2" [_source-config _connection {:keys [id]}]
  (fn [input-params _eql-params]
    (swap! seen conj [id input-params])
    (case id
      "acct_lines" [{:t.line/id 1 :t.line/qty 2} {:t.line/id 2 :t.line/qty 3}]
      "acct_total" {:t.acct/total (reduce + (map :t.line/qty (:t.acct/lines input-params)))}
      "acct_greet" {:t.acct/greeting (str "hi " (:t.acct/name input-params "anon"))})))

(def ^:private source {:type "sql" :query "SELECT 1"})

(def ^:private model-text
  (pr-str
   {:namespaces {"t.acct" {:label "Account"} "t.line" {:label "Line"}}
    :attributes {:t.acct/id {:type :integer :identity? true :format :id :label "ID"}
                 :t.acct/name {:type :string :format :text :label "Name"}
                 :t.acct/greeting {:type :string :format :text :label "Greeting"}
                 :t.acct/total {:type :integer :format :number :label "Total"}
                 :t.acct/lines {:cardinality :one-to-many :target "t.line" :label "Lines"}
                 :t.line/id {:type :integer :identity? true :format :id :label "Line id"}
                 :t.line/qty {:type :integer :format :number :label "Qty"}}
    :sources {"src" {:type "sql" :jdbc_url "jdbc:sqlite::memory:"}}
    :resolvers [{:id "acct_lines" :input [:t.acct/id]
                 :output [{:t.acct/lines [:t.line/id :t.line/qty]}]
                 :result_type "list" :limit {:default 10 :max 10}
                 :source source :source-ref "src"}
                ;; 2.0 §2.4 lowering: flat roots plus the nested subtree.
                {:id "acct_total" :input [:t.acct/lines] :input-nested {:t.acct/lines [:t.line/qty]}
                 :output [:t.acct/total] :result_type "single"
                 :source source :source-ref "src"}
                ;; 2.0 §2.4 lowering: flat roots plus the optional set.
                {:id "acct_greet" :input [:t.acct/id :t.acct/name] :input-optional #{:t.acct/name}
                 :output [:t.acct/greeting] :result_type "single"
                 :source source :source-ref "src"}]}))

(defn- compiled []
  (let [{:keys [model findings]} (dialect/read-dialect model-text)
        _ (is (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
        model (-> model
                  (update :sources update-vals #(assoc % :type "test-input-v2"))
                  (update :resolvers (fn [rs] (mapv #(assoc-in % [:source :type] "test-input-v2") rs))))
        result (engine/compile-complete model)]
    (is (:compiled result) (pr-str (:findings result)))
    (:compiled result)))

(defn- pathom-input [compiled id]
  (-> compiled :engine/bundle :env ::pci/index-resolvers
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config ::pco/input))

(deftest the-dialect-reader-accepts-the-two-lowering-keys
  (let [{:keys [model findings]} (dialect/read-dialect model-text)
        by-id (into {} (map (juxt :id identity)) (:resolvers model))]
    (is (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    (is (= #{:t.acct/name} (get-in by-id ["acct_greet" :input-optional])))
    (is (= {:t.acct/lines [:t.line/qty]} (get-in by-id ["acct_total" :input-nested])))
    (testing "a 1.x entry has neither key -- absent, not empty"
      (is (not (contains? (get by-id "acct_lines") :input-optional)))
      (is (not (contains? (get by-id "acct_lines") :input-nested))))))

(deftest pco-input-is-built-from-the-two-keys-and-nothing-else-reads-them
  (let [c (compiled)]
    (is (= [:t.acct/id] (pathom-input c "acct_lines")))
    (is (= [{:t.acct/lines [:t.line/qty]}] (pathom-input c "acct_total")))
    (is (= [:t.acct/id (pco/? :t.acct/name)] (pathom-input c "acct_greet")))))

(deftest a-nested-input-arrives-as-the-evaluated-subtree
  (reset! seen [])
  (let [c (compiled)
        answer (engine/query c {:graph "t" :version 1} {:entity {:t.acct/id 1} :query [:t.acct/total]})]
    (is (= :ok (:engine/status answer)) (pr-str answer))
    (is (= 5 (get-in answer [:engine/data :t.acct/total])))
    (testing "the connector is handed a vector of maps holding only the requested children (2.0 §2.3)"
      (let [[_ in] (first (filter #(= "acct_total" (first %)) @seen))]
        (is (= [{:t.line/qty 2} {:t.line/qty 3}] (:t.acct/lines in)))))))

(deftest an-unreachable-optional-input-is-absent-not-nil
  (reset! seen [])
  (let [c (compiled)
        anon (engine/query c {:graph "t" :version 1} {:entity {:t.acct/id 1} :query [:t.acct/greeting]})
        named (engine/query c {:graph "t" :version 1}
                            {:entity {:t.acct/id 1 :t.acct/name "Acme"} :query [:t.acct/greeting]})]
    (is (= "hi anon" (get-in anon [:engine/data :t.acct/greeting])) (pr-str anon))
    (is (= "hi Acme" (get-in named [:engine/data :t.acct/greeting])) (pr-str named))
    (let [[_ in] (first (filter #(= "acct_greet" (first %)) @seen))]
      (is (not (contains? in :t.acct/name)) "absent, not nil (2.0 §2.3)"))))

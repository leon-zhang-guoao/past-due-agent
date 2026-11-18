(ns open.atlas.server.mcp.saved-query-test
  "Saved queries' pure half: shape, binding and the static attribute check
   (proposal studio-saved-queries §3)."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.server.mcp :as mcp]))

(def overdue
  {:name "overdue_invoices"
   :description "Invoices overdue as of a date."
   :params [{:name "as_of" :type :date :required? true :example "2026-10-01"}
            {:name "min_amount" :type :number}]
   :eql '{:entity {}
          :query [{(:acme.invoice/all {:due_before ?as_of :amount_gte ?min_amount :limit 50})
                   [:acme.invoice/id :acme.invoice/amount :acme.customer/name]}]}})

(defn- codes [fs] (set (map :code fs)))

(deftest a-well-formed-query-has-no-findings
  (is (empty? (mcp/saved-query-findings overdue)))
  (is (empty? (mcp/saved-query-set-findings [overdue]))))

(deftest shape-is-checked
  (testing "a description is required — it is what an agent chooses by"
    (is (= #{:saved-query/invalid} (codes (mcp/saved-query-findings (dissoc overdue :description))))))
  (testing "names are snake_case"
    (is (seq (mcp/saved-query-findings (assoc overdue :name "Overdue-Invoices")))))
  (testing "an undeclared placeholder"
    (is (seq (mcp/saved-query-findings (update overdue :params pop)))))
  (testing "a declared parameter that is never used"
    (is (seq (mcp/saved-query-findings (update overdue :params conj {:name "unused" :type :string})))))
  (testing "an optional parameter outside a map value cannot be dropped"
    (is (seq (mcp/saved-query-findings
              {:name "q" :description "d" :params [{:name "id" :type :integer}]
               :eql '[{[:acme.invoice/id ?id] [:acme.invoice/amount]}]}))))
  (testing "the same one, required, is fine"
    (is (empty? (mcp/saved-query-findings
                 {:name "q" :description "d" :params [{:name "id" :type :integer :required? true}]
                  :eql '[{[:acme.invoice/id ?id] [:acme.invoice/amount]}]}))))
  (testing "mutations are refused for now"
    (is (contains? (codes (mcp/saved-query-findings (assoc overdue :kind :mutation)))
                   :saved-query/kind-unsupported))
    (is (contains? (codes (mcp/saved-query-findings
                           {:name "q" :description "d" :params []
                            :eql '[(acme.invoice/void! {:acme.invoice/id 1})]}))
                   :saved-query/kind-unsupported)))
  (testing "an example of the wrong type"
    (is (seq (mcp/saved-query-findings (assoc-in overdue [:params 0 :example] "yesterday")))))
  (testing "duplicate names in a set"
    (is (seq (mcp/saved-query-set-findings [overdue overdue])))))

(deftest binding-values
  (testing "values replace placeholders; an absent optional drops its entry"
    (let [{:keys [request findings]} (mcp/bind-saved-query overdue {"as_of" "2026-10-01"})]
      (is (nil? findings))
      (is (= '{:entity {}
               :query [{(:acme.invoice/all {:due_before "2026-10-01" :limit 50})
                        [:acme.invoice/id :acme.invoice/amount :acme.customer/name]}]}
             request))))
  (testing "keyword keys work too (the /eql body is EDN)"
    (is (:request (mcp/bind-saved-query overdue {:as_of "2026-10-01" :min_amount 100}))))
  (testing "refusals name the parameter"
    (is (= #{:saved-query/param-missing} (codes (:findings (mcp/bind-saved-query overdue {})))))
    (is (= #{:saved-query/param-unknown}
           (codes (:findings (mcp/bind-saved-query overdue {"as_of" "2026-10-01" "x" 1})))))
    (is (= #{:saved-query/param-type}
           (codes (:findings (mcp/bind-saved-query overdue {"as_of" "1 Oct"})))))
    (is (= #{:saved-query/param-type}
           (codes (:findings (mcp/bind-saved-query overdue {"as_of" "2026-10-01" "min_amount" "100"})))))))

(deftest stale-attributes
  (let [known #{:acme.invoice/all :acme.invoice/id :acme.invoice/amount :acme.customer/name}]
    (is (nil? (mcp/saved-query-stale-findings overdue known)))
    (is (= ["acme.customer/name"]
           (:got (first (mcp/saved-query-stale-findings overdue (disj known :acme.customer/name))))))))

(deftest listing
  (let [d (mcp/describe-saved-query overdue false)]
    (is (= "overdue_invoices" (:name d)))
    (is (= ["as_of"] (get-in d [:params "required"])))
    (is (nil? (:eql d))))
  (is (string? (:eql (mcp/describe-saved-query overdue true))))
  (is (= {"as_of" "2026-10-01"} (mcp/saved-query-example-params overdue))))

(ns open.atlas.common.connector.derived.semantic-binding-test
  "Semantic Binding validation for the derived connector (owner ruling
   2026-09-18): per-clause findings with precise paths, slot names equal the
   attribute's local name, read-only, `:expr` is the only implementation key."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.derived :as derived]
            [open.atlas.common.connector.spi :as spi]))

(def binding
  {:binding/effect :read
   :binding/implementation {:expr "(* qty unit_price)"}
   :binding/input-map {:line/qty "qty" :line/unit_price "unit_price"}
   :binding/output-map {:line/total "total"}})

(defn- codes-and-paths [b]
  (set (map (juxt :code :path) (:findings (derived/validate-binding b)))))

(deftest a-valid-binding-validates-and-lowers
  (is (empty? (:findings (derived/validate-binding binding))))
  (is (= {:type "derived" :expr "(* qty unit_price)"} (derived/binding-operation binding)))
  (testing "the registered adapter answers the findings map, not a boolean"
    (let [adapter (spi/semantic-adapter "derived")]
      (is (= {:findings []} ((:validate adapter) binding)))
      (is (= (derived/binding-operation binding) ((:lower adapter) binding)))))
  (testing "the lowered operation evaluates through the connector"
    (let [read! (spi/compile-source (derived/binding-operation binding) nil
                                    {:id "total" :input [:line/qty :line/unit_price]
                                     :output [:line/total] :result_type "single"})]
      (is (= 12 (:line/total (read! {:line/qty 3 :line/unit_price 4} {})))))))

(deftest each-clause-reports-its-own-code-and-path
  (is (contains? (codes-and-paths (assoc binding :binding/effect :write))
                 [:connector.binding/effect [:binding/effect]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/implementation :timeout] 5))
                 [:connector.binding/unknown-key [:binding/implementation :timeout]]))
  (is (contains? (codes-and-paths (assoc binding :binding/implementation {}))
                 [:connector.binding/missing-key [:binding/implementation :expr]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/input-map :line/qty] "quantity"))
                 [:connector.binding/slot-mismatch [:binding/input-map :line/qty]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/output-map :line/total] "sum"))
                 [:connector.binding/slot-mismatch [:binding/output-map :line/total]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/output-map :line/other] "other"))
                 [:connector.binding/output-count [:binding/output-map]]))
  (is (contains? (codes-and-paths (assoc-in binding [:binding/input-map :other/qty] "qty"))
                 [:connector.binding/duplicate-physical-name [:binding/input-map :line/qty]]))
  (testing "all clause findings are collected, not just the first"
    (let [bad (-> binding (assoc :binding/effect :write)
                  (assoc-in [:binding/implementation :timeout] 5)
                  (assoc-in [:binding/input-map :line/qty] "quantity"))]
      (is (= 3 (count (:findings (derived/validate-binding bad))))))))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §2.2 (doc 109 §109.8 items 1-2, owner 2026-09-18):
;; derived supports BOTH forms -- an absent optional root binds `nil`, a nested
;; root binds the evaluated vector of maps.
;; ---------------------------------------------------------------------------

(def ^:private nested-binding
  {:binding/effect :read
   :binding/implementation {:expr "(reduce + 0 (map :item/qty items))"}
   :binding/input-map {:order/items "items"}
   :binding/output-map {:order/total "total"}
   ;; What the execution layer adds for a 2.0 definition.
   :binding/input-nested {:order/items [:item/qty]}})

(deftest a-nested-input-is-bound-as-the-evaluated-vector-and-aggregated
  (is (empty? (:findings (derived/validate-binding nested-binding))) (pr-str (derived/validate-binding nested-binding)))
  (let [read! (spi/compile-source (derived/binding-operation nested-binding) nil
                                  {:id "total" :input [:order/items] :input-nested {:order/items [:item/qty]}
                                   :output [:order/total] :result_type "single"})]
    (is (= 5 (:order/total (read! {:order/items [{:item/qty 2} {:item/qty 3}]} {}))))
    (is (= 0 (:order/total (read! {:order/items []} {}))) "an empty subtree aggregates to the seed"))
  (testing "the traversal set is admitted; the generators are still refused"
    (doseq [expr ["(apply + (map :item/qty items))"
                  "(count (filter :item/qty items))"
                  "(get (first items) :item/qty)"]]
      (is (empty? (:findings (derived/validate-binding (assoc-in nested-binding [:binding/implementation :expr] expr))))
          expr))
    (doseq [expr ["(map :item/qty (range 3))" "(reduce + (iterate inc 1))"]]
      (is (seq (:findings (derived/validate-binding (assoc-in nested-binding [:binding/implementation :expr] expr))))
          expr))))

(deftest an-absent-optional-input-is-bound-as-nil
  (let [b (-> binding
              (assoc-in [:binding/implementation :expr] "(if (nil? unit_price) 0 (* qty unit_price))")
              (assoc :binding/input-optional #{:line/unit_price}))
        read! (spi/compile-source (derived/binding-operation b) nil
                                  {:id "total" :input [:line/qty :line/unit_price] :input-optional #{:line/unit_price}
                                   :output [:line/total] :result_type "single"})]
    (is (empty? (:findings (derived/validate-binding b))))
    (is (= 0 (:line/total (read! {:line/qty 3} {}))) "absent root -> nil (contract 2.0 §2.2)")
    (is (= 12 (:line/total (read! {:line/qty 3 :line/unit_price 4} {}))))))

(deftest underlying-expression-findings-are-kept-without-echoing-the-expression
  (let [expr "(slurp \"/etc/private-file\")"
        result (derived/validate-binding (assoc-in binding [:binding/implementation :expr] expr))]
    (is (seq (:findings result)))
    (is (every? #(and (:code %) (:severity %) (:path %) (:message %)) (:findings result)))
    (is (not (.contains (pr-str result) "private-file")))))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §6.3 (doc 109 §109.8 item 3, owner 2026-09-18): an
;; expression yields one scalar, so derived refuses both output forms.
;; ---------------------------------------------------------------------------

(deftest derived-refuses-union-and-recursive-output
  (is (contains? (codes-and-paths (assoc binding :binding/output-union #{:line/total}))
                 [:connector.binding/union-output-unsupported [:binding/output-map :line/total]]))
  (is (contains? (codes-and-paths (assoc binding :binding/output-recursion #{:line/total}))
                 [:connector.binding/recursion-output-unsupported [:binding/output-map :line/total]])))

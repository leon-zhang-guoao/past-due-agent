(ns open.atlas.common.engine.batch-isolation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

(def calls (atom []))
(defmethod spi/compile-source "test-isolation" [_ _ _]
  (fn [input _]
    (swap! calls conj input)
    (assert (string? (:p/detail input)))
    {:p/derived (str/upper-case (:p/detail input))}))
(defmethod spi/compile-batch-source "test-isolation" [_ _ config]
  (fn [inputs _]
    (mapv (fn [input]
            (if (= "details" (:id config))
              (if (= "private-bad" (:p/id input))
                (spi/batch-item-failure {:resolver-id "details" :source-type "api"} 400)
                {:p/detail (:p/id input)})
              (do (swap! calls conj input)
                  (assert (string? (:p/detail input)))
                  {:p/derived (str/upper-case (:p/detail input))}))) inputs)))

(defn compiled [derived-type]
  (let [doc {:namespaces {"p" {:label "Products"}}
             :attributes {:p/id {:type :string :identity? true :label "ID"}
                          :p/detail {:type :string :label "Detail"}
                          :p/derived {:type :string :label "Derived"}
                          :p/index {:cardinality :one-to-many :target "p" :label "Catalog"}}
             :resolvers [{:id "index" :input [] :output [{:p/index [:p/id]}]
                          :result_type "list" :limit {:default 10 :max 10}
                          :source {:type "static" :data [{:p/id "good"} {:p/id "private-bad"}]}}
                         {:id "details" :input [:p/id] :output [:p/detail] :result_type "single"
                          :source {:type "api" :method "GET" :endpoint "/test/{ids}"
                                   :batch {:parent-key :p/id :child-fk "id" :placeholder "ids"}}}
                         {:id "derived" :input [:p/detail] :output [:p/derived] :result_type "single"
                          :source (case derived-type
                                    :static {:type "static" :match-on :p/detail :data [{:p/detail "good" :p/derived "GOOD"}]}
                                    :batch {:type "api" :method "GET" :endpoint "/test/{ids}"
                                            :batch {:parent-key :p/detail :child-fk "id" :placeholder "ids"}}
                                    {:type "api" :method "GET" :endpoint "/test"})}]}
        parsed (dialect/read-dialect (pr-str doc))
        model (update (:model parsed) :resolvers
                      #(mapv (fn [r] (if (= "api" (get-in r [:source :type]))
                                       (assoc-in r [:source :type] "test-isolation") r)) %))]
    (is (empty? (:findings parsed)) (pr-str (:findings parsed)))
    (engine/compile-env model)))

(deftest failed-inputs-never-become-downstream-source-parameters
  (doseq [kind [:single :batch :static]]
    (reset! calls [])
    (let [env (compiled kind)
          r (engine/query env [{:p/index [:p/id :p/derived]}])]
      (is (= :ok (:engine/status r)) (pr-str r))
      (is (true? (:engine/partial? r)))
      (is (= [{:p/id "good" :p/derived "GOOD"} {:p/id "private-bad"}]
             (get-in r [:engine/data :p/index])))
      (is (= (if (= kind :static) [] [{:p/detail "good"}]) @calls))
      (is (= [:p/index 1] (:path (first (:engine/row-outcomes r)))))
      (is (not (str/includes? (pr-str (:engine/issues r)) "private-bad")))
      (is (nil? (:engine/not-found r))))))

(deftest ident-issues-are-value-free-and-data-outcomes-retain-correlation
  (let [r (engine/query (compiled :single)
                       [{[:p/id "good"] [:p/detail]} {[:p/id "private-bad"] [:p/detail]}])]
    (is (= :ok (:engine/status r)))
    (is (= {} (get-in r [:engine/data [:p/id "private-bad"]])))
    (is (= [[:p/id "private-bad"]] (:path (first (:engine/row-outcomes r)))))
    (is (= :idents (first (:path (first (:engine/issues r))))))
    (is (not (str/includes? (pr-str (:engine/issues r)) "private-bad")))
    (is (not (str/includes? (pr-str (:engine/data r)) "BatchItemFailure")))))


(deftest a-single-failed-entity-is-partial-not-not-found
  (let [r (engine/query (compiled :single) nil
                       {:entity {:p/id "private-bad"} :query [:p/id :p/detail]}
                       {:stats? true})]
    (is (= :ok (:engine/status r)))
    (is (true? (:engine/partial? r)))
    (is (= [] (:path (first (:engine/row-outcomes r)))))
    (is (nil? (:engine/not-found r)))
    (is (= {:p/id "private-bad"} (:engine/data r)))
    (is (not (str/includes? (pr-str (:engine/stats r)) "BatchItemFailure")))))

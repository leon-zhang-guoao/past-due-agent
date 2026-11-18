(ns open.atlas.common.engine.complete-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.engine :as engine]))

(def model
  {:attributes {:case/id {:type :integer :identity? true}
                :case/title {:type :string}}
   :namespaces {"case" {:label "Case"}}
   :resolvers [] :mutations [] :links [] :sources {} :terms {} :metrics {}})

(deftest supported-empty-model-uses-the-existing-compiler
  (let [result (engine/compile-complete model)]
    (is (empty? (:findings result)))
    (is (= model (get-in result [:compiled :engine/model])))
    (is (= (engine/catalog (engine/compile-env model))
           (engine/catalog (:compiled result))))))

(deftest an-excluded-resolver-prevents-an-executable-candidate
  (let [candidate (assoc model :resolvers
                         [{:id "lookup" :input [:case/id] :output [:case/title]
                           :result_type "single" :source-ref "missing"}])
        legacy (engine/compile-env candidate)
        strict (engine/compile-complete candidate)]
    (is (some? (:engine/bundle legacy)))
    (is (seq (:engine/findings legacy)))
    (is (= (:engine/findings legacy) (:findings strict)))
    (is (not (contains? strict :compiled)))))

(deftest an-excluded-mutation-also-prevents-an-executable-candidate
  (let [candidate (assoc model :mutations
                         [{:id "create" :params {} :result_type "none"
                           :source-ref "missing"}])
        result (engine/compile-complete candidate)]
    (is (seq (:findings result)))
    (is (not (contains? result :compiled)))))

(ns open.atlas.common.semantic-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.semantic :as semantic]))

(def sample
  {:entities {"po" {:entity/label "采购" :entity/identity [:po/id :po/line]
                          :entity/attributes [:po/id :po/line :po/number]}}
   :attributes {:po/id {:attribute/label "订单" :attribute/type :integer
                        :attribute/nullable? false :attribute/identity-scope "po" :attribute/identity-component :po/id}
                :po/line {:attribute/label "行" :attribute/type :integer :attribute/nullable? false}
                :po/number {:attribute/label "编号" :attribute/type :integer
                            :attribute/nullable? false :attribute/identity-scope "po" :attribute/identity-component :po/id}}
   :terms {"number" {:term/label "编号" :term/description "Business number"}}})

(defn codes [v] (set (map :code (:findings (semantic/validate-vocabulary v)))))

(deftest ownership-is-unique-and-matches-namespace
  (is (contains? (codes (assoc-in sample [:entities "other"]
                                 {:entity/label "Other" :entity/identity [:po/id] :entity/attributes [:po/id]}))
                 :semantic/ownership))
  (is (contains? (codes (update-in sample [:entities "po" :entity/attributes] pop)) :semantic/ownership)))

(deftest different-components-cannot-be-equivalent
  (is (contains? (codes (assoc-in sample [:attributes :po/id :attribute/identity-component] :po/line))
                 :semantic/identity-component))
  (let [v (-> sample
              (assoc-in [:attributes :po/line :attribute/identity-scope] "po")
              (assoc-in [:attributes :po/line :attribute/identity-component] :po/line)
              (assoc :equivalences [{:equivalence/attributes [:po/id :po/line]
                                     :equivalence/scope "po" :equivalence/direction :two-way}]))]
    (is (contains? (codes v) :semantic/identity-component))))

(deftest validation-does-not-infer-navigation
  (let [v (-> sample
              (assoc-in [:attributes :po/id :attribute/term] "number")
              (assoc-in [:attributes :po/number :attribute/term] "number"))]
    (is (= v (:vocabulary (semantic/validate-vocabulary v))))
    (is (nil? (:aliases (:vocabulary (semantic/validate-vocabulary v)))))))

(deftest composite-identity-must-be-present-scalar-and-nonnullable
  (doseq [v [(assoc-in sample [:entities "po" :entity/identity] [])
             (assoc-in sample [:entities "po" :entity/identity] [:po/missing])
             (assoc-in sample [:attributes :po/line :attribute/nullable?] true)
             (update sample :attributes dissoc :po/line)
             (assoc-in sample [:entities "po" :entity/identity] [:po/id :po/id])]]
    (is (seq (codes v)))
    (is (nil? (:vocabulary (semantic/validate-vocabulary v))))))

(deftest aliases-are-explicit-and-type-checked
  (let [v (assoc sample :aliases [{:alias/from :po/id :alias/to :po/number
                                  :alias/direction :one-way}])]
    (is (= v (:vocabulary (semantic/validate-vocabulary v))))
    (is (contains? (codes (assoc-in v [:attributes :po/number :attribute/type] :string))
                   :semantic/alias-type))
    (is (contains? (codes (assoc-in v [:aliases 0 :alias/direction] :two-way)) :semantic/schema))))

(deftest equivalence-needs-explicit-compatible-scope
  (let [v (assoc sample :equivalences [{:equivalence/attributes [:po/id :po/number]
                                       :equivalence/scope "po"
                                       :equivalence/direction :two-way}])]
    (is (empty? (codes v)))
    (is (contains? (codes (update-in v [:attributes :po/number] dissoc :attribute/identity-scope))
                   :semantic/identity-scope))
    (is (contains? (codes (assoc-in v [:equivalences 0 :equivalence/attributes] [:po/id :po/id]))
                   :semantic/duplicate))))

(deftest schema-and-reference-errors-do-not-echo-data
  (doseq [v [(assoc sample :credentials "private-value")
             (assoc-in sample [:attributes :po/id :attribute/nullable?] "false")
             (assoc-in sample [:attributes :po/id :attribute/term] "missing")
             (assoc-in sample [:attributes :po/id :attribute/target] "po")]]
    (is (seq (codes v)))
    (is (not (.contains (pr-str (semantic/validate-vocabulary v)) "private-value")))))

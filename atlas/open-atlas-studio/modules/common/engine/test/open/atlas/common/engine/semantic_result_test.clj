(ns open.atlas.common.engine.semantic-result-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.semantic-result :as result]))

(def vocabulary
  {:entities {"case" {:entity/label "Case" :entity/identity [:case/id]
                      :entity/attributes [:case/id :case/title]}}
   :attributes {:case/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :case/title {:attribute/label "Title" :attribute/type :string :attribute/nullable? false}}})

(deftest query-projections-are-preserved
  (doseq [response [{:engine/status :ok :engine/data {:case/title "title"}}
                    {:engine/status :ok :engine/data {} :engine/not-found {:attributes [:case/title]}}]]
    (is (= response (:response (result/validate-query-result vocabulary response))))))

(deftest invalid-values-are-withheld-not-coerced-or-echoed
  (doseq [data [{:case/title nil} {:case/id "private-value"}
                {"private-key" "private-value"} {:case/unknown "private-value"}]]
    (let [checked (result/validate-query-result vocabulary {:engine/status :ok :engine/data data})]
      (is (seq (:findings checked)))
      (is (nil? (:response checked)))
      (is (not (.contains (pr-str checked) "private"))))))

(deftest existing-rejections-are-not-rewritten-as-empty-success
  (let [response {:engine/status :rejected :findings [{:code :test/denied}]}]
    (is (= response (:response (result/validate-query-result vocabulary response))))))

(deftest ident-roots-are-independent-validated-projections
  (let [response {:engine/status :ok :engine/data
                  {[:case/id 1] {:case/title "one"} [:case/id 2] {:case/title "two"}
                   :case/title "root"}}]
    (is (= response (:response (result/validate-query-result vocabulary response)))))
  (doseq [data [{[:case/id 1] {:case/title 42}}
                {[:case/id "private-id"] {:case/title "title"}}
                {[:case/id 1] {:case/id 2}}
                {[:case/id 1] "private-value"}
                {[:case/unknown "private-id"] {}}
                {[:case/id 1 2] {}}]]
    (let [checked (result/validate-query-result vocabulary {:engine/status :ok :engine/data data})]
      (is (seq (:findings checked)))
      (is (nil? (:response checked)))
      (is (not (.contains (pr-str checked) "private"))))))

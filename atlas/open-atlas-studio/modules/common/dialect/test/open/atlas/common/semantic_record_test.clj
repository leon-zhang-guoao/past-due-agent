(ns open.atlas.common.semantic-record-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.semantic-record :as record]))

(def vocabulary
  {:entities {"case" {:entity/label "Case" :entity/identity [:case/id :case/site]
                      :entity/attributes [:case/id :case/site :case/title :case/notes]}
              "note" {:entity/label "Note" :entity/identity [:note/id]
                      :entity/attributes [:note/id :note/body]}}
   :attributes {:case/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :case/site {:attribute/label "Site" :attribute/type :string :attribute/nullable? false}
                :case/title {:attribute/label "Title" :attribute/type :string :attribute/nullable? true}
                :case/notes {:attribute/label "Notes" :attribute/type :ref :attribute/nullable? false
                             :attribute/cardinality :many :attribute/target "note"}
                :note/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :note/body {:attribute/label "Body" :attribute/type :string :attribute/nullable? false}}})
(def base {:case/id 1 :case/site "Malaysia"})
(defn validate [r] (record/validate-record vocabulary "case" r))

(deftest identity-is-complete-and-values-are-not-coerced
  (is (= base (:record (validate base))))
  (doseq [r [(dissoc base :case/site) (assoc base :case/id nil) (assoc base :case/id "1")]]
    (is (seq (:findings (validate r))))
    (is (nil? (:record (validate r))))))

(deftest omitted-null-and-required-are-distinct
  (is (= base (:record (validate base))))
  (is (= (assoc base :case/title nil) (:record (validate (assoc base :case/title nil)))))
  (is (= :record/missing
         (-> (record/validate-record vocabulary "case" base #{:case/title}) :findings first :code)))
  (is (empty? (:findings (record/validate-record vocabulary "case"
                                                (assoc base :case/title nil) #{:case/title})))))

(deftest nested-records-keep-error-paths-without-data
  (is (empty? (:findings (validate (assoc base :case/notes [{:note/id 2 :note/body "ok"}])))))
  (let [result (validate (assoc base :case/notes [{:note/body "secret-body"}]))]
    (is (= [:case/notes 0 :note/id] (-> result :findings first :path)))
    (is (not (.contains (pr-str result) "secret-body"))))
  (is (= :record/cardinality
         (-> (validate (assoc base :case/notes {})) :findings first :code)))
  (is (empty? (:findings (validate (assoc base :case/notes []))))))

(deftest unknown-input-does-not-leak-keys-or-values
  (let [result (validate (assoc base "secret-key" "secret-value"))]
    (is (= :record/unknown-attribute (-> result :findings first :code)))
    (is (not (.contains (pr-str result) "secret")))))

(deftest projections-allow-omission-but-not-invalid-present-values
  (let [projection {:case/notes [{:note/body "only requested body"}]}]
    (is (= projection (:record (record/validate-projection vocabulary "case" projection))))
    (is (seq (:findings (validate projection)))))
  (is (seq (:findings (record/validate-projection vocabulary "case" {:case/id "1"})))))

(deftest scalar-values-use-explicit-internal-types
  (doseq [[type valid invalid] [[:integer 1 "1"]
                               [:decimal 1.25M 1.25]
                               [:boolean false 0]
                               [:string "1" 1]
                               [:date (java.time.LocalDate/of 2026 9 8) "2026-09-08"]
                               [:datetime (java.time.Instant/parse "2026-09-08T00:00:00Z") "2026-09-08T00:00:00Z"]]]
    (let [v (assoc-in vocabulary [:attributes :case/title :attribute/type] type)]
      (is (empty? (:findings (record/validate-projection v "case" {:case/title valid}))))
      (is (= :record/type (-> (record/validate-projection v "case" {:case/title invalid}) :findings first :code))))))

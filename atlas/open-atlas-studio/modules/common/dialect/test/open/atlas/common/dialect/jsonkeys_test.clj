(ns open.atlas.common.dialect.jsonkeys-test
  "Schema-driven JSON→EDN map-key coercion (owner ruling 2026-09-07,
   doc 106 §106.14 item 9). The positions are derived from the contract's own
   schema, so these tests name positions only to show the two directions."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as dialect]))

(deftest string-keyed-positions-take-strings-back
  (testing ":headers is [:map-of :string :string]; JSON can only send strings"
    (is (= {:type "api" :base_url "https://h" :headers {"Accept" "application/json"}}
           (dialect/coerce-json-keys
            :source {:type "api" :base_url "https://h" :headers {:Accept "application/json"}}))))
  (testing "a code list's keys come back as strings"
    (is (= {"RUNNING" {:label "运行中"}}
           (dialect/coerce-json-keys :values {:RUNNING {:label "运行中"}}))))
  (testing "an operation block's :query_params, nested under a :multi branch"
    (is (= {"recursive" "true"}
           (:query_params
            (dialect/coerce-json-keys
             :resolver-source {:type "api" :method "GET" :endpoint "/p"
                               :query_params {:recursive "true"}}))))))

(deftest attribute-keyed-positions-take-keywords
  (testing ":field_mapping is [:map-of AttrKw …]; the string is the wrong half there"
    (is (= {:field_mapping {:n.f.p/id "$.id"}}
           (select-keys
            (dialect/coerce-json-keys
             :resolver-source {:type "api" :method "GET" :endpoint "/p"
                               :field_mapping {"n.f.p/id" "$.id"}})
            [:field_mapping]))))
  (testing "a branch scope is walked too (doc 106 §106.14 item 6)"
    (is (= {:n.f.g/id "$.id" :n.f.g/parent {:n.f.g/id "$.component.parentGroupId"}}
           (:field_mapping
            (dialect/coerce-json-keys
             :resolver-source
             {:type "api" :method "GET" :endpoint "/g"
              :field_mapping {"n.f.g/id" "$.id"
                              "n.f.g/parent" {"n.f.g/id" "$.component.parentGroupId"}}}))))))

(deftest it-never-guesses
  (testing "a key that already fits is untouched"
    (is (= {:headers {"Accept" "x"}}
           (select-keys (dialect/coerce-json-keys
                         :source {:type "api" :base_url "h" :headers {"Accept" "x"}})
                        [:headers]))))
  (testing "a key that fits neither spelling is left for the contract to refuse"
    (is (= {12 "x"}
           (:field_mapping (dialect/coerce-json-keys
                            :resolver-source {:type "api" :method "GET" :endpoint "/p"
                                              :field_mapping {12 "x"}})))))
  (testing "values are never touched — a wrong type is a modelling error, not an encoding one"
    (is (= {"Accept" 7}
           (:headers (dialect/coerce-json-keys
                      :source {:type "api" :base_url "h" :headers {:Accept 7}})))))
  (testing "an unknown :type selects no :multi branch and nothing is rewritten"
    (is (= {:type "mystery" :headers {:Accept "x"}}
           (dialect/coerce-json-keys :source {:type "mystery" :headers {:Accept "x"}})))))

(deftest shape-refusals-carry-a-literal-example
  (let [fs (:findings (dialect/read-dialect
                       (pr-str {:attributes {:a/b {:type "string"}}
                                :links [{:from "a/b" :to :c/d}]})))
        shape (first (filter #(= :dialect.schema/invalid-shape (:code %)) fs))]
    (testing "the example is a literal spelling, dispatched by the expected type"
      (is (some? shape))
      (is (= "a qualified keyword, e.g. :cis.party/customer_no" (:expected shape))))))

(ns open.atlas.common.connector.api.jsonpath-test
  "JSONPath extraction: dotted paths, [*] columns, numeric indexes,
   positional (all-digit) segments, and nested [*] flattening."
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api.impl.jsonpath :as jsonpath]))

(def sample
  {:data {:total 2
          :rows  [["2026-03" "Acme" 3] ["2026-04" "Bolt" 5]]
          :items [{:id 1 :addr {:city "Delft"} :parts [{:qty 2} {:qty 3}]}
                  {:id 2 :addr {:city "Ede"}   :parts [{:qty 7}]}]}})

(deftest dotted-paths
  (is (= 2 (jsonpath/extract sample "$.data.total")))
  (is (nil? (jsonpath/extract sample "$.data.nope.deeper"))))

(deftest star-columns
  (is (= [1 2] (jsonpath/extract sample "$.data.items[*].id")))
  (testing "nested map under the array marker"
    (is (= ["Delft" "Ede"] (jsonpath/extract sample "$.data.items[*].addr.city"))))
  (testing "a missing field keeps its slot (index alignment)"
    (is (= [1 nil] (jsonpath/extract {:data {:items [{:a 1} {:b 2}]}}
                                     "$.data.items[*].a")))))

(deftest numeric-index-and-root-forms
  (is (= {:qty 2} (jsonpath/extract sample "$.data.items[0].parts[0]")))
  (is (= "Ede" (jsonpath/extract [{:c "Delft"} {:c "Ede"}] "$[1].c")))
  (is (= [{:c "Delft"}] (jsonpath/extract [{:c "Delft"}] "$[*]"))))

(deftest positional-digit-segments
  (testing "an all-digits segment indexes array rows by position"
    (is (= ["Acme" "Bolt"] (jsonpath/extract sample "$.data.rows[*].1")))))

(deftest nested-star-flattens-to-rows
  (is (= [2 3 7] (jsonpath/extract sample "$.data.items[*].parts[*].qty"))))

(deftest non-sequential-array-target-is-empty
  (is (= [] (jsonpath/extract {:data {:items 42}} "$.data.items[*].id"))))

(deftest the-root-is-the-document-itself
  "doc 106 §106.12 item 7.4 (fixed 2026-09-07): `$` is api-v1's root with
   nothing walked after it. It used to fall through to a map-key lookup of
   `:$`, so a `:data_path \"$\"` probed clean — the probe's own walker already
   read it as the whole response — and answered nil at query time."
  (is (= sample (jsonpath/extract sample "$")))
  (is (= [{:a 1}] (jsonpath/extract [{:a 1}] "$")))
  (is (= sample (jsonpath/extract sample " $ "))))

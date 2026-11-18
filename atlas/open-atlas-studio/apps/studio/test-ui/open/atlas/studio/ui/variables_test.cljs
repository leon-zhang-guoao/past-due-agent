(ns open.atlas.studio.ui.variables-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.studio.ui.variables :as variables]))

(deftest manually-added-variables-are-visible-without-any-source
  (is (= [{:name "MY_KEY" :stored? true :present? true :used-by []}]
         (variables/variable-rows {:variables [] :unreferenced ["MY_KEY"]})))
  (is (= ["A" "B"]
         (mapv :name (variables/variable-rows
                      {:variables [{:name "B" :used-by ["nifi"]}]
                       :unreferenced ["A" "B"]})))))

(deftest variable-names-match-source-reference-syntax
  (is (variables/valid-name? "NIFI_CLIENT_STORE_PASS"))
  (is (variables/valid-name? "_TOKEN_2"))
  (is (not (variables/valid-name? "${TOKEN}")))
  (is (not (variables/valid-name? "my-token")))
  (is (not (variables/valid-name? "2TOKEN"))))

(def ^:private rows
  [{:name "ATLAS_DOSSIER_USER" :stored? true :present? true :used-by ["atlas-dossier"]}
   {:name "CH2_PASSWORD" :stored? true :present? true :used-by ["ch2" "ch22"]}
   {:name "LEAKY_DB_URL" :stored? true :present? true :used-by []}
   {:name "NIFI_STORE_PASS" :stored? false :present? false :used-by ["nifi-local"]}])

(deftest search-finds-a-variable-by-name-or-by-the-source-that-uses-it
  (is (= ["CH2_PASSWORD"] (mapv :name (variables/filter-variable-rows rows "ch2" :all)))
      "a Source name matches the variables it uses")
  (is (= ["CH2_PASSWORD"] (mapv :name (variables/filter-variable-rows rows "ch22" :all))))
  (is (= ["ATLAS_DOSSIER_USER"] (mapv :name (variables/filter-variable-rows rows "dossier user" :all)))
      "every word must match, in any order and any case")
  (is (= 4 (count (variables/filter-variable-rows rows "" :all))))
  (is (= 4 (count (variables/filter-variable-rows rows "   " :all)))))

(deftest a-pasted-reference-is-searched-as-its-name
  (is (= ["LEAKY_DB_URL"] (mapv :name (variables/filter-variable-rows rows "${LEAKY_DB_URL}" :all))))
  (is (= ["leaky" "db"] (variables/query-terms "${leaky} db"))))

(deftest quick-filters-answer-the-two-housekeeping-questions
  (is (= ["LEAKY_DB_URL"] (mapv :name (variables/filter-variable-rows rows "" :unused))))
  (is (= ["NIFI_STORE_PASS"] (mapv :name (variables/filter-variable-rows rows "" :not-set))))
  (is (= [] (variables/filter-variable-rows rows "atlas" :unused))
      "a filter and a search combine"))

(deftest rows-sort-by-column-with-a-stable-name-tiebreak
  (is (= ["ATLAS_DOSSIER_USER" "CH2_PASSWORD" "LEAKY_DB_URL" "NIFI_STORE_PASS"]
         (mapv :name (variables/sort-variable-rows rows :name :asc))))
  (is (= ["NIFI_STORE_PASS" "LEAKY_DB_URL" "CH2_PASSWORD" "ATLAS_DOSSIER_USER"]
         (mapv :name (variables/sort-variable-rows rows :name :desc))))
  (is (= "LEAKY_DB_URL" (:name (last (variables/sort-variable-rows rows :sources :asc))))
      "variables nothing references sort last by Source")
  (is (= "NIFI_STORE_PASS" (:name (first (variables/sort-variable-rows rows :status :asc))))
      "«not-set» sorts before «stored»"))

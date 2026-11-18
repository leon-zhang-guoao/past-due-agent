(ns open.atlas.studio.ui.query-tools-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [open.atlas.studio.ui.editor :as editor]
            [open.atlas.studio.ui.query-tools :as tools]))

(deftest lifecycle-discovery-uses-contract-capability-not-resolver-spelling
  (is (tools/supports-lifecycle?
       {:publication {:required-capabilities #{:semantic/core-v1 :connector/nifi}}
        :execution {:resolvers {"a-completely-different-id" {}}}}))
  (is (not (tools/supports-lifecycle?
            {:publication {:required-capabilities #{:semantic/core-v1 :connector/api}}
             :execution {:resolvers {"nifi_provenance_search" {}}}}))))

(deftest examples-come-from-the-contract
  (let [example {:example/id "search" :example/eql "[:x/all]" :example/entity {}}]
    (is (= [example]
           (tools/query-examples {:graph {:graph/query-examples [example]}})))
    (is (= [] (tools/query-examples {})))))

(deftest examples-are-formatted-before-they-enter-both-editors
  (let [{:keys [query-text entity-text error]}
        (tools/example-editor-text
         {:example/eql "[(:demo.order/status {:Status \"PAID\" :minimum 50}) :demo.order/total]"
          :example/entity {:demo.order/id 1001 :demo.order/code "PO-7"}})]
    (is (nil? error))
    (is (= '[(:demo.order/status {:Status "PAID" :minimum 50}) :demo.order/total]
           (reader/read-string query-text)))
    (is (= {:demo.order/id 1001 :demo.order/code "PO-7"}
           (reader/read-string entity-text)))
    (is (re-find #"\n" query-text))
    (is (re-find #"\n" entity-text))))

(deftest invalid-example-edn-is-retained-and-reported
  (let [{:keys [query-text entity-text error]}
        (tools/example-editor-text {:example/eql "[:demo.order/id" :example/entity {}})]
    (is (= "[:demo.order/id" query-text))
    (is (= {} (reader/read-string entity-text)))
    (is (re-find #"Unreadable EDN" error))))

(deftest a-saved-contract-target-does-not-disable-a-valid-run
  (let [run-target-note "Run target: saved Semantic Contract draft revision 2"]
    (is (false? (tools/query-action-disabled?
                 {:busy? false :handler identity :scope-block nil
                  :parse-error nil :entity-error nil
                  :run-target-note run-target-note})))
    (is (true? (tools/query-action-disabled?
                {:busy? false :handler identity :scope-block "Pick a graph"
                 :parse-error nil :entity-error nil
                 :run-target-note run-target-note})))
    (is (true? (tools/query-action-disabled?
                {:busy? false :handler nil :scope-block nil
                 :parse-error nil :entity-error nil})))))

(deftest semantic-query-request-keeps-the-tools-closed-key-set
  (doseq [lenient? [false true]]
    (let [expected {:graph "owner/nifi-local"
                    :action :query
                    :expected_revision 2
                    :query [:nifi.provenance.event/id]
                    :entity {}
                    :lenient? lenient?}]
      (is (= expected
             (tools/semantic-query-args
              "owner/nifi-local" 2
              {:query [:nifi.provenance.event/id]
               :entity {}
               :lenient? lenient?
               :graph-id "must-not-cross-the-tool-boundary"
               :ui-state :ignored}))))))

(deftest completion-follows-the-saved-contract-not-the-legacy-pool
  (let [catalog {:attributes [{:attribute "legacy/id" :producers ["old"]}]}
        draft {:revision 6
               :document {:vocabulary {:attributes
                                      {:new.case/id {:attribute/type :uuid :attribute/label "Case ID"}
                                       :new.case/recent {:attribute/type :ref
                                                         :attribute/description "Recent cases"}}}}}]
    (is (= [{:attribute "new.case/id" :detail "Case ID"}
            {:attribute "new.case/recent" :detail "Recent cases"}]
           (tools/completion-attributes draft catalog)))
    (is (= [{:attribute "legacy/id" :detail "1 producers"}]
           (tools/completion-attributes nil catalog)))
    (is (= [] (tools/completion-attributes {:revision 1 :document {}} catalog)))
    (is (= [] (tools/completion-attributes nil nil)))))

;; ---------------------------------------------------------------------------
;; The EQL box takes a whole request map
;; ---------------------------------------------------------------------------

(deftest the-eql-box-accepts-a-whole-execute-eql-request
  (testing "a request map runs as it stands, entity included"
    (let [r (editor/parse-request "{:entity {:a/id 1} :query [:a/name]}" "")]
      (is (= {:query [:a/name] :entity {:a/id 1}} (:value r)))
      (is (= :entity (:combined r)))))
  (testing "without :entity the Entity box is used, as in the two-box form"
    (let [r (editor/parse-request "{:query [:a/name]}" "{:a/id 2}")]
      (is (= {:query [:a/name] :entity {:a/id 2}} (:value r)))
      (is (= :query (:combined r)))))
  (testing "the request's own :entity wins over a filled Entity box"
    (is (= {:a/id 1} (:entity (:value (editor/parse-request "{:query [:a] :entity {:a/id 1}}" "{:a/id 9}"))))))
  (testing "the two-box form is unchanged"
    (let [r (editor/parse-request "[:a/name]" "{:a/id 3}")]
      (is (= {:query [:a/name] :entity {:a/id 3}} (:value r)))
      (is (nil? (:combined r))))))

(deftest a-malformed-request-map-is-refused-by-name-not-trimmed
  (let [params (editor/parse-request "{:query [:a] :params {:x 1}}" "")]
    (is (nil? (:value params)) "an unsupported key is refused, not silently dropped")
    (is (str/includes? (:error params) ":params")))
  (is (some? (:query-error (editor/parse-request "{:entity {:a/id 1}}" ""))) ":query is required")
  (is (some? (:query-error (editor/parse-request "{:query :a/name}" ""))) ":query must be a vector")
  (is (some? (:query-error (editor/parse-request "{:query [:a] :entity [1]}" ""))) ":entity must be a map")
  (is (some? (:query-error (editor/parse-request ":a/name" ""))) "neither vector nor map"))

(deftest one-editor-one-document-entity-then-query
  (testing "the two halves become one {:entity … :query …} document that parses back"
    (let [text (editor/request-text "[:a/name]" "{:a/id 1}")]
      (is (str/starts-with? text "{:entity {:a/id 1}"))
      (is (= {:query [:a/name] :entity {:a/id 1}} (:value (editor/parse-request text ""))))))
  (testing "nothing yet is the empty template, still a valid request"
    (is (= "{:entity {}\n :query  []}" (editor/request-text nil nil)))
    (is (= {:query [] :entity {}} (:value (editor/parse-request (editor/request-text "" "") "")))))
  (testing "a multi-line query is indented under its key and still reads back"
    (let [text (editor/request-text "[{:a/orders [:o/id :o/total :o/status :o/created-at :o/customer-name]}]" "{}")]
      (is (= [{:a/orders [:o/id :o/total :o/status :o/created-at :o/customer-name]}]
             (:query (:value (editor/parse-request text "")))))))
  (testing "a Contract example arrives as the same document"
    (let [{:keys [request-text]} (tools/example-editor-text {:example/eql "[:demo.order/id]"
                                                            :example/entity {:demo.order/id 7}})]
      (is (= {:query [:demo.order/id] :entity {:demo.order/id 7}}
             (:value (editor/parse-request request-text "")))))))

(deftest format-lays-out-a-request-and-leaves-anything-else-to-the-plain-formatter
  (is (= (editor/request-text "[:a]" "{:a/id 1}") (editor/format-request "{:query [:a] :entity {:a/id 1}}")))
  (is (= "{:query [:a], :params {:x 1}}" (editor/format-request "{:query [:a] :params {:x 1}}"))
      "a map with another key is not reshaped (the parser refuses it by name)")
  (is (= "[:a]" (editor/format-request "[:a]"))))

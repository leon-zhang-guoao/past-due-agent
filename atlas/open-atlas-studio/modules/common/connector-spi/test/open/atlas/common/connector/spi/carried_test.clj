(ns open.atlas.common.connector.spi.carried-test
  "`carried-attributes` — the seam's answer to «what is written INTO these
   rows rather than read out of a record» (doc 106 §106.14 items 13, 17, 18).

   One implementation, three callers: the engine writes those values into the
   child rows, and `need-view` reads a compound identity back out of the same
   answer. A second derivation would put `[host id]` on one face and `[id]` on
   the other, which §106.5 rules out."
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.connector.spi :as spi]))

;; A fake source type, so this test asserts the SEAM and not any connector's
;; spelling of a binding.
(defmethod spi/connection-bound-attributes "test-carry" [_] #{:nifi.instance/host})
(defmethod spi/in-record-collection-attributes "test-carry" [_]
  #{:nifi.flow.processor/relationships})

(def ^:private model
  {:sources {"nifi" {:type "test-carry" :base_url "https://{nifi.instance/host}/x"}}
   :attributes {:nifi.instance/host {:type :string :identity? true}
                :nifi.flow.processor/id {:type :string :identity? true}
                :nifi.flow.processor/relationships {:target "nifi.flow.relationship"
                                                    :cardinality :one-to-many}
                :nifi.flow.processor/bulletins {:target "nifi.flow.bulletin"
                                                :cardinality :one-to-many}
                :nifi.flow.relationship/name {:type :string :identity? true}
                :nifi.flow.bulletin/message {:type :string}}
   :resolvers [{:id "processor_by_host_id"
                :source-ref "nifi"
                :input [:nifi.instance/host :nifi.flow.processor/id]
                :result_type "single"
                :output [:nifi.flow.processor/id
                         {:nifi.flow.processor/relationships
                          [:nifi.flow.relationship/name]}
                         {:nifi.flow.processor/bulletins
                          [:nifi.flow.bulletin/message]}]}]})

(deftest a-templated-source-carries-its-bound-attribute-everywhere
  (let [c (spi/carried-attributes model)]
    (is (contains? (get c "nifi.flow.processor") :nifi.instance/host)
        "item 13: the connection binding rides the rows it was read with")
    (is (contains? (get c "nifi.flow.relationship") :nifi.instance/host))
    (is (contains? (get c "nifi.flow.bulletin") :nifi.instance/host)
        "every branch, in-record or not")))

(deftest only-an-in-record-branch-carries-the-parent-key
  (let [c (spi/carried-attributes model)]
    (is (= #{:nifi.instance/host :nifi.flow.processor/id}
           (get c "nifi.flow.relationship"))
        "item 17: these rows came out of the parent's own response")
    (is (= #{:nifi.instance/host} (get c "nifi.flow.bulletin"))
        (str "an endpoint-backed child collection carries no parent key — its "
             "rows came from a second call and never held it"))))

(deftest a-plain-source-carries-nothing
  ;; sql, static and derived answer `#{}` from both dispatch points, so their
  ;; models read back exactly as they did before the rulings.
  (is (= {} (spi/carried-attributes
             (assoc-in model [:sources "nifi"] {:type "sql" :jdbc_url "jdbc:x"}))))) 

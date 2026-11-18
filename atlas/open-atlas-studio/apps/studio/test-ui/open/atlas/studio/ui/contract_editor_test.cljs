(ns open.atlas.studio.ui.contract-editor-test
  (:require [cljs.test :refer [deftest is]]
            [open.atlas.studio.ui.contract-editor :as contract-editor]))

(deftest selected-resolver-save-patches-only-that-resolver-and-binding
  (let [document {:vocabulary {:entities {"orders" {:entity/label "Orders"}}
                               :attributes {:orders/id {:attribute/type :integer}}}
                  :execution {:resolvers {"selected" {:label "Before" :input [:orders/id] :output []}
                                          "untouched" {:label "Keep me" :input [] :output [:orders/id]}}
                              :bindings {"selected-binding" {:binding/for [:resolver "selected"]
                                                              :binding/source "db"
                                                              :binding/implementation {:query "before"}}
                                         "untouched-binding" {:binding/for [:resolver "untouched"]
                                                               :binding/source "db"}}}}
        selected {:id "selected" :binding-id "selected-binding"}
        updated (contract-editor/replace-selected
                 document :resolvers selected
                 {:label "After" :input [:orders/id] :output [:orders/id]}
                 {:binding/for [:resolver "selected"] :binding/source "db"
                  :binding/implementation {:query "after"}})]
    (is (= "After" (get-in updated [:execution :resolvers "selected" :label])))
    (is (= "after" (get-in updated [:execution :bindings "selected-binding"
                                    :binding/implementation :query])))
    (is (= (get-in document [:execution :resolvers "untouched"])
           (get-in updated [:execution :resolvers "untouched"])))
    (is (= (get-in document [:execution :bindings "untouched-binding"])
           (get-in updated [:execution :bindings "untouched-binding"])))
    (is (= (:vocabulary document) (:vocabulary updated)))))

(deftest selected-attribute-uses-the-exact-contract-key
  (let [document {:vocabulary {:attributes {:order/id {:attribute/type :integer}
                                            :order/name {:attribute/type :string}}}}
        updated (contract-editor/replace-selected
                 document :attributes
                 {:id "order/id" :contract-id :order/id}
                 {:attribute/type :string :attribute/nullable? false}
                 nil)]
    (is (= :string (get-in updated [:vocabulary :attributes :order/id :attribute/type])))
    (is (= {:attribute/type :string}
           (get-in updated [:vocabulary :attributes :order/name])))
    (is (nil? (get-in updated [:vocabulary :attributes "order/id"])))))

(deftest selected-relationship-save-preserves-other-vector-entries
  (doseq [kind [:aliases :equivalences]]
    (let [original {:vocabulary {kind [{:description "first"} {:description "second"}]}}
          updated (contract-editor/replace-selected original kind
                    {:id "same endpoints" :contract-index 1} {:description "changed"} nil)]
      (is (= [{:description "first"} {:description "changed"}]
             (get-in updated [:vocabulary kind]))))))

(deftest usage-guide-fields-are-optional-and-preserve-other-notes
  (let [guide {:guide/purpose "Purpose" :guide/examples ["example"]}]
    (is (= {:guide/examples ["example"]}
           (contract-editor/update-guide-field guide :guide/purpose "  ")))
    (is (= ["First" "Second"]
           (:guide/limitations (contract-editor/update-guide-field guide :guide/limitations " First\n\nSecond "))))
    (is (= "First\nSecond" (contract-editor/guide-form-value {:guide/limitations ["First" "Second"]} :guide/limitations)))))

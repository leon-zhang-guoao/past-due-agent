(ns open.atlas.common.semantic-protocol-test
  (:require [clojure.test :refer [deftest is]]
            [open.atlas.common.semantic-protocol :as protocol]))

(deftest query-input-is-not-a-mutation-entry
  (is (empty? (:findings (protocol/validate-query-request
                          {:graph/id "support" :request/id "r1" :query [:case/id]}))))
  (is (empty? (:findings (protocol/validate-query-request
                          {:graph/id "support" :request/id "r2" :query [:case/id] :lenient? true}))))
  (is (seq (:findings (protocol/validate-query-request
                       {:graph/id "support" :request/id "r1" :query [(list 'create {})]})))))

(deftest partial-denial-is-explicit-and-does-not-carry-denied-data
  (let [response {:query/status :partial :data {:case/id 1} :pagination {}
                  :issues [{:path [:case/private] :status :denied :message "not permitted"}]}]
    (is (= response (:response (protocol/validate-query-response response))))
    (doseq [r [(assoc-in response [:data :case/private] nil)
               (assoc-in response [:issues 0 :message] "internal graph details")
               (assoc response :query/status :ok)]]
      (is (seq (:findings (protocol/validate-query-response r)))))))

(deftest pagination-exposes-incompleteness
  (let [r {:query/status :ok :data {:case/items [{:case/id 1}]} :issues []
           :pagination {[:case/items] {:limit 1 :has-more? true :next-cursor "opaque"}}}]
    (is (empty? (:findings (protocol/validate-query-response r))))
    (is (seq (:findings (protocol/validate-query-response (assoc-in r [:pagination [:case/items] :next-cursor] nil)))))
    (is (seq (:findings (protocol/validate-query-response (assoc-in r [:data :case/items] [1 2])))))))

(deftest write-receipts-distinguish-unknown-from-uncommitted
  (let [base {:operation/id "create" :request/id "request" :operation/status :committed :receipt/id "receipt" :issues []}]
    (is (= base (:response (protocol/validate-operation-response base))))
    (is (seq (:findings (protocol/validate-operation-response (dissoc base :receipt/id)))))
    (is (seq (:findings (protocol/validate-operation-response (assoc base :operation/status :unknown)))))
    (is (empty? (:findings (protocol/validate-operation-response
                           {:operation/id "create" :request/id "request" :operation/status :unknown
                            :issues [{:path [] :status :unknown :message "check operation status"}]}))))))

(deftest identity-is-not-accepted-from-business-input-envelope
  (let [request {:operation/id "create" :request/id "r" :idempotency/key "key" :input {:case/title "x"}}]
    (is (empty? (:findings (protocol/validate-operation-request request))))
    (is (seq (:findings (protocol/validate-operation-request (assoc request :actor "admin")))))))

(deftest live-grants-are-scoped-to-graph-and-explicit-operations
  (let [grant {:graph/id "support" :principal {:kind :user :issuer "entra" :subject "user"}
               :read? true :operations #{"create-case"}}]
    (is (= grant (:grant (protocol/validate-grant grant))))
    (is (seq (:findings (protocol/validate-grant (assoc grant :role "admin")))))
    (is (seq (:findings (protocol/validate-grant (dissoc grant :operations)))))))


(deftest temporal-results-are-portable-on-edn-and-json-wires
  (let [instant (java.time.Instant/parse "2026-09-08T12:00:00Z")
        result (protocol/wire-value {:rows [{:event/time instant :event/date (java.time.LocalDate/parse "2026-09-08")
                                            :event/size 12M :event/missing nil}]})]
    (is (= "2026-09-08T12:00:00Z" (get-in result [:rows 0 :event/time])))
    (is (= "2026-09-08" (get-in result [:rows 0 :event/date])))
    (is (= result (clojure.edn/read-string (pr-str result))))
    (is (= 12M (get-in result [:rows 0 :event/size])))))

(deftest read-protocol-accepts-ident-joins
  (is (empty? (:findings (protocol/validate-query-request
                          {:graph/id "g" :request/id "r"
                           :query [{[:case/id 1] [:case/title]} {[:case/id 2] [:case/title]}]}))))
  (doseq [k [[:case/id] [:case/id 1 2] ["case/id" 1]]]
    (is (seq (:findings (protocol/validate-query-request
                         {:graph/id "g" :request/id "r" :query [{k [:case/title]}]}))))))

(ns open.atlas.common.connector.api.nifi-test
  (:require [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.api.impl.nifi :as nifi]))

(def uuid "01234567-89ab-cdef-0123-456789abcdef")
(def options {:status 200 :body {:provenanceOptions {:searchableFields [{:id "FlowFileUUID"} {:id "Filename"}]}}})
(defn transport [responses calls]
  (let [remaining (atom responses)]
    (fn [_ request]
      (swap! calls conj request)
      (let [response (first @remaining)]
        (swap! remaining rest)
        (or response (throw (ex-info "Unexpected request" request)))))))
(defn code [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(deftest semantic-reader-does-not-hide-cleanup-failure
  (with-redefs [nifi/query! (fn [& _] {:events [] :warnings [:cleanup-failed]})]
    (is (thrown? clojure.lang.ExceptionInfo
                 ((nifi/compile-read {:operation :provenance :parameter_mapping {}}
                                     {:id "events" :result_type "list"}) {} {})))))

(deftest semantic-reader-maps-eql-params-separately-from-entity-input
  (let [seen (atom nil)
        source {:operation :provenance
                :parameter_mapping {}
                :eql_parameter_mapping {:search-field :search-field
                                        :search-value :search-value}}]
    (with-redefs [nifi/query! (fn [_ _ params _]
                                (reset! seen params)
                                {:events []})]
      ((nifi/compile-read source {:id "search" :result_type "list"})
       {}
       {:search-field "Filename" :search-value "*.csv" :limit 25})
      (is (= {:search-field "Filename" :search-value "*.csv" :max-results 25}
             @seen)))))

(deftest standalone-scope-is-not-sent-as-a-cluster-node
  (let [calls (atom [])]
    (nifi/query! {} :event {:event-id 1 :node-id "standalone"}
                 {:request! (transport [{:status 200 :body {:provenanceEvent {:eventId 1}}}] calls)})
    (is (nil? (:node (first @calls))))))

(deftest immediate-results-preserve-distinct-identities
  (let [calls (atom [])
        result (nifi/query! {} :provenance {:uuid uuid :node-id "node-1" :max-results 1}
                           {:request! (transport [options
                                                 {:status 201 :body {:provenance {:id "job-1" :finished true
                                                                                 :results {:provenanceEvents [{:id "event-uuid" :eventId 123 :flowFileUuid uuid :content "excluded"}]}}}}
                                                 {:status 200}] calls)})]
    (is (= ["GET" "POST" "DELETE"] (mapv :method @calls)))
    (is (= {"FlowFileUUID" {:value uuid :inverse false}}
           (get-in @calls [1 :body :provenance :request :searchTerms])))
    (is (every? #(= "node-1" (:node %)) @calls))
    (is (= [{:id "event-uuid" :eventId 123 :flowFileUuid uuid}] (:events result)))
    (is (:limit-reached? result))))

(deftest polls-and-cleans-lineage
  (let [calls (atom [])
        result (nifi/query! {} :lineage {:uuid uuid}
                           {:sleep! (fn [_])
                            :request! (transport [{:status 201 :body {:lineage {:id "job-2" :finished false}}}
                                                  {:status 200 :body {:lineage {:id "job-2" :finished true :results {:nodes [] :links []}}}}
                                                  {:status 403}] calls)})]
    (is (= ["POST" "GET" "DELETE"] (mapv :method @calls)))
    (is (= [] (:nodes result)))
    (is (= [:cleanup-failed] (:warnings result)))
    (is (= "/provenance/lineage/job-2" (:path (last @calls))))))

(deftest failures-still-clean-created-jobs
  (doseq [[response expected] [[{:status 403} :nifi/http]
                             [{:status 404} :nifi/http]
                             [{:status 200 :body {:lineage {:finished true}}} :nifi/invalid-response]]]
    (let [calls (atom [])]
      (is (= expected (code #(nifi/query! {} :lineage {:uuid uuid}
                                        {:sleep! (fn [_])
                                         :request! (transport [{:status 201 :body {:lineage {:id "job"}}} response {:status 200}] calls)}))))
      (is (= "DELETE" (:method (last @calls)))))))

(deftest timeout-cleans-with-separate-budget
  (let [clock (atom 0) calls (atom [])]
    (is (= :nifi/query-timeout
           (code #(nifi/query! {} :lineage {:uuid uuid}
                              {:deadline-ms 10 :now (fn [] @clock) :sleep! (fn [ms] (swap! clock + ms))
                               :request! (transport [{:status 201 :body {:lineage {:id "job"}}} {:status 200}] calls)}))))
    (is (= ["POST" "DELETE"] (mapv :method @calls)))
    (is (= 1500 (:timeout-ms (last @calls))))))

(deftest rejects-unsupported-search-and-mutations
  (let [calls (atom [])]
    (is (= :nifi/search (code #(nifi/query! {} :provenance {:search-field "orderNumber" :search-value "x"}
                                          {:request! (transport [options] calls)}))))
    (is (= ["GET"] (mapv :method @calls))))
  (is (= :nifi/operation (code #(nifi/query! {} :replay {}))))
  (is (= :nifi/lineage-input (code #(nifi/query! {} :lineage {:uuid "------------------------------------"})))))

(deftest event-details-require-the-addressed-event
  (doseq [event [nil {} {:eventId 2}]]
    (is (= :nifi/invalid-response
           (code #(nifi/query! {} :event {:event-id 1}
                              {:request! (fn [_ _] {:status 200 :body {:provenanceEvent event}})})))))
  (let [calls (atom [])
        r (nifi/query! {} :event {:event-id 1 :node-id "node-2"}
                       {:request! (transport [{:status 200 :body {:provenanceEvent {:eventId 1 :flowFileUuid uuid}}}] calls)})]
    (is (= 1 (get-in r [:event :eventId])))
    (is (= "/provenance-events/1" (:path (first @calls))))))

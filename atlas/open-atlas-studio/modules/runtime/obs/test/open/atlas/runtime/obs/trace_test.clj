(ns open.atlas.runtime.obs.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.error :as p.error]
            [com.wsscode.pathom3.interface.async.eql :as p.a.eql]
            [open.atlas.runtime.obs.impl.trace :as trace]))

(pco/defresolver answer []
  {::pco/output [:test/answer]}
  {:test/answer 42})

(pco/defresolver double-answer [{:test/keys [answer]}]
  {::pco/output [:test/double]}
  {:test/double (* 2 answer)})

(defn- run-query []
  (let [env (-> {::p.error/lenient-mode? true
                 ::p.a.eql/parallel? true}
                (pci/register [answer double-answer]))]
    @(p.a.eql/process env {} [:test/double])))

(deftest summarize-real-run-stats
  (let [result  (run-query)
        summary (trace/summarize-run-stats result)]
    (is (some? summary) "run-stats metadata should be present and parseable")
    (is (>= (:resolver-count summary) 2))
    (is (every? :resolver (:resolvers summary)))
    (is (every? #(contains? % :ok?) (:resolvers summary)))
    (is (symbol? (:slowest summary)))
    (testing "timing + order are captured for the timeline"
      (is (integer? (:span-ms summary)) "wall-clock span of the resolver phase")
      ;; double-answer depends on answer, so answer must start no later
      (let [starts (mapv :start (:resolvers summary))]
        (is (every? integer? starts) "each resolver carries a start offset")
        (is (= starts (sort starts)) ":resolvers are in execution order (by start)")
        (is (zero? (first starts)) "the first resolver's offset is 0")))))

(deftest summarize-nil-safety
  (is (nil? (trace/summarize-run-stats nil)))
  (is (nil? (trace/summarize-run-stats {})))
  (is (nil? (trace/summarize-run-stats
             (with-meta {} {:com.wsscode.pathom3.connect.runner/run-stats "garbage"})))))

(deftest eql-shape-no-values
  (let [shape (trace/eql-shape {:vpo/order_no "SECRET-123"}
                               [:vpo/order_no
                                {:vpo/order_detail [:vpo_order_detail/sku_no]}
                                '({:vpo/all [:vpo/order_no]} {:limit 5})])]
    (is (= [:vpo/order_no] (:entity-keys shape)))
    (is (= [:vpo/order_no :vpo/order_detail :vpo/all] (:query-keys shape)))
    (is (= ["vpo" "vpo_order_detail"] (:entity-namespaces shape)) "nested join namespaces too")
    (is (not (str/includes? (pr-str shape) "SECRET")))))

(deftest eql-shape-nil-safety
  (is (= {:entity-keys [] :query-keys [] :entity-namespaces []} (trace/eql-shape nil nil))))

(deftest wrap-request-id-basics
  (let [handler (trace/wrap-request-id
                 (fn [req] {:status 200 :headers {} :body (:obs/request-id req)}))
        resp    (handler {:uri "/x"})]
    (is (string? (get-in resp [:headers "X-Request-Id"])))
    (is (= (:body resp) (get-in resp [:headers "X-Request-Id"]))
        "request map and response header carry the same id")
    (is (= 8 (count (:body resp))))))

(deftest wrap-request-id-binds-dynamic-var
  (let [seen    (atom nil)
        handler (trace/wrap-request-id
                 (fn [_] (reset! seen trace/*request-id*) {:status 200 :headers {}}))]
    (handler {:uri "/x"})
    (is (string? @seen))))

(deftest wrap-request-id-nil-response-passthrough
  (let [handler (trace/wrap-request-id (constantly nil))]
    (is (nil? (handler {:uri "/x"})))))

(deftest wrap-request-id-honors-incoming-header
  (let [handler (trace/wrap-request-id
                 (fn [req] {:status 200 :headers {} :body (:obs/request-id req)}))
        resp    (handler {:uri "/x" :headers {"x-request-id" "client-id-1"}})]
    (is (= "client-id-1" (:body resp)))
    (is (= "client-id-1" (get-in resp [:headers "X-Request-Id"])))))

(deftest wrap-request-id-clears-dynamic-var-after-handler
  (let [handler (trace/wrap-request-id (constantly {:status 200 :headers {}}))]
    (handler {:uri "/x"})
    (is (nil? trace/*request-id*))))

(deftest summarize-garbage-map-safety
  (is (nil? (trace/summarize-run-stats
             (with-meta {}
               {:com.wsscode.pathom3.connect.runner/run-stats
                {:com.wsscode.pathom3.connect.planner/nodes nil
                 :com.wsscode.pathom3.connect.runner/node-run-stats nil}})))))

(deftest eql-shape-ident-vector
  (is (= [:person/by-id]
         (:query-keys (trace/eql-shape {} [[:person/by-id 42]])))))

(deftest eql-shape-non-map-entity
  (is (= {:entity-keys [] :query-keys [:a/b] :entity-namespaces ["a"]}
         (trace/eql-shape 42 [:a/b])))
  (is (= {:entity-keys [] :query-keys [] :entity-namespaces []}
         (trace/eql-shape "x" :not-a-vector))))

(deftest extract-upstream-request-id-test
  (is (= "abc-123" (trace/extract-upstream-request-id {:requestId "abc-123"})))
  (is (= "xyz" (trace/extract-upstream-request-id {"requestId" "xyz"})))
  (is (nil? (trace/extract-upstream-request-id {:data {:x 1}}))))

(deftest summarize-trace-merges-http-and-pathom
  ;; HTTP call records lead and surface upstream ids; with no run-stats the
  ;; summary is the call trace itself.
  (binding [trace/*call-trace* (atom [])]
    (trace/record-resolver-call! {:resolver "ec_product_summary_batch" :ms 120 :ok? true
                                  :upstream-request-id "up-1"})
    (trace/record-resolver-call! {:resolver "mycis_get_end_customer_by_eu_nos" :ms 80 :ok? true
                                  :upstream-request-id "up-2"})
    (let [summary (trace/summarize-trace {} (vec @trace/*call-trace*))]
      (is (= 2 (:resolver-count summary)))
      (is (= "ec_product_summary_batch" (:slowest summary)))
      (is (= "up-1" (-> summary :resolvers first :upstream-request-id))))))

(deftest summarize-trace-keeps-run-stats-timeline-when-no-http
  ;; no HTTP calls → summarize-trace returns the run-stats summary unchanged
  ;; (preserving :start/:span-ms execution-order timeline).
  (let [result (run-query)]
    (is (= (trace/summarize-run-stats result) (trace/summarize-trace result nil)))))

(ns open.atlas.common.engine.nested-collection-test
  "doc 106 §106.14 item 15 (owner 2026-09-07): a join branch may declare the
   ARRAY its rows come from, so a collection that lives INSIDE its host record
   is read by the request that reads the record.

   What the engine owes that ruling is nothing new, and that is the claim this
   file measures: a connector answering `{B/as [row …]}` for a `\"list\"`
   resolver is a branch it ALREADY answered, so `nest-row` leaves it alone
   (the item 5/6 rule), the join root lifts the rows exactly as it lifts a
   root collection's, and the URL-bound attributes of item 13 are written into
   those rows like any other child's. The rows here are what
   `connector-api`'s `response->single` hands over for such a
   `:field_mapping` — that module's own tests measure the mapping; this one
   measures what the engine does with the result."
  (:require [clojure.test :refer [deftest testing is]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

;; --- the fake connector -----------------------------------------------------
;; Registered on the REAL SPI under a type no connector uses (same device as
;; `fake-connector` and `url-bound-join-test`): the engine reaches it through
;; the seam the api connector uses, not through a door opened for the test.

(def ^:private by-host
  {"h1" {"g1" {:nifi.pg/name "root"
               :nifi.pg/processors [{:nifi.proc/id "p1"} {:nifi.proc/id "p2"}]
               :nifi.pg/samples [{:nifi.queue/count 3} {:nifi.queue/count 5}]}
         "g2" {:nifi.pg/name "empty"
               ;; An EMPTY array is an empty collection; an ABSENT one is nil.
               :nifi.pg/processors []
               :nifi.pg/samples nil}}
   "h2" {"g1" {:nifi.pg/name "root-h2"
               :nifi.pg/processors [{:nifi.proc/id "px"}]
               :nifi.pg/samples []}}})

(def ^:private proc-names
  {"h1" {"p1" "GenerateFlowFile" "p2" "LogAttribute"}
   "h2" {"px" "PutFile"}})

(defmethod spi/connection-bound-attributes "test-nested"
  [source-config]
  (into #{} (map (comp keyword second))
        (re-seq #"\{([^}]+)\}" (str (:base_url source-config)))))

(defmethod spi/compile-source "test-nested"
  [_source-config _connection resolver-config]
  (fn [in* _eql-params]
    (let [host (:nifi.instance/host in*)
          rec  (get-in by-host [host (:nifi.pg/id in*)])]
      (case (:id resolver-config)
        "pg_by_id" (when rec (select-keys rec [:nifi.pg/name]))
        ;; The in-record shapes: ONE record whose branch is already assembled,
        ;; which is what `response->single` answers even under `list`.
        "pg_processors" (when rec (select-keys rec [:nifi.pg/processors]))
        "pg_samples"    (when rec (select-keys rec [:nifi.pg/samples]))
        "proc_by_id" (when-let [n (get-in proc-names [host (:nifi.proc/id in*)])]
                       {:nifi.proc/name n})))))

;; --- the model ---------------------------------------------------------------

(def ^:private base
  {:sources {"nifi" {:type "api"
                     :base_url "https://{nifi.instance/host}:9443/nifi-api"}}
   :namespaces {"nifi.instance" {:label "NiFi 实例"}
                "nifi.pg"       {:label "Process group"}
                "nifi.proc"     {:label "Processor"}
                "nifi.queue"    {:label "排队采样点"}}
   :attributes
   {:nifi.instance/host {:type :string :identity? true :format :id :label "主机名"}
    :nifi.pg/id         {:type :string :identity? true :format :id :label "组 id"}
    :nifi.pg/name       {:type :string :format :text :label "组名"}
    :nifi.pg/processors {:cardinality :one-to-many :target "nifi.proc" :label "组里的 processor"}
    ;; A child with NO identity: a sampling point, which reads back as a
    ;; `:series` fragment (§106.14 item 10) rather than a thing.
    :nifi.pg/samples    {:cardinality :one-to-many :target "nifi.queue" :label "排队采样"}
    :nifi.proc/id       {:type :string :identity? true :format :id :label "processor id"}
    :nifi.proc/name     {:type :string :format :text :label "processor 名"}
    :nifi.queue/count   {:type :integer :format :number :label "排队数"}}})

(def ^:private pg-by-id
  {:id "pg_by_id" :label "按 id 取组"
   :input [:nifi.pg/id :nifi.instance/host]
   :output [:nifi.pg/name]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/process-groups/{nifi.pg/id}"
            :field_mapping {:nifi.pg/name "$.component.name"}}})

;; **No `:limit`** — the D2.8 exemption this ruling grants: the rows are
;; bounded by the record they came in, so there is no next page to ask for.
(def ^:private pg-processors
  {:id "pg_processors" :label "组里的 processor（就在它自己的记录里）"
   :input [:nifi.pg/id :nifi.instance/host]
   :output [{:nifi.pg/processors [:nifi.proc/id]}]
   :result_type "list" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/process-groups/{nifi.pg/id}"
            :field_mapping {:nifi.pg/processors {:_from "$.processors[*]"
                                                 :nifi.proc/id "$.component.id"}}}})

(def ^:private pg-samples
  {:id "pg_samples" :label "组的排队采样"
   :input [:nifi.pg/id :nifi.instance/host]
   :output [{:nifi.pg/samples [:nifi.queue/count]}]
   :result_type "list" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/process-groups/{nifi.pg/id}"
            :field_mapping {:nifi.pg/samples {:_from "$.status.samples[*]"
                                              :nifi.queue/count "$.queued"}}}})

(def ^:private proc-by-id
  {:id "proc_by_id" :label "按 id 取 processor"
   :input [:nifi.proc/id :nifi.instance/host]
   :output [:nifi.proc/name]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/processors/{nifi.proc/id}"
            :field_mapping {:nifi.proc/name "$.component.name"}}})

(defn- read-model [resolvers]
  (dialect/read-dialect (pr-str (assoc base :resolvers resolvers))))

(defn- retype [model t]
  (-> model
      (update :sources update-vals #(assoc % :type t))
      (update :resolvers (fn [rs] (mapv #(assoc-in % [:source :type] t) rs)))))

(defn- compiled [resolvers]
  (let [{:keys [model findings]} (read-model resolvers)]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    (engine/compile-env (retype model "test-nested"))))

(defn- q [c request] (engine/query c nil request))

(defn- output-of [c id]
  (-> (get-in c [:engine/bundle :env ::pci/index-resolvers])
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config
      ::pco/output))

;; ---------------------------------------------------------------------------

(deftest an-in-record-collection-needs-no-limit
  (testing "D2.8's :limit is not asked of rows the host record already bounds"
    (is (empty? (filter #(= :dialect.invariant/list-limit-missing (:code %))
                        (:findings (read-model [pg-processors]))))))
  (testing "and a list resolver WITHOUT such a branch is asked for it as before"
    (let [fs (:findings (read-model
                         [(update-in pg-processors [:source :field_mapping]
                                     (constantly {:nifi.proc/id "$[*].component.id"}))]))]
      (is (some #(= :dialect.invariant/list-limit-missing (:code %)) fs)))))

(deftest the-rows-come-out-of-the-join-root
  (let [c (compiled [pg-by-id pg-processors])]
    (is (empty? (:engine/findings c)))
    (let [r (q c {:entity {:nifi.pg/id "g1" :nifi.instance/host "h1"}
                  :query [:nifi.pg/name {:nifi.pg/processors [:nifi.proc/id]}]})]
      (is (= :ok (:engine/status r)))
      (is (= {:nifi.pg/name "root"
              :nifi.pg/processors [{:nifi.proc/id "p1"} {:nifi.proc/id "p2"}]}
             (:engine/data r))))))

(deftest a-child-with-an-id-chains-into-its-own-by-key-resolver
  ;; The point of «有 id 的子对象可为东西并继续链式»: the collection carries
  ;; only the id, and the name comes from the child's own lookup.
  (let [c (compiled [pg-by-id pg-processors proc-by-id])
        r (q c {:entity {:nifi.pg/id "g1" :nifi.instance/host "h1"}
                :query [{:nifi.pg/processors [:nifi.proc/id :nifi.proc/name]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:nifi.pg/processors [{:nifi.proc/id "p1" :nifi.proc/name "GenerateFlowFile"}
                                 {:nifi.proc/id "p2" :nifi.proc/name "LogAttribute"}]}
           (:engine/data r)))))

(deftest the-url-bound-attribute-rides-into-the-in-record-rows
  ;; §106.14 item 13 over item 15's rows: without this the chain above cannot
  ;; happen at all — `proc_by_id` needs the host, and no response row carries it.
  (let [c (compiled [pg-processors proc-by-id])]
    (is (= [{:nifi.pg/processors [:nifi.proc/id :nifi.instance/host]}]
           (output-of c "pg_processors")))
    (testing "so another host answers its own subtree"
      (is (= {:nifi.pg/processors [{:nifi.proc/name "PutFile"}]}
             (:engine/data (q c {:entity {:nifi.pg/id "g1" :nifi.instance/host "h2"}
                                 :query [{:nifi.pg/processors [:nifi.proc/name]}]})))))))

(deftest a-child-without-an-identity-is-read-as-a-series
  (let [c (compiled [pg-samples])
        r (q c {:entity {:nifi.pg/id "g1" :nifi.instance/host "h1"}
                :query [{:nifi.pg/samples [:nifi.queue/count]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:nifi.pg/samples [{:nifi.queue/count 3} {:nifi.queue/count 5}]}
           (:engine/data r)))
    (testing "need-view calls such a namespace a :series hanging off its parent"
      (let [v (dialect/need-view (:model (read-model [pg-by-id pg-samples])))]
        (is (= [{:kind :series :name "nifi.queue" :parent "nifi.pg/samples"}]
               (mapv #(select-keys % [:kind :name :parent])
                     (filter #(= "nifi.queue" (:name %)) (:fragments v)))))))))

(deftest an-empty-collection-answers-empty
  ;; The nil / [] distinction is the CONNECTOR's (rows-test measures it): an
  ;; absent array is «no such collection», an empty one «none of them». Under a
  ;; `"list"` resolver the engine's own contract then flattens both to a list,
  ;; as it always has — a list resolver answers rows, never nil.
  (let [c (compiled [pg-processors pg-samples])]
    (is (= {:nifi.pg/processors []}
           (:engine/data (q c {:entity {:nifi.pg/id "g2" :nifi.instance/host "h1"}
                               :query [{:nifi.pg/processors [:nifi.proc/id]}]}))))
    (is (= {:nifi.pg/samples []}
           (:engine/data (q c {:entity {:nifi.pg/id "g2" :nifi.instance/host "h1"}
                               :query [{:nifi.pg/samples [:nifi.queue/count]}]}))))))

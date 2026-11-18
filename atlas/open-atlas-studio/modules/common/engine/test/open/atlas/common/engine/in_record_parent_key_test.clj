(ns open.atlas.common.engine.in-record-parent-key-test
  "doc 106 §106.14 item 17 (owner 2026-09-07): the parent record's IDENTITY
   attributes ride an in-record collection (`:_from`) into its child rows.

   Item 13 carried a source's URL binding across a join for one reason — the
   parent already knew the value and Pathom 3's plain nested join inherits
   nothing. An in-record collection's rows are in the same position: they were
   assembled out of the parent's own response, so the parent's key is known at
   assembly time and simply never appeared in the child's fields. Item 17
   therefore GENERALISES item 13 rather than adding a second mechanism: one
   table of «what this branch carries», fed by bound ∪ ancestor identities for
   `:_from` branches and by bound alone for endpoint-backed ones.

   What that buys, and what this file measures: an in-record child may be a
   thing with a COMPOUND identity that includes the parent's key
   (`relationship` known by `[processor_id name]`), its by-key resolver is
   reachable from the in-record rows, and a child row can chain back to its
   parent. An endpoint-backed child collection is untouched — its rows came
   from a second call and never held the parent's key.

   The dialect `:output` and the recipe are unchanged; this is bookkeeping over
   `:input` and the declared identities."
  (:require [clojure.test :refer [deftest testing is]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

;; --- the fake connector -----------------------------------------------------
;; Registered on the REAL SPI under a type no connector uses (the device
;; `fake-connector`, `url-bound-join-test` and `nested-collection-test` all
;; use): the engine reaches it through the same seam the api connector uses,
;; not through a door opened for the test.

(def ^:private by-host
  {"h1" {"p1" {:name "GenerateFlowFile"
               :children ["c1" "c2"]
               :rels {"success" {:auto false :dests [{:x.dest/port 1} {:x.dest/port 2}]}
                      "failure" {:auto true  :dests []}}}}
   "h2" {"p1" {:name "PutFile"
               :rels {"success" {:auto true :dests [{:x.dest/port 9}]}}}}})

(defn- from-key-branches
  "The `:_from` branches of a `:field_mapping`, at any depth — what the api
   connector answers for real. Spelled out here so the engine is measured
   through the SPI rather than through that connector's own code."
  [field-mapping]
  (reduce-kv (fn [acc attr v]
               (if (and (map? v) (contains? v :_from))
                 (into (conj acc attr) (from-key-branches (dissoc v :_from)))
                 acc))
             #{} (or field-mapping {})))

(defmethod spi/connection-bound-attributes "test-inrecord"
  [source-config]
  (into #{} (map (comp keyword second))
        (re-seq #"\{([^}]+)\}" (str (:base_url source-config)))))

(defmethod spi/in-record-collection-attributes "test-inrecord"
  [source-config]
  (from-key-branches (:field_mapping source-config)))

(defmethod spi/compile-source "test-inrecord"
  [_source-config _connection resolver-config]
  (fn [in* _eql-params]
    (let [rec (get-in by-host [(:x.instance/host in*) (:x.proc/id in*)])]
      (case (:id resolver-config)
        "proc_by_id" (when rec {:x.proc/name (:name rec)})
        ;; ONE record whose branches are already assembled — what a
        ;; `:_from` field_mapping answers even under `:result_type "list"`.
        "proc_relationships"
        (when rec
          {:x.proc/relationships
           (mapv (fn [[n r]] {:x.rel/name n :x.rel/destinations (:dests r)})
                 (sort-by key (:rels rec)))})
        ;; The child's by-key read: it needs BOTH parts of the compound key,
        ;; and the parent's part can only have come across the join.
        "rel_by_key"
        (when-let [r (get-in rec [:rels (:x.rel/name in*)])]
          {:x.rel/auto_terminate (:auto r)})
        ;; An endpoint-backed child collection: a SECOND call, whose rows
        ;; never held the parent's key.
        "proc_provenance"
        (when rec {:x.proc/events [{:x.event/seq 1} {:x.event/seq 2}]})
        ;; A SELF-nesting in-record collection: the children are processors
        ;; too, so the carried key and the child's own key are one attribute.
        "proc_children"
        (when rec {:x.proc/children (mapv (fn [c] {:x.proc/id c
                                                   :x.proc/name (str "child-" c)})
                                          (:children rec))})))))

;; --- the model ---------------------------------------------------------------

(def ^:private base
  {:sources {"nifi" {:type "api"
                     :base_url "https://{x.instance/host}:9443/nifi-api"}}
   :namespaces {"x.instance" {:label "实例"}
                "x.proc"     {:label "Processor"}
                "x.rel"      {:label "关系"}
                "x.dest"     {:label "关系去向"}
                "x.event"    {:label "溯源事件"}}
   :attributes
   {:x.instance/host      {:type :string :identity? true :format :id :label "主机名"}
    :x.proc/id            {:type :string :identity? true :format :id :label "processor id"}
    :x.proc/name          {:type :string :format :text :label "processor 名"}
    :x.proc/relationships {:cardinality :one-to-many :target "x.rel" :label "它的关系"}
    :x.proc/events        {:cardinality :one-to-many :target "x.event" :label "溯源事件"}
    :x.proc/children      {:cardinality :one-to-many :target "x.proc" :label "它里面的 processor"}
    ;; The child's own half of a compound identity: a relationship is known by
    ;; `[processor_id name]`, and only `name` lives in its own namespace.
    :x.rel/name           {:type :string :identity? true :format :id :label "关系名"}
    :x.rel/auto_terminate {:type :boolean :format :code :label "自动终止"}
    :x.rel/destinations   {:cardinality :one-to-many :target "x.dest" :label "去向"}
    :x.dest/port          {:type :integer :format :number :label "端口序号"}
    :x.event/seq          {:type :integer :format :number :label "事件序号"}}})

(def ^:private proc-by-id
  {:id "proc_by_id" :label "按 id 取 processor"
   :input [:x.proc/id :x.instance/host]
   :output [:x.proc/name]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/processors/{x.proc/id}"
            :field_mapping {:x.proc/name "$.component.name"}}})

;; Two levels of `:_from`: the processor's relationships, and each
;; relationship's destinations — both assembled out of the SAME response.
(def ^:private proc-relationships
  {:id "proc_relationships" :label "processor 的关系（就在它自己的记录里）"
   :input [:x.proc/id :x.instance/host]
   :output [{:x.proc/relationships
             [:x.rel/name {:x.rel/destinations [:x.dest/port]}]}]
   :result_type "list" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/processors/{x.proc/id}"
            :field_mapping
            {:x.proc/relationships
             {:_from "$.component.relationships[*]"
              :x.rel/name "$.name"
              :x.rel/destinations {:_from "$.destinations[*]"
                                   :x.dest/port "$.port"}}}}})

(def ^:private rel-by-key
  {:id "rel_by_key" :label "按 [processor_id name] 取关系"
   :input [:x.proc/id :x.rel/name :x.instance/host]
   :output [:x.rel/auto_terminate]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :method "GET"
            :endpoint "/processors/{x.proc/id}/relationships/{x.rel/name}"
            :field_mapping {:x.rel/auto_terminate "$.autoTerminate"}}})

(def ^:private proc-provenance
  {:id "proc_provenance" :label "processor 的溯源事件（另一个端点）"
   :input [:x.proc/id :x.instance/host]
   :output [{:x.proc/events [:x.event/seq]}]
   :result_type "list" :limit {:default 50 :max 200} :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/processors/{x.proc/id}/provenance"
            :field_mapping {:x.event/seq "$.eventId"}}})

(def ^:private proc-children
  {:id "proc_children" :label "processor 里的 processor（就在它自己的记录里）"
   :input [:x.proc/id :x.instance/host]
   :output [{:x.proc/children [:x.proc/id :x.proc/name]}]
   :result_type "list" :source-ref "nifi"
   :source {:type "api" :method "GET" :endpoint "/processors/{x.proc/id}"
            :field_mapping {:x.proc/children {:_from "$.children[*]"
                                              :x.proc/id "$.id"
                                              :x.proc/name "$.name"}}}})

(defn- read-model [resolvers]
  (let [{:keys [model findings]}
        (dialect/read-dialect (pr-str (assoc base :resolvers resolvers)))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(defn- retype
  "Swap the source type to the fake connector's, in memory and AFTER
   `read-dialect` — dialect-v1 §3 keeps `:type` to a controlled vocabulary,
   so the model is written as the \"api\" it stands for."
  [model t]
  (-> model
      (update :sources update-vals #(assoc % :type t))
      (update :resolvers (fn [rs] (mapv #(assoc-in % [:source :type] t) rs)))))

(defn- compiled [resolvers]
  (engine/compile-env (retype (read-model resolvers) "test-inrecord")))
(defn- q [c request] (engine/query c nil request))

(defn- output-of
  "The `::pco/output` Pathom actually indexed — the declaration a planner
   reads, which is exactly what item 17 widens."
  [c id]
  (-> (get-in c [:engine/bundle :env ::pci/index-resolvers])
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config
      ::pco/output))

;; ---------------------------------------------------------------------------

(deftest a-two-level-in-record-collection-declares-both-ancestors-keys
  (let [c (compiled [proc-relationships])]
    (is (empty? (:engine/findings c)))
    (testing "each level gains its ancestors' identities, and the binding"
      ;; `x.proc/id` is the parent's key and `x.instance/host` item 13's: the
      ;; two arrive by the same mechanism, which is the point of item 17.
      (is (= [{:x.proc/relationships
               [:x.rel/name
                {:x.rel/destinations [:x.dest/port :x.instance/host :x.proc/id :x.rel/name]}
                :x.instance/host :x.proc/id]}]
             (output-of c "proc_relationships"))))))

(deftest the-rows-carry-both-ancestors-keys
  (let [c (compiled [proc-relationships])
        r (q c {:entity {:x.instance/host "h1" :x.proc/id "p1"}
                :query [{:x.proc/relationships
                         [:x.rel/name :x.proc/id
                          {:x.rel/destinations [:x.dest/port :x.proc/id :x.rel/name]}]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:x.proc/relationships
            [{:x.rel/name "failure" :x.proc/id "p1" :x.rel/destinations []}
             {:x.rel/name "success" :x.proc/id "p1"
              :x.rel/destinations [{:x.dest/port 1 :x.proc/id "p1" :x.rel/name "success"}
                                   {:x.dest/port 2 :x.proc/id "p1" :x.rel/name "success"}]}]}
           (:engine/data r)))))

(deftest a-compound-identity-including-the-parents-key-is-reachable
  ;; `rel_by_key` needs `[x.proc/id x.rel/name]` (plus the host). Before item
  ;; 17 the in-record rows held only `x.rel/name`, so this was
  ;; `:engine.request/unreachable` — the shape §106.12 10.1 measured.
  (let [c (compiled [proc-relationships rel-by-key])
        r (q c {:entity {:x.instance/host "h1" :x.proc/id "p1"}
                :query [{:x.proc/relationships [:x.rel/name :x.rel/auto_terminate]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:x.proc/relationships [{:x.rel/name "failure" :x.rel/auto_terminate true}
                                   {:x.rel/name "success" :x.rel/auto_terminate false}]}
           (:engine/data r)))))

(deftest a-child-row-can-chain-back-to-its-parent
  ;; From inside a relationship, back to the processor's own name: the
  ;; parent's key travelled with the row, so `proc_by_id` is reachable there.
  (let [c   (compiled [proc-relationships proc-by-id])
        ask (fn [h] (q c {:entity {:x.instance/host h :x.proc/id "p1"}
                          :query [{:x.proc/relationships [:x.rel/name :x.proc/name]}]}))]
    (is (= {:x.proc/relationships [{:x.rel/name "failure" :x.proc/name "GenerateFlowFile"}
                                   {:x.rel/name "success" :x.proc/name "GenerateFlowFile"}]}
           (:engine/data (ask "h1"))))
    ;; And the host axis still holds: another instance answers its own record.
    (is (= {:x.proc/relationships [{:x.rel/name "success" :x.proc/name "PutFile"}]}
           (:engine/data (ask "h2"))))))

(deftest an-endpoint-backed-child-collection-is-unchanged
  ;; Its rows came from a SECOND call and never held the parent's key, so item
  ;; 17 must not claim otherwise. Only item 13's binding is added — byte for
  ;; byte what this resolver compiled to before item 17.
  (let [c (compiled [proc-provenance])]
    (is (= [{:x.proc/events [:x.event/seq :x.instance/host]}]
           (output-of c "proc_provenance")))
    (let [r (q c {:entity {:x.instance/host "h1" :x.proc/id "p1"}
                  :query [{:x.proc/events [:x.event/seq]}]})]
      (is (= {:x.proc/events [{:x.event/seq 1} {:x.event/seq 2}]} (:engine/data r))))))

(deftest a-source-that-carries-nothing-compiles-the-tree-it-declared
  ;; sql / static / derived bind nothing and have no `:_from`: their
  ;; `::pco/output` must be the declared `:output`, unchanged.
  (let [plain (-> (read-model [proc-relationships proc-provenance])
                  (retype "test-inrecord")
                  (update-in [:sources "nifi"] dissoc :base_url)
                  (update :resolvers
                          (fn [rs] (mapv #(update % :source dissoc :field_mapping) rs))))
        c     (engine/compile-env plain)]
    (is (= (:output proc-relationships) (output-of c "proc_relationships")))
    (is (= (:output proc-provenance) (output-of c "proc_provenance")))))

(deftest a-child-keeps-its-own-key-when-the-branch-is-its-own-namespace
  ;; A carried value COMPLETES a row; it never overrules one. When the children
  ;; are the same kind as their host — a status record inside a status record —
  ;; the carried parent key and the child's own key are ONE attribute, and
  ;; merging the carried value last gave every child its PARENT's id (measured
  ;; on nifi-local v14: each child status then answered the root's queues).
  (let [c (compiled [proc-children])
        r (q c {:entity {:x.instance/host "h1" :x.proc/id "p1"}
                :query [{:x.proc/children [:x.proc/id :x.proc/name]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:x.proc/children [{:x.proc/id "c1" :x.proc/name "child-c1"}
                              {:x.proc/id "c2" :x.proc/name "child-c2"}]}
           (:engine/data r)))))

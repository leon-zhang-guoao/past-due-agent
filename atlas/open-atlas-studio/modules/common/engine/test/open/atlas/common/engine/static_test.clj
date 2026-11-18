(ns open.atlas.common.engine.static-test
  "8.17: a `\"static\"` operation block is translated by the engine into
   Pathom's static-table / global-data semantics — not dispatched through the
   connector SPI (static-v1 has no callable source, same class as a link).
   Exit conditions of doc 102 §8.17.7, in-memory models only."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [open.atlas.common.connector.spi :as real-spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.engine.fake-connector :as fake]))

(def ^:private instances
  [{:nifi.instance/host "nifi-a.corp" :nifi.instance/name "prod-a" :nifi.instance/env "prod"}
   {:nifi.instance/host "nifi-b.corp" :nifi.instance/name "prod-b" :nifi.instance/env "prod"}
   {:nifi.instance/host "localhost"   :nifi.instance/name "local"  :nifi.instance/env "dev"}])

(def ^:private base
  {:namespaces {"nifi.instance" {:label "NiFi 实例"}
                "nifi.pg"       {:label "Process group"}}
   :attributes
   {:nifi.instance/host {:type :string :identity? true :format :id :label "主机名"}
    :nifi.instance/name {:type :string :format :text :label "实例名"}
    :nifi.instance/env  {:type :string :format :code :label "环境"
                         :values {"prod" {:label "生产"} "dev" {:label "开发"}}}
    :nifi.instance/all_instances {:cardinality :one-to-many :target "nifi.instance"
                                  :label "全部实例"}
    :nifi.instance/groups {:cardinality :one-to-many :target "nifi.pg" :label "该实例的组"}
    :nifi.pg/name {:type :string :format :text :label "组名"}}})

(defn- read-model
  "Build the model the way the pool does: through `read-dialect`, so the
   dialect's own `StaticOp` write-time validation runs first."
  [resolvers]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str (assoc base :resolvers resolvers)))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:private list-resolver
  {:id "nifi_instance_all_instances" :label "NiFi 实例清单"
   :input [] :output [{:nifi.instance/all_instances [:nifi.instance/host :nifi.instance/name :nifi.instance/env]}]
   :result_type "list" :limit {:default 50 :max 200}
   :source {:type "static" :data instances}})

(def ^:private by-host-resolver
  {:id "nifi_instance_by_host" :label "按主机名取实例"
   :input [:nifi.instance/host] :output [:nifi.instance/name :nifi.instance/env]
   :result_type "single"
   :source {:type "static" :match-on :nifi.instance/host :data instances}})

(defn- q [compiled request] (engine/query compiled nil request))
(defn- finding-codes [compiled] (set (map :code (:engine/findings compiled))))

;; ---------------------------------------------------------------------------

(deftest static-is-not-a-registered-spi-type
  (is (not (contains? (real-spi/registered-types) "static"))
      "8.17: static is engine translation, not a connector; registered-types omitting it is design"))

(deftest a-list-static-resolver-answers-the-whole-data-set
  (let [c (engine/compile-env (read-model [list-resolver]))]
    (is (empty? (:engine/findings c)))
    (testing "global query returns every row, pruned to the requested fields"
      (let [r (q c {:entity {} :query [{:nifi.instance/all_instances
                                        [:nifi.instance/host :nifi.instance/name :nifi.instance/env]}]})]
        (is (= :ok (:engine/status r)))
        (is (= instances (get-in r [:engine/data :nifi.instance/all_instances])))))
    (testing ":limit is honoured through the ordinary resolve-params path"
      (let [c2 (engine/compile-env (read-model [(assoc list-resolver :limit {:default 2 :max 5})]))
            r  (q c2 {:entity {} :query [{:nifi.instance/all_instances [:nifi.instance/host]}]})]
        (is (= :ok (:engine/status r)))
        (is (= ["nifi-a.corp" "nifi-b.corp"]
               (mapv :nifi.instance/host (get-in r [:engine/data :nifi.instance/all_instances]))))))))

(deftest a-list-static-resolver-without-a-join-anchor-is-refused-by-the-existing-gate
  (let [c (engine/compile-env (read-model [(assoc list-resolver
                                                  :output [:nifi.instance/host :nifi.instance/name])]))]
    (is (contains? (finding-codes c) :engine.compile/list-without-anchor))
    (is (empty? (:attributes (engine/catalog c))) "nothing was loaded")))

(deftest a-match-on-static-resolver-indexes-rows-by-the-input-value
  (let [c (engine/compile-env (read-model [by-host-resolver]))]
    (is (empty? (:engine/findings c)))
    (testing "a hit answers that row, cut to :output — the host key is not in :output (D2.19)"
      (let [r (q c {:entity {:nifi.instance/host "nifi-b.corp"}
                    :query [:nifi.instance/name :nifi.instance/env]})]
        (is (= :ok (:engine/status r)))
        (is (= {:nifi.instance/name "prod-b" :nifi.instance/env "prod"}
               (select-keys (:engine/data r) [:nifi.instance/name :nifi.instance/env :nifi.instance/host])))))
    (testing "a miss answers nil from the resolver itself — conformance, not a failure"
      ;; The resolver is a plain Pathom resolver in the compiled index; called
      ;; directly it returns nil for a key no row carries, and nothing is thrown.
      (let [r (get-in c [:engine/bundle :env ::pci/index-resolvers
                         'open.atlas.common.engine.generated/nifi_instance_by_host])]
        (is (some? r) "the static resolver is in the index")
        (is (nil? (r {} {:nifi.instance/host "nowhere"})))))
    (testing "through the engine a miss is an ANSWER: 200-equivalent, attribute absent"
      ;; Defect 11 of the 0.2 release notes: a `:match-on` miss used to answer
      ;; `:engine.execution/failed`, which reads as «the source broke». It did
      ;; not — there is no such row. Same reading for sql `"single"`.
      (let [r (q c {:entity {:nifi.instance/host "nowhere"} :query [:nifi.instance/name]})]
        (is (= :ok (:engine/status r)))
        (is (not (contains? (:engine/data r) :nifi.instance/name)) "absent, not nil-valued")
        (is (= {:anchor [:nifi.instance/host] :attributes [:nifi.instance/name]}
               (:engine/not-found r))))
      (let [r (engine/query c nil {:entity {:nifi.instance/host "nowhere"} :query [:nifi.instance/name]}
                            {:lenient? true})]
        (is (= :ok (:engine/status r)))
        (is (nil? (get-in r [:engine/data :nifi.instance/name])))))))

(deftest match-on-must-be-one-of-the-inputs
  (let [c (engine/compile-env (read-model [(assoc by-host-resolver :input [:nifi.instance/name])]))
        f (first (filter #(= :engine.compile/static-match-on-not-input (:code %)) (:engine/findings c)))]
    (is (some? f))
    (is (= :error (:severity f)))
    (is (= [:resolvers "nifi_instance_by_host"] (:path f)))
    (is (= :nifi.instance/host (:got f)))))

(deftest row-security-on-a-static-resolver-is-refused-at-compile-time
  (let [c (engine/compile-env (read-model [(assoc by-host-resolver
                                                  :row-security {:attribute :nifi.instance/host})]))
        f (first (filter #(= :engine.compile/static-row-security (:code %)) (:engine/findings c)))]
    (is (some? f))
    (is (= :error (:severity f)))
    (is (= ["static-v1" "8.17"] (:refs f)))
    (is (= :engine.catalog/unknown-attribute
           (:code (q c {:entity {:nifi.instance/host "localhost"} :query [:nifi.instance/name]})))
        "not loaded: the attribute is unreachable")))

(deftest an-order-declaration-on-a-static-resolver-is-refused-at-compile-time
  ;; static-v1 grants static no ordering capability, so a declaration the
  ;; contract withholds is an error rather than something the engine sorts for
  ;; itself in memory (owner 2026-09-05).
  (let [c (engine/compile-env
           (read-model [(assoc list-resolver
                               :order {:allowed [:nifi.instance/host]
                                       :default [:nifi.instance/host :asc]})]))
        f (first (filter #(= :engine.compile/static-order-unsupported (:code %))
                         (:engine/findings c)))]
    (is (some? f))
    (is (= :error (:severity f)))
    (is (= [:resolvers "nifi_instance_all_instances"] (:path f)))
    (is (= ["static-v1" "8.17"] (:refs f)))
    ;; `:attributes` is fed by `reachable`, i.e. by what a loaded resolver
    ;; claims to produce; `:joins` comes from the model's own registry and
    ;; stays populated either way.
    (is (empty? (:attributes (engine/catalog c))) "not loaded"))

  (testing "with no :order declared the caller's :order_by is refused by the existing gate"
    ;; The two halves are one door: nothing declares ordering, so nothing can
    ;; ask for it either.
    (let [c (engine/compile-env (read-model [list-resolver]))
          r (q c [(list {:nifi.instance/all_instances [:nifi.instance/host]}
                        {:order_by :nifi.instance/host})])]
      (is (= :engine.request/order-not-allowed (:code r)))))

  (testing "rows answer in the order :data lists them"
    (let [c (engine/compile-env (read-model [list-resolver]))
          r (q c {:entity {} :query [{:nifi.instance/all_instances [:nifi.instance/host]}]})]
      (is (= (mapv :nifi.instance/host instances)
             (mapv :nifi.instance/host (get-in r [:engine/data :nifi.instance/all_instances])))))))

(deftest offset-on-a-static-resolver-is-refused-at-the-gate
  ;; static-v1's capability line says `offset ✗`. Before this, `:offset` sailed
  ;; through the gate and the static branch ignored it, so a caller asking to
  ;; skip two rows got the unskipped set with `:engine/status :ok` — a page that
  ;; was never skipped, and nothing said so.
  (let [c (engine/compile-env (read-model [list-resolver]))
        r (q c [(list {:nifi.instance/all_instances [:nifi.instance/host]} {:offset 2})])]
    (is (= :engine.request/offset-unsupported (:code r)))
    (is (= "static" (:source-type r)) "the refusal names the source type")
    (is (= [:query :nifi.instance/all_instances :offset] (:path r)))
    (is (nil? (:engine/data r))))

  (testing "a source type that implements offset itself is untouched"
    ;; b1's fixture through the fake connector — not static, so the gate hands
    ;; `:offset` down exactly as it did before 8.17. The capability is decided
    ;; per source type, not turned on for everyone.
    (let [b1 (fake/inline-model
              (:model (dialect/read-dialect (slurp (io/resource "graph.edn")))))
          c  (engine/compile-env b1)
          r  (engine/query c nil [(list {:cis.master.part/all_parts [:cis.master.part/sku_no]}
                                        {:offset 1})])]
      (is (= :ok (:engine/status r)))
      (is (not= :engine.request/offset-unsupported (:code r))))))

(deftest batch-on-a-static-resolver-hits-the-existing-batch-unsupported-gate
  ;; StaticOp is closed, so `:batch` cannot pass read-dialect; assoc it in
  ;; memory to prove the engine's own gate holds regardless.
  (let [model (read-model [by-host-resolver])
        model (update model :resolvers (fn [rs] (mapv #(assoc-in % [:source :batch] {:x 1}) rs)))
        c     (engine/compile-env model)]
    (is (contains? (finding-codes c) :engine.compile/batch-unsupported))
    (is (not (contains? (finding-codes c) :engine.compile/source-not-executable)))))

(deftest a-list-static-resolver-over-a-single-map-is-refused
  (let [c (engine/compile-env (read-model [(assoc-in list-resolver [:source :data] (first instances))]))]
    (is (contains? (finding-codes c) :engine.compile/static-list-not-sequential))))

(deftest a-static-parent-collection-fans-out-into-a-static-child-collection
  (let [groups {:id "nifi_instance_groups" :label "组"
                :input [:nifi.instance/host] :output [{:nifi.instance/groups [:nifi.pg/name]}]
                :result_type "list" :limit {:default 10 :max 10}
                :source {:type "static" :match-on :nifi.instance/host
                         :data [{:nifi.instance/host "nifi-a.corp" :nifi.pg/name "ingest"}
                                {:nifi.instance/host "nifi-a.corp" :nifi.pg/name "egress"}
                                {:nifi.instance/host "localhost"   :nifi.pg/name "sandbox"}]}}
        c (engine/compile-env (read-model [list-resolver groups]))
        r (q c {:entity {} :query [{:nifi.instance/all_instances
                                    [:nifi.instance/host {:nifi.instance/groups [:nifi.pg/name]}]}]})]
    (is (empty? (:engine/findings c)))
    (is (= :ok (:engine/status r)))
    (is (= [["ingest" "egress"] [] ["sandbox"]]
           (mapv #(mapv :nifi.pg/name (:nifi.instance/groups %))
                 (get-in r [:engine/data :nifi.instance/all_instances])))
        "each parent row expands on its own; a parent with no rows gets [] not an error")))

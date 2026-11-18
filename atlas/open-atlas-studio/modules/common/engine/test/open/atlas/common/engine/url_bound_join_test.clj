(ns open.atlas.common.engine.url-bound-join-test
  "doc 106 §106.14 item 13 (owner 2026-09-07): a URL-bound attribute rides the
   join into the child entity, derived by the engine from `:input`.

   Why it has to: Pathom 3's plain nested join does NOT inherit the parent's
   attributes — measured against this repo's pathom3 2025.01.16-alpha — so a
   child resolver whose `:input` needs the host is simply
   `:engine.request/unreachable`, and nothing can produce the host (it never
   appears in a response row). The Pathom-standard remedy is for the parent to
   DECLARE and EMIT what it already knows in the join's output; that is what
   the engine now does, mechanically, for `(:input resolver) ∩
   (connection-bound-attributes source-entry)`.

   The dialect `:output` is untouched: this file measures that the generated
   `::pco/output` gains the leaves and the rows gain the values, and that a
   source binding nothing compiles to exactly the tree it declared."
  (:require [clojure.test :refer [deftest testing is]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

;; --- a fake connector that binds its host from the URL -----------------------
;; Registered on the REAL SPI under a type no connector uses, exactly as
;; `fake-connector` does: the engine must reach this through the same seam the
;; api connector uses, not through a door opened for the test.

(def ^:private by-host
  {"h1" {:instance {:nifi.instance/version "1.0"
                    :nifi.instance/root_group {:nifi.pg/id "g1"}}
         :groups   {"g1" {:nifi.pg/name "root-h1"}}
         :procs    {"g1" [{:nifi.proc/id "p1" :nifi.pg/id "g1"}]
                    :names {"p1" "proc-h1"}}}
   "h2" {:instance {:nifi.instance/version "2.0"
                    :nifi.instance/root_group {:nifi.pg/id "gx"}}
         :groups   {"gx" {:nifi.pg/name "root-h2"}}
         :procs    {"gx" [{:nifi.proc/id "px" :nifi.pg/id "gx"}]
                    :names {"px" "proc-h2"}}}})

(defmethod spi/connection-bound-attributes "test-urlbound"
  [source-config]
  ;; Stands in for the api connector's reading of a templated `:base_url`
  ;; (api-v1 «:base_url 的占位符»). Same answer shape: a set of qualified kws.
  (into #{} (map (comp keyword second))
        (re-seq #"\{([^}]+)\}" (str (:base_url source-config)))))

(defmethod spi/compile-source "test-urlbound"
  [_source-config _connection resolver-config]
  (fn [in* _eql-params]
    (let [host (:nifi.instance/host in*)
          h    (get by-host host)]
      ;; No host, no answer — a real HTTP call would have an unresolved `{…}`
      ;; in its URL and refuse. This is what makes an uncarried binding show up
      ;; as a failed query rather than a silently wrong one.
      (case (:id resolver-config)
        "instance_by_host" (:instance h)
        "pg_by_id"         (get-in h [:groups (:nifi.pg/id in*)])
        "pg_processors"    {:nifi.pg/processors (get-in h [:procs (:nifi.pg/id in*)])}
        "proc_by_id"       (when-let [n (get-in h [:procs :names (:nifi.proc/id in*)])]
                             {:nifi.proc/name n})))))

;; --- the model ---------------------------------------------------------------

(def ^:private base
  {:sources {"nifi" {:type "api"
                     :base_url "https://{nifi.instance/host}:9443/nifi-api"}}
   :namespaces {"nifi.instance" {:label "NiFi 实例"}
                "nifi.pg"       {:label "Process group"}
                "nifi.proc"     {:label "Processor"}}
   :attributes
   {:nifi.instance/host       {:type :string :identity? true :format :id :label "主机名"}
    :nifi.instance/version    {:type :string :format :text :label "版本"}
    :nifi.instance/root_group {:cardinality :one-to-one :target "nifi.pg" :label "根组"}
    :nifi.pg/id         {:type :string :identity? true :format :id :label "组 id"}
    :nifi.pg/name       {:type :string :format :text :label "组名"}
    :nifi.pg/processors {:cardinality :one-to-many :target "nifi.proc" :label "组里的 processor"}
    :nifi.proc/id    {:type :string :identity? true :format :id :label "processor id"}
    :nifi.proc/name  {:type :string :format :text :label "processor 名"}
    :nifi.proc/group {:cardinality :one-to-one :target "nifi.pg" :label "所属组"}}})

;; Every resolver on a templated source takes the bound attribute as an
;; `:input` (need-grammar-v1 §3.5) — that is the model fact this feature reads.
(def ^:private instance-by-host
  {:id "instance_by_host" :label "按主机取实例"
   :input [:nifi.instance/host]
   :output [:nifi.instance/version {:nifi.instance/root_group [:nifi.pg/id]}]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :endpoint "/flow/about" :method "GET"}})

(def ^:private pg-by-id
  {:id "pg_by_id" :label "按 id 取组"
   :input [:nifi.pg/id :nifi.instance/host]
   :output [:nifi.pg/name]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :endpoint "/process-groups/{nifi.pg/id}" :method "GET"}})

(def ^:private pg-processors
  {:id "pg_processors" :label "组里的 processor"
   :input [:nifi.pg/id :nifi.instance/host]
   :output [{:nifi.pg/processors [:nifi.proc/id {:nifi.proc/group [:nifi.pg/id]}]}]
   :result_type "list" :limit {:default 50 :max 200} :source-ref "nifi"
   :source {:type "api" :endpoint "/process-groups/{nifi.pg/id}/processors"
            :method "GET"}})

(def ^:private proc-by-id
  {:id "proc_by_id" :label "按 id 取 processor"
   :input [:nifi.proc/id :nifi.instance/host]
   :output [:nifi.proc/name]
   :result_type "single" :source-ref "nifi"
   :source {:type "api" :endpoint "/processors/{nifi.proc/id}" :method "GET"}})

(defn- read-model [m resolvers]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str (assoc m :resolvers resolvers)))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(defn- retype
  "Swap the source type to the fake connector's, in memory and AFTER
   `read-dialect` — dialect-v1 §3 keeps `:type` to a controlled vocabulary, so
   the model is written as the `\"api\"` it stands for and the fake takes over
   at the SPI. Same device as `fake-connector/inline-model`."
  [model t]
  (-> model
      (update :sources update-vals #(assoc % :type t))
      (update :resolvers (fn [rs] (mapv #(assoc-in % [:source :type] t) rs)))))

(defn- compiled [resolvers]
  (engine/compile-env (retype (read-model base resolvers) "test-urlbound")))
(defn- q [c request] (engine/query c nil request))

(defn- output-of
  "The `::pco/output` Pathom actually indexed for a generated resolver — the
   declaration a planner reads, which is exactly what item 13 widens."
  [c id]
  (-> (get-in c [:engine/bundle :env ::pci/index-resolvers])
      (get (symbol "open.atlas.common.engine.generated" id))
      pco/operation-config
      ::pco/output))

;; ---------------------------------------------------------------------------

(deftest the-binding-is-declared-on-every-join-branch
  (let [c (compiled [instance-by-host pg-processors])]
    (is (empty? (:engine/findings c)))
    (testing "a branch below the root gains the bound attribute as a leaf"
      (is (= [:nifi.instance/version
              {:nifi.instance/root_group [:nifi.pg/id :nifi.instance/host]}]
             (output-of c "instance_by_host"))))
    (testing "a root-collection join root gains it too, and so does the branch inside it"
      (is (= [{:nifi.pg/processors [:nifi.proc/id
                                    {:nifi.proc/group [:nifi.pg/id :nifi.instance/host]}
                                    :nifi.instance/host]}]
             (output-of c "pg_processors"))))))

(deftest a-child-reached-through-a-join-can-be-read
  ;; 9.4's measurement, now green: `{:nifi.instance/root_group [… name]}`
  ;; crosses from the instance into `pg_by_id`, whose `:input` needs the host.
  (let [c (compiled [instance-by-host pg-by-id])
        r (q c {:entity {:nifi.instance/host "h1"}
                :query [:nifi.instance/version
                        {:nifi.instance/root_group [:nifi.pg/id :nifi.pg/name]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:nifi.instance/version "1.0"
            :nifi.instance/root_group {:nifi.pg/id "g1" :nifi.pg/name "root-h1"}}
           (:engine/data r)))))

(deftest two-hosts-answer-their-own-subtrees
  ;; The axis is real: the same query against another host walks into that
  ;; host's group, not the first one's — the value travelled with the child.
  (let [c (compiled [instance-by-host pg-by-id])
        ask (fn [h] (q c {:entity {:nifi.instance/host h}
                          :query [{:nifi.instance/root_group [:nifi.pg/name]}]}))]
    (is (= {:nifi.instance/root_group {:nifi.pg/name "root-h1"}}
           (:engine/data (ask "h1"))))
    (is (= {:nifi.instance/root_group {:nifi.pg/name "root-h2"}}
           (:engine/data (ask "h2"))))))

(deftest the-binding-survives-two-levels-of-nesting
  ;; instance → root_group (branch) → processors (root collection) →
  ;; proc_by_id (needs the host) → and back up through the processor's own
  ;; `{proc/group [pg/id]}` branch into `pg_by_id` (needs it again).
  (let [c (compiled [instance-by-host pg-by-id pg-processors proc-by-id])
        r (q c {:entity {:nifi.instance/host "h2"}
                :query [{:nifi.instance/root_group
                         [{:nifi.pg/processors
                           [:nifi.proc/name {:nifi.proc/group [:nifi.pg/name]}]}]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:nifi.instance/root_group
            {:nifi.pg/processors [{:nifi.proc/name "proc-h2"
                                   :nifi.proc/group {:nifi.pg/name "root-h2"}}]}}
           (:engine/data r)))))

(deftest a-source-that-binds-nothing-compiles-the-tree-it-declared
  ;; sql / static / derived answer `#{}` from `connection-bound-attributes`,
  ;; and their `::pco/output` must be the declared `:output`, unchanged — this
  ;; feature is invisible to every non-templated source.
  (let [plain (-> (read-model base [instance-by-host pg-processors])
                  (retype "test-inline")
                  ;; No templated URL, so nothing is bound — the shape a sql or
                  ;; static source presents.
                  (update-in [:sources "nifi"] dissoc :base_url))
        c     (engine/compile-env plain)]
    (is (= (:output instance-by-host) (output-of c "instance_by_host")))
    (is (= (:output pg-processors) (output-of c "pg_processors")))))

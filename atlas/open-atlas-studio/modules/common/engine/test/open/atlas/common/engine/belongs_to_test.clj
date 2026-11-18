(ns open.atlas.common.engine.belongs-to-test
  "doc 106 §106.14 item 5 (owner 2026-09-06): the child→parent «belongs to /
   has one» join `A/b` is produced by the CHILD's own resolvers — a branch
   `{A/b [B/k]}` in their `:output`, whose leaf is the parent's identity
   mapped from the foreign key.

   What the engine owes that ruling, and what this file measures: a branch
   BELOW the root of an `:output` tree is assembled from the flat row a
   connector answers (D2.16), and the parent's identity so placed chains into
   the parent's own by-key resolver in one query. Static sources are used so
   the measurement is of the engine alone — no connector, no database (the
   same reason `static-test` uses them)."
  (:require [clojure.test :refer [deftest testing is]]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.common.engine :as engine]))

(def ^:private instances
  [{:nifi.instance/host "nifi-a.corp" :nifi.instance/name "prod-a"}
   {:nifi.instance/host "localhost"   :nifi.instance/name "local"}])

;; The child rows are FLAT, as every connector answers them: the parent's
;; identity rides in the row (`:nifi.instance/host` — sql maps the foreign key
;; column to it, api a JSONPath), not nested under the join attribute.
(def ^:private groups
  [{:nifi.pg/id "g1" :nifi.pg/name "root"    :nifi.instance/host "localhost"}
   {:nifi.pg/id "g2" :nifi.pg/name "ingest"  :nifi.instance/host "localhost"}
   {:nifi.pg/id "g3" :nifi.pg/name "orphan"  :nifi.instance/host nil}])

(def ^:private base
  {:namespaces {"nifi.instance" {:label "NiFi 实例"}
                "nifi.pg"       {:label "Process group"}}
   :attributes
   {:nifi.instance/host {:type :string :identity? true :format :id :label "主机名"}
    :nifi.instance/name {:type :string :format :text :label "实例名"}
    :nifi.instance/groups {:cardinality :one-to-many :target "nifi.pg" :label "该实例的组"}
    :nifi.pg/id   {:type :string :identity? true :format :id :label "组 id"}
    :nifi.pg/name {:type :string :format :text :label "组名"}
    ;; The B2/B4 join: on the CHILD, targeting the parent, one-to-one.
    :nifi.pg/instance {:cardinality :one-to-one :target "nifi.instance" :label "所属实例"}
    ;; The SELF relation: same namespace on both ends (§106.14 item 6).
    :nifi.pg/parent {:cardinality :one-to-one :target "nifi.pg" :label "父组"}}})

(def ^:private instance-by-host
  {:id "nifi_instance_by_host" :label "按主机名取实例"
   :input [:nifi.instance/host] :output [:nifi.instance/name]
   :result_type "single"
   :source {:type "static" :match-on :nifi.instance/host :data instances}})

(def ^:private pg-by-id
  {:id "nifi_pg_by_id" :label "按 id 取组"
   :input [:nifi.pg/id]
   :output [:nifi.pg/name {:nifi.pg/instance [:nifi.instance/host]}]
   :result_type "single"
   :source {:type "static" :match-on :nifi.pg/id :data groups}})

(def ^:private pg-by-instance
  {:id "nifi_instance_groups" :label "实例的组"
   :input [:nifi.instance/host]
   :output [{:nifi.instance/groups [:nifi.pg/id :nifi.pg/name
                                    {:nifi.pg/instance [:nifi.instance/host]}]}]
   :result_type "list" :limit {:default 50 :max 200}
   :source {:type "static" :match-on :nifi.instance/host :data groups}})

;; --- The SELF relation (§106.14 item 6, owner 2026-09-07) -------------------
;; «Every group belongs to a parent group»: parent and child share a
;; namespace, so the branch's leaf `:nifi.pg/id` IS the resolver's own
;; identity output. A flat row cannot hold two values for one attribute kw,
;; so the branch carries its own scope and the connector answers it ALREADY
;; shaped (sql: a branch-scoped `:field_mapping`; api: the same, in
;; JSONPaths). These static rows are what such a connector hands over.
(def ^:private nested-groups
  [{:nifi.pg/id "g1" :nifi.pg/name "root"   :nifi.pg/parent nil}
   {:nifi.pg/id "g2" :nifi.pg/name "ingest" :nifi.pg/parent {:nifi.pg/id "g1"}}])

(def ^:private pg-self
  {:id "nifi_pg_self_by_id" :label "按 id 取组（含父组）"
   :input [:nifi.pg/id]
   :output [:nifi.pg/id :nifi.pg/name {:nifi.pg/parent [:nifi.pg/id]}]
   :result_type "single"
   :source {:type "static" :match-on :nifi.pg/id :data nested-groups}})

(defn- read-model [resolvers]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str (assoc base :resolvers resolvers)))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(defn- q [compiled request] (engine/query compiled nil request))

;; ---------------------------------------------------------------------------

(deftest the-belongs-to-branch-is-assembled-from-the-flat-row
  (let [c (engine/compile-env (read-model [pg-by-id]))]
    (is (empty? (:engine/findings c)) "a leaf in another namespace is a legal branch (D2.16)")
    (testing "the parent's identity comes back nested under the join attribute"
      (let [r (q c {:entity {:nifi.pg/id "g1"}
                    :query [:nifi.pg/name {:nifi.pg/instance [:nifi.instance/host]}]})]
        (is (= :ok (:engine/status r)))
        (is (= {:nifi.pg/name "root"
                :nifi.pg/instance {:nifi.instance/host "localhost"}}
               (:engine/data r)))))
    (testing "a child with no parent answers nil, not a half-filled map"
      (let [r (q c {:entity {:nifi.pg/id "g3"}
                    :query [{:nifi.pg/instance [:nifi.instance/host]}]})]
        (is (= :ok (:engine/status r)))
        (is (nil? (get-in r [:engine/data :nifi.pg/instance])))))))

(deftest the-branch-chains-into-the-parents-by-key-resolver
  ;; The whole point of 106.14 item 5: `{child/parent [parent/name]}` in ONE
  ;; query, with no link and no second sentence — the child's row already
  ;; carried the parent's key.
  (let [c (engine/compile-env (read-model [pg-by-id instance-by-host]))
        r (q c {:entity {:nifi.pg/id "g2"}
                :query [:nifi.pg/name {:nifi.pg/instance [:nifi.instance/name]}]})]
    (is (= :ok (:engine/status r)))
    (is (= {:nifi.pg/name "ingest"
            :nifi.pg/instance {:nifi.instance/name "local"}}
           (:engine/data r)))))

(deftest a-child-collection-carries-the-branch-inside-its-rows
  ;; B1's list resolver gains the same branch (106.14 item 5: «the child's
  ;; by-key resolver AND the parent's child-collection resolver both gain it»),
  ;; so the branch sits one level BELOW the join root.
  (let [c (engine/compile-env (read-model [pg-by-instance instance-by-host]))
        r (q c {:entity {:nifi.instance/host "localhost"}
                :query [{:nifi.instance/groups
                         [:nifi.pg/id {:nifi.pg/instance [:nifi.instance/name]}]}]})]
    (is (= :ok (:engine/status r)))
    (is (= [{:nifi.pg/id "g1" :nifi.pg/instance {:nifi.instance/name "local"}}
            {:nifi.pg/id "g2" :nifi.pg/instance {:nifi.instance/name "local"}}]
           (get-in r [:engine/data :nifi.instance/groups])))))

(deftest a-self-relation-keeps-the-groups-own-id
  "§106.14 item 6 (owner 2026-09-07). Before the branch had its own scope the
   parent's id was mapped onto the SAME attribute kw as the group's own, the
   later write shadowed the earlier, and every by-key query answered the
   parent's id where the group's belonged."
  (let [c (engine/compile-env (read-model [pg-self]))]
    (is (empty? (:engine/findings c)) "a branch whose leaf shares the namespace is legal")
    (testing "the group's own id survives, and the parent sits in the branch"
      (let [r (q c {:entity {:nifi.pg/id "g2"}
                    :query [:nifi.pg/id :nifi.pg/name {:nifi.pg/parent [:nifi.pg/id]}]})]
        (is (= :ok (:engine/status r)))
        (is (= {:nifi.pg/id "g2" :nifi.pg/name "ingest"
                :nifi.pg/parent {:nifi.pg/id "g1"}}
               (:engine/data r)))))
    (testing "the branch chains back into the SAME by-key resolver"
      (let [r (q c {:entity {:nifi.pg/id "g2"}
                    :query [{:nifi.pg/parent [:nifi.pg/name]}]})]
        (is (= :ok (:engine/status r)))
        (is (= {:nifi.pg/parent {:nifi.pg/name "root"}} (:engine/data r)))))
    (testing "the root group has no parent, and says so"
      (let [r (q c {:entity {:nifi.pg/id "g1"}
                    :query [:nifi.pg/id {:nifi.pg/parent [:nifi.pg/id]}]})]
        (is (= :ok (:engine/status r)))
        (is (= "g1" (get-in r [:engine/data :nifi.pg/id])))
        (is (nil? (get-in r [:engine/data :nifi.pg/parent])))))))

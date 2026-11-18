(ns open.atlas.common.dialect.need-test
  "The four-word read-back (doc 106 §106.5, P8a).

   Small hand-built models for each clause of the criterion, and the real NiFi
   v13 bundle for the whole of it — §106.14 is a measured result, so a change
   that quietly moves the criterion has to move that number too."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.dialect :as d])
  (:import [java.util.zip ZipInputStream]))

;; ---------------------------------------------------------------------------
;; Hand-built models
;; ---------------------------------------------------------------------------

(def declared-model
  "A thing whose identity is declared, with one scalar attribute, one relation
   and a root collection."
  {:namespaces {"shop.order" {:kind :transaction}
                "shop.line"  {:kind :transaction}}
   :attributes {:shop.order/id     {:type :string :identity? true}
                :shop.order/total  {:type :decimal}
                :shop.order/lines  {:target "shop.line" :cardinality :one-to-many}
                :shop.order/all_orders {:target "shop.order" :cardinality :one-to-many}
                :shop.line/id      {:type :string :identity? true}
                :shop.line/sku     {:type :string}}
   :resolvers [{:id "order_by_id" :input [:shop.order/id] :result_type "single"
                :source-ref "s" :output [:shop.order/id :shop.order/total]}
               {:id "order_lines" :input [:shop.order/id] :result_type "list"
                :source-ref "s"
                :output [{:shop.order/lines [:shop.line/id :shop.line/sku]}]}
               {:id "all_orders" :input [] :result_type "list" :source-ref "s"
                :output [{:shop.order/all_orders [:shop.order/id]}]}
               {:id "line_by_id" :input [:shop.line/id] :result_type "single"
                :source-ref "s" :output [:shop.line/id :shop.line/sku]}]})

(defn- thing [view name*] (first (filter #(= name* (:name %)) (:things view))))

(deftest declared-identity-reads-back
  (let [v (d/need-view declared-model)
        o (thing v "shop.order")]
    (is (= #{"shop.order" "shop.line"} (set (map :name (:things v)))))
    (is (= ["shop.order/id"] (:identity o)))
    (is (true? (:identity-declared? o)))
    (testing "a declared identity needs no finding"
      (is (empty? (filter #(= :dialect.need/identity-undeclared (:code %)) (:findings v)))))
    (testing "attributes are the non-join ones"
      (is (= ["shop.order/id" "shop.order/total"] (:attributes o))))
    (testing "the root collection is the list entry point, not a relation"
      (is (true? (:list? o)))
      (is (= "shop.order/all_orders" (:list o)))
      (is (= [{:name "shop.order/lines" :kind :many :target "shop.line"}]
             (:relations o))))))

(deftest cardinality-says-many-or-one
  (let [v (d/need-view (assoc-in declared-model
                                 [:attributes :shop.order/lines :cardinality] :one-to-one))]
    (is (= :one (:kind (first (:relations (thing v "shop.order"))))))))

(deftest shape-evidence-promotes-and-says-so
  (let [m (update-in declared-model [:attributes :shop.order/id] dissoc :identity?)
        v (d/need-view m)
        o (thing v "shop.order")
        f (first (filter #(= :dialect.need/identity-undeclared (:code %)) (:findings v)))]
    (testing "the by-id resolver is the evidence"
      (is (= ["shop.order/id"] (:identity o)))
      (is (false? (:identity-declared? o))))
    (testing "and it is reported for repair, not read as a declaration"
      (is (some? f))
      (is (= [:attributes :shop.order/id] (:path f)))
      (is (= "mark :identity? true" (:expected f)))
      (is (= :warning (:severity f))))))

(deftest a-namespace-nothing-produces-is-not-a-thing
  (let [m (update declared-model :resolvers
                  (fn [rs] (filterv #(not= "line_by_id" (:id %)) rs)))
        m (assoc-in m [:attributes :shop.line/id :identity?] true)
        ;; the only resolver that mentions shop.line now nests it under a join,
        ;; which still produces it — so drop that one too
        m (update m :resolvers (fn [rs] (filterv #(not= "order_lines" (:id %)) rs)))
        v (d/need-view m)]
    (is (nil? (thing v "shop.line")))
    (is (some #(and (= "shop.line" (:name %)) (= :no-identity (:kind %))) (:fragments v)))))

(def misplaced-model
  "The NiFi shape §106.13 found: the join hangs on the CHILD namespace while
   the resolver reads the parent."
  {:namespaces {"q.queue" {} "q.history" {}}
   :attributes {:q.queue/id {:type :string :identity? true}
                :q.history/all_history {:target "q.history" :cardinality :one-to-many}
                :q.history/at {:type :datetime}}
   :resolvers [{:id "queue_by_id" :input [:q.queue/id] :result_type "single"
                :source-ref "s" :output [:q.queue/id]}
               {:id "queue_history" :input [:q.queue/id] :result_type "list"
                :source-ref "s"
                :output [{:q.history/all_history [:q.history/at]}]}]})

(deftest misplaced-join-is-reported-not-read-as-a-relation
  (let [v (d/need-view misplaced-model)
        f (first (filter #(= :dialect.need/join-misplaced (:code %)) (:findings v)))]
    (is (some? f))
    (is (= [:attributes :q.history/all_history] (:path f)))
    (is (= ["q.queue"] (:expected f)))
    (testing "the parent does not gain a relation it does not carry"
      (is (= [] (:relations (thing v "q.queue")))))
    (testing "and the child is not credited with one either"
      (is (nil? (thing v "q.history"))))))

(deftest fragments-carry-their-kind
  (let [m {:namespaces {"x.thing" {} "x.orphan" {}}
           :attributes {:x.thing/id {:type :string :identity? true}
                        :x.thing/a  {:type :string}
                        :x.thing/b  {:type :string}
                        :x.orphan/c {:type :string}
                        :x.thing/derived {:type :string}}
           :resolvers [{:id "t_by_id" :input [:x.thing/id] :result_type "single"
                        :source-ref "s" :output [:x.thing/id :x.thing/a]}
                       ;; no source at all — nothing outside the model supplies it
                       {:id "t_derived" :input [:x.thing/id] :result_type "single"
                        :output [:x.thing/derived]}
                       ;; two resolvers produce :x.thing/b directly
                       {:id "t_b1" :input [:x.thing/id] :result_type "single"
                        :source-ref "s" :output [:x.thing/b]}
                       {:id "t_b2" :input [:x.thing/id] :result_type "single"
                        :source-ref "s" :output [:x.thing/b]}]}
        v (d/need-view m)
        kinds (group-by :kind (:fragments v))]
    (is (= ["x.orphan"] (map :name (:no-identity kinds))))
    (is (= ["x.thing/derived"] (map :name (:derived-without-source kinds))))
    (is (= ["x.thing/b"] (map :name (:produced-twice kinds))))
    (testing "every fragment says which kind it is"
      (is (every? :kind (:fragments v)))
      (is (every? :why (:fragments v))))))

(deftest concepts-come-from-terms-and-bindings
  (let [m (-> declared-model
              (assoc :terms {"order_total" {:label "订单金额" :type :decimal}})
              (assoc-in [:attributes :shop.order/total :term] "order_total")
              (assoc-in [:attributes :shop.order/total :term-context] "net of tax"))
        v (d/need-view m)]
    (is (= [{:concept "order_total"
             :definition {:label "订单金额" :type :decimal}
             :attributes ["shop.order/total"]
             :contexts {"shop.order/total" "net of tax"}}]
           (:concepts v)))))

(deftest deterministic
  (is (= (d/need-view declared-model) (d/need-view declared-model)))
  (is (= (pr-str (d/need-view declared-model)) (pr-str (d/need-view declared-model)))))

;; ---------------------------------------------------------------------------
;; The real graph — doc 106 §106.14, measured 2026-09-06
;; ---------------------------------------------------------------------------

(defn- bundle-content
  "`content.edn` out of a bundle zip. The fixture is a fact and is read, never
   written (CLAUDE.md)."
  [path]
  (with-open [zis (ZipInputStream. (io/input-stream (io/file path)))]
    (loop []
      (if-let [e (.getNextEntry zis)]
        (if (.endsWith (.getName e) "content.edn")
          (edn/read-string (slurp zis))
          (recur))
        nil))))

(defn- nifi-bundle
  "The bundle, whether the runner's cwd is the repo root (`clojure -M:test`)
   or this module (`cd modules/common/dialect && clojure -M:test`)."
  []
  (first (filter #(.exists ^java.io.File %)
                 (map io/file ["fixtures/bundles/open-atlas-nifi-local.zip"
                               "../../../fixtures/bundles/open-atlas-nifi-local.zip"]))))

(def ^:private nifi-carried
  "What `spi/carried-attributes` answers for this bundle — a STAND-IN, the way
   `intent-test`'s `fake-connection-bound` stands in for the api connector.

   `common/dialect` may not depend on `common/connector-spi` (D1.8), and which
   attributes a source binds is a source-dialect judgement; the derivation
   itself is tested where it lives, in `connector-spi`'s `carried-test`. What
   is tested HERE is what `need-view` does with the answer: doc 106 §106.14
   item 18 makes a carried attribute part of the key.

   Every namespace on the host-templated source carries the instance's host;
   the in-record children additionally carry their parent's key (item 17)."
  (let [h #{:nifi.instance/host}]
    {"nifi.flow.group" h "nifi.flow.processor" h "nifi.flow.connection" h
     "nifi.flow.label" h "nifi.flow.bulletin" h "nifi.flow.counter" h
     "nifi.flow.queue_history" h
     "nifi.flow.group_status" h
     "nifi.flow.relationship" (conj h :nifi.flow.processor/id)}))

(deftest nifi-v15-reads-back-as-106-14-says
  (let [f (nifi-bundle)]
    (is (some? f) "fixture missing: fixtures/bundles/open-atlas-nifi-local.zip")
    (when f
      (let [model (:objects (bundle-content f))
            v (d/need-view model nifi-carried)
            names (set (map :name (:things v)))]
        (testing "nine things, each with its identity DECLARED"
          ;; v7's split between «declared» and «shape evidence only» is gone:
          ;; add_thing's `identity` writes `:identity? true` (§106.14 item 4,
          ;; gap 6), so declared and detected are now the same set. v12 was six,
          ;; said from scratch over a HOST-TEMPLATED source (§106.14 items 11
          ;; and 13). v13 adds two: `nifi.flow.group_status`, the recursive
          ;; status RECORD that hosts the nested collections (§106.14 item 15),
          ;; and `nifi.flow.counter`, whose key is two fields together
          ;; (§106.14 item 14) — a NiFi counter is «this name in that context».
          ;; v14 adds the ninth: `nifi.flow.relationship`, a v13 `:series`
          ;; fragment that item 17 turns into a THING — the parent's key rides
          ;; the in-record collection into its rows, so «this relationship,
          ;; under that processor» is sayable and the child chains back.
          (is (= #{"nifi.instance" "nifi.flow.group" "nifi.flow.processor"
                   "nifi.flow.connection" "nifi.flow.label" "nifi.flow.bulletin"
                   "nifi.flow.group_status" "nifi.flow.counter"
                   "nifi.flow.relationship"}
                 names))
          (is (= names (set (map :name (filter :identity-declared? (:things v)))))))
        (testing "a per-instance component is recognised by [host id]"
          ;; **v15's change** (owner 2026-09-07, §106.14 item 18): a component
          ;; id is unique per INSTANCE only, so «which one» is the host and the
          ;; id together. The host is not an attribute of any of these
          ;; namespaces — it is the instance's key, carried into every row read
          ;; over that connection — and the key is read back in the by-key
          ;; resolver's `:input` order.
          ;;
          ;; Two exceptions, and both are the same fact: item 18 widens the key
          ;; by what the thing's BY-KEY resolver takes in, and these two have
          ;; no by-key resolver to widen. `bulletin` is «all of the X, known by
          ;; k» over a collection endpoint with no `/{id}` path (§106.12 item
          ;; 7.7); `relationship` lives inside the processor's record and NiFi
          ;; offers no way to fetch one on its own. Their rows do carry the
          ;; host (and, for a relationship, the processor's id — the query
          ;; `processor → relationships → nifi.flow.processor/id` answers it),
          ;; but nothing lists it as an input, so nothing declares it as key.
          ;;
          ;; v12's instance comes from a HAND-WRITTEN roster keyed on `host`
          ;; (need-grammar-v1 §3.1, add_thing's `rows`). The counter is keyed
          ;; on two of its OWN fields (§106.14 item 14) — a NiFi counter is
          ;; «this name in that context» — and is a per-instance component none
          ;; the less; it simply has no id to pair the host with.
          (is (= {"nifi.instance" ["nifi.instance/host"]
                  "nifi.flow.group" ["nifi.instance/host" "nifi.flow.group/id"]
                  "nifi.flow.processor" ["nifi.instance/host" "nifi.flow.processor/id"]
                  "nifi.flow.connection" ["nifi.instance/host" "nifi.flow.connection/id"]
                  "nifi.flow.label" ["nifi.instance/host" "nifi.flow.label/id"]
                  "nifi.flow.group_status" ["nifi.instance/host"
                                            "nifi.flow.group_status/id"]
                  "nifi.flow.bulletin" ["nifi.flow.bulletin/id"]
                  "nifi.flow.counter" ["nifi.flow.counter/context"
                                       "nifi.flow.counter/name"]
                  "nifi.flow.relationship" ["nifi.flow.relationship/name"]}
                 (into {} (for [t (:things v)] [(:name t) (:identity t)]))))
          (testing "without the carried set the same graph reads the narrower key"
            ;; The one-argument arity is the pre-item-18 answer, and it must
            ;; still be a usable view rather than an error — a caller with no
            ;; connector on its classpath gets this.
            (is (= ["nifi.flow.group/id"]
                   (:identity (thing (d/need-view model) "nifi.flow.group"))))))
        (testing "the root collections are lists, not relations"
          ;; ONE root collection, and it is the static roster. Over the
          ;; host-templated source a root collection has no key to hang the URL
          ;; binding on (need-grammar-v1 §3.5), so «all bulletins» and «all
          ;; counters» are said as the INSTANCE's child collections instead —
          ;; which is also what «on which instance?» means for them.
          (is (= {"nifi.instance" "nifi.instance/all_instances"}
                 (into {} (for [e (concat (:things v) (:fragments v))
                                :when (:list e)]
                            [(:name e) (:list e)]))))
          (is (= #{"nifi.instance"}
                 (set (map :name (filter :list? (:things v)))))))
        (testing "the group's four child collections, and one belongs-to per child"
          ;; §106.14 item 5 landed: every «belongs to» join is produced by the
          ;; child's own resolver, so it reads back as a relation on the CHILD.
          ;; The group's own «belongs to a parent group» is here too: §106.14
          ;; item 6 gave a self relation its own mapping scope, so `$.id` (its
          ;; own key) and `$.component.parentGroupId` (its parent's) no longer
          ;; collide in one flat :field_mapping.
          ;; The group's «belongs to a parent group» is said ONCE in v14.
          ;; v13 carried it twice — `group/parent` from the sentence that says
          ;; it, and `group/group` generated alongside the child collection —
          ;; two names for one edge, which is exactly the duplication D2.29's
          ;; «a branch IS an attribute» exists to avoid.
          (is (= [{:name "nifi.flow.group/connections" :kind :many :target "nifi.flow.connection"}
                  {:name "nifi.flow.group/groups" :kind :many :target "nifi.flow.group"}
                  {:name "nifi.flow.group/labels" :kind :many :target "nifi.flow.label"}
                  {:name "nifi.flow.group/parent" :kind :one :target "nifi.flow.group"}
                  {:name "nifi.flow.group/processors" :kind :many :target "nifi.flow.processor"}]
                 (:relations (thing v "nifi.flow.group"))))
          (is (= {"nifi.instance"
                  [{:name "nifi.instance/all_bulletin" :kind :many :target "nifi.flow.bulletin"}
                   {:name "nifi.instance/all_counter" :kind :many :target "nifi.flow.counter"}
                   {:name "nifi.instance/root_group" :kind :one :target "nifi.flow.group"}
                   {:name "nifi.instance/root_group_status" :kind :one
                    :target "nifi.flow.group_status"}]
                  "nifi.flow.processor"
                  [{:name "nifi.flow.processor/group" :kind :one :target "nifi.flow.group"}
                   {:name "nifi.flow.processor/relationships" :kind :many
                    :target "nifi.flow.relationship"}]
                  "nifi.flow.label"
                  [{:name "nifi.flow.label/group" :kind :one :target "nifi.flow.group"}]
                  "nifi.flow.bulletin"
                  [{:name "nifi.flow.bulletin/group" :kind :one :target "nifi.flow.group"}]
                  "nifi.flow.counter" []
                  "nifi.flow.relationship" []
                  "nifi.flow.group_status"
                  [{:name "nifi.flow.group_status/child_status" :kind :many
                    :target "nifi.flow.group_status"}
                   {:name "nifi.flow.group_status/connection_status" :kind :many
                    :target "nifi.flow.connection"}
                   {:name "nifi.flow.group_status/processor_status" :kind :many
                    :target "nifi.flow.processor"}]
                  "nifi.flow.connection"
                  [{:name "nifi.flow.connection/group" :kind :one :target "nifi.flow.group"}
                   {:name "nifi.flow.connection/history" :kind :many
                    :target "nifi.flow.queue_history"}]}
                 (into {} (for [t (:things v) :when (not= "nifi.flow.group" (:name t))]
                            [(:name t) (:relations t)]))))
          (testing "and the history join sits on the connection, not on the history point"
            (is (some #(= "nifi.flow.connection/history" (:name %))
                      (:relations (thing v "nifi.flow.connection"))))))
        (testing "the three nested collections come from ONE call and carry no :limit"
          ;; §106.14 item 15: a collection that sits inside the record its
          ;; parent already fetched. All three hang off the group-status
          ;; lookup's own operation block — same :endpoint, one branch each
          ;; carrying its own `:_from` — and D2.8's «a list must declare
          ;; :limit» is waived for exactly this shape, because the cardinality
          ;; is the host record's and there is no next page to ask for.
          (let [by-id (into {} (for [r (:resolvers model)] [(:id r) r]))]
            (doseq [[rid branch-key]
                    {"nifi_flow_group_status_child_status"
                     :nifi.flow.group_status/child_status
                     "nifi_flow_group_status_connection_status"
                     :nifi.flow.group_status/connection_status
                     "nifi_flow_group_status_processor_status"
                     :nifi.flow.group_status/processor_status}]
              (let [r (get by-id rid)
                    branch (get-in r [:source :field_mapping branch-key])]
                (is (some? r) rid)
                (is (= "/flow/process-groups/{nifi.flow.group_status/id}/status"
                       (get-in r [:source :endpoint]))
                    (str rid " reuses the record's own endpoint"))
                (is (nil? (:limit r))
                    (str rid " is bounded by its host record, not by :limit"))
                (is (map? branch) (str rid " writes a branch, not a leaf"))
                (is (.endsWith ^String (:_from branch) "[*]")
                    (str rid "'s :_from names the array itself"))))))
        (testing "the compound key's BOTH parts are produced, and are the key"
          ;; A NiFi counter has an `id`, but the business key is «this name in
          ;; that context» — «Lines Counted» appears under every CountText.
          ;; §106.14 item 14: every :identity? true attribute in one namespace
          ;; IS the key, and a compound-key thing's collection has to produce
          ;; all of it or the cut refuses (cut.structure/incomplete-root-collection).
          (let [r (first (filter #(= "nifi_instance_all_counter" (:id %)) (:resolvers model)))
                key-attrs (set (for [[a e] (:attributes model)
                                     :when (and (:identity? e)
                                                (= "nifi.flow.counter" (namespace a)))]
                                 a))]
            (is (some? r))
            (is (= #{:nifi.flow.counter/context :nifi.flow.counter/name} key-attrs))
            (is (every? (set (keys (get-in r [:source :field_mapping]))) key-attrs)
                "the collection reads every part of the key")))
        (testing "ONE fragment left, and it is a series"
          ;; A series is a legal fragment (owner 2026-09-07, §106.14 item 10):
          ;; a relation points at it, so it reads back carrying the parent that
          ;; reaches it, not as a namespace nobody can get to. A queue history
          ;; point genuinely has no key of its own — a sampling instant is not
          ;; a thing. v13's OTHER series was `nifi.flow.relationship`, and item
          ;; 17 promoted it: with the processor's key riding into those rows,
          ;; a relationship is «this name, under that processor», which is an
          ;; identity, so it reads back as a thing.
          (is (= [{:kind :series :name "nifi.flow.queue_history"
                   :parent "nifi.flow.connection/history"}]
                 (mapv #(select-keys % [:kind :name :parent]) (:fragments v))))
          (testing "so nothing reads back as derived-without-source or produced-twice"
            (is (= #{:series} (set (map :kind (:fragments v))))))
          (testing "and a series is not a defect: no finding names it"
            (is (empty? (filter #(#{[:attributes :nifi.flow.queue_history]}
                                  (:path %))
                                (:findings v))))))
        (testing "six concepts carry their bindings"
          (is (= [{:concept "nifi.flow/flowfile_availability"
                   :attributes ["nifi.flow.connection/flowfile_availability"]}
                  {:concept "nifi.flow/parent_group_id"
                   :attributes ["nifi.flow.bulletin/group_id"
                                "nifi.flow.connection/parent_group_id"
                                "nifi.flow.label/parent_group_id"
                                "nifi.flow.processor/parent_group_id"]}
                  {:concept "nifi.flow/percent_use_count"
                   :attributes ["nifi.flow.connection/percent_use_count"]}
                  {:concept "nifi.flow/queued_bytes"
                   :attributes ["nifi.flow.connection/queued_bytes"
                                "nifi.flow.group_status/queued_bytes"
                                "nifi.flow.queue_history/queued_bytes"]}
                  {:concept "nifi.flow/queued_count"
                   :attributes ["nifi.flow.connection/queued_count"
                                "nifi.flow.group_status/queued_count"
                                "nifi.flow.queue_history/queued_count"]}
                  {:concept "nifi.flow/run_status"
                   :attributes ["nifi.flow.processor/run_status"]}]
                 (mapv #(select-keys % [:concept :attributes]) (:concepts v))))
          (testing "each has a definition and no per-attribute context"
            (is (every? (comp :label :definition) (:concepts v)))
            (is (every? (comp empty? :contexts) (:concepts v)))))
        (testing "and the read-back has NO findings left"
          ;; v7 answered four identity-undeclared plus one join-misplaced.
          ;; The rebuild's whole point was to make that list empty.
          (is (= [] (mapv (juxt :code :path) (:findings v)))))))))

;; ---------------------------------------------------------------------------
;; Compound identity (owner 2026-09-07, doc 106 §106.14 item 14)
;; ---------------------------------------------------------------------------

(def ^:private compound-model
  "Two `:identity? true` attributes in one namespace: ONE key, not an
   ambiguity, and not a second dialect key."
  {:attributes {:k8s.pod/namespace {:type :string :identity? true}
                :k8s.pod/name      {:type :string :identity? true}
                :k8s.pod/phase     {:type :string}}
   :namespaces {"k8s.pod" {}}
   :resolvers [{:id "pod_by_key" :input [:k8s.pod/namespace :k8s.pod/name]
                :output [:k8s.pod/phase] :result_type "single"
                :source {:type "sql" :query "SELECT 1"}}]})

(deftest a-compound-identity-reads-back-as-one-thing
  (let [v (d/need-view compound-model)
        t (first (:things v))]
    (is (= 1 (count (:things v))) "two keys are one thing, not two and not a fragment")
    (is (= ["k8s.pod/namespace" "k8s.pod/name"] (:identity t))
        (str "both parts, in the by-key resolver's `:input` order — the order the "
             "modeller said the key in, and the order every template and WHERE "
             "clause already uses (doc 106 §106.14 item 18)"))
    (is (true? (:identity-declared? t)))
    (is (empty? (:findings v)))))

(deftest shape-evidence-covers-a-compound-key
  ;; «A single-result resolver whose input is ENTIRELY this namespace's
  ;; attributes» — widened from «exactly one attribute» by the ruling. One
  ;; finding per part, because the repair declares one attribute.
  (let [m (-> compound-model
              (update-in [:attributes :k8s.pod/namespace] dissoc :identity?)
              (update-in [:attributes :k8s.pod/name] dissoc :identity?))
        v (d/need-view m)
        t (first (:things v))]
    (is (= ["k8s.pod/namespace" "k8s.pod/name"] (:identity t)))
    (is (false? (:identity-declared? t)))
    (is (= [[:attributes :k8s.pod/name] [:attributes :k8s.pod/namespace]]
           (mapv :path (:findings v))))
    (is (every? #(= :dialect.need/identity-undeclared (:code %)) (:findings v)))))

(deftest an-input-spanning-two-namespaces-is-not-shape-evidence
  ;; That resolver is reached from elsewhere; its own namespace's key is not
  ;; what the whole input names.
  (let [m (-> compound-model
              (update-in [:attributes :k8s.pod/namespace] dissoc :identity?)
              (update-in [:attributes :k8s.pod/name] dissoc :identity?)
              (assoc-in [:attributes :k8s.node/id] {:type :string})
              (assoc-in [:resolvers 0 :input] [:k8s.pod/name :k8s.node/id]))
        v (d/need-view m)]
    (is (empty? (:things v)))
    (is (= [:no-identity]
           (mapv :kind (filter #(= "k8s.pod" (:name %)) (:fragments v)))))))

(deftest identity-that-is-also-a-foreign-key-is-not-produced-twice
  ;; The ruling's named boundary: when a thing's key is ALSO the leaf some
  ;; other namespace's relation reads it by, two resolvers legitimately carry
  ;; it — the thing's own, and the child whose row already holds it.
  (let [m {:attributes {:a/id   {:type :string :identity? true}
                        :a/code {:type :string}
                        :a/slug {:type :string}
                        :b/id   {:type :string :identity? true}
                        :b/a    {:target "a" :cardinality :one-to-one}}
           :namespaces {"a" {} "b" {}}
           :resolvers [{:id "a_by_code" :input [:a/code] :output [:a/id]
                        :result_type "single" :source {:type "sql" :query "SELECT 1"}}
                       {:id "a_by_slug" :input [:a/slug] :output [:a/id]
                        :result_type "single" :source {:type "sql" :query "SELECT 2"}}
                       {:id "b_by_id" :input [:b/id] :output [{:b/a [:a/id]}]
                        :result_type "single" :source {:type "sql" :query "SELECT 3"}}]}
        v (d/need-view m)]
    (is (not-any? #(= :produced-twice (:kind %)) (:fragments v)))
    (testing "and an ordinary attribute produced twice is still reported"
      (let [m2 (-> m
                   (assoc-in [:resolvers 0 :output] [:a/id :a/slug])
                   (assoc-in [:resolvers 1 :output] [:a/id :a/slug]))
            v2 (d/need-view m2)]
        (is (= [{:kind :produced-twice :name "a/slug"}]
               (mapv #(select-keys % [:kind :name])
                     (filter #(= :produced-twice (:kind %)) (:fragments v2)))))))))

;; ---------------------------------------------------------------------------
;; Item 18 — a carried attribute may be part of the key
;; ---------------------------------------------------------------------------

(def ^:private templated-model
  "One host-templated source: the instance is a thing of its own, and a group
   is recognised by the host it lives on AND its own id (doc 106 §106.14 item
   18). `host` is NOT an attribute of `nifi.flow.group` — it is the instance's
   key, carried into every row read over that connection."
  {:attributes {:nifi.instance/host   {:type :string :identity? true}
                :nifi.flow.group/id   {:type :string :identity? true}
                :nifi.flow.group/name {:type :string}}
   :namespaces {"nifi.instance" {} "nifi.flow.group" {}}
   :resolvers [{:id "nifi_instance_by_host"
                :input [:nifi.instance/host] :output [:nifi.instance/host]
                :result_type "single" :source {:type "api" :endpoint "/flow/about"}}
               {:id "nifi_flow_group_by_host_id"
                :input [:nifi.instance/host :nifi.flow.group/id]
                :output [:nifi.flow.group/id :nifi.flow.group/name]
                :result_type "single"
                :source {:type "api" :endpoint "/process-groups/{nifi.flow.group/id}"}}]})

(def ^:private templated-carried
  "What `spi/carried-attributes` answers for the model above. Passed in rather
   than derived: `common/dialect` may not depend on the SPI (D1.8), and which
   attributes a source binds is a source-dialect judgement."
  {"nifi.flow.group" #{:nifi.instance/host}})

(deftest a-carried-attribute-reads-back-as-part-of-the-key
  (let [v (d/need-view templated-model templated-carried)
        g (thing v "nifi.flow.group")]
    (is (= ["nifi.instance/host" "nifi.flow.group/id"] (:identity g))
        "the whole key, in the by-key resolver's `:input` order (item 18)")
    (is (true? (:identity-declared? g)))
    (is (= ["nifi.flow.group/id" "nifi.flow.group/name"] (:attributes g))
        "the carried half is another thing's attribute and is not listed here")
    (is (empty? (:findings v)))))

(deftest without-the-carried-set-the-key-reads-as-it-always-did
  ;; The one-argument arity is the pre-item-18 answer, so a caller with no
  ;; connector on its classpath still gets a usable — if narrower — view.
  (let [g (thing (d/need-view templated-model) "nifi.flow.group")]
    (is (= ["nifi.flow.group/id"] (:identity g)))))

(def ^:private in-record-model
  "A processor's relationships come out of the processor's own record, so the
   processor's key rides into every relationship row (item 17) and the child is
   recognised by `[processor_id name]` (item 18)."
  {:attributes {:nifi.flow.processor/id            {:type :string :identity? true}
                :nifi.flow.processor/relationships {:target "nifi.flow.relationship"
                                                    :cardinality :one-to-many}
                :nifi.flow.relationship/name       {:type :string :identity? true}
                :nifi.flow.relationship/auto_terminate {:type :boolean}}
   :namespaces {"nifi.flow.processor" {} "nifi.flow.relationship" {}}
   :resolvers [{:id "nifi_flow_processor_by_id"
                :input [:nifi.flow.processor/id]
                :output [:nifi.flow.processor/id
                         {:nifi.flow.processor/relationships
                          [:nifi.flow.relationship/name
                           :nifi.flow.relationship/auto_terminate]}]
                :result_type "single"
                :source {:type "api" :endpoint "/processors/{nifi.flow.processor/id}"}}
               {:id "nifi_flow_relationship_by_processor_id_name"
                :input [:nifi.flow.processor/id :nifi.flow.relationship/name]
                :output [:nifi.flow.relationship/auto_terminate]
                :result_type "single"
                :source {:type "api" :endpoint "/processors/{nifi.flow.processor/id}"}}]})

(deftest an-in-record-child-is-keyed-on-the-parent-key-and-its-own
  (let [v (d/need-view in-record-model
                       {"nifi.flow.relationship" #{:nifi.flow.processor/id}})
        r (thing v "nifi.flow.relationship")]
    (is (= ["nifi.flow.processor/id" "nifi.flow.relationship/name"] (:identity r))
        "both halves read back, the carried one included (item 18)")
    (is (= ["nifi.flow.relationship/auto_terminate" "nifi.flow.relationship/name"]
           (:attributes r)))))

(deftest an-endpoint-backed-single-key-thing-is-unchanged
  ;; sql, static and derived carry nothing, so their things read back exactly
  ;; as they did before the ruling — the empty `carried` map is the whole
  ;; difference.
  (is (= (:things (d/need-view compound-model))
         (:things (d/need-view compound-model {}))))
  (let [t (thing (d/need-view declared-model {}) "shop.order")]
    (is (= ["shop.order/id"] (:identity t)))
    (is (= "shop.order/all_orders" (:list t)))))

(ns open.atlas.integration.semantic-output-v2-test
  "Semantic contract 2.0 §6 (doc 109 §109.8 item 3, owner 2026-09-18) end to
   end: one 2.0 document with a NiFi-shaped process-group tree (static, the
   record embeds its direct children, recursion ceiling 3) and a ticket whose
   subject is a device OR a person (union), compiled through
   `compile-candidate` and queried through the real engine; the final
   projection passes `semantic-result/validate-query-result`. A second document
   puts the recursion on a SQLite self-referencing table behind the sql
   connector, ceiling 2, and a request for 5 is clamped and reported. The same
   fragments under a 1.4 descriptor are a schema error, and the document
   carries both capabilities through the contract."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.contract :as version]
            [open.atlas.common.engine :as engine]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-contract :as contract]
            [open.atlas.common.semantic-result :as result]))

;; ---------------------------------------------------------------------------
;; A: static process-group tree + ticket/subject union
;; ---------------------------------------------------------------------------

(def ^:private vocabulary
  {:entities {"nifi.pg" {:entity/label "Process group" :entity/identity [:nifi.pg/id]
                         :entity/attributes [:nifi.pg/id :nifi.pg/name :nifi.pg/children]}
              "ticket" {:entity/label "Ticket" :entity/identity [:ticket/id]
                        :entity/attributes [:ticket/id :ticket/subject]}
              "device" {:entity/label "Device" :entity/identity [:device/id]
                        :entity/attributes [:device/id :device/name]}
              "person" {:entity/label "Person" :entity/identity [:person/id]
                        :entity/attributes [:person/id :person/email]}}
   :attributes {:nifi.pg/id {:attribute/label "ID" :attribute/type :string :attribute/nullable? false}
                :nifi.pg/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                :nifi.pg/children {:attribute/label "Children" :attribute/type :ref :attribute/nullable? false
                                   :attribute/target "nifi.pg" :attribute/cardinality :many}
                :ticket/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :ticket/subject {:attribute/label "Subject" :attribute/type :ref :attribute/nullable? true
                                 :attribute/targets #{"device" "person"}}
                :device/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :device/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                :person/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :person/email {:attribute/label "Email" :attribute/type :string :attribute/nullable? false}}})

(def ^:private groups
  [{:nifi.pg/id "root" :nifi.pg/name "NiFi Flow" :nifi.pg/children [{:nifi.pg/id "ingest"} {:nifi.pg/id "egress"}]}
   {:nifi.pg/id "ingest" :nifi.pg/name "Ingest" :nifi.pg/children [{:nifi.pg/id "ingest.kafka"}]}
   {:nifi.pg/id "ingest.kafka" :nifi.pg/name "Kafka" :nifi.pg/children [{:nifi.pg/id "ingest.kafka.dlq"}]}
   {:nifi.pg/id "ingest.kafka.dlq" :nifi.pg/name "DLQ" :nifi.pg/children [{:nifi.pg/id "ingest.kafka.dlq.deep"}]}
   {:nifi.pg/id "ingest.kafka.dlq.deep" :nifi.pg/name "Deep" :nifi.pg/children []}
   {:nifi.pg/id "egress" :nifi.pg/name "Egress" :nifi.pg/children []}])

(def ^:private tickets
  [{:ticket/id 1 :ticket/subject {:device/id 10 :device/name "router"}}
   {:ticket/id 2 :ticket/subject {:person/id 20 :person/email "ops@example.test"}}
   {:ticket/id 3 :ticket/subject {:device/id 11 :device/name "switch" :person/id 21 :person/email "x@example.test"}}])

(def ^:private definitions
  {:resolvers {"pg-by-id" {:label "Process group by id" :effect :read :result/cardinality :one
                           :input [:nifi.pg/id]
                           ;; The record embeds its direct children; the planner
                           ;; recurses to the ceiling by calling this resolver
                           ;; per child (2.0 §6.3: «resolver 只答直接子级»).
                           :output [:nifi.pg/id :nifi.pg/name {:nifi.pg/children 3}]}
               "ticket-by-id" {:label "Ticket by id" :effect :read :result/cardinality :one
                               :input [:ticket/id]
                               :output [:ticket/id {:ticket/subject {"device" [:device/id :device/name]
                                                                     "person" [:person/id :person/email]}}]}}
   :operations {}
   :sources {"mem" {:source/kind "static" :source/effects #{:read}}}
   :bindings {"pg-static" {:binding/for [:resolver "pg-by-id"] :binding/source "mem" :binding/effect :read
                           :binding/implementation {:data groups :match-on :nifi.pg/id}
                           :binding/input-map {:nifi.pg/id "nifi.pg/id"}
                           :binding/output-map {:nifi.pg/id "nifi.pg/id" :nifi.pg/name "nifi.pg/name"
                                                :nifi.pg/children "nifi.pg/children"}}
              "ticket-static" {:binding/for [:resolver "ticket-by-id"] :binding/source "mem" :binding/effect :read
                               :binding/implementation {:data tickets :match-on :ticket/id}
                               :binding/input-map {:ticket/id "ticket/id"}
                               :binding/output-map {:ticket/id "ticket/id" :ticket/subject "ticket/subject"
                                                    :device/id "device/id" :device/name "device/name"
                                                    :person/id "person/id" :person/email "person/email"}}}
   :selection {[:resolver "pg-by-id"] "pg-static" [:resolver "ticket-by-id"] "ticket-static"}})

(def ^:private adapters {"static" (spi/semantic-adapter "static") "sql" (spi/semantic-adapter "sql")})
(def ^:private v2 {:contract version/semantic-v2})

(defn- issues-of [answer code] (filter #(= code (:code %)) (:engine/issues answer)))

(deftest a-2-0-document-walks-a-process-group-tree-and-discriminates-a-union
  (let [candidate (compiler/compile-candidate vocabulary definitions adapters {} v2)
        q (fn [entity query] (engine/query (:compiled candidate) {:graph "nifi" :version 1} {:entity entity :query query}))]
    (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
    (when (:compiled candidate)
      (testing "three levels of process groups, exactly as declared"
        (let [answer (q {:nifi.pg/id "root"} [:nifi.pg/name {:nifi.pg/children 3}])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= {:nifi.pg/name "NiFi Flow"
                  :nifi.pg/children [{:nifi.pg/name "Ingest"
                                      :nifi.pg/children [{:nifi.pg/name "Kafka"
                                                          :nifi.pg/children [{:nifi.pg/name "DLQ"
                                                                              :nifi.pg/children [{}]}]}]}
                                     {:nifi.pg/name "Egress" :nifi.pg/children []}]}
                 (:engine/data answer))
              "the boundary record is present but not descended into, and answers only what was asked")
          (is (not (contains? answer :recursion/depth-applied)))
          (is (empty? (:findings (result/validate-query-result vocabulary answer)))
              (pr-str (:findings (result/validate-query-result vocabulary answer))))))
      (testing "an unbounded request is clamped to the declared ceiling and the clamp is reported"
        (let [answer (q {:nifi.pg/id "root"} [:nifi.pg/name {:nifi.pg/children '...}])]
          (is (= :ok (:engine/status answer)) (pr-str answer))
          (is (= [{:path [] :attribute :nifi.pg/children :requested '... :applied 3}]
                 (:recursion/depth-applied answer)))))
      (testing "a device ticket and a person ticket each answer their branch"
        (let [union-q [:ticket/id {:ticket/subject {:device/id [:device/id :device/name]
                                                    :person/id [:person/id :person/email]}}]
              device (q {:ticket/id 1} union-q)
              person (q {:ticket/id 2} union-q)]
          (is (= {:ticket/id 1 :ticket/subject {:device/id 10 :device/name "router"}} (:engine/data device)) (pr-str device))
          (is (= {:ticket/id 2 :ticket/subject {:person/id 20 :person/email "ops@example.test"}} (:engine/data person)))
          (is (empty? (:findings (result/validate-query-result vocabulary device))))
          (is (empty? (:findings (result/validate-query-result vocabulary person))))
          (testing "a record carrying both identities is withheld and reported, never guessed"
            (let [both (q {:ticket/id 3} union-q)]
              (is (= :ok (:engine/status both)) (pr-str both))
              (is (nil? (get-in both [:engine/data :ticket/subject])))
              (is (:engine/partial? both))
              (is (= [[:ticket/subject]] (mapv :path (issues-of both :engine.execution/union-ambiguous))))
              (is (not (re-find #"switch|x@example" (pr-str (:engine/issues both)))) "no value leaves the engine"))))))))

(def ^:private retired-1-4 {:contract/family "atlas-semantic" :contract/version "1.4.0"})

(deftest the-same-fragments-are-read-without-a-descriptor-and-refused-under-a-retired-one
  ;; Task book «contract-upgrade-and-retirement» PR1 (owner 2026-09-19).
  (let [compile (fn [options] (compiler/compile-candidate vocabulary definitions adapters {} options))]
    (is (some? (:compiled (compile {}))) (pr-str (:findings (compile {}))))
    (let [r (compile {:contract retired-1-4})]
      (is (nil? (:compiled r)))
      (is (= [version/version-retired] (:findings r))))))

(deftest the-2-0-document-carries-both-capabilities-through-the-contract
  (let [document {:contract version/semantic-v2
                  :workspace {:workspace/id "nifi-dev"}
                  :graph {:graph/id "nifi" :graph/label "NiFi" :graph/exposure :internal
                          :graph/entities ["nifi.pg" "ticket" "device" "person"]
                          :graph/resolvers ["pg-by-id" "ticket-by-id"]
                          :graph/operations [] :graph/dependencies [] :graph/terms []}
                  :vocabulary vocabulary
                  :execution definitions
                  :ownership {:entities {"nifi.pg" "nifi" "ticket" "nifi" "device" "nifi" "person" "nifi"}
                              :resolvers {"pg-by-id" "nifi" "ticket-by-id" "nifi"}
                              :operations {} :terms {}}
                  :publication {:release/version "1.0.0" :source/revision 1
                                :required-capabilities #{:semantic/core-v1 :connector/static
                                                         :graph/single-owner-v1 :deployment/source-slot-v1
                                                         :artifact/embedded-closure-v1
                                                         :execution/output-union-v2 :execution/output-recursion-v2}}}
        checkers (update-vals adapters :validate)]
    (is (empty? (:findings (contract/validate-document document checkers)))
        (pr-str (:findings (contract/validate-document document checkers))))
    (doseq [cap [:execution/output-union-v2 :execution/output-recursion-v2]]
      (is (some #(= :contract/capability-undeclared (:code %))
                (:findings (contract/validate-document
                            (update-in document [:publication :required-capabilities] disj cap) checkers)))
          (str cap)))
    (is (= [version/version-retired]
           (:findings (contract/validate-document (assoc document :contract retired-1-4) checkers))))))

;; ---------------------------------------------------------------------------
;; B: recursion over a SQLite self-referencing table (ceiling 2)
;; ---------------------------------------------------------------------------

(def ^:private grp-vocabulary
  {:entities {"grp" {:entity/label "Group" :entity/identity [:grp/id]
                     :entity/attributes [:grp/id :grp/name :grp/children]}}
   :attributes {:grp/id {:attribute/label "ID" :attribute/type :string :attribute/nullable? false}
                :grp/name {:attribute/label "Name" :attribute/type :string :attribute/nullable? false}
                :grp/children {:attribute/label "Children" :attribute/type :ref :attribute/nullable? false
                               :attribute/target "grp" :attribute/cardinality :many}}})

(def ^:private grp-definitions
  {:resolvers {"grp-by-id" {:label "Group by id" :effect :read :result/cardinality :one
                            :input [:grp/id] :output [:grp/id :grp/name]}
               "grp-children" {:label "Children of a group" :effect :read :result/cardinality :many
                               :input [:grp/id]
                               :output [{:grp/children [:grp/id :grp/name {:grp/children 2}]}]
                               :pagination {:default 50 :max 50}
                               :sort {:allowed [:grp/name] :default [:grp/name :asc]}}}
   :operations {}
   :sources {"db" {:source/kind "sql" :source/effects #{:read}}}
   :bindings {"by-id-sql" {:binding/for [:resolver "grp-by-id"] :binding/source "db" :binding/effect :read
                           :binding/implementation {:query "SELECT id, name FROM grp WHERE id = :id"}
                           :binding/input-map {:grp/id "id"}
                           :binding/output-map {:grp/id "id" :grp/name "name"}}
              "children-sql" {:binding/for [:resolver "grp-children"] :binding/source "db" :binding/effect :read
                              :binding/implementation {:query "SELECT id, name FROM grp WHERE parent_id = :id"}
                              :binding/input-map {:grp/id "id"}
                              :binding/output-map {:grp/children "children" :grp/id "id" :grp/name "name"}}}
   :selection {[:resolver "grp-by-id"] "by-id-sql" [:resolver "grp-children"] "children-sql"}})

(deftest sql-recursion-is-planned-per-row-and-a-request-for-five-is-clamped-to-two
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE grp (id TEXT PRIMARY KEY, name TEXT, parent_id TEXT)"])
        (jdbc/execute! connection ["INSERT INTO grp VALUES ('root','Root',NULL),('a','A','root'),('b','B','root'),('a1','A1','a'),('a1x','A1X','a1')"])
        (let [candidate (compiler/compile-candidate grp-vocabulary grp-definitions adapters {"db" {:jdbc_url url}} v2)
              q (fn [query] (engine/query (:compiled candidate) {:graph "grp" :version 1} {:entity {:grp/id "root"} :query query}))]
          (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
          (when (:compiled candidate)
            (let [answer (q [:grp/name {:grp/children 5}])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= [{:path [] :attribute :grp/children :requested 5 :applied 2}] (:recursion/depth-applied answer)))
              (is (= {:grp/name "Root"
                      :grp/children [{:grp/name "A" :grp/children [{:grp/name "A1" :grp/children [{:grp/name "A1X"}]}]}
                                     {:grp/name "B" :grp/children []}]}
                     (:engine/data answer))
                  "two levels are descended into; the third level answers the requested shape from its parent's rows and is not descended into")
              (is (empty? (:findings (result/validate-query-result grp-vocabulary answer)))
                  (pr-str (:findings (result/validate-query-result grp-vocabulary answer)))))
            (testing "a union on a sql binding is refused at the binding, not discovered at query time"
              (let [union-vocab (-> grp-vocabulary
                                    (assoc-in [:entities "grp" :entity/attributes] [:grp/id :grp/name :grp/children :grp/owner])
                                    (assoc-in [:entities "who"] {:entity/label "Who" :entity/identity [:who/id] :entity/attributes [:who/id]})
                                    (assoc-in [:entities "what"] {:entity/label "What" :entity/identity [:what/id] :entity/attributes [:what/id]})
                                    (assoc-in [:attributes :grp/owner] {:attribute/label "Owner" :attribute/type :ref :attribute/nullable? true
                                                                        :attribute/targets #{"who" "what"}})
                                    (assoc-in [:attributes :who/id] {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false})
                                    (assoc-in [:attributes :what/id] {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}))
                    union-defs (-> grp-definitions
                                   (assoc-in [:resolvers "grp-by-id" :output]
                                             [:grp/id :grp/name {:grp/owner {"who" [:who/id] "what" [:what/id]}}])
                                   (update-in [:bindings "by-id-sql" :binding/output-map] assoc :grp/owner "owner" :who/id "who_id" :what/id "what_id"))
                    r (compiler/compile-candidate union-vocab union-defs adapters {"db" {:jdbc_url url}} v2)]
                (is (nil? (:compiled r)))
                (is (some #(and (= :connector.binding/union-output-unsupported (:code %))
                                (= [:bindings "by-id-sql" :binding/output-map :grp/owner] (:path %)))
                          (:findings r))
                    (pr-str (:findings r)))))))
        (finally (sql/close-all-pools!))))))

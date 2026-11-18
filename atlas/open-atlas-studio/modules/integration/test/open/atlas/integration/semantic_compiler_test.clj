(ns open.atlas.integration.semantic-compiler-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.connector.sql.impl.run :as sql-run]
            [open.atlas.common.connector.derived :as derived]
            [open.atlas.common.semantic-compiler :as compiler]
            [open.atlas.common.semantic-result :as semantic-result]
            [open.atlas.common.semantic-artifact :as semantic-artifact]
            [open.atlas.common.engine :as engine]))

(def vocabulary
  {:entities {"support.case" {:entity/label "Case" :entity/identity [:support.case/id]
                              :entity/attributes [:support.case/id :support.case/title]}}
   :attributes {:support.case/id {:attribute/label "ID" :attribute/type :integer :attribute/nullable? false}
                :support.case/title {:attribute/label "Title" :attribute/type :string :attribute/nullable? false}}})
(def definitions
  {:resolvers {"lookup" {:label "Lookup" :effect :read :result/cardinality :one
                         :input [:support.case/id] :output [:support.case/title]}}
   :operations {}
   :sources {"support" {:source/kind "sql" :source/effects #{:read}}}
   :bindings {"lookup-sql" {:binding/for [:resolver "lookup"] :binding/effect :read :binding/source "support"
                            :binding/implementation {:query "SELECT title FROM cases WHERE id = :id"}
                            :binding/input-map {:support.case/id "id"}
                            :binding/output-map {:support.case/title "title"}}}
   :selection {[:resolver "lookup"] "lookup-sql"}})
(def adapters {"sql" {:validate #(empty? (:findings (sql/validate-binding %)))
                       :lower sql/binding-operation}})

(deftest nullable-reference-omits-identity-and-skips-target-io
  (let [v (-> vocabulary
              (update-in [:entities "support.case" :entity/attributes] conj :support.case/foreign-id)
              (assoc-in [:attributes :support.case/foreign-id]
                        {:attribute/label "Foreign ID" :attribute/type :integer :attribute/nullable? true})
              (assoc :references [{:reference/from :support.case/foreign-id :reference/to :support.case/id
                                   :reference/direction :two-way :reference/on-null :omit}]))
        calls (atom 0)
        original sql-run/compile-read
        url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
        (jdbc/execute! connection ["INSERT INTO cases VALUES (0, 'zero'), (1, 'one')"])
        (with-redefs [sql-run/compile-read (fn [& args]
                                          (let [run (apply original args)]
                                            (fn [& inputs] (swap! calls inc) (apply run inputs))))]
          (let [candidate (compiler/compile-candidate v definitions adapters {"support" {:jdbc_url url}})
                q #(engine/query (:compiled candidate) {:graph "test" :version 1}
                                 {:entity %1 :query %2})]
            (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
            (when (:compiled candidate)
              (let [answer (q {:support.case/foreign-id nil} [:support.case/id :support.case/title])]
                (is (not (contains? (:engine/data answer) :support.case/id)))
                (is (zero? @calls) (pr-str answer)))
              (q {} [:support.case/id :support.case/title])
              (is (zero? @calls) "Missing foreign key must also skip target IO")
              (is (= "zero" (get-in (q {:support.case/foreign-id 0} [:support.case/title]) [:engine/data :support.case/title])))
              (is (= 1 @calls))
              (is (= 1 (get-in (q {:support.case/id 1} [:support.case/foreign-id]) [:engine/data :support.case/foreign-id]))))))
        (finally (sql/close-all-pools!))))))

(deftest semantic-definitions-use-the-existing-engine-and-real-sqlite
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
        (jdbc/execute! connection ["INSERT INTO cases VALUES (?, ?)" 1 "支持请求"])
        (let [result (compiler/compile-candidate vocabulary definitions adapters {"support" {:jdbc_url url}})]
          (is (empty? (:findings result)) (pr-str (:findings result)))
          (is (some? (:compiled result)))
          (when (:compiled result)
            (let [answer (engine/query (:compiled result) {:graph "test" :version 1}
                                       {:entity {:support.case/id 1} :query [:support.case/title]})]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= "支持请求" (get-in answer [:engine/data :support.case/title])))
              (is (= answer (:response (semantic-result/validate-query-result vocabulary answer)))))))
        (finally (sql/close-all-pools!))))))

(deftest invalid-selection-and-missing-deployment-do-not-compile
  (doseq [result [(compiler/compile-candidate vocabulary (assoc definitions :selection {}) adapters {})
                  (compiler/compile-candidate vocabulary definitions adapters {})]]
    (is (seq (:findings result)))
    (is (nil? (:compiled result)))))

(deftest explicit-write-compiles-in-development-only
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")
        defs (-> definitions
                 (assoc-in [:sources "support" :source/effects] #{:read :write})
                 (assoc-in [:operations "create"] {:label "Create" :effect :write :result/cardinality :none
                                                    :input [:support.case/id :support.case/title] :output []})
                 (assoc-in [:bindings "create-sql"]
                           {:binding/for [:operation "create"] :binding/source "support" :binding/effect :write
                            :binding/implementation {:query "INSERT INTO cases VALUES (:id, :title)"}
                            :binding/input-map {:support.case/id "id" :support.case/title "title"}
                            :binding/output-map {}})
                 (assoc-in [:selection [:operation "create"]] "create-sql"))]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
        (let [result (compiler/compile-candidate vocabulary defs adapters {"support" {:jdbc_url url}})]
          (is (empty? (:findings result)) (pr-str (:findings result)))
          (when (:compiled result)
            (let [answer (engine/query (:compiled result) {:graph "test" :version 1}
                                       [(list 'create {:support.case/id 2 :support.case/title "New"})])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= 1 (:n (jdbc/execute-one! connection ["SELECT count(*) AS n FROM cases"])))))))
        (finally (sql/close-all-pools!))))))

(deftest pagination-filter-sort-and-metrics-survive-the-bridge
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")
        v (-> vocabulary
              (update-in [:entities "support.case" :entity/attributes] conj :support.case/all)
              (assoc-in [:attributes :support.case/all] {:attribute/label "Cases" :attribute/type :ref
                                                        :attribute/nullable? false :attribute/cardinality :many
                                                        :attribute/target "support.case"}))
        metric {:entity "support.case" :label "Count" :expression {:contract :duckdb-spill/v1 :text "SELECT COUNT(*) AS n FROM input"}}
        defs (-> definitions
                 (assoc :resolvers {"all" {:label "All" :effect :read :result/cardinality :many :input []
                                           :output [{:support.case/all [:support.case/id :support.case/title]}]
                                           :pagination {:default 1 :max 5}
                                           :sort {:allowed [:support.case/id] :default [:support.case/id :desc]}
                                           :filters {:minimum {:attribute :support.case/id :operator :gte}}}})
                 (assoc :bindings {"all-sql" {:binding/for [:resolver "all"] :binding/source "support" :binding/effect :read
                                             :binding/implementation {:query "SELECT id, title FROM cases"}
                                             :binding/input-map {} :binding/output-map {:support.case/all "all"
                                                                                      :support.case/id "id" :support.case/title "title"}}})
                 (assoc :selection {[:resolver "all"] "all-sql"} :metrics {"count" metric}))]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
        (doseq [i [1 2 3]] (jdbc/execute! connection ["INSERT INTO cases VALUES (?, ?)" i (str i)]))
        (let [result (compiler/compile-candidate v defs adapters {"support" {:jdbc_url url}})]
          (is (empty? (:findings result)) (pr-str (:findings result)))
          (when (:compiled result)
            (is (= metric (get-in result [:compiled :engine/model :metrics "count"])))
            (let [answer (engine/query (:compiled result) {:graph "test" :version 1}
                                       [{(list :support.case/all {:minimum 2 :limit 2}) [:support.case/id]}])]
              (is (= :ok (:engine/status answer)) (pr-str answer))
              (is (= [3 2] (mapv :support.case/id (get-in answer [:engine/data :support.case/all])))))))
        (finally (sql/close-all-pools!))))))

(deftest derived-binding-reuses-the-existing-sandbox
  (let [v (-> vocabulary
              (update-in [:entities "support.case" :entity/attributes] conj :support.case/next)
              (assoc-in [:attributes :support.case/next] {:attribute/label "Next" :attribute/type :integer :attribute/nullable? false}))
        defs {:resolvers {"next" {:label "Next" :effect :read :result/cardinality :one
                                  :input [:support.case/id] :output [:support.case/next]}}
              :operations {} :sources {"derived" {:source/kind "derived" :source/effects #{:read}}}
              :bindings {"next-derived" {:binding/for [:resolver "next"] :binding/source "derived" :binding/effect :read
                                         :binding/implementation {:expr "(+ id 1)"}
                                         :binding/input-map {:support.case/id "id"} :binding/output-map {:support.case/next "next"}}}
              :selection {[:resolver "next"] "next-derived"}}
        adapter {"derived" {:validate #(empty? (:findings (derived/validate-binding %))) :lower derived/binding-operation}}
        result (compiler/compile-candidate v defs adapter {})]
    (is (empty? (:findings result)) (pr-str (:findings result)))
    (when (:compiled result)
      (is (= 5 (get-in (engine/query (:compiled result) {:graph "test" :version 1}
                                      {:entity {:support.case/id 4} :query [:support.case/next]})
                      [:engine/data :support.case/next]))))
    (is (seq (:findings (compiler/compile-candidate v
                                                   (assoc-in defs [:bindings "next-derived" :binding/implementation :expr] "(slurp id)") adapter {}))))))

(deftest artifact-roundtrip-recompiles-with-the-same-engine
  (let [document (edn/read-string (slurp (io/resource "semantic-v2.edn")))
        checkers (update-vals adapters :validate)
        encoded (semantic-artifact/encode-candidate document {} checkers)
        decoded (semantic-artifact/read-candidate (:files encoded) (:artifact-digest encoded) {} checkers
                                                  #{:semantic/core-v1 :connector/sql :graph/single-owner-v1
                                                    :deployment/source-slot-v1 :artifact/embedded-closure-v1})
        url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (is (= document (:document decoded)))
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (try
        (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
        (jdbc/execute! connection ["INSERT INTO cases VALUES (1, 'artifact')"])
        (let [loaded (:document decoded)
              candidate (compiler/compile-candidate (:vocabulary loaded) (:execution loaded) adapters {"db" {:jdbc_url url}})]
          (is (empty? (:findings candidate)) (pr-str (:findings candidate)))
          (when (:compiled candidate)
            (is (= "artifact" (get-in (engine/query (:compiled candidate) {:graph "support" :version 1}
                                                    {:entity {:support.case/id 1} :query [:support.case/title]})
                                      [:engine/data :support.case/title])))))
        (finally (sql/close-all-pools!))))))

(deftest declared-alias-and-equivalence-reach-the-existing-planner
  (let [v (-> vocabulary
              (update-in [:entities "support.case" :entity/attributes] conj :support.case/number)
              (assoc-in [:attributes :support.case/number] {:attribute/label "Number" :attribute/type :integer
                                                            :attribute/nullable? false :attribute/identity-scope "support.case"
                                                            :attribute/identity-component :support.case/id})
              (assoc-in [:attributes :support.case/id :attribute/identity-scope] "support.case")
              (assoc-in [:attributes :support.case/id :attribute/identity-component] :support.case/id))
        execution {:resolvers {"label" {:label "Label" :effect :read :result/cardinality :one
                                         :input [:support.case/id :support.case/number] :output [:support.case/title]}}
                   :operations {} :sources {"derived" {:source/kind "derived" :source/effects #{:read}}}
                   :bindings {"label" {:binding/for [:resolver "label"] :binding/source "derived" :binding/effect :read
                                        :binding/implementation {:expr "(str id number)"}
                                        :binding/input-map {:support.case/id "id" :support.case/number "number"}
                                        :binding/output-map {:support.case/title "title"}}}
                   :selection {[:resolver "label"] "label"}}
        adapter {"derived" {:validate #(empty? (:findings (derived/validate-binding %))) :lower derived/binding-operation}}
        alias-v (assoc v :aliases [{:alias/from :support.case/number :alias/to :support.case/id :alias/direction :one-way :alias/description "Preserved alias explanation"}])
        equivalence-v (assoc v :equivalences [{:equivalence/attributes [:support.case/id :support.case/number]
                                               :equivalence/scope "support.case" :equivalence/direction :two-way
                                               :equivalence/description "Preserved equivalence explanation"}])
        alias-candidate (compiler/compile-candidate alias-v execution adapter {})
        eq-candidate (compiler/compile-candidate equivalence-v execution adapter {})]
    (is (empty? (:findings alias-candidate)) (pr-str (:findings alias-candidate)))
    (is (empty? (:findings eq-candidate)) (pr-str (:findings eq-candidate)))
    (when (and (:compiled alias-candidate) (:compiled eq-candidate))
      (let [q (fn [candidate input output]
                (engine/query (:compiled candidate) {:graph "test" :version 1} {:entity input :query output}))]
        (is (= 9 (get-in (q alias-candidate {:support.case/number 9} [:support.case/id]) [:engine/data :support.case/id])))
        (is (not= 9 (get-in (q alias-candidate {:support.case/id 9} [:support.case/number]) [:engine/data :support.case/number])))
        (is (= 9 (get-in (q eq-candidate {:support.case/id 9} [:support.case/number]) [:engine/data :support.case/number])))
        (is (= 9 (get-in (q eq-candidate {:support.case/number 9} [:support.case/id]) [:engine/data :support.case/id])))))))

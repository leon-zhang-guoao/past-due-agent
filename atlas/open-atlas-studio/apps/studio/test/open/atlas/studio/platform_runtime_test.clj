(ns open.atlas.studio.platform-runtime-test
  "doc 89 Phase 2: this file used to pin bridge②'s `:batch?`-deletion bug
   (§8.9.2) — a dialect→legacy translation that shipped its own silent-[]
   failure. The bridge is gone; what replaced it is `platform-runtime`
   compiling straight through `open.atlas.common.engine`, the same compiler
   `/api/studio/*` and a deployed Server use. This file now pins THAT
   switch, on the exact shape the original bug hit: a batched child
   collection over a composite-key join table (`film`/`film_actor`, a
   two-column primary key on the child), against a real SQLite database via
   the real `open.atlas.common.connector.sql` — not a fake connector, and
   not fixtures/** (a self-contained fixture, per doc 89's brief: this is a
   test-authoring concern, not a modelling one)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [open.atlas.runtime.obs :as obs]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.derived]
            [open.atlas.common.connector.sql]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.builder-server :as builder-server]
            [open.atlas.studio.platform-runtime :as runtime]))

;; `file:…?mode=memory&cache=shared` rather than `:memory:` — the latter gives
;; each pooled connection its own empty database. Shared cache keeps one
;; database alive across every connection HikariCP opens, as long as one
;; connection (the keep-alive below) stays open (same pattern as
;; modules/common/connector-sql/test/.../db.clj, which the engine's own real-
;; SQLite fixture uses).
(def ^:private jdbc-url "jdbc:sqlite:file:atlas_studio_film_fixture?mode=memory&cache=shared")

(def ^:private keep-alive (atom nil))

(defn- with-fixture-db [f]
  (reset! keep-alive (java.sql.DriverManager/getConnection jdbc-url))
  (try
    (doseq [s ["CREATE TABLE film (film_id INTEGER PRIMARY KEY, title TEXT NOT NULL)"
               "CREATE TABLE film_actor (film_id INTEGER NOT NULL, actor_id INTEGER NOT NULL, PRIMARY KEY (film_id, actor_id))"
               "INSERT INTO film (film_id, title) VALUES (1, 'ACADEMY DINOSAUR')"
               "INSERT INTO film (film_id, title) VALUES (2, 'ACE GOLDFINGER')"
               ;; film 1 has two actors, film 2 has one — enough to prove the
               ;; batch executor keeps each parent's own rows apart rather
               ;; than bleeding or dropping a slot.
               "INSERT INTO film_actor (film_id, actor_id) VALUES (1, 10)"
               "INSERT INTO film_actor (film_id, actor_id) VALUES (1, 20)"
               "INSERT INTO film_actor (film_id, actor_id) VALUES (2, 30)"]]
      (jdbc/execute! ^java.sql.Connection @keep-alive [s]))
    (f)
    (finally (.close ^java.sql.Connection @keep-alive))))

(use-fixtures :once with-fixture-db)

(def ^:private model
  (let [{:keys [model findings]}
        (dialect/read-dialect
         (pr-str
          ;; Both namespaces are registered: D2.29 (owner 2026-09-02) requires a
          ;; join attribute's `:target` to be a key of `:namespaces`.
          {:namespaces {"pg.dvd3.film" {} "pg.dvd3.film_actor" {}}
           ;; D2.29: the path to the child rows is a join ATTRIBUTE — `:target`
           ;; and no `:type` — not an entry in a retired `:navigations` map.
           :attributes {:pg.dvd3.film/film_actor {:target "pg.dvd3.film_actor"
                                                  :cardinality :one-to-many}
                        :pg.dvd3.film/film_id {:type :integer :identity? true}
                       :pg.dvd3.film/title {:type :string}
                       ;; No resolver ever produces a real value for this —
                       ;; it exists purely to give the lenient-mode test a
                       ;; sibling attribute whose only resolver genuinely
                       ;; fails at execution time.
                       :pg.dvd3.film/broken {:type :string}
                       :pg.dvd3.film_actor/actor_id {:type :integer}}
           :sources {"dvd-rental" {:type "sql" :jdbc_url jdbc-url}}
           :resolvers [{:id "pg_dvd3_film_by_id"
                       :input [:pg.dvd3.film/film_id]
                       :output [:pg.dvd3.film/title]
                       :result_type "single"
                       :source {:type "sql"
                               :query "SELECT film_id, title FROM film WHERE film_id = :film_id"}
                       :source-ref "dvd-rental"}
                      {:id "pg_dvd3_film_film_actor"
                       :input [:pg.dvd3.film/film_id]
                       :output [{:pg.dvd3.film/film_actor [:pg.dvd3.film_actor/actor_id]}]
                       :result_type "list"
                       :limit {:default 50 :max 500}
                       :source {:type "sql"
                               :query "SELECT film_id, actor_id FROM film_actor WHERE film_id IN (:film_ids)"
                               :batch {:parent-key :pg.dvd3.film/film_id
                                      :child-fk "film_id"
                                      :placeholder "film_ids"}}
                       :source-ref "dvd-rental"}
                      {:id "pg_dvd3_film_broken"
                       :input [:pg.dvd3.film/film_id]
                       :output [:pg.dvd3.film/broken]
                       :result_type "single"
                       :source {:type "sql"
                               :query "SELECT film_id, no_such_column FROM film WHERE film_id = :film_id"}
                       :source-ref "dvd-rental"}]}))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:private scope
  {:workspace "89-phase2-ws" :graph "89-phase2-graph" :graph-name "film/db" :revision "rev-1"})

(deftest batched-composite-key-child-collection-returns-real-rows
  ;; The original production bug (doc 89 §8.9.2): a batched child collection
  ;; over exactly this shape compiled onto the single-row path under the old
  ;; bridge, `IN (:film_ids)` bound nothing, and the query answered `[]` —
  ;; indistinguishable from "this film really has no actors". This proves the
  ;; engine path does not repeat that.
  (let [entry (runtime/environment! scope model)]
    (testing "nested join over a composite-key join table returns real rows"
      (let [r (runtime/process entry {:entity {:pg.dvd3.film/film_id 1}
                                      :query [:pg.dvd3.film/title
                                              {:pg.dvd3.film/film_actor [:pg.dvd3.film_actor/actor_id]}]})]
        (is (= "ACADEMY DINOSAUR" (get-in r [:result :pg.dvd3.film/title])))
        (is (= #{10 20} (set (map :pg.dvd3.film_actor/actor_id
                                  (get-in r [:result :pg.dvd3.film/film_actor])))))
        (is (some? (:plan r))
            "the plan DTO rides along — the Run panel's Plan/Trace tab reads this")))
    (testing "a different parent gets only its own rows — batch alignment never bleeds a slot"
      (let [r (runtime/process entry {:entity {:pg.dvd3.film/film_id 2}
                                      :query [{:pg.dvd3.film/film_actor [:pg.dvd3.film_actor/actor_id]}]})]
        (is (= [30] (map :pg.dvd3.film_actor/actor_id
                         (get-in r [:result :pg.dvd3.film/film_actor]))))))))

(deftest bare-join-attribute-is-refused-rather-than-answering-empty
  (let [entry (runtime/environment! scope model)]
    (try
      (runtime/process entry {:entity {:pg.dvd3.film/film_id 1}
                              :query [:pg.dvd3.film/film_actor]})
      (is false "expected an :engine.query/join-required rejection, got a result")
      (catch clojure.lang.ExceptionInfo e
        (is (= :engine.query/join-required (:code (ex-data e)))
            "D5.4: a join attribute named bare is refused, not answered with []")))))

(deftest lenient-mode-returns-partial-data-when-a-sibling-resolver-fails
  (let [entry (runtime/environment! scope model)
        req   {:entity {:pg.dvd3.film/film_id 1}
              :query  [:pg.dvd3.film/title :pg.dvd3.film/broken]}]
    (testing "off by default: a resolver that genuinely fails fails the whole request"
      (try
        (runtime/process entry req)
        (is false "expected the request to fail")
        (catch clojure.lang.ExceptionInfo e
          (is (= :engine.execution/failed (:code (ex-data e))))
          (is (= :source (:error/category (ex-data e)))))))
    (testing ":lenient? true: the working sibling attribute still comes back"
      (let [r (runtime/process entry (assoc req :lenient? true))]
        (is (= "ACADEMY DINOSAUR" (get-in r [:result :pg.dvd3.film/title])))
        (is (not (contains? (:result r) :pg.dvd3.film/broken))
            "the broken attribute is missing rather than poisoning the whole answer")))))

;; ---------------------------------------------------------------------------
;; Derived resolvers (doc 89 Phase 2 point 6): the legacy Studio compiler had
;; no derived executor at all — `platform-runtime/config`'s old `resolvers`
;; filter dropped every `:type "derived"` resolver before it ever reached the
;; legacy compiler (`(filter #(= "sql" (get-in % [:source :type])) ...)`), so
;; fixtures/demo's 13 derived resolvers were catalog-visible but never
;; actually ran on the builder path. This is an intended improvement, not a
;; side effect — prove it directly against the real demo model rather than a
;; synthetic one.
;; ---------------------------------------------------------------------------

(def ^:private demo-model
  ;; **`fixtures/demo` by name, not "whatever the seed happens to be"**. This
  ;; test asserts facts about the cis fixture — 13 derived resolvers, an
  ;; `audit_info` attribute — so it must name it. It used to go through
  ;; `builder-server/model-path`, and the day the packaged seed became the DVD
  ;; store (8.14) that path started answering with a different model, which is
  ;; a confusing way to learn that this test was never about the default.
  (let [{:keys [model findings]} (dialect/read-dialect (slurp "../../fixtures/demo/model.edn"))]
    (assert (empty? (filter #(= :error (:severity %)) findings))
            (str "fixtures/demo/model.edn must read with zero errors: " (pr-str findings)))
    model))

(def ^:private demo-scope
  {:workspace "89-phase2-ws" :graph "89-phase2-demo-graph" :graph-name "demo/cis" :revision "rev-1"})

(deftest derived-resolvers-execute-on-the-builder-path
  (testing "fixtures/demo declares 13 derived resolvers"
    (is (= 13 (count (filter #(= "derived" (get-in % [:source :type])) (:resolvers demo-model))))))
  (let [entry (runtime/environment! demo-scope demo-model)
        r (runtime/process entry
                           {:entity {:cis.master.customer/entry_id "op42"
                                     :cis.master.customer/entry_datetime "2026-01-02T00:00:00Z"
                                     :cis.master.customer/u_version 3}
                            :query [:cis.master.customer/audit_info]})]
    (is (= "entry op42 @ 2026-01-02T00:00:00Z · v3"
           (get-in r [:result :cis.master.customer/audit_info]))
        "a pure derived (SCI) resolver runs and answers from caller-supplied input, no connector needed")))

(deftest run-stats-carry-a-per-resolver-timeline
  ;; Activity 的详情面板要画「哪个 resolver、什么时候开始、跑了多久」。
  ;; 抽取器是 obs 的（apps/server 迁过来的那一份），这里证明它读得懂 engine
  ;; 交出来的 stats——读不懂的话它返回 nil，面板就会永远空着而没人知道。
  (let [entry (runtime/environment! scope model)
        r (runtime/process entry {:entity {:pg.dvd3.film/film_id 1}
                                  :query [:pg.dvd3.film/title]})
        t (obs/summarize-run-stats (:stats r))]
    (is (some? (:stats r)) "process 把原始 stats 交了出来")
    (is (some? t) "obs 的摘要读得懂它——nil 意味着面板永远是空的")
    (is (pos? (:resolver-count t)))
    (is (every? #(contains? % :resolver) (:resolvers t)))
    (is (some :ms (:resolvers t)) "至少一个 resolver 带耗时，时间线才画得出来")))

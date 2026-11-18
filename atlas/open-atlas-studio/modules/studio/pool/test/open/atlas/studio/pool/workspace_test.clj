(ns open.atlas.studio.pool.workspace-test
  "**D3.22: a pool object's owning workspace is a stored column** (owner ruling
   2026-09-05).

   Two halves, and they are separate on purpose:

   - the **write face** — `:workspace` is a required key of every action, a
     `:create` stamps it, an `:update`/`:delete` must match what is stored, and
     `objects`/`as-dialect` answer per workspace;
   - the **migration** — who owns the objects written before the column
     existed. It reproduces the derivation that used to run on every write
     (`builder_server`'s `asset-workspace`), reads the claim table D3.17
     retired, and falls back to the sole workspace when there is exactly one.
     With two or more and nothing to derive from, it **refuses and names the
     objects** rather than handing one workspace's model to another.

   Like `migrate-test`, the migration half writes raw datoms: the claim and
   workspace tables belong to `studio/graph`, which the pool may not depend on
   (D1.8), and the state under test is by definition one no current writer can
   produce."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)
(def ^:dynamic *cfg* nil)

(defn- with-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "ws"))})]
    (pool/delete-pool! cfg)
    (let [conn (pool/open-pool! cfg)]
      (binding [*conn* conn *cfg* cfg]
        (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(use-fixtures :each with-pool)

(defn- codes-of [findings] (set (map :code findings)))

(defn- create! [workspace ns-name]
  (pool/execute-action *conn* {:workspace workspace :action :create :type :namespace
                               :id ns-name :entry {:kind :master}
                               :expected_revision 0}))

;; ---------------------------------------------------------------------------
;; The column exists
;; ---------------------------------------------------------------------------

(deftest the-schema-carries-the-column
  (is (some #(= :pool.object/workspace (:db/ident %)) pool/datahike-schema)
      "core-schema's fifth entry (D3.22)")
  (is (= :db.type/string
         (:db/valueType (first (filter #(= :pool.object/workspace (:db/ident %))
                                       pool/datahike-schema))))))

;; ---------------------------------------------------------------------------
;; The write face
;; ---------------------------------------------------------------------------

(deftest an-action-without-a-workspace-is-refused
  "The key face is closed and `:workspace` is in it — a write that does not say
   on whose behalf it writes cannot be answered any more, because D3.17 left
   nothing to derive an owner from."
  (let [r (pool/execute-action *conn* {:action :create :type :namespace :id "a.b"
                                       :entry {:kind :master} :expected_revision 0})]
    (is (contains? (codes-of (:findings r)) :pool.action/malformed))
    (is (nil? (:revision r)) "and nothing was written")
    (is (nil? (pool/object (pool/db *conn*) :namespace "a.b"))))
  (testing "an empty string is not a workspace either"
    (is (contains? (codes-of (:findings (pool/execute-action
                                         *conn* {:workspace "" :action :create
                                                 :type :namespace :id "a.b"
                                                 :entry {:kind :master}
                                                 :expected_revision 0})))
                   :pool.action/malformed))))

(deftest a-create-stamps-the-object
  (is (empty? (:findings (create! "alpha" "a.b"))))
  (is (= "alpha" (:pool/workspace (pool/object (pool/db *conn*) :namespace "a.b")))))

(deftest another-workspace-may-not-update-or-delete-it
  (create! "alpha" "a.b")
  (let [r (pool/execute-action *conn* {:workspace "beta" :action :update :type :namespace
                                       :id "a.b" :entry {:label "mine now"}
                                       :expected_revision 1})
        f (first (filter #(= :pool.workspace/mismatch (:code %)) (:findings r)))]
    (is (some? f))
    (is (= "alpha" (:expected f)) "the refusal names who to go and ask")
    (is (= "beta" (:got f)))
    (is (not (contains? (:pool/entry (pool/object (pool/db *conn*) :namespace "a.b")) :label))
        "and nothing was written"))
  (is (contains? (codes-of (:findings (pool/execute-action
                                       *conn* {:workspace "beta" :action :delete
                                               :type :namespace :id "a.b"
                                               :expected_revision 1})))
                 :pool.workspace/mismatch))
  (is (some? (pool/object (pool/db *conn*) :namespace "a.b"))))

(deftest the-owner-may-update-and-the-stamp-survives
  "`update-tx` retracts every field the new value does not carry, so an update
   that forgot to restate the stamp would leave an object nobody could ever
   write again."
  (create! "alpha" "a.b")
  (is (empty? (:findings (pool/execute-action
                          *conn* {:workspace "alpha" :action :update :type :namespace
                                  :id "a.b" :entry {:label "Thing"}
                                  :expected_revision 1}))))
  (let [o (pool/object (pool/db *conn*) :namespace "a.b")]
    (is (= "alpha" (:pool/workspace o)))
    (is (= "Thing" (:label (:pool/entry o))))))

(deftest objects-and-as-dialect-answer-per-workspace
  (create! "alpha" "a.b")
  (create! "beta" "c.d")
  (let [db (pool/db *conn*)]
    (is (= #{"a.b" "c.d"} (set (map :pool/id (pool/objects db)))) "the whole pool")
    (is (= ["a.b"] (map :pool/id (pool/objects db "alpha"))))
    (is (= ["c.d"] (map :pool/id (pool/objects db "beta"))))
    (is (= #{"a.b"} (set (keys (:namespaces (pool/as-dialect db "alpha"))))))
    (is (= #{"a.b" "c.d"} (set (keys (:namespaces (pool/as-dialect db))))))))

;; ---------------------------------------------------------------------------
;; The migration
;; ---------------------------------------------------------------------------

(def ^:private governance-schema
  "The `studio/graph` idents the stamp pass reads. Installed here because the
   pool's own schema does not carry them and this test may not require that
   module (D1.8) — in a real Studio one Datahike store holds both."
  [{:db/ident :graph.claim/selector :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :graph.claim/workspace :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :graph.workspace/name :db/valueType :db.type/string
    :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}])

(defn- govern! [claims workspaces]
  (d/transact *conn* governance-schema)
  (d/transact *conn* (into (vec (for [[s w] claims]
                                  {:graph.claim/selector s :graph.claim/workspace w}))
                           (for [w workspaces] {:graph.workspace/name w}))))

(defn- unstamped-import!
  "A pool as it was written before the column existed: the export envelope
   carries no `:pool/workspace`, exactly as a pre-D3.22 backup does."
  []
  (pool/import-pool!
   *conn*
   {:pool/version 1
    :pool/objects
    [{:pool/type :namespace :pool/id "sales.order" :pool/revision 1
      :pool/entry {:kind :transaction}}
     {:pool/type :namespace :pool/id "ops.run" :pool/revision 1
      :pool/entry {:kind :event}}
     {:pool/type :attribute :pool/id :sales.order/no :pool/revision 1
      :pool/entry {:type :string :identity? true}}
     {:pool/type :attribute :pool/id :ops.run/id :pool/revision 1
      :pool/entry {:type :string :identity? true}}
     {:pool/type :link :pool/id [:sales.order/no :ops.run/id] :pool/revision 1
      :pool/entry {:from :sales.order/no :to :ops.run/id}}
     {:pool/type :resolver :pool/id "order_by_no" :pool/revision 1
      :pool/entry {:id "order_by_no" :input [:sales.order/no]
                   :output [:sales.order/no] :result_type "single"
                   :source-ref "sales-db"
                   :source {:type "sql" :query "SELECT no FROM o WHERE no = :no"}}}
     {:pool/type :source :pool/id "sales-db" :pool/revision 1
      :pool/entry {:type "sql" :jdbc_url "${SALES_URL}"}}]}))

(deftest the-migration-stamps-from-the-claims-that-were-in-force
  "The rules are the ones `builder_server` ran on every write, moved here and
   run once: a namespace/attribute by its own name, a link by its `:from`, a
   resolver by its outputs, a source by the resolvers referencing it."
  (is (empty? (:findings (unstamped-import!))))
  (govern! {"sales.*" "sales-ws" "ops.*" "ops-ws"} ["sales-ws" "ops-ws"])
  (let [r (pool/migrate-pool! *conn*)
        db (pool/db *conn*)
        owner (fn [t id] (:pool/workspace (pool/object db t id)))]
    (is (true? (:migrated? r)) (pr-str (:findings r)))
    (is (= 7 (:stamped (:summary r))))
    (is (= 7 (:by-claim (:summary r))) "every one decided by a claim, none by fallback")
    (is (= "sales-ws" (owner :namespace "sales.order")))
    (is (= "ops-ws" (owner :namespace "ops.run")))
    (is (= "sales-ws" (owner :attribute :sales.order/no)))
    (is (= "sales-ws" (owner :link [:sales.order/no :ops.run/id]))
        "a link goes with its :from end")
    (is (= "sales-ws" (owner :resolver "order_by_no")))
    (is (= "sales-ws" (owner :source "sales-db"))
        "a source goes with the resolvers that reference it"))
  (testing "and the objects are writable by their owner afterwards"
    ;; Warnings ride along and errors do not: the link's other end now lives in
    ;; another workspace, so `sales-ws`'s own scope sees it dangling. That is
    ;; dirt this write did not introduce, so it is reported and does not block
    ;; (studio/graph ruling ⑤).
    (let [r (pool/execute-action *conn* {:workspace "sales-ws" :action :update
                                         :type :namespace :id "sales.order"
                                         :entry {:label "Order"} :expected_revision 1})]
      (is (= 2 (:revision r)))
      (is (not-any? #(= :error (:severity %)) (:findings r))))))

(deftest a-single-workspace-studio-stamps-everything-with-it
  "The local-Studio case: no claim covers `ops.*`, and there is nobody else it
   could belong to."
  (is (empty? (:findings (unstamped-import!))))
  (govern! {"sales.*" "only-ws"} ["only-ws"])
  (let [r (pool/migrate-pool! *conn*)]
    (is (true? (:migrated? r)) (pr-str (:findings r)))
    (is (= 7 (:stamped (:summary r))))
    (is (pos? (:by-sole-workspace (:summary r))))
    (is (= #{"only-ws"} (set (map :pool/workspace (pool/objects (pool/db *conn*))))))))

(deftest the-sole-claim-holder-is-the-modelling-workspace
  "Level 2 of the fallback (§1b.1, owner ruling 2026-09-05): several
   workspaces, but every claim under one of them. A claim is the evidence of
   where modelling happened; the others are empty personal workspaces. This is
   the shape of the owner's own Studio — four workspaces, nine claims, all
   under one, 169 objects no claim covered."
  (is (empty? (:findings (unstamped-import!))))
  (govern! {"sales.*" "sales-ws"} ["sales-ws" "ops-ws" "third-ws"])
  (let [r (pool/migrate-pool! *conn*)]
    (is (true? (:migrated? r)) (pr-str (:findings r)))
    (is (= 7 (:stamped (:summary r))))
    (is (pos? (:by-sole-workspace (:summary r))))
    (is (= #{"sales-ws"} (set (map :pool/workspace (pool/objects (pool/db *conn*)))))
        "including `ops.run`, which no claim covers")))

(deftest claims-under-two-workspaces-refuse-the-migration
  "Level 2 does not apply when two workspaces hold claims: which of them the
   unclaimed objects belong to is exactly the question nobody can answer from
   the store."
  (is (empty? (:findings (unstamped-import!))))
  (govern! {"sales.*" "sales-ws" "other.*" "ops-ws"} ["sales-ws" "ops-ws"])
  (let [r (pool/migrate-pool! *conn*)]
    (is (false? (:migrated? r)))
    (is (= #{:pool.migrate/unowned} (codes-of (:findings r))))
    (is (some #(= [:pool :namespace "ops.run"] (:path %)) (:findings r)))
    (is (every? #(nil? (:pool/workspace %)) (pool/objects (pool/db *conn*)))
        "and not one byte was written")))

(deftest an-undecidable-object-refuses-the-migration-by-name
  "Two workspaces and NO claim at all: neither fallback level applies, so it
   refuses and names every object it could not decide. Nothing is written."
  (is (empty? (:findings (unstamped-import!))))
  (govern! {} ["sales-ws" "ops-ws"])
  (let [r (pool/migrate-pool! *conn*)]
    (is (false? (:migrated? r)))
    (is (= #{:pool.migrate/unowned} (codes-of (:findings r))))
    (is (some #(= [:pool :namespace "ops.run"] (:path %)) (:findings r))
        "named, one finding per object")
    (is (every? #(nil? (:pool/workspace %)) (pool/objects (pool/db *conn*)))
        "and not one byte was written")
    (testing "no entry content leaks into the refusal (finding-v1 §5)"
      (is (not-any? #(str/includes? (str (:got %)) "SALES_URL") (:findings r))))))

(deftest the-stamp-pass-is-idempotent
  (is (empty? (:findings (unstamped-import!))))
  (govern! {} ["only-ws"])
  (is (true? (:migrated? (pool/migrate-pool! *conn*))))
  (let [after (pool/export-pool (pool/db *conn*))
        again (pool/migrate-pool! *conn*)]
    (is (false? (:migrated? again)))
    (is (= 0 (:stamped (:summary again))))
    (is (= after (pool/export-pool (pool/db *conn*)))
        "byte-for-byte the same pool, audit records included")))

(deftest the-stamp-rides-the-export-and-comes-back
  (create! "alpha" "a.b")
  (let [text (pool/export-pool (pool/db *conn*))
        cfg2 (pool/config {:backend :mem :id (str (gensym "ws2"))})]
    (pool/delete-pool! cfg2)
    (let [c2 (pool/open-pool! cfg2)]
      (try
        (is (empty? (:findings (pool/import-pool! c2 (read-string text)))))
        (is (= "alpha" (:pool/workspace (pool/object (pool/db c2) :namespace "a.b"))))
        (finally (pool/close-pool! c2) (pool/delete-pool! cfg2))))))

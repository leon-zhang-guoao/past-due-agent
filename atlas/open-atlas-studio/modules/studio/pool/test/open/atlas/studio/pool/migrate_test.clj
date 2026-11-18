(ns open.atlas.studio.pool.migrate-test
  "**The D2.29 pool migration** (`impl/migrate.clj`, owner ruling 2026-09-02:
   a pre-D2.29 pool is fixed, not refused).

   ## Why this test writes raw datoms

   The pool it has to start from **cannot be built through the public API**:
   `execute_action` refuses `:navigation` as an unknown type and
   `:collection?` as an unknown entry key — that is D2.29 working. So the old
   shapes are transacted straight into Datahike, legacy schema included.

   Going under the write face is normally exactly what this module's boundary
   rules forbid, and it is legitimate **here and only here**: the subject
   under test is historical state that no current writer can produce, and a
   migration tested only against states the current code can reach is a
   migration tested against nothing. What is fabricated is confined to
   `old-pool!` below, and every assertion afterwards reads through the public
   interface.

   ## What the fixture is

   One pool holding all three cases at once, because that is how a real
   pre-D2.29 pool looks — the demo model had all three:

   - `:shop.order/all_orders` — a `:collection? true` attribute AND a
     same-named navigation (the `all_cpos` case: merge);
   - `:shop.order/lines` — a navigation with no attribute counterpart (the
     `cpo_details` case);
   - `:shop.line/all_lines` — a `:collection? true` attribute with no
     navigation."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

;; ---------------------------------------------------------------------------
;; Fabricating a pre-D2.29 pool
;; ---------------------------------------------------------------------------

(def ^:private legacy-schema
  "The pool schema as it stood **before** D2.29, in the part D2.29 deleted.

   Copied from `git show 51bd38e:…/impl/schema.clj` and the navigation row of
   the old type table — a real pre-D2.29 pool has these idents installed
   because the code that created it installed them."
  [{:db/ident :pool.navigation/target      :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.navigation/cardinality :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :pool.navigation/label       :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.navigation/description :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.navigation/synonyms    :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :pool.attribute/collection?  :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   ;; The child edge: a navigation hung off its namespace entity.
   {:db/ident :pool.child/parent           :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}])

(def ^:private base-model
  "The part of the old pool that is still expressible today: two namespaces
   and the scalar attributes. The `:type :json` on the two collection
   attributes is what a pre-D2.29 collection carried (`fixtures/demo` before
   D2.29: `{:collection? true :type :json :label …}`)."
  {:namespaces {"shop.order" {:label "Order"}
                "shop.line"  {:label "Order line"}}
   :attributes {:shop.order/order_no  {:type :integer :label "Order No"}
                :shop.line/line_no    {:type :integer :label "Line No"}
                :shop.order/all_orders {:type :json :label "All Orders"
                                        :description "Global collection of orders."}
                :shop.line/all_lines   {:type :json :label "All Lines"}}})

(defn- okey [t id] (binding [*print-namespace-maps* false] (pr-str [t id])))

(defn- navigation-tx
  "One pre-D2.29 navigation object, as datoms."
  [tempid [ns-name local] entry revision]
  (cond-> {:db/id tempid
           :pool.object/key      (okey :navigation [ns-name local])
           :pool.object/type     :navigation
           :pool.object/id       (binding [*print-namespace-maps* false]
                                   (pr-str [ns-name local]))
           :pool.object/revision revision
           :pool.child/parent    [:pool.object/key (okey :namespace ns-name)]}
    (:target entry)      (assoc :pool.navigation/target (:target entry))
    (:cardinality entry) (assoc :pool.navigation/cardinality (:cardinality entry))
    (:label entry)       (assoc :pool.navigation/label (:label entry))
    (:description entry) (assoc :pool.navigation/description (:description entry))))

(defn- old-pool!
  "An open connection to a pool in its pre-D2.29 shape."
  []
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "old-pool"))})
        _    (pool/delete-pool! cfg)
        conn (pool/open-pool! cfg)
        r    (pool/import-pool! conn
                                {:pool/version 1
                                 :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                     (pool/dialect->objects base-model))})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    (d/transact conn legacy-schema)
    (d/transact conn
                [(navigation-tx "nav-all-orders" ["shop.order" "all_orders"]
                                {:target "shop.order" :cardinality :one-to-many
                                 :label "Navigation label that loses to the attribute's"}
                                2)
                 (navigation-tx "nav-lines" ["shop.order" "lines"]
                                {:target "shop.line" :cardinality :one-to-many :label "Lines"}
                                3)
                 [:db/add [:pool.object/key (okey :attribute :shop.order/all_orders)]
                  :pool.attribute/collection? true]
                 [:db/add [:pool.object/key (okey :attribute :shop.line/all_lines)]
                  :pool.attribute/collection? true]])
    conn))

(defn- half-written-navigation-tx
  "A **half-written** navigation object: an interrupted write that got a key,
   type and revision but never its `:pool.object/id` (nor a `:target`). A real
   pool holds exactly one — `[:navigation [\"demo.ch.posts\" \"all_posts\"]]`.
   It cannot become a valid join attribute (a join needs a target), so the
   migration must skip it rather than read its nil id and NPE, and must not
   refuse the whole migration over it."
  [tempid ns-name local]
  {:db/id tempid
   :pool.object/key      (okey :navigation [ns-name local])
   :pool.object/type     :navigation
   :pool.object/revision 1})

(defn- entry-of [db akw] (:pool/entry (pool/object db :attribute akw)))

(defn- navigations-left [db]
  (d/q '[:find [?e ...] :where [?e :pool.object/type :navigation]] db))

;; ---------------------------------------------------------------------------
;; The three shapes
;; ---------------------------------------------------------------------------

(deftest a-navigation-and-its-collection-attribute-become-one-join-attribute
  "Row 1 of D2.29's table, and the case the demo model's `all_cpos` was.

   The two objects were one thing wearing two hats; the merge keeps the join
   shape from the navigation (nothing else knows where the branch lands) and
   the prose from the attribute (that is where an author edits a label).
   `:collection?` and the scalar `:type` are **gone**, not merely unread: a
   dropped key that is still on disk comes back the day someone reads the raw
   entity."
  (let [conn (old-pool!)
        r    (pool/migrate-pool! conn)
        db   (pool/db conn)]
    (is (:migrated? r))
    (is (= 1 (:merged (:summary r))))
    (is (= {:target "shop.order" :cardinality :one-to-many
            :label "All Orders" :description "Global collection of orders."}
           (entry-of db :shop.order/all_orders))
        "the join keys come from the navigation, the prose from the attribute")
    (is (empty? (navigations-left db)) "the navigation object is gone, not orphaned")
    (is (empty? (d/q '[:find [?e ...] :where [?e :pool.attribute/collection? _]] db))
        "and so is every :collection? datom")
    (pool/close-pool! conn)))

(deftest a-navigation-with-no-attribute-becomes-one
  "Row 2. Same identity said in the surviving registry's vocabulary: the
   navigation `[\"shop.order\" \"lines\"]` is the attribute `:shop.order/lines`,
   and it keeps its revision — the object changed registry, it did not
   restart its history."
  (let [conn (old-pool!)
        r    (pool/migrate-pool! conn)
        db   (pool/db conn)]
    (is (= 1 (:navigations (:summary r))))
    (is (= {:target "shop.line" :cardinality :one-to-many :label "Lines"}
           (entry-of db :shop.order/lines)))
    (is (= 3 (:pool/revision (pool/object db :attribute :shop.order/lines))))
    (pool/close-pool! conn)))

(deftest a-collection-attribute-with-no-navigation-joins-onto-its-own-namespace
  "Row 3, which is D2.29's own sentence about what a global collection is:
   「一个『全局集合』就是 `:target` 指向自己 namespace 的 join 属性」."
  (let [conn (old-pool!)
        r    (pool/migrate-pool! conn)
        db   (pool/db conn)]
    (is (= 1 (:collections (:summary r))))
    (is (= {:target "shop.line" :cardinality :one-to-many :label "All Lines"}
           (entry-of db :shop.line/all_lines)))
    (pool/close-pool! conn)))

;; ---------------------------------------------------------------------------
;; A half-written stub (a real pool carries one)
;; ---------------------------------------------------------------------------

(deftest a-half-written-navigation-with-no-id-is-skipped-not-migrated
  "An interrupted navigation write (key/type/revision, but no `:pool.object/id`
   and no target) must not enter the migration: reading its nil id would NPE at
   `(keyword ns-name local)`, and it has no target to become a join attribute
   from. The migration skips it and still converts the real objects beside it,
   without refusing itself over the stub."
  (let [conn (old-pool!)
        _    (d/transact conn [(half-written-navigation-tx "stub-nav" "demo.ch.posts" "all_posts")])
        r    (pool/migrate-pool! conn)
        db   (pool/db conn)]
    (is (:migrated? r) "migration ran and committed, no NPE on the stub")
    (is (= {:target "shop.line" :cardinality :one-to-many :label "Lines"}
           (entry-of db :shop.order/lines))
        "the real navigation still became its join attribute")
    (is (= 1 (:merged (:summary r))))
    (is (= 1 (:navigations (:summary r))))
    (is (nil? (pool/object db :attribute :demo.ch.posts/all_posts))
        "the stub was not turned into an attribute")
    (testing "and the stub does not make an already-migrated pool re-migrate"
      (is (false? (:migrated? (pool/migrate-pool! conn)))
          "needed? ignores the id-less stub, so a second open is a no-op"))
    (pool/close-pool! conn)))

;; ---------------------------------------------------------------------------
;; Idempotency
;; ---------------------------------------------------------------------------

(deftest running-it-twice-changes-nothing-the-second-time
  "The migration hangs off **every** open, so «already migrated» is the
   common case, and a second run that wrote anything would move revisions and
   add audit records on every restart — an object nobody edited would look
   edited, which is exactly the ledger lying."
  (let [conn   (old-pool!)
        _      (pool/migrate-pool! conn)
        before (pool/export-pool (pool/db conn))
        second (pool/migrate-pool! conn)]
    (is (false? (:migrated? second)))
    (is (= {:merged 0 :navigations 0 :collections 0 :cleared 0
            ;; D3.22's stamp pass reports alongside D2.29's counts.
            :stamped 0 :by-claim 0 :by-sole-workspace 0}
           (:summary second)))
    (is (= before (pool/export-pool (pool/db conn)))
        "byte-for-byte the same pool, audit records included")
    (pool/close-pool! conn)))

(deftest a-pool-that-was-never-pre-d229-is-not-touched
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "new-pool"))})
        _    (pool/delete-pool! cfg)
        conn (pool/open-pool! cfg)]
    (pool/import-pool! conn {:pool/version 1
                             :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                 (pool/dialect->objects base-model))})
    (is (false? (:migrated? (pool/migrate-pool! conn))))
    (pool/close-pool! conn)))

;; ---------------------------------------------------------------------------
;; The result is a model the dialect accepts
;; ---------------------------------------------------------------------------

(deftest the-migrated-pool-exports-a-model-the-dialect-accepts
  "The point of the migration: after it, the pool is an ordinary post-D2.29
   pool. Not «no navigation objects are left» — that would also be true of a
   migration that deleted them — but «what comes out passes B0 and imports
   back», which is the pool's own exit condition 1 said of a migrated pool."
  (let [conn  (old-pool!)
        _     (pool/migrate-pool! conn)
        db    (pool/db conn)
        model (pool/as-dialect db)
        {:keys [findings]} (dialect/read-dialect
                            (binding [*print-namespace-maps* false] (pr-str model)))]
    (is (empty? (filter #(= :error (:severity %)) findings))
        (pr-str (filter #(= :error (:severity %)) findings)))
    (is (= 3 (count (dialect/join-attributes model)))
        "all three shapes arrived as join attributes")
    (testing "and the export still round-trips into a fresh pool"
      (let [data (edn/read-string (pool/export-pool db))
            cfg2 (pool/config {:backend :mem :id (str (gensym "reimport"))})
            _    (pool/delete-pool! cfg2)
            c2   (pool/open-pool! cfg2)
            r    (pool/import-pool! c2 data)]
        (is (empty? (:findings r)) (pr-str (:findings r)))
        (is (= (pool/export-pool db) (pool/export-pool (pool/db c2))))
        (pool/close-pool! c2)))
    (pool/close-pool! conn)))

;; ---------------------------------------------------------------------------
;; It is an auditable act
;; ---------------------------------------------------------------------------

(deftest the-migration-records-itself-without-forging-a-human
  "Audit ruling ② names migrations among the writers with no entrance to
   name, so the entrance is `:unattributed` — the shape of an absence, which
   only the pool writes. The actor says which ruling moved the data. A pool
   that quietly rewrote objects with no record would make the ledger's
   silence mean two different things."
  (let [conn (old-pool!)
        _    (pool/migrate-pool! conn)
        muts (pool/mutations (pool/db conn))]
    (is (seq muts))
    (is (every? #(= :unattributed (:mutation/entrypoint %)) muts))
    (is (every? #(= {:system "open.atlas.studio.pool" :migration "D2.29"}
                    (:mutation/actor %))
                muts)
        "a machine actor naming the ruling, never a person")
    (testing "one record per object touched, in the ledger's own vocabulary"
      (is (= #{[:attribute :shop.order/all_orders]
               [:attribute :shop.order/lines]
               [:attribute :shop.line/all_lines]
               [:navigation ["shop.order" "all_orders"]]
               [:navigation ["shop.order" "lines"]]}
             (set (map :mutation/object muts))))
      (is (= #{:update :create :delete} (set (map :mutation/action muts)))))
    (pool/close-pool! conn)))

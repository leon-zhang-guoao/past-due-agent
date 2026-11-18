(ns open.atlas.studio.graph.recipe-migration-test
  "**The recipe half of the D2.29 migration** (`pool/impl/migrate.clj`, owner
   ruling doc 106 §106.14 item 19).

   D2.29 moved navigations into the attribute registry in the pool and touched
   no recipe, which left every pre-D2.29 graph both uneditable
   (`update-recipe!` refuses the retired `:navigations` group — the member key
   set is closed at eight, scope-projection-v1 §1) and unpublishable (a
   resolver's `:output` straddles the selection, `:scope/mixed-output`, §4).

   ## Why the test lives in this module

   The repair reads recipes and is judged by the recipe writer and the
   projection, both of which live here; the pool cannot depend on this module
   (D1.8), so its own test suite can only see half the subject.

   ## Why it writes one raw datom

   A recipe carrying `:navigations` **cannot be written through the public
   API** — that is `update-recipe!` working, and it is the first of the two
   defects. So the retired group is put on the graph the way the pre-D2.29
   Studio left it: as the EDN string in `:graph.graph/members`. Everything
   afterwards is read and written through the public interface."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(defn- read! [m]
  (let [{:keys [model findings]} (dialect/read-dialect (pr-str m))]
    (assert (empty? (filter #(= :error (:severity %)) findings)) (pr-str findings))
    model))

(def ^:private model
  "One join attribute (`all_pods`), one resolver that produces it as a branch,
   and one that does not — the smallest shape in which a recipe can straddle."
  (read!
   {:attributes {:k8s.pod/namespace {:type :string :format :id :identity? true :label "NS"}
                 :k8s.pod/name      {:type :string :format :id :identity? true :label "Name"}
                 :k8s.pod/phase     {:type :string :format :text :label "Phase"}
                 :k8s.pod/all_pods  {:target "k8s.pod" :cardinality :one-to-many :label "All pods"}}
    :namespaces {"k8s.pod" {:kind :master :label "Pod"}}
    :sources    {"db" {:type "sql" :label "DB" :jdbc_url "jdbc:sqlite:memory"}}
    :resolvers  [{:id "pod_by_key" :input [:k8s.pod/namespace :k8s.pod/name]
                  :output [:k8s.pod/phase] :result_type "single"
                  :source {:type "sql" :query "SELECT 1"} :source-ref "db"}
                 {:id "pod_all" :input [] :curated? true :limit {:default 50 :max 500}
                  :output [{:k8s.pod/all_pods [:k8s.pod/namespace :k8s.pod/name
                                               :k8s.pod/phase]}]
                  :result_type "list"
                  :source {:type "sql" :query "SELECT 2"} :source-ref "db"}]}))

(def ^:private leaf-attributes
  #{:k8s.pod/namespace :k8s.pod/name :k8s.pod/phase})

(def ^:private all-attributes (conj leaf-attributes :k8s.pod/all_pods))

(defn- members [attrs resolvers]
  {:namespaces #{"k8s.pod"} :attributes attrs :resolvers resolvers
   :links #{} :terms #{} :units #{} :metrics #{} :mutations #{}})

(defn- register! [conn nm ms]
  (let [r (graph/register-graph! conn {:workspace "demo" :name nm :members ms})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    (graph/qualified-name "demo" nm)))

(defn- put-retired-group!
  "Write the retired `:navigations` group onto a registered graph's recipe, the
   way a pre-D2.29 Studio left it. See the ns docstring for why this is the one
   thing the test does under the write face."
  [conn qn navs]
  (let [db (pool/db conn)
        e  (d/q '[:find ?e . :in $ ?q :where [?e :graph.graph/qualified-name ?q]] db qn)
        ms (read-string (d/q '[:find ?m . :in $ ?e
                               :where [?e :graph.graph/members ?m]] db e))]
    (d/transact conn [{:db/id e
                       :graph.graph/members
                       (binding [*print-namespace-maps* false]
                         (pr-str (assoc ms :navigations navs)))}])))

(defn- recipe [conn qn] (:graph/members (graph/graph (pool/db conn) qn)))

(defn- old-studio!
  "A pool holding the four graphs the ruling is about, none of them migrated
   yet: two carrying the retired group (one redundant, one not), one straddling
   its resolver's `:output`, and one already clean.

   → `[cfg conn]`; the caller reopens `cfg` to run the migration."
  []
  (let [cfg  (pool/config {:backend :mem :id (str (gensym "recipes"))})
        _    (pool/delete-pool! cfg)
        conn (graph/install! (pool/open-pool! cfg))
        r    (pool/import-pool! conn
                                {:pool/version 1
                                 :pool/objects (mapv #(assoc % :pool/revision 1 :pool/workspace "demo")
                                                     (pool/dialect->objects model))})
        w    (graph/register-workspace!
              conn {:name "demo"
                    :members [{:principal {:issuer "test-idp" :subject "a"} :role :owner}]})]
    (assert (empty? (:findings r)) (pr-str (:findings r)))
    (assert (empty? (:findings w)) (pr-str (:findings w)))
    ;; (a) the retired group, its member already in `:attributes` — the shape
    ;; measured on every real graph (`demo/ps-dvd9` held 26 such members).
    (register! conn "redundant" (members all-attributes #{"pod_by_key" "pod_all"}))
    (put-retired-group! conn "demo/redundant" #{:k8s.pod/all_pods})
    ;; (a') the retired group holding the ONLY mention of a join attribute, and
    ;; no resolver that would add it back — it is moved, not lost.
    (register! conn "only-mention" (members leaf-attributes #{"pod_by_key"}))
    (put-retired-group! conn "demo/only-mention" #{:k8s.pod/all_pods})
    ;; (b) the straddling resolver: the branch key is outside the recipe, its
    ;; leaves inside — `:scope/mixed-output` at the cut.
    (register! conn "straddling" (members leaf-attributes #{"pod_by_key" "pod_all"}))
    ;; (c) already repaired, by hand or by a script — nothing to do.
    (register! conn "clean" (members all-attributes #{"pod_by_key" "pod_all"}))
    [cfg conn]))

(defn- reopen! [cfg] (pool/open-pool! cfg))

(defn- recipe-records [db]
  (filterv #(= :graph/recipe (first (:mutation/object %))) (pool/mutations db)))

(deftest the-retired-navigations-group-is-dropped-and-the-graph-becomes-editable
  "Defect 1: the member key set is closed at eight groups, so `update-recipe!`
   refuses a recipe carrying `:navigations` outright — editing from the UI is
   impossible until the group is gone."
  (let [[cfg conn] (old-studio!)]
    (testing "before the migration the graph cannot be edited at all"
      (is (= [:graph.registry/malformed]
             (mapv :code (:findings (graph/update-recipe!
                                     conn "demo/redundant"
                                     {:members (recipe conn "demo/redundant")}))))))
    (let [conn (reopen! cfg)]
      (is (not (contains? (recipe conn "demo/redundant") :navigations)))
      (is (= all-attributes (:attributes (recipe conn "demo/redundant")))
          "the member was already in :attributes, so nothing moved")
      (is (empty? (:findings (graph/update-recipe! conn "demo/redundant"
                                                   {:members (recipe conn "demo/redundant")})))
          "and the recipe is editable again"))
    (pool/delete-pool! cfg)))

(deftest a-retired-member-that-is-nowhere-else-is-moved-not-lost
  "The group is redundant in every case measured, but «redundant» is a fact
   about those pools, not a rule — a member the recipe mentions nowhere else is
   an attribute this graph selects, and dropping it would silently shrink the
   graph."
  (let [[cfg _] (old-studio!)
        conn (reopen! cfg)]
    (is (= all-attributes (:attributes (recipe conn "demo/only-mention"))))
    (is (not (contains? (recipe conn "demo/only-mention") :navigations)))
    (pool/delete-pool! cfg)))

(deftest a-straddling-join-attribute-is-added-and-the-cut-check-goes-quiet
  "Defect 2, and the root cause of `:scope/mixed-output` (scope-projection-v1
   §4): the resolver produces the branch key and its leaves, the recipe names
   only the leaves."
  (let [[cfg conn] (old-studio!)]
    (is (contains? (set (map :code (:findings (graph/project (pool/db conn) "demo/straddling"))))
                   :scope/mixed-output)
        "before the migration the graph cannot be cut")
    (let [conn (reopen! cfg)]
      (is (= all-attributes (:attributes (recipe conn "demo/straddling"))))
      (is (not (contains? (set (map :code (:findings (graph/project (pool/db conn) "demo/straddling"))))
                          :scope/mixed-output))
          "the branch key is inside the selection now, so nothing straddles"))
    (pool/delete-pool! cfg)))

(deftest a-graph-already-repaired-is-not-touched-and-gets-no-record
  "«Repaired out of the ledger» and «repaired by this pass» must be told apart
   by the ledger, which is the one place that can tell them apart at all."
  (let [[cfg _] (old-studio!)
        conn (reopen! cfg)
        recs (recipe-records (pool/db conn))]
    (is (= all-attributes (:attributes (recipe conn "demo/clean"))))
    (is (= #{"demo/only-mention" "demo/redundant" "demo/straddling"}
           (set (map #(:graph (second (:mutation/object %))) recs)))
        "one record per CHANGED graph, and the clean graph is not one of them")
    (pool/delete-pool! cfg)))

(deftest the-records-say-which-of-the-two-repairs-happened
  "A recipe is a list of names; a record saying only «the recipe changed» would
   leave the next reader to diff two EDN blobs to learn what this pass did."
  (let [[cfg _] (old-studio!)
        conn (reopen! cfg)
        by-graph (into {} (for [r (recipe-records (pool/db conn))]
                            [(:graph (second (:mutation/object r))) r]))]
    (is (= {:graph "demo/redundant" :dropped-duplicates [:k8s.pod/all_pods]}
           (second (:mutation/object (by-graph "demo/redundant")))))
    (is (= {:graph "demo/only-mention" :moved-to-attributes [:k8s.pod/all_pods]}
           (second (:mutation/object (by-graph "demo/only-mention")))))
    (is (= {:graph "demo/straddling" :added-join-attributes [:k8s.pod/all_pods]}
           (second (:mutation/object (by-graph "demo/straddling")))))
    (testing "written as the platform's own act: no entrance, a machine actor"
      (is (= :unattributed (:mutation/entrypoint (by-graph "demo/redundant"))))
      (is (= {:system "open.atlas.studio.pool" :migration "D2.29-recipes"}
             (:mutation/actor (by-graph "demo/redundant")))))
    (pool/delete-pool! cfg)))

(deftest opening-twice-repairs-once
  "The migration hangs off **every** open, so a second run that wrote anything
   would add ledger rows on every restart — a recipe nobody edited would look
   edited."
  (let [[cfg _] (old-studio!)
        conn  (reopen! cfg)
        first-run (pool/migrate-pool! conn)
        recipes (into {} (for [qn ["demo/redundant" "demo/only-mention"
                                   "demo/straddling" "demo/clean"]]
                           [qn (recipe conn qn)]))
        recs (recipe-records (pool/db conn))
        conn2 (reopen! cfg)
        second-run (pool/migrate-pool! conn2)]
    (is (false? (:migrated? first-run))
        "the first reopen already did the work; asking again finds nothing")
    (is (= 3 (count recs)) "three graphs changed, three records")
    (is (false? (:migrated? second-run)))
    (is (= recs (recipe-records (pool/db conn2))) "no ledger row was added")
    (is (= recipes (into {} (for [[qn _] recipes] [qn (recipe conn2 qn)])))
        "and no recipe moved")
    (pool/delete-pool! cfg)))

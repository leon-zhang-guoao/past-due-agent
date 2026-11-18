(ns open.atlas.studio.graph.rename-test
  "**Renaming a workspace** (E4 §1b.3, owner ruling 2026-09-05): one
   transaction over every stored occurrence of the name, then the shelf
   directories.

   What these tests hold down is the part a rename gets wrong quietly: that it
   moves the graphs' qualified names, the D3.22 ownership column and the
   version registry ids **together**, that it touches nothing belonging to
   another workspace, and that it refuses onto an occupied name instead of
   merging two workspaces into one."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [taoensso.timbre :as timbre]))

(timbre/set-min-level! :warn)

(def ^:dynamic *conn* nil)

(defn- with-pool [f]
  (let [cfg (pool/config {:backend :mem :id (str (gensym "rename-pool"))})]
    (pool/delete-pool! cfg)
    (let [conn (graph/install! (pool/open-pool! cfg))]
      (binding [*conn* conn]
        (try (f) (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))))

(use-fixtures :each with-pool)

(defn- workspace! [nm]
  (graph/register-workspace!
   *conn* {:name nm :members [{:principal {:issuer "test-idp" :subject nm} :role :owner}]}))

(defn- namespace! [workspace nm]
  (pool/execute-action *conn* {:workspace workspace :action :create :type :namespace
                               :id nm :entry {:kind :master} :expected_revision 0}))

(defn- version-row!
  "A version registry row, written as raw datoms: `studio/version` is not on
   this module's classpath (it depends on graph, not the other way round), and
   the rename has to move these ids with the name they are built from."
  [qn n]
  (d/transact *conn* [{:db/ident :studio.version/id :db/valueType :db.type/string
                       :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                      {:db/ident :studio.version/graph :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}])
  (d/transact *conn* [{:studio.version/id (str qn "@v" n) :studio.version/graph qn}]))

(defn- version-ids []
  (set (d/q '[:find [?i ...] :where [?e :studio.version/id ?i]] (pool/db *conn*))))

(deftest a-rename-moves-graphs-objects-and-versions-together
  (workspace! "demo")
  (workspace! "other")
  (graph/register-graph! *conn* {:workspace "demo" :name "core" :members {}})
  (graph/register-graph! *conn* {:workspace "other" :name "core" :members {}})
  (namespace! "demo" "sales.order")
  (namespace! "other" "ops.run")
  (version-row! "demo/core" 1)
  (version-row! "other/core" 1)
  (let [r (graph/rename-workspace! *conn* "demo" "renamed" [])
        db (pool/db *conn*)]
    (is (true? (:renamed? r)) (pr-str (:findings r)))
    (is (= ["other" "renamed"] (mapv :workspace/name (graph/workspaces db))))
    (is (= ["other/core" "renamed/core"] (mapv :graph/qualified-name (graph/graphs db))))
    (is (= "renamed" (:pool/workspace (pool/object db :namespace "sales.order"))))
    (is (= #{"renamed/core@v1" "other/core@v1"} (version-ids)))
    (testing "the other workspace is untouched — a rename is not a sweep"
      (is (= "other" (:pool/workspace (pool/object db :namespace "ops.run"))))))
  (testing "one mutation record per object that moved, with a system actor"
    (let [recent (take-last 1 (pool/mutations (pool/db *conn*)))]
      (is (= 1 (count recent)))
      (is (= :update (:mutation/action (first recent))))
      (is (= "D3.22" (:migration (:mutation/actor (first recent))))))))

(deftest a-rename-onto-an-occupied-name-is-refused
  (workspace! "demo")
  (workspace! "taken")
  (namespace! "demo" "sales.order")
  (let [r (graph/rename-workspace! *conn* "demo" "taken" [])]
    (is (false? (:renamed? r)))
    (is (= #{:workspace.registry/name-taken} (set (map :code (:findings r)))))
    (is (= "demo" (:pool/workspace (pool/object (pool/db *conn*) :namespace "sales.order")))
        "nothing was written")))

(deftest renaming-a-workspace-that-is-not-there-is-refused-and-re-running-is-a-no-op
  (workspace! "demo")
  (testing "a name nobody registered"
    (let [r (graph/rename-workspace! *conn* "ghost" "renamed" [])]
      (is (false? (:renamed? r)))
      (is (= #{:workspace.registry/unknown} (set (map :code (:findings r)))))))
  (testing "idempotence: old gone, new already there ⇒ success without work"
    (is (true? (:renamed? (graph/rename-workspace! *conn* "demo" "renamed" []))))
    (let [again (graph/rename-workspace! *conn* "demo" "renamed" [])]
      (is (false? (:renamed? again)))
      (is (true? (:already? again)))
      (is (empty? (:findings again))))))

(deftest the-shelf-directory-moves-and-says-what-it-was
  (workspace! "demo")
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "rename-shelf" (into-array java.nio.file.attribute.FileAttribute [])))
        parent (io/file root "artifacts")
        version-dir (io/file parent "demo" "core" "1")]
    (.mkdirs version-dir)
    (spit (io/file version-dir "manifest.edn") (pr-str {:workspace "demo"}))
    (let [r (graph/rename-workspace! *conn* "demo" "renamed" [parent])]
      (is (true? (:renamed? r)))
      (is (false? (.exists (io/file parent "demo"))))
      (is (.isFile (io/file parent "renamed" "core" "1" "manifest.edn")))
      (testing "the manifest is NOT rewritten — artifact-digest covers it (artifact-v1 §7.3)"
        (is (= {:workspace "demo"}
               (read-string (slurp (io/file parent "renamed" "core" "1" "manifest.edn"))))))
      (testing "and a record beside it says so"
        (let [note (read-string (slurp (io/file parent "renamed" "renamed-from.edn")))]
          (is (= "demo" (:renamed-from note)))
          (is (= "renamed" (:renamed-to note))))))))

(deftest an-email-named-workspace-does-not-warn
  (testing "owner 2026-09-05: artifact-v1 §8 admits `@`, so a personal workspace
            named after an email (D3.22) is a legal shelf directory"
    (workspace! "demo")
    (let [r (graph/rename-workspace! *conn* "demo" "jie.gao@tdsynnex.com" [])]
      (is (true? (:renamed? r)))
      (is (empty? (:findings r))))))

(deftest a-name-that-is-not-a-portable-path-segment-warns-but-does-not-refuse
  (workspace! "demo")
  (let [r (graph/rename-workspace! *conn* "demo" "core team" [])]
    (is (true? (:renamed? r)))
    (is (= #{:workspace.registry/rename-unportable} (set (map :code (:findings r)))))
    (is (every? #(= :warning (:severity %)) (:findings r))
        (str "a warning: artifact-v1 §8 forbids a space in a shelf segment, so the "
             "shelf under this name cannot be read or written — but the name is the "
             "owner's to choose"))))

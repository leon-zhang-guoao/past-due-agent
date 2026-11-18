(ns open.atlas.studio.artifact-cleanup-test
  (:require [clojure.edn :as edn]
            [open.atlas.common.semantic-artifact :as semantic]
            [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [open.atlas.runtime.artifact-cleanup :as cleanup])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [] (.toFile (Files/createTempDirectory "graph-purge-" (make-array FileAttribute 0))))
(defn- remove-tree [f]
  (doseq [x (reverse (file-seq f))] (.delete x)))
(defn- artifact! [root path graph refs]
  (let [dir (io/file root path)]
    (.mkdirs dir)
    (spit (io/file dir "manifest.edn") (pr-str {:provenance {:workspace "ws" :graph graph :version 1}}))
    (spit (io/file dir "content.edn") (pr-str {:objects {} :references refs}))
    (spit (io/file dir "report.edn") "{}")
    dir))

(deftest deletes-owned-shelf-and-staging-copies-only
  (let [root (temp-dir)]
    (try
      (let [owned (mapv #(artifact! root % "target" [])
                        ["studio/ws/target/1" "runtime/ws/target/1" "staging/digest"])
            other (artifact! root "runtime/ws/other/1" "other" [])
            evidence (io/file root "runtime/events.edn")]
        (spit evidence "{:audit true}")
        (is (= {:artifact-copies-deleted 3} (cleanup/apply! (cleanup/plan! [root] "ws/target"))))
        (is (every? #(not (.exists %)) owned))
        (is (.exists (io/file other "content.edn")))
        (is (.exists evidence))
        (is (= {:artifact-copies-deleted 0} (cleanup/apply! (cleanup/plan! [root] "ws/target")))))
      (finally (remove-tree root)))))

(deftest referenced-artifacts-and-unreadable-references-block-before-deletion
  (doseq [content [(pr-str {:references [{:workspace "ws" :graph "target" :version 1}]}) "broken["]]
    (let [root (temp-dir)]
      (try
        (let [owned (artifact! root "ws/target/1" "target" [])
              other (artifact! root "ws/other/1" "other" [])]
          (spit (io/file other "content.edn") content)
          (is (thrown? clojure.lang.ExceptionInfo (cleanup/plan! [root] "ws/target")))
          (is (.exists (io/file owned "content.edn"))))
        (finally (remove-tree root))))))

(deftest a-failed-purge-can-be-retried-with-ownership-intact
  (let [root (temp-dir)]
    (try
      (let [owned (artifact! root "ws/target/1" "target" [])
            plan (cleanup/plan! [root] "ws/target")
            real-delete cleanup/delete-path!]
        (with-redefs [cleanup/delete-path! (fn [p]
                                            (if (= "report.edn" (str (.getFileName p)))
                                              (throw (java.io.IOException. "injected"))
                                              (real-delete p)))]
          (is (thrown? clojure.lang.ExceptionInfo (cleanup/apply! plan))))
        (is (.exists (io/file owned "manifest.edn")))
        (is (= {:artifact-copies-deleted 1} (cleanup/apply! (cleanup/plan! [root] "ws/target"))))
        (is (not (.exists owned))))
      (finally (remove-tree root)))))

(deftest symlink-content-is-never-followed-or-deleted
  (let [root (temp-dir) outside (temp-dir)]
    (try
      (let [owned (artifact! root "ws/target/1" "target" [])
            secret (io/file outside "keep")]
        (spit secret "keep")
        (Files/createSymbolicLink (.toPath (io/file owned "link")) (.toPath outside) (make-array FileAttribute 0))
        (is (thrown? clojure.lang.ExceptionInfo (cleanup/plan! [root] "ws/target")))
        (is (= "keep" (slurp secret)))
        (.delete (io/file owned "link")))
      (finally (remove-tree root) (remove-tree outside)))))

(deftest nested-foreign-artifacts-block-directory-removal
  (let [root (temp-dir)]
    (try
      (artifact! root "parent" "target" [])
      (let [child (artifact! root "parent/child" "other" [])]
        (is (thrown? clojure.lang.ExceptionInfo (cleanup/plan! [root] "ws/target")))
        (is (.exists (io/file child "content.edn"))))
      (finally (remove-tree root)))))

(deftest missing-artifacts-do-not-block-deletion
  (let [root (temp-dir)]
    (try
      (is (= {:artifact-copies-deleted 0}
             (cleanup/apply! (cleanup/plan! [(io/file root "missing")] "ws/target"))))
      (let [owned (artifact! root "target" "target" [])
            other (artifact! root "other" "other" [])
            plan (cleanup/plan! [root] "ws/target")]
        (remove-tree owned)
        (.delete (io/file other "content.edn"))
        ;; A leftover manifest without content has no retained dependencies.
        (spit (io/file other "manifest.edn") "{}")
        (is (= {:artifact-copies-deleted 0}
               (cleanup/apply! (cleanup/plan! [root] "ws/target"))))
        (is (map? (cleanup/apply! plan))))
      (finally (remove-tree root)))))

(deftest semantic-ownership-and-dependencies
  (let [root (temp-dir)
        semantic! (fn [path graph id deps]
                    (let [dir (artifact! root path graph [])]
                      (spit (io/file dir "manifest.edn")
                            (pr-str {:artifact/format-version 2 :graph/id id
                                     :deployment {:workspace "ws" :graph graph :version 1}}))
                      (spit (io/file dir "content.edn")
                            (pr-str {:artifact/format-version 2
                                     :document {:graph {:graph/id id :graph/dependencies deps}}}))
                      dir))]
    (try
      (let [owned (semantic! "target" "target" "target-id" [])
            other (semantic! "other" "other" "other-id" [{:graph/id "target-id"}])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"references this Graph"
                             (cleanup/plan! [root] "ws/target" "target-id")))
        ;; Dependency protection also works after the target artifact was removed.
        (remove-tree owned)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"references this Graph"
                             (cleanup/plan! [root] "ws/target" "target-id")))
        (semantic! "other" "other" "other-id" [])
        (semantic! "target" "target" "target-id" [])
        (is (= {:artifact-copies-deleted 1}
               (cleanup/apply! (cleanup/plan! [root] "ws/target" "target-id"))))
        (is (.exists other)))
      (finally (remove-tree root)))))


(deftest encoded-semantic-artifact-does-not-block-an-unpublished-graph
  (let [root (temp-dir)]
    (try
      (let [document (-> (edn/read-string (slurp (io/file "../../modules/common/dialect/fixtures/semantic-v2.edn")))
                         (assoc-in [:publication :release/version] "1"))
            workspace (get-in document [:workspace :workspace/id])
            encoded (semantic/encode-candidate document {}
                      {"sql" #(string? (get-in % [:binding/implementation :query]))}
                      {:workspace workspace :graph "published" :version 1})
            dir (io/file root "published")]
        (is (seq (:files encoded)))
        (.mkdirs dir)
        (doseq [[name text] (:files encoded)] (spit (io/file dir name) text))
        (is (= {:artifact-copies-deleted 0}
               (cleanup/apply! (cleanup/plan! [root] "ws/test-copy" "copy-id"))))
        (is (= {:artifact-copies-deleted 1}
               (cleanup/apply! (cleanup/plan! [root] (str workspace "/published"))))))
      (finally (remove-tree root)))))

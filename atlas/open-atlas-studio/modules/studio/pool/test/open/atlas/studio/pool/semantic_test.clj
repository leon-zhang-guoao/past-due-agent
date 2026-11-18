(ns open.atlas.studio.pool.semantic-test
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [clojure.test :refer [deftest is]]
            [open.atlas.studio.pool :as pool]))

(def document
  {:contract {:contract/family "atlas-semantic" :contract/version "2.0.0"}
   :workspace {:workspace/id "w"}
   :graph {:graph/id "g" :graph/label "Graph" :graph/exposure :restricted
           :graph/entities [] :graph/resolvers [] :graph/operations [] :graph/dependencies [] :graph/terms []}
   :vocabulary {:entities {} :attributes {}}
   :execution {:resolvers {} :operations {} :sources {} :bindings {} :selection {}}
   :ownership {:entities {} :resolvers {} :operations {} :terms {}}
   :publication {:release/version "1.0.0" :source/revision 0
                 :required-capabilities #{:semantic/core-v1 :graph/single-owner-v1
                                          :deployment/source-slot-v1 :artifact/embedded-closure-v1}}})

(deftest semantic-drafts-persist-isolate-and-reject-stale-writes
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "atlas-semantic-pool" (make-array java.nio.file.attribute.FileAttribute 0)))
        cfg (pool/config {:backend :file :path (.getPath dir)})
        conn (pool/open-pool! cfg)]
    (try
      (let [save #(pool/save-semantic-draft! conn "w" "g" %1 %2 {} {:entrypoint :ui})]
        (is (= 1 (:revision (save 0 document))))
        (is (= :semantic.draft/revision-conflict (get-in (save 0 document) [:findings 0 :code])))
        (is (seq (:findings (save 1 (assoc-in document [:workspace :workspace/id] "other")))))
        (is (seq (:findings (save 1 (assoc document :unknown true)))))
        (is (nil? (pool/semantic-draft (pool/db conn) "other" "g")))
        (is (empty? (pool/objects (pool/db conn))))
        (is (= 2 (:revision (save 1 (assoc-in document [:graph :graph/label] "Changed")))))
        (is (= 2 (get-in (pool/semantic-draft (pool/db conn) "w" "g") [:document :publication :source/revision]))))
      (finally (pool/close-pool! conn)))
    (let [reopened (pool/open-pool! cfg)]
      (try
        (is (= "Changed" (get-in (pool/semantic-draft (pool/db reopened) "w" "g") [:document :graph :graph/label])))
        (finally (pool/close-pool! reopened) (pool/delete-pool! cfg))))))

(deftest simultaneous-saves-have-one-winner
  (let [cfg (pool/config {:backend :mem :id (str (java.util.UUID/randomUUID))}) conn (pool/open-pool! cfg)]
    (try
      (let [gate (promise)
            writes (repeatedly 2 #(future @gate (pool/save-semantic-draft! conn "w" "g" 0 document {} nil)))
            writes (vec writes)]
        (deliver gate true)
        (is (= 1 (count (filter :document (mapv deref writes)))))
        (is (= 1 (:revision (pool/semantic-draft (pool/db conn) "w" "g")))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

(deftest pool-backup-preserves-semantic-history-and-revision
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))})
        other-cfg (pool/config {:backend :mem :id (str (random-uuid))})
        conn (pool/open-pool! cfg) other (pool/open-pool! other-cfg)]
    (try
      (pool/save-semantic-draft! conn "w" "g" 0 document {} nil)
      (pool/save-semantic-draft! conn "w" "g" 1 (assoc-in document [:graph :graph/label] "Second") {} nil)
      (let [backup (pool/export-pool (pool/db conn))]
        (is (empty? (:findings (pool/import-pool! other (edn/read-string backup)))))
        (is (= backup (pool/export-pool (pool/db other))))
        (is (= 2 (:revision (pool/semantic-draft (pool/db other) "w" "g"))))
        (is (seq (:findings (pool/import-pool! other (edn/read-string backup)))))
        (is (= 2 (:revision (pool/semantic-draft (pool/db other) "w" "g")))) )
      (finally (pool/close-pool! conn) (pool/close-pool! other)
               (pool/delete-pool! cfg) (pool/delete-pool! other-cfg)))))

(deftest workspace-rename-retains-history-and-creates-a-new-revision
  (let [cfg (pool/config {:backend :mem :id (str (random-uuid))}) conn (pool/open-pool! cfg)]
    (try
      (pool/save-semantic-draft! conn "w" "g" 0 document {} nil)
      (let [original (get-in (edn/read-string (pool/export-pool (pool/db conn))) [:pool/semantic :history 0 :semantic.history/document])]
        (d/transact conn (pool/semantic-workspace-rename-tx (pool/db conn) "w" "renamed"))
        (is (nil? (pool/semantic-draft (pool/db conn) "w" "g")))
        (is (= 2 (:revision (pool/semantic-draft (pool/db conn) "renamed" "g"))))
        (is (= "renamed" (get-in (pool/semantic-draft (pool/db conn) "renamed" "g") [:document :workspace :workspace/id])))
        (is (= original (get-in (edn/read-string (pool/export-pool (pool/db conn))) [:pool/semantic :history 0 :semantic.history/document]))))
      (finally (pool/close-pool! conn) (pool/delete-pool! cfg)))))

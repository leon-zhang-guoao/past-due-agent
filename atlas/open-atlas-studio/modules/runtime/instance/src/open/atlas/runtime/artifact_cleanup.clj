(ns open.atlas.runtime.artifact-cleanup
  "Removal of a Graph's local artifact copies. Plan before stopping service;
   keep the registry on any failure so the same deletion can be retried."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption Path FileVisitOption]))

(defn- refuse! [message data]
  (throw (ex-info message (assoc data :code :graph-artifact-cleanup-failed))))

(defn- paths [root]
  (let [p (.toPath (io/file root))]
    (if (Files/exists p (into-array LinkOption []))
      (with-open [walk (Files/walk (.toRealPath p (into-array LinkOption []))
                                  (into-array FileVisitOption []))]
        (let [xs (vec (iterator-seq (.iterator walk)))]
          (when (some #(Files/isSymbolicLink %) xs)
            (refuse! "Artifact cleanup refuses symbolic links" {}))
          xs))
      [])))

(defn- read-map! [^Path path]
  (try
    (let [value (edn/read-string (slurp (.toFile path)))]
      (when-not (map? value) (throw (ex-info "Expected a map" {})))
      value)
    (catch Exception _
      (refuse! "An artifact cannot be inspected; deletion was not started"
               {:file (str path)}))))

(defn- qualified [{:keys [workspace graph]}]
  (when (and (string? workspace) (string? graph)) (str workspace "/" graph)))

(defn- owner [manifest]
  (qualified (if (= 2 (:artifact/format-version manifest))
               (:deployment manifest)
               (:provenance manifest))))

(defn- missing? [^Path path]
  (Files/notExists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn plan!
  "Inspect all local retained and staged artifacts. References from any other
   Graph block deletion; references between the target's own versions do not."
  ([roots graph] (plan! roots graph nil))
  ([roots graph graph-id]
   (let [all (distinct (mapcat paths (remove nil? roots)))
         manifests (filter #(= "manifest.edn" (str (.getFileName ^Path %))) all)
         rows (mapv (fn [^Path manifest]
                      (let [data (read-map! manifest)
                            owner (owner data)]
                        (when (and (not owner)
                                   (not (missing? (.resolve (.getParent manifest) "content.edn"))))
                          (refuse! "Artifact ownership is missing; deletion was not started"
                                   {:file (str manifest)}))
                        {:owner owner :graph-id (:graph/id data) :dir (.getParent manifest)})) manifests)
         known (set (map :dir rows))
         target-ids (cond-> (set (keep :graph-id (filter #(= graph (:owner %)) rows)))
                      graph-id (conj graph-id))]
     ;; Do not infer that an uninspectable retained artifact has no dependencies.
     (doseq [^Path p all
             :when (= "content.edn" (str (.getFileName p)))]
       (when-not (contains? known (.getParent p))
         (refuse! "An artifact manifest is missing; deletion was not started" {:file (str p)})))
     (doseq [{:keys [owner] :as row} rows
             :when (and (not= owner graph)
                        (not (missing? (.resolve ^Path (:dir row) "content.edn"))))]
       (let [content (read-map! (.resolve ^Path (:dir row) "content.edn"))
             semantic? (= 2 (:artifact/format-version content))
             refs (if semantic?
                    (get-in content [:document :graph :graph/dependencies])
                    (:references content))]
         (when-not (and (sequential? refs) (every? #(if semantic? (string? (:graph/id %)) (some? (qualified %))) refs))
           (refuse! "Artifact references cannot be inspected; deletion was not started"
                    {:graph owner}))
         (when (some #(if semantic? (contains? target-ids (:graph/id %)) (= graph (qualified %))) refs)
           (throw (ex-info "Another retained Graph artifact references this Graph"
                           {:code :graph-artifact-in-use :graph graph :referenced-by owner})))))
     (let [owned (mapv :dir (filter #(= graph (:owner %)) rows))]
       (doseq [^Path candidate owned
               {other :dir} rows
               :when (and (not= candidate other) (.startsWith ^Path other candidate))]
         (refuse! "Nested artifact directories cannot be safely removed" {:graph graph}))
       {:graph graph :directories owned}))))

(defn- version-of
  "The graph version a manifest was cut as, or nil when it does not say."
  [manifest]
  (if (= 2 (:artifact/format-version manifest))
    (or (get-in manifest [:deployment :version])
        (some-> (:release/version manifest) str parse-long))
    (let [v (get-in manifest [:provenance :version])]
      (if (string? v) (parse-long v) v))))

(defn plan-version!
  "Inspect all local retained and staged artifacts for reclaiming ONE version
   of a Graph (R3.5 criterion 1). Any other retained copy whose references pin
   this exact version blocks — reported by name, not thrown, so a caller can
   show why. Uninspectable copies refuse, as for whole-Graph deletion: an
   artifact we cannot read might be the one that references this version.
   → `{:graph … :version n :directories [Path…] :referenced-by [{:graph … :version n}…]}`"
  [roots graph graph-id version]
  (let [all (distinct (mapcat paths (remove nil? roots)))
        manifests (filter #(= "manifest.edn" (str (.getFileName ^Path %))) all)
        rows (mapv (fn [^Path manifest]
                     (let [data (read-map! manifest)
                           owner (owner data)]
                       (when (and (not owner)
                                  (not (missing? (.resolve (.getParent manifest) "content.edn"))))
                         (refuse! "Artifact ownership is missing; reclaim was not started"
                                  {:file (str manifest)}))
                       {:owner owner :graph-id (:graph/id data) :version (version-of data)
                        :dir (.getParent manifest)}))
                   manifests)
        known (set (map :dir rows))
        target? #(and (= graph (:owner %)) (= version (:version %)))
        target-ids (cond-> (set (keep :graph-id (filter #(= graph (:owner %)) rows)))
                     graph-id (conj graph-id))]
    (doseq [^Path p all
            :when (= "content.edn" (str (.getFileName p)))]
      (when-not (contains? known (.getParent p))
        (refuse! "An artifact manifest is missing; reclaim was not started" {:file (str p)})))
    (let [referencing
          (for [row rows
                :when (and (not (target? row))
                           (not (missing? (.resolve ^Path (:dir row) "content.edn"))))
                :let [content (read-map! (.resolve ^Path (:dir row) "content.edn"))
                      semantic? (= 2 (:artifact/format-version content))
                      refs (if semantic?
                             (get-in content [:document :graph :graph/dependencies])
                             (:references content))
                      _ (when-not (sequential? refs)
                          (refuse! "Artifact references cannot be inspected; reclaim was not started"
                                   {:graph (:owner row)}))]
                :when (some (fn [r]
                              (if semantic?
                                (and (contains? target-ids (:graph/id r))
                                     (= (str version) (str (:release/version r))))
                                (and (= graph (qualified r)) (= version (:version r)))))
                            refs)]
            {:graph (:owner row) :version (:version row)})
          owned (mapv :dir (filter target? rows))]
      (doseq [^Path candidate owned
              {other :dir} rows
              :when (and (not= candidate other) (.startsWith ^Path other candidate))]
        (refuse! "Nested artifact directories cannot be safely removed" {:graph graph}))
      {:graph graph :version version :directories owned
       :referenced-by (vec (sort-by (juxt :graph :version) (distinct referencing)))})))

(defn delete-path! [^Path path] (Files/deleteIfExists path))

(defn apply!
  "Remove precisely the inspected copies. Keep manifest last: a failed cleanup
   retains ownership evidence and is retryable even if content was removed."
  [{:keys [graph directories]}]
  (try
    (doseq [^Path dir directories :when (not (missing? dir))]
      (when (or (Files/isSymbolicLink dir)
                (not= dir (.toRealPath dir (into-array LinkOption []))))
        (refuse! "Artifact path changed during deletion" {:graph graph}))
      (let [manifest (.resolve dir "manifest.edn")
            _ (when-not (= graph (owner (read-map! manifest)))
                (refuse! "Artifact ownership changed during deletion" {:graph graph}))
            children (paths (.toFile dir))]
        (doseq [^Path p (sort-by #(.getNameCount ^Path %) >
                                (remove #{dir manifest} children))]
          (delete-path! p))
        (delete-path! manifest)
        (delete-path! dir)))
    {:artifact-copies-deleted (count directories)}
    (catch Exception e
      (throw (ex-info "Artifact cleanup failed; Graph registration was retained. Retry deletion."
                      {:code :graph-artifact-cleanup-failed :graph graph} e)))))

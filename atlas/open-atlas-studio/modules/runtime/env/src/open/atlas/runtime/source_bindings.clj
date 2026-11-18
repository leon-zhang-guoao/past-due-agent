(ns open.atlas.runtime.source-bindings
  "Instance-local connection configuration, addressed by workspace, graph UUID
   and logical Source ID. Never reads an authoring repository."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn resolve-entry [bindings id source]
  (if-not (:semantic/workspace source)
    source
    (let [versioned [(:semantic/workspace source) (:semantic/graph source)
                     (:semantic/release source) id]
          legacy [(:semantic/workspace source) (:semantic/graph source) id]
          entry (or (when (:semantic/release source) (get bindings versioned))
                    (get bindings legacy))]
      (when-not (and (map? entry) (= (:source/kind source) (:type entry)))
        (throw (ex-info "Deployment Source binding unavailable"
                        {:code :semantic.runtime/source-binding-unavailable
                         :source id :release (:semantic/release source)})))
      entry)))

(defn resolve-package-bindings
  "Resolve portable Graph Source slots through an operator-owned Source
   registry. The package carries `[graph-id slot-id]` requirements only; the
   deployment file maps those slots to registered Source IDs, so several Graphs
   may intentionally share one actual Source without copying its credentials.

   Registry shape:
   `{:sources {\"warehouse\" {:type \"sql\" ...}}
     :bindings {[\"orders\" \"primary\"] \"warehouse\"}}`."
  [{:keys [sources bindings] :as registry} requirements]
  (when-not (and (= #{:sources :bindings} (set (keys registry)))
                 (map? sources) (map? bindings))
    (throw (ex-info "Invalid deployment Source registry"
                    {:code :semantic.runtime/source-registry-invalid})))
  (into {}
        (for [{:keys [graph-id slot-id source]} requirements
              :let [slot [graph-id slot-id]
                    source-id (get bindings slot)
                    entry (get sources source-id)]]
          (do
            (when-not (and (string? source-id) (map? entry)
                           (= (:source/kind source) (:type entry)))
              (throw (ex-info "Deployment Source binding unavailable"
                              {:code :semantic.runtime/source-binding-unavailable
                               :slot slot})))
            [slot entry]))))

(defn file-resolver
  "Resolve semantic Source references from an operator-owned EDN map. The
   credential resolver is injected by the host and runs after scoped lookup.
   Legacy embedded configurations continue through that same resolver."
  [path resolve-credentials]
  (fn [id source]
    (let [bindings (when (:semantic/workspace source)
                     (let [file (io/file path)]
                       (when-not (and (.isFile file) (<= (.length file) 1048576))
                         (throw (ex-info "Deployment Source bindings unavailable" {:code :semantic.runtime/source-binding-unavailable})))
                       (with-open [reader (java.io.PushbackReader. (io/reader file))]
                         (let [data (edn/read {:eof nil} reader)]
                           (when-not (and (map? data) (= ::end (edn/read {:eof ::end} reader)))
                             (throw (ex-info "Invalid deployment Source bindings" {:code :semantic.runtime/source-binding-invalid})))
                           data))))
          entry (resolve-entry bindings id source)]
      (resolve-credentials id entry))))

(defn install-missing!
  "Install previously absent scoped bindings. Existing different configuration
   is an operator decision and is never overwritten by publication. New writes
   use `[workspace graph release source]`; legacy three-part keys remain readable."
  [path entries]
  (let [file (io/file path)
        existing (if (.isFile file)
                   (do (when (> (.length file) 1048576) (throw (ex-info "Bindings too large" {})))
                       (edn/read-string (slurp file))) {})]
    (let [conflicts (vec (keep (fn [[k v]]
                                 (when (and (contains? existing k) (not= v (get existing k))) k))
                               entries))]
      (when-not (and (map? existing) (map? entries)
                     (every? (fn [[k v]]
                               (and (vector? k) (contains? #{3 4} (count k))
                                    (every? string? k) (map? v))) entries)
                     (empty? conflicts))
        (throw (ex-info
                (if-let [slot (first conflicts)]
                  (str "Deployment Source binding already differs for Source " (peek slot)
                       (when (= 4 (count slot)) (str " in release " (nth slot 2))) ".")
                  "Invalid deployment Source binding entries")
                {:code :semantic.runtime/source-binding-conflict
                 :conflicting-slots conflicts}))))
    (io/make-parents file)
    (let [tmp (java.nio.file.Files/createTempFile (.toPath (.getParentFile (.getAbsoluteFile file))) ".source-bindings-" ".edn"
                                                (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile tmp) (pr-str (merge existing entries)))
        (java.nio.file.Files/move tmp (.toPath file)
                                 (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                                      java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        (finally (java.nio.file.Files/deleteIfExists tmp))))))

(defn- read-bindings [path]
  (let [file (io/file path)]
    (if (.isFile file)
      (do (when (> (.length file) 1048576)
            (throw (ex-info "Bindings too large" {:code :semantic.runtime/source-binding-invalid})))
          (let [data (edn/read-string (slurp file))]
            (when-not (map? data)
              (throw (ex-info "Invalid deployment Source bindings"
                              {:code :semantic.runtime/source-binding-invalid})))
            data))
      {})))

(defn source-slots
  "Every binding key `[workspace graph (release) source]` filed for one Source
   of one workspace. Keys only — the entries are connection configuration."
  [path workspace source-id]
  (vec (sort-by pr-str
                (filter (fn [k] (and (vector? k) (= workspace (first k)) (= source-id (peek k))))
                        (keys (read-bindings path))))))

(defn remove-slots!
  "Drop the named binding keys (owner 2026-10-04: removing a declared Source
   takes its bindings with it). Atomic replace, like `install-missing!`;
   a key not present is ignored. -> the keys actually removed."
  [path slots]
  (let [file (io/file path)
        existing (read-bindings path)
        gone (vec (filter #(contains? existing %) slots))]
    (when (seq gone)
      (let [tmp (java.nio.file.Files/createTempFile (.toPath (.getParentFile (.getAbsoluteFile file))) ".source-bindings-" ".edn"
                                                    (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (spit (.toFile tmp) (pr-str (apply dissoc existing gone)))
          (java.nio.file.Files/move tmp (.toPath file)
                                    (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                                         java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (finally (java.nio.file.Files/deleteIfExists tmp)))))
    gone))

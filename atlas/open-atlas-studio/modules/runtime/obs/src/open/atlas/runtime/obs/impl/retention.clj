(ns open.atlas.runtime.obs.impl.retention
  "Application opt-in retention for diagnostics only; never scans business data."
  (:require [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.util Timer TimerTask UUID]
           [java.util.zip GZIPOutputStream GZIPInputStream]
           [java.nio.file Files]))

(def lock (Object.))
(defonce roots (atom {}))
(defonce timer (Timer. "atlas-log-retention" true))
(defn setting [name fallback]
  (try (let [n (Long/parseLong (or (System/getenv name) ""))] (if (pos? n) n fallback))
       (catch Exception _ fallback)))
(defn policy [] {:days (setting "OPEN_ATLAS_LOG_RETENTION_DAYS" 30)
                :file-bytes (setting "OPEN_ATLAS_LOG_MAX_FILE_BYTES" (* 50 1024 1024))
                :total-bytes (setting "OPEN_ATLAS_LOG_MAX_TOTAL_BYTES" (* 2 1024 1024 1024))})
(defn regular? [f] (and (not (Files/isSymbolicLink (.toPath (io/file f)))) (.isFile (io/file f))))
(defn tree [f]
  (let [f (io/file f)]
    (when-not (Files/isSymbolicLink (.toPath f))
      (if (.isDirectory f) (mapcat tree (or (.listFiles f) [])) (when (.isFile f) [f])))))
(defn day [ms] (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli ms) java.time.ZoneOffset/UTC)))
(defn archive! [file now]
  (let [f (io/file file)]
    (when (and (regular? f) (pos? (.length f)))
      (let [target (io/file (str f "." now "-" (UUID/randomUUID) ".gz"))
            tmp (io/file (str target ".tmp")) last-write (.lastModified f)]
        (try
          (with-open [in (io/input-stream f) out (GZIPOutputStream. (io/output-stream tmp))]
            (io/copy in out))
          (Files/move (.toPath tmp) (.toPath target) (make-array java.nio.file.CopyOption 0))
          (.setLastModified target last-write)
          (spit f "")
          (.setLastModified f now)
          (finally (.delete tmp)))))))
(defn for-path [path]
  (let [p (.getCanonicalPath (io/file path))]
    (some (fn [[home cfg]] (when (str/starts-with? p (str home java.io.File/separator)) cfg)) @roots)))
(defn rotate! [file cfg now]
  (let [f (io/file file)]
    (when (and (regular? f) (pos? (.length f))
               (or (>= (.length f) (:file-bytes cfg)) (not= (day now) (day (.lastModified f)))))
      (archive! f now))))
(defn retained-files [path]
  (let [f (io/file path) prefix (str (.getName f) ".")]
    (sort-by #(.lastModified %) (filter #(and (regular? %) (or (= (.getName %) (.getName f))
                         (and (str/starts-with? (.getName %) prefix)
                              (or (str/ends-with? (.getName %) ".gz")
                                  (re-matches #".*\.\d+" (.getName %))))))
                   (or (.listFiles (.getParentFile f)) [])))))
(defn reader [file]
  (io/reader (if (str/ends-with? (str file) ".gz")
               (GZIPInputStream. (io/input-stream file)) (io/input-stream file))))
(defn reduce-lines [path f initial]
  (reduce (fn [result file]
            (try (with-open [r (reader file)] (reduce f result (line-seq r)))
                 (catch java.io.FileNotFoundException _ result))) initial (retained-files path)))
(defn maintain! [home cfg now]
  (locking lock
    (let [logs (concat (tree (io/file home "logs")) (concat (tree (io/file home "obs" "logs")) (tree (io/file home "runtime" "obs" "logs"))))
          active? #(boolean (re-matches #".*\.(jsonl|ndjson|log)" (.getName %)))
          ;; Process logs have a separate stream writer; never truncate its open file here.
          _ (doseq [f logs :when (re-matches #".*\.(jsonl|ndjson)" (.getName f))] (rotate! f cfg now))
          files (vec (concat (filter #(re-matches #".*\.(jsonl|ndjson|log)(\.\d+|\.[\w-]+\.gz)?" (.getName %))
                                        (concat (tree (io/file home "logs")) (concat (tree (io/file home "obs" "logs")) (tree (io/file home "runtime" "obs" "logs")))))
                             (filter #(str/ends-with? (.getName %) ".edn") (concat (tree (io/file home "obs" "audit-index")) (tree (io/file home "runtime" "obs" "audit-index"))))))
          eligible? #(and (not (active? %)) (not (str/ends-with? (.getName %) ".tmp")))
          cutoff (- now (* (:days cfg) 86400000))]
      (doseq [f files :when (and (eligible? f) (< (.lastModified f) cutoff))] (.delete f))
      (loop [total (reduce + 0 (map #(.length %) files))
             candidates (sort-by #(.lastModified %) (filter #(and (.exists %) (eligible? %)) files))
             removed 0]
        (if (and (> total (:total-bytes cfg)) (seq candidates))
          (let [f (first candidates) size (.length f)]
            (if (.delete f) (recur (- total size) (rest candidates) (inc removed))
                (recur total (rest candidates) removed)))
          (when (pos? removed)
            (binding [*out* *err*] (println "Log retention: capacity limit removed" removed "oldest log/index files before configured expiry."))))))))
(defn configure! [home]
  (let [home (.getCanonicalPath (io/file home)) cfg (policy)]
    (when-not (contains? @roots home)
      (swap! roots assoc home cfg)
      (try (maintain! home cfg (System/currentTimeMillis))
           (catch Exception _ (binding [*out* *err*] (println "Log retention: startup cleanup failed"))))
      (.schedule timer (proxy [TimerTask] []
                         (run [] (try (maintain! home cfg (System/currentTimeMillis)) (catch Exception _ nil))))
                 60000 60000))
    cfg))

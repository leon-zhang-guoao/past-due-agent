(ns open.atlas.studio.pool.impl.maintenance
  "Verified copy collection for the application-owned maintenance window.
   The standalone recovery script keeps its own copy of these primitives so it
   can run against older installed Studio jars. Both paths have real-store tests."
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [konserve.core :as k] [taoensso.timbre :as log])
  (:import [java.nio.file Files LinkOption CopyOption StandardCopyOption]
           [java.security MessageDigest] [java.util Date Properties UUID]))

(def no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defn fail! [message] (throw (ex-info message {})))
(defn path [f] (.toPath (io/file f)))
(defn exists? [f] (Files/exists (path f) no-links))
(defn canonical [f] (.getCanonicalFile (io/file f)))
(defn inventory [root]
  (let [root (canonical root)]
    (into (sorted-map)
          (for [f (file-seq root)]
            (do
              (when (Files/isSymbolicLink (path f)) (fail! "Symlinks in pool are not supported; inspect before maintenance."))
              [(str (.relativize (path root) (path f)))
               [(if (.isDirectory f) :dir :file) (.length f) (.lastModified f)]])))))
(defn size-of [root]
  (reduce (fn [out f] (if (.isFile f) (-> out (update :files inc) (update :bytes + (.length f))) out))
          {:files 0 :bytes 0} (file-seq (io/file root))))
(defn copy-tree! [source target]
  (doseq [f (file-seq (io/file source))]
    (let [dest (.resolve (path target) (.relativize (path source) (path f)))]
      (when (Files/isSymbolicLink (path f)) (fail! "Refusing to copy a symlink."))
      (if (.isDirectory f)
        (Files/createDirectories dest (make-array java.nio.file.attribute.FileAttribute 0))
        (Files/copy (path f) dest (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES]))))))
(defn atomic-move! [from to]
  (when (exists? to) (fail! "Refusing to replace an existing directory."))
  (Files/move (path from) (path to) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))
(defn connect-copy! [pool]
  ;; Inspect the stored configuration through Datahike's own consistency check.
  ;; Preserve its history/index settings; only the copied store location changes.
  (let [store {:backend :file :path (str pool)}]
    (try
      (log/with-min-level :fatal (d/connect {:store store}))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type stored-config]} (ex-data e)]
          (if (and (= :config-does-not-match-stored-db type)
                   (= :file (first (:store stored-config))))
            (d/connect (assoc stored-config :store store :allow-unsafe-config true))
            (throw e)))))))
(defn tree-digest [root]
  (let [md (MessageDigest/getInstance "SHA-256") buffer (byte-array 1048576)]
    (doseq [f (sort-by str (filter #(.isFile %) (file-seq (io/file root))))]
      (.update md (.getBytes (pr-str [(str (.relativize (path root) (path f))) (.length f)]) "UTF-8"))
      (with-open [in (io/input-stream f)]
        (loop [] (let [n (.read in buffer)] (when (pos? n) (.update md buffer 0 n) (recur))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest md)))))
(defn delete-owned-tree! [run child]
  (let [child (canonical child)]
    (when-not (= (canonical run) (.getParentFile child)) (fail! "Refusing cleanup outside this run directory."))
    (inventory child)
    (doseq [f (reverse (file-seq child))] (Files/delete (path f)))))
(defn datom-fingerprint [db]
  (let [md (MessageDigest/getInstance "SHA-256")
        counts (reduce (fn [counts x]
                         (.update md (.getBytes (pr-str [(:e x) (:a x) (:v x) (:tx x) (:added x)]) "UTF-8"))
                         (.update md (byte 10))
                         (update counts (:a x) (fnil inc 0)))
                       (sorted-map) (d/datoms db :eavt))]
    {:datoms (reduce + (vals counts)) :attribute-counts counts
     :sha256 (format "%064x" (java.math.BigInteger. 1 (.digest md)))}))
(defn fingerprint [db]
  (cond-> (assoc (datom-fingerprint db)
                 :max-eid (:max-eid db) :max-tx (:max-tx db)
                 :database-settings (dissoc (:config db) :store :name :initial-tx :allow-unsafe-config))
    (get-in db [:config :keep-history?])
    (assoc :temporal-history (datom-fingerprint (d/history db)))))
(defn validate-layout! [pool]
  (doseq [f (.listFiles (io/file pool))]
    (when-not (or (and (.isFile f) (re-matches #"[0-9a-fA-F-]{36}\.ksv" (.getName f)))
                  ;; Directories are preserved in the backup, outside the candidate key store.
                  (and (.isDirectory f) (not (contains? #{"data" "meta"} (.getName f)))))
      (fail! (str "Unexpected entry in database directory: " (.getName f) ". No files were removed.")))))
(defn check-version! []
  (doseq [[resource expected] [["META-INF/maven/io.replikativ/datahike/pom.properties" "0.6.1596"]
                                ["META-INF/maven/io.replikativ/konserve/pom.properties" "0.7.319"]]]
    (let [props (Properties.) url (io/resource resource)]
      (when-not url (fail! "Cannot verify installed storage library version."))
      (with-open [in (io/input-stream url)] (.load props in))
      (when-not (= expected (.getProperty props "version"))
        (fail! (str "Unsupported storage library version; this procedure was validated for " expected "."))))))
(defn collect-copy! [candidate]
  (let [conn (connect-copy! candidate)]
    (try
      (let [db @conn branches (k/get (:store db) :branches nil {:sync? true})]
        (when-not (= #{:db} (set branches)) (fail! "This procedure supports only the single default database branch."))
        (let [before (fingerprint db)
              deleted @(d/gc-storage conn (Date.))]
          (when (instance? Throwable deleted) (throw deleted))
          {:fingerprint before :deleted-blobs (count deleted)}))
      (finally (d/release conn true)))))
(defn verify-copy! [candidate expected]
  (let [conn (connect-copy! candidate)]
    (try
      (when-not (= expected (fingerprint @conn)) (fail! "Verification failed: database content or settings changed; swap will not be accepted."))
      (finally (d/release conn true)))))
(defn write-report! [run report]
  (let [temp (io/file run "report.edn.tmp") target (io/file run "report.edn")]
    (spit temp (str (pr-str report) "\n"))
    (Files/move (path temp) (path target)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn private-directory! [dir]
  (Files/createDirectory (path dir)
                         (into-array java.nio.file.attribute.FileAttribute
                                     [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                                       (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))])))

(defn open-verified! [pool expected]
  (let [conn (connect-copy! pool)]
    (try
      (when-not (= expected (fingerprint @conn))
        (fail! "Reopened pool differs from the verified original."))
      conn
      (catch Throwable e (d/release conn true) (throw e)))))

(defn prune-backups!
  "Retire only our completed automatic runs, retaining the newest N. Never
   remove manual backups, failed runs or a backup containing unexpected folders."
  [home retain]
  (let [runs (->> (.listFiles (io/file home))
                  (filter #(and (.isDirectory %)
                                (str/starts-with? (.getName %) "pool-maintenance-auto-")
                                (not (Files/isSymbolicLink (path %)))))
                  (keep (fn [dir]
                          (try
                            (let [r (edn/read-string (slurp (io/file dir "report.edn")))]
                              (when (and (= :studio.pool/automatic (:kind r))
                                         (= :applied (:status r))
                                         (= (str (canonical home)) (:home r))
                                         (empty? (:preserved-extra-directories r)))
                                [dir (:started-ms r)]))
                            (catch Exception _ nil))))
                  (sort-by second >))]
    (doseq [[dir _] (drop retain runs)] (delete-owned-tree! home dir))))

(defn compact!
  "Caller must exclude and drain ALL pool users for the complete call.
   Publishes a freshly reopened connection before returning, including when a
   failed swap restores the original. A fatal recovery leaves the lock in place
   and throws :pool/maintenance-recovery-required; caller must fail closed."
  [conn {:keys [publish-connection! retain-backups] :or {retain-backups 1}}]
  (check-version!)
  (let [cfg (:config @conn) pool (io/file (get-in cfg [:store :path]))
        home (.getParentFile (canonical pool))
        lock (io/file home (str (.getName pool) ".maintenance.lock"))
        fatal? (atom false)]
    (when-not (and (= :file (get-in cfg [:store :backend])) publish-connection!)
      (fail! "Automatic maintenance requires a file pool and connection publisher."))
    (when (Files/isSymbolicLink (path pool)) (fail! "Pool symlinks are not supported."))
    (validate-layout! pool)
    (inventory pool)
    (private-directory! lock)
    (spit (io/file lock "owner.edn") (pr-str {:kind :studio.pool/automatic :started-at (str (Date.))}))
    (try
      (let [before (size-of pool) source-inventory (inventory pool)
            expected (fingerprint @conn)
            needed (+ (* 2 (:bytes before)) (* 64 1024 1024))]
        (when (< (.getUsableSpace home) needed) (fail! "Insufficient disk space for pool maintenance backup and candidate."))
        (let [started (System/currentTimeMillis)
              run (io/file home (str "pool-maintenance-auto-" started "-" (UUID/randomUUID)))
              original (io/file run "original") candidate (io/file run "candidate")
              previous (io/file run "previous-live")
              base {:kind :studio.pool/automatic :started-ms started :home (str home)
                    :run (str run) :before before :before-fingerprint expected
                    :preserved-extra-directories (mapv #(.getName %) (filter #(.isDirectory %) (.listFiles pool)))}]
          (private-directory! run)
          (write-report! run (assoc base :status :copying))
          (copy-tree! pool original)
          (let [backup-hash (tree-digest original)
                report (assoc base :backup-sha256 backup-hash)]
            (when-not (and (= source-inventory (inventory pool)) (= backup-hash (tree-digest pool)))
              (fail! "Pool changed during maintenance backup; no collection applied."))
            (write-report! run (assoc report :status :backup-verified))
            (private-directory! candidate)
            (doseq [f (.listFiles original) :when (.isFile f)]
              (Files/copy (path f) (.resolve (path candidate) (.getName f))
                          (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES])))
            (let [{:keys [fingerprint deleted-blobs]} (collect-copy! candidate)
                  report (assoc report :after (size-of candidate) :deleted-blobs deleted-blobs
                                :records-identical true)]
              (when-not (= expected fingerprint) (fail! "Backup records differ from the active connection."))
              (verify-copy! candidate expected)
              (when-not (= source-inventory (inventory pool)) (fail! "Pool changed before replacement; original is untouched."))
              (write-report! run (assoc report :status :swapping))
              ;; Every foreground operation has completed before this point.
              ;; Release removes Datahike's cached connection for this path.
              (try
                (d/release conn true)
                (atomic-move! pool previous)
                (atomic-move! candidate pool)
                (let [reopened (open-verified! pool expected)]
                  (try (publish-connection! reopened)
                       (catch Throwable e (d/release reopened true) (throw e))))
                (catch Throwable cause
                  (try
                    (when (exists? previous)
                      (when (exists? pool) (atomic-move! pool (io/file run "failed-candidate")))
                      (atomic-move! previous pool))
                    (publish-connection! (open-verified! pool expected))
                    (write-report! run (assoc report :status :failed-restored))
                    (catch Throwable recovery
                      (reset! fatal? true)
                      (throw (ex-info "Pool recovery needs operator attention; requests remain blocked."
                                      {:type :pool/maintenance-recovery-required :run (str run)} recovery))))
                  (throw (ex-info "Pool maintenance failed; original database was restored."
                                  {:type :pool/maintenance-restored :run (str run)} cause))))
              (write-report! run (assoc report :status :applied))
              (try (delete-owned-tree! run previous)
                   (catch Exception e (log/warn "Previous pool copy retained after maintenance" (.getMessage e))))
              (try (prune-backups! home retain-backups)
                   (catch Exception e (log/warn "Pool backup retention did not complete" (.getMessage e))))
              (assoc report :status :applied)))))
      (finally
        (when-not @fatal?
          (Files/deleteIfExists (path (io/file lock "owner.edn")))
          (Files/deleteIfExists (path lock)))))))

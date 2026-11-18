(ns open.atlas.server.mcp.impl.results
  "Process-local, expiring result handles. Files are private temporary transport
   artifacts, never Sources or a business database. Only server-minted handles
   resolve paths. All access and eviction is serialized to avoid read/delete races."
  (:require [clojure.edn :as edn])
  (:import [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util UUID HexFormat]
           [java.util.concurrent Executors ThreadFactory TimeUnit]))

(defn- setting [key fallback]
  (let [v (if-let [s (System/getenv key)] (parse-long s) fallback)]
    (when-not (and (integer? v) (pos? v))
      (throw (ex-info "Result configuration must be a positive integer" {:setting key})))
    v))

(def inline-bytes (setting "ATLAS_MCP_INLINE_BYTES" 262144))
(def max-bytes (setting "ATLAS_MCP_RESULT_MAX_BYTES" (* 16 1024 1024)))
(def total-bytes (setting "ATLAS_MCP_RESULT_TOTAL_BYTES" (* 128 1024 1024)))
(def ttl-ms (setting "ATLAS_MCP_RESULT_TTL_MS" (* 15 60 1000)))
(def max-files (setting "ATLAS_MCP_RESULT_MAX_FILES" 128))

(defn now-ms [] (System/currentTimeMillis))
(defn- encode [v]
  (binding [*print-length* nil *print-level* nil *print-dup* false]
    (.getBytes (pr-str v) "UTF-8")))
(defn fingerprint [v]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (encode v))))
(defn large? [v] (> (alength ^bytes (encode v)) inline-bytes))

(defn create-store []
  ;; A PROCESS temp directory, deliberately (doc 108 §108.5 item 7, ruled NO in
  ;; §108.6): neither handles nor their materializations survive a restart. A
  ;; fresh store gets a fresh directory, so there is no path a later process
  ;; could reopen even by accident.
  {:directory (Files/createTempDirectory "atlas-mcp-results-" (make-array FileAttribute 0))
   :entries (atom {})
   ;; key → promise, for first-build coalescing. Held OUTSIDE the entries lock:
   ;; a build takes as long as a table load, and holding the store's lock for it
   ;; would stall every unrelated handle read.
   :builds (atom {})})

(defn- entry-bytes
  "A handle costs its result file plus every materialization built from it.
   Counting only the result file would let the store's declared budget be
   exceeded several times over by the caches it spawned."
  [entry]
  (+ (:bytes entry 0) (reduce + 0 (map :bytes (vals (:materializations entry))))))

(defn- delete-entry-files! [{:keys [path materializations]}]
  (Files/deleteIfExists ^Path path)
  (doseq [{:keys [path meta-path]} (vals materializations)]
    (when path (Files/deleteIfExists ^Path path))
    (when meta-path (Files/deleteIfExists ^Path meta-path))))

(defn sweep! [{:keys [entries]}]
  (locking entries
    (doseq [[id entry] @entries :when (<= (:expires-at entry) (now-ms))]
      ;; A materialization dies with its handle: same TTL, no sliding renewal
      ;; (§108.2 「缓存命中不续期」). Outliving the handle would be a cache
      ;; answering for a result nobody may read any more.
      (delete-entry-files! entry)
      (swap! entries dissoc id))))

(defn close! [{:keys [entries directory]}]
  (locking entries
    (doseq [entry (vals @entries)] (delete-entry-files! entry))
    (reset! entries {})
    (Files/deleteIfExists ^Path directory)))

(defonce ^:private default-store
  (delay
    (let [s (create-store)
          executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ r] (doto (Thread. r "atlas-result-expiry") (.setDaemon true)))))]
      (.scheduleWithFixedDelay executor
        ^Runnable (fn [] (try (sweep! s) (catch Exception _ nil))) 30 30 TimeUnit/SECONDS)
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. ^Runnable (fn [] (.shutdownNow executor) (try (close! s) (catch Exception _ nil)))))
      s)))
(def ^:dynamic *store* nil)
(defn- store [] (or *store* @default-store))

(defn retain! [owner response]
  (let [{:keys [entries directory] :as s} (store)
        bytes (encode response)
        size (alength ^bytes bytes)]
    (locking entries
      (sweep! s)
      (if (or (> size max-bytes) (>= (count @entries) max-files)
              (> (+ size (reduce + 0 (map entry-bytes (vals @entries)))) total-bytes))
        {:error "Result storage budget exceeded; no partial result was retained."}
        (try
          ;; Refuse unsupported EDN values before creating a published handle.
          (edn/read-string (String. ^bytes bytes "UTF-8"))
          (let [id (str "result_" (UUID/randomUUID))
                path (Files/createTempFile ^Path directory "result-" ".edn" (make-array FileAttribute 0))
                captured (now-ms)
                expires (+ captured ttl-ms)]
            (try
              (Files/write path bytes (make-array java.nio.file.OpenOption 0))
              (swap! entries assoc id {:path path :owner owner :bytes size :captured-at captured :expires-at expires})
              (merge (dissoc response :result)
                     {:result_handle id :storage :temporary-file :result-bytes size
                      :captured-at captured :expires-at expires :result-complete true
                      :source-completeness (if (:partial response) :partial :unknown)})
              (catch Exception e
                (Files/deleteIfExists path)
                (throw e))))
          (catch Exception _ {:error "Result could not be retained; no handle was created."}))))))

(defn- live-entry
  "The entry for this handle IF the caller owns it and it has not expired.
   Ownership is re-checked on every materialization read for the same reason
   `fetch!` re-checks it: a handle is not a bearer grant."
  [{:keys [entries] :as s} owner handle]
  (sweep! s)
  (let [entry (get @entries handle)]
    (when (and entry (= owner (:owner entry))) entry)))

(defn materialized
  "An existing materialization of `handle` under `key`, as
   `{:path Path :meta-path Path}`, or nil. Never renews the TTL: a cache hit is
   a read, not a reason for the result to live longer."
  [owner handle key]
  (let [{:keys [entries] :as s} (store)]
    (locking entries
      (when-let [entry (live-entry s owner handle)]
        (when-let [m (get (:materializations entry) key)]
          (when (Files/exists ^Path (:path m) (make-array java.nio.file.LinkOption 0))
            (select-keys m [:path :meta-path])))))))

(defn materialize!
  "Build the materialization for `key` once, coalescing concurrent first builds.

   `build!` is `(fn [^Path db-target ^Path meta-target] → anything)`; it writes
   to TEMP paths and this function renames them into place atomically, so a
   failed or cancelled build never leaves a readable half-file for the next
   caller to trust. `wait-ms` bounds how long a second caller waits for the
   first builder before giving up (the caller then falls back to the in-memory
   path and says so).

   Returns `{:path … :meta-path …}`, or `{:error … :code …}` — never a partial."
  [owner handle key wait-ms build!]
  (let [{:keys [entries builds directory] :as s} (store)
        mine (promise)
        builder? (locking builds
                   (if (contains? @builds key)
                     false
                     (do (swap! builds assoc key mine) true)))]
    (if-not builder?
      (let [waiting (locking builds (get @builds key))
            v (deref waiting (max 0 (long (or wait-ms 0))) ::timeout)]
        (if (= ::timeout v)
          {:error "Another caller is still materializing this result; this call used the in-memory path."
           :code :analysis.materialize/busy}
          v))
      (let [db (Files/createTempFile ^Path directory "mat-" ".duckdb.tmp" (make-array FileAttribute 0))
            meta (Files/createTempFile ^Path directory "mat-" ".edn.tmp" (make-array FileAttribute 0))
            result
            (try
              ;; DuckDB wants to CREATE the database file itself.
              (Files/deleteIfExists db)
              (build! db meta)
              (let [size (+ (Files/size db) (Files/size meta))
                    final-db (.resolveSibling db (str "mat-" (UUID/randomUUID) ".duckdb"))
                    final-meta (.resolveSibling meta (str "mat-" (UUID/randomUUID) ".edn"))]
                (locking entries
                  (let [entry (live-entry s owner handle)
                        others (reduce + 0 (map entry-bytes (vals (dissoc @entries handle))))
                        current (if entry (entry-bytes entry) 0)]
                    (cond
                      (nil? entry)
                      {:error "Result handle is unknown, expired, or inaccessible; execute the query again."
                       :code :analysis.materialize/gone}

                      (> (+ others current size) total-bytes)
                      {:error (str "Materializing this result would exceed the server's total result "
                                   "storage budget; this call used the in-memory path instead.")
                       :code :analysis.limit/total-bytes}

                      :else
                      (do (Files/move db final-db
                                      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                          (Files/move meta final-meta
                                      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
                          (swap! entries assoc-in [handle :materializations key]
                                 {:path final-db :meta-path final-meta :bytes size})
                          {:path final-db :meta-path final-meta})))))
              (catch Throwable t
                {:error (str "This result could not be materialized; the in-memory path was used. "
                             (.getMessage t))
                 :code :analysis.materialize/failed}))]
        ;; No half-file survives a refusal or a throw, on any path.
        (try (Files/deleteIfExists db) (catch Exception _ nil))
        (try (Files/deleteIfExists meta) (catch Exception _ nil))
        (locking builds (swap! builds dissoc key))
        (deliver mine result)
        result))))

(defn probe
  "`:ok` / `:scope` / `:missing` for one handle, and NOTHING else.

   A multi-handle analysis has to distinguish «this handle expired» from «this
   handle belongs to another scope» (doc 108 §108.5 item 2), and `fetch!` alone
   collapses both into one refusal. The answer is deliberately three keywords:
   it never returns another owner's handle, its contents, or its scope — only
   whether the handle the CALLER named is theirs and alive."
  [owner handle]
  (let [{:keys [entries] :as s} (store)]
    (locking entries
      (sweep! s)
      (let [entry (get @entries handle)]
        (cond (nil? entry) :missing
              (= owner (:owner entry)) :ok
              :else :scope)))))

(defn fetch! [owner handle]
  (let [{:keys [entries] :as s} (store)]
    (locking entries
      (sweep! s)
      (let [{:keys [path] :as entry} (get @entries handle)]
        (if (and entry (= owner (:owner entry)))
          (try
            (let [response (edn/read-string (Files/readString ^Path path))]
              (assoc response :captured-at (:captured-at entry) :expires-at (:expires-at entry)
                     :result-complete true :source-completeness (if (:partial response) :partial :unknown)))
            (catch Exception _ {:error "Retained result is unavailable; execute the query again."}))
          {:error "Result handle is unknown, expired, or inaccessible; execute the query again."})))))

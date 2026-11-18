(ns open.atlas.studio.platform-runtime
  "The Builder's own environment cache: a graph's DRAFT model (the scope
   projection, `builder-server/draft-model-for-root`) -> `common/engine`'s
   compiled env, cached per (workspace, graph, revision).

   ## What 8.4.18 裁定十一 changed here

   The model this compiles is no longer assembled by the Builder. It is the
   scope projection — the very function `studio/version` runs to CUT a version
   — so «the draft runs» and «the cut runs» became one judgement. The old
   `resolved-model` went with that assembly; what is left of it is
   `with-credentials`, the same one-line substitution `runtime/env` performs,
   over the same `resolve-source` this app hands `runtime/instance` at
   start-up.

   **And the compile itself is the runtime's** (8.4.18 裁定十二 ①). It used
   to call `engine/compile-env` here with its own credential substitution in
   front — the last two lines of `runtime/env`'s `impl.closure/compile-closure`
   copied out, because that module exported no door for a model that is on no
   shelf. It exports one now, `env/for-model`, and `for-request` finishes
   through it: a draft and a published artifact are compiled by literally the
   same builder, so neither the substitution nor the compile options can drift
   apart from each other.

   Before doc 89 this namespace held a **second** dialect->legacy translation
   (`config`) that fed the app's own pathom compiler — two places that had to
   agree on what dialect-v1 means, and the two disagreed at least twice (doc
   89 §8.9.2: `:batch?` deleted from the dialect, one translation followed,
   the other did not, and every generated child collection silently answered
   `[]`). Query Studio's 'builder · full model' now compiles through
   `open.atlas.common.engine` directly, the same compiler `/api/studio/*` and
   a deployed Server use — dialect-v1 is translated in exactly one place
   (D1.8). What is left here is genuinely app-local: this cache, and
   resolving a source's `${VAR}` credentials for **this** Studio instance
   (`open.atlas.studio.credentials`) plus the demo database's local path —
   neither belongs in `common/engine`, which knows nothing about a browser,
   a Studio home directory, or a bundled demo fixture."
  (:require [open.atlas.runtime.source-bindings :as source-bindings]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [open.atlas.common.engine :as engine]
            [open.atlas.runtime.env :as env]
            [open.atlas.studio.credentials :as credentials]
            [open.atlas.studio.pathom.explain :as explain]))

(defn- studio-home []
  (or (System/getenv "OPEN_ATLAS_STUDIO_HOME")
      (str (System/getProperty "user.home") "/.open-atlas/studio")))

(def ^:private demo-databases
  "The demo databases this Studio knows how to satisfy, keyed by the path the
   models declare. `fixtures/dvd` is the example a person meets first;
   `fixtures/demo` (cis) is the fixture six modules' tests run through."
  {"data/open-atlas-demo.db"  {:file "open-atlas-demo.db" :fixture "../../fixtures/dvd/dvd-db"}
   "data/cis-demo.db"  {:file "cis-demo.db" :fixture "../../fixtures/demo/demo-db"}})

(defn- sql-statements [text]
  (->> (str/split-lines text)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn- build-from-fixture!
  "Build a demo database from the committed schema.sql + seed.sql.

   The last resort, and the one that makes a fresh checkout work without a
   build step: the databases are generated artifacts, so they are not in the
   repository, and a Studio started from source would otherwise have a model
   pointing at a file nobody made."
  [^java.io.File target fixture-dir]
  (let [schema (io/file fixture-dir "schema.sql")
        seed   (io/file fixture-dir "seed.sql")]
    (when (and (.isFile schema) (.isFile seed))
      (io/make-parents target)
      (with-open [conn (java.sql.DriverManager/getConnection
                        (str "jdbc:sqlite:" (.getPath target)))
                  stmt (.createStatement conn)]
        (doseq [file [schema seed]
                sql  (sql-statements (slurp file))]
          (.execute stmt sql)))
      target)))

(defn- demo-database-path
  "An absolute path for one declared demo database, materialising it once under
   this Studio's home if it is not there yet.

   SQLite must open a real filesystem path rather than a jar URL, so the
   packaged copy is written out. **Never overwritten**: between releases that
   file may be an operator's own demo workspace."
  [{:keys [file fixture]}]
  (let [built (io/file "target/data" file)]
    (if (.isFile built)
      (.getCanonicalPath built)                     ; a build in this checkout
      (let [target (io/file (studio-home) "demo" file)]
        (when-not (.isFile target)
          (if-let [resource (io/resource (str "data/" file))]
            (do (io/make-parents target)
                (with-open [in (io/input-stream resource)
                            out (io/output-stream target)]
                  (io/copy in out)))
            (when-not (build-from-fixture! target fixture)
              (throw (ex-info "No bundled demo database and no fixture to build one from"
                              {:file file :fixture fixture})))))
        (.getCanonicalPath target)))))

(defn resolve-source
  "One source entry, with `${VAR}` credential placeholders substituted for
   **this** instance (`credentials/resolve-config`) and the demo database's
   declared relative path rewritten to an absolute one. sql-v1 and
   `open.atlas.common.connector.sql` both key connection fields by underscore
   (`:jdbc_url`) — this is the dialect's own field name, not a second one."
  [source-id source]
  ;; §106.14-12 resolves store-first, environment-second, at ANY depth — this
  ;; is the seam an api source's nested `[:auth :value]` finally comes through.
  ;; It does NOT refuse here: this function runs while an environment is
  ;; COMPILED, and one source whose variable is unset must not make a graph's
  ;; structure unreadable (`list_entities` and friends never dial). A variable
  ;; that cannot be answered is refused where a connection is actually opened —
  ;; `require-readable-source!` and `declare-source!` in `builder-server`,
  ;; both through `credentials/resolve-config!`.
  (let [source ((source-bindings/file-resolver (str (io/file (studio-home) "source-bindings.edn")) (fn [_ entry] entry)) source-id source)
        resolved (credentials/resolve-config (studio-home) source-id source)]
    (if-let [demo (get demo-databases
                       (some-> (:jdbc_url source) (str/replace #"^jdbc:sqlite:" "")))]
      (assoc resolved :jdbc_url (str "jdbc:sqlite:" (demo-database-path demo)))
      resolved)))

(defonce ^:private environments (atom {}))
(def ^:private environment-lock (Object.))
(defn environment-cache-ttl-ms
  "Configured idle TTL for cached graph environments — **the data plane's own
   value, read through the runtime** (8.4.20 裁定十四).

   Both doors expire after the same idle window and therefore must read one
   number: `OPEN_ATLAS_GRAPH_ENV_CACHE_TTL_MS`, two hours by default. The
   reader lives in `runtime/env` because the runtime module cannot depend on
   this app, so a default kept here would be the second copy the ruling
   forbids. This function is the app-facing name for it and nothing else."
  []
  (env/cache-ttl-ms))

(defn- now-ms [] (System/currentTimeMillis))

(defn- expired? [entry now ttl-ms]
  (>= (- now @(:last-used-ms entry)) ttl-ms))

(defn- reap-expired! [now ttl-ms]
  ;; Unlike the legacy snapshot this cache retired, a compiled engine env owns
  ;; no external resource of its own (no open connection, nothing to close):
  ;; `open.atlas.common.connector.sql`'s HikariCP pools are cached
  ;; independently, keyed by connection fingerprint, and outlive any one
  ;; scope's cache entry (impl/pool.clj). Eviction here is purely about not
  ;; holding onto compiled pathom envs and catalogs for graph revisions
  ;; nobody is querying any more.
  (let [expired (into {} (filter (fn [[_ entry]] (expired? entry now ttl-ms)) @environments))]
    (when (seq expired)
      (swap! environments #(apply dissoc % (keys expired))))
    (count expired)))

(defn- environment-key [{:keys [workspace graph revision]}]
  (when-not (and (string? workspace) (seq workspace)
                 (string? graph) (seq graph)
                 (string? revision) (seq revision))
    (throw (ex-info "A graph-scoped environment requires workspace, graph, and revision"
                    {:code :graph-context-required
                     :scope {:workspace workspace :graph graph :revision revision}})))
  [workspace graph revision])

(defn environment!
  "Return the compiled authoring environment for one effective graph
   revision, as `{:key :scope :compiled :created-at :last-used-ms :active}`.

   `:compiled` is `open.atlas.runtime.env/for-model`'s compiled env — the
   same builder a served version is compiled by. (The old
   `{:authoring? true}` flag retired with the tier axis, D5.11: there is one
   compiled view and it is already the modeller's full one.) Entries expire
   after the configured *idle* TTL (two hours by default, 8.4.20 裁定十四 ②). The returned entry carries one execution lease; `process` and
   `explain` release it even when they fail, so expiry never happens mid-use
   — though unlike the legacy snapshot, nothing here would actually break if
   it did; the lease is kept for `cache-status`'s `:in-flight-count` and to
   keep this cache's shape recognisable rather than because a resource
   depends on it now."
  [scope model]
  (let [key (environment-key scope)
        now (now-ms)
        ttl-ms (environment-cache-ttl-ms)]
    (locking environment-lock
      (reap-expired! now ttl-ms)
      (let [entry (or (get @environments key)
                      (let [;; `resolve-source` goes in, the substitution happens inside:
                            ;; the credential step and the compile are one door
                            ;; (裁定十二 ①), the same one a served version enters
                            ;; through `env/for-request`.
                            compiled (:env (env/for-model
                                            model {:resolve-source resolve-source}))
                            entry {:key key :scope scope :compiled compiled
                                   :created-at (str (java.time.Instant/now))
                                   :last-used-ms (atom now)
                                   :active (atom 0)}]
                        (swap! environments assoc key entry)
                        entry))]
        (reset! (:last-used-ms entry) now)
        (swap! (:active entry) inc)
        entry))))

(defn- release! [entry]
  (locking environment-lock
    (swap! (:active entry) dec)))

(defn invalidate!
  "Retire cached graph environments selected by `predicate`.  Call this only
   after a graph/model publish or archive transition; normal requests merely
   reuse the immutable revision they explicitly selected."
  [predicate]
  (locking environment-lock
    (let [removed (into {} (filter (fn [[_ entry]] (predicate entry)) @environments))]
      (swap! environments #(apply dissoc % (keys removed)))
      (count removed))))

(defn environment-invalidate!
  "Drop **every** cached environment for one graph, by graph UUID (8.4.20
   裁定十四 ②).

   The draft key is graph + projection digest, so a changed model would be
   rebuilt under a new key anyway — but not until two idle hours had passed for
   the old one, and the ruling asks for the stale entry to be released at the
   write. Whole-graph rather than per-revision: an entry that still matches the
   current digest recompiles to the identical env, so dropping it costs one
   compile and keeps this function's contract a single sentence."
  [graph-id]
  (invalidate! #(= graph-id (get-in % [:scope :graph]))))

(defn cached-graph-revisions
  "`{graph-uuid #{revision …}}` for what the cache is holding right now.

   The host's post-write sweep needs it: it re-projects those graphs and drops
   the ones whose digest moved. Reading the cache rather than the graph
   registry keeps the sweep proportional to what is actually cached — a graph
   with no entry has nothing to invalidate."
  []
  (locking environment-lock
    (reduce (fn [m [_ graph revision]] (update m graph (fnil conj #{}) revision))
            {}
            (keys @environments))))

(defn clear! [] (invalidate! (constantly true)))

(defn cache-status []
  (let [ttl-ms (environment-cache-ttl-ms)]
    (locking environment-lock
      ;; Studio polls this endpoint, so idle entries are retired promptly even
      ;; after the user closes a graph without issuing another EQL request.
      (reap-expired! (now-ms) ttl-ms)
      ;; A zero-sized cache is the normal post-TTL state, not a server outage.
      {:ready? true
       :environment-count (count @environments)
       :environment-cache-ttl-ms ttl-ms
       :environment-cache-ttl-seconds (/ ttl-ms 1000.0)
       :in-flight-count (reduce + 0 (map #(deref (:active %)) (vals @environments)))
       :environments (->> @environments vals
                          (map #(assoc (select-keys % [:scope :created-at])
                                       :in-flight @(:active %)))
                          (sort-by (comp pr-str :scope)) vec)})))

(defn- run*
  "One EQL request against `entry`'s compiled env → engine's OWN result map.
   Does not touch the lease; the two public entrances below do."
  [entry {:keys [entity query lenient?]}]
  ;; The middle argument is engine/query's retired token slot (D5.11) —
  ;; ignored by the implementation, nil here so nobody reads a tier into it.
  (engine/query (:compiled entry) nil
                {:entity (or entity {}) :query query}
                {:lenient? lenient? :stats? true}))

(defn run
  "Run one EQL request and return engine's result **as data** — `:engine/status
   :ok` with `:engine/data`, or the flat `:rejected` refusal (D5.4).

   This is the entrance the Builder's single draft path uses (8.4.18 裁定十一):
   the MCP face has to hand a refusal to `finding-v1` intact, and a refusal
   that arrived as a thrown exception has already lost its shape. `process`
   below keeps the throwing contract for the callers that were built on it."
  [entry request]
  (try (run* entry request) (finally (release! entry))))

(defn process
  "Run one EQL request (`{:entity :query :lenient?}`) against `entry`'s
   compiled env, `{:result :plan}` — the same DTO shape Query Studio has
   always read. `:result` is engine's `:engine/data`; `:plan` comes from
   `open.atlas.studio.pathom.explain/plan-of-result` reading the raw
   (unscrubbed) result's Pathom3 run-stats metadata, which `:stats? true`
   keeps rather than discards.

   Engine never throws for a query-shaped failure — `:engine/status
   :rejected` is data, not an exception (D5.4's structured-refusal
   discipline) — so a rejection here is turned into a thrown `ex-info`
   instead, matching every caller upstream of this function, which still
   expects a query failure to arrive as a Clojure exception."
  [entry request]
  (try
    (let [r (run* entry request)]
      (if (= :rejected (:engine/status r))
        (throw (ex-info (:message r) (dissoc r :engine/status :severity :message)))
        (cond-> {:result (:engine/data r)
         :plan (explain/plan-of-result (:engine/stats r))
         ;; 交出原始 run-stats：Activity 的每-resolver 时间线从这里来。
         ;; **不在这里加工**——摘要的形状归 obs（`summarize-run-stats`），
         ;; 这里再写一份就是第二个答案。
         :stats (:engine/stats r)
         :environment (:scope entry)}
          (:engine/partial? r) (assoc :partial true :issues (:engine/issues r) :source-completeness :partial)
          (:engine/row-outcomes r) (assoc :row-outcomes (:engine/row-outcomes r)))))
    (finally (release! entry))))

(defn compiled-env
  "`entry`'s compiled env, releasing the execution lease exactly as `process`
   and `explain` do.

   **Why this exists rather than callers reading `:compiled` themselves**
   (8.4.13): `environment!` hands out a lease on every call, and only the
   three functions here give it back. A caller that wanted the catalog and
   read the key directly would leak one lease per call — invisible until
   `cache-status`'s `:in-flight-count` never returns to zero and idle
   environments stop being reaped.

   The value is immutable, so holding it after the lease is returned is safe:
   what the lease protects is the cache entry's liveness, not the compiled
   env's contents."
  [entry]
  (try (:compiled entry) (finally (release! entry))))

(defn explain
  "Plan `request` (`{:entity :query}`) against `entry`'s compiled authoring
   env and `resolvers` (the graph's resolvers in the `graph-resolver` DTO
   shape `open.atlas.studio.pathom.explain/explain` expects — the same shape
   `builder-server/catalog-data` and `graph-data` already build from this
   same model), without invoking any connector — the same contract Explain
   has always had."
  [entry resolvers request]
  (try
    (assoc (explain/explain (get-in (:compiled entry) [:engine/by-tier :authoring :env])
                            resolvers request)
           :config-revision (get-in entry [:scope :revision])
           :environment (:scope entry))
    (finally (release! entry))))

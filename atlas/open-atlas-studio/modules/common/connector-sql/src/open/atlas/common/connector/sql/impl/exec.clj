(ns open.atlas.common.connector.sql.impl.exec
  "执行与故障归类。

   principles：「超时强制且有默认值，enforce 不了超时的连接器不允许发布。」
   所以本 ns 没有「不设超时」这条路径：`timeout-s` 永远给出一个正整数秒，
   写入端已经把 `:timeout_ms <= 0` 拒掉了。

   每个出口都经 SPI 的故障构造器——驱动异常在这里就地归类并**丢弃**，它的
   message 里通常有整条语句与主机名。"
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.impl.pool :as pool])
  (:import [java.sql SQLException ResultSet ResultSetMetaData SQLTimeoutException SQLTransientConnectionException
            SQLTransientException Statement Types]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(def default-timeout-ms
  "超时的缺省值（principles：「超时强制且**有默认值**」）。
   没有它，一次跑飞的查询会一直占着调用线程；Hikari 的 connectionTimeout
   管不到这一段。"
  30000)

(def ^:private slow-query-threshold-ms
  "慢查询告警阈值（毫秒）。超出阈值会被标记为 `:slow-query`，便于 UI 告警。"
  2000)

(defn timeout-ms
  "`:resilience :timeout_ms`（D6.2 的 policy 块）优先，否则缺省。"
  [source-config]
  (or (get-in source-config [:resilience :timeout_ms]) default-timeout-ms))

;; ---------------------------------------------------------------------------
;; Resilience policy (doc 107 §107.3 / §107.11, owner 2026-09-18)
;;
;; sql lands in the SAME batch as api and shares the SAME vocabulary: one rule
;; must give one answer on both connectors (D1.8). What sql does NOT get in
;; this step is a `:status-map` equivalent (SQLSTATE -> category) -- §107.9
;; item 11 leaves that until there is corpus to rule it on -- and no claim is
;; made about driver cancellation being retryable; the existing timeout
;; enforcement (`with-deadline`) is unchanged.
;; ---------------------------------------------------------------------------

(def connector-default-policy
  "The sql connector's level of the four-level merge. A database query is
   typically dearer than an HTTP call, hence the longer attempt default."
  {:timeout_ms default-timeout-ms})

(defn effective-policy
  "The policy in force for one statement: the four-level merge of D6.2, with
   the deployment's clamp already applied upstream (runtime/env)."
  ([source-config] (effective-policy source-config nil))
  ([source-config config]
   (spi/effective-policy [connector-default-policy
                          (:resilience source-config)
                          (:policy config)]
                         nil)))

(defn fingerprint
  "The source identity a bulkhead and a breaker are keyed on (D6.5).

   The JDBC URL WITHOUT its query string, plus the username: the query string
   of a jdbc url routinely carries the password, and two pools to one database
   are one load on that database. Hashed, so even the host never travels as a
   key."
  [{:keys [jdbc_url username]}]
  (str (hash [(first (str/split (str jdbc_url) #"\?")) (str username)])))

(defn guard-for
  "The guard arguments for one statement (see `spi/guarded-call!`)."
  ([source-config ctx] (guard-for source-config ctx nil))
  ([source-config ctx config]
   {:fingerprint (fingerprint source-config)
    :policy (effective-policy source-config config)
    :ctx ctx
    ;; D6.3: no statement is started once the query's budget is spent, and a
    ;; retry backoff never sleeps past it.
    :deadline-at (spi/deadline-at)}))

(defn timeout-s
  "`Statement.setQueryTimeout` 的整秒值，向上取整，最小 1。

   **按剩余预算收窄**（D6.3 / doc 107 §107.7）：一条语句的时限与整条查询的
   预算不相加。剩下 200 ms 时还允许一条语句跑满 30 秒，等于让它在调用方已经
   被告知超时之后继续占着连接干活。

   秒的粒度是 JDBC 的，不是我们的：`setQueryTimeout` 只收整秒，所以最小值是 1
   ——剩余不足一秒时仍然发这一条，由 `with-deadline` 的绝对计时器兜底
   （引擎的 `:cancelled` 判定在更上游，见 `spi/guarded-call!`）。"
  [source-config]
  (let [ms (spi/attempt-timeout-ms (timeout-ms source-config))]
    (max 1 (quot (+ (long ms) 999) 1000))))

(defn- message-chain
  "Every message down the cause chain, lower-cased and joined.

   Classifying on `.getMessage` alone reads the OUTERMOST wrapper, which is
   routinely the least specific thing thrown. A missing JDBC driver is the
   worked example: Hikari's top-level message is `Failed to get driver instance
   for jdbcUrl=…` and the words that name the fault -- `No suitable driver` --
   sit one level down, in the `SQLException` cause. Reading only the top missed
   both `:connection` patterns and classified it `:source`, so the Builder
   showed a missing jar as a query fault.

   Bounded, and tracks identity: a cause chain is not guaranteed acyclic (a
   driver may set an exception as its own cause), and this must not hang the
   caller to classify a failure."
  [^Throwable e]
  (loop [^Throwable t e, acc [], seen #{}]
    (if (or (nil? t) (contains? seen t) (> (count acc) 8))
      (str/lower-case (str/join " " acc))
      (recur (.getCause t)
             (conj acc (or (.getMessage t) ""))
             (conj seen t)))))

(defn classify
  "驱动异常 → `:error/category`。归类**在这里**看一眼 message 就丢掉——
   看是为了把 timeout 与普通 source 错分开，丢是因为它带着语句。"
  [^Exception e]
  (let [msg (message-chain e)]
    (cond
      (instance? SQLTimeoutException e)            :timeout
      (re-find #"timeout|timed out|interrupted" msg) :timeout
      (instance? SQLTransientConnectionException e) :connection
      (instance? SQLTransientException e)         :connection
      ;; `failed to get driver instance` is HikariCP's own wording for "no
      ;; driver on the classpath for this jdbc_url". Matched alongside the
      ;; driver-level `no suitable driver` so the category is right even when
      ;; the cause is absent.
      (re-find #"connection|unable to open|no suitable driver|failed to get driver instance|closed|deadlock|database is locked|database is busy|too many connections|connection refused|connection reset|socket" msg)
      :connection
      :else :source)))

(defn- slow-query? [elapsed-ms]
  (>= elapsed-ms slow-query-threshold-ms))

(defn diagnostic-redactor
  "Resolve connection secrets only when an authoring diagnostic is requested.
   The closure stays inside the connector; config never enters the receipt."
  [source-config]
  (fn [message]
    (let [cc (pool/connection-config source-config)
          secrets (filter #(and (string? %) (seq %))
                          [(:jdbc-url cc) (:username cc) (:password cc)])]
      (-> (reduce #(str/replace %1 %2 "[redacted]") (str message)
                  (sort-by count > secrets))
          (str/replace #"(?i)(?:jdbc:|https?://)[^\s]+" "[redacted-url]")
          (str/replace #"(?i)(password|passwd|pwd|token|secret)\s*[=:]\s*[^\s,;]+" "$1=[redacted]")))))

(defn- diagnose! [ctx stage ^Throwable error]
  (when-let [sink spi/*source-diagnostics*]
    (try
      (let [redact (or (::diagnostic-redactor ctx) (diagnostic-redactor {}))
            causes (loop [pending [error] seen #{} out []]
                     (if (or (empty? pending) (>= (count out) 8))
                       out
                       (let [^Throwable t (first pending) remaining (subvec pending 1)
                             message (when t (redact (or (.getMessage t) "No message")))]
                         (if (or (nil? t) (contains? seen t))
                           (recur remaining seen out)
                           (recur (cond-> (conj remaining (.getCause t))
                                    (instance? SQLException t) (conj (.getNextException ^SQLException t)))
                                  (conj seen t)
                                  (conj out
                                        (cond-> {:type (.getName (class t))
                                                 :message (subs message 0 (min 4000 (count message)))}
                                          (instance? SQLException t)
                                          (assoc :sql-state (.getSQLState ^SQLException t)
                                                 :vendor-code (.getErrorCode ^SQLException t)))))))))]
        (sink (merge (select-keys ctx [:resolver-id :source-type :source-ref])
                     {:stage stage :category (classify error) :causes causes})))
      (catch Exception _ nil))))

(def ^:private declared-code
  ;; A refusal code a source function raised on purpose: `code` or `code:name`, lowercase identifiers
  ;; only. Whatever follows (ids, times, row values) is cut off, never forwarded (finding-v1 §5).
  #"^([a-z][a-z0-9_]{2,63})(?::([a-z][a-z0-9_]{0,63}))?(?=$|[\s:\[(,;.])")

(defn- raised-code
  "The declared refusal code of a PL/pgSQL `RAISE EXCEPTION` (SQLSTATE P0001), or nil. Only the
   code shape is forwarded; the driver's wrapping (`ERROR:`, `Where:` context) and anything after
   the code are dropped, so no statement text or value can travel."
  [^Throwable t]
  (when (and (instance? SQLException t) (= "P0001" (.getSQLState ^SQLException t)))
    (let [line (-> (or (.getMessage t) "") (str/replace #"^ERROR:\s*" "") str/split-lines first (or ""))]
      (when-let [[_ code field] (re-find declared-code line)]
        (str "SQL statement rejected: " code (when field (str ":" field)) ".")))))

(defn- safe-write-reason [e]
  ;; Only exact, fixed constraint messages and declared P0001 codes are public; never forward driver text.
  (loop [t e seen #{}]
    (when (and t (not (contains? seen t)) (< (count seen) 8))
      (or (raised-code t)
          (get {"[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (catalog must be confirmed)"
                "The selected catalog must be confirmed before this operation."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (revision conflict)"
                "The record revision changed. Read the latest revision before editing."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (confirmed domain required)"
                "A confirmed parent domain is required."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (unknown_actor)"
                "SQL statement rejected: unknown_actor. The declared actor must be an active employee with a recorded email. Provision the operator before writing."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (unknown_field)"
                "SQL statement rejected: unknown_field. The changes object contains an undeclared field. Use the operation field names without Graph namespaces."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (revision_conflict_or_missing_object)"
                "SQL statement rejected: revision_conflict_or_missing_object. The record is missing or its revision changed. Read it again before editing."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (request_already_processed_read_receipt)"
                "SQL statement rejected: request_already_processed_read_receipt. This request was already processed. Read its receipt; do not submit another write."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (invalid_payload)"
                "SQL statement rejected: invalid_payload. The write payload has an invalid shape. Read the operation contract."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (invalid_batch)"
                "SQL statement rejected: invalid_batch. The batch must contain 1 to 100 items and no other top-level fields."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (invalid_item)"
                "SQL statement rejected: invalid_item. A batch item contains an invalid shape or undeclared field."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (invalid_changes)"
                "SQL statement rejected: invalid_changes. The changes value must be an object."
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort (invalid_email)"
                "SQL statement rejected: invalid_email. The supplied email format is invalid."}
               (.getMessage ^Throwable t))
          (recur (.getCause ^Throwable t) (conj seen t))))))

(defn datasource!
  "拿到 DataSource：宿主注入的优先，否则按 source-config 开池。
   未解析的 `${VAR}` 是 `:connection`——变量**名**进 message（它是方言里的
   配置，不是凭证值），变量的值当然不进。"
  [source-config connection ctx]
  (or connection
      (let [cc (pool/connection-config source-config)]
        (when (seq (:missing cc))
          (spi/fail! :connection ctx
                     (str "unresolved " (str/join ", " (map #(str "${" % "}") (:missing cc))))))
        (try (pool/datasource cc)
             (catch Exception e
               (diagnose! (assoc ctx ::diagnostic-redactor (diagnostic-redactor source-config)) :connect e)
               (spi/fail! (classify e) ctx "pool initialization failed"))))))

(defonce ^:private ^ScheduledExecutorService watchdog
  ;; 单线程、daemon：它只做一件事——到点了调 Statement.cancel()。
  (Executors/newScheduledThreadPool
   1
   (reify ThreadFactory
     (newThread [_ r]
       (doto (Thread. r "atlas-sql-timeout") (.setDaemon true))))))

(defn- with-deadline
  "跑 `f`，到 `timeout` 秒还没回来就 `Statement.cancel()`。

   为什么不只靠 `Statement.setQueryTimeout`：**驱动可以不理它**。
   本仓库跑 conformance 的 sqlite-jdbc 3.46 就是这样——一条 9 亿行的递归 CTE
   带 `setQueryTimeout(1)` 照跑 106 秒才回来。principles 说「超时强制且有默认值，
   enforce 不了超时的连接器不允许发布」，那么「强制」这件事就不能外包给驱动的
   善意：setQueryTimeout 照设（驱动支持就少一层开销），另外自己看着表，到点
   `cancel()`。两条腿都断了的驱动才是真的不能发布。

   返回 `[结果 cancelled?]`；抛出时由调用方结合 `cancelled?` 归类——被我们取消
   的语句抛出来的是普通 SQLException，不打这个标记就会被误判成 `:source`。"
  [^Statement st timeout f]
  (let [cancelled (volatile! false)
        task (.schedule watchdog
                        ^Runnable (fn []
                                    (vreset! cancelled true)
                                    (try (.cancel st) (catch Exception _ nil)))
                        (long timeout) TimeUnit/SECONDS)]
    (try
      [(f) cancelled]
      (finally (.cancel task false)))))

(defn- sanitize-id
  "白名单过滤：只留 `[A-Za-z0-9-]`。request-id 与 graph UUID 本来就满足它,
   过滤是为了让「注释永远闭合不了 `*/`」成为代码事实而不是假设。"
  [x]
  (when x (not-empty (str/replace (str x) #"[^A-Za-z0-9-]" ""))))

(defn tag
  "E2：给一条语句前置 `/* open-atlas req=… graph=… */`。

   **只在这里拼，且在绑定完成之后、`jdbc/prepare` 之前。** 注释绝不能进模型、
   不能进 `resolve-bindings` 的输入、不能进任何 hash 或缓存键——否则占位符检查
   与语句身份全部错位。它不改占位符个数，也不改绑定顺序：只在 sql 串前面加字。

   没有 request-id（单测、CLI、体检）就原样返回。"
  [sql-params ctx]
  (if-let [rid (sanitize-id (:request-id ctx))]
    (let [gid (sanitize-id (:graph-id ctx))
          c   (str "/* open-atlas req=" rid (when gid (str " graph=" gid)) " */ ")]
      (into [(str c (first sql-params))] (rest sql-params)))
    sql-params))

(defn- trace!
  "一条语句的回执，交给 `spi/*statement-trace*`。宿主没绑就是 no-op
   （见那个 var 的文档：这不是日志，是宿主显式接上的一条通道）。

   `sql-params` 就是 `[sql v1 v2 …]`——值从来没有进过 sql 串，所以语句与值在
   这里天然是分开的两件东西，而不是需要再解析一次的一整块。"
  [ctx sql-params t0 rows outcome error-category attempt]
  (let [elapsed-ms (quot (- (System/nanoTime) t0) 1000000)]
    (spi/trace-statement!
     (cond-> {:sql         (first sql-params)
              :values      (vec (rest sql-params))
              :elapsed-ms  elapsed-ms
              :resolver-id (:resolver-id ctx)
              :source-type (:source-type ctx)
              :outcome     outcome
              ;; doc 107 §107.8: which try this was, and in what category the
              ;; failing ones failed -- the same two fields the api connector
              ;; puts on its receipts, so one audit reader serves both.
              :attempt     attempt
              :slow-query  (slow-query? elapsed-ms)}
       rows (assoc :rows rows)
       error-category (assoc :error-category error-category :category error-category)))))

;; Semantic Contract 2.0 reads only (see impl.values): a timestamp column is
;; read with getTimestamp, so the DRIVER resolves it to an instant with the zone
;; it knows for that column (ClickHouse DateTime carries its own time zone; a
;; PostgreSQL timestamptz its offset). Reading it as a zoneless LocalDateTime
;; and picking a zone here would be a guess. Every other column goes through
;; next.jdbc's default reader unchanged.
(defn- instant-reader [^ResultSet rs ^ResultSetMetaData md ^Integer i]
  (let [t (.getColumnType md i)]
    (if (or (= t Types/TIMESTAMP) (= t Types/TIMESTAMP_WITH_TIMEZONE))
      (some-> (.getTimestamp rs i) (.toInstant))
      (rs/read-column-by-index (.getObject rs i) md i))))

(def ^:private instant-builder (rs/as-maps-adapter rs/as-unqualified-lower-maps instant-reader))

(defn- query-once!
  [ds sql-params timeout ctx attempt instants?]
  (let [cancelled (volatile! false)
        ;; 回执用**打过标的**文本：源库日志里看到的就是这条（值仍不在其中）。
        sql-params (tag sql-params ctx)
        t0        (System/nanoTime)]
    (try
      (with-open [con (jdbc/get-connection ds)
                  st  (jdbc/prepare con sql-params {:timeout timeout})]
        (let [[rows c] (with-deadline st timeout
                         #(jdbc/execute! st nil {:builder-fn (if instants? instant-builder rs/as-unqualified-lower-maps)}))]
          (vreset! cancelled @c)
          (trace! ctx sql-params t0 (count rows) :ok nil attempt)
          rows))
      (catch Exception e
        ;; 回执也给失败的那条：一条查不出东西的语句，正是最值得看见的那条。
        (diagnose! ctx :query e)
        (trace! ctx sql-params t0 nil :error (if @cancelled :timeout (classify e)) attempt)
        ;; 失败绝不能长得像空结果：「没有数据」与「source 挂了」是两个答案。
        (if-let [reason (when-not @cancelled (safe-write-reason e))]
          (throw (ex-info reason {:error/category :source :error/safe-reason reason :error/statement-status :rejected}))
          (spi/fail! (if @cancelled :timeout (classify e)) ctx))))))

(defn- execute-no-result-once!
  [ds sql-params timeout ctx attempt]
  (let [cancelled (volatile! false)
        sql-params (tag sql-params ctx)
        t0        (System/nanoTime)]
    (try
      (with-open [con (jdbc/get-connection ds)
                  st  (jdbc/prepare con sql-params {:timeout timeout})]
        (let [[_ c] (with-deadline st timeout #(.execute st))]
          (vreset! cancelled @c))
        (trace! ctx sql-params t0 nil :ok nil attempt)
        nil)
      (catch Exception e
        (diagnose! ctx :execute e)
        (trace! ctx sql-params t0 nil :error (if @cancelled :timeout (classify e)) attempt)
        (if-let [reason (when-not @cancelled (safe-write-reason e))]
          (throw (ex-info reason {:error/category :source :error/safe-reason reason :error/statement-status :rejected}))
          (spi/fail! (if @cancelled :timeout (classify e)) ctx))))))

;; ---------------------------------------------------------------------------
;; The guarded entry points (D6.4 / D6.5 via the shared SPI implementation)
;; ---------------------------------------------------------------------------

(defn query!
  "跑一条读语句，返回 unqualified-lower 的行向量。
   `sql-params` 是 `[sql v1 v2 …]`——**永远**是这个形状，值从来没有进过 sql 串。

   Every statement goes through the source's bulkhead and circuit breaker, and
   is retried per the effective policy (doc 107 §107.3, §107.11 -- sql lands in
   the same batch as api, sharing ONE implementation of the rule, D1.8). A read
   is idempotent by default (D6.4).

   `guard` may be nil, which means the platform default: no retry, generous
   permits. Nothing about the existing timeout enforcement changes -- retrying
   a `:timeout` is not allowed, because `:timeout` is not a transient category.

   `read-opts` `{:instants? true}` reads timestamp columns as instants through
   the driver (Semantic Contract 2.0 resolvers only, see `instant-reader`)."
  ([ds sql-params timeout ctx] (query! ds sql-params timeout ctx nil))
  ([ds sql-params timeout ctx guard] (query! ds sql-params timeout ctx guard nil))
  ([ds sql-params timeout ctx guard {:keys [instants?]}]
   (spi/guarded-call! (merge {:fingerprint (str (hash ds)) :policy spi/platform-default-policy
                              :ctx ctx}
                             guard)
                      (fn [n] (query-once! ds sql-params timeout ctx n (boolean instants?))))))

(defn execute-no-result!
  "跑一条不读结果集的语句（sql-v1：mutation 的 `:result_type \"none\"`）。
   走裸 PreparedStatement 而不是 `jdbc/execute!`：后者总要构造结果/更新计数，
   「不读结果集」就该真的不读。

   Never retried: a mutation's idempotency is not known (D6.4), and sql-v1 has
   no key for declaring it. It still takes a permit and still sees the breaker."
  ([ds sql-params timeout ctx] (execute-no-result! ds sql-params timeout ctx nil))
  ([ds sql-params timeout ctx guard]
   (spi/guarded-call! (merge {:fingerprint (str (hash ds)) :policy spi/platform-default-policy
                              :ctx ctx}
                             guard
                             {:idempotent? false})
                      (fn [n] (execute-no-result-once! ds sql-params timeout ctx n)))))

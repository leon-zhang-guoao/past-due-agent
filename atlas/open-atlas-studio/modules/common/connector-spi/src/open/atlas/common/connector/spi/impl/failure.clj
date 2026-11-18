(ns open.atlas.common.connector.spi.impl.failure
  "连接器故障契约（principles「执行与连接」）：

     「每个连接器故障都是带
      `{:error/category :timeout|:connection|:source|:mapping, :resolver-id,
        :source-type}` 的 `ex-info`，`ex-data` 里永远没有语句、带查询串的 URL、
      响应体、凭证或行值——这个异常要一路走到 UI 与 MCP。」

   「永远没有」是**键面封闭**：ex-data 恰好是上面三个键，多一个键就是多一条
   泄漏通道。因此本 ns 是构造故障的唯一入口，没有「再塞点上下文」的重载。

   Two additions since, both configuration-shaped numbers and both closed the
   same way: an optional `:retry-after-ms` (doc 107 §107.4 -- a DURATION parsed
   from `Retry-After`, the only upstream-derived number allowed here) and an
   optional `:limit` (§107.6 -- which declared bound a collection read hit, and
   by how much). The key face is the three, optionally plus those two."
  (:require [open.atlas.common.dialect :as dialect]
            [open.atlas.common.connector.spi.impl.redact :as redact]))

(def categories
  "The controlled failure vocabulary, closed. principles pinned the first four;
   doc 60 E6.3 added the next five and doc 107 §107.4 the last three (owner
   ruling 2026-09-18, §107.12).

   :timeout           -- 超时（强制且有默认值；enforce 不了超时的连接器不允许发布）
   :connection        -- 连不上/凭证未设/池起不来
   :source            -- source 侧执行失败（语句被拒、约束冲突、驱动报错）
   :mapping           -- 拿到了行但对不上模型（列缺席、field mapping 解析不到）
   :unavailable       -- upstream says it cannot serve right now (502/503/504)
   :rate_limited      -- upstream is throttling us (429); carries :retry-after-ms
   :circuit_open      -- WE refused to call: the breaker for this source is open
   :bulkhead_rejected -- WE refused to call: no permit within :max_wait_ms
   :cancelled         -- the deadline was already spent; no request was sent
   :unauthorized      -- authorization refused (401/403). D6.4: never retried
   :not-found         -- the addressed row does not exist (by-key 404)
   :input-rejected    -- this input could not be fetched; NOT proof it is absent

   The set lives in the dialect (`dialect/failure-categories`) because the
   dialect validates `:retry_on` against it and cannot depend on the SPI --
   one definition, aliased here rather than copied (CLAUDE.md)."
  dialect/failure-categories)

(def transient-categories
  "The only categories a `:retry_on` may name (D6.4). doc 107 §107.3."
  dialect/transient-failure-categories)

(def protected-statuses
  "HTTP statuses whose classification the platform keeps: a `:status-map` may
   not re-map authorization, rate limiting or system failure (doc 107 §107.5)."
  dialect/protected-statuses)

(defn- message
  "故障消息。`detail` 只允许是**配置层面**的短语（键名、占位名、环境变量名、
   category），绝不允许是驱动异常原文、语句、URL、行值——那些正是本契约要挡的
   东西。调用方传 detail 时按这条自查；本 ns 不做二次过滤，因为它无法区分
   「凭证值」与「刚好长得像值的键名」。"
  [category resolver-id source-type detail]
  (str "connector failure: " category
       " [resolver " (pr-str resolver-id) ", source-type " (pr-str source-type) "]"
       (when detail (str " — " detail))))

(defn fail!
  "构造并抛出连接器故障。

   ctx: `{:resolver-id id :source-type type}`（mutation 路径把 mutation id 放
   `:resolver-id`——它是「哪个操作块」的标识，方言里 resolver 与 mutation 的 id
   同一个名字空间）。

   刻意**不挂 cause**：`(ex-info msg data cause)` 会让下游 `(.getCause e)`
   `.getMessage` 取回驱动原文——里面通常有整条语句和主机名。契约说这个异常要
   「一路走到 UI 与 MCP」，那它每一层都不能藏着业务数据。驱动原文的去处是本地
   日志，不是异常。"
  ([category ctx] (fail! category ctx nil nil))
  ([category ctx detail] (fail! category ctx detail nil))
  ([category {:keys [resolver-id source-type]} detail {:keys [retry-after-ms limit]}]
   (assert (categories category) (str "unknown :error/category " (pr-str category)))
   (throw (ex-info (message category resolver-id source-type detail)
                   ;; Three keys, plus at most `:retry-after-ms` -- see ns doc.
                   ;; doc 107 §107.4: that number is the ONE upstream-derived
                   ;; value allowed in ex-data (parsed from `Retry-After`, a
                   ;; duration and never a URL, body or row value). It is here
                   ;; because the retry loop and the caller both need to honour
                   ;; it, and a failure that cannot say "wait 2 s" forces the
                   ;; caller to guess.
                   (cond-> {:error/category category
                            :resolver-id    resolver-id
                            :source-type    source-type}
                     (and retry-after-ms (nat-int? retry-after-ms))
                     (assoc :retry-after-ms retry-after-ms)

                     ;; doc 107 §107.6 (owner 2026-09-18): WHICH bound stopped a
                     ;; collection read, so the engine can answer with the same
                     ;; `:engine.limit/exceeded` shape the four EQL boundaries
                     ;; use. A bound's name and its two numbers are CONFIGURATION
                     ;; -- the same class as `:retry-after-ms`, and the same
                     ;; class as the numbers `gate/limit-exceeded` already
                     ;; publishes -- never a row, a cursor or a URL. Refusing
                     ;; without naming the bound tells a modeller «too many»,
                     ;; which names nothing he can act on (D2.9).
                     (and (map? limit) (keyword? (:limit limit)))
                     (assoc :limit (select-keys limit [:limit :max :actual])))))))

(defn failure?
  "x 是否是一个合法的连接器故障（ex-data 键面是契约的三个键，可选加
   `:retry-after-ms`）。
   测试与上层守卫用；它同时是「没多带键」的断言。"
  [x]
  (boolean
   (when (instance? clojure.lang.IExceptionInfo x)
     (let [d (ex-data x)]
       (and (map? d)
            (let [ks (set (keys d))]
              (and (contains? ks :error/category) (contains? ks :resolver-id)
                   (contains? ks :source-type)
                   ;; Closed: the three, optionally plus :retry-after-ms and
                   ;; :limit. Both are configuration-shaped numbers a caller
                   ;; cannot act without; nothing else may ride along.
                   (empty? (disj ks :error/category :resolver-id :source-type
                                 :retry-after-ms :limit))))
            (contains? categories (:error/category d)))))))

(defn category
  "故障的 `:error/category`，非连接器故障给 nil。"
  [x]
  (when (failure? x) (:error/category (ex-data x))))

(defn retry-after-ms
  "The upstream-declared wait before a retry is worth making, or nil."
  [x]
  (when (failure? x) (:retry-after-ms (ex-data x))))

(defn limit
  "The bound this failure hit -- `{:limit kw :max n :actual n}` -- or nil.
   The engine turns it into `:engine.limit/exceeded` (doc 107 §107.6)."
  [x]
  (when (failure? x) (:limit (ex-data x))))

(defn redacted
  "对外暴露截断函数（finding-v1 §5 同源），供连接器构造 finding 的 `:got`。"
  [v]
  (redact/redact v))

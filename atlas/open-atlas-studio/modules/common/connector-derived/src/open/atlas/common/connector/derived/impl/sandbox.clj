(ns open.atlas.common.connector.derived.impl.sandbox
  "SCI 沙箱：白名单、预解析、带预算的求值。

   ## 白名单**不够**——SCI 的特殊形式绕过它（实测，2026-08-24）

   README 裁定 ① 原本写着「未在 `:bindings` 里给出的符号一律解析不到，所以白名单在
   构造上成立」。**那句话是错的**，实测：

   | 表达式 | SCI 缺省行为 |
   |---|---|
   | `(slurp …)` / `(System/getenv …)` | 拒绝 ✓（符号解析不到） |
   | `(java.io.File. …)` | 拒绝 ✓（类名解析不到） |
   | **`(eval 1)`** | **可用** ✗ 而契约点名拒绝 `eval` |
   | **`(atom 1)` / `(swap! …)`** | **可用** ✗ 可变状态，破坏纯函数抽查 |
   | **`(loop [] (recur))` / `(while true …)`** | **可用** ✗ 无界迭代 |
   | **`(.length s)` / `(. s length)`** | **可用** ✗ 实例 interop，通往反射 |
   | `(read-string …)` / `(iterate …)` / `(range)` | **可用** ✗ |

   而且更深一层：**SCI 默认就带一份 clojure.core 子集**——`(map inc [1 2])` 直接可用,
   我们一个字节都没绑。所以 `:bindings` 里的白名单是在**加**，不是在**限**。

   ## 于是闸是一张 allowlist 扫描，跑在预解析时

   `scan` 遍历整棵语法树，**任何不在允许集合里的符号一律拒绝**。允许集合恰好三块：

   1. 契约白名单 + 我们挑的 clojure.core 纯函数子集（`whitelist`）；
   2. 一小组**有界**的特殊形式与宏（`permitted-forms`）——`if` `cond` `let` `->` 之类;
   3. 本 resolver `:input` 的 local name，以及表达式自己 `let` 出来的局部名。

   这样契约那句「白名单 v1 封闭，扩项即契约升版」**才真的成立**：它不再依赖
   「SCI 恰好没给某个东西」，而是**我们没写进允许集合的东西一律进不来**。

   **闸在发布时，不在运行时。** `validate-source` 里拒绝，比让它跑起来再靠超时兜住
   便宜得多——而超时**兜不住**（见 `evaluate` 的 docstring）。

   ## clojure.core 子集：逐条给理由，宁少勿多

   derived-v1 说白名单「v1 封闭，扩项即契约升版」，并允许「clojure.core 纯函数子集」。
   子集在下面 `core-subset` 里逐条注释——判据只有一条：**同输入同输出，且不碰进程
   之外的任何东西**。凡是我说不出这句话的，一律不给。

   ## `today` 从上下文取，不读时钟（README 裁定 ②）

   契约的 conformance 要「同输入同输出（纯函数性质抽查）」，而白名单里有 `today`。
   两句话不能同时为真。处置：`today` 返回**求值上下文**里的那个日期，上下文由调用方给。
   于是纯函数抽查对不含 `today` 的表达式逐字成立，含 `today` 的在**给定同一上下文时**
   也逐字成立——它仍是纯函数，只是多了一个显式入参。

   同则见 scope-projection-v1 §1「实现不得读取 latest、当前时间或池快照之外的状态」
   与越界写标记的 `:at` 必传：**读时钟的函数没法在测试里断言**。"
  (:require [clojure.string :as str]
            [sci.core :as sci])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)
           (java.time.temporal ChronoUnit)))

;; ---------------------------------------------------------------------------
;; 契约点名的自实现函数
;; ---------------------------------------------------------------------------

(defn- ->date
  "字符串或 LocalDate → LocalDate；其余（含 nil）给 nil。
   **不抛**：契约说「缺失输入绑 nil，表达式自行处理」，日期解析失败与输入缺失
   在表达式作者眼里是同一件事——都该由他自己判，而不是让整条求值炸掉。"
  [x]
  (cond
    (instance? LocalDate x) x
    (string? x) (try (LocalDate/parse ^String x) (catch DateTimeParseException _ nil))
    :else nil))

(defn- round2
  "四舍五入到两位小数。金额场景的高频需求，所以契约把它单列而不是让人写
   `(/ (round (* x 100)) 100.0)`——后者在表达式里既长又容易写错。"
  [x]
  (when (number? x)
    (/ (Math/round (* (double x) 100.0)) 100.0)))

(defn- sum
  "数值求和，**跳过非数值与 nil**。理由同 `->date`：输入缺失是合法的（契约），
   而一个 nil 让整条求值炸掉，会把「这一行某列为空」变成「这个属性算不出来」——
   两件事在结果上完全不同。"
  [& xs]
  (reduce + 0 (filter number? (flatten xs))))

(defn- days-between [a b]
  (let [a (->date a) b (->date b)]
    (when (and a b) (.between ChronoUnit/DAYS a b))))

(defn- before?* [a b]
  (let [a (->date a) b (->date b)]
    (when (and a b) (.isBefore a b))))

(defn- after?* [a b]
  (let [a (->date a) b (->date b)]
    (when (and a b) (.isAfter a b))))

;; ---------------------------------------------------------------------------
;; 白名单
;; ---------------------------------------------------------------------------

(def ^:private core-subset
  "clojure.core 的纯函数子集。**判据只有一条**：同输入同输出，且不碰进程之外的
   任何东西。每一条都要说得出这句话，说不出就不给。

   刻意**不给**的几类，理由记在这里免得下一个人「顺手补上」：
   - `atom` / `swap!` / `deref`：可变状态，破坏纯函数抽查；
   - `re-pattern`：正则匹配本身是纯的，但让表达式**构造**模式就开了一条绕过超时
     预算的路（灾难性回溯在编译期就能吃掉预算）；
   - `iterate` / `range` / `repeat` / `cycle`: SEQUENCE GENERATORS -- they let
     the expression decide its own workload instead of the input. `map` /
     `reduce` / `filter` are bounded on their own (they walk the collection they
     are given) and used to be refused together with the generators because the
     two combined can build an unbounded sequence. Semantic contract 2.0 §2.2
     hands the expression an already-evaluated vector (nested input), and an
     aggregating resolver (an order total) cannot be written without walking it,
     so the TRAVERSALS are admitted and the GENERATORS stay refused: with no
     generator in the allowlist, a traversal's workload is still the input's;
   - `read-string`：`eval` 的近亲，而契约点名拒绝 `eval`。"
  {;; 算术与比较——纯，且工作量由输入决定
   '+ + '- - '* * '/ / 'quot quot 'rem rem 'mod mod
   'inc inc 'dec dec 'max max 'min min
   '= = 'not= not= '< < '<= <= '> > '>= >=
   'zero? zero? 'pos? pos? 'neg? neg?
   ;; 判定与转换——纯
   'nil? nil? 'some? some? 'number? number? 'string? string? 'boolean? boolean?
   'not not 'boolean boolean
   'str str 'long long 'double double 'int int
   ;; 集合上的只读问询——纯；`if`/`when`/`and`/`or`/`cond` 是特殊形式，无需绑定
   'count count 'empty? empty?
   ;; Collection traversal and aggregation (Semantic contract 2.0 §2.2,
   ;; derived-v1 "Semantic 2.0 输入形态"): pure, and the workload is the length
   ;; of the input vector -- no sequence generator is in the allowlist.
   'reduce reduce 'map map 'filter filter 'first first 'apply apply 'get get})

(def ^:private contract-bindings
  "derived-v1 的封闭白名单，**逐字照抄那张表**。一个都不许多给。"
  {'abs          (fn [x] (when (number? x) (Math/abs (double x))))
   'round        (fn [x] (when (number? x) (Math/round (double x))))
   'round2       round2
   'ceil         (fn [x] (when (number? x) (Math/ceil (double x))))
   'floor        (fn [x] (when (number? x) (Math/floor (double x))))
   'sqrt         (fn [x] (when (number? x) (Math/sqrt (double x))))
   'pow          (fn [x y] (when (and (number? x) (number? y)) (Math/pow (double x) (double y))))
   'upper        (fn [s] (some-> s str str/upper-case))
   'lower        (fn [s] (some-> s str str/lower-case))
   'trim         (fn [s] (some-> s str str/trim))
   'blank?       (fn [s] (str/blank? (when s (str s))))
   'format       (fn [fmt & args] (apply clojure.core/format fmt args))
   'sum          sum
   'date         ->date
   'days-between days-between
   'before?      before?*
   'after?       after?*
   ;; `today` 的**真实实现按次注入**（见 `ctx-for`）——它从求值上下文取，不读时钟
   ;; （README 裁定 ②）。这里给一个占位，只为让它进 `whitelist`：契约那张表里有它,
   ;; 而白名单与那张表要逐字对得上。
   'today        (fn [] nil)})

(def whitelist
  "白名单的**符号集合**。测试拿它与契约那张表逐字对账——这一条挡的是
   「顺手多给一个」，而多给一个在 derived-v1 里是**契约升版**级别的改动。"
  (into (set (keys contract-bindings)) (keys core-subset)))

;; ---------------------------------------------------------------------------
;; 禁止形式：白名单挡不住的那一半（见 ns 抬头那张实测表）
;; ---------------------------------------------------------------------------

(def contract-symbols
  "derived-v1 那张表**逐字**点名的符号（不含我们挑的 clojure.core 子集）。
   单独导出供测试对账——多给一个在契约里是**升版**级别的改动。"
  (set (keys contract-bindings)))

(def permitted-forms
  "允许的特殊形式与宏。**封闭**，判据一条：**有界**——它的工作量由输入决定,
   而不是由它自己决定。

   刻意**不给**的，理由记在这里免得下一个人「顺手补上」：
   - `loop`/`recur`/`while`/`for`/`doseq`/`dotimes`、`iterate`/`range`/`repeat`/`cycle`
     ——无界迭代。这一类尤其要在**发布闸**上拦：超时**停不下** `recur` 循环（实测,
     见 `evaluate`），所以「能写出来但会超时」实际是「能写出来并且永久烧掉一个线程」;
   - `fn`/`def`/`defn`/`letfn`——它们让表达式变成**程序**，而契约要的是**表达式**;
   - `try`/`catch`——求值失败已经有语义了（属性缺席 + finding），让表达式自己吞掉
     异常只会把失败藏起来。"
  '#{if if-not when when-not cond condp case
     and or
     let if-let when-let
     -> ->> some-> some->>
     quote})

(def ^:private binding-forms
  "会引入局部名的形式——扫描时要把它们绑出来的名字算进允许集合。"
  '#{let if-let when-let})

(defn- take-nth-even
  "取向量的偶数位（0、2、4…）——`let` 的绑定名都在那儿。"
  [v]
  (keep-indexed (fn [i x] (when (even? i) x)) v))

(defn- bound-locals
  "语法树里由 `let` 一类形式绑出来的局部名。
   只取**偶数位**（`[a 1 b 2]` → `a` `b`）；解构不支持（那需要走进 map/vector 模式,
   而表达式里用不着——`:input` 已经把值摊平成局部名了）。"
  [form]
  (into #{}
        (comp (filter #(and (seq? %) (contains? binding-forms (first %))))
              (mapcat (fn [[_ bs & _]] (when (vector? bs) (take-nth-even bs))))
              (filter simple-symbol?))
        (tree-seq coll? seq form)))

(defn- never-a-local?
  "这个符号**一定不可能**是 resolver 的 input local name，也不可能是 `let` 局部名：
   带命名空间限定（`clojure.java.io/file`、`System/getenv`）、以 `.` 开头（`.length`）、
   以 `.` 结尾（`File.`）。

   为什么需要它：`validate-source` 的 SPI 签名只有 `[source-config]`——**它看不到
   resolver**，于是看不到 inputs，也就无法做完整的 allowlist（每个 input local name
   在它眼里都是「不被允许的符号」）。这一条让写入端至少能拒掉**确定越界**的那些,
   完整的 allowlist 留给带 inputs 的那一路。"
  [sym]
  (let [n (name sym)]
    (or (qualified-symbol? sym)
        (and (> (count n) 1) (str/starts-with? n "."))
        (and (> (count n) 1) (str/ends-with? n ".")))))

(defn scan-without-inputs
  "**弱扫描**：只拒绝「一定不可能是 input local name」的符号。给
   `validate-source` 的 1-arity 用——它按 SPI 签名看不到 resolver。

   **这不是 allowlist**，别把它当成完整的闸：`slurp` 在这里过得去（它长得像一个
   input local name）。完整的闸是 `scan`，它需要 inputs，跑在 `compile-source`
   （装配期）与拿得到 resolver 的校验路径上。

   这条分界写在这里，因为「写入端校验过了」是最容易产生的错觉。"
  [form]
  (first (filter #(and (symbol? %) (never-a-local? %))
                 (filter symbol? (tree-seq coll? seq form)))))

(defn scan
  "语法树 → 第一个不被允许的符号，没有则 nil。

   **allowlist**：允许集合 = `whitelist` ∪ `permitted-forms` ∪ `inputs`（resolver 的
   input local name）∪ 表达式自己 `let` 出来的局部名。其余一律拒绝。

   为什么是 allowlist 而不是黑名单：SCI 默认带一份 clojure.core 子集（见 ns 抬头）,
   黑名单要枚举「它给了什么而我们不要」——那张表**没有人维护得住**，漏一个就开口子,
   而漏掉哪一个不会有人发现。allowlist 的失败方向相反：漏给一个，作者当场看到拒绝。"
  ([form] (scan form nil))
  ([form inputs]
   (let [allowed (into (into whitelist permitted-forms)
                       (concat (map #(symbol (name %)) inputs)
                               (bound-locals form)))]
     (first (remove allowed
                    (filter simple-symbol? (tree-seq coll? seq form)))))))

;; ---------------------------------------------------------------------------
;; 预解析与求值
;; ---------------------------------------------------------------------------

(defn- short-phrase
  "异常消息截到 200 字。**只留短语**——principles 要求故障不携带业务数据，
   而一个完整的堆栈或驱动原文里什么都可能有。"
  [t]
  (let [m (or (ex-message t) (.getName (class t)))]
    (subs m 0 (min 200 (count m)))))

(defn parse
  "`:expr` 字符串 → `{:form …}` 或 `{:error 短语}`。

   **只解析，不求值**（derived-v1：「表达式在编译期预解析，解析失败即校验失败」）。
   与 `scan` 一起构成这个连接器唯一真正的闸门：它挡的是**发布了一个永远算不出来
   的属性**。"
  [expr]
  (try
    {:form (sci/parse-string (sci/init {}) expr)}
    (catch Throwable t {:error (short-phrase t)})))

(defn- ctx-for
  "每次求值现造一个 SCI 上下文：白名单 + 本次的输入绑定 + `today`。

   **不复用一个全局 ctx**：绑定随每次调用变，而共享可变状态是纯函数抽查最容易
   翻车的地方（上一行的值渗到下一行）。造 ctx 的开销远小于一次数据库往返。

   `today` 在这里被**真实实现覆盖**——`contract-bindings` 里那个只是占位，为了让它
   进 `whitelist`（契约那张表里有它）。真实值来自**求值上下文**，不读时钟
   （README 裁定 ②）。"
  [bindings today]
  (sci/init {:bindings (merge core-subset contract-bindings
                              {'today (fn [] today)}
                              bindings)}))

(defn evaluate
  "跑一次求值 → `{:value v}` / `{:error 短语}` / `{:timeout true}`。

   ## 超时是**预算，不是保证**（README 裁定 ④，2026-08-24 更正）

   预算是逐次求值的，超时只让这一个输出属性缺席、不拖垮同实体其他属性——这一半成立。

   **但它停不下正在跑的那一个。** 实测：`future-cancel` 对 `(loop [] (recur))`
   返回 `true`，而 800ms 之后那个线程**仍在烧**。所以：

   - 无界迭代由 `scan` 在**预解析时**拒掉（那才是真闸），
   - 超时只兜「有界但病态」的工作量（比如 `(pow 2 (pow 2 30))`），
   - 承载线程是 **daemon**，于是一个失控的求值**不会挂住 JVM 退出**——第一版用
     `future`（非 daemon 池）就挂住了，测试跑到 `shutdown-agents` 停在那里。

   这段实测记在这里，因为「加个超时就安全了」是最容易写下的那句错话。

   `:expr` 文本可以进错误短语（它是**配置**不是数据），**求值结果与输入行值一律不进**
   ——principles「source 失败与日志不携带业务数据」。这条区分写在这里，免得下一个人
   把「短语里带了 `:expr`」当漏洞修掉。"
  [form bindings today budget-ms]
  (let [p (promise)
        t (doto (Thread.
                 ^Runnable
                 (fn []
                   (deliver p (try
                                {:value (sci/eval-form (ctx-for bindings today) form)}
                                (catch Throwable t {:error (short-phrase t)}))))
                 "derived-eval")
            ;; **daemon**：失控的求值不许挂住 JVM 退出（见上）。
            (.setDaemon true)
            (.start))
        r (deref p budget-ms ::timeout)]
    (if (= ::timeout r)
      (do (.interrupt t)                ; 礼貌地试一次；停不下也不再等（预算不是保证）
          {:timeout true})
      r)))

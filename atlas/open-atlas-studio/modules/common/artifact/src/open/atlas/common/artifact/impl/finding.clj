(ns open.atlas.common.artifact.impl.finding
  "Finding v1（docs/contracts/finding-v1.md）在制品域的落法。

   `:code` 用 `artifact.*` 域，**自己的注册表**——finding-v1 §3 明说
   「后续模块续接：`artifact.*`、`pool.*`、`connector.*`」，engine 的
   `:engine.*` 也是同样的处理（各自注册表，不与方言共用）。

   §2 的封闭 schema 在这里手写而不引 malli：本模块的 deps 只有 clojure +
   dialect，而 `open.atlas.common.dialect/finding?` 校验的是**方言的**
   code 注册表，对 `artifact.*` 一律判否。键面与语义逐条对齐 finding-v1 §2。"
  (:require [clojure.string :as str]))

(def codes
  "已发布 `artifact.*` code 注册表（finding-v1 §6）。一经发布不改名。
   产出未注册 code 是实现 bug（测试兜底）。"
  #{;; artifact.envelope —— content.edn 信封结构（D4.2 顶层封闭，只允许三个键）
    :artifact.envelope/parse-error
    :artifact.envelope/unknown-key            ; D4.6 未知键一律拒绝，不静默忽略
    :artifact.envelope/missing-key
    :artifact.envelope/invalid-shape
    :artifact.envelope/derived-key-supplied   ; 推导出来的键被当成入参传进来
    ;; artifact.verification —— 装载校验的声明（D4.3/D4.11）
    :artifact.verification/undeclared-skip
    :artifact.verification/contradictory-declaration
    ;; artifact.compat —— 格式版本协商（D4.7）
    :artifact.compat/unsupported-format-version   ; v1 装载方只支持版本 1
    :artifact.compat/format-version-mismatch      ; content.edn 与 manifest.edn 回显不一致
    ;; artifact.integrity —— 三种身份的比对（D4.3）
    :artifact.integrity/missing-file              ; 部分写入的版本（D4.8）
    :artifact.integrity/content-hash-mismatch
    :artifact.integrity/artifact-digest-mismatch
    ;; artifact.reference —— 引用清单（D4.4）
    :artifact.reference/invalid-shape
    :artifact.reference/duplicate                 ; 同限定名与版本重复，不靠后者覆盖
    :artifact.reference/missing                   ; 装载闭包里缺这一份（D4.1）
    :artifact.reference/content-hash-mismatch     ; 上游被人动过——可检测事件（D4.3）
    ;; artifact.model —— `:objects` 交给方言 reader 后的结论（D4.6）
    :artifact.model/rejected
    ;; artifact.store —— 落盘边界（D4.8）
    :artifact.store/path-escape
    :artifact.store/version-exists
    ;; D4.12：staging 与目标不在同一文件系统 → 原子移动不可用 → **拒绝**。
    ;; 这是配置错误，不是运行时降级（退回普通 move 会让「制品架上的东西一定
    ;; 完整」失去依据，而没有任何人会知道）。
    :artifact.store/atomic-move-unavailable})

(def ^:private required-keys #{:code :severity :path :message})
(def ^:private optional-keys #{:expected :got :refs :files})
(def ^:private severities #{:error :warning :info})

(defn valid?
  "finding-v1 §2 封闭 schema。finding 自身也被校验——用同一套机器。"
  [x]
  (and (map? x)
       (every? #(contains? x %) required-keys)
       (every? #(or (required-keys %) (optional-keys %)) (keys x))
       (contains? codes (:code x))
       (contains? severities (:severity x))
       (vector? (:path x))
       (string? (:message x))
       (or (not (contains? x :refs))
           (and (vector? (:refs x)) (every? string? (:refs x))))
       (or (not (contains? x :files))
           (and (vector? (:files x)) (every? string? (:files x))))))

(defn make
  "构造并断言。opts 只收 :expected :got :refs :files。"
  [code severity path message & {:as opts}]
  (let [f (merge {:code code :severity severity :path path :message message} opts)]
    (assert (valid? f) (str "invalid finding: " (pr-str f)))
    f))

(defn error
  "本模块的拒绝几乎全是 `:error`——D4.6「装载失败必须点名哪个文件、哪条规则、
   哪个对象」，因此 `:files` 与 `:path` 是常参而不是可选装饰。"
  [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn redact
  "不泄漏截断（finding-v1 §5）。与 dialect 的同名函数同语义；这里不跨模块
   引 `dialect/redact` 是因为制品见到的值域不同（字节数组、路径），而
   **路径本身**在制品里恰恰是必须点名的线索（D4.6），不能被截断掉。"
  [v]
  (cond
    (string? v) (str "string(" (count v) " chars)")
    (map? v)    (str "map(" (count v) " keys)")
    (coll? v)   (str "coll(" (count v) " items)")
    :else       v))

(defn sort-findings
  "确定性顺序：按 :path 字典序、同 path 按 :code（finding-v1 §6）。"
  [findings]
  (vec (sort-by (juxt (comp pr-str :path) (comp str :code)) findings)))

(defn errors? [findings]
  (boolean (some #(= :error (:severity %)) findings)))

(defn short-name
  "hash 在消息里只留前缀——完整值走 `:expected`/`:got`。"
  [h]
  (if (and (string? h) (> (count h) 19)) (str (subs h 0 19) "…") (str h)))

(defn qualified-name
  "`workspace/graph@version`——引用点名的统一写法（D3.10 限定名两段都写）。"
  [{:keys [workspace graph version]}]
  (str workspace "/" graph "@" version))

(defn blank-str? [s] (or (not (string? s)) (str/blank? s)))

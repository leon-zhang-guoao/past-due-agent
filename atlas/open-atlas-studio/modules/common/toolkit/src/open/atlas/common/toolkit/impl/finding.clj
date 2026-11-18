(ns open.atlas.common.toolkit.impl.finding
  "toolkit 的 finding 注册表与构造器。

   形状照抄 finding-v1 §2（封闭键面、severity 三档、:path、不泄漏），
   只有 :code 的域不同——finding-v1 §3 明说「后续模块续接」，本模块续 `toolkit.*`。

   **为什么自带一份注册表**：`open.atlas.common.dialect/finding-codes` 是 B0 内核
   的常量，只登记 dialect.*。同 studio/pool 的 `pool.*`（pool/impl/finding.clj 的
   ns 注释），各模块登记自己的域，谁也不去改别人的表。

   **截断复用 `dialect/redact`**——finding-v1 §5 明说要复用同一个截断函数。
   工具的入参里会坐着 source 条目（`:jdbc_url`），拒绝里一个字节都不许出现。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布 :code 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。

   本 ns **只判工具面自身的事**：名字认不认识、入参形状对不对。工具做的事本身
   （方言结构、池的引用完整性）一律回落到 dialect.* / pool.*——「不写第二个
   schema」（pool README 裁定 ②）在这一层同样成立。"
  #{;; ── toolkit.tool —— 名字与挂载 ────────────────────────────────────
    ;; 能力过滤掉的工具走的也是这一条：principles「两把钥匙」要的是 dispatch
    ;; **不认识**它，而不是「认识但拒绝」——两者对调用方必须逐字不可区分。
    :toolkit.tool/unknown
    ;; ── toolkit.args —— 入参形状 ──────────────────────────────────────
    :toolkit.args/malformed               ; 入参不是 map / 键面不封闭
    :toolkit.args/missing                 ; 必填参数缺席
    :toolkit.args/invalid})               ; 类型或枚举不符

(defn make
  "构造并断言（finding-v1 §2 封闭键面）。opts 只收 :expected :got :refs :files。"
  [code severity path message & {:as opts}]
  (assert (contains? codes code) (str "unregistered finding code: " code))
  (assert (contains? #{:error :warning :info} severity))
  (assert (vector? path))
  (assert (string? message))
  (assert (every? #{:expected :got :refs :files} (keys opts))
          (str "finding key set is closed: " (pr-str (keys opts))))
  (merge {:code code :severity severity :path path :message message} opts))

(defn valid? [f]
  (and (map? f)
       (empty? (remove #{:code :severity :path :message :expected :got :refs :files} (keys f)))
       (contains? codes (:code f))
       (contains? #{:error :warning :info} (:severity f))
       (vector? (:path f))
       (string? (:message f))))

(def redact
  "不泄漏截断（finding-v1 §5）——复用 B0 导出的那一个函数，不另写。"
  dialect/redact)

(ns open.atlas.common.candidates.impl.finding
  "候选模块的 finding 注册表与构造器。

   形状**照抄 finding-v1 §2**（封闭键面、severity 三档、`:path`、不泄漏），
   只有 `:code` 的域不同：finding-v1 §3 说「后续模块续接：artifact.* / pool.*
   / connector.*」——本模块续的是 `candidate.*`。

   为什么不把 code 直接叫 `import.candidate/*`（B2 那三条的原名）：`import.*`
   是**一次性转换器**的域，让 common 姓 `import.` 等于把 common 的身份挂在一个
   一次性工具上。这个判断在转换器退休（2026-08-24，fixtures/demo/README.md）之后
   更站得住：域没了，`candidate.*` 还在。
   B2 那三个 code 当年**一个字都不改**——finding-v1 §3「一经发布不改」——
   由 legacy-import 在边界上原样贴回去；那层边界随它一并删除，
   见 `git log -- modules/legacy-import`。

   **截断函数复用内核导出的 dialect/redact**（finding-v1 §5 明说要复用同一个
   截断函数，不许各写一份）。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布 :code 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。
   三条对应三张会「有话要说」的候选清单——组候选没有 code：它要么出候选，
   要么因为撞名而不出，两种结局都不需要 owner 另外知道点什么。"
  #{:candidate/type-mismatch            ; D2.27：编码或类型不一致 → 不出候选
    :candidate/curated-signoff-needed   ; D2.13：无锚 list resolver 待签
    :candidate/sensitivity-unconfirmed}); D5.1：疑似需要授权、待 owner 裁

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
       (string? (:message f))
       (or (nil? (:refs f)) (and (vector? (:refs f)) (every? string? (:refs f))))
       (or (nil? (:files f)) (and (vector? (:files f)) (every? string? (:files f))))))

(def redact
  "不泄漏截断（finding-v1 §5）——复用 B0 导出的那一个函数，不另写。"
  dialect/redact)

(defn sort-findings
  "确定性顺序（finding-v1 §6）：按 :path 字典序、同 path 按 :code。"
  [findings]
  (vec (sort-by (juxt (comp pr-str :path) (comp str :code) :message) findings)))

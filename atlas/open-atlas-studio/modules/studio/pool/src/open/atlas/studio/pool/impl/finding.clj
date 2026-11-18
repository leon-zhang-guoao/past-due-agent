(ns open.atlas.studio.pool.impl.finding
  "池的 finding 注册表与构造器。

   形状**照抄 finding-v1 §2**（封闭键面、severity 三档、:path、不泄漏），
   只有 :code 的域不同：finding-v1 §3 明说「后续模块续接：artifact.* /
   pool.* / connector.*」——B4 续的是 `pool.*`。

   为什么自带一份注册表而不塞进内核：`open.atlas.common.dialect/finding-codes`
   是 B0 内核的常量，只登记 dialect.*。让 studio 去改内核的注册表，就把
   D1.8 的依赖方向反过来了。B2 的 `import.*` 已经按这条路走过一遍
   （legacy_import/impl/finding.clj 的 ns 注释），池照同一条。

   **截断函数复用内核导出的 dialect/redact**——finding-v1 §5 明说要复用同一个
   截断函数，不许各写一份。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布 :code 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。
   域 = 产出它的判定层。池**只判池特有的事**，结构校验一律回落到
   `dialect/read-dialect`（README 裁定 ②「不写第二个 schema」），所以这张表里
   没有任何一条是「这个键不合法」——那种 code 全部姓 dialect.*。"
  #{;; ── pool.action —— action 请求自身的形状与寻址 ─────────────────────
    :pool.action/malformed                ; action map 键面封闭 / 必填键缺席
    :pool.action/unknown-action           ; :action 不是 create|update|delete
    :pool.action/unknown-type             ; :type 不是池认识的对象类型
    :pool.action/invalid-id               ; id 的形状与该类型的身份约定不符
    :pool.action/unknown-entry-key        ; entry 带了类型契约之外的键（只有 source 有 open keys）
    :pool.action/id-taken                 ; create 撞上已有 id（§7.3：没有公开 upsert）
    :pool.action/unknown-entity           ; update/delete 指向不存在的对象（同上）
    ;; ── pool.workspace —— 归属是存下来的一列（D3.22）───────────────────
    ;; 与 `pool.action/*` 分开：action 的形状对、寻址也对，错的是**这个
    ;; workspace 不是这个对象的主人**——两者的处置不同（改请求 vs 换个人来改）。
    :pool.workspace/mismatch              ; update/delete 的 workspace 与库里盖的不符
    ;; ── pool.migrate —— 一次性迁移判不出的事 ───────────────────────────
    :pool.migrate/unowned                 ; stamp-workspace 判不出某个对象的归属，逐个点名
    ;; ── pool.conflict —— 对象级乐观并发（README 裁定 ④）────────────────
    :pool.conflict/revision-mismatch      ; expected_revision 与实际不符
    ;; ── pool.reference —— 池特有的引用完整性 ───────────────────────────
    :pool.reference/inbound               ; delete 的目标仍被别的对象引用，逐个点名
    ;; ── pool.dialect —— 投机事务过不了 B0 ──────────────────────────────
    :pool.dialect/rejected                ; 摘要行；同批返回的 dialect.* finding 是明细
    ;; **只报这次写引入的那些**（studio/graph README 裁定 ⑤ 的差集）。写之前池里
    ;; 就有的脏走下面这条 `:warning` 随行报出：它是真的，但不是这次写的责任。
    ;; 两条 code 分开，是因为下游对它们的处置完全不同——一条挡住动作，一条只是
    ;; 让人看见池里有活要干。
    :pool.dialect/pre-existing            ; :warning——写之前就在的 dialect error
    ;; ── pool.candidate —— 候选签字通道（B4.4，§7.3 待钉第 2 条）─────────
    :pool.candidate/unknown               ; 裁决点的候选此刻不在池挖出来的清单里
    :pool.candidate/already-confirmed     ; 同一条候选已经有确认记录 —— 记录不可变，不许再签一次
    :pool.candidate/id-required           ; 签词条却不给概念 ID（D2.26：机器不许命名概念）
    :pool.candidate/malformed             ; 裁决自身的形状/键面/词表不对
    ;; ── pool.provenance —— 可信出处（8.4 P1b，impl/audit.clj）───────────
    ;; 只有一条：出处是服务端构造的，所以它错了是**宿主的 bug**，不是调用方的
    ;; 输入错误。分成几条 code 会让人以为其中某几种是客户端能修的。
    :pool.provenance/malformed            ; 出处键面/入口词表/字段类型不对
    ;; ── pool.export —— 池级导出/导入的信封 ─────────────────────────────
    :pool.export/malformed                ; 导出信封形状非法
    :pool.export/version-unsupported      ; :pool/version 不是 1
    :pool.export/duplicate-object})       ; 同一 [type id] 在导出里出现两次

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

(defn valid?
  "本 ns 产出的 finding 是否合法（finding-v1 §2；code 须在本 ns 注册表）。
   同批返回的 dialect.* 明细用 `dialect/finding?` 判，不进这里。"
  [f]
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

(defn errors? [findings] (boolean (some #(= :error (:severity %)) findings)))

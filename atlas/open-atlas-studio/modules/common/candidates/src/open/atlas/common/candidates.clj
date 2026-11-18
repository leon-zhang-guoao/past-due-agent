(ns open.atlas.common.candidates
  "**机器提议**的唯一对外接口。跨模块只许 require 本 ns；
   `open.atlas.common.candidates.impl.*` 视为私有（clj-kondo `:discouraged-namespace`
   执法，同 B0 的 `open.atlas.common.dialect`）。

   它只回答一句话：**「这几列名字一样，可能是一回事。」**
   它不回答「这个概念叫什么」——那句话机器说不出口（[D2.26](docs/design/20-dialect.md)
   禁止拿历史字段名当概念名），所以按名字频次聚类出的候选 `:term` 一律是 `nil`,
   挂一条 `:id-required-from-owner true`。这就是「机器提议 / owner 处置」那条分界。

   **今天只有一个调用方**：B4 的池（从池的内容挖）。第二个——B2 的一次性
   转换器——随转换器本身在 2026-08-24 退休了（fixtures/demo/README.md）。
   模块仍然单独立在这里，不是因为还有两个调用方，是因为池要它，而池是 studio、
   本 ns 是 common：D1.8 的依赖方向不许 common 反过来依赖 studio。
   **只做提议不做处置**：签字的解析、合并与拒绝留在调用方。"
  (:require [open.atlas.common.candidates.impl.finding :as finding]
            [open.atlas.common.candidates.impl.propose :as propose]))

;; ---------------------------------------------------------------------------
;; 提议
;; ---------------------------------------------------------------------------

(defn candidates
  "方言模型 → `{:terms … :curated … :findings …}`。
   **不改 model**——这是本模块的全部意义。"
  [model]
  (propose/candidates model))

(def term-candidates   "D2.26：词条候选 + D2.27 闸门的 findings。" propose/term-candidates)
(def curated-candidates "D2.13：无锚 list resolver 的 `:curated?` 待签清单。" propose/curated-candidates)

;; ---------------------------------------------------------------------------
;; 候选身份：**签字通道的键**
;; ---------------------------------------------------------------------------

(defn candidate-key
  "一条候选的**稳定身份**：`[种类 键]`。

   为什么需要它：B2 的签字文件当年按各自的 `:signoff-key` / `:local-name` / id 寻址,
   四张清单四套寻址法——文件是人写的，人看得懂上下文。`confirm_candidates` 收到的
   是一次 action 的两个参数，**没有上下文**，所以身份必须自带种类。

   两种（`[:sensitive …]` 随访问档位退役，owner 裁定 2026-08-27）：
   - `[:term \"<local name>\"]`
   - `[:curated \"<resolver id>\"]`"
  [kind k]
  [kind (if (keyword? k) (binding [*print-namespace-maps* false] (pr-str k)) (str k))])

(defn keyed
  "`candidates` 的产物 → `{候选身份 候选行}`，确定性顺序（sorted-map）。
   签字通道拿它做「这条候选存不存在」的唯一判据——存不存在与长什么样是同一份数据,
   两份就会分叉。"
  [cands]
  (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
        (concat
         (for [c (:terms cands)]        [(candidate-key :term (:local-name c)) c])
         (for [c (:curated cands)]      [(candidate-key :curated (:signoff-key c)) c]))))

(def kinds
  "两种候选，固定顺序。报告与工具入参的枚举都取这一份。"
  [:term :curated])

;; ---------------------------------------------------------------------------
;; 词表常量（摆在接口上，不许各写一份 —— CLAUDE.md）
;; ---------------------------------------------------------------------------

(def audit-concepts        "审计族白名单：历史 local name → **概念名**。" propose/audit-concepts)
(def term-carried-keys
  "词条**携带**的语义字段：绑定之后本地再写任何一个都是 schema 拒绝（D2.26）。
   签字通道要按它把本地键摘掉（B4 的 `confirm_candidates`；B2 的 `sign-terms`
   当年摘的是同一批键），所以它摆在接口上——**一条规则一处实现**（CLAUDE.md）。"
  [:description :format :label :synonyms :values])


;; ---------------------------------------------------------------------------
;; finding
;; ---------------------------------------------------------------------------

(defn finding? [x] (finding/valid? x))

(def finding-codes
  "全部已发布 `candidate.*` code 的注册表（finding-v1 §6）。"
  finding/codes)

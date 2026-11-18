(ns open.atlas.studio.graph.impl.finding
  "本模块的 finding 注册表与构造器（[finding-v1](../../../../../../../../docs/contracts/finding-v1.md)）。

   形状照抄 finding-v1 §2（封闭键面、severity 三档、`:path`、不泄漏），只有 `:code`
   的域不同：§3「后续模块续接」——B5a 续的是 `workspace.*` 与 `graph.*`。

   为什么自带注册表而不塞进内核：`open.atlas.common.dialect/finding-codes` 是 B0 的
   常量，只登记 `dialect.*`；让 studio 去改内核的注册表就把 D1.8 的依赖方向反过来了。
   B2 的 `import.*`、B4 的 `pool.*` 都走的这一条。

   **截断复用内核导出的 `dialect/redact`**（finding-v1 §5 明说同一个截断函数，
   不许各写一份）。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布 `:code` 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。

   域 = 产出它的判定层。**这里没有任何一条叫「越界写」**——D3.4 的越界写不是拒绝,
   是留痕（README 裁定 ④）。它若出现在这张表上，就说明有人把检测改回了预防。"
  #{;; ── workspace.registry —— workspace 自身（D3.3）────────────────────
    :workspace.registry/malformed          ; 请求键面封闭 / 必填键缺席 / 角色不在词表
    :workspace.registry/name-taken         ; workspace 名已登记
    :workspace.registry/unknown            ; 点名一个没登记过的 workspace
    ;; ── rename (E4 §1b.3, owner ruling 2026-09-05) ────────────────────
    :workspace.registry/rename-unportable  ; 新名不是可移植路径段（artifact-v1 §8）——warning
    :workspace.registry/rename-shelf       ; 货架目录移动失败，改名已回滚
    :workspace.registry/last-owner          ; 摘掉最后一个 owner —— 那会让 workspace 无人可管
    ;; ── graph.registry —— graph 登记与限定名（D3.10）───────────────────
    :graph.registry/malformed              ; 请求形状 / 裸名 / 菜谱形状
    :graph.registry/name-taken             ; 同一 workspace 内裸名重复
    :graph.registry/unknown                ; 点名一个没登记过的限定名
    :graph.registry/selector-not-owned     ; 菜谱 selector 不在本 workspace 的地盘里（D3.9）
    :graph.registry/default-query-invalid  ; Query Studio 开场查询的形状不对（D3.20）
    :graph.registry/default-graph-foreign  ; 落地图不属于本 workspace（D3.17 / D3.21）
    ;; ── package —— 发布名（doc 104 §8.17.7 D，owner 裁定 2026-09-06）─────
    ;; 域是 `package.*` 而不是 `graph.registry.*`：判的不是「这张图登记得合不合
    ;; 法」，而是「它能不能出厂」，而导入端按 `package.*` 分流同一族拒绝。
    :package/name-required                 ; 个人 workspace 的图名里没有一段能当发布名用的字符（2026-10-01 起，没起名不再拒绝，走默认名）
    ;; ── graph.access —— team visibility + graph editing ────────────────
    :graph.access/malformed                 ; access row or principal shape is invalid
    :graph.access/not-workspace-member      ; an outsider cannot receive graph access
    :graph.access/redundant                 ; the recipient's workspace role already edits it
    ;; ── graph.grant —— D3.14 授权 ──────────────────────────────────────
    :graph.grant/malformed                 ; 请求形状 / 档位不在词表
    :graph.grant/duplicate                 ; 同一 [被授权方 graph] 已有授权
    :graph.grant/unknown                   ; 撤销一条不存在的授权
    :graph.grant/not-authorized          ; 菜谱引用一张没授权给本 workspace 的 graph
    ;; ── scope —— scope projection v1 的拒绝（B5a.2）────────────────────
    ;; 契约 §2 逐字点名的五条，**名字一个字母都不许改**：下游按 code 分流,
    ;; 改名等于把一条已发布的拒绝换成一条没人认识的。
    :scope/unclosed-input                  ; §2.4 候选的 input 不在可用属性里
    :scope/mixed-output                    ; §2.4 同时产出选中与未选中属性
    :scope/unclosed-link                   ; §2.6 link 一端缺失
    :scope/unclosed-metric                 ; §2.8 metric 的 dimension 缺失
    :scope/object-conflict                 ; §2.9/§2.10 同身份内容不同
    ;; 契约点名了判定但没给 code 的几处，域同为 `scope.*`：
    :scope/unknown-member                  ; §1 菜谱点名了池快照里没有的对象（D3.19）
    :scope/unclosed-source                 ; §2.5 source 引用不存在
    :scope/unclosed-registry               ; §2.7 注册表悬空引用
    ;; D2.29 改名（owner 裁定 2026-09-02）：判定没变，被判的对象从 namespace 上的
    ;; navigation 变成了 join 属性，`:scope/unclosed-navigation` 里的那个词已经
    ;; 没有指称对象了。
    :scope/unclosed-join                   ; join 属性的 :target 不在投影里（契约未覆盖，见 impl.projection）
    ;; ── graph.report —— 发现面报表（B5a.3，裁定 ⑥）────────────────────
    ;; 报表**本身**不是拒绝：它答的是「归谁 / 谁引用 / 进了哪些图」。这条 code
    ;; 只用在**问题本身问不通**时（对象寻址形状不对、类型不认识）——报表宁可
    ;; 结构化地说「你问的这个东西我读不出来」，也不能返回一份空清单，
    ;; 因为空清单与「答案就是没有」在报表上长得一模一样。
    :graph.report/malformed
    ;; ── graph.export —— 治理数据的导出信封（裁定 ①：跟着池走）──────────
    :graph.export/malformed
    :graph.export/version-unsupported})

(defn make
  "构造并断言（finding-v1 §2 封闭键面）。opts 只收 `:expected :got :refs :files`。"
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
  "不泄漏截断（finding-v1 §5）—— B0 导出的那一个函数，不另写。"
  dialect/redact)

(defn sort-findings
  "确定性顺序（finding-v1 §6）：按 `:path` 字典序、同 path 按 `:code`。"
  [findings]
  (vec (sort-by (juxt (comp pr-str :path) (comp str :code) :message) findings)))

(defn errors? [findings] (boolean (some #(= :error (:severity %)) findings)))

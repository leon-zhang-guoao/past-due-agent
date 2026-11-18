(ns open.atlas.runtime.graph.impl.finding
  "本模块的 finding 构造与已发布 `:code` 注册表。域是 `deploy.*`。

   **回落的 code 原样透传，不重贴一层皮**（同 studio/version 的 `finding?`）：
   装载一份制品的拒绝有一半来自 `artifact.*`（digest 对不上、闭包缺件、
   reader 拒绝），把它们重新包成 `deploy.*` 会让下游按 code 分流时漏掉一半,
   而那些 code 是 artifact-v1 已发布、一经发布不改名的。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.runtime.store :as rs]))

(def codes
  "本模块自己产出的 `:code`。"
  #{;; ── 写闸：租约与 poisoned 的 code 在 `instance.*`（runtime/store）,
    ;;    因为那把租约属于整台 instance 而不是本子系统（③-1）。这里只保留
    ;;    「effect 落盘失败」那一条的转述口——它经 `all-codes` 回落。
    ;; ── 信封的上下文（形状由 control-protocol 判，这里判「对不对得上这台机器」）
    :deploy.audience/mismatch        ; 信封指向的不是本 instance
    :deploy.authz/service-not-permitted ; 该 service principal 无权对本机做这个动作

    ;; ── 幂等与部署计划 ─────────────────────────────────────────────────
    :deploy.request/envelope-conflict ; 同 request-id、不同信封 hash
    :deploy.plan/unknown              ; deployment-id 没有已保存的 plan
    :deploy.plan/immutable            ; 同 deployment-id、不同 plan —— 换新 id
    :deploy.plan/not-in-plan          ; 要暂存的 digest 不在该 plan 的闭包里

    ;; ── 暂存与装载 ─────────────────────────────────────────────────────
    :deploy.stage/digest-mismatch     ; 重算的 digest ≠ 已认证信封里的期望值
    :deploy.load/receipt-conflict     ; 已有 receipt 记着别的 digest——隔离，绝不改写

    ;; ── 闭包重验（**装载与激活共用一次遍历**，所以 code 也是模式中立的：
    ;;    在 `:load` 路径上冒出 `deploy.activate/*` 读起来就是错的）───────
    :deploy.closure/member-absent     ; 可达成员既不在架子上也不在暂存区（R3.3）
    :deploy.closure/cycle             ; (graph, 版本) 上本该是 DAG
    :deploy.closure/plan-mismatch     ; 可达集与 plan 对不上，或 digest/content-hash 不符

    ;; ── 激活与回滚 ─────────────────────────────────────────────────────
    :deploy.activate/not-loaded       ; 激活时该成员没有 receipt（装载期不判这一条）
    :deploy.activate/stale-expectation ; CAS：:expected-current 与实际不符
    :deploy.activate/gate-unavailable  ; 装配方没注入 S5.7 门禁——**拒绝激活**
    :deploy.pointer/broken-chain})    ; head event 不存在，或 previous 链断了

(def all-codes
  "本模块的 finding 可能带的**全部** code：自己的 + 四个回落来源。
   `finding?` 认这一份——回落来的原样透传（见 ns 注释）。
   `instance.*` 是新加的那一个：写闸与原子落盘的拒绝来自 `runtime/store`（③-1）。"
  (into codes (concat artifact/finding-codes cp/finding-codes dialect/finding-codes
                      rs/finding-codes)))

(defn make
  [code severity path message & {:keys [expected got refs]}]
  (cond-> {:code code :severity severity :path path :message message}
    (some? expected) (assoc :expected expected)
    (some? got)      (assoc :got (dialect/redact got))
    (seq refs)       (assoc :refs (vec refs))))

(defn error [code path message & {:as opts}]
  (apply make code :error path message (mapcat identity opts)))

(defn valid? [x]
  (boolean
   (and (map? x)
        (contains? all-codes (:code x))
        (contains? #{:error :warning :info} (:severity x))
        (vector? (:path x))
        (string? (:message x))
        (empty? (remove #{:code :severity :path :message :expected :got :refs :files}
                        (keys x))))))

(defn sort-findings [fs]
  (vec (sort-by (fn [f] [(pr-str (:path f)) (pr-str (:code f)) (:message f)]) fs)))

(defn errors? [fs] (boolean (some #(= :error (:severity %)) fs)))

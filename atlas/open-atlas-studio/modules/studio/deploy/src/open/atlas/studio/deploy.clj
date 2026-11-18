(ns open.atlas.studio.deploy
  "部署的 **studio 那一半**（D3.12 / D5.7）：owner 校验 → 签断言 → 编排 → 送出。

   跨模块只许 require 本 ns。

   ## 为什么这一半必须存在

   D3.12 的授权链只有一条：**studio 先以自己的成员表校验 actor 是该 graph 的
   owner，再以受限 service principal 调 server 控制面，并携带已签名的 actor
   principal**。server 校验 service 有对应 instance 权限，审计同时记两者——
   但它**不凭 actor 自报字段重建 workspace 角色**，因为那张表在 studio,
   复制过去就会有两份会漂移的成员关系。

   所以没有本模块，「双主体可审计」只成立一半：server 记得下是谁**声称**触发的,
   却没有任何地方真的判过他是不是 owner。

   ## 编排

   ```
   offer  送闭包清单（含根）→ 回「缺哪些」
   stage  只送缺的那几份字节
   load   装载一个根版本（R3.3 的依赖检查落在这里）
   activate / rollback  原子切指针
   ```

   一个 `deployment-id` 贯穿这一串；每条命令自己一个 `request-id`（幂等键）。
   **plan 不可改**：同 deployment-id 换一份 plan 是拒绝，要换就开新的 id。

   ## 两把钥匙

   `:actor-secret` 签断言（代替 owner 说话），`:service-secret` 签请求（证明是
   哪个受控服务在调）。**两把都必须与数据面 graph token 的钥匙分开**（D3.12：
   共用就等于让一张查询 token 的泄漏变成一次部署权泄漏）。"
  (:require [open.atlas.common.control-protocol :as cp]
            [open.atlas.studio.deploy.impl.client :as client]
            [open.atlas.studio.deploy.impl.finding :as finding]
            [open.atlas.studio.deploy.impl.plan :as plan]))

(def check-owner
  "`(check-owner db qualified actor)` → `[finding…]`（空 = 是 owner）。
   **只认 owner，不认 editor**（D5.7）。`actor` 与信封里的 `:actor/principal`
   是**同一个值**——通过 owner 检查的 actor 就是审计里的 actor（D5.10）。"
  plan/check-owner)

(def build-plan
  "`(build-plan db artifact-root qualified version)` → `{:closure […]}` 或
   `{:findings […]}`。闭包**从制品架上真遍历**，digest 从版本登记取。"
  plan/build-plan)

(def envelope plan/envelope)

(defn- ->finding-list [{:keys [status body findings]}]
  (cond
    (seq findings) findings
    (= 200 status) nil
    :else (or (seq (:findings body))
              [(finding/error :deploy.transport/unexpected [:response]
                              "the control plane rejected without structured findings"
                              :got status)])))

(defn- send!
  "送一份信封。传输是**可注入的**：缺省走 HTTP，`:send-envelope` 覆盖它。

   为什么要能换：studio 内嵌的那台 runtime 与 Builder 在同一个进程里
   （[D1.13](../../../../docs/design/10-platform-boundary.md)），走一次 HTTP 往返
   纯属浪费。但**换掉的只有传输**——信封照签、`handle!` 照跑，命令校验、审计、
   闭包检查与原子激活一样都不少（owner 裁定 2026-08-25）。绕过它们就等于
   studio 自测的那条路不再等于线上那条路，而 D1.5 的全部价值正在于两者相等。

   注入的函数与 `client/post-envelope` **同形**：吃 `[conf env]`，回
   `{:status :body}` 或 `{:findings […]}`。同形是有意的——应答的解读只有下面
   `->finding-list` 一处，两种传输因此不可能对「这算不算成功」有两种看法。"
  [conf env]
  (let [transport (or (:send-envelope conf) client/post-envelope)
        r (transport conf env)]
    (if-let [fs (->finding-list r)]
      {:findings (vec fs)}
      {:result (:control/result (:body r))})))

(defn- envelope-for [{:keys [deployment-id audience actor actor-key-id actor-secret
                                 now expires-at request-id-fn]}
                     op payload]
  (envelope {:op op :payload payload
             :deployment-id deployment-id
             :request-id (request-id-fn op)
             :audience audience
             :actor-principal actor
             :actor-key-id actor-key-id
             :actor-secret actor-secret
             :now now :expires-at expires-at}))

;; ---------------------------------------------------------------------------
;; The five steps, one function each
;;
;; They are public because re-running exactly one step is a real need — both
;; operationally (a stage that timed out mid-flight; the bytes are content-
;; addressed, so re-sending is a no-op) and for the assembly point, which has
;; to reach states `deploy!` deliberately never produces: offered but not
;; staged, root delivered but not its dependency. Driving those through a
;; hand-rolled envelope would mean a second copy of the signing and transport
;; code living in a test.
;; ---------------------------------------------------------------------------

(defn plan!
  "Traverse the shelf → `{:closure […]}` or `{:findings […]}`. No network."
  [{:keys [db artifact-root graph version]}]
  (build-plan db artifact-root graph version))

(defn offer!
  "Send the closure manifest (root included) → `{:missing […]}`.
   This is what binds this deployment id to a set of trusted digests."
  [conf closure]
  (let [{:keys [graph version]} conf]
    (send! conf (envelope-for conf :offer {:graph graph :version version
                                           :closure closure}))))

(defn stage!
  "Send the bytes of the given closure members → `{:sent n}`.
   Stops at the first refusal.

   **One request id per artifact, keyed by its digest.** The request id is the
   idempotency key of a single command, and staging A and staging B are two
   commands — reusing one id across the whole step made the second artifact
   look like the first one being replayed with a different envelope. Keying on
   the digest also means re-sending the same artifact reuses the same id, which
   is what makes a retry idempotent rather than a conflict."
  [{:keys [artifact-root request-id-fn] :as conf} members]
  (or (some (fn [m]
              (let [conf (assoc conf :request-id-fn
                                (constantly (request-id-fn [:stage (:artifact-digest m)])))
                    x (send! conf (envelope-for conf :stage
                                                {:artifact-digest (:artifact-digest m)
                                                 :files (plan/shelf-files artifact-root m)}))]
                (when (seq (:findings x)) x)))
            members)
      {:result {:sent (count members)}}))

(defn load!
  "Load one root version. **R3.3's dependency check lands here**, not on activate."
  [{:keys [graph version] :as conf}]
  (send! conf (envelope-for conf :load {:graph graph :version version})))

(defn activate!
  "Move the pointer, atomically, guarded by `:expected-current`."
  [{:keys [graph version expected-current] :as conf}]
  (send! conf (envelope-for conf :activate {:graph graph :version version
                                            :expected-current expected-current})))

(defn deploy!
  "Deploy an already-cut version to one instance, from the owner check all the
   way to activation.

   `conf` needs at least:
   `:db :artifact-root :graph :version :endpoint :audience :actor :actor-key-id
    :actor-secret :service-key-id :service-secret :deployment-id :request-id-fn
    :now :expires-at :expected-current`

   `:send-envelope` 可选——缺省走 HTTP，studio 内嵌的 runtime 换成同进程直调
   （见 `send!`；换的只有传输，命令边界一样都不少）。

   `:request-id-fn` is `(fn [step] → id)` — injected so it is testable, and so a
   retry can reuse the same id (strict idempotency needs it: same id, same
   envelope, same answer). `step` is the op keyword, except for staging, where
   it is `[:stage <artifact-digest>]`: one id per artifact, see `stage!`.

   → `{:steps [{:step … :result …}…] :findings []}`, or a stop at the **first**
   refusal. Stopping midway is safe: every step's marker is already on disk, so
   re-running with the same deployment id picks up from what is still missing
   (`:offer` with a fresh request id recomputes `:missing`)."
  [{:keys [db graph actor] :as conf}]
  (cp/run-deployment!
   [(fn [_] {:step :owner :findings (check-owner db graph actor)})
    (fn [_] (let [{:keys [closure findings]} (plan! conf)]
              {:step :plan :result {:closure closure} :findings findings}))]
   {:offer (fn [acc] (offer! conf (get-in acc [:by-step :plan :closure])))
    :stage (fn [acc] (stage! conf (get-in acc [:by-step :offer :missing])))
    :load (fn [_] (load! conf))
    :activate (fn [_] (activate! conf))}))

(defn rollback!
  "Point the graph at an **explicit** target version. The `:deployment-id` must be
   the one that deployed **that** version: the evidence has to be the plan of the
   version being pointed at, not the current one's.

   ## The op is chosen by direction (owner ruling 2026-08-30)

   In the runtime `:activate` and `:rollback` are one function (`pointer-move`):
   same CAS, same plan-root binding, same closure re-verification, same
   row-security gate. **The only things that differ are which payload key
   carries the target and which op the event records.**

   That last one is not decoration. «We pushed forward» and «we went back» are
   two different facts about how an instance is run, and the event log is the
   only place they are ever distinguishable. So this function picks the op from
   the move it is actually making rather than from its own name:

   | target vs pointer | op | payload key |
   |---|---|---|
   | earlier | `:rollback` | `:to-version` |
   | later, or nothing serving | `:activate` | `:version` |

   Nothing serving counts as forward: there is no earlier state to go back to.

   **This function's name is the act a caller asks for, not the op on the wire.**
   Callers reach it to move the pointer to a version that already exists — the
   MCP tool is `rollback_graph` and the Builder's button says «activate» — and
   before this ruling it stamped every such move `:rollback`, so an audit read
   «rolled back to v5» about a move that went forwards."
  [{:keys [db graph to-version actor expected-current] :as conf}]
  (or
   (when-let [fs (seq (check-owner db graph actor))] {:findings (vec fs)})
   (let [back? (and (integer? expected-current) (integer? to-version)
                    (< to-version expected-current))]
     (if back?
       (send! conf (envelope-for conf :rollback
                                 {:graph graph :to-version to-version
                                  :expected-current expected-current}))
       ;; Through `activate!`, not a second `:activate` envelope built here:
       ;; there is one place that knows what an activation looks like on the
       ;; wire, and two would be two things to keep in step.
       (activate! (assoc conf :version to-version))))))

;; 与 server/http 那一侧**同一份名字**。studio 不依赖 server（D1.8），所以两处
;; 只能靠 `deploy_test` 里那条断言对齐——各写一份字符串就是各有一份协议。
(defn header-key-id    [] client/header-key-id)
(defn header-timestamp [] client/header-timestamp)
(defn header-signature [] client/header-signature)

(def finding-codes finding/codes)
(defn finding? [x] (finding/valid? x))

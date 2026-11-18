(ns open.atlas.runtime.graph.impl.command
  "五个控制命令的**判定**（D3.16：判定是纯函数，传输是壳）。

   ## 形状

   ```clojure
   (execute reads env service-principal instance-id)
   ;; => {:result {…}        ; 应答体（成功时）
   ;;     :effects [effect…]  ; 「要落哪些字节」的描述，按序施加
   ;;     :findings [finding…]}
   ```

   **本 ns 不碰 IO、不碰时钟、不碰随机数**。它「纯」的标准与
   `artifact/graph-traverse` 同一条：**给定注入的读函数的返回值，结果确定**。
   读通过 `reads` 注入，写只作为 `:effects` 描述返回——施加在 impl.store。

   为什么值得这么绕：控制面的判定是执法点（D1.6「执法点在内核不在 server」的
   同一条精神），而一个只能靠起进程、铺目录才能测的判定，等于没有单元测试的
   执法点。这样一来 CAS 冲突、plan 被改、digest 对不上、闭包缺件，全都是一次
   函数调用就能断言的事。

   ## 校验顺序**固定**（owner 裁定 2026-08-24）

   信封形状 → audience → 时效 → actor 验签 → service 权限 → request-id 幂等
   → deployment plan 一致性 → 命令本身。见 `execute`。

   顺序不是口味：认证在最前——**没验签之前，信封里的一切都还只是调用方的说法**;
   幂等必须在 plan 一致性之前（否则一次合法重放先撞上 plan 检查）；plan 一致性
   必须在重算 missing 之前（否则「重算」会变成「原地改部署计划」）。

   **两个签名分工**：service 的请求签名由壳验（它要原始 HTTP body，只有壳有）,
   actor 验签在这里——它算在规范信封上，而那是两侧共用的定义（D3.16）。

   ## `:effects` 词表

   | kind | 落什么 | commit marker |
   |---|---|---|
   | `:write-deployment` | 已认证的 closure plan（写一次） | 该文件 |
   | `:stage-artifact`   | 三份文本 → `staging/<hex>/` | 完整目录 |
   | `:land-artifact`    | 三份文本 → 制品架 | —（下一条才是） |
   | `:write-receipt`    | receipt | **receipt** |
   | `:append-event`     | 不可变事件 | —（下一条才是） |
   | `:set-pointer`      | 指针（含 head event-id） | **pointer** |
   | `:write-request`    | 幂等记录 | 该文件 |

   顺序即施加顺序：`:land-artifact` 一定排在它的 `:write-receipt` 之前,
   `:append-event` 一定排在 `:set-pointer` 之前——**先落内容，后落 marker**。
   崩在中间留下的是「有目录无 receipt」或「有 event 未被 head 引用」，两者都
   **不被当作成功状态**（见 impl.store 的恢复规则）。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.control-protocol :as cp]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.runtime.graph.impl.finding :as f]))

;; ---------------------------------------------------------------------------
;; 坐标与小工具
;; ---------------------------------------------------------------------------

(defn qualified->coords
  "`\"core-team/core\"` + 版本 → `{:workspace :graph :version}`（制品架的坐标）。
   限定名只有一个 `/`（D3.10）；形状不对给 nil。"
  [qualified version]
  (let [i (.indexOf ^String qualified "/")]
    (when (and (pos? i) (< (inc i) (count qualified)))
      {:workspace (subs qualified 0 i)
       :graph     (subs qualified (inc i))
       :version   version})))

(defn digest-hex
  "`\"sha256:<64hex>\"` → `\"<64hex>\"`。**暂存目录名用去前缀的那 64 位**：
   冒号不是合法路径段（artifact 的 `segment-pattern` 只收
   `[A-Za-z0-9][A-Za-z0-9._-]*`），而完整值仍活在 receipt 与信封里。"
  [digest]
  (when (string? digest) (subs digest (count "sha256:"))))

(defn plan-hash
  "closure plan 的身份。**同 deployment-id 必须给出同一份 plan**——否则
   「重算 missing」会变成「原地改部署计划」，而 plan 正是那条可信 digest 链的锚。

   算在规范 EDN 上（`dialect/canonical-str`）：书写顺序、键序都不进身份。"
  [plan]
  (str "sha256:" (cp/sha256-hex (dialect/canonical-str plan))))

(defn- plan-of
  "信封的 `:offer` payload → 保存下来的 plan（**只留身份相关的那几项**）。"
  [env]
  (let [p (:control/payload env)]
    {:root     {:graph (:graph p) :version (:version p)}
     :audience (:control/audience env)
     :closure  (vec (sort-by (juxt :graph :version) (:closure p)))}))

;; `load*` 与 `pointer-move` 都用它，而它定义在两者之后（放在一起读更顺:
;; 它是遍历那一段的主角）。
(declare verify-plan)

(defn evidence
  "**产生 durable commit 的 effect 一律带这一份**（deployment plan、首次 staging
   marker、receipt、event）。

   为什么不能只靠最后那条 `write-request`：marker 落成之后、`write-request`
   之前崩溃，留下的是**已承诺的状态却没有完整的双主体审计**——而那条记录写在
   最后，补不了这个窗口。审计的两个主体是 `(service, actor)`（D5.10/D3.12）,
   谁触发、谁代为调用，缺一不可。"
  [env ctx]
  {:request-id        (:control/request-id env)
   :deployment-id     (:control/deployment-id env)
   :service-principal (:service-principal ctx)
   :actor-principal   (get-in env [:control/actor :actor/principal])
   :issued-at         (:control/issued-at env)
   :accepted-at       (:now ctx)})

(defn- member-index [plan]
  (into {} (for [m (:closure plan)] [[(:graph m) (:version m)] m])))

(defn path-segment
  "外部 ID → 可安全当路径段用的 64 位 hex。

   **request-id / deployment-id 是外部字符串**，只被要求非空——它们可以含 `/`
   或 `..`。直接拿去拼路径就是穿越。落盘一律用它的 SHA-256 hex 作段名，
   **原始 ID 保存在文件内容里**（同 artifact 的段白名单纪律：拒绝而不是净化,
   只是这里的取值域不由我们决定，所以改成编码）。"
  [id]
  (cp/sha256-hex (str id)))

;; ---------------------------------------------------------------------------
;; 前置 ①：认证 —— 时效、actor 验签、service 权限
;; ---------------------------------------------------------------------------
;;
;; 时间戳是固定格式的 RFC3339 UTC（control-protocol 的 `timestamp?` 只收这一种
;; 拼法），所以**字典序比较就是时间序**，不需要日期解析——少一个依赖，也少一处
;; 时区/闰秒的判断。

(defn- check-window
  "`issued-at <= now < expires-at`，且 `expires-at - issued-at <= 上限`。

   **不用字符串比较**：正则只管拼法，`2026-13-45T99:99:99Z` 过得了正则却不是
   真实日期，而一个解析不出来的时间戳比较起来会给出一个看似成立的答案。

   `now == expires-at` 算**过期**（右开区间）：一个「到期这一秒还能用」的窗口,
   在两台时钟差一秒的机器上就是两种答案。

   有效期还有**上限**（`cp/max-assertion-seconds`）：没有它，「短时断言」可以
   签几年——而 actor 断言就是那把代替 owner 说话的钥匙。"
  [env now]
  (let [t-now (cp/parse-instant now)
        t-iss (cp/parse-instant (:control/issued-at env))
        t-exp (cp/parse-instant (:control/expires-at env))]
    (cond
      (nil? t-now)
      [(f/error :control.signature/not-yet-valid []
                "no usable clock reading was supplied to decide" :got now)]

      (not (pos? (.compareTo t-exp t-iss)))
      [(f/error :control.signature/expired [:control/expires-at]
                "the validity window is empty or inverted"
                :expected (:control/issued-at env) :got (:control/expires-at env))]

      (> (- (.getEpochSecond t-exp) (.getEpochSecond t-iss)) cp/max-assertion-seconds)
      [(f/error :control.signature/expired [:control/expires-at]
                (str "the validity window is longer than this protocol allows; a long-lived "
                     "assertion is a long-lived key to act as the owner")
                :expected cp/max-assertion-seconds
                :got (- (.getEpochSecond t-exp) (.getEpochSecond t-iss)))]

      (not (neg? (.compareTo t-now t-exp)))
      [(f/error :control.signature/expired [:control/expires-at]
                "this envelope has expired" :expected (:control/expires-at env) :got now)]

      (neg? (.compareTo t-now t-iss))
      [(f/error :control.signature/not-yet-valid [:control/issued-at]
                (str "this envelope is issued in the future; either a clock is wrong or "
                     "someone is holding an envelope for later")
                :expected (:control/issued-at env) :got now)])))

(defn- check-actor-signature [reads env]
  (let [key-id    (get-in env [:control/actor :actor/key-id])
        presented (get-in env [:control/actor :actor/signature])
        secret    ((:actor-key reads) key-id)]
    (cond
      (not (cp/well-formed-signature? presented))
      [(f/error :control.signature/malformed [:control/actor :actor/signature]
                "the actor signature is not \"hmac-sha256:\" + 64 lowercase hex")]
      (nil? secret)
      [(f/error :control.signature/unknown-key [:control/actor :actor/key-id]
                (str "no verification key is on file for this key id; keys rotate, so an "
                     "unknown id is a rejection rather than a fallback to some default")
                :got key-id)]
      (not (cp/actor-signature-matches? env secret))
      [(f/error :control.signature/mismatch [:control/actor :actor/signature]
                (str "the actor signature does not cover this envelope; the signature is "
                     "over the WHOLE envelope minus the signature field itself, so any "
                     "edit — version, rollback target, instance, payload — invalidates it"))])))

(defn- check-service-authz [reads service-principal op]
  (when-not (boolean ((:service-allows? reads) service-principal op))
    [(f/error :deploy.authz/service-not-permitted [:control/op]
              (str "this service principal is not permitted to perform this operation on "
                   "this instance; the server's control plane only answers \"who may do "
                   "these five things to this machine\" — it does not replicate any "
                   "workspace membership (D3.12)")
              :got op :refs ["D3.12" "D5.7"])]))

;; ---------------------------------------------------------------------------
;; 前置 ②：audience / 幂等 / plan 一致性
;; ---------------------------------------------------------------------------

(defn- check-audience [env instance-id]
  (when (not= (:control/audience env) instance-id)
    [(f/error :deploy.audience/mismatch [:control/audience]
              (str "this envelope is addressed to another instance; an envelope that "
                   "verifies here but was meant for somewhere else is a replay")
              :expected instance-id :got (:control/audience env))]))

(defn- replay
  "同 request-id 的处置。→ `{:replay {…原结果…}}`、`{:findings […]}`，或 nil（首次）。"
  [reads env]
  (when-let [rec ((:request reads) (:control/request-id env))]
    (if (= (:envelope-hash rec) (plan-hash env))
      {:replay (:result rec)}
      {:findings
       [(f/error :deploy.request/envelope-conflict [:control/request-id]
                 (str "this request id was already used with a different envelope; "
                      "the request id is the idempotency key of one command — reuse it "
                      "only to retry the identical command, and use a new one (under the "
                      "same deployment id) to ask the question again")
                 :got (:control/request-id env))]})))

(defn- plan-consistency
  "同 deployment-id 的 plan 必须逐字相同。→ `[finding…]` 或 nil。"
  [stored incoming]
  (when (and stored (not= (plan-hash stored) (plan-hash incoming)))
    [(f/error :deploy.plan/immutable [:control/deployment-id]
              (str "this deployment id already carries a different plan; the plan is the "
                   "anchor of the trusted digest chain, so recomputing :missing must never "
                   "become editing the deployment in place — use a new deployment id")
              :got (:control/deployment-id incoming))]))

;; ---------------------------------------------------------------------------
;; offer
;; ---------------------------------------------------------------------------

(defn- loaded?
  "「已装载」= 制品目录在 **且** receipt 在，且 receipt 记的 digest 与期望一致。
   两者缺一都不算——有目录无 receipt 是上一次失败的残留（那个期望 digest 当初
   来自哪份已认证信封，没有证据），有 receipt 无目录是字节没了。"
  [reads coords expected-digest]
  (let [r ((:receipt reads) coords)]
    (boolean (and r
                  ((:shelf-artifact reads) coords)
                  (= (:artifact-digest r) expected-digest)))))

(defn- staged? [reads digest]
  (boolean ((:staged-artifact reads) (digest-hex digest))))

(defn- offer [reads env ctx]
  (let [incoming (plan-of env)
        stored   ((:deployment reads) (:control/deployment-id env))]
    (if-let [fs (plan-consistency stored incoming)]
      {:findings fs}
      (let [missing (vec (for [m (:closure incoming)
                               :let [coords (qualified->coords (:graph m) (:version m))]
                               :when (not (or (loaded? reads coords (:artifact-digest m))
                                              (staged? reads (:artifact-digest m))))]
                           m))]
        {:result  {:missing missing}
         ;; plan 写一次：已存在且一致时不重写（重写是无谓的写放大，也让
         ;; 「写一次不可改」在磁盘上更难被误解）。
         :effects (cond-> []
                    (nil? stored)
                    (conj {:effect/kind :write-deployment
                           :deployment-id (:control/deployment-id env)
                           :plan incoming
                           :evidence (evidence env ctx)}))}))))

;; ---------------------------------------------------------------------------
;; stage
;; ---------------------------------------------------------------------------

(defn- stage [reads env ctx]
  (let [p        (:control/payload env)
        expected (:artifact-digest p)
        files    (:files p)
        stored   ((:deployment reads) (:control/deployment-id env))
        actual   (artifact/artifact-digest files)]
    (cond
      (nil? stored)
      {:findings [(f/error :deploy.plan/unknown [:control/deployment-id]
                           (str "no plan is on file for this deployment id; offer must come "
                                "first — it is what binds a coordinate to a trusted digest")
                           :got (:control/deployment-id env))]}

      ;; 不在 plan 里的字节不进暂存区：一个已认证但跑偏的 service 不该能往这台
      ;; 机器上灌任意制品。
      (not (some #(= expected (:artifact-digest %)) (:closure stored)))
      {:findings [(f/error :deploy.plan/not-in-plan [:control/payload :artifact-digest]
                           "this artifact is not part of the plan for this deployment id"
                           :got expected)]}

      (not= actual expected)
      {:findings [(f/error :deploy.stage/digest-mismatch [:control/payload :files]
                           (str "the digest recomputed from these three files does not match "
                                "the expected digest in the authenticated envelope")
                           :expected expected :got actual)]}

      :else
      {:result  {:staged expected}
       ;; 内容寻址：同一份重传是幂等的，落在同一处。
       ;; **evidence 落在内容目录旁的独立文件里**（`stage-receipt.edn`），
       ;; 不进那三份文件——三文件的 digest 是身份，往里塞东西就改了身份。
       :effects [{:effect/kind :stage-artifact
                  :digest-hex  (digest-hex expected)
                  :digest      expected
                  :files       files
                  :evidence    (evidence env ctx)}]})))

;; ---------------------------------------------------------------------------
;; load
;; ---------------------------------------------------------------------------

(defn- load-member-effects
  "一个闭包成员在本次 load 里要落的字节。已装载 → 无；架上有目录但 digest 对不上
   → 拒绝（隔离，绝不覆盖）；有目录无 receipt 且 digest 相符 → **只补 receipt**。"
  [reads env ctx m]
  (let [coords   (qualified->coords (:graph m) (:version m))
        expected (:artifact-digest m)
        on-shelf ((:shelf-artifact reads) coords)
        receipt  ((:receipt reads) coords)
        staged   ((:staged-artifact reads) (digest-hex expected))
        receipt* {:artifact-digest expected
                  :content-hash    (:content-hash m)
                  :evidence        (evidence env ctx)}]
    (cond
      ;; 已装载：什么都不做（load 可重跑，这是它幂等的那一半）
      (and on-shelf receipt (= (:artifact-digest receipt) expected))
      {:effects []}

      ;; **receipt 在、但 digest 不同：无条件拒绝，连架上的字节都不必看。**
      ;; receipt 是可信的 commit marker——「当初那个期望 digest 来自哪份已认证
      ;; 信封」的唯一证据。让它被一份「字节碰巧对得上新期望值」的推断改写，
      ;; 等于把一条历史改成另一条，而且改的正是那份证据本身。
      (and receipt (not= (:artifact-digest receipt) expected))
      {:findings
       [(f/error :deploy.load/receipt-conflict [:control/payload]
                 (str "this coordinate already has a receipt for a different digest; it is "
                      "quarantined, never rewritten — the receipt is the evidence that an "
                      "expected digest once came from an authenticated envelope, and two "
                      "different histories under one coordinate is a fact someone must look at")
                 :expected (:artifact-digest receipt) :got expected)]}

      ;; receipt 缺席、架上有目录：这是「目录先落、receipt 后提交」的可恢复路径。
      ;; **字节对不对已经由 verify-plan 判过了**（它以 plan digest 为期望值重读了
      ;; 每一份），所以这里只补 marker，不再重算一遍。
      on-shelf
      {:effects [{:effect/kind :write-receipt :coords coords :receipt receipt*}]}

      staged
      {:effects [{:effect/kind :land-artifact :coords coords :files staged}
                 {:effect/kind :write-receipt :coords coords :receipt receipt*}]}

      ;; 走到这里说明既不在架上也不在暂存区——但 verify-plan 已经先报过
      ;; `:deploy.closure/member-absent` 了，这一支只是不让它静默产出空 effect。
      :else
      {:findings [(f/error :deploy.closure/member-absent [:control/payload]
                           "this closure member is neither staged nor on the shelf"
                           :got {:graph (:graph m) :version (:version m)})]})))

(defn- load* [reads env ctx]
  (let [p      (:control/payload env)
        stored ((:deployment reads) (:control/deployment-id env))]
    (if (nil? stored)
      {:findings [(f/error :deploy.plan/unknown [:control/deployment-id]
                           "no plan is on file for this deployment id"
                           :got (:control/deployment-id env))]}
      (let [root (:root stored)]
        (if (not= [(:graph root) (:version root)] [(:graph p) (:version p)])
          {:findings [(f/error :deploy.plan/immutable [:control/payload]
                               (str "this deployment's plan is rooted at a different version; "
                                    "load the root the plan was authenticated for, or open a "
                                    "new deployment")
                               :expected root :got {:graph (:graph p) :version (:version p)})]}
          ;; **R3.3 的依赖检查落在这里**：先从 root 真遍历一遍引用图，通过了
          ;; 才产生任何 effect。只逐个装 plan 列出的成员的话，一份不完整的
          ;; plan 照样拿得到 receipt——而 receipt 是不可改写的证据，等到
          ;; `:activate` 才拒就已经太晚。
          (let [root-key [(:graph p) (:version p)]
                gfs      (verify-plan reads stored root-key :load)]
            (if (seq gfs)
              {:findings gfs}
              (let [per (map #(load-member-effects reads env ctx %) (:closure stored))
                    fs  (vec (mapcat :findings per))]
                (if (seq fs)
                  {:findings (f/sort-findings fs)}
                  {:result  {:loaded (mapv (juxt :graph :version) (:closure stored))}
                   :effects (vec (mapcat :effects per))})))))))))

;; ---------------------------------------------------------------------------
;; activate / rollback
;; ---------------------------------------------------------------------------

(defn verify-plan
  "从 **root 的 `content.edn` 实际遍历**引用图，逐条断言 plan 说的与机器上的一致。
   两条路径共用**同一次遍历**（D3.15 的派生 `invalid`：它是算出来的，不是标上去的）。

   `mode`：
   - `:load` —— 字节可来自架子**或**暂存区；可信期望值是 **plan 的 digest**;
     **不要求已有 receipt**（receipt 正是这一步要写出来的东西）。
   - `:activate` —— 字节只认架子；**要求 receipt 存在且其 digest 等于 plan 的**
     （receipt 是「那个期望值来自已认证信封」的唯一证据）。

   两者都判：无 missing、无环、**可达集 ∪ {root} 精确等于 plan 闭包**、每个成员
   实际算出的 `content-hash` 等于 plan 声明的值。

   ## 为什么 `:load` 也必须遍历

   R3.3 说依赖检查落在**装载**上。只逐个装 plan 列出的成员的话：一份不完整的
   plan、而它列出的制品都在场——照样拿得到 receipt，要到 `:activate` 才被拒。
   那时字节已经在架子上、receipt 已经签发，而 receipt 是不可改写的证据。

   → `[finding…]`（空 = 通过）。"
  [reads plan root-key mode]
  (let [idx      (member-index plan)
        problems (atom [])
        note!    (fn [f] (swap! problems conj f))
        files-of (fn [coords]
                   (or ((:shelf-artifact reads) coords)
                       (when (= :load mode)
                         (when-let [m (get idx [(str (:workspace coords) "/" (:graph coords))
                                                (:version coords)])]
                           ((:staged-artifact reads) (digest-hex (:artifact-digest m)))))))
        content  (fn [coords] (get (files-of coords) "content.edn"))
        resolve*
        (fn [[qn v :as k] _path]
          (let [coords  (qualified->coords qn v)
                files   (files-of coords)
                receipt ((:receipt reads) coords)
                planned (get idx k)]
            (cond
              (nil? files) nil                      ; → graph-traverse 报 missing

              ;; 可信期望值：装载期取 plan 的（receipt 还不存在），激活期取
              ;; receipt 的（并要求它与 plan 相符）。
              (and (= :activate mode) (nil? receipt))
              (do (note! (f/error :deploy.activate/not-loaded [:control/payload]
                                  (str "this version's bytes are on the shelf but it has no "
                                       "receipt — there is no evidence its expected digest "
                                       "ever came from an authenticated envelope")
                                  :got {:graph qn :version v}))
                  [])

              (and (= :activate mode) planned
                   (not= (:artifact-digest receipt) (:artifact-digest planned)))
              (do (note! (f/error :deploy.closure/plan-mismatch [:control/payload]
                                  "the receipt's digest disagrees with the plan"
                                  :expected (:artifact-digest planned)
                                  :got (:artifact-digest receipt)))
                  [])

              :else
              (let [expected (if (= :activate mode)
                               (:artifact-digest receipt)
                               (:artifact-digest planned))]
                (if (nil? expected)
                  ;; 可达但 plan 没提——下面的集合比较会点名它，这里只是走不下去。
                  []
                  (let [{:keys [findings references content-hash deployment]}
                        (artifact/read-artifact
                         files
                         {:expected-digest expected
                          ;; reader 要的是被引用方的 **content.edn 文本**。
                          :resolve-reference
                          (fn [ref]
                            (content (qualified->coords
                                      (str (:workspace ref) "/" (:graph ref)) (:version ref))))})]
                    (cond
                      ;; **只有 `:error` 挡路**。carried 键（D2.9/D5.8）是逐项
                      ;; `:info` 点名——「合法、能往返、暂无执行语义」。把它当
                      ;; 拒绝，等于一张写过 `:row-security` 或 `:cache` 的图永远
                      ;; 装不上，而 D5.8 说的正是「点名而不是拒绝」；更糟的是
                      ;; S5.7 的门禁就再也轮不到开口——那张图在装载期就死了,
                      ;; 而它本该在**激活**那一步被拦并说明理由。
                      (some #(= :error (:severity %)) findings)
                      (do (run! note! (filter #(= :error (:severity %)) findings)) [])

                      (and deployment (not= coords deployment))
                      (do (note! (f/error :deploy.closure/plan-mismatch [:control/payload]
                                          "semantic artifact deployment coordinates disagree with the plan")) [])

                      ;; plan 同时声明了 content-hash——它也得对得上，否则「plan
                      ;; 说的那份内容」与「机器上这份」只是碰巧 digest 相同。
                      (and planned (not= content-hash (:content-hash planned)))
                      (do (note! (f/error :deploy.closure/plan-mismatch [:control/payload]
                                          "the content-hash disagrees with the plan"
                                          :expected (:content-hash planned) :got content-hash))
                          [])

                      :else
                      (vec (for [r references]
                             [(str (:workspace r) "/" (:graph r)) (:version r)])))))))))
        {:keys [order cycles missing]}
        (artifact/graph-traverse ::root [root-key] resolve*)
        reached (into #{root-key} order)
        planned (set (keys idx))]
    (f/sort-findings
     (vec
      (concat
       @problems
       (for [{:keys [path]} cycles]
         (f/error :deploy.closure/cycle [:control/payload]
                  "the reference closure contains a cycle; (graph, version) is a DAG"
                  :got (vec (rest path)) :refs ["R3.3"]))
       (for [{:keys [node path]} missing]
         (f/error :deploy.closure/member-absent [:control/payload]
                  "this transitively referenced version is not present on this instance"
                  :got node :expected (vec (rest path)) :refs ["R3.3"]))
       (for [k (sort-by pr-str (remove planned reached))]
         (f/error :deploy.closure/plan-mismatch [:control/payload]
                  (str "this version is reachable from the root but the plan never mentioned "
                       "it; a member nobody vouched for must not ride along")
                  :got k))
       (for [k (sort-by pr-str (remove reached planned))]
         (f/error :deploy.closure/plan-mismatch [:control/payload]
                  (str "the plan promised this member but nothing in the reference graph "
                       "reaches it")
                  :got k)))))))

(defn- pointer-move
  "activate 与 rollback 的共同体：CAS → plan 根绑定 → 闭包重验 → 事件 → 指针。

   两者的差别只有目标版本从哪个键来，以及事件里记的 op。合成一个函数是因为
   **回滚不是另一种操作，是切到一个更早的目标**——写成两份实现，CAS 或重验
   哪天只改了一处，那一处就成了后门。

   **回滚要带目标那一版当初的 deployment-id**，不是当前版本的：证据必须是
   「我要切过去的那一版」的 plan，否则就成了拿 A 的证据给 B 背书。"
  [reads env ctx op to-version]
  (let [p        (:control/payload env)
        graph    (:graph p)
        current  ((:pointer reads) graph)
        stored   ((:deployment reads) (:control/deployment-id env))
        cur-ver  (:version current)
        root-key [graph to-version]]
    (cond
      ;; **同一个 request-id 已经切过**。事件 id 就是 request-id（一条命令恰好
      ;; 产生一个事件，而 request-id 本来就是那条命令的唯一键），所以纯命令造得出
      ;; 确定性的 id，「我这次请求应用过没有」也退化成与 head 的一次比较。
      ;;
      ;; **返回的是事件里存着的原始结果**，不是现算的：现算给出的是
      ;; `{:from 7 :to 7}`，而第一次的答案是 `{:from nil :to 7}`——两次同样的
      ;; 请求拿到两个不同的答案，严格幂等就已经破了。
      (= (:head-event-id current) (:control/request-id env))
      (let [ev ((:event reads) graph (:control/request-id env))]
        (cond
          (nil? ev)
          {:findings [(f/error :deploy.pointer/broken-chain [:control/request-id]
                               (str "the pointer names this request as its head event, but "
                                    "that event is not on disk — the chain is broken and "
                                    "this graph must not be served until someone looks")
                               :got (:control/request-id env))]}
          (not= (:envelope-hash ev) (plan-hash env))
          {:findings [(f/error :deploy.request/envelope-conflict [:control/request-id]
                               "this request id already committed a different envelope"
                               :got (:control/request-id env))]}
          :else {:result (:result ev) :effects [] :replayed true}))

      (not= cur-ver (:expected-current p))
      {:findings [(f/error :deploy.activate/stale-expectation [:control/payload :expected-current]
                           (str "the deployment pointer is not where this request expected it; "
                                "someone moved it in between — re-read and decide again")
                           :expected (:expected-current p) :got cur-ver)]}

      (nil? stored)
      {:findings [(f/error :deploy.plan/unknown [:control/deployment-id]
                           "no plan is on file for this deployment id"
                           :got (:control/deployment-id env))]}

      ;; **plan 的根必须就是这次要切过去的版本。** 不绑的话，拿 app@v7 的 plan
      ;; 验完闭包、再把指针指向 v3 是通的——那等于用 A 的证据给 B 背书。
      (not= (:root stored) {:graph graph :version to-version})
      {:findings [(f/error :deploy.plan/immutable [:control/deployment-id]
                           (str "this deployment's plan is rooted elsewhere; the evidence must "
                                "be the plan of the version being pointed AT — for a rollback "
                                "that is the older version's own deployment, not the current "
                                "one's")
                           :expected {:graph graph :version to-version} :got (:root stored))]}

      ;; **门禁缺席即拒绝激活**（S5.7 / D5.7）。写成「没注入就当放行」是 fail-open:
      ;; 一台装配漏了这一步的机器会安安静静地把未核对的 `:row-security` 放上线,
      ;; 而没有任何东西会说一句。要不查，装配方得**显式**注入一个放行的门禁——
      ;; 那句话在代码里看得见。
      (not (ifn? (:row-security-gate reads)))
      {:findings [(f/error :deploy.activate/gate-unavailable [:reads]
                           (str "no row-security gate is wired into this instance, so nothing "
                                "can tell whether this closure declares :row-security whose "
                                "wiring was never verified (D5.14); refusing to activate is "
                                "the fail-closed direction — a missing gate must not read as "
                                "an open one")
                           :refs ["S5.7" "D5.14" "D5.7"])]}

      :else
      (let [fs (into (vec (verify-plan reads stored root-key :activate))
                     ;; **S5.7 的门禁在这里**，在任何 effect 之前：闭包里有声明而
                     ;; 接线核对没通过的 resolver 时，激活失败而**指针保持原值**
                     ;; （D5.14 取代了 D5.6 的判据，失败方向不变）。已有 latest
                     ;; token 不构成绕过，正是因为这一步排在
                     ;; 切指针之前——回滚同样过这道门：切到一个更早的版本也是激活。
                     (vec ((:row-security-gate reads) graph to-version)))]
        (if (seq fs)
          ;; D3.15：拒绝**新的**激活。已经在跑的那一版一个字节不动——
          ;; 自动下线比自动上线更违反「上线时机是 owner 对消费者的承诺」。
          {:findings fs}
          (let [result {:from cur-ver :to to-version}]
            {:result  result
             :effects [{:effect/kind :append-event
                        :graph graph
                        :event-id (:control/request-id env)
                        ;; **事件自带完整的幂等与审计证据**：崩在「切了指针、
                        ;; 还没写 requests」时，已提交的事件里必须找得到是谁做的、
                        ;; 签的是哪份信封、原始答案是什么。只靠 requests 记录的话,
                        ;; 那三样会随它一起丢。
                        :event (merge (evidence env ctx)
                                      {:event/id          (:control/request-id env)
                                       :op                op
                                       :graph             graph
                                       :from              cur-ver
                                       :to                to-version
                                       :result            result
                                       :envelope-hash     (plan-hash env)
                                       :instance-id       (:instance-id ctx)
                                       :previous-event-id (:head-event-id current)})}
                       ;; 指针是 commit marker，**带 head event-id**：恢复时靠它
                       ;; 走事件链（head 必须存在、previous 链必须闭合）。
                       {:effect/kind :set-pointer
                        :graph graph
                        :pointer {:version       to-version
                                  :head-event-id (:control/request-id env)}}]}))))))

;; ---------------------------------------------------------------------------
;; 入口
;; ---------------------------------------------------------------------------

(defn- dispatch [reads env ctx]
  (case (:control/op env)
    :offer    (offer reads env ctx)
    :stage    (stage reads env ctx)
    :load     (load* reads env ctx)
    :activate (pointer-move reads env ctx :activate (get-in env [:control/payload :version]))
    :rollback (pointer-move reads env ctx :rollback (get-in env [:control/payload :to-version]))))

(defn execute
  "一条控制命令的判定。

   ```clojure
   (execute reads env {:instance-id … :service-principal … :now …})
   ```

   `reads` 要提供：`:deployment :request :event :staged-artifact :shelf-artifact
   :receipt :pointer :actor-key :service-allows?`，外加 `:row-security-gate`
   （S5.7，`(fn [graph version] → [finding…])`）。

   **`service-principal` 由壳从已认证的通道取得后传进来**——信封里没有它,
   自称的身份不是身份。service 的**请求签名**由壳验（它要原始 HTTP body,
   只有壳有）；**actor 验签、时效与 instance/op 权限在这里**，因为它们算在
   规范信封上，而那是两侧共用的定义。

   ## 校验顺序固定（owner 裁定 2026-08-24）

   信封形状 → audience → 时效 → actor 验签 → service 权限 → request-id 幂等
   → deployment plan 一致性 → 命令本身。

   顺序不是口味：认证在最前——**没验签之前，信封里的一切都还只是调用方的说法**;
   幂等必须在 plan 一致性之前（否则一次合法重放先撞上 plan 检查）；plan 一致性
   必须在重算 missing 之前（否则「重算」会变成「原地改部署计划」）。"
  [reads env {:keys [instance-id service-principal now] :as ctx}]
  (or
   (when-let [fs (seq (cp/validate-envelope env))]          {:findings (vec fs)})
   (when-let [fs (seq (check-audience env instance-id))]    {:findings (vec fs)})
   (when-let [fs (seq (check-window env now))]              {:findings (vec fs)})
   (when-let [fs (seq (check-actor-signature reads env))]   {:findings (vec fs)})
   (when-let [fs (seq (check-service-authz reads service-principal (:control/op env)))]
     {:findings (vec fs)})
   (let [r (replay reads env)]
     (cond
       (:findings r) {:findings (:findings r)}
       (:replay r)   {:result (:replay r) :effects [] :replayed true}))
   (let [out (dispatch reads env ctx)]
     (cond-> out
       ;; 幂等记录最后写：命令自身必须**先是**幂等的（pointer-move 从事件里取回
       ;; 原始结果的那一支），这条记录只让重放答得更快，不是它成立的依据。
       (and (:result out) (empty? (:findings out)) (not (:replayed out)))
       (update :effects (fnil conj [])
               {:effect/kind :write-request
                :request-id  (:control/request-id env)
                :record {:envelope-hash     (plan-hash env)
                         :op                (:control/op env)
                         :result            (:result out)
                         :service-principal service-principal
                         :actor-principal   (get-in env [:control/actor :actor/principal])
                         :accepted-at       now}})))))

(ns open.atlas.common.control-protocol
  "控制面协议（D3.16）——**studio 与 server 共用的那一半**。

   跨模块只许 require 本 ns；`open.atlas.common.control-protocol.impl.*` 视为
   私有（同 B0 的 `open.atlas.common.dialect`）。

   ## 本模块回答两个问题，仅此两个

   1. **一份合法的控制信封长什么样**（封闭键面、五个 op 各自的 payload）；
   2. **签名算在哪串字节上**（actor 签整个信封减一个字段；service 签固定结构）。

   命令的判定在 `runtime/graph`，owner 校验在 `studio/deploy`，传输在
   `server/http`。本模块不认识制品架、不认识池，也不认识 HTTP。发布步骤通过宿主注入的函数执行。

   ## 为什么在 common

   studio 要**构造并签名**同一份信封，server 要**校验**它，而 D1.8 禁止 studio
   依赖 server。协议若只住 server，studio 只能再写一份——两份信封形状或两份签名
   输入分家的那天，症状是「签名验不过」，而没有人会想到去查是不是有两套定义。
   归属判据是 D1.8 自己那一句：「凡是 studio 与 server 必须表现一致的行为，
   实现就属于 `common`」（同 [D1.12](docs/design/10-platform-boundary.md) 对引用图
   可达性的裁定）。

   ## 五个 op

   `:offer` 送闭包清单（**含根自己**），server 回缺哪些 → `:stage` 送缺的那几份
   字节 → `:load` 装载一个根版本（R3.3 的依赖检查落在**这里**，不落在激活上）
   → `:activate` 原子切指针 / `:rollback` 切回一个**显式**的目标版本。

   契约：[finding-v1](docs/contracts/finding-v1.md)（所有拒绝的形状）。"
  (:require [open.atlas.common.control-protocol.impl.envelope :as envelope]
            [open.atlas.common.control-protocol.impl.finding :as finding]
            [open.atlas.common.control-protocol.impl.signature :as signature]))

;; ---------------------------------------------------------------------------
;; 信封
;; ---------------------------------------------------------------------------

(def protocol-version envelope/protocol-version)
(def ops               envelope/ops)
(def envelope-keys     envelope/envelope-keys)
(def actor-keys        envelope/actor-keys)
(def payload-keys      envelope/payload-keys)
(def max-assertion-seconds
  "一份控制信封的最长有效期（秒）。两侧共用——studio 不该签一份 server 会拒的断言。"
  envelope/max-assertion-seconds)
(def parse-instant
  "RFC3339 UTC 秒精度 → `Instant`；形状不对或不是真实日期给 nil。"
  envelope/parse-instant)

(defn validate-envelope
  "控制信封 → `[finding…]`（空 = 形状合法）。**只判形状**：权限、时效与验签
   各有各的输入，住在别处。"
  [env]
  (envelope/validate env))

;; ---------------------------------------------------------------------------
;; 签名
;; ---------------------------------------------------------------------------

(def signature-prefix signature/signature-prefix)

(def actor-signing-input
  "信封 → actor 签名算在上面的那串文本（整个信封，只挖掉签名那一个字段）。
   导出它是为了让两侧的测试能断言**算的是同一串字节**，而不是各自只断言
   「验得过」——后者在两边同时错的时候也是绿的。"
  signature/actor-signing-input)

(def sign-actor              signature/sign-actor)
(def actor-signature-matches? signature/actor-signature-matches?)

(def service-signing-input   signature/service-signing-input)
(def sign-request            signature/sign-request)
(def request-signature-matches? signature/request-signature-matches?)

(def well-formed-signature?  signature/well-formed-signature?)
(def constant-time=          signature/constant-time=)
(def sha256-hex              signature/sha256-hex)

(defn build-envelope
  "Build and actor-sign one canonical control envelope. Studio deployment and
   the Server's authenticated graph-upload UI share this constructor; only the
   transport and the authority check outside the envelope differ."
  [{:keys [op payload deployment-id request-id audience actor-principal actor-key-id
           now expires-at actor-secret]}]
  (let [base {:control/version       protocol-version
              :control/op            op
              :control/request-id    request-id
              :control/deployment-id deployment-id
              :control/audience      audience
              :control/issued-at     now
              :control/expires-at    expires-at
              :control/actor         {:actor/principal actor-principal
                                      :actor/key-id    actor-key-id}
              :control/payload       payload}]
    (assoc-in base [:control/actor :actor/signature]
              (sign-actor base actor-secret))))

;; ---------------------------------------------------------------------------
;; finding
;; ---------------------------------------------------------------------------

(def finding-codes
  "本模块已发布的 `:code` 注册表（finding-v1 §6）。域是 `control.*`。"
  finding/codes)

(defn finding? [x] (finding/valid? x))

(defn- run-steps
  "按序跑，**第一次拒绝就停**。每一步是 `(fn [acc] → {:step :result :findings})`,
   `acc` 里有前面各步的结果（stage 要用 offer 回的 `:missing`）。

   写成线性的一串而不是嵌套的 `or`：嵌套那种我数错过两次括号，而括号数错在这里
   的后果是「某一步的拒绝被跳过去」。"
  [steps]
  (reduce (fn [acc f]
            (let [{:keys [step result findings]} (f acc)]
              (if (seq findings)
                (reduced (assoc acc :findings (vec findings)))
                (-> acc
                    (update :steps conj {:step step :result result})
                    (assoc-in [:by-step step] result)))))
          {:steps [] :by-step {} :findings []}
          steps))

(defn run-deployment!
  "Studio and Server share the ordered deployment pipeline and stop on the
   first refusal. Preflight callbacks return {:step :result :findings}; command
   callbacks return {:result :findings} and receive prior results in :by-step.
   Hosts supply authorization, artifact discovery, signing and transport."
  ([preflight commands] (run-deployment! preflight commands {:activate? true}))
  ([preflight commands {:keys [activate?] :or {activate? true}}]
   (run-steps
    (concat preflight
            (for [op (cond-> [:offer :stage :load] activate? (conj :activate))]
              (fn [acc] (assoc ((get commands op) acc) :step op)))))))

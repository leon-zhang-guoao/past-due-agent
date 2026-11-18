(ns open.atlas.common.control-protocol.impl.signature
  "两个签名，各签什么**必须写死**（D3.16）。

   | | 覆盖 | 钥匙 |
   |---|---|---|
   | **actor** | 整个信封，只挖掉 `[:control/actor :actor/signature]` 这一个字段 | studio 的 actor 签名密钥 |
   | **service** | `{key-id, 时间戳, 请求体的 hash}` | service 的请求认证密钥 |

   **两把钥匙必须分开**，且都与数据面 graph token 的钥匙分开（D3.12：控制面与
   数据面同一个入口/同一把钥匙，一张查询 token 的泄漏就变成一次部署权泄漏）。

   ## 为什么 actor 签的是「整个信封减一个字段」

   只绑 `op + graph` 不够——一张五分钟有效的断言仍然可以被改成另一个版本号、
   另一个回滚目标、另一台 instance，或换一份 payload 重放。签整个信封之后，
   这四种全部堵死。

   挖的**只是签名那一个字段**，不是整个 `:control/actor`：挖多了就把
   `:actor/principal` 与 `:actor/key-id` 排除在签名之外，于是谁都能改主体和
   钥匙 ID——那正好是签名要防的东西。

   ## 为什么签的是规范 EDN，而不是拼字符串

   裸拼接有**边界歧义**：`(str a b)` 对 `[\"x\" \"yz\"]` 与 `[\"xy\" \"z\"]` 得到同一串
   字节，于是两份不同的输入共用一个签名。规范 EDN 是自定界的（一份文本只解析
   出一个值），且 `dialect/canonical-str` 已经把「什么叫同一份内容」定死在一处
   （D2.12）——两侧算出同一串字节靠的就是它。

   service 那一边同理：不拼 `时间戳 + body`，而是签一个**固定结构**的 map，
   body 以它的 hash 进去（定长、无歧义，也不必把整个请求体再序列化一遍）。

   ## 原语不在这里

   hex、SHA-256、HMAC 与**恒时比较**住在 `common/crypto`（零依赖）。它们此前是
   本 ns 的私有函数，而数据面 token 的 keyed digest（S5.9）要的是同一套——两份
   逐字节相同的恒时比较，改一处忘另一处的那天不会编译不过，只会有一处悄悄退回
   短路比较。本 ns 只定义**签什么**，怎么算是别人的事。"
  (:require [open.atlas.common.crypto :as crypto]
            [open.atlas.common.dialect :as dialect]))

(def signature-prefix crypto/mac-prefix)

(def sha256-hex
  "文本 → 64 位小写 hex（无 `sha256:` 前缀）。"
  crypto/sha256-hex)

(defn- hmac [^String secret ^String data] (crypto/hmac-sha256 secret data))

(def constant-time=
  "两个规范签名串是否相等。见 `common/crypto/constant-time=`：形状不对不进比较,
   比较本身交给 JDK 平台承诺的恒时实现。"
  crypto/constant-time=)

;; ---------------------------------------------------------------------------
;; actor：签整个信封，只挖签名那一个字段
;; ---------------------------------------------------------------------------

(defn actor-signing-input
  "信封 → actor 签名要算在上面的那串**文本**。

   `dissoc-in` 掉 `[:control/actor :actor/signature]`——**只这一个字段**。
   若信封里本来就没有 actor 或没有签名，结果与「有签名再挖掉」逐字节相同：
   签名方与验签方因此算的是同一串字节，不需要约定「签名位置放什么占位值」。"
  [env]
  (dialect/canonical-str
   (if (map? (:control/actor env))
     (update env :control/actor dissoc :actor/signature)
     env)))

(defn sign-actor
  "信封 + 密钥 → 该信封的 actor 签名串。调用方负责把它放回
   `[:control/actor :actor/signature]`。"
  [env ^String secret]
  (hmac secret (actor-signing-input env)))

(defn actor-signature-matches?
  "信封自带的 actor 签名，与用 `secret` 重算的是否一致。"
  [env ^String secret]
  (constant-time= (get-in env [:control/actor :actor/signature])
                  (sign-actor env secret)))

;; ---------------------------------------------------------------------------
;; service：请求认证。签固定结构的 map，body 以 hash 进去
;; ---------------------------------------------------------------------------

(defn service-signing-input
  "`{key-id, 时间戳, body 的 sha256}` 的规范 EDN。

   body 以 **hash** 而不是原文进去：定长、无歧义，也不必把请求体再序列化一遍
   （再序列化一遍就又多一处「什么叫同一份内容」）。"
  [{:keys [key-id timestamp body-text]}]
  (dialect/canonical-str
   {:service/key-id    key-id
    :service/timestamp timestamp
    :service/body-sha256 (sha256-hex body-text)}))

(defn sign-request
  [ctx ^String secret]
  (hmac secret (service-signing-input ctx)))

(defn request-signature-matches?
  [ctx ^String secret ^String presented]
  (constant-time= presented (sign-request ctx secret)))

;; ---------------------------------------------------------------------------
;; 形状
;; ---------------------------------------------------------------------------

(def well-formed-signature?
  "`\"hmac-sha256:\" + 64 位小写 hex`。形状先判、再比对：一个形状就不对的签名
   不该走到定长比较那一步（也让 finding 说得出「形状不对」而不是「不匹配」）。"
  crypto/well-formed-mac?)

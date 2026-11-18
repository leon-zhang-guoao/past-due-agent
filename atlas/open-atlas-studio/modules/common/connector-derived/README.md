---
title: "connector-derived — derived v1 的实现"
description: "SCI 沙箱、白名单、超时与两处契约裁定。"
---

# connector-derived

[derived v1](../../../docs/contracts/connectors/derived-v1.md) 的实现。契约是规格：
操作块封闭（`:type` + `:expr`）、白名单封闭（扩项即契约升版）、batch ✗、mutation ✗、
未知操作键 `:reject`。

## 为什么现在做它

`"derived"` 在 [D2.15](../../../docs/design/20-dialect.md#d215-连接器契约注册表核心方言只钉公共骨架)
的 v1 注册表里**写着，但一行代码都没有**。这本身就是这个仓库最忌讳的形态——
「写了却没生效」，而且它比一般的失效更隐蔽：写入端会接受 `:type "derived"`（契约已注册），
装载时才发现没有实现，于是它成了一个**只在运行时才暴露的空承诺**。

[D2.25](../../../docs/design/20-dialect.md#d225-组已移除收纳交给-derived-属性藏交给-listed)
把组的「一次问全」交给了 derived，于是它从「早该补」变成「挡在路上」。

## 裁定

### ① 闸是一张 **allowlist 扫描**，不是白名单绑定（2026-08-24 实测更正）

**这一条我原本写错了。** 原文是「SCI 的缺省行为是未在 `:bindings` 里给出的符号一律
解析不到，所以只列白名单即可，契约那句『显式拒绝 I/O、反射、`eval`、命名空间操作』
在构造上成立」。实测把它证伪了两次：

| 表达式 | SCI 缺省 |
|---|---|
| `(slurp …)` / `(System/getenv …)` / `(java.io.File. …)` | 拒绝 ✓ |
| **`(eval 1)`** | **可用** ✗ 而契约点名拒绝 `eval` |
| **`(atom 1)` / `(swap! …)`** | **可用** ✗ |
| **`(loop [] (recur))` / `(while true …)`** | **可用** ✗ |
| **`(.length s)`** | **可用** ✗ 实例 interop，通往 `getClass` → 反射 |
| **`(map inc [1 2])`** | **可用** ✗ ——**SCI 默认就带一份 clojure.core 子集** |

最后一行是根子：`:bindings` 里的白名单是在**加**，不是在**限**。

**所以闸是 allowlist 扫描**：遍历整棵语法树，**任何不在允许集合里的符号一律拒绝**。
允许集合恰好三块——契约白名单 + 我们挑的 core 纯函数子集；一小组**有界**的特殊形式
（`if` `cond` `let` `->` 之类）；本 resolver `:input` 的 local name 与表达式自己
`let` 出来的局部名。

这样契约那句「白名单 v1 封闭，扩项即契约升版」**才真的成立**：它不再依赖「SCI 恰好
没给某个东西」，而是**我们没写进允许集合的东西一律进不来**。allowlist 的失败方向也
是对的——漏给一个，作者当场看到拒绝；黑名单漏禁一个，没有人会发现。

契约白名单里有几个不是 clojure.core 的，自己实现：`round2` `sum` `date`
`days-between` `before?` `after?`，以及 `upper` `lower` `trim` `blank?`（clojure.string）、
`abs` `round` `ceil` `floor` `sqrt` `pow`（Math）。**一个都不许多给。**

**闸有强弱两路，因为 SPI 的 `validate-source` 签名只有 `[source-config]`**——它看不到
resolver，于是看不到 inputs，也就做不了完整 allowlist（每个 input local name 在它眼里
都是越界符号）。所以：1-arity 做**弱扫描**（只拒「一定不可能是 local name」的：带
命名空间限定、`.` 开头或结尾）；完整 allowlist 走 `validate-source-with-inputs` 与
装配期的 `compile-source`。**别把 1-arity 当完整的闸**——`slurp` 在它眼里长得像一个
input local name。

### ② `today` 与「纯函数」是契约里的一处自相矛盾——按可测的那一半处理

契约的 conformance 要点写着「**同输入同输出（纯函数性质抽查）**」，而白名单里有
`today`——它读时钟。两句话不能同时为真。

**处置**：`today` 从**求值上下文**取，不从系统时钟直接读。上下文由调用方给，缺省是
求值时刻。于是：

- 纯函数抽查对**不含 `today` 的表达式**逐字成立，且这一条可测；
- 含 `today` 的表达式在**给定同一个上下文时**同输入同输出——它仍然是纯函数，只是多了
  一个显式入参。

这与本仓库既有的两处同则：scope projection v1 §1「实现不得读取 latest、当前时间或池快照
之外的状态」，以及越界写标记的 `:at` 是必传参数而非 `(System/currentTimeMillis)`——
**读时钟的函数没法在测试里断言**。

**契约需要补一句**（owner 裁定后由 owner 改）：把「同输入同输出」限定为「给定同一求值
上下文」。实现先按可测的那一半走，差异记在这里。

### ③ 运行期 finding 没有通道——v1 用可注入的 sink，并把它标成权宜之计

契约说「求值失败或超时：该输出属性**缺席并记 finding**，不失败整个实体」。

「缺席」有现成语义（`single` 可 nil，engine 天然支持）。「记 finding」**没有通道**：
`compile-source` 的返回形状是 `(fn [input-params eql-params] → single-map|nil | [row…])`,
里面没有 finding 的位置，而 `spi/fail!` 是**抛**——那会失败整个实体，正是契约禁的。

**处置**：求值失败时属性缺席，finding 送进一个**可注入的 sink**（缺省丢弃并按 `:warn`
记日志，不含行值——同 principles「source 失败与日志不携带业务数据」）。测试注入一个收集器
来断言 finding 的形状与 code。

**这是权宜之计，且到期日明确**：derived 的求值失败该在结果信封
（[§7.3](../../../docs/design/70-consumption.md#73-工具面与方言的对应)）里有一个「随行
事实」的槽位，那是 B7 的活。曾经指望的先例 `:filtered-sensitive` **已随访问档位退役而
删除**（D5.11 / D7.12，owner 裁定 2026-08-28），所以那是一个要新开的槽位。**在它出现之前，sink 的存在本身要在代码里写明**——
否则「记了 finding」会变成一句没有人在听的话。

### ④ 超时是**预算，不是保证**（2026-08-24 实测更正）

契约：「超时不拖垮同实体其他属性」。每次 `:expr` 求值各自带预算（参考实现 300ms）,
超时只让**这一个**输出属性缺席——这一半成立。

**但它停不下正在跑的那一个。** 实测：`future-cancel` 对 `(loop [] (recur))` 返回
`true`，而 800ms 后那个线程**仍在烧**。所以：

- **无界迭代由裁定 ① 的 allowlist 在发布闸上拒掉**——那才是真闸。「能写出来但会超时」
  实际是「能写出来并且永久烧掉一个线程」；
- 超时只兜「有界但病态」的工作量；
- 承载线程是 **daemon**：失控的求值不会挂住 JVM 退出。第一版用 `future`（非 daemon 池）
  时，测试跑到 `shutdown-agents` 就停在那里——这个坑是实测踩出来的，不是推演的。

「加个超时就安全了」是这一段最容易写下的那句错话，所以实测结论记在这里。
预算缺省随 [OQ-RESILIENCE-DEFAULTS](../../../docs/design/90-open-questions.md#oq-resilience-defaults)。

### ⑤ 没有连接，`validate-source` 仍要判

derived 不写 `:source-ref`，没有连接概念，所以 `health-check` 无意义（恒 `:ok`）。
但 `validate-source` 是**写入端**的闸：`:expr` 缺失或空、操作块有未知键、**预解析失败**
一律拒绝。契约明说「表达式在编译期预解析，解析失败即校验失败」——那是这个连接器唯一
真正的闸门，它挡的是「发布了一个永远算不出来的属性」。

`compile-batch-source` / `compile-mutation` / `compile-mutation` 一律结构化拒绝
（能力表：batch ✗、mutation ✗）——**不是静默不支持**。

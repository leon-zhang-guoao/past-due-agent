---
title: "studio/pool — B4 池内核"
description: "Datahike 池、action 写面、导出/导入往返。B4 的架构裁定与边界。"
---

# studio/pool — B4 池内核

[B4 的交付](../../../docs/design/80-build-plan.md)是「Datahike 池、action、写工具与
MCP 工具面、搜索索引」，退出条件三条：

1. **删掉 store、导入最后一次导出，精确重建出同一模型**（自动化测试）；
2. 每个 action 的拒绝都是**结构化的且不含配置值**；
3. `expected_revision` 冲突被正确拒绝。

## 本模块确立的四根之一

这是本仓库**第一个 `studio` 模块**。在此之前 [D1.8](../../../docs/design/10-platform-boundary.md)
的「common 不得依赖 studio/server」只在**一个方向**上可验证——没有 studio 模块，也就没有
可依赖的东西。现在两个方向都成立：本模块 `:local/root` 依赖 `common/dialect`，而 common
的每一份 `deps.edn` 里都查不到 studio。**边界是 classpath 事实，不是编码约定。**

## 裁定

### ① 池按**概念**建模，不整块存方言 EDN

最省事的做法是给每张 graph 存一个 `:graph/dialect-edn` 字符串。**否决**：
[§7.3](../../../docs/design/70-consumption.md#73-工具面与方言的对应) 的
`query_model`（「无描述的属性」「`:format :code` 缺 `:values`」）、`resolve_target`
（四个离散裁决）与 [D3.5](../../../docs/design/30-workspace-graph.md#d35-版本在-graph-层对象无版本演化靠新增)
的差异报告（**同 id 内容 hash 对比**）都要**查得动单个对象**。整块 EDN 让这三样退化成
「读出来、parse、在内存里遍历」——那不是池，是一个存字符串的地方。

于是七概念 + 三注册表各自是 Datahike 实体，`:db/ident` 化的属性名与
[dialect-v1](../../../docs/contracts/dialect-v1.md) 的条目键一一对应。

### ② 校验复用 B0，池里**不写第二个 schema**

action 的投机事务（`d/with`）跑完之后，把对象拼回方言形状交 `dialect/read-dialect`
判——finding 即拒绝、干净才提交。理由同 dialect-v1 §7 对 B2 的要求：**转换器不许自带
第二个 schema**。池是第二个写入者，同一条理由。

代价是每次写要拼一次方言形状；这个代价是**故意付的**——两套校验规则总有一天会分叉，
而分叉的那一天没有人会知道。

> **这一条我原本写的是「把受影响的对象拼回去」，那是错的**（B4.1 实施时纠正）。B0 的
> 跨条目不变量——先注册后使用、组成员须已登记且同 namespace、词条多绑定须有互异
> `:term-context`——**按定义**是跨对象的。只拼受影响的那几个，判出来的是另一个问题的
> 答案，而那个答案会看起来很像对的。
>
> 于是投机校验拼的是**整池**。178 属性量级下这个开销可忽略，但**代价要点名**：
> B4 里没有 workspace / graph（裁定 ⑤），所以此刻「整池 = 一个 namespace 宇宙」这个
> 前提恰好成立。**B5a 多 workspace 真进来之后，这一步必须换成按投影范围拼**——否则一个
> workspace 里的脏对象会挡住所有人的写，而报出来的会是「你的模型不合法」。

### ③ 导出是**池级**的规范形 EDN

退出条件 1 要的是「精确重建」，所以导出必须无损且**逐字节确定**。复用 B0 的规范形纪律
（键排序、缺省不写、100 列版式常量）。

**不是**方言文件：池装着多个 workspace 的多张 graph 与共享对象，而方言文件是**一张
graph 的投影结果**。把导出写成方言等于提前做了投影，而投影是 B5a
（[D3.11](../../../docs/design/30-workspace-graph.md#d311-scope-菜谱-v1own-的-namespace-前缀集-钉版本引用)
的 scope 菜谱）的事。

### ④ `expected_revision` 是**对象级**的

不是全局单调计数。理由：池是多 workspace 共享的（D3.1），全局 revision 会让两个互不相干
的 workspace 互相踩到——而那种冲突是**假的**，假冲突教会人无脑重试，真冲突于是也被无脑
重试掉。

### ⑤ 投影（池 → 方言）**不在 B4**

B4 的边界到「池里有对象、action 写得进、导出导入转得回来、搜索索引得到」为止。
`<workspace>/<graph>` 的 scope 菜谱解析与圈图是 B5a。

### ⑥ 真实装料 = demo 模型

池的测试不用玩具数据：把 [`fixtures/demo/model.edn`](../../../fixtures/demo/README.md)
（188 属性 / 23 词条 / 12 张码值表）装进池，再导出、删库、导回来、比对。
**退出条件 1 于是是对一份真模型说的**，而不是对三个属性的玩具说的。

那份模型原本是 B2 转换器每次测试现场跑出来的；转换一次性做完后工具与语料一并
退休（2026-08-24），模型落成上面那个文件，池直接读它——**装料是同一份，只是不
再现场转一次**。B2 那三层权宜之计（`scope.edn` 的 `:rename-*`/`:correct-*`、
`additions/`、手写 `signoff.edn`）随之作废：它们本来要迁进池，现在没有可迁的
东西了，模型自己就是事实源。要看它们走 `git log -- fixtures/b2 modules/legacy-import`。

## 开池即迁移：pre-D2.29 的池被**修好**，不是被拒收

`open-pool!` 不只是「建库 + 装 schema + 连接」，它还把池就地迁到
[D2.29](../../../docs/design/20-dialect.md#d229-分支即属性navigation-并入属性)
（owner 裁定 2026-09-02：**旧池要修，不是拒**）。实现在 `impl/migrate.clj`,
公开入口 `migrate-pool!`。

D2.29 之前写下的池里坐着两样今天寻址不到的东西：`:navigation` 对象
（`:pool.object/type :navigation`、id 是 `["ns" "名"]`、字段在 `:pool.navigation/*`、
靠 `:pool.child/parent` 挂在 namespace 上）与 `:pool.attribute/collection?` datom。
不迁的后果不是报错而是**静默变小**：`store/all-objects` 用一张没有 navigation 那行的
类型表去读它，读出一个空条目，`objects->dialect` 再把它丢掉。

三种旧形状，与当年手改 `fixtures/demo/model.edn` 的那三种**逐条对应**：

| 旧 | 新 |
|---|---|
| navigation + 同名 `:collection? true` 属性（`all_cpos`） | **合并成一条 join 属性**：`:target`/`:cardinality` 取 navigation 的，`:label`/`:description`/`:synonyms` 优先取属性自己的，`:collection?` 与标量 `:type` 去掉 |
| 只有 navigation（`cpo_details`） | 变成 join 属性 `ns/名`，**revision 原样带过来**（对象换了注册表，不是重新开始） |
| 只有 `:collection? true` | `:target` 指向**自己的 namespace**、`:cardinality :one-to-many` |

几条要点，都是有意的：

- **只丢那两个退役键**。旧的 collection 属性合法地带过 `:format`/`:term`/`:unit`,
  而 join 的键面封闭。这里不猜作者想留哪个：按 D2.29 写出去，让下面那道方言闸
  **点名**拒绝——一条能照着改的 finding 好过一个无声消失的键。此时迁移一个字节
  都不写，`open-pool!` 抛异常（findings 在 ex-data 里）：一个既不旧也不新的池
  被交出去，正是这条迁移要防的静默半支持。
- **全有或全无**。一次 `d/transact`，所以没有「迁了一半的池」这种状态可回来面对：
  提交前崩溃 = 旧池原样，下次开池整个重跑。
- **靠遗留 datom 判断该不该迁，不设 marker**：于是「已经迁过」与「迁完又崩过」
  分不开——本来也不需要分开。已迁的池上是两次 `:find ?e .`（撞到第一条就停）。
- **像任何一次写一样留审计**：每动一个对象一条 mutation 记录，同一次事务提交,
  出处是 `audit/system-provenance`——**没有入口**（`:unattributed`，audit 裁定 ②
  点名了迁移这类调用方）、actor 是机器身份并写明是哪条裁定。**不伪造人**。
- **schema 里的死属性留着**：Datahike 没有便宜的「卸载 `:db/ident`」，所以
  `:pool.navigation/*`、`:pool.child/parent`、`:pool.attribute/collection?`
  在老池里仍装着，只是**没有任何 datom 用它们**。新建的池从来不装。

## 切片

| 切片 | 内容 | 对应退出条件 |
|---|---|---|
| **B4.1** | Datahike schema（七概念 + 三注册表）、`execute_action` 写面、`expected_revision`、投机校验、池级导出/导入往返 | 1、2、3 —— **三条全在这一片** |
| **B4.2** | 单一 registry → `tools/list` + dispatch；`query_model`；`resolve_target` | 交付物「写工具与 MCP 工具面」 |
| **B4.3** | 搜索索引：类型化命中、BM25 类型内排序、按 revision 键控 | 交付物「搜索索引」 |
| **B4.4** | `confirm_candidates` 候选签字通道 | [§7.3 待钉第 2 条](../../../docs/design/70-consumption.md#实施期待钉的四处不阻塞默认走法先记下) |

B4.1 承担全部三条退出条件，所以它先做、也做得最重。

## B4.4 落在池里的三样东西

裁定本身写在 [`modules/common/candidates/README.md`](../../common/candidates/README.md)
（提议归 common，处置归调用方）。池这一侧的落点：

1. **候选从池挖**（裁定 ①）：`pool-candidates` 把 `as-dialect` 喂给 common/candidates。
   没有第二条挖法——两条挖法分叉的那天，owner 会以为自己看的是全部。
2. **确认记录是独立实体**（裁定 ②）：它有自己的 `:pool.confirmation/key`，
   与十二个对象类型**不共用** `:pool.object/key`。于是 `as-dialect`、投机校验、
   导出的对象清单（全部经 `store/all-objects`）**查不到**它——「不进模型」不是一句
   要靠人遵守的话。**不可变**同理：`execute_action` 按类型表收类型，`:confirmation`
   不在那张表里，没有任何写面寻址得到它。
   记录随导出走（信封多一个可缺席的 `:pool/confirmations`）：一份连一次重建都撑不过
   的记录，说它不可变是自欺；而它与 `:pool/objects` 分开，制品的 content-hash 于是
   仍然只由模型决定（D4.3）。
3. **provenance 从记录推**（裁定 ③）：`provenance` 答 `:signoff` 还是 `:authored`,
   判据是「有没有哪条确认记录的 `:produced` 点着它」。不另存一个 `:origin` 标记——
   两个真相会分家，而挂在对象上的标记还会被后来一次无关的 `update` 静默 retract 掉。

**`confirm_candidates` 为什么是单独一条 action**，而不是 `execute_action` 的一个
`:type`：确认记录拼不回方言（而投机校验一定要拼回去）；一次签字产出**好几个对象**
（词条注册项 + n 条绑定），拆成 n+1 次调用就让「签了一半的候选」成为池里可表达的
状态——那正是「签字退化成一次多余的点击」的形态。完整理由见 `impl/confirm.clj`。

## `execute-actions`：几条动作作为**一件事**写进去

`execute-action` 的单位是一个对象。有些提案的单位不是一个对象——从库结构生成的一张表
是 namespace + n 个属性（标量的与 join 的，D2.29）+ 两条 resolver，写进去四条就是一个
**会答错的实体**：登记了却没人产出的属性、身后没有 resolver 的 join。拆成 n 次调用，「半张表」
就成了池里可表达的状态，而这个状态和「某人正在手写、还没写完的一张表」逐字节相同。

这就是 `confirm_candidates` 当年为什么是**一条**而不是 n+1 条的同一条理由
（见 `impl/confirm.clj`），从另一个方向走到。

**顺序是调用方的。** 动作按给定顺序逐个校验，每一条都对着**前面几条留下的池**判——
这是一个批次能先 create 一个对象、再 update 它的原因（D2.29 之后池里没有父子对象，
逐条判的只剩身份与 revision；方言那道闸是对批次**终态**判一次）。顺序给错了得到的是
点名对象的结构化拒绝，**不是静默重排**：这里不知道两种可能的顺序里调用方想的是哪个。

**原子是真的，不是近似。** 校验全程跑在投机数据库上，一条不过就一个字节都不写；
通过之后累积的事务**一次 `d/transact`** 提交——`schema/object->tx` 用字符串 tempid
寻址、`:pool.object/key` 是 unique identity，所以同一批次里两次动作打在同一个对象上
落到同一个实体，而不是两个。

不是第二个写面：每一条动作走的是 `execute-action` 用的同一个 `prepare` 与同一次投机校验。

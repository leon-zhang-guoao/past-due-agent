---
title: "studio/tools — B4.2 建模面工具"
description: "execute_action / query_model / resolve_target，与单一 registry 的裁定。"
---

# studio/tools — B4.2 建模面工具

[§7.3 建模面](../../../docs/design/70-consumption.md#73-工具面与方言的对应)那张表的
三个工具。机制在 [common/toolkit](../../common/toolkit)，这里只声明工具本身。

## 裁定

### ① registry 的**机制**在 common，**工具**在 studio / server

principles「MCP 与工具面」要的是「一份 registry 同时生成 `tools/list` 与 dispatch，
MCP/HTTP/UI 共用它」。B7 的读面工具（`list_entities` / `describe_entities` / `search` /
`execute_eql`）跑在 **server** 上，而 [D3.8](../../../docs/design/30-workspace-graph.md#d38-server-是版本架无共享池)
的 server 是「版本架」、[D1.8](../../../docs/design/10-platform-boundary.md#d18-四个-namespace-根依赖严格单向)
禁止它依赖 studio。

机制若住在 studio，server 就只能**再写一份**——而「在一处声明、在另一处分派会漂移」
正是这条规则要防的失败，写两份是它的加强版。于是机制下沉到 `common/toolkit`，
工具本身各归各家。

### ② 「不挂载」不是「挂载了永远拒绝」

principles：没有写钥匙，写工具在 `tools/list` 里**缺席**、dispatch 也**不认识**它们。
这必须是 registry 的能力过滤，不是每个工具自己 handler 开头的一句 if——一个存在且
永远失败的工具会诱使客户端反复重试，还告诉攻击者门后有什么。

**`tools/list` 与 dispatch 必须由同一次能力过滤算出来**，否则「列出来了但调不了」
或「没列出来却调得动」两种漂移都会出现，而且都要等到客户端真调了才看得见。

### ③ B4.2 **不做网络传输**

交付到「registry 能算出 `tools/list` 的 JSON 形状、能按名字 dispatch」为止。stdio /
HTTP 挂载归 Builder MCP（[8.4](../../../docs/design/84-builder-mcp.md)，2026-08-28
立项；原 B8a 归属已随 B8a 并入 B8 而失效）。理由：传输一进来，测试就得起进程、开端口，而这一片
要证的是**声明与分派同源**——那件事在进程内就证得完，起端口只是把它埋深。

### ④ `resolve_target` 是闸门，不是搜索的包装

四个离散裁决（`:exact-unique` `:exact-ambiguous` `:fuzzy` `:none`），
**只有 `:exact-unique` 带出 `:target` 与可执行参数**。

**不设置信度浮点。** BM25 是语料内排序，同一个正确匹配这里 26 分那里 1.0 分，
没有哪个阈值两次意味着同一件事；歧义绝不靠排名打破。

拿着一个格式正确的 action 的 agent **一定会去跑它**，所以安全不能活在参数旁边的一句
警告里，只能是**参数的缺席**。

### ⑤ 缩写表是**输入**，不是本模块的内容

`no` 在一个领域是 `number`，隔两个 namespace 就是 `purchase order`——principles 明说
「Studio 与模型都不许猜」。本模块吃一张缩写表，**默认为空**，由 owner 填。

空表不是缺陷：不认识的缩写会让 `resolve_target` 落到 `:fuzzy` 或 `:none`，那是诚实的
回答。**替 owner 猜一张表**才是缺陷——猜错的形态是把写目标解到另一个属性上，而那种
损坏是静默的。

### ⑥ `query_model` 的每一条缺口都要**指向一个能修它的 action**

不是「你这儿有问题」，是「你这儿有问题，这个 action 修它」。一份修不动的体检报告
读一次就没人再读了。

## 覆盖的账单项（§7.3）

| 缺口 | 出处 |
|---|---|
| 属性无 `:description` / `:synonyms` | §7.3 |
| namespace 无 identity | principles「entity 由 namespace 推导」——没标就是空，空是诚实答案，且要被报成一条 action 就能修的缺口 |
| 声明漂移 | [D2.2](../../../docs/design/20-dialect.md#d22-属性单一声明漂移在切版本时拒绝) |
| `:format :code` 缺 `:values` | §7.3 |
| 词条零绑定 | §7.3 新账单项 |
| 未分组属性数 | §7.3 新账单项。**它是账单项，不是缺口**——报在报告的 `:metrics` 一节（数字 + 按 entity 的分布），**不带 `:action`**，理由见下面那一段 |
| 多绑定缺 `:term-context` | §7.3 新账单项 |

**指纹-`:values` 偏差**（[D7.6](../../../docs/design/70-consumption.md#d76-治理闭环fingerprint-发现码值人来标注语义)）
不在本片：指纹是 B7。

## 「未分组属性」是指标，不是缺口

组是给**陪衬属性**用的收纳（[D2.25](../../../docs/design/20-dialect.md#d225-组陪衬属性的收纳与查询宏)）：
核心业务属性本来就该不分组，identity 属性更是**禁止**入组。把它和真缺陷放进同一张
gap 清单，会诱使人把它追到 0——而追到 0 的唯一办法是把业务属性塞进一个含糊的杂物组，
**正好是 D2.25 要防的那件事**（同一条理由让描述覆盖率拆成两个永不合并的指标，见下一节）。

所以它不是具名查询：`query_model` 的报告里单独一节 `:metrics`，给出数字与按 entity 的
分布，**没有 `:action`**——报告给的是一个看得见的账，不是一条催人执行的命令。
具名查询因此是**七条**，不是八条。

## 已知缺一半：组内属性的描述覆盖率

`:attribute/missing-description` **只报未分组属性**。这不是遗漏，是
[D2.25](../../../docs/design/20-dialect.md#d225-组陪衬属性的收纳与查询宏)：描述覆盖率
**拆成两个指标、永不合并**——组内属性以组描述兜底。把两者混进一条查询，
「把业务属性塞进一个含糊的杂物组」就能刷指标。

**但另一半现在一条查询都没有**：一个组内属性没描述、它所在的组也没描述，当前谁都
报不出来。补法是加一条 `:group/missing-description`（注册组无 `:description`，
其成员因此没有兜底）。在今天的装料上它会是 0——demo 模型自 D2.25 组移除之后
一个注册组都没有，早先 B2 那 7 个组也是每个都写了描述。
**但 0 与「没查」是两回事**，这正是这一段存在的理由。

## B4.4 追加的第四个工具：`confirm_candidates`

候选签字通道（[§7.3 待钉第 2 条](../../../docs/design/70-consumption.md#实施期待钉的四处不阻塞默认走法先记下)）。
`:capability :write`，走同一份 registry，没有写钥匙时**缺席**（裁定 ②）。

handler 与 `execute_action` 同则：**直接转 `pool/confirm-candidates!`，不在这里加一条
校验**——加一条就是第二个 schema 的开端。工具面只做一件本层的事：把 MCP 客户端那侧的
`concept_id`（snake_case，同 `expected_revision`）翻成池里的 `:concept-id`，翻译只此一处。

## 从 source 建默认模型：`describe_source` / `plan_from_source` / `generate_from_source`

一个库自己就知道它有哪些表、哪些列、声明了什么键。让建模的人重打一遍不是建模，
是转录——而转录正是打字错误变成类型事实的地方。这三条工具把
[SPI 第七个分派点](../../common/connector-spi) 读回来的结构，变成池里的登记动作，
把人的工夫留在真正需要的地方：词条、描述、派生 resolver。

### ⑦ 机器决定**结构**，不决定**意义**

列 → 同名属性（[D2.1](../../../docs/design/20-dialect.md#d21-字段映射恒等缺省) 的恒等
缺省正是这件事安全的理由）、声明的主键 → `:identity?` 与一条 by-key resolver、
外键 → link。这些是机械的。

`:label` / `:description` / `:term` **一律不写**——[D2.26](../../../docs/design/20-dialect.md#d226-词条glossary-的机制本体)
明说机器不许给概念命名，而从历史列名编出来的一句话是**读起来像决定的填充文本**。
这些位置留空，由 `query_model` 现成的缺口查询接住，那本来就是接下来那一遍要做的事。
方言**要求**而机器给不出的位置（没有对应方言 `:type` 的列类）摆 `fill-in`——
那是个非法值，照原样跑会被结构化拒绝，比静默写进一个猜出来的类型便宜得多（同裁定 ⑥）。

namespace 名就是**表名原样**，不做单数化、不去前缀（owner 裁定 2026-08-26）：那些是
关于意义的猜测，而一个要人注意到并撤销的猜测，比一个一眼就能改的平名字更糟。

### ⑧ all-or-nothing 的单位是 **relation**，不是这一次运行

一张表是 namespace + n 个属性（标量的与 join 的，D2.29）+ resolver，写进去一半就是
一个会答错的实体，所以一张表整体成败——这是 `pool/execute-actions` 存在的理由。

但**被 block 的表跳过，其余照写**。几百张表的库是常态，里面必然混着没有主键的视图、
不能不加引号写进 SQL 的标识符、没有方言类型的列类；为其中一条拒绝全部，就是一个
用不完的工具。唯一不许随之留下的是**挂在被跳过的 namespace 上的东西**——指向它的
link、建在它上面的子集合——它们会指向从未创建的属性，所以一并去掉，并带一条
warning 点名是谁把它们带走的。

### ⑨ 规模是设计输入，不是以后再说

一次运行至多 `max-relations`（200）张关系，**超了拒绝，绝不截断**：静默地建掉前 200 张
表，正好制造「以为库建好了，其实没有」这种最坏的失败。浏览大库走
`describe-schema` 的 `:keys? false`（跳过无法批量取的逐表主键/外键往返），选定之后
再按 `:names` 把键读回来；用没读键的描述去规划会把每张表都报成「没有主键」——
一个长得像真答案的错答案——所以规划器**拒绝**这种描述。

### ⑩ 写面自己重新规划，不接受调用方递来的 action

接了，它就只是 `execute_action` 换了个名字，而「这些是从 source 结构生成的」这句话
没有任何东西担保。规划在这一侧做，调用方能改的只有勾选。

---
title: "common/search — B4.3 搜索索引"
description: "三类平级文档、类型内 BM25、陈旧即弃。与 resolve_target 的分工裁定。"
---

# common/search — B4.3 搜索索引

[D7.11](../../../docs/design/70-consumption.md#d711-语义层的呈现与搜索词条是文档洪泛在结构上解决)
的机制本体。规模先说死：**全语料属性 + 词条 + entity 不足一万文档**，FTS5 毫秒级
——**效率瓶颈从来不是速度，是结果质量**，本模块的全部预算花在后者。

## 裁定

### ① 三类**平级**文档，语义文本只索引一次

词条、entity、属性是三类文档（组随 [D2.25](../../../docs/design/20-dialect.md) 退役）。**绑定属性的语义文本只在词条文档里索引一次，
不向属性文档展开复制。**

这是「洪泛在结构上解决」的全部内容：demo 模型里 `cis.party/customer_no` 绑着 7 个属性,
真实 CIS 里 `created_by` 绑着 381 个。复制展开的话，搜「客户编号」得到 7 条一模一样的
命中，搜「录入人」得到 381 条——**把所有别的东西埋掉**。不复制，就得到一条词条命中,
带绑定计数与 scope 内的绑定清单。

### ② BM25 **只在类型内**排序，类型间固定顺序

固定顺序：scope 内精确名 → 词条 → entity → 散属性。

**不设置信度浮点。** BM25 是语料内排序，同一个正确匹配这里 26 分那里 1.0 分，
没有哪个阈值两次意味着同一件事。**歧义永不靠排名打破**（principles「MCP 与工具面」
对 `resolve_target` 的同一条论证，这里是它的读侧镜像）。

### ③ 与 `resolve_target` 共用**匹配层**，不共用**裁决层**

两者哲学相同、词汇不同：
- `resolve_target`（写闸门）：`:exact-unique` / `:exact-ambiguous` / `:fuzzy` / `:none`;
- `search_attrs`（读解析）：直中 / 经词条命中 / `:ambiguous` / `:none`（D7.12）。

**共用的是匹配层**——标识符折叠（`to_acct_no` / `toAcctNo` / `to acct no` 同形）、
缩写表展开、词条 label/synonym 查找。**不共用裁决层**：把两套裁决合成一套，等于让
读侧那个「错了只是白看一眼」的失败模式，给写侧那个「错了是静默的模型损坏」的定契约。

反过来，让匹配层各写一份则是本仓库反复栽的那个坑——两份实现总有一天分叉，而分叉的
那天没有人会知道。

### ④ 陈旧即弃，**不带警告地用**是错的

索引按 revision 键控。revision 对不上 → **整体作废重建**，不存在「属性文档新、
词条文档旧」的撕裂态。

**B4 的键是池 revision，不是 graph 闭包 revision。** 设计说的是后者
（[D7.11](../../../docs/design/70-consumption.md#d711-语义层的呈现与搜索词条是文档洪泛在结构上解决)：
字典是钉版本引用，闭包 hash 天然覆盖），但 B4 还没有 graph（pool README 裁定 ⑤）。
**B5a 必须把这个键换掉**——与投机校验那处「整池 = 一个 namespace 宇宙」是同一笔债,
一并记在这里。

### ⑤ **2/3-gram 自己切**，不装自定义 tokenizer

FTS5 内置的 `unicode61` 不切 CJK。**不引 C 扩展**（要按平台编译 `.so`，而本仓库的
测试要在任何一台机器上跑得起来）。做法是索引期与查询期**都由我们把文本切成 2/3-gram**,
以空格分隔喂给 FTS5，让它把 n-gram 当普通词。

**CJK 与 Latin 都出 gram。** 这一条最初写的是「中文出 gram、英文按词」，实施时被顶了
回来——`docs/principles.md`「存储与同步」说的是**字符** 2/3-gram，并把
`HP compnay` → `HP company ltd.` 的**拼写容错**明确算作它买来的能力：**搜索框被要求的
是容错不是语义**。只按整词索引，一个错字就一条都不命中，而建模的人手打字段名时打错
是常态。**principles 为准，这一条已改。**

Latin 侧出「整词 + 它的 gram」：整词要留着，否则「scope 内精确名」那一档（裁定 ②）
就分不出精确匹配与拼错匹配。**标识符折叠排在 gram 之前**——`sso/order-no` /
`sso/order_no` / `sso/orderNo` 折叠后是同一个字符串，于是产生逐字节相同的 gram 流。

这条直接决定了前几轮补同义词的价值：31 个属性当时没有中文同义词，**中文搜不到**——
不是排序靠后，是索引里根本没有那些字。

### ⑥ 吃**方言模型**，不吃 Datahike 连接

本模块的输入是 `(模型, revision)`。studio 侧喂池投影出来的模型，server 侧喂制品装载
出来的模型——**两边索引出的东西因此逐字节可比**，与 D4.10「两层都必须等价」同则。

## 边界：谁过滤，过滤什么

[§7.3](../../../docs/design/70-consumption.md#73-工具面与方言的对应) 把 `search` /
`search_attrs` 列在**读面**。可见性在 2026-08-28 之后只剩一个坐标：**这张图的 grant
有没有**——有就读得到它声明过的一切，没有就一个字节都读不到
（[D5.11](../../../docs/design/50-security.md#d511-访问档位整套退役给与不给的粒度是-graph)）。
grant 判定在数据面那一道门上（D5.4 执法点唯一），**不在本模块**：索引不认识 token,
拿得到索引就是已经过了那道门。原先写在这里的「B7 加档位过滤、`:restricted` 命中不得
出现、计数按 token 的档位视图算」**随档位一并退役**——那道缝的输入没有了,
留着只会指使后来的人去实现一个不存在的过滤。

本模块**自己**执行的过滤只有一种：`:listed? false`
（[D2.28](../../../docs/design/20-dialect.md#d228-listed这个属性是不是这张图对外语义的一部分)）。
它是**建模判断**、对所有 grant 一视同仁，engine 的 catalog 对它一律不列
（[§7.3](../../../docs/design/70-consumption.md#73-工具面与方言的对应)：它是这一层
唯一还会让 catalog 少东西的原因），点名它得 `:unknown-attribute`,
与点名一个从未建模的属性逐字相同。索引照收就会指着一个 catalog 否认存在的名字——
**「查不到」与「不存在」在消费端逐字相同**，调用方分辨不出，只会照着搜索结果去查,
然后拿到一个否认它存在的拒绝。同一个模型的两个视图必须对「有什么」给同一个答案。

`build-index` 另收一个 `:visible?` 谓词，那是**调用方给的文档抑制器**，不是模型语义:
它只回答「这次调用把语料缩到哪一块」（例如按 over-limit finding 自己给的补救办法缩小
语料），缺省全可见。它与档位无关。

## server 侧：同一个索引，不是第二份实现

[D7.10](../../../docs/design/70-consumption.md#d710-server-携带搜索索引从已装载制品重建)：
server 从**已装载制品**重建索引，按 graph 版本键控、对不上即弃。`modules/server/mcp`
的 `search_attributes` 工具因此调本模块（只 require 公共 ns），不自带匹配层——
它曾自带一份子串扫描，与这里各说各话，那正是裁定 ③ 说的「两份实现总有一天分叉」。

## Precision-first global discovery

Global search first tries conjunctive normalized whole Latin words; CJK still uses normalized grams with contiguous-word validation. Exact names remain first, then type-local BM25 order. Only an empty exact/whole-word result enters fuzzy matching. Exceeding the envelope does not broaden the search. Request/response shape, default envelope 200, explicit max-hits, stale rejection and listed/visible filtering remain unchanged. Candidate membership intentionally changes: incidental fragment matches no longer accompany successful whole-word results. Scoped resolution and write-side matching are unchanged.

### Fuzzy candidate criteria

Each normalized query word must independently match a document word. A known whole word anchors its position; a long word cannot compensate for a missing short word. Unknown words generate candidates from fragment postings, symmetric deletions, and prefixes. Acceptance permits a prefix of at least two characters, a verified single edit (including adjacent transposition) for words of at least four characters, or IDF-weighted query-fragment coverage >= 0.45 with at least two shared fragments for words longer than three characters. This coverage is not pg_trgm similarity or a public confidence score. IDF is computed from visible-document fragment frequencies; common fragments are downweighted, not deleted. Final document validation requires all per-word groups before counting the envelope. BM25 remains type-local.

An experimental hard df cutoff of 20% lost genuine matches (`coverge rate` found only one of four exact coverage_rate attributes), so it was rejected. The replacement recovers short transpositions such as `raet` while retaining every query word's constraint. Thresholds 0.35, 0.45 and 0.55 yielded the same results on the measured TRDC queries; this does not establish a universal optimum. Boundary trigrams and field-weighted columns are deferred pending evidence; the FTS schema is unchanged. Private normalized words/descriptions and postings rebuild with the index; glossary descriptions are not copied into bound attributes.

### Local verification (2026-09-25)

TRDC v4.2.0 vocabulary projected into the search input has 565 documents. Legacy vs new hit counts:

| Query | Legacy | New |
|---|---:|---:|
| coverage rate | 423 | 6 |
| coverge rate | 423 | 6 |
| coverage raet | 335 | 6 |
| covrage rate | 389 | 6 |
| coverage rtae | 367 | 6 |
| coverage | 326 | 85 |
| rate | 349 | 6 |
| 覆盖率 | 0 | 0 |

All five two-word queries retain all four attributes named exactly coverage_rate. Six hits include other matching metrics; they are not six identically named attributes. Chinese recall still needs Chinese labels/synonyms in the model. Template descriptions cannot supply missing business meaning. No Graph content, runtime deployment or database migration is included in this search change. These measurements are local retrieval results, not an end-to-end Agent benchmark.

Reproduce current corpus results from `modules/common/search` with `clojure -M verification/search-corpus.clj /path/to/trdc-support.edn`; the report includes the input SHA-256 and result IDs. Validation: search 22 tests / 167 assertions, MCP analytics 14 tests / 49 assertions; all pass.

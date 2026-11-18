---
title: "demo 模型与 demo 库"
description: "平台的 demo 数据：一份 committed 的方言模型，与它查的那份 SQLite 库。两者一起构成所有模块测试的真实装料。"
---

# demo 模型与 demo 库

平台的 demo 数据，三份，配套：

| 文件 | 是什么 |
|---|---:|
| [`model.edn`](model.edn) | **方言模型**：207 属性（188 标量 + **19 join**）/ 23 词条 / 48 resolver（35 条 sql + 13 条 derived）/ 12 张码值表 |
| [`demo-db/`](demo-db/README.md) | **SQLite 库**：模型里的 resolver 查的就是它（`schema.sql` + `seed.sql`） |

模型与库**必须一致**——模型说某个属性从 `msos.ship_to_city` 取，库里就得有那一列。
一致性不是靠人盯，是靠 `modules/integration` 的 `business-chain-test`：它拿这份
模型编译出引擎、连这份库、跑真 EQL。列名对不上、表缺了、数据不满足业务规则，
那里当场红。**改一边就得改另一边，然后跑那个测试。**

`demo-fixture-test` 负责让 `model.edn` **自己过一遍 B0**
——committed 的东西更该验，因为转换器退休之后不再有任何一步会替它验。

旁边的 `graphs.edn` 是**这份模型播出来的种子图**：`demo/{cis,master,sales,purchase}`
与它们的选择器。这份名单原先硬编码在 `apps/studio/.../builder_server.clj` 里，于是
按别的模型播种的 Studio 也照样起四张 `cis.*` 空图；owner 2026-09-06 裁定种子图由模型
自己声明（[doc 104 §8.17.6](../../docs/design/104-graph-packages-and-market.md)，
OQ-STUDIO-SEED）。**这四张图与选择器是既成事实**，跟这里的模型一样不得为了让别处
变绿而改。

## 为什么模型是 committed 的一份文件

它曾经不是。2026-08-24 之前，模型是每次跑测试**现场从存量语料转换**出来的：
`fixtures/b2/legacy/`（29 文件 / 6,339 行旧方言语料）过 B2 转换器，加
`scope.edn` 的范围裁剪、`additions/` 的作者端补充、`signoff.edn` 的 owner 签字,
六个模块各跑一遍。

那套机制的理由是「快照会悄悄过期」——语料改了而快照没跟上，测试仍然全绿。
**转换一次性完成之后，这条理由就失效了**：没有上游了，模型自己就是事实源,
不是谁的缓存。于是：

- 转换器（`modules/legacy-import`）与存量语料（`fixtures/b2/`）**删除**，
  它们是一次性的东西，做完就到期了；
- 模型落成本文件，六个模块直接读它，不再各跑一遍转换。

**要找回去看 git 历史**：语料、转换规则、`scope.edn` 的每一条排除、
`signoff.edn` 里 owner 逐条的裁定与理由，全在
`git log -- fixtures/b2 modules/legacy-import` 里。活文件只放当前有效的东西,
把版本控制该干的活塞进活树，只会让下一个读者分不清哪些是活的。

## 测试怎么用它

模型是**方言文件**而不是池导出，因为读它的模块里有 `common/search`——
common 不依赖任何 studio 模块，这一条在 classpath 上可验证（D1.8），
给它一份要 `import-pool!` 才读得懂的文件就把这条方向破了。

- 只要方言的（search / tools / graph / version / integration）：直接
  `(edn/read-string (slurp (io/resource "model.edn")))`；
- 要池的（pool）：`dialect->objects` → `import-pool!`，与池自己的装料同一条路。

各模块 `:test` alias 的 `:extra-paths` 把本目录放上 classpath，所以
`io/resource "model.edn"` 与 `io/resource "demo-db/schema.sql"` 都取得到。

## 改模型

今天：直接改 `model.edn`，它自己要过 B0（`business-chain-test` 会验）。

将来：这正是**池**（`modules/studio/pool`）该接的活——建模动作走
`execute_action`，有版本、有 diff、有回滚，改完导出回本文件。本文件的形状
（规范形方言、键字典序、缺省不写）与 `pool/as-dialect` 的输出一致，
就是为了那一天不用改任何读它的人。

## 档位裁定表去哪了

`tiers.md` 记的是 owner 逐个 entity 给 `:sensitivity` 定档的理由。访问档位已于
2026-08-27 整套退役（[D5.11](../../docs/design/50-security.md#d511-访问档位整套退役给与不给的粒度是-graph)）
——属性不再有这个键，那份裁定也就没有了对象。文件随之删除，理由留在 `git log`：

```
git log -- fixtures/demo/tiers.md
```

一次性的东西做完就到期，这与 `fixtures/b2` 和 `modules/legacy-import` 同一条处置。
今天要更窄的可见范围，做法是**切一张更窄的图**，不是在属性上挂一个等级。

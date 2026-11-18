---
title: "B1 竖切 fixture"
description: "单张 graph + SQLite 种子，engine 与 connector-sql 共用的端到端验收集。"
---

# B1 竖切 fixture

`graph.edn` 过 B0 reader **零 findings**（改动后须复验）；`schema.sql` 是它的 source 侧。
两个模块的 `:test` alias 都把本目录挂进 classpath，端到端用例读同一份——engine 与
connector 若对同一份 fixture 的理解不一致，测试会当场分叉，这正是要的。

## 档位分布 —— 已随访问档位退役

这里曾经列着三档的素材分布，为 B1 退出条件「三种档位的 token 得到 D5.4 的三种
处理」服务。访问档位已于 2026-08-27 整套退役
（[D5.11](../../docs/design/50-security.md#d511-访问档位整套退役给与不给的粒度是-graph)）:
属性不再有 `:sensitivity`，`graph.edn` 里那五处声明因此删除，本节的分布表也随之
失去对象。分布与理由留在 `git log -- fixtures/b1/graph.edn fixtures/b1/README.md`。

**竖切的其余部分一个字节没动**：这份 fixture 承担的是 engine、connector-sql 与
artifact 三个模块对**同一份模型**的一致理解，那件事与档位无关。

## 结构覆盖

- **组**：`cis.master.vendor` 的 `g_audit_info`（绑注册表 `common.audit/g_audit_info`），
  成员是两个属性——退出条件「组查询与逐个点名成员结果逐字相同」（D2.25）用它；
- **join 属性**：`:cis.master.vendor/parts`（一对多）与 `:cis.master.part/all_parts`（具名查询锚）；
- **batch**：两条，父键不同——`demo_part_by_id`（`"single"`，`IN (:sku_nos)`）与
  `demo_parts_by_vendor`（`"list"`，`IN (:vend_nos)`，同时消掉那条 join 的 N+1）；
  vendor 1003 无 part 提供空位用例；
- **per-parent 窗口**：`demo_parts_by_vendor` 的 `:limit {:default 2 :max 5}` +
  `:order`——vendor 1001 有 3 个 part、1002 有 2 个、1003 没有，一次调用同时覆盖
  「切窗」「不切窗」「空位」三种情形（sql-v1「分页在 batch 上是 per-parent 窗口，不下推」）；
- **eql-param**：`demo_all_parts` 的 `:prod_code` 锚上收窄；
- **`:limit`/`:order`**：list resolver 的分页与排序声明。

## 连接

`:jdbc_url` 是 `${B1_JDBC_URL}`——凭证与连接串永不写字面值（sql-v1）。测试装配时
指向内存 SQLite 并执行 `schema.sql`。

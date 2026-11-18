---
title: "旧 Graph 迁移到 Semantic Contract（已退役）"
description: "把 Studio Pool/Graph 的旧模型迁成可验证、可试查询、可发布和可回滚的 Semantic Contract 草稿。"
---

> **已退役 2026-09-19**：本页描述的 `prepare-migration` / `prepare-migration-document` /
> `migration-source-evidence` / `prepare-upgrade` 动作与 `semantic-migration` 命名空间已删除，平台只读
> 契约 2.0（[任务书](../design/tasks/contract-upgrade-and-retirement.md)）。旧 Graph 的唯一升级路径是
> `open-atlas-graph-builder` skill：读旧方言 `model.edn` 直接产出 2.0 文档，再经 Import 导入。以下正文只作
> 历史记录，其中的映射表仍可参考。

# 旧 Graph 迁移到 Semantic Contract（已退役）

本指南迁移的是**作者模型**。旧 Graph、已发布版本和正在服务的 Artifact 不会被原地改写。迁移结果先成为
Workspace 中一份有 revision 的 Semantic Contract 草稿；只有人在 Release 页完成检查并明确发布后，才会产生新的
不可变 Artifact。回滚仍是把 serving 指针切回旧版本。

## 1. 三种文件不要混用

| 文件 | 用途 | 能否由本指南导入 |
|---|---|---|
| Semantic Contract EDN | vocabulary、execution definition、binding、logical source、ownership、publication | 能；Studio 的 **Import Contract EDN** 或 Builder MCP `semantic_draft action=import` |
| legacy dialect/model EDN | 旧 Pool 中的 namespace、attribute、resolver、source 等 | 不能直接当 Contract；先生成迁移建议 |
| Graph bundle / Artifact zip | 已切出的不可变运行版本 | 走 Graph/Server 的 bundle 安装流程，不走 Contract import |

`import` 与 `save` 使用同一套 Workspace 编辑权限、Contract schema、binding checker 和 `expected_revision`。它不会导入
credential，不会发布，也不会激活 serving。

## 2. 旧对象到新契约的映射

| 旧模型 | Semantic Contract | 迁移时必须确认 |
|---|---|---|
| namespace / Thing | `:vocabulary :entities` | 业务边界、label、description、真实 identity |
| attribute | `:vocabulary :attributes` | type、nullable、cardinality、target、description |
| resolver 的 input/output | `:execution :resolvers`（Definition） | effect、one/many、输入和输出树、过滤参数 |
| resolver 内 source 配置 | `:execution :bindings`（Binding） | endpoint/SQL、input/output/parameter map；不得含凭据 |
| source | `:execution :sources`（logical Source） | 只保留 id、kind、effects；部署连接在 Studio/Server 单独配置 |
| link | alias；有条件外键关系用 reference | 方向、nullable 时的行为、类型相容性 |
| graph recipe / members | `:graph` + `:ownership` | 每个 entity/resolver/operation 的唯一 owner |
| 发布版本 | `:publication` | release/version、source/revision、精确 capabilities |

Definition、Binding、Source 必须分开。Definition 说“输入什么、输出什么”；Binding 说“该 Source 上怎样执行与映射”；
Source 只声明逻辑能力。数据库 URL、API token、口令和证书引用属于服务器端连接配置。

## 3. 开始前冻结证据

1. 用 `list_graphs` 和 `inspect_graph` 记录 graph 名、graph id、当前 serving 版本和 findings。
2. 导出当前 Graph bundle，并记录当前 serving 版本；这是回滚锚点。
3. 对 SQL source 调 `describe_source {source, prefix, names}`。它只读 metadata，不读业务行。
4. API 没有通用 schema introspection。使用现有 `add_thing` / `add_relation` 的 plan 模式，以明确的 `path`、`sample`、
   `data_path` 做一次受控 probe；先审查 probe evidence，暂不 `apply`。
5. 把无法由证据决定的问题列入 review 清单。不要让 AI 根据列名或搜索 filter 猜 identity。

至少保留这些 unresolved decisions：

- 哪些列由 PK/unique constraint 或业务契约证明为 identity；复合 identity 的顺序是什么。
- 每个字段是否 nullable。样本没出现 null 不能证明非空。
- Resolver 是 `:one` 还是 `:many`；API 返回一个对象的样本不能证明端点永远单值。
- 外键是否真代表业务关系、方向是什么、空外键用 `:reference/on-null :omit` 是否正确。
- 同一输出有多个 Resolver 时，哪一个 Binding 进入 `:execution :selection`。
- filter 是来源真实支持的收窄条件，还是仅用于搜索的普通字段；后者不能充当 identity。

## 4. 生成迁移草稿

通过 Builder MCP：

```text
semantic_draft {graph: "workspace/graph", action: "read"}
semantic_draft {graph: "workspace/graph", action: "prepare-migration"}
```

`prepare-migration` 只返回 proposal 和 unresolved decisions，不保存。对 SQL 关系补充来源证据后，可调用：

```text
semantic_draft {
  graph: "workspace/graph",
  action: "migration-source-evidence",
  migration: {source: "source-id", mappings: {...}}
}
```

再把已由人确认的 vocabulary 和 resolver mappings 交给
`prepare-migration-document`。调用时必须带当前 `expected_revision` 和 proposal 中的 `source/hash`；旧模型在期间发生变化时，
服务以 `:migration/source-changed` 拒绝，不会在移动目标上继续转换。

依赖为空、资产全部由根 Graph 拥有的 1.0.0–1.3.0 草稿可用 `prepare-upgrade` 生成 1.4.0 建议；建议会补齐
`:graph/terms`、`:ownership/terms` 和 1.4 所需 capabilities，但不会替用户发明 conditional references、filters、
bounded source collection 或 query examples。含嵌入式依赖资产的旧草稿必须先拆成 1.4 Graph Package 的独立快照，
不能通过修改版本号把外部资产变成本 Graph 所有。

## 5. 补齐新版本描述字段

对每个 entity、attribute 和 resolver 写能让未见过源系统的人理解的描述：业务含义、来源证据、单位或格式、nullable 的语义、
以及限制。不要把 SQL、JSONPath 或 JDBC URL复制到业务描述里；这些属于 Binding 或部署连接。

推荐的最低描述完整度：

- Entity：对象是什么、身份为何可靠。
- Attribute：业务含义、来源字段、单位/格式、null 的含义。
- Resolver：以什么输入读取什么结果、one/many、filter 如何收窄。
- Reference/Alias：为何相连、方向、空值行为。
- Query example：业务目的、初始 entity、参数、预期 cardinality 和字段形状。

多个属性绑定同一 `:attribute/term` 只表示共享业务含义。例如供应商主数据的 `vend_no` 与订单的 `from_acct_no` 都可绑定
`common/vend_no`。这不会自动建立 reference、join、alias 或 identity；关系仍需 FK/API 契约等独立证据并显式声明。
Source 字段大小写可由 Binding 保留，例如物理列 `sku_No` 映射到规范化 Semantic 属性 `:demo.part/sku_no`。

## 6. 静态校验、导入与 revision

在 Studio 的 **Semantic Contract** 页选择 **Import Contract EDN**。文件读取后，服务器立即执行完整校验并保存新的 draft
revision；失败时返回带 `:code`、`:path`、`:severity` 的 findings，旧 revision 保持不变。

外部 AI 使用 Builder MCP 可直接完成同一动作：

```text
semantic_draft {
  graph: "workspace/graph",
  action: "validate",
  text: "<完整 EDN>"
}

semantic_draft {
  graph: "workspace/graph",
  action: "import",
  expected_revision: 0,
  text: "<完整 EDN>"
}
```

已有草稿时使用 `read` 回执里的 revision。revision 过期返回 `:semantic.draft/revision-conflict`。`save` 也接受完整 EDN，
但 `import` 更清楚地表示整份外部文件进入草稿库。两者都不会自动 publish。

静态校验能证明 schema、引用闭包、ownership、capability 和 connector binding 的结构一致；它不能证明 credential 可用、SQL
能在目标数据库执行、API 响应仍符合 probe，也不能证明业务定义正确。

## 7. 实际来源与 EQL 差分

确认 Contract 中每个 logical source id 都已在同一 Workspace 注册，kind 一致，凭据变量已在服务器端配置。然后对旧路径与新草稿
运行一组相同的 EQL：

1. 每个 Entity 至少一条 identity lookup。
2. 每条关系至少一条带 nested join 的查询。
3. 每个 filter 一条命中和一条不命中。
4. nullable、空集合、来源超时和权限不足各一条边界用例。
5. 用 `lenient? false` 验证严格模式；只有产品明确需要部分结果时再用 `lenient? true`，并检查 `:engine/issues`。

Query Studio 的 **Entity Data** 是初始实体上下文，例如 `{:demo.order/id 1001}`；EQL filter 参数写在查询属性或 join 的参数表上，
不能塞进 Entity Data。Contract 自带 query examples 时，Studio 可将示例装入这两个编辑器。

## 8. 发布、激活与回滚

通过所有差分后，在 Release 页做 cut-check，再切出新的 immutable Artifact。Artifact 内容哈希固定；任何更改都必须生成新版本。
发布与 serving 激活是两个动作，默认 workspace write token 不应获得生产 activate 权限。

回滚步骤：停止把流量切向新版本；把 serving 指针切回第 3 节记录的旧 Artifact；运行旧版本 smoke query；保留新 Artifact、
findings、trace 和差分结果用于修复。不要编辑已发布文件，也不要把草稿 revision 冒充运行版本。

## 9. 完成判据

- 导入后 `read` 重开得到同一 canonical Contract 和递增 revision。
- 所有 logical source 都能在部署环境解析，但 Contract/Artifact 中没有 credential 值。
- 静态 findings 为零；真实来源 lookup、relationship、filter 和失败边界通过。
- Golden Scenarios 和旧/新 EQL 差分经人审阅。
- 新 Artifact 有 digest，serving 切换和旧版本回滚均有记录。

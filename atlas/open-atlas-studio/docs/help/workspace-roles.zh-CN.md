---
title: "Workspace 角色与权限"
description: "Studio workspace 的三种角色（owner、editor、member）各能做什么：导入、发布、编辑、Source 与凭据、token、授权；成员如何切换 workspace；按图单独授权的范围；对应的拒绝代码。"
---

# Workspace 角色与权限

图属于 workspace。把别人加进你的 workspace 时选的角色，决定他们能对这个 workspace 里的图做什么。
以下规则读自代码，权威出处见文末。

## 1. 三种角色

| 角色 | 怎么得到 | 一句话 |
|---|---|---|
| **owner** | 建 workspace 的人；首个管理员 | 什么都能做，包括新建图和给别人授权 |
| **editor** | owner 在成员管理里加人时选 editor | 维护 workspace 里**所有已有的图**，并管理 Source 与凭据 |
| **member** | owner 加人时选 member | 只能看；owner 可以按图单独授予编辑权 |

加人时只能选 **member** 或 **editor**：owner 不能经邀请授予（拒绝代码 `workspace-role-invalid`）。
只有 owner 能加人（`workspace-owner-required`），被加的人必须先有 Studio 账号（`studio-user-not-found`）。

## 2. 权限表

| 操作 | owner | editor | member | member + 按图授权 |
|---|---|---|---|---|
| 看 workspace 里所有图 | ✅ | ✅ | ✅ | ✅ |
| 编辑图（recipe、成员、默认查询、包名） | ✅ | ✅ | ❌ | ✅ 仅该图 |
| **导入**到已有的图（更新） | ✅ | ✅ | ❌ | ✅ 仅该图 |
| **导入**时新建图 | ✅ | ❌ | ❌ | ❌ |
| **发布**、回滚 | ✅ | ✅ | ❌ | ✅ 仅该图 |
| 签发自测 graph token | ✅ | ✅ | ❌ | ✅ 仅该图 |
| Source 与凭据（声明、连接、改变量、删除） | ✅ | ✅ | ❌ | ❌ |
| 签发 workspace token（Builder MCP） | ✅ | ✅ | ❌ | ❌ |
| 新建、改名、复制、合并、归档、删除图；设默认图 | ✅ | ❌ | ❌ | ❌ |
| 给成员按图授权 / 撤销 | ✅ | ❌ | ❌ | ❌ |
| 加人、改角色 | ✅ | ❌ | ❌ | ❌ |

要点：

- **editor 能导入和发布，但不能新建图。** 要新图时，由 owner 先建好（或由 owner 导入一次），之后 editor 往里导入更新、发布。
  导入包里只要有一张目标图不存在，整个导入就要求 owner。
- **按图授权只给编辑这一张图**，不给 Source / 凭据 / workspace token，也不能新建图。
- 已经是 editor 的人不能再按图授权（会被拒绝）：editor 本来就能编辑全部图。
- 授权只有 owner 能给，避免「被授权的人再授权下一个人」的扩散。

## 3. 成员要先切到你的 workspace

所有操作只对**当前 workspace** 生效。成员登录后在界面的 workspace 切换器里选你的 workspace；不切换时操作别的
workspace 的图会以 `graph-outside-current-workspace` 拒绝。API：`GET /api/studio/context` 列出可选的 workspace，
`POST /api/studio/context/workspace`（`{"workspace": "<名>"}`）切换，选择会保存。

## 4. 经 MCP 也一样

Builder MCP 的 `import_semantic_graph`、`upload_graph`、`publish_graph` 走同一套检查，不另设规则。
另外 `upload_graph` 需要带写权限的 workspace token（见 [Builder MCP 独立接入](builder-mcp.md)），
而 workspace token 只有 owner 或 editor 能签发。

## 5. 常见拒绝

| 代码 | 含义 | 处理 |
|---|---|---|
| `graph-editor-required` | 对这张图没有编辑权 | owner 把你设为 editor，或按图授权 |
| `workspace-owner-required` | 这一步要 owner（新建图、导入时新建、授权、加人等） | 请 owner 操作 |
| `workspace-editor-required` | 要 owner 或 editor（Source、凭据、workspace token） | owner 把你设为 editor |
| `graph-outside-current-workspace` | 当前 workspace 不是图所在的那个 | 先切换 workspace（第 3 节） |
| `workspace-role-invalid` | 邀请时选了 member / editor 以外的角色 | 只能选 member 或 editor |

## 6. 出处

- 角色解析：`modules/studio/graph/src/open/atlas/studio/graph/impl/registry.clj` 的 `graph-access-role`
- 权限检查：`apps/studio/src/open/atlas/studio/graph_service.clj`（`require-editable!`、`require-workspace-owner!`、
  命名空间说明里的授权规则）
- 导入：`apps/studio/src/open/atlas/studio/semantic_package_service.clj`（更新要编辑权、新建要 owner）
- 发布与回滚：`apps/studio/src/open/atlas/studio/publish_service.clj`（`publish!`、`rollback-impl!`）
- 加人：`apps/studio/src/open/atlas/studio/builder_server.clj`（成员管理，只允许 member / editor）
- 测试：`graph_test.clj` 的 `workspace-members-see-all-team-graphs-but-editing-is-per-graph`；`builder_server_test.clj`

---
title: "apps/studio — Studio Builder 与内嵌 runtime"
description: "Studio 的两个监听口、两种 token、Semantic Contract 工作流与验证入口。"
---

# apps/studio

Studio 同时提供 Builder UI 和一台内嵌 runtime。生产和开发启动方式分别见 bin/README
与 dev/README。

Docker 源码构建、Compose 部署、持久化及升级回退见
[Builder Docker 部署指南](../../docs/guides/builder-docker.zh-CN.md)。

Datahike pool 默认启用应用内自动回收：每 5 分钟检查增长，空闲时先备份、核验再回收，
正常间隔 12 小时；维护期间新的 Builder 请求稍后重试。配置、备份轮换和故障处置见
[自动数据库维护指南](../../docs/guides/studio-pool-maintenance.zh-CN.md)。
旧版安装的一次性清理和离线回滚见 [维护脚本与生产操作指南](../../scripts/maintenance/README.md)。

## Graph 内容工作流

新的 Graph 内容统一由 Semantic Contract 1.4 生成和导入。外部 AI 使用：

- docs/guides/ai-semantic-contract.md
- docs/guides/examples/ai-semantic-contract-example-2.0.edn

在 Studio 创建或选择目标 Graph，让 AI 保留示例中的 portable workspace/Graph placeholder并输出单个 EDN document，
再从 Graphs → Graph Definition → Import Semantic Contract EDN 导入。Studio 显示当前目标，目标不同时经用户确认映射后保存 draft；
发布、部署和 token 步骤见 docs/guides/build-graph-and-tokens.md。

Source 自动建模页面、plan/generate HTTP 接口和底层公开规划器已经移除。Source 连接与 schema
读取仍可使用；Resolver、Binding、Alias、Equivalence 和导航必须在 Contract 中明确声明。
显式的局部编辑工具继续用于修复和维护。

## 两个监听口

- https://127.0.0.1:9631：Studio UI 和 Builder MCP（默认自签证书）。UI 使用会话 cookie，MCP 使用 workspace token。
- https://127.0.0.1:9632：EQL 和数据面 MCP，使用 graph token。

workspace token 与 graph token 不可互换。监听地址、TLS、签发与客户端配置见
docs/guides/mcp-setup.md。

## 前端产物

resources/public/js 是编译产物。scripts/run-studio.sh 会在产物缺失或早于 src-ui 时执行
release 构建；开发环境可用 shadow-cljs watch。OPEN_ATLAS_SKIP_UI_BUILD=1 只用于已有 watch
进程的情况。

## 验证

在 apps/studio 目录运行：

- npm run release
- npm run test:ui
- clojure -M:test

Semantic Contract 的导入、校验和旧 Artifact 拒绝由 semantic_service_test 覆盖。

### Import a model, then configure its Sources

Selecting an EDN model validates and saves the complete draft automatically.
The import page does not mount a full-file editor. Invalid syntax or contract
structure leaves the previous revision unchanged. Missing registered Sources
are retained as typed draft slots without credentials; release still requires
registered, compatible Sources and a compilable contract.

The persistent **Resolver Sources** list edits the same saved bindings used by
Object Review. Search or show only unbound entries; select a compatible Source
per binding, or use **Set all `<kind>` resolvers** to point *every* binding of a
kind at one Source, replacing what each pointed at (owner, 2026-10-01 — the
earlier fill-only-unbound rule left already-bound resolvers behind). An explicit
per-binding choice in the same request still wins. Refresh after registering a
new Workspace Source. The list fills the pane and uses windowed rendering,
including for models with hundreds of resolvers. Local/static/derived bindings
do not require a Workspace connection and are never touched by a per-kind set.

The draft service exposes `resolver-sources` and `configure-resolver-sources`;
configuration saves require the current `expected_revision`. EDN remains the
portable import/download format. Studio accepts only single-Graph model `.edn`
files through **Model → Import/Export → Import model (.edn)**. The browser
file-import workflow no longer switches to Graph Package import. A package envelope
(including a renamed `.package.edn` file) is refused without changing the draft.
Create separate Graphs and import the same model into each to keep independent
drafts with the same asset namespaces; configure their Sources separately.
Import never releases or activates a model.

### Rename a graph

In **Graphs**, select a graph and choose **Rename Graph** from its action menu.
Workspace owners can change the Studio display name, including for published
graphs. Names must be nonempty, at most 128 characters, and unique within the
workspace. Renaming preserves the graph UUID, deployment coordinates, published
versions, access tokens, and `/mcp/<uuid>` connection URL.

### Query logs and Activity details

Studio records `query-executed` events in `<studio-home>/logs/app.jsonl`, using
Server's query field names and shared Activity detail reader/UI. `execution-target`
is `draft` for Builder queries and `published` for the graph-token runtime.
Records include EQL input, graph, request ID, outcome and duration, plus connection
IP, User-Agent and MCP tool where available. Builder MCP records its workspace
Token ID and authorizing principal; browser draft queries record the signed-in
principal. Published queries record the verified graph Token ID and version.
Bearer secrets and query results are excluded. Activity details are attached only
after Studio's existing workspace visibility filter. EQL includes input parameters.

Log lifecycle: see [shared retention policy](../../docs/guides/log-retention.md).

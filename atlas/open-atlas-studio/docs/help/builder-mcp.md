---
title: "MCP 配置"
description: "两个 MCP 端点（Studio Builder 的 workspace token 与数据面的 graph token）、各自的取 token 方式、Claude Code 与 Claude Desktop 的客户端配置、HTTPS 说明、三份默认 agent 定义的用法，以及拒绝的读法。"
---

# MCP 配置

平台向 agent 暴露**两个** MCP 端点，各有各的钥匙、各有各的台账：

| | 端点 | 钥匙 | 做什么 |
|---|---|---|---|
| **Builder MCP**（控制面） | Studio Builder 监听器上的 `/mcp`，默认 `https://127.0.0.1:9631/mcp` | **workspace token**，前缀 `oaw_v1.` | 改模型：加属性 / resolver / link、建图、改菜谱、发布 |
| **数据面 MCP** | Server 或 Studio 数据面上的 `/mcp`，默认 `http://127.0.0.1:3009/mcp`（Studio 是 `https://…:9632`） | **graph token**，前缀 `oat_v1.` | 读一张已发布的图：列实体、列属性、查询、算指标 |

**两扇门刻意分开**：一张泄漏的查询 token 不能变成部署权限。拿错门是结构化拒绝，而且
拒绝里会说清你带的是哪种钥匙——两个前缀存在就是为了让这句话不需要验签就能说出口。
`initialize` 握手里两边的 `serverInfo.name` 也不同（`open-atlas-builder-mcp` 与
`open-atlas-mcp`），一个同时配了两个端点的 agent 分得出是谁答的。

两个端点的**路由集是同一份实现**：`POST /mcp`（JSON-RPC）、`GET /mcp`（SSE）、
以及旧版传输 `GET /mcp/sse` + `POST /mcp/messages`。token 一律在
`Authorization: Bearer …` 头里呈现，在 JSON-RPC 解析**之前**被校验并剥离——它不进消息体、
不进参数、不进响应、不进日志。

## 1. 取 workspace token（Builder MCP）

在 Builder 里登录后打开 workspace token 面板（`/api/studio/workspace-tokens`）：

```bash
curl -s -X POST https://127.0.0.1:9631/api/studio/workspace-tokens/issue \
     -H 'content-type: application/edn' -b cookies.txt \
     --data '{:scope [:read :write] :label "modelling agent" :expires-in-days 30}'
```

- **scope 只有两种**：`[:read]` 或 `[:read :write]`，没有只写。**没有 write scope 时写工具
  直接不存在**——`tools/list` 里看不到它们，不是列出来再拒绝。
- **`expires-in-days` 必填**，1–365，面板提供 1 / 7 / 30 / 90 天。没有默认值：一个签发者
  从没看过的默认寿命，是一条没人权衡过的策略。
- `label` 必填、`description` 可选，超长是**拒绝**不是截断。
- **secret 只在这一次响应里出现**，台账存的是 keyed digest。
- 签发者必须是该 workspace 的 owner 或 editor——这把钥匙委托的正是「手改模型」。

撤销：`POST /api/studio/workspace-tokens/revoke`，body `{:token-id …}`。

## 2. 取 graph token（数据面 MCP）

见[建图与 token 授权 §9](../guides/build-graph-and-tokens.md#9-签发与撤销-graph-token)。要点：
Server 控制台 `POST /api/console/tokens/issue`，body `{:graph-id "demo/dvd"}`；一张 token
**恰好授权一张图**，跟随该图的 serving 指针；secret 只出现一次。
Studio 自测 token 用 `POST /api/studio/tokens/issue`，只在这台 Studio 的内嵌 runtime 上有效。

## 3. 客户端配置

### Claude Code

```bash
# 数据面（读一张图）
claude mcp add --transport http open-atlas \
  http://127.0.0.1:3009/mcp \
  --header "Authorization: Bearer oat_v1.…"

# Builder（改模型）
claude mcp add --transport http open-atlas-builder \
  https://127.0.0.1:9631/mcp \
  --header "Authorization: Bearer oaw_v1.…"
```

或者写进项目根的 `.mcp.json`：

```json
{
  "mcpServers": {
    "open-atlas": {
      "type": "http",
      "url": "http://127.0.0.1:3009/mcp",
      "headers": { "Authorization": "Bearer oat_v1.…" }
    },
    "open-atlas-builder": {
      "type": "http",
      "url": "https://127.0.0.1:9631/mcp",
      "headers": { "Authorization": "Bearer oaw_v1.…" }
    }
  }
}
```

`.mcp.json` 里带着的是一把**真钥匙**：它与口令同级，别提交到版本控制。

### Claude Desktop

Claude Desktop 走 stdio，所以本机 HTTP 端点要经一个 stdio↔HTTP 代理接进去。在
`claude_desktop_config.json` 里：

```json
{
  "mcpServers": {
    "open-atlas": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:3009/mcp",
               "--header", "Authorization: Bearer oat_v1.…"]
    }
  }
}
```

改完配置要重启 Claude Desktop。若客户端只支持旧版传输，端点上的
`GET /mcp/sse` + `POST /mcp/messages` 说的是同一件事、同一套认证，只是帧不同。

### HTTPS 与证书

Studio 的两个监听器（Builder 9631、数据面 9632）**默认都走 HTTPS**，三种用法：

1. **默认：自签证书。** `bin/start-studio.sh`（含 Docker 镜像）首次启动时用 `keytool` 在
   `$OPEN_ATLAS_STUDIO_HOME/tls/self-signed.p12` 生成一张自签证书（口令在同目录
   `self-signed.password`，权限 600），两个监听器共用，之后复用。证书名默认含 `localhost`、
   `127.0.0.1` 与主机名；要加别的名字，首次启动前设
   `OPEN_ATLAS_STUDIO_TLS_SAN="dns:studio.example.com,ip:192.0.2.10"`（已生成的要删掉重生成）。
   客户端要信任这张证书；`curl -k` 只适合手工验证，不要留在配置里。导出 PEM 给客户端：

   ```bash
   keytool -exportcert -rfc -alias studio -keystore "$OPEN_ATLAS_STUDIO_HOME/tls/self-signed.p12" \
     -storepass "$(cat "$OPEN_ATLAS_STUDIO_HOME/tls/self-signed.password")" > studio.pem
   ```

   `curl --cacert studio.pem …`；Claude Code / `mcp-remote` 等 Node 客户端用
   `NODE_EXTRA_CA_CERTS=studio.pem`。
2. **自带证书。** 设 `OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE`（**PKCS12**）与
   `OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD`；数据面默认用同一张。数据面要用另一张，设
   `OPEN_ATLAS_STUDIO_DATA_TLS_KEYSTORE` / `OPEN_ATLAS_STUDIO_DATA_TLS_PASSWORD`。
   只给 keystore 不给口令会**拒绝启动**。
3. **反向代理终结 TLS。** `apps/studio/compose.yaml` 的 `proxy` profile 起一个 Caddy：

   ```bash
   STUDIO_DOMAIN=studio.example.com STUDIO_TLS=ops@example.com docker compose --profile proxy up -d
   ```

   Builder 在 `https://STUDIO_DOMAIN`，数据面在 `https://STUDIO_DOMAIN:9443`。
   `STUDIO_TLS` 填邮箱即向 Let's Encrypt 申请证书（域名须公网可达）；默认 `internal` 用
   Caddy 自己的 CA。Caddy 到容器内一跳仍是 HTTPS（自签，不校验）。

开启 TLS 后监听器**只说 HTTPS**，不再接受明文；启动横幅、UI 里给出的地址跟着变成 `https://`。
`OPEN_ATLAS_STUDIO_TLS=off` 回到明文 HTTP（本机开发时 `clojure -M` 直接起的也是明文）。

- **默认只在回环，明文 HTTP 仍是合法的**（owner 2026-08-29 裁定）。把
  `OPEN_ATLAS_STUDIO_BUILDER_HOST` 放开出回环时，横幅会打印
  `⚠ the Builder listener is NOT on loopback`，未配 TLS 时再加一行说明 token 是**明文**传输。
- 数据面监听地址：`OPEN_ATLAS_STUDIO_DATA_HOST`（默认 `127.0.0.1`）、`OPEN_ATLAS_STUDIO_DATA_PORT`。
- **Server**（单进程、一个端口承载控制面、数据面、MCP 与 console）：设
  `OPEN_ATLAS_SERVER_TLS_KEYSTORE`（PKCS12）与 `OPEN_ATLAS_SERVER_TLS_PASSWORD` 即走 HTTPS；
  不设仍是明文，对外服务时用反向代理或配上 keystore。

## 4. 端点上有哪些工具

**数据面：七条**，`list_entities`、`list_attributes`、`search_attributes`、`execute_eql`、
`analyze_result`、`compute_metric`、`list_mutations`。调用方**不能选图**——bearer 就把图定死了。

**Builder：五十六条**，顺序本身是契约的一部分（`tools/list` 是 agent 最先读的东西）：

```text
1 list_graphs          2 inspect_graph        3 query_model          4 describe_source
5 read_object          6 create_graph         7 declare_source       8 add_thing
9 add_attribute       10 add_relation        11 add_link            12 add_metric
13 update_resolver    14 update_attribute    15 remove_objects      16 describe_objects
17 define_concept     18 unassign_term       19 change_graph_members
20 list_recent_queries 21 list_saved_queries 22 save_saved_query    23 delete_saved_query
24 publish_graph      25 reclaim_version
26 list_graph_tokens  27 issue_graph_token   28 revoke_graph_token
29 list_credentials   30 save_credential     31 remove_credential
32 list_sources       33 probe_api_source    34 remove_source
35 rename_graph       36 duplicate_graph     37 merge_graphs        38 archive_graph
39 restore_graph      40 delete_graph        41 rollback_graph      42 check_graph_cut
43 set_default_query  44 set_package_name    45 set_workspace_default_graph
46 reclaim_unused_versions
47 list_graph_access  48 grant_graph_access  49 revoke_graph_access
50 list_workspace_tokens 51 revoke_workspace_token 52 list_workspace_objects
53 semantic_draft     54 import_semantic_graph 55 get_graph         56 upload_graph
```

Graph 内容的新建统一使用 [AI Semantic Contract 生成说明](../guides/ai-semantic-contract.md)
和其中的 1.4 示例。外部 AI 输出完整 Contract EDN 后，通过 semantic_draft 的 validate、
import 或 save 写入有 revision 的草稿；这些动作不发布，也不激活 Graph。

其余 Builder 写工具保留给明确的局部维护和修复。它们不能替代完整 Contract，也不存在
从 Source 自动推断并生成整张 Graph 的入口。

宿主同时挂上数据面那七条时是 **63 条**：Builder 的五十六条在前，七条数据面工具在后，
描述、handler 与拒绝形状**与数据面完全相同**（学过一次不用重学），只多一个 `graph` 参数
——数据面的调用方由 bearer 指定图，这里由调用方说是本 workspace 的哪一张，省略则用 workspace
默认图。而且这七条读的是**草稿**：结果上带 `:draft true`，还带上真正发出去的 `:statements`。

Studio 的显式局部编辑也经过同一套服务端校验和审计。完整 Graph 内容仍以
Graph Definition 中的 Semantic Contract draft 为准。

**列出工作区的图（含用途）**：MCP 上 `list_graphs` 带 `with_guide: true`，每张图另返回
`:graph/label` 与 Semantic Contract 草稿里的 `:graph/usage-guide`（`:guide/purpose` 业务目的、
`:guide/asset-scope`、`:guide/applicability`、`:guide/limitations` 等，均可选，没有就不出现）。
不走 MCP 的程序可直接用 REST，同样只认工作区令牌所属的工作区，`:read` 即可：

```bash
curl -H "Authorization: Bearer oaw_v1.…" -H "Accept: application/json" \
  "https://127.0.0.1:9631/api/studio/graphs?with-guide=true&include-archived=false"
```

默认返回 EDN，`Accept: application/json` 时返回 JSON。字段与 `list_graphs` 相同（同一个服务函数）：
`graph/id`、`graph/name`、`graph/display-name`、`graph/qualified-name`、`graph/state`、
`graph/members`、`graph/can-edit?`，加 `with-guide=true` 时的 `graph/label`、`graph/usage-guide`。

**导出制品不是工具**：`GET /api/studio/graphs/export` 直接收 workspace token
（`:read` 即可，走同一个 `workspace-token/verify`），所以 agent 不必借浏览器会话，
但工具清单上不多一个名字（8.4.16：常用的留、非常用的回 GUI）。

改名、复制、合并、归档、删除、回滚等图生命周期从 2026-10-11 起也可经 MCP 完成（owner 裁定，推翻裁定九中这几条），执行的是 Graphs 页面同一套处理函数与权限；`delete_graph` 只删已归档的图且要求 `confirm` 重复图名。图授权（`grant_graph_access` 等）与工作区令牌的列出、吊销同样可经 MCP 完成；**签发工作区令牌仍只能由人在 Studio 中操作**，任何 key 都不能再签出 key。Source 自动初始建模已经退役，GUI、MCP 和专用 API 都不再提供该入口。

## 5. 默认数据面 agent 定义

数据面定义住 [docs/agents](../agents/)，正文可作为对应 agent 的 system prompt：

| 定义 | 用途 | 要哪个 MCP |
|---|---|---|
| [数据分析 agent](../agents/analyst-agent.md) | 问业务问题、取数、算指标、声明口径 | 数据面 `/mcp`（graph token） |
| [NiFi 流态查询 agent](../agents/nifi-status-agent.md) | 只读查询一台 NiFi 的运行状态 | 数据面 `/mcp`（graph token，图是 `<workspace>/nifi-local`） |

**怎么用**：

1. 打开对应的 md，按文件说明复制 system prompt 正文。
   文末的「出处与落点」一节是给人看的，**不随正文下发**。
2. 按上面 §3 给这个 agent 配好它需要的那一个 MCP 端点。**不要同时给一个 agent 两把钥匙**：
   分析与建模是两件事，合进一个 agent 只会让拿错门变成一道谜题。
3. 在 OMA 上托管时，正文落进 agent 定义的 `system` 字段；Workbench 侧怎么把这个 agent
   接进业务用户的 chat，见 [Workbench 接 OMA](../guides/workbench-oma.md)。

NiFi agent 的那张图不是随包的，要先按[接入 NiFi](../guides/connect-nifi.md) 建出来。这些定义里的
图限定名都**不写死**（个人 workspace 以邮箱命名），agent 靠 `list_graphs` / `list_entities`
知道自己持有什么。

## 6. 拒绝长什么样

一切拒绝都是 [finding-v1](../contracts/finding-v1.md) 的形状：**点名位置、给出期望、不泄漏值**。
在 MCP 上它以 `isError` 的 tool result 到达 agent——是一条可读可行动的信息，不是伪装成成功
的空结果。

```clojure
{:findings [{:code :token.authz/revoked
             :severity :error
             :path [:authorization]
             :message "…"}]}
```

会遇到的几类：

- **401，带一句点名钥匙种类的话**。Builder 端点的原话是：需要一张 workspace token
  （`oaw_v1.…`），并明说「数据面的 graph token 在这里不被接受」。
- **`:token.authz/*`**：`unknown`、`secret-mismatch`、`revoked`、`expired` → 401；
  `graph-disabled` → 403。
- **`:token.format/malformed`**：没带 bearer、body 不是规范 EDN、body 超限（413）。
- **写工具缺席**：只读 scope 的 workspace token 看不到写工具，`tools/list` 里就没有它们。
- **`:tools.draft/unavailable`**：宿主挂了草稿查询的声明却没接上运行环境——这是配线问题，
  拒绝直接点名配线。
- **`:tools.intent/name-ambiguous` / `name-unresolved` / `unknown-column` /
  `source-undecidable` / `probe-refused` / `placeholder-encoding`**：写面的六类常见拒绝。
  `:path` **点名你真填过的那个参数名**（`:via`，不是内部的 `:foreign_key`）——工具面有一张
  表把规划器的词换成声明里的词（8.4.25）；code / message / `:got` 是规划器的，原样传出。
- **`:cut.structure/empty-recipe`**：一个对象都没点名的图**切不出版本**，preflight 与
  Release 页同时说话（8.4.26）。
- **`:scope/mixed-output`**：发布时 resolver 的输出跨了菜谱的选择边界，见
  [建图与 token 授权 §5](../guides/build-graph-and-tokens.md#5-cut-check这张图现在能切吗)。
- **`:credential/missing`**：source 条目里的某个 `${VAR}`，这台 Studio 的凭证库与进程
  环境都答不上（[doc 106 §106.14 第 12 条](../design/106-need-grammar-to-dialect.md)，
  2026-09-07）。拒绝点名**变量名与它写在条目的哪个键上**，不出值。`declare_source` 的
  plan 里另有 `:requires {:vars [...]}`，每个变量一行「库里有没有 / 环境里有没有」——
  所以「缺哪个变量」在建之前就看得见，不必等第一次查询。值从界面填进凭证库，或由 agent
  用 `save_credential` 写入（owner 2026-10-11；凭证库按变量名存值，**库优先于进程环境**，
  改完即生效不必重启 Studio）。`list_credentials` 只列变量名、谁在用、有没有值，**从不
  返回值**；`remove_credential` 先 plan，仍被 source 引用时拒绝。`declare_source`
  本身**不写凭证库**（8.4.24 裁定十八）。

超限、冲突、缺失一律**拒绝而不是截断或猜**：静默的降级会让调用方对着少掉的东西推出错误结论。

## 7. AI 修改、上传、发布与测试

1. `get_graph({graph: "workspace/name"})` 取得完整 Contract EDN `text` 和草稿 `revision`。
2. 修改完整 EDN，保留目标 Graph、workspace 标识与 Source 绑定。
3. `upload_graph({graph: "workspace/name", text: "…", expected_revision: 3})` 保存修改；revision 使用读取结果。冲突时重新读取并合并，不能盲目重试覆盖。
4. `publish_graph({graph: "workspace/name", apply: true})` 生成发布版本并激活；检查返回的 `version` 与 `serving`。
5. 使用原 Graph token 调用**数据面** `execute_eql` 验证业务查询。token 跟随 serving 指针，无需重新签发。Builder 的同名查询工具读取草稿，不用于证明已发布版本正确。

上传失败不修改草稿，上传成功不自动发布。`get_graph` 沿用 Graph editor 访问检查；`upload_graph` 还要求 workspace token 的 write scope。跨 Graph 或带子图的可移植包使用 `import_semantic_graph`。

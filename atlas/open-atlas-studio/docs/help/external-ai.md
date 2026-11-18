# 外部 AI 建模与导入

有两条受支持路径。

## 文件路径

外部 AI 读取允许的 source schema/sample，生成单 Graph 的 Semantic Contract 2.0 模型 `.edn`。管理员选择目标 Graph，在 **Model → Import/Export → Import model (.edn)** 导入；校验通过后保存草稿，再配置 Sources 并验证查询。

可从随包提供的 [`orders-semantic-contract-2.0.edn`](orders-semantic-contract-2.0.edn) 开始。它包含模型定义、description、Term、Resolver、Binding 与逻辑 Source Slot，不含连接地址或凭据。Graph → Model → Import/Export 的模型导入不接受 `.package.edn` Graph Package。

## Builder MCP 路径

外部 AI 使用 workspace token 连接 Studio Builder MCP。它可以用 authoring tools 建模，或调用 `import_semantic_graph`：第一次 `apply=false` 获取计划，第二次携带原样 `targets`、`plan_id` 并设置 `apply=true`。Repository 或 revision 变化会使旧 plan 失效。

AI 看不到 credential；Source Gateway 只允许受限 introspection、少量 sample 和明确允许的只读 probe。

## 修改 Studio 中已有的 Graph

连接 Builder MCP 后，使用 `get_graph` 读取完整 EDN 与草稿 revision，修改后以
`upload_graph(graph, text, expected_revision)` 上传同一张图。该入口保留原 Graph 身份及 Source 绑定，
复用服务端校验与冲突保护，仅接受单 Graph 模型。

上传成功后调用 `publish_graph(graph, apply=true)` 创建新发布版本并激活。
随后使用原 Graph token 连接数据面 MCP 调用 `execute_eql`；结果中的 `env/version`
应与发布返回的 `serving` 一致。Builder 上的同名查询读取草稿，不能替代发布后的验证。

Builder 地址（`<Studio 地址>/mcp`，配 workspace token）显示在 Workspace → Team 的 workspace token 面板里；
数据面地址按图给出，在该图的 Publish 页，可直接复制。

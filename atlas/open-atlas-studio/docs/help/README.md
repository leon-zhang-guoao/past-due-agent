# Open Atlas Studio Help

本 Help 随 Studio 发布包离线提供。

1. [Graph Definition、Entities 与 Settings 使用说明](graph-definition-and-entities.zh-CN.md)
2. [Semantic Contract 2.0 格式](contract-format.md)
3. [外部 AI 建模与导入](external-ai.md)
4. [Studio 用户与管理员流程](studio-workflow.md)
5. [旧 Graph 迁移](migration.md)
6. [Builder MCP 独立接入](builder-mcp.md)
7. [Contract 2.0 单 Graph 模型示例](orders-semantic-contract-2.0.edn)
8. [Workspace 角色与权限](workspace-roles.zh-CN.md)

当前边界：Studio 负责 authoring、验证、单 Graph 模型 `.edn` 导入和不可变 release preparation；Graph → Model → Import/Export 不提供包导入；Production Server 只提供 Query MCP。Studio 导入不会自动发布、激活或携带 Source 凭据。

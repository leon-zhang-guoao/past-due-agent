# Studio 用户与管理员流程

1. 在 Workspace 登记或连接 Source。连接配置保存在部署环境。
2. 创建或选择目标 Graph。
3. 在 Graph → Model → Import/Export 选择 `Import model (.edn)`，导入单 Graph 的 Contract 2.0 模型。
4. 检查 label/description、ownership、dependencies、Source Slot 和 findings。
5. 为每个 Graph Slot 选择已登记 Source。多个 Slot 可以选择同一个 Source。
6. 运行 resolver、graph、policy、agent 和 Golden Scenario 测试，并检查统一 trace。
7. Cut immutable release。发布版本不可原地修改。
8. 激活前再次检查权限、兼容性和连接；只有全部通过才切换，失败保留旧 serving 版本。

模型导入本身不执行第 5–8 步。此界面入口不再提供 Graph Package 导入；`.package.edn` 包文件会被拒绝。

要创建同模型的独立副本，分别新建 Graph 并导入同一份模型 `.edn`。业务 namespace 可以相同，各自维护草稿和 Source 绑定。

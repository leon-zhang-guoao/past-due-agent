# Semantic Contract 2.0 格式

每个文档只拥有一张 Graph 的 Entity、Resolver、Operation 与 Term。跨 Graph 能力通过带确切 version 与 content hash 的 dependencies 表达。完整规范见随包提供的 `ATLAS-SEMANTIC-CONTRACT-V2.zh-CN.md`（平台自 2026-09-19 起只读契约 2.0）。

外部数据 Graph 可以有一个或多个逻辑 Source Slot。Contract 保存 source kind、effects、resolver binding 和物理字段映射；部署连接信息留在 Studio 或 Server 的 Source Registry。先在 **Graphs → Sources** 明确维护 Graph 可用的 Sources。Studio 导入时忽略文件里的 Source 身份与连接设置，并让用户把每个 Resolver 映射到该 Graph 已选择、类型兼容的 Source；Workspace 中未加入 Graph 的 Source 不会成为默认值。

所有面向人的对象应填写 label 和 description。共享业务含义先定义 Term，再由 Attribute 显式引用。相同名字不会自动建立 Term、Relation、Join 或 Identity。


## 2026-09-22 当前契约澄清

Resolver Input/Output 用于依赖与路径规划。所有 Input 必填，optional Input 被拒绝；本次执行控制使用 Parameters（:params 与 :binding/parameter-map），允许 optional，且不参与路径规划。Input 放 :entity，Parameters 放 EQL 属性/Join 参数。省略 optional Parameters 必须可运行，缺失必要 Input 必须在源调用前拒绝。

# Graph 页面使用说明

## Model → Import/Export

Graph 的 Semantic Contract draft 是模型定义的权威来源。选择 Graph 后，打开 **Model → Import/Export**：

- `Import model (.edn)`：选择单 Graph 的 Semantic Contract 2.0 `.edn` 文件，校验后自动保存为当前 Graph 的新草稿 revision。
- `Download model`：下载当前已保存模型，供修改或导入另一张 Graph。
- `Refresh Sources`：重新加载草稿及 Source 绑定状态。

此文件导入入口已移除 Graph Package 导入。不要选择 `.package.edn`；即使把包文件改名为普通 `.edn`，它仍会因内容是 package envelope 而被拒绝，已有草稿保持不变。请使用对应的单 Graph 模型文件。

同一份模型可以分别导入两张不同 Graph，保留相同业务 namespace。草稿按目标 Graph 独立保存；在下方 Resolver Sources 中分别配置绑定。缺少 Source 不阻止合法草稿导入，但会阻止发布。文件解析或模型校验失败不覆盖已有草稿。

导入只保存草稿。完成 Source 配置及查询验证后，进入 Publish 检查并发布。

## New Graph

`New empty graph` 只需要 `Graph name`。它建立空 Graph 登记，不要求 namespace，不生成 Entity、Resolver、Source 或虚构的 Semantic draft。之后可以在 `Model → Import/Export` 中导入 Contract，也可以从真实 Source 开始建模。

## Entities & Relationships

这个页面用于浏览 Graph 已定义的业务对象，不会一次展开数百个 Entity。

1. 在搜索框输入 Entity、Attribute、Relationship 或 description 的一部分。
2. 左侧列表每次最多显示 50 个 Entity；需要时选择 `Show 50 more`。
3. 选择 Entity 后，右侧显示 Identity、Attributes 和 Relationships。
4. 选择 Relationship 的目标名称可以跳到关联 Entity。
5. 新 Definition 的阅读视图通过 `Edit Definition` 返回权威 EDN；旧 Graph 的 legacy projection 仍保留原建模计划入口。
6. `Concepts` 查看共享业务含义；`Validation` 查看 finding。

Graph 有已保存的 Definition draft 时，`Entities & Relationships` 从同一份 draft 生成可搜索的阅读视图，Query Studio 也运行这份 draft。没有 draft 的旧 Graph 才显示和运行 legacy projection。Query Studio 会显示 `Run target`，可据此确认当前运行目标。

`Release` 遵循同一规则：有已保存 draft 时，cut check 和 publish 都读取该 Contract revision，并生成 Artifact format 2；没有 draft 时才发布 legacy recipe。Release 页会直接显示实际目标，缺失 Source 注册或 Source 类型不匹配时会给出具体路径。

点击 Query Studio 中的 Contract 示例，会先格式化 Query EDN 和 Entity Data，再分别载入两个编辑器；不会自动运行。格式化只重新排版可读 EDN，不改变参数、字符串、数字、keyword 大小写或数据结构。若示例含非法 EDN，页面保留原文本并显示解析提示。

## Settings

Settings 只保留仍属于 Graph metadata 或 lifecycle 的操作：

- `Package name`：设置不可变 release 使用的稳定两段式名称。
- `Duplicate Graph`：输入新 Graph name 与新的 Asset namespace，复制当前 Contract 1.4 Graph 自有的 Entity、Attribute、Resolver、Operation 和本地 Term。内部引用随 namespace 改写；外部 Graph dependencies 与外部 Term references 保留。Source credentials、tokens、releases 和 serving state 不复制，也不会自动激活。
- `Delete Graph`：永久删除已归档 Graph 的登记、草稿及编辑历史、版本记录，并清理其 Studio、Runtime 与暂存区中的本地制品副本。其他保留制品仍引用它时会拒绝删除；文件清理失败时保留登记，允许重试。审计与部署证据、共享资产、外部 Graph 和 Source instance 保留。Archive 本身不会清理制品。

新 ownership 模型不提供 `Add/Remove by prefix`。每个 Asset 只有一个 Graph owner；复用通过显式 Graph dependency 完成。底层 legacy recipe API 暂时保留兼容旧 Graph，但不会出现在新版 Settings 中。Rename 也不在 Settings 提供，Graph identity 以 immutable ID 为准。

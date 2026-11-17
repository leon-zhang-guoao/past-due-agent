# 通用选择与展开 — 3.5.0

本地实现，沿用现有 Atlas semantic2.0 的标量属性、entity、resolver、SQL binding、
分页与 usage-guide 结构。用户已授权以现有 EDN 继续；没有假定新的 Builder 能力。
未执行平台 config-check、真实 JDBC、权限、知识关联或真实 Agent 验收。

## 实际变化

- 新增 pastdue.explore-v3-query/rows，以一条逻辑源查询完成选择、排名、展开。
- VENDOR 选择后可展开 Vendor／Code／Source／联合分布；GROUPED_ROW 保留旧聚合行排名。
- GLOBAL／TARGET_DATE 明确全局或逐日 Top N。展开绑定 Country、实际 P/T、Vendor 和
  必要桶身份，不把一天的选中 Vendor 外推到别的日期。
- 选择 ETA 筛选与展示 ETA 筛选分开；SELECTION 返回选择口径，FACT 返回展开口径。
- Country、候选总体和 Vendor 分母在 ETA 筛选和 Top N 前计算；候选总体分母不缩成赢家。
- EMPTY_SELECTION 不生成 FACT，不把内部空集合编码为 []=all；没有展示桶的选中对象仍保留
  SELECTION，并与没有选中区分。显式 Vendor 覆盖按实际日期返回。
- 输出选择数、FACT 数、唯一 ordinal、页数量和指纹，可核对源／保留结果／交付完整性。
- 共享 SQL 构建器分为源、指标、选择、输出、分页阶段；不再用 rsplit 切割 SQL 尾部。

## 保留及限制

旧版38个 resolver 和 SQL binding 保持原样。指标版本3.1.0，既有报告、日历、阈值、
Owner／联系人／PO、HTML companion 路径与含义不变。未新增运行服务、源表、任意 SQL 或写操作。

输入从22增至26个显式标量字段：增加的是受控选择语义，减少的是模型跨调用搬运，
不是已经实现输入默认化。原生默认值／保存查询需取得平台契约后再决定；不采用未验证的 EDN 键。

同一 SQL 不保证事务快照。CTE／当前源可能重读，每次续页重新聚合。指纹是普通漂移检测，
不是原子版本。聚合路径包含多阶段 CTE 和窗口，不能推定生产查询次数或性能；需实测计划和延迟。
缩小 page-size 仅减少传输，不降低完整聚合成本。历史仍使用当前组织映射；不能证明历史人员因果。

未添加原生知识文章或 certified 指标，平台契约、负责人和激活流程未知。相关数据限制放在现有
图描述／usage guide，并保留 Skill 参考，防止在知识尚未激活时丢失必要限制。

## 验证与部署

SQL 合成测试覆盖旧指标等价、日期分区、Vendor→ETA、分母、选择／展示筛选、空选择、
无数据与零、组织排除、两期消失、显式日期覆盖、原13 Vendor集合及超过1000行分页。
实际输出与哈希以 releases/validation-v3.5.0-sql.json 为准；合成测试不证明真实模型泛化。
本地35项SQL测试全部通过（462.793秒）；18项查询契约、17项Top10、38项报告／证据、
11项打包、34项HTML和4项SQL报告适配测试通过。Skill校验通过。旧38个resolver／SQL
与3.4.0 ZIP逐项相同；39个resolver／46个entity／821个attribute只是静态库存，不是线上认证。

1. 备份实际部署的 Prompt／完整 Skills／图与源配置；保留现有批准的标识与凭据。
2. 部署新增图3.5.0/revision6，执行平台 config-check、真实SQL/JDBC、页上限、权限和性能验证。
3. 更新 Prompt 和整个 analysis Skill；HTML companion 未改。新会话先发现实际 v3 能力。
4. 执行实际问题、多轮追问和故障场景，独立核对数字；另用未参与调优的保留题重复验收。
5. 回退使用保存的部署集合；旧路径不被重定向。已有3.4.0 ZIP保留不覆盖。

docs/graph-design-review 是设计阶段记录；当前实现状态以本文和验证回执为准。

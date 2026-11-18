# PastDue 3.6.1：减少重复调用，保持事实身份与及时交付

> 未发布候选，已由 [3.6.2 收窄方案](STABLE_REPORT_ROLLOUT_3_6_2.md) 取代。不要依据本文整体推广整页读取或新 CURRENT 默认路由；保留本文仅供追溯。

## 本次交付

使用 codebase-design 整理职责与接口，用 diagnosing-bugs 的真实记录确定缺陷，用 skill-creator / writing-for-agents 改写部署指引。继续使用一个 pastdue-analysis Skill，不要求部署平台安装这些设计技能，也不增加新的分析子agent或宿主脚本。

这是 Prompt/Skill 修订。graph仍为3.6.0/revision8，保持字段、41条resolver、SQL、指标和选择语义；不修改Atlas代码、源配置、数据库、凭据或超时。不把本次故障演化成只能处理Top10的固定场景表。

## 设计

此前难点不是缺一条场景能力，而是查询页、投影、Vendor事实和最终报告的状态同时交给模型临时记忆。本次把这些机械约束集中到现有result-reader.md，避免每个场景重新决定读取和恢复过程。

| 职责 | 由哪里维护 | 输出给下一阶段 |
|---|---|---|
| 理解提问与选择等价查询 | SKILL、report/interactive planning | 明确的Country/Vendor、时间、过滤、分母、选择、展示范围 |
| 查询、关联与读取 | result-reader + 完整页SQL | 有来源的页、已读行/字段、精确缺口 |
| 事实绑定与核对 | completion-contract | 按完整scope和Vendor身份绑定的指标、摘要、check及状态 |
| 解释和交付 | report-format | 每个请求Country的实际结果/缺口，逐Vendor准确拷贝 |

这是指令中的职责划分，不是已安装的确定性执行器。模型可以自由选择受支持的查询和分析方式，但业务身份、来源和已读状态必须一致。

## 每页的主路径

`执行源页 → 用本次真实handle读完整显式字段 → 检查身份/页码/截断 → 保存事实及字段覆盖 → 补真实缺口 → 按has-more继续或结束 → 完成该Country绑定和草稿`

- report-v2默认50条非控制行，CURRENT默认100。诊断page-size1只查一页，不沿用到正式报告。不是盲目增大到200；源字节上限仍有效。
- report-page-read.sql/current-page-read.sql从保留的data读取，覆盖相应完整EQL投影，无record_type过滤。首次完整读就可先解释控制行，再解释业务行；不必分别调用工具才能做到control-first。
- 只有真实缺行、缺字段、平台或宿主明确截断才补读同一handle。metadata-only不等于业务字段已读；反之，完整读过的业务字段不因换一条SELECT就需要再读。
- 页可在Vendor中间切断，按scope、Vendor code、record_type、dimension、eta值及ordinal合并。继续页计算全国分母和排名，不重新把显示的Vendor集合当候选总体。
- 每次调用有明确缺口，之后更新行/字段覆盖；全部模式统计源执行、读取、发现、续页、纠正、耗时。没有新事实且没有支持的纠正时结束受影响路径，交付已验证事实及局部缺口。没有“一律最多N次”的上限。
- 原来的single-handle report三投影作为旧接口或实际大结果的分割方法保留，不能和已完整读过的新页再叠加执行。

工作记录使用现成文件工具可写的任务文件，或结构化任务笔记；不强行假设宿主有持久内存。实际记录要保留数据值/原始响应引用及关联，不是只记几个handle就声称已存数据。业务事实仍只来自Atlas。可用文件工具缺失不构成编造保存成功的理由。

## 故障处理

1. **响应handle或cursor不一致**：检查同一次调用已有的真实请求/原始响应。如果宿主不能暴露这些，隔离该页，报告关联未验证，不猜handle、不重复源SQL碰碰运气。此问题若来自宿主转发或Atlas服务端，需要对应维护方修复；Skill不能改写已经发送/收到的响应。
2. **DuckDB locked memory_limit**：保留已经成功读到的字段；失败只影响未读部分。配置未改变时同样SQL的原样重试或重跑源查询无效。不修改Atlas的前提下，若没有真实可用的其他读取方式，就明确局部不可用。
3. **EQL属性拼写错误**：按实际属性名纠正一次，EQL连字符与SQL下划线分别处理。
4. **确证过期、字节拒绝、fingerprint变化**：按reader中的有界恢复，保持业务范围与新capture说明；缺失的字段/页仍登记为缺口。

## Vendor防错配与Country交付

报告从绑定后的Vendor事实写，排名和名字只用于展示。Code/Source check的P/T数量、net_change必须与同一Vendor的指标对应；counts_match=true不能豁免身份核对。原记录SILVER PEAK指标+149却带EATON检查+81必须被判为REPORT_ERROR。

一个Country绑定完成时准备其报告草稿和交付状态，再处理下一个Country。最终逐一核对原始Country列表和Vendor集合；CA已有结果时不能因US局部缺口而省略CA。已读SONY检查直接复用，US中间页只补被WHERE排掉的Vendor指标，不重查其已读ETA。

无预警报告、单/多Vendor、改善/零值、CURRENT、历史、自由组合和已有HTML交接均保留。自动监控/TEST原正向选择与按需报告TOP_N分别保留；不把恰好全正的TOP_N写成POSITIVE_TOP_N。HTML仍由明确请求触发，使用已验证绑定，无新增Atlas查询。

## 需要更新什么

1. 将部署agent的system prompt替换为 `agent/SYSTEM_PROMPT.md`（3.6.1）。
2. 完整替换已安装的 `pastdue-analysis` 目录，保留本地所需的工具绑定配置，确认SKILL标题和policy-version均3.6.1。勿只复制SKILL.md：reader、policy、completion、planning和两个SQL参考文件需要一起更新。
3. 本次graph无变化。若线上还没revision8，先部署既有双重ORDER BY修复；若已是revision8，无需重发graph。完整包包含该graph用于保持闭包，不表示本次又修改了它。
4. 不使用旧Skill或发布包覆盖本次文件。新包为 `pastdue-agent-v3.6.1.zip` 与 `pastdue-analysis-skill-v3.6.1.zip`。HTML companion内容未改，不需要重新注册子agent。
5. 新对话或确认技能缓存已刷新后先跑一个Country，再跑原US/CA业务范围。宿主/响应关联异常未解决时，不应仅因新Skill加载成功而认定业务恢复。

## 验证与实际限制

本地有完整页SQL测试：从graph的真实绑定SQL在合成ClickHouse数据执行，经过Atlas式追加排序/limit后，将实际输出交给SQLite执行所交付的简单SELECT投影，核对每个字段/行、续页、Vendor指标和coverage。SQLite只是开发测试，不能模拟部署DuckDB配置和工具宿主。

另有报告检查回归验证counts_match=true也不能让外来Vendor数量通过已有核对器。这些检查器仅在开发/CI运行，没有假装在业务agent中自动执行，更不能证明自由文本永远正确。

### 线上逐步验收（业务数据只经现有Atlas工具）

| 验收 | 应观察到的行为 |
|---|---|
| 原报告单Country，同一有效P/T、选10家 | 完整页一次读成功后直接复用；页码和handle对应；逐Vendor指标与checks相符。 |
| 相同范围扩到US/CA | 各Country都有章节或独立原因；完成US绑定后再处理CA；不会回头重复已完成页。 |
| 完成页数与成本 | 若仍为US170行/CA136行、page50，则源页预期4+3；不是承诺数据永远这些行数。实际调用偏离预期要能对应明确缺口/纠正。 |
| 真实截断或局部投影 | 在同一真实handle上精确补缺口，完整投影不再被无理由分三遍重读。 |
| 既有memory_limit错误或关联异常 | 保留成功证据、停止无效路径，准确报告缺口；不以重复SQL或相似数字修复来源。不要人为修改生产源制造错误。 |
| 单/多个Vendor及未触发预警报告 | 保留精确集合、所有贡献符号和真实业务状态；CURRENT不冒充两期比较。 |
| 自由问题和跟进 | 使用explore已有运算组合，候选总体/分母/时间不因结果读取而改变。 |

记录实际工具调用次数、耗时、源页、缺口及交付集合。五分钟是用户此前体验基线，需要在相同真实业务范围上测量比较；本次未连接线上，不承诺恢复到五分钟，也不把离线通过标为真实agent验收成功。

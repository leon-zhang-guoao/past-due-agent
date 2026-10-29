# 3.3.7 — 分层修复与真实验收

状态：**PENDING_LIVE_PILOT**。未运行离线测试，未访问 Atlas／生产数据库。
交付仍是 System Prompt + 完整 Skill + 原有 Atlas EDN。

## 1. 证据与结论

本轮依据用户提供的 chat/chat.md 与 chat/cursor_project_file_submission.md，
并对照当前 reader、data contract 与 build_v31_contract.py；不把日志中的旧指令
视为当前操作授权。现有记录不能定位宿主内部组件。

| 观察 | 可以确认 | 不能据此声称 |
|---|---|---|
| 3.3.6 同时提交 query=""、result_handle、result_handles=[] | 读取因 selector 互斥被拒绝，未取得业务行；原样重复三次无效 | 一定是模型／平台／Atlas 某一层补字段 |
| Prompt 说发送前去空值，但仓库没有线上 MCP 客户端 | 只有行为指令，没有执行型清理钩子 | 3.3.6 已实现参数序列化修复 |
| 成功记录存在只带 handle/version 的 discovery 且返回列和数量 | discovery 本身不是必然错误 | 删除 discovery 能根治互斥参数错误 |
| execute/read handle 对不上；部分返回显示旧捕获信息 | 关联证据异常，需要原始调用对应关系 | 已证实缓存、并发、模型虚构或某个平台 bug |
| 对 CONTROL 读了 Vendor PD/Open 占位字段，没有读 Country 专用字段 | “Country 总量为0”的推断使用了错误字段 | 真实 Country 总量一定为0，或底层 SQL 一定出错 |
| 成功片段 US177/CA154 条紧凑记录被读取，check 均 true，无所示行/单元格截断 | 记录运输和服务端摘要检查有正面证据 | 原始全桶独立审计、handle 归属、日期身份、最终报告均通过 |
| 结果有两期 ETA 和 signed totals，最终仅列主要增量 | 报告阶段省略已取得事实 | 仅归因于 Atlas 数据未取全 |

历史成功片段的全量来源覆盖警告不能自动解释为失败，也不能自动忽略。
最终判断需结合实际身份、源分页、唯一 ordinal、预期数量及核对信息。

## 2. 已实施的仓库内修复

### 调用边界

result-reader.md 成为唯一维护的参数／保留结果读取说明。
构造三键单 handle SQL 调用；未知列时允许受支持的 discovery，不再断言其有害。
最多一次有依据、实际改变的纠正；原样重复、改版本或重跑源查询不算纠正。
指令明确不具备实际序列化拦截能力，不添加线上 Python／代理服务。

### 类型化读取

同一 report handle 按三种不相交投影读取：
1. COUNTRY_CONTROL：专用 Country PD/Open/ratio、选择范围及预期数量。
2. VENDOR_METRICS：Vendor PD/Open/ratio、贡献、排名、覆盖率。
3. ETA/Check：桶两期数据、Other、signed totals、预期和实际核对信息。

三组均保留 scope_key、Country/Vendor、P/T、metric_version、record_type、ordinal。
按唯一 ordinal 并集核对完整紧凑记录；源分页与保留结果分页仍分别核对。
COUNTRY_CONTROL 上非专用 Vendor 占位字段不作事实解释。
删除“Country 为0则保留并豁免”规则；真不一致记录两份原始字段和捕获，限制跨查询
事件归因，但保留身份和分母已验证的独立结果。未知 handle 归属不能据合理数字放行。

### 执行与报告

阶段式读取引用，不再一次拼接全部大文档。一个 Country 完成源请求、读取和绑定
后再处理下一个；不逐 Vendor 重查源。标准报告逐家固定指标、Code 表、Source 表、
两个增减净汇总及诊断。摘要不能代替完整单元；自由问答仍只组合相关能力。

报告区分查询、读取/对账、实际交付三个完成层次，分别列出选中家数、完整交付家数、
含局部失败家数及尚未交付身份。服务端返回的零数量 Other 若代表真实剩余桶，仍保留；
不存在 OTHER 记录时不补造。没有确定性宿主 renderer／独立验收门禁。

### 版本与打包

Prompt/Skill/policy/display contract3.3.7；Atlas publication3.3.0/revision4 与
metric3.1.0 不变。本次不修改统计 SQL、表结构、业务时区、阈值或灵活问答参数。
修正发布器旧的3.3.2版本及漏包的 mcp-tool-arguments.md；只使用 package-only，
所有未执行验证保持 NOT_RUN。摘要哈希只表示文件一致，不表示平台或 Agent 通过。

## 3. 宿主问题的真正修复边界

同一条调用应能对应如下证据（平台能暴露多少就记录多少）：

```text
原始工具 schema → 实际给模型的 schema → 模型生成参数
→ 宿主出站 MCP 参数 → MCP 原始响应 → 界面/导出
```

用同一 tool-call ID 定位首次分歧。原始 schema 没有 default 不能排除中间 schema
转换、宿主默认值或模型填参。缺少证据时明确 UNCONFIRMED，不重复要求无法提供的日志。
只提供一张最终请求截图，也足以确认错误形状，但不足以归因具体组件。

若可修改调用适配层：只序列化选中的 selector；针对 selector 校验空值和互斥，
真实多个有值的 selector 必须拒绝，不能任选一个；保持原始请求/响应关联。
不用“删除任何空字段”的通用规则改变其他工具、false/0 或真实业务参数。

当前只能部署 Prompt/Skill/EDN。若宿主持续不能表达合法调用，只能由平台维护方修复、
采用经验证可正确省略参数的配置/连接，或先核实 execute_eql 是否真的支持受控分页完整
直接返回再设计替代路径。本版不假设该路径可用，不静默切换接口或长文本 block。
不以改 SQL、改 analysis_version、删除 handle 核验来规避调用错误。

## 4. 只做真实线上分阶段验收

每一阶段失败先停该依赖链；不把整个双国大报告作为最小复现。

1. **调用**：同宿主/连接，新 execute 小监控；用实际新 handle 提交三键 SQL 读。
   记录实际发送参数、原始响应、可获得的 call ID、handle、捕获和过期信息。
   参数仍被补齐且无法纠正：停止读取，交平台处理；不得标修复完成。
2. **国家字段**：固定一个真实可用日期对，执行完整 report EQL；先读专用 Country
   字段与 monitor/history 对照。缺列先查实际 schema，不把未读字段当0。
3. **单家输出**：同一已捕获报告中选排名第一的完整事实作人工对照，核实两个表、
   Other、signed totals、百分比和 pp。试点只覆盖一家须注明，不冒充完整 Top10。
4. **单国完整**：原始选择集合与所有逐家单元一一对应；每个数字对应原始行。
   再扩展 US/CA，确认各国独立、所有必需维度均有数据或局部失败原因。
5. **双语与灵活问答**：中文/英文、日常/测试/历史、当前排名、负变化排名、指定
   Vendor、趋势和组织筛选。固定日期可重复观察，但不能据此保证永久确定性。

如果 supplied 旧数据用于人工对照：LEXMARK2594 的 SH158→401、US114→108；
Code增加250/减少−6/净244，Source增加319/减少−75/净244。这只属于原记录的
2026-10-27→2026-10-28，不是新运行默认数据，也不能用来填缺失结果。

## 5. 本轮实际验证状态

- 代码/文档修改与人工静态审阅：已进行；不构成行为验收。
- 本地测试、Skill validator、SQL fixtures：NOT_RUN（按用户要求）。
- Atlas config-check／实际部署／Agent端到端：NOT_RUN（本会话无可用 Atlas 工具）。
- 发布包只作白名单、文件摘要和归档一致性处理，不执行测试。
- 参数注入与旧 handle 异常的组件根因：UNCONFIRMED。
- 发布门槛：以上真实调用与报告验收通过前保持 PENDING_LIVE_PILOT。

# 3.1.2 — 报告一致性与事实绑定改造

## 范围与发布状态

在当前 `past-due-agent` 改造。交付仍是 System Prompt + 完整 Skill + Atlas EDN。
Agent / 展示契约版本 **3.1.2**；Atlas 继续使用 **3.1.1 publication / revision 2**，
指标/API 仍为 **3.1.0**。本次不修改 Atlas SQL、数据库结构、业务阈值、日期或排名公式。
不增加线上执行服务、Python、邮件草稿或发送能力。

状态：**PENDING_LIVE_ACCEPTANCE**。依用户要求，本次**未运行离线测试**；
未调用线上 Atlas / Agent，不能把规则编辑或打包当成运行效果证明。

## 实际问题与证据边界

`chat/chat.md` 与 `chat/chat2.md` 是最终输出，不是完整工具日志。可确认：

- 同为标准测试，英文用宽表汇总，中文用变化列表和另列诊断，未按统一 Vendor 单元呈现。
- 同名 previous/target 数量列被混用：中文 US 13788 的 Vendor 总量写成 SH 桶数量，
  64036 写成 US 桶数量；不能据此声称 Atlas 两次返回冲突。
- 贡献变化与本期贡献层级混用；部分排名、Code 标签、US 联合来源与自身计数矛盾。
- 只说 coverage unknown 就判断 PARTIAL，未说明究竟缺少哪项必需核对。
- 尚无原始请求/handle/完整返回证据，不能确认上游关联或数据库根因。

这些具体 Vendor 仅作为真实回放检查点，**没有作为特殊处理写入 Agent 规则**。
不同报告的数字不作为另一份报告的权威修正源，必须回到对应实际 Atlas 返回。

## 整体改造方式

### 1. 一套事实绑定，不从展示表再生成“证据”

在已有扁平接口上明确记录判别：

- COUNTRY_CONTROL：Country 口径、数量、分母与控制信息。
- VENDOR_METRICS：Vendor 总量、Open、比例、贡献、名次、覆盖率。
- ETA_CODE_SUMMARY / ETA_SOURCE_SUMMARY：独立维度桶。
- US_SOURCE_SUMMARY：Code=US 条件下的 Source，不是整个 Country US。
- VENDOR_CHECK：各维度完整桶合计、增减及适用性。

按 scope-key + Country + Vendor + 日期对 + 维度关联，保留实际 handle 与行序号。
概览、详情、诊断复用同一组绑定事实，禁止按相邻行、重复数量或第一行猜归属。
贡献变化绑定 delta-country-contribution，不使用 target-country-contribution。

### 2. 一份可直接填充的中英文骨架

权威列定义在 `table-display-contract.edn`，实际章节与双语骨架在 `report-format.md`。
标准流程固定：运行信息 → Country 指标 → 各 Country 内 Vendor 汇总和逐家分析
→ Country 小结 → 跨 Country → 完成检查 → 测试结论（仅 TEST）。

Vendor 汇总仅作索引，移除重复 ETA 列表/诊断宽列；每家仍完整显示指标、Code 表、
Source 表、全桶增减净变化、适用的 US 联合来源、业务诊断及明确失败原因。
保持上期→本期、整数不加千分位、比例两位及贡献变化四位 pp。中英文列序一致。

### 3. 局部失败，不一刀切丢弃 Country

缺字段、查询失败、未查询、成功空结果、不适用与数值零分开显示。
Code、Source、US 独立核对；失败保留位置，不影响已核验的独立事实。
US 检查逐 Vendor 执行，CA 同样适用。Other 只展示服务器真实汇总，不能补零。

事实冲突需要两个原始同语义返回；单个返回内部不一致是核对失败，报告抄错是
REPORT_ERROR。草稿抄错应依据原始记录纠正，不能通过“隔离”掩盖报告错误。

### 4. 完整性有明确证据层级

完整读取、身份、记录数、桶表示及两期合计和适用 server checks 全部通过，才能
标记 SERVER_RECONCILED。泛化的 source coverage UNKNOWN 单独保留警告，
不自动 PARTIAL，也不自动证明完整；缺哪项必需证据就说明具体缺口。
未读取原始全桶，不宣称 CLIENT_RAW_AUDIT。展示未完成也不能把完整测试标 PASS。

### 5. 保留问答灵活性

上述结构限定标准日报/历史完整报告/测试预设，不强制普通问答跑整份报告。
交互问题继续按时间、筛选、指标、粒度、分母、排序组合；不隐式套用正向 Top10。
用户明确要求简报、CSV、自定义列时允许定制，注明 CUSTOM 范围，事实标准不放松。

## 更新哪些文件

1. 更新 `agent/SYSTEM_PROMPT.md`。
2. 替换完整 `skill/pastdue-analysis/`，不要只替换 SKILL.md 或单个模板。
   可用 `releases/pastdue-analysis-skill-v3.1.2.zip`。
3. 已部署 3.1.1 Atlas 图不需要因本次展示修复重新上传 EDN；旧环境仍需先部署
   完整包内的兼容图，并保留正确 source binding。不要误把 Agent 3.1.2 当成图版本。

完整包：`releases/pastdue-agent-v3.1.2.zip`。不含聊天、CSV、测试 HTML、凭据、
开发脚本或非必需 Runtime。旧 3.1.1 ZIP 保留，回滚时恢复其整套 Prompt/Skill。
本次图未变更，单独回滚展示层不需要回滚图；更早部署仍应成套回滚。

## 发布与证据记录

新增显式 `scripts/build_release.py --package-only`，用于**只打包**：不导入/执行
离线测试，不继承旧 PASS。打包仅处理允许文件、内容摘要、ZIP 和敏感信息防误装。
manifest 分开记录 artifact hashes 与 validation evidence，后者均为 NOT_RUN。
这是开发交付工具，Agent 运行不调用。不会替代平台验证或真实 Agent 验收。
旧版本验证记录单独保存在 `VALIDATION_V3_1_1.md`，不适用于本次变更。

## 待真实运行验收（本次未执行）

在已更新 Prompt/完整 Skill 的实际环境中：

1. 对同一个仍存在的完整工作日快照，分别输入 `start test for snapshot 2026-10-26`
   和 `开始测试，目标快照 2026-10-26`；若该日期不可用，改用相同可用固定日期。
   各重复运行，核对章节、列序、所选集合和每家分析结构，而非要求解释措辞完全一致。
2. 保留实际 execute→handle、原始行、日期和最终报告；确认每家概览/详情的总量来自
   VENDOR_METRICS，ETA 桶来自正确 Vendor/维度，贡献变化来自 delta 字段。
3. 若回放旧日期仍是同一返回，检查 US 13788/64036、CA 1097/39379 等旧错误点；
   **以当次真实行而非旧报告数字为准**。所有 Vendor 都要检查，不能仅对示例通过。
4. CA 与 US 各验证 Code=US 联合来源，检查 Code/Source/联合各自的两期合计。
5. 分别验证无触发日报、强制测试、历史完整、只列事件，以及改善排名/单 Vendor/
   当前占比追问；普通问答不应被强制完整报告。
6. 展开现有失败或不完整返回，确认失败粒度、未查询/零/空/Other 区分及状态准确。

保留原始证据后再确认改造效果。不声称纯提示词可像确定性渲染器一样硬性阻止错填，
也不在此边界内额外强加线上服务。若实际平台仍不能稳定遵守，需基于真实证据评估
宿主可选渲染/提交校验能力，不能靠继续堆规则假装已经机器强制。

# 3.2.0 — 固定事实块与业务解释分离

## 改造目标与边界

两份真实报告仍出现不同布局、日期方向倒置、Vendor 总量混入 Code 桶、错误 PASS。
本次不再只强化文字约束，改为 Atlas 生成固定事实块，Agent 引用事实块并生成总结。
不增加服务、线上 Python、数据库表、邮件发送或任意 SQL。原有 explore/raw-report
接口保留。业务时区、完整工作日、触发阈值、正向 Top10、ETA Top5+Other 均不变。

版本：Agent/模板/Atlas publication **3.2.0**，source revision **3**，
指标/API 原有口径仍 **3.1.0**。状态：**PENDING_LIVE_PILOT**。
本次依要求**不运行离线测试**，未调用实际 Atlas/ClickHouse/Agent，不能标生产通过。

## 新增接口

| 入口 | 输入 | 输出 |
|---|---|---|
| pastdue.monitor-block-query/countries | calendar-mode, relative-change-threshold, language | 原有 Country 监控事实 + 固定格式 Country 表、语言/模板版本、预期行数 |
| pastdue.report-block-query/blocks | 原 report 七输入 + language zh/en | 一个 COUNTRY_OVERVIEW + 每家 VENDOR_DETAILS、诊断依据、服务端核对、预期块数 |

正常 Top10 每国最多 11 个块；无正向 Vendor 返回一个明确空选择的 overview。
空选择不代表执行失败，无效日期/范围不代表正常空选择。所有内容受现有授权限制。

块中固定日期方向、列序、整数、比例两位、贡献四位 pp、正负号。通过相同研发期模板
选择 zh/en 标签，不手工维护两套不同 SQL 指标逻辑。旧原始解析接口不静默换义。

## 数据与展示的职责

- 计算继续使用权威 report SQL，新增 resolver 将其**一次**包入 raw_facts，
  后续按 record-type 取字段、窗口核对、排序字符串聚合，不逐 Vendor 重跑源表。
- Country 控制、排行来自正确记录类型；Vendor 总量/比率来自 VENDOR_METRICS；
  Code/Source 来自各自汇总；增减合计与适用性来自 VENDOR_CHECK。
- Code/Source/US 分别核对两期、桶表示、增减及净变化。服务端检查与最终展示分离。
- overview 后立即生成 Country Vendor 总结，再按原排名引用每家完整事实块。
- US 条件来源继续内部核验，诊断依据提供实际 Vendor/Source/日期变化及规则，
  融入 Country 总结，不单独恢复技术表或 US 汇总行。
- 未识别 Code/Source 不猜业务意义，Other 不推断隐藏来源，不宣称因果或人员责任。
- 单维度失败保留其他有效事实，返回原因；不能把整个 Country 悄悄清空。

`content-sha256` 标识服务端返回字节，**不能**证明模型最后转发完全相同。
Agent 不得声称心算摘要、独立全文比较或宿主门禁已执行。

## 完成状态修正

标准测试不再由模型给自身整篇输出发 PASS。分开显示：

1. 服务端事实核对：SERVER_RECONCILED 或具体未核验原因；
2. 实际取得事实块数 / 预期块数；
3. 最终展示：无独立验收时 NOT_INDEPENDENTLY_VERIFIED；
4. 已确认输出错误单独记录 REPORT_ERROR，不能用笼统 PARTIAL 掩盖。

这不是降低要求：每家完整下钻仍必须执行，错误必须纠正；只是取消不真实的自证。
自由问答仍使用可组合 explore，标准报告约束不限制普通问题。

## 部署与真实试点（未执行）

1. 保存目前已部署的 graph、source binding、Prompt、完整 Skill。
2. 先在目标 Atlas 正式 config-check / 试验环境部署合并图 `atlas/charts/pastdue.edn`，
   保留受控 source binding；不使用历史聊天凭据。此次新增字符串聚合 SQL **需要**
   目标 ClickHouse 22.8 / JDBC 实际执行确认，不能推断与 24.8 等价。
3. 在 Studio 运行 `docs/FACT_BLOCKS_PILOT.eql` 中每个独立 EDN 请求。
   文件是多个独立查询，不是一次 POST 的整体。先单 Country 固定有效日期。
   参数化 MCP 与 Studio plain join 有不同包装，不能再次使用已拒绝的 query shape。
4. 确认语言/模板、overview与Vendor集合、数据原因、两期值、Other 和 signed totals。
   保存原始 execute→handle 与完整内容，不只截图最终数字。先 raw payload，再报告。
5. 再更新 System Prompt + 完整 Skill，在同一有效日期跑中文和英文单 CA 测试。
   对照真实块与最终文字，尤其原日志的 Vendor 总量/日期方向，不依赖旧报告作真值。
6. 单 Country 成功后再扩展两国、普通日报/历史/当前问答。重复同一固定日期，
   观察事实结构稳定性；自然解释措辞可以不同。未通过前不要作生产自动调度切换。

无原生固定展示能力：事实块仍经过模型，不能保证严格零改写；本次目标是减少模型
逐格重写/算数环节，真实试点不通过就不能声称解决。新 SQL 的窗口与字符串开销
未实测；Code394仍需实际日志，缩页不是性能修复。

## 交付与回滚

完整包 `releases/pastdue-agent-v3.2.0.zip`；Skill 包
`releases/pastdue-analysis-skill-v3.2.0.zip`。**本次需要先更新 Atlas EDN**，
不是只更新提示词。原 3.1.3 ZIP 保留；回滚先恢复旧 Prompt/Skill 后再恢复批准的旧图，
不能让新 Agent 配旧的缺接口图。不自动提交或推送 Git。

开发期 `scripts/build_report_blocks.py` 定义格式与映射，读取既有规则字典；
`build_analysis_contract.py` 整合生成，Agent 线上不运行脚本。
只执行 `build_release.py --package-only` 打包，未执行验证项目保持 NOT_RUN。

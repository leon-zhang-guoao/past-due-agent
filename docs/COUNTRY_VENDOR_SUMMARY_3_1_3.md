# 3.1.3 — Vendor 排行后的 Country 总结分析

## 用户要求与实际调整

“US 联合来源”展示含义不清。删除标准报告中的独立列/表/章节，将相关业务解释
融入每个 Country 的 Vendor 排行之后的总结分析，而不是删除分析依据。

每个 Country 现在依次呈现：

1. Vendor 排行汇总。
2. **Country Vendor 总结分析**：正向贡献覆盖率、主要 Vendor 的风险与改善、
   ETA 变化所提示的业务问题、数量与分母影响、建议及限制。
3. 原有每家 Vendor 的指标、ETA Code / Source 两期表、Code/Source 增减合计及诊断。

不在末尾重复输出第二份 Country 小结。不再在每家 Vendor 下展示独立
“US 联合来源”技术表、N/A 占位或增减汇总中的 US 技术行。
普通 ETA Source 表与完整 Vendor 下钻不变；跨 Country 不合并排名。

## 分析能力没有移除

US_SOURCE_SUMMARY 与 dimension=us VENDOR_CHECK 仍按需适用性查询、核对。
这些事实是 ETA Code=US 的条件来源，不是 Country US。业务结论保留实际
Country、Vendor、来源及两期数量，仅在相关且已核验时融入总结。

依原规则区分 AutoUS / 已确认其他来源 / 未识别来源。不能把独立 Code、Source
分布拼接，不能推断 PO 迁移、人员责任或因果。隐藏 Other 的来源不作猜测。
缺联合证据时明确相关业务解释不可用，不能因为表格移除就假装核对通过。
所选 Vendor 的发现不得扩大为全 Country；不新增未经支持的跨 Vendor 汇总。

用户明确要求来源细分时仍可展示可选模块，标题为：
**“ETA Code=US 的来源细分” / “Source breakdown for ETA Code=US”**。
普通问答继续按需组合能力，不被标准报告结构限制。

## 更新与交付

Agent / 展示契约版本 3.1.3；Atlas 继续 publication 3.1.1 / revision 2，
指标/API 3.1.0。本次没有修改 Atlas SQL 或业务规则。

- 更新 `agent/SYSTEM_PROMPT.md`。
- 替换完整 Skill：`releases/pastdue-analysis-skill-v3.1.3.zip`。
- 完整包：`releases/pastdue-agent-v3.1.3.zip`。
- 已部署兼容图，无需因本次展示变更重新上传 Atlas EDN。
- 保留 3.1.2 发布包，回滚时恢复其 Prompt + 完整 Skill；Atlas 本次未变。

本次依用户要求未运行离线测试，未执行实际 Atlas/Agent。仅编辑文件与打包，
所有未执行验证保持 NOT_RUN，状态 PENDING_LIVE_ACCEPTANCE。

实际运行后应确认：中英文均在各 Country 排行后出现总结；没有独立 US 联合来源
展示；每家 Code/Source 分析仍完整；有联合事实时结论可回溯到准确 Vendor 和日期；
缺证据时保留限制，不能牺牲真实性来简化展示。

# 3.7.0：实时概况、单 Vendor、批量 Vendor

## 目标与改动

用户明确：目前／当前／今日 Past Due 情况要使用实时明细，不是 snapshot。
本轮使用 codebase-design、skill-creator、writing-for-agents 和仓库 graph-authoring
的分层方式：稳定公式/匹配/状态放到 EDN；意图、调用与交付放到 Skill；测试验证共享接口。

1. 新增 `pastdue.live-overview-query/rows`：单 Country、top-n0..20，默认5。
   一次聚合返回 Country Open/PD/ratio、可读 Vendor 数、重点 Vendor、无法归属编号的数量。
   默认 US/CA 分别查；无预警也返回。实时排序按 PD、Open 降序和 code 升序。
2. 新增 `pastdue.vendor-identity-query/rows`：多个名称／编号统一识别，返回每个输入的
   唯一匹配、全部歧义候选或无可读匹配；支持分页。同名跨 Country 不自动选。
3. 复用 `pastdue.current-v2-query/rows`：按 Country 一批查询确定的编号，提供 Vendor
   及 joint Code×Source 指标；保留覆盖状态与分页。已知 Country+code 跳过名称识别。
4. Prompt/Skill 默认实时路由，仅覆盖上述当前场景；历史报告、监控、TEST、自由组合
   explore、可选 PO/Owner/HTML 路径继续保留。全宽读取仍可选；优先已有证据及必要字段。

不是逐问增加 endpoint：新问题仍组合 overview / resolve / filter / aggregate / rank / drill。
本次新增的是缺少的两种共享操作，批量度量使用原接口。原41个 resolver 及其 SQL、输入输出、
source/selection 在结构对比中保持相同；Graph 升级3.7.0/revision9，新增至43个。
Atlas 源码、连接、策略、超时和表均未修改。

## 口径与影响

CURRENT Open=可读明细行数；PD=is_pastdue=1的行数；PD/Open分母0为null。
国家概况计入缺失或非正 Vendor code 的明细，另列 unattributed；榜单仅使用正编号。
已有 current-v2 的 COUNTRY_ALL 分母排除 null code，本轮保留其公式；存在这类数据时须说明
两接口占比口径差异。查询时间不是源刷新时间，多次查询不是事务一致快照。
名称只去首尾空格并按ASCII大小写无关精确匹配；中文等非ASCII内容保持原样，英文别名、简称
不做模糊猜测。没有可读数据不表示 Vendor 不存在、也不表示零逾期。
大批次依据 EQL8KB及实际输出上限分块，保存输入到 Country+code 映射并完整汇总；不限制10家。

## 更新哪些文件、如何部署

完整包：`releases/pastdue-agent-v3.7.0.zip`。
Skill包：`releases/pastdue-analysis-skill-v3.7.0.zip`（含原有HTML Skill，保留其已有配置）。

1. 备份线上批准的 graph、Prompt 和完整 Skill 目录。
2. 使用 `atlas/charts/pastdue.edn` 更新现有 graph，保留线上 graph ID/source slot映射和凭证。
   先在测试图 config-check，确认新增两条查询与既有 current-v2 的 schema。
   不修改 `atlas/open-atlas-studio`，不改变连接超时。通用模板中占位 ID 不能直接覆盖线上 ID。
3. 在测试图先执行 Skill references 中的 `live-overview-query.eql`（分别US、CA），
   再执行 `vendor-identity-query.eql`，把真实匹配编号填入 `current-v2-query.eql`。
   保存 EQL、原始响应、实际 handle、耗时、scope/status、count/ordinal/fingerprint。
   EQL样例中名称/编号仅为输入示例，不能当作环境保证。
4. 更新 `agent/SYSTEM_PROMPT.md` 和完整 `skill/pastdue-analysis/`，包括新的
   live-current.md、两份EQL和两份typed retained SQL；不要只覆盖SKILL.md。
5. 通过下面的线上验收后，再切正式图/Agent。老版图没有新接口时，明确报告缺失能力；
   已知编号可使用语义相同的 explore CURRENT 路径，不回退snapshot冒充实时。

3.6.3是Prompt-only修复；3.7.0这次需要更新EDN。历史ZIP保持原文件，支持恢复上一套Prompt+Skill；
新增graph可先保留，旧接口未改，也可恢复备份graph。不要把新Prompt绑定到未验收的新graph并声称完成部署。

## 线上验收（当前尚未执行）

| 输入 | 必须看到 | 性能/调用核对 |
|---|---|---|
| 目前past due情况是什么 | 实时Country指标、查询时间、前5Vendor及解释；不只有两行快照 | 无分页时默认US/CA各1次source，retained读另计 |
| US Vendor64342的情况 | 指定Vendor Open、PD、ratio、Code/Source结构或精确数据缺口 | 无需名称lookup；一Country/set一次source，分页另计 |
| 一个真实完整Vendor名称 | 唯一识别后分析；同名给候选 | 批量identity后按Country一次set查询 |
| 多个名称/编号，含重复、零PD、查不到、跨Country同名 | 每个输入有去向；不漏项、不把缺失写0、不猜同名；合计不重复 | 不逐Vendor执行同一aggregate；看source和retained调用分别次数 |
| 13家及更大批次 | 全部覆盖或列出局部失败；ordinal/count一致 | 实际过8KB才分块；无不前进分页 |
| 无预警完整report／开始测试／明确历史日期 | 原标准报告与ETA/check字段绑定保留 | 与相同Country/P/T的3.6.3比较 |
| 大小写、引号、部分名称、权限过滤 | 精确匹配/候选/缺口；无权限泄漏 | 用部署账号与受限角色分别检查 |

同时对照可信的实时源查询相同口径；数据变动造成的差异要保存capture说明。
未提供可信业务参考数字和源刷新SLA；不要声称source freshness、权限或线上性能已验收。
本地SQL使用新版chdb，不能证明ClickHouse22.8/JDBC/Atlas路径通过；新SQL必须执行上表。
5秒source超时若仍发生，保留错误及请求，仅凭本轮不能保证已消除。

本地证据：默认发布检查包含12项新接口/SQL/retained读取测试及既有回归；manifest记录实际执行状态。
`tests/fixtures/live-question-cases.json`是公开验收规范，不是LLM已通过的证据。
构建工作记录见 `docs/builds/current-3.7.0/`。

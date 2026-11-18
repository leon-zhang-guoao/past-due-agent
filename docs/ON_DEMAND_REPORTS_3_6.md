# 按需报告与 Vendor 查询 — 3.6.0

## 目标与设计

用户主动查看报告不依赖预警；询问一个或多个 Vendor 时保留完整指定集合，包括改善、持平和无可读数据的 Vendor。自动预警继续使用原有阈值与筛选。Atlas 源码保持不变。

原流程把自动监控预设与用户请求混在一起：DAILY 先判断触发，再取正向贡献 Top10；单 Vendor 已有 SINGLE_VENDOR，但多 Vendor 完整报告只能多次查询，CURRENT 通用 explore 的 SQL 又较重。

新流程将调用来源、时间口径、Country/Vendor 范围、分析操作、输出形式分别处理。报告是输出需求，预警是事实状态；用户请求本身允许分析。没有新增封闭场景分类器或运行时 Python 服务。Prompt/Skill 理解与组合，graph 返回有契约的事实。

| 用户需求 | 执行路径 | 输出 |
|---|---|---|
| 无预警也看完整报告 | 最新有效 P/T → report-v2 TOP_N | 真实预警状态、全国指标、包含非正向 Vendor 的排名与 ETA 分析 |
| 某 Vendor 当前情况 | current-v2 一元素集合 | CURRENT 数量、比例、Code/Source 分布、覆盖状态 |
| 几个 Vendor 当前情况 | current-v2 精确集合 | 一次逻辑批量查询，逐个交代覆盖与指标 |
| 某一个/几个 Vendor 的完整报告 | 有效 P/T → report-v2 VENDOR_SET | 精确集合，不按贡献正负或默认 Top10 丢弃 |
| 排名、历史、范围、先选择再展开 | 现有 explore-v3 组合 | 保持原有操作、分母和日期语义 |
| 定时监控、明确测试 | 原 DAILY/TEST 路径 | 保持原阈值、业务状态及 TEST 标记 |

报告未明确时间时默认最近完成的有效快照对，并展示实际日期；普通“Vendor 情况”默认 CURRENT。上下文已有范围时保留，只修改用户明确改变的项。CURRENT 与快照对分别标记。单问指标只回答相关模块。

## 实现

### report-v2

`pastdue.report-v2-query/rows` 的九个标量输入：Country、T、P、calendar-mode、selection-mode、top-n、vendor-codes-json、page-after-ordinal、page-size。

- TOP_N：按全国贡献排序，包含下降和持平；默认按需报告 Top10。
- POSITIVE_TOP_N：明确要求正向贡献时使用，保留空正向集合。
- VENDOR_SET：去重后的明确 Vendor 集合，top-n=0；单 Vendor 为一元素集合。
- 报告字段与 metric3.1.0 沿用旧接口；增加 VENDOR_COVERAGE 和分页元数据。
- 全国分母与排名先计算再筛选；缺失 Vendor 种子只承载身份，不产生 ETA 桶，不改变真实 Vendor 排名。
- 两个历史表引用：受 P/T 范围约束的日期检查、仅 P/T 的事实读取。线性窗口管道复用聚合结果；VENDOR_SET 中非目标 Vendor 只参与全国指标，省去其 ETA 展开。

### current-v2

`pastdue.current-v2-query/rows` 的八个标量输入：Country、vendor-codes-json、三项组织筛选、denominator-scope、page-after-ordinal、page-size。

- 一处 detail view 引用；按 Vendor×Code×Source 聚合后，用窗口计算 Vendor 和总体指标，避免反复展开明细 CTE。
- FILTERED_POPULATION / WITHIN_VENDOR 可在基础读取时限定指定 Vendor；COUNTRY_ALL 保留全国读取及未筛选全国 PD。
- COUNTRY_ALL 中非目标 Vendor 折叠到内部汇总标识，仅参与全国 PD，减少不必要的 ETA 分组；它不会作为 Vendor 结果返回。
- 组织映射去重后以集合判断筛选；无组织筛选时保留未映射 Vendor。
- FACT 返回桶 PD/Open、Vendor 总量、显式分母、桶比例和 Vendor 比例；Vendor 总量在桶间重复，不能相加。
- VENDOR_COVERAGE 区分 MATCHED、NO_READABLE_ROWS、EXCLUDED_BY_ORG；有 Open 而 PD=0 是测得零，无可读数据不是零。
- 此入口只提供 CURRENT；排名、历史、ETA 选择/展开过滤等继续交给 explore。PO/Owner/联系人保持独立的按需能力。

两个入口按计算职责复用，覆盖报告选择与当前集合聚合，不为每句自然语言增加专用 resolver。

### 分页和完整性

每页最多199条非控制记录，加重复控制行，resolver 默认200。完整结果有全局 ordinal、expected-record-count、page-record-count、next-after-ordinal 和内容 fingerprint。指纹不含时间和页号，包含指标与身份。控制行 ordinal0；合并去重控制，覆盖占位字段不作观测。

分页约束行数，不保证序列化结果低于线上字节上限。报告起始 page-size=50，CURRENT 起始100，依据真实字节拒绝作有证据的缩小。合成本地报告100行加控制的带命名空间 JSON 约311 KB，已超过源码默认256 KiB；因此不用100作为报告起始值。这个估算不是线上序列化实测，长文本仍可能要求更小页。每页重新执行 SQL，较小页不会降低聚合成本。fingerprint 发现普通漂移，不能证明事务隔离；不一致遵守已有一次重取规则。

## 兼容性与文件

原39个 resolver、绑定和指标含义保留；新增2个后共41个。旧报告、历史、自动预警、自由问答、CURRENT 明细/Owner/联系人和 HTML 按需流程继续有效。新增能力在 Atlas 发布后才能使用。

| 文件 | 职责 |
|---|---|
| agent/SYSTEM_PROMPT.md、skill/pastdue-analysis/SKILL.md | 入口路由：报告与预警独立，单/多 Vendor |
| references/report-planning.md | 范围、默认值、能力选择、覆盖及回退指导 |
| references/report-v2-query.eql、current-v2-query.eql | 完整 EQL 示例；示例日期不是运行默认值 |
| scripts/build_report_v2.py、build_current_v2.py | 新 SQL 与 EDN 构建，不执行在线业务分析 |
| scripts/build_analysis_contract.py、atlas/charts/pastdue.edn | 确定性生成 graph3.6.0 |
| scripts/analysis_validation.py | 报告覆盖记录与0起始分页 ordinal 的离线核对 |
| scripts/test_report_v2.py、test_current_v2.py | 合成 ClickHouse 和旧接口差异校验 |
| scripts/build_release.py、run_golden_eval.py | 新参考文件随包交付，维护实际验证状态 |

atlas/open-atlas-studio 未改。注册源超时与映射须单独核对，不能通过新 graph 声称已修复。原顶层 timeout 配置未自动替换，因为它不能证明线上注册源配置。

## 验证与上线

本地 SQL：`python scripts/test_report_v2.py`、`python scripts/test_current_v2.py`；需要 chdb 执行合成数据，跳过不能作为验收。离线检查：`python scripts/run_golden_eval.py`。这些不是 Atlas/ClickHouse22.8 或真实 agent 验证。

新增回归包括未预警、负贡献、零值与缺失、单边日期、13家 Vendor、非法输入、重复映射、组织筛选、三种分母、分页/指纹，以及与旧 SINGLE_VENDOR 和 explore-v3 逐字段一致。旧39个 resolver/绑定逐项比较保持。

1. 保留已发布 graph/env 与源映射，在草稿/测试版本校验3.6.0 EDN。
2. 确认实际源 `past-due-ck` 的有效超时与 root 预算。需要调整时使用平台支持的源配置，先核对共享消费者；不能调整则按现有5秒约束实测。
3. 在实际 ClickHouse22.8 和 Atlas 运行两个 EQL 示例，替换真实可读 Vendor、有效日期，记录语句/参数、耗时、字节、分页和覆盖。
4. 跑 tests/fixtures/live-question-cases.json 新增按需报告和 Vendor 场景，核对原始请求→事实→交付；回归旧预警与自由问答。
5. 满足正确性与性能后发布 graph，再安装匹配 Prompt/Skill。失败则回退原版本；旧部署只使用发现且语义等价的路径。

目前不保证5秒内完成，不宣称线上超时消除。源视图、表排序/分区、驱动与策略影响真实性能。若仍超预算，再依据执行计划优化源视图或评估数据库聚合层和刷新语义，保留全国分母与完整性。

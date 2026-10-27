# 3.1.1 — 批量报告取消问题修复

## 证据与边界

用户实际返回 ClickHouse **22.8.21.38**、Code **394 QUERY_WAS_CANCELLED**，
报错表达式包含 `max(target_country_open) FROM country_pair`。基础 Vendor 排名成功。
这是数据源执行被取消，不是 UNKNOWN_IDENTIFIER，也不是分页漏读。
取消发起方（JDBC / Atlas deadline / 数据库 / 外部取消）仍未确认；不能写成已确定根因。

## 实现

- `pastdue.report-query/rows` 七输入、字段、Top10 / Top5+Other、US 联合来源和
  正向贡献口径不变。指标版本保留 3.1.0；发布版本 3.1.1、source revision 2。
- **两条独立受限源读取**：date_guard 只读取 Country 的 `[P,T]` 日期键并验证
  P 确实是 T 前最近可用合格快照；period_raw 只读 Country + `snapshot_date IN(P,T)`。
  不依赖物化 CTE、新 analyzer、临时表、会话状态或数据库变更。
- facts 以标签独立扩展 metrics / Code / Source / Code=US×Source，分别分组，
  不交叉连接独立分布。窗口只在唯一 metrics 标签上计算 Vendor / Country 数量与分母，
  不把桶或 seed 的重复记录计入指标。
- Country 指标、全量正向分母、稳定排名、选中数量及覆盖率沿同一线性链传递。
  删除反复引用 country_pair / selected / report_scope 的标量子查询和事实链分支。
- 独立 control seed 保证不存在 Country、无效日期、空选择都返回一个控制记录；
  每家每维 seed 保证零 US 桶仍有 required=false 的 VENDOR_CHECK。
- 先完成全量指标和选择，随后只对选中 Vendor 运行 ETA 桶排序和 Top5/Other 窗口。
  数量与比率、signed totals、原始 UNKNOWN、行序号、预期记录数继续核对。
- 显式 Bool 输出，避免 JDBC/JSON 把 checks 编码为普通整数。
- Prompt/Skill 明确 exact execute→handle 关联、先串行建立关联、不得靠删除必需字段
  或盲目缩页重试取消的聚合。Studio 普通关联与 MCP 参数化查询不再混为一谈。

## 本地验证（不等于 22.8 / Atlas 通过）

- 本地 chdb / ClickHouse 24.8.4.1 合成 SQL 和报告核对测试，真实运行结果见发布 manifest。
- EXPLAIN：报告 ReadFromMergeTree 节点从上一版 21 降至 2；这只是计划节点，不代表
  生产毫秒数、固定 I/O 节省或 SLA，也不证明 22.8 使用完全相同的计划。
- 本地 24.8 classic analyzer 与默认 analyzer 结果一致；仍不能代替目标 22.8 验证。
- 新旧独立 SQL 结果对照：选中集合、排名、所有 Vendor 指标、Code/Source/US Top5、
  Other 两期计数和全桶增加/减少总量。
- 回归：无效日期/日历/选择、缺失 Country、无 Open、坏计数、空选择、单 Vendor
  负贡献、上期有本期无、无 US 桶、分页及全集期望记录数。
- 本机没有 ClickHouse 22.8 服务/容器运行环境；未连接生产。**22.8 / JDBC / Atlas
  包装 / 实际超时与最终 Agent 报告均待线上验收**。

## 部署与回滚

1. 保留已部署图及 source slot 绑定；不要改变实际 `past-due-ck` 的连接信息。
2. 上传 3.1.1 的 **atlas/charts/pastdue.edn**（权威合并图），不要只上传参考分片。
   保留现有 source slot→实际 source 的绑定；不要求新表、服务、Python 或更长超时。
3. 先串行在 Studio 执行下面的 US，再把 Country 改 CA；记录耗时及完整原始错误。
4. 更新 matching Prompt/完整 Skill，再运行 开始测试 / start test。
   每个 execute 只使用它刚返回的 handle，保留原始关联以核验；不能拿上一轮替代。
5. 如需回滚，使用已保留的 `releases/pastdue-agent-v3.1.0.zip` 的图、Prompt、Skill
   成套恢复。`pre-v3.1-rollback.zip` 是更早基线，不是本次 patch 的直接回滚版本。

## Studio smoke EQL（已去参数化分页）

```clojure
{:entity {:pastdue.report-query/country-code "US"
          :pastdue.report-query/target-snapshot-date "2026-10-26"
          :pastdue.report-query/previous-snapshot-date "2026-10-23"
          :pastdue.report-query/calendar-mode "WEEKDAYS"
          :pastdue.report-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.report-query/top-n 10
          :pastdue.report-query/vendor-code 0}
 :query [{:pastdue.report-query/rows
           [:pastdue.report-fact/scope-key
            :pastdue.report-fact/record-type
            :pastdue.report-fact/country-code
            :pastdue.report-fact/vendor-code
            :pastdue.report-fact/target-snapshot-date
            :pastdue.report-fact/previous-snapshot-date
            :pastdue.report-fact/scope-status
            :pastdue.report-fact/dimension
            :pastdue.report-fact/eta-value
            :pastdue.report-fact/row-kind
            :pastdue.report-fact/previous-past-due-count
            :pastdue.report-fact/target-past-due-count
            :pastdue.report-fact/counts-match
            :pastdue.report-fact/expected-record-count
            :pastdue.report-fact/row-ordinal]}]}
```

此投影只定位执行是否成功，不足以完成标准报告。
默认页可能截断；结果页数必须根据 expected-record-count 及宿主已支持的分页读取，
不把普通查询一次成功视为完整验收。完整报告还须读取全部必需字段。
若仍取消，保留 Code、版本、耗时与表达式；不可宣称取消来源已解决。

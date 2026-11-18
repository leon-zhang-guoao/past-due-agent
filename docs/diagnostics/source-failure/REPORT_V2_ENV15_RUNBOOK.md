> 后续进展：现场已提供ClickHouse Code62，确认双重ORDER BY。请先按 [revision8修复与验证](REPORT_SORT_REV8_FIX.md) 操作；下文保留为修复前排查记录。

# 3.6.0 更新后报告失败：env15 排查执行单

用户已确认拿不到 recent-id 详情。当前执行入口改为
[无需后台日志的 EQL 对照与恢复](AGENT_NO_LOG_REPORT_RECOVERY.md)，
其中包含完整查询、顺序、停止条件和结果表；本文后台诊断步骤不再是前置要求。

## 已确认

- 本次记录加载了3.6.0 Skill，list_attributes 看到了 report-v2 输入和字段。
- monitor 返回结果；historical comparisons 的后续读取显示了 US/CA 的 NORMAL、有效 P/T=2026-11-16→2026-11-17。但 execute 展示的 handle 与随后读取 handle 不一致，不能把这些读取自动绑定到展示的请求。
- US/CA 的 report-v2 实际执行均拒绝：`:engine.execution/failed`，`:error/category :source`，env/version15。没有底层 ClickHouse code/message，不能归因为旧5秒超时，也不能证明新SQL已在线兼容。
- US recent-id：`6d86f35a-7fa5-4514-a945-3fbe80b85564`。
- CA recent-id：`9a58895a-40fd-41fa-82f0-b3dbd2215b5b`。
- `analyze_result input_row_limit=0` 曾被明确拒绝，改为20000后读取成功。这是独立的已纠正参数错误，没有引起后面的 report-v2 source failure。
- 日志没有 current-v2 执行，不能认定其成功或失败。

## 执行规则

只做只读排查，不修改 Atlas 源码，不改超时、不发新 EDN、不把 SQL 文本提交到 execute_eql/analyze_result。原业务请求文本没有包含在附件中，不能据本文件推断用户是否明确 TEST。

按下面顺序执行；每步记录真实提交对象、原始响应、时间、env/version、recent-id、handle及cause。参数日期只重放本次失败，不能作为未来报告的默认日期。

### 1. 优先读取既有错误，不重复运行

在 Atlas Studio 的近期查询/诊断面打开上面两个 recent-id，导出脱敏 Sources/Causes：resolver-id、source-ref、stage、category、异常类、sql-state、vendor-code、底层message。若当前连接不提供此能力，明确此步骤未执行并转交平台管理员；不要虚构get_recent等工具。先用list/read支持的真实工具描述，不能以普通业务EQL查询recent-id。

若能查看实际 SQL/参数回执，核对与 `report-v2-env15/us-binding-reference.sql` / `ca-binding-reference.sql` 的差异。这两个文件仅由本地graph3.6和附件参数生成，未证明等于线上实际绑定；只能在现有授权数据库客户端作只读对照。

### 2. 最小新旧接口对照（仅在cause不足时）

顺序执行，每国最多各一次新/旧请求，先US，后CA。新入口 page-size=1，投影缩小，只用来区分输出预算和source执行故障；它不会降低核心聚合成本。旧入口相同Country/P/T/正向Top10，但无新分页/覆盖能力，不当作所有新语义的通用替代。

调用 execute_eql 的对象形状：`{"query":"<下面完整EQL>","retain_result":true}`；只带实际工具schema支持的键。失败后停止该入口重复调用，保存recent-id和cause；不递归按Vendor重试。

#### US new

```clojure
{:entity {:pastdue.report-v2-query/country-code "US"
    :pastdue.report-v2-query/target-snapshot-date "2026-11-17"
    :pastdue.report-v2-query/previous-snapshot-date "2026-11-16"
    :pastdue.report-v2-query/calendar-mode "WEEKDAYS"
    :pastdue.report-v2-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.report-v2-query/top-n 10
    :pastdue.report-v2-query/vendor-codes-json "[]"
    :pastdue.report-v2-query/page-after-ordinal 0
    :pastdue.report-v2-query/page-size 1}
  :query [{:pastdue.report-v2-query/rows [:pastdue.report-v2-fact/scope-key :pastdue.report-v2-fact/record-type :pastdue.report-v2-fact/scope-status :pastdue.report-v2-fact/country-code :pastdue.report-v2-fact/target-snapshot-date :pastdue.report-v2-fact/previous-snapshot-date :pastdue.report-v2-fact/selection-mode :pastdue.report-v2-fact/metric-version :pastdue.report-v2-fact/selected-vendor-count :pastdue.report-v2-fact/expected-record-count :pastdue.report-v2-fact/row-ordinal]}]}
```

#### US legacy

```clojure
{:entity {:pastdue.report-query/country-code "US"
    :pastdue.report-query/target-snapshot-date "2026-11-17"
    :pastdue.report-query/previous-snapshot-date "2026-11-16"
    :pastdue.report-query/calendar-mode "WEEKDAYS"
    :pastdue.report-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.report-query/top-n 10
    :pastdue.report-query/vendor-code 0}
  :query [{:pastdue.report-query/rows [:pastdue.report-fact/scope-key :pastdue.report-fact/record-type :pastdue.report-fact/scope-status :pastdue.report-fact/country-code :pastdue.report-fact/target-snapshot-date :pastdue.report-fact/previous-snapshot-date :pastdue.report-fact/selection-mode :pastdue.report-fact/metric-version :pastdue.report-fact/selected-vendor-count :pastdue.report-fact/expected-record-count :pastdue.report-fact/row-ordinal]}]}
```

#### CA new

```clojure
{:entity {:pastdue.report-v2-query/country-code "CA"
    :pastdue.report-v2-query/target-snapshot-date "2026-11-17"
    :pastdue.report-v2-query/previous-snapshot-date "2026-11-16"
    :pastdue.report-v2-query/calendar-mode "WEEKDAYS"
    :pastdue.report-v2-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.report-v2-query/top-n 10
    :pastdue.report-v2-query/vendor-codes-json "[]"
    :pastdue.report-v2-query/page-after-ordinal 0
    :pastdue.report-v2-query/page-size 1}
  :query [{:pastdue.report-v2-query/rows [:pastdue.report-v2-fact/scope-key :pastdue.report-v2-fact/record-type :pastdue.report-v2-fact/scope-status :pastdue.report-v2-fact/country-code :pastdue.report-v2-fact/target-snapshot-date :pastdue.report-v2-fact/previous-snapshot-date :pastdue.report-v2-fact/selection-mode :pastdue.report-v2-fact/metric-version :pastdue.report-v2-fact/selected-vendor-count :pastdue.report-v2-fact/expected-record-count :pastdue.report-v2-fact/row-ordinal]}]}
```

#### CA legacy

```clojure
{:entity {:pastdue.report-query/country-code "CA"
    :pastdue.report-query/target-snapshot-date "2026-11-17"
    :pastdue.report-query/previous-snapshot-date "2026-11-16"
    :pastdue.report-query/calendar-mode "WEEKDAYS"
    :pastdue.report-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.report-query/top-n 10
    :pastdue.report-query/vendor-code 0}
  :query [{:pastdue.report-query/rows [:pastdue.report-fact/scope-key :pastdue.report-fact/record-type :pastdue.report-fact/scope-status :pastdue.report-fact/country-code :pastdue.report-fact/target-snapshot-date :pastdue.report-fact/previous-snapshot-date :pastdue.report-fact/selection-mode :pastdue.report-fact/metric-version :pastdue.report-fact/selected-vendor-count :pastdue.report-fact/expected-record-count :pastdue.report-fact/row-ordinal]}]}
```

### 3. 判定与下一步

| 结果 | 允许的结论/动作 |
|---|---|
| 原失败cause明确语法、UNKNOWN_IDENTIFIER、ILLEGAL_AGGREGATION、类型/函数错误 | 按错误位置修正graph绑定SQL；用ClickHouse22.8与JDBC路径验证，不能只用新版本chdb过关。 |
| 新最小仍source失败、旧最小成功 | 故障集中在新增SQL/绑定路径；取底层cause并对照SQL，不等于已证明某一种语法错误。当前POSITIVE_TOP_N可用旧接口作等价恢复。 |
| 两者都相同source失败 | 检查共享SQL结构、实际源策略/用户、驱动与负载；不能继续认定只因新接口。 |
| 明确Code159/timeout | 核对该路径有效超时和实际耗时；源SQL优化或支持的源配置调整。不能用小分页替代计算优化。 |
| 新最小成功、原完整投影失败 | 核对明确字节/行/字段转换报错；最小投影改变输出也可能改变优化计划，需cause确认。再一次完整范围验证分页，不能把最小投影当完整报告。 |
| 未知/无cause | 状态UNCONFIRMED；交付独立已验证事实和缺失范围，不发布“最终修复版”。 |

### 4. 串行绑定结果，修复读取链

每次 execute 的实际响应是该次 handle 的唯一来源。新请求响应复用了旧handle/旧captured-at，或后续使用未在该请求响应出现的handle，先隔离依赖事实、保存原始transport调用ID和响应；不能靠数字看起来合理修复关联。导出/UI重复展示、宿主转发、缓存和agent错用均待核对，不能直接断言Atlas缓存bug。

取真实handle后，优先只提交：

```json
{"result_handle":"<该次响应真实handle>","sql":"SELECT * FROM data ORDER BY row_ordinal","analysis_version":2}
```

若实际工具schema要求 input_row_limit，给1..20000正整数（例如20000），禁止0。可选query/result_handles应省略；如果宿主必填空字段，记录真实对象和平台拒绝再作有证据修正，不声称Skill能拦截序列化。monitor/history没有row_ordinal时按country_code排序。

只在原始响应关联可信后使用事实。完成新旧对照也不等于完整报告已完成；仍要源分页、retained/cell完整性、Vendor/ETA核对和实际交付覆盖。

## 本地验证边界

本次本地合成chdb重放：旧report POSITIVE_TOP_N返回181条，新report-v2 page-size50返回51条；关闭新分析器同样成功。未复现source failure。该结果不能证明ClickHouse22.8或线上JDBC兼容，不能据此修改SQL并宣称修复。

当前不替换3.6.0交付包。待cause和新旧对照明确后，再决定SQL修补、源配置或读取链修复，避免让用户盲目反复更新。

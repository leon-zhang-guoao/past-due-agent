# PastDue ETA 失败排查：Agent 单文件执行指南

## 任务与完成条件

你是连接 Atlas 的执行 Agent。请按本文实际调用工具，排查 US13 Vendor 的 CURRENT ETA 查询失败原因。
本文包含全部基线查询和判读规则，无需读取同目录其他文件，也无需取得 recent-id 日志。
执行只读诊断、记录证据并交付诊断结论；本轮不修改图、部署、凭据、Prompt 或 Skill。

完成条件：三个基线路径各有实际响应或明确的未执行原因；将已确认事实、待验证推断和剩余阻塞分开。
业务数据存在且完整时可以附带结果；诊断完成不等于13 Vendor 的 ETA 刷新完成。
若工具不存在，记录 TOOL_UNAVAILABLE 并结束，不编造执行结果。

## 1. 固定业务范围与已有证据

原始请求：第一列是 US Vendor#，重新刷新这些 Vendor 的 PastDue ETA 数量。

| Vendor# | 名称 | 用户提供的旧 PastDue 行数 |
|---|---|---:|
| 64342 | F5 NETWORKS, INC | 639 |
| 75256 | NINJAONE LLC | 353 |
| 64351 | CIENA COMMUNICATIONS,INC. | 492 |
| 19215 | MICROSOFT CORP/LICENSING | 347 |
| 65505 | JUNIPER NETWORKS (US), INC | 332 |
| 75257 | ZOOM VIDEO COMMUNICATIONS, INC. | 290 |
| 22344 | BARRACUDA NETWORKS, INC | 243 |
| 85058 | EXTREME NETWORK INC | 159 |
| 71886 | ALE USA INC. | 211 |
| 48378 | FORTINET INC. | 200 |
| 65885 | FIVE9 INC. | 181 |
| 75691 | SPLUNK INC. | 174 |
| 72478 | CROWDSTRIKE, INC. | 544 |

Country=US；时间=CURRENT；13 Vendor 显式集合；无组织过滤；不排名；不设 Top N。
旧行数只有用户输入来源，未确认其采集时刻，不能作为当前查询必须相等的参考答案。
CURRENT 使用实际捕获时间与 America/Los_Angeles 时区；1970-01-01 是非活动日期参数的占位值。

已有失败记录：

- V3 输入齐全，返回 engine.execution/failed、error/category=source、source execution failed；底层原因未知。
- 旧接口曾提交 VENDOR_CODE_SOURCE 和 WITHIN_VENDOR，但其 SQL 只接受
  VENDOR / VENDOR_CODE / VENDOR_SOURCE，以及 COUNTRY_ALL / FILTERED_POPULATION。
  本地有数据样本可复现错误参数导致0行；这解释备用路径，不解释 V3 源错误。
- 最后一次读取使用了可见 execute 响应未提供的 handle，读取返回的 handle 也与请求不同。
  该次结果存在关联异常，不能支持业务结论。重复 handle 本身不单独证明缓存错误。
- 本地合成 SQL 通过不代表线上 SQL、JDBC、权限或真实数据已通过。

## 2. 发现工具并建立执行记录

使用当前连接实际暴露的工具名；它们可能带有连接前缀。
先阅读 execute_eql、list_entities、list_attributes 和可用 analyze_result 的实际参数描述。

1. `list_entities`：调用参数 `{}`，记录实际环境/版本及三条查询路径是否存在。
2. `list_attributes`：若工具 schema 与下例一致，分三次调用：

```json
{"entities":["pastdue.current-analysis-query","pastdue.current-analysis-fact"]}
```

```json
{"entities":["pastdue.explore-v2-query","pastdue.explore-v2-fact"]}
```

```json
{"entities":["pastdue.explore-v3-query","pastdue.explore-v3-fact"]}
```

核对输入、输出属性及必填项。string 类型本身不能证明枚举值受支持。
某路径不存在，记 CAPABILITY_UNAVAILABLE，跳过该路径并继续其他可用路径。
线上 schema 与下文不一致，记录具体差异；只执行契约匹配的路径。

为每次调用记录：步骤、工具名、实际参数、调用ID（若可见）、环境/版本、开始/结束时间、
原始响应、错误类别、返回行数、截断信息，以及真实返回的 handle 和捕获/过期时间。
如可写工作文件，存入当前会话的诊断目录；否则直接在最终回复提供记录。
对外展示先脱敏；保留诊断必要字段，不存储凭据。

## 3. 统一执行方式与错误处理

以下每个 eql 代码块都是一次独立查询。将整个代码块内容作为字符串 Q，实际调用：

```text
execute_eql({"query": Q, "retain_result": false})
```

Q 是下文完整 EQL 字符串，不是字母 Q、文件路径或多个查询拼接。
通过工具的结构化参数传入，保留 Vendor JSON 数组在 EQL 中的字符串类型。
只有工具明确支持 retain_result=false 时才按此执行。若该模式不可表达，记录
DIRECT_MODE_UNAVAILABLE；可用工具明确支持的 retain_result=true 执行一次并按第7节读取，
同时记录模式差异，不声称完成了直接返回对照。
即使 false，宿主也可能裁剪输出或返回 handle；只按真实响应处理。

顺序执行第4、5、6节，共最多三次基线源查询，每条一次，并发为1。
单条报错或空集合通常不妨碍其他路径取证；遇到明确权限拒绝/连接不可用等共同阻塞，
停止受同一阻塞影响的步骤并说明原因。此处次数界限仅约束本轮诊断，不限制正常业务能力。

每个响应分开标记：

| 状态 | 判定 |
|---|---|
| SOURCE_ERROR | 响应明确报告源执行失败；抄录底层异常（如有），不猜根因。 |
| INVALID_ARGUMENT | 参数或查询校验失败；记录具体字段和实际提交对象。 |
| EMPTY | 实际返回0行；不能解释为PastDue 数量为0或源已损坏。 |
| CONTROL_ERROR | explore 返回 CONTROL 且 scope-status 非OK；保留具体状态，如 NO_DATA / DATA_ERROR。 |
| OK_PARTIAL | 有事实但分页、源完整性、行/单元格裁剪或身份校验未完成。 |
| OK_COMPLETE | 第8节的完整性和范围校验全部通过。 |
| ASSOCIATION_ERROR | 读取响应与实际请求 handle 不一致；隔离该结果。 |
| UNVERIFIED | 无法解析、缺少来源或缺少必要范围/控制字段。 |

CONTROL 的 EMPTY_SELECTION / NO_DATA 与工具返回空集合是不同状态。
每个失败工作项最多一次有证据的参数修正；记录前后差异和依据。
相同失败参数不重发；更换 SQL 输出列或缩小 page-size 不保证降低源聚合成本。

## 4. 基线A：旧接口，单Vendor ETA Code

目的：用合法参数检查 US64342 当前明细聚合路径。它只覆盖单 Vendor 的 Code 边际分布，
不能代替13 Vendor 的联合 Code×Source 刷新。
分母取 COUNTRY_ALL；within-vendor-share 是该旧接口单独返回的指标，并非 WITHIN_VENDOR 输入。

```eql
{:entity {:pastdue.current-analysis-query/country-code "US"
    :pastdue.current-analysis-query/grain "VENDOR_CODE"
    :pastdue.current-analysis-query/vendor-code-filter "64342"
    :pastdue.current-analysis-query/department-type-filter ""
    :pastdue.current-analysis-query/master-primary-filter ""
    :pastdue.current-analysis-query/master-manager-filter ""
    :pastdue.current-analysis-query/denominator-scope "COUNTRY_ALL"}
  :query [{:pastdue.current-analysis-query/rows [
      :pastdue.current-analysis-fact/country-code
      :pastdue.current-analysis-fact/vendor-code
      :pastdue.current-analysis-fact/vendor-name
      :pastdue.current-analysis-fact/eta-value
      :pastdue.current-analysis-fact/grain
      :pastdue.current-analysis-fact/denominator-scope
      :pastdue.current-analysis-fact/open-line-count
      :pastdue.current-analysis-fact/past-due-count
      :pastdue.current-analysis-fact/denominator-past-due-count
      :pastdue.current-analysis-fact/vendor-past-due-count
      :pastdue.current-analysis-fact/past-due-ratio
      :pastdue.current-analysis-fact/past-due-share
      :pastdue.current-analysis-fact/within-vendor-share
      :pastdue.current-analysis-fact/row-ordinal
      :pastdue.current-analysis-fact/expected-row-count
      :pastdue.current-analysis-fact/queried-at-utc]}]}
```

本步完成：记录响应状态、实际行数和范围；无论空集合或源错误，按共同阻塞规则决定是否继续B/C。

## 5. 基线B：V2，13 Vendor 联合分布

线上存在 explore-v2-query/rows 才执行。CURRENT、Vendor集合、粒度、过滤和无排名语义与
原失败 V3 一致；V2不产生 V3 的 SELECTION 记录。下文补充了分页与校验输出字段。

```eql
{:entity {:pastdue.explore-v2-query/country-code "US"
    :pastdue.explore-v2-query/time-basis "CURRENT"
    :pastdue.explore-v2-query/target-snapshot-date "1970-01-01"
    :pastdue.explore-v2-query/previous-snapshot-date "1970-01-01"
    :pastdue.explore-v2-query/range-start-date "1970-01-01"
    :pastdue.explore-v2-query/range-end-date "1970-01-01"
    :pastdue.explore-v2-query/calendar-mode "WEEKDAYS"
    :pastdue.explore-v2-query/pair-policy "PREVIOUS_AVAILABLE"
    :pastdue.explore-v2-query/grain "VENDOR_CODE_SOURCE"
    :pastdue.explore-v2-query/vendor-codes-json "[64342,75256,64351,19215,65505,75257,22344,85058,71886,48378,65885,75691,72478]"
    :pastdue.explore-v2-query/eta-code-filter ""
    :pastdue.explore-v2-query/source-filter ""
    :pastdue.explore-v2-query/department-type-filter ""
    :pastdue.explore-v2-query/master-primary-filter ""
    :pastdue.explore-v2-query/master-manager-filter ""
    :pastdue.explore-v2-query/denominator-scope "WITHIN_VENDOR"
    :pastdue.explore-v2-query/ranking-metric "NONE"
    :pastdue.explore-v2-query/sort-direction "ASC"
    :pastdue.explore-v2-query/change-selection "ALL"
    :pastdue.explore-v2-query/top-n 0
    :pastdue.explore-v2-query/page-after-ordinal 0
    :pastdue.explore-v2-query/page-size 500}
  :query [{:pastdue.explore-v2-query/rows [
      :pastdue.explore-v2-fact/scope-key
      :pastdue.explore-v2-fact/record-type
      :pastdue.explore-v2-fact/scope-status
      :pastdue.explore-v2-fact/country-code
      :pastdue.explore-v2-fact/vendor-code
      :pastdue.explore-v2-fact/vendor-name
      :pastdue.explore-v2-fact/target-snapshot-date
      :pastdue.explore-v2-fact/previous-snapshot-date
      :pastdue.explore-v2-fact/time-basis
      :pastdue.explore-v2-fact/grain
      :pastdue.explore-v2-fact/eta-code
      :pastdue.explore-v2-fact/eta-source
      :pastdue.explore-v2-fact/denominator-scope
      :pastdue.explore-v2-fact/metric-version
      :pastdue.explore-v2-fact/business-timezone
      :pastdue.explore-v2-fact/queried-at-local
      :pastdue.explore-v2-fact/target-past-due-count
      :pastdue.explore-v2-fact/target-open-count
      :pastdue.explore-v2-fact/target-past-due-ratio
      :pastdue.explore-v2-fact/target-past-due-share
      :pastdue.explore-v2-fact/target-ratio-denominator
      :pastdue.explore-v2-fact/selection-rank
      :pastdue.explore-v2-fact/expected-record-count
      :pastdue.explore-v2-fact/row-ordinal
      :pastdue.explore-v2-fact/requested-vendor-count
      :pastdue.explore-v2-fact/returned-vendor-count
      :pastdue.explore-v2-fact/page-record-count
      :pastdue.explore-v2-fact/has-more
      :pastdue.explore-v2-fact/calendar-mode
      :pastdue.explore-v2-fact/organization-basis
      :pastdue.explore-v2-fact/target-share-denominator
      :pastdue.explore-v2-fact/page-after-ordinal
      :pastdue.explore-v2-fact/page-size
      :pastdue.explore-v2-fact/next-after-ordinal
      :pastdue.explore-v2-fact/result-fingerprint]}]}
```

本步完成：识别 CONTROL、FACT、VENDOR_COVERAGE；记录控制状态、计数、分页和截断信息。

## 6. 基线C：V3，13 Vendor 选择与联合展开

沿用原失败调用的26个输入；retain_result 改为false，并补充完整性校验输出字段。
因此这是同输入的诊断对照，不是完全相同的原请求重放。

```eql
{:entity {:pastdue.explore-v3-query/country-code "US"
    :pastdue.explore-v3-query/time-basis "CURRENT"
    :pastdue.explore-v3-query/target-snapshot-date "1970-01-01"
    :pastdue.explore-v3-query/previous-snapshot-date "1970-01-01"
    :pastdue.explore-v3-query/range-start-date "1970-01-01"
    :pastdue.explore-v3-query/range-end-date "1970-01-01"
    :pastdue.explore-v3-query/calendar-mode "WEEKDAYS"
    :pastdue.explore-v3-query/pair-policy "PREVIOUS_AVAILABLE"
    :pastdue.explore-v3-query/grain "VENDOR_CODE_SOURCE"
    :pastdue.explore-v3-query/vendor-codes-json "[64342,75256,64351,19215,65505,75257,22344,85058,71886,48378,65885,75691,72478]"
    :pastdue.explore-v3-query/eta-code-filter ""
    :pastdue.explore-v3-query/source-filter ""
    :pastdue.explore-v3-query/department-type-filter ""
    :pastdue.explore-v3-query/master-primary-filter ""
    :pastdue.explore-v3-query/master-manager-filter ""
    :pastdue.explore-v3-query/denominator-scope "WITHIN_VENDOR"
    :pastdue.explore-v3-query/ranking-metric "NONE"
    :pastdue.explore-v3-query/sort-direction "ASC"
    :pastdue.explore-v3-query/change-selection "ALL"
    :pastdue.explore-v3-query/top-n 0
    :pastdue.explore-v3-query/selection-unit "VENDOR"
    :pastdue.explore-v3-query/rank-partition "GLOBAL"
    :pastdue.explore-v3-query/expansion-eta-code-filter ""
    :pastdue.explore-v3-query/expansion-source-filter ""
    :pastdue.explore-v3-query/page-after-ordinal 0
    :pastdue.explore-v3-query/page-size 500}
  :query [{:pastdue.explore-v3-query/rows [
      :pastdue.explore-v3-fact/scope-key
      :pastdue.explore-v3-fact/record-type
      :pastdue.explore-v3-fact/scope-status
      :pastdue.explore-v3-fact/country-code
      :pastdue.explore-v3-fact/vendor-code
      :pastdue.explore-v3-fact/vendor-name
      :pastdue.explore-v3-fact/target-snapshot-date
      :pastdue.explore-v3-fact/previous-snapshot-date
      :pastdue.explore-v3-fact/time-basis
      :pastdue.explore-v3-fact/grain
      :pastdue.explore-v3-fact/eta-code
      :pastdue.explore-v3-fact/eta-source
      :pastdue.explore-v3-fact/denominator-scope
      :pastdue.explore-v3-fact/metric-version
      :pastdue.explore-v3-fact/business-timezone
      :pastdue.explore-v3-fact/queried-at-local
      :pastdue.explore-v3-fact/target-past-due-count
      :pastdue.explore-v3-fact/target-open-count
      :pastdue.explore-v3-fact/target-past-due-ratio
      :pastdue.explore-v3-fact/target-past-due-share
      :pastdue.explore-v3-fact/target-ratio-denominator
      :pastdue.explore-v3-fact/selection-rank
      :pastdue.explore-v3-fact/expected-record-count
      :pastdue.explore-v3-fact/row-ordinal
      :pastdue.explore-v3-fact/requested-vendor-count
      :pastdue.explore-v3-fact/returned-vendor-count
      :pastdue.explore-v3-fact/page-record-count
      :pastdue.explore-v3-fact/has-more
      :pastdue.explore-v3-fact/fact-record-count
      :pastdue.explore-v3-fact/calendar-mode
      :pastdue.explore-v3-fact/organization-basis
      :pastdue.explore-v3-fact/target-share-denominator
      :pastdue.explore-v3-fact/page-after-ordinal
      :pastdue.explore-v3-fact/page-size
      :pastdue.explore-v3-fact/next-after-ordinal
      :pastdue.explore-v3-fact/result-fingerprint
      :pastdue.explore-v3-fact/selection-unit
      :pastdue.explore-v3-fact/rank-partition
      :pastdue.explore-v3-fact/selected-object-count]}]}
```

本步完成：识别 CONTROL、FACT、SELECTION、VENDOR_COVERAGE；记录控制状态、计数、分页和截断信息。

## 7. 条件分支：保留结果和 handle 读取

基线返回业务行时优先分析该真实响应。以下情况才进入本节：

- execute 实际只返回 handle；读取这个响应提供的真实 handle。
- 某条直接模式基线成功，且还需要对照保留链路：本轮最多选择一条已成功路径，
  保持 EQL 完全一致，仅将 retain_result=false 改为true，执行一次。
  先选择V3，其次V2，最后A；执行时刻不同，结果变化不单独证明保留链路故障。

取 H=该次 execute 响应中的 result_handle，记录来源调用；本文没有可复用的真实 handle。
若实际 analyze_result 支持下面 schema，可先发现列名：

```text
analyze_result({"result_handle": H, "analysis_version": 2})
```

此调用仅发现 schema/counts；没有读取业务行时不算完成。
拿到真实列名后，用同一 H 读诊断行：

旧接口A（仅在这些列名已确认存在时）：

```sql
SELECT * FROM data ORDER BY vendor_code, eta_value, row_ordinal
```

V2/V3（仅在这些列名已确认存在时）：

```sql
SELECT * FROM data ORDER BY row_ordinal
```

调用形式：

```text
analyze_result({"result_handle": H, "sql": S, "analysis_version": 2})
```

S 是上面的 SQL 字符串。每个对象只选用 result_handle 一个结果选择器，
query/result_handles 键保持缺省，false/0 等业务值按 schema 原样保留。
返回中若有 result_handle，须等于 H；缺失时通过真实调用ID/来源元数据确认关联，无法确认则 UNVERIFIED。
输入没有来源、响应串号或无法确认关联时隔离依赖事实，记 ASSOCIATION_ERROR / UNVERIFIED；
其他独立验证结果仍可使用。先查实际请求与响应，不以另一个 handle 替换 H。

发现超过预览上限，可按工具实际支持的有界读取继续读取同一结果；若本轮只需要定位
故障层，记录 OK_PARTIAL 即可。列为空、flatten=0 或 row-count=0，不能反推源业务量为0。

## 8. 完整性与诊断边界

A核对 country_code=US、vendor_code=64342、grain=VENDOR_CODE、分母为COUNTRY_ALL，
实际唯一行数与 expected_row_count 一致，且行和单元格未截断。
V2/V3先核对 CONTROL 的范围、CURRENT、实际捕获时间、metric-version和 scope-status。
FACT只绑定请求的13 Vendor；VENDOR_COVERAGE 的数值占位字段不作为测量值。
V3的 SELECTION 和 FACT 是两个粒度，数量不能相加作业务总量。

分页：本轮默认读首个源页，has-more=true 即将完整刷新标记未完成。
若继续收集完整结果，复制返回的 next-after-ordinal 到同条 EQL 的 page-after-ordinal，
保持其他输入不变，顺序取下一源页。每次返回的 handle 独立绑定，游标不是 handle。
校验每页实际行数=page-record-count、唯一ordinal并集=0..expected-record-count-1、
重复CONTROL范围一致且只计一次、全程fingerprint/全量计数稳定、has-more最终false。
V3还要校验完整 SELECTION数=selected-object-count、FACT数=fact-record-count，
每条FACT绑定到同日期/Vendor的SELECTION；用13 Vendor覆盖状态交代未匹配原因。
若基线所在工具未暴露所需字段或截断无法消除，保持 OK_PARTIAL。

CURRENT页会重新执行源SQL；漂移时隔离该页并集，本轮记录 CURRENT_PAGE_DRIFT，停止拼接。
相同fingerprint不能证明事务一致性。未经完整收集，不输出认证的全量数量或总体成功结论。

## 9. 依据响应判读原因

| 实际结果 | 允许的结论与下一步 |
|---|---|
| A有数据，B有数据，C源错误 | 当前明细及V2路径可用；优先排查线上V3的SQL/绑定、额外计算、引擎兼容。不是全源不可用的证据。 |
| A有数据，B/C均源错误 | 排查explore共用SQL、额外依赖、类型和权限；A成功不能证明这些依赖正常。 |
| A空，B或C有数据 | 核对A接口与结果读取，及64342是否实际匹配；不能用A空否定其他Vendor事实。 |
| A/B/C均空或NO_DATA | 核对环境、US实际数据、视图、权限与关联；合法空和源错误分开记录。 |
| 三者均源错误 | 可能存在共同依赖或连接故障，也可能有独立错误；请求更底层错误证据。 |
| 某路径false成功、true/读取失败 | 增加了保留/读取链路故障嫌疑；核对同时刻、调用关联和输出大小后再定因。 |
| 请求和返回handle不一致 | 该次关联链路异常已确认，责任组件待原始调用日志区分。 |
| 能力不存在或版本变化 | 记录真实环境/版本及能力差异；不以本地发布版本推定线上部署正确。 |

只得到通用 source execution failed 时，最终结论保留“底层原因未确定”。
若当前工具可读到源异常/查询计划，继续取得这些证据；若无权限或工具，明确缺口即可。
IT后续可在其授权的只读环境对线上实际SQL和绑定参数运行EXPLAIN或执行SELECT，
提供数据库异常、数据库/JDBC版本与参数类型。这是后续人工取证，不授权本Agent
绕过Atlas直连数据库、获取凭据或更改业务数据。

## 10. 交付结果

用中文输出以下内容；任何未执行项写 NOT_RUN 和实际原因。

1. 一句话结论：明确已确认失败层与未确定部分。
2. 执行表：

| 步骤 | 环境/版本 | 模式 | 状态 | 实际行数/CONTROL状态 | 完整性 | 错误或关键证据 |
|---|---|---|---|---|---|---|
| A旧接口单Vendor | 实际值 | false或替代模式 | 实际值 | 实际值 | 完整/部分/未知 | 实际值 |
| B V2 13Vendor | 实际值 | 同上 | 实际值 | 实际值 | 同上 | 实际值 |
| C V3 13Vendor | 实际值 | 同上 | 实际值 | 实际值 | 同上 | 实际值 |
| 条件保留读取 | 实际值 | true | 实际值/NOT_RUN | 实际值 | 同上 | handle关联情况 |

3. 已确认原因、仍待验证原因及相应证据，分别列出；避免把部署猜测当结论。
4. 下一步最小修复/取证动作，以及哪些业务结果仍不可交付。
5. 提供实际请求与原始响应（或可访问的记录路径），凭据先脱敏。

如工具或权限缺失导致无法继续，交付当前证据与准确阻塞；无需再次索取 recent-id。

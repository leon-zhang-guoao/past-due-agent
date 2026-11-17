# PastDue 超时排查：Agent 单文件执行指南（第二轮）

## 目标、范围与完成条件

请实际执行本文支持的只读诊断，区分：Atlas请求期限、连接/连接池等待、当前视图读取、
局部聚合、全国分母计算，以及完整SQL的规划/执行/取数成本。无需 recent-id 日志。
本轮不重做第一轮A/B/C，不修改部署、图、SQL绑定、超时值或数据库数据。
本文包含全部探针SQL、分支和报告模板；不依赖其他指南文件。

原业务是 US13 Vendor CURRENT ETA 刷新。本轮用US64342定位故障；局部诊断成功不表示
13 Vendor业务已完成。旧行数不是探针必须匹配的答案。CURRENT事实按实际采集时刻记录。

完成条件：每个适用探针有实际结果或准确的未执行原因；输出故障最早出现的边界、
已确认事实、仍待验证原因及最小下一步。缺少源诊断能力本身就是可交付的阻塞结论。

## 1. 本轮已有证据及待验证判断

第一轮已实际观察：环境 env/version=14；合法旧接口A、V2批量B、V3批量C均在
retain_result=false 下返回 engine.execution/failed、error/category=:timeout。
三次界面区间约6秒（09:53:20–26、09:53:30–36、09:53:41–47），没有事实行或handle。
界面秒级时间不是数据库精确耗时，也不能证明存在固定6秒超时。

本地 sources.edn 写 timeout_ms=120000、max_pool_size=5、min_idle=1，实际线上值及参数
含义未核实。绑定的逻辑源名是 eta-clickhouse，配置清单键是 eta-clickhouse-prod；
可能存在合法映射，应读实际解析结果，不能仅凭名称差异认定配错。
A虽单Vendor，SQL仍计算US全国分母，因此它不是轻量健康检查。
B/C也包含国家总体计算；CURRENT查询引用的CTE、历史分支或映射是否被优化掉，要看真实计划。

开始前向用户简述以下待验证判断，然后继续执行，不等待用户挑选：

| 优先级 | 判断 | 可区分证据 |
|---|---|---|
| 1 | 共同请求期限、连接/池等待造成近似时间失败 | 同执行链路P0也失败，或慢探针在同一期限被取消；结合连接与取消计时。 |
| 2 | 全国总体/视图扫描成本过高 | P0/P1/P2成功，而P3超时或明显更慢。 |
| 3 | 完整SQL重复计算、规划或取数成本较高 | P0–P3成功，而P4失败；计划/查询日志定位差别。 |
| 4 | 源对象、类型转换、权限或部署映射有差异 | 单独对象/转换报明确异常，或线上绑定/连接与本地不同。 |

## 2. 先发现真实工具，选择可执行路径

读取当前工具描述/schema，查找已有的“源连接测试、只读源SQL执行、查询计划、源配置读取、
实际SQL/执行跟踪”能力。名称以实际工具为准，不虚构 test_source / execute_sql 等工具名。
若工具分页，仅遍历与源诊断相关能力；已有同环境有效schema可复用。

按可用能力选择：

- **路径A（首选）**：平台提供源诊断工具，能在实际绑定解析出的相同源配置/凭据/角色/
  JDBC连接池/执行期限下运行只读SELECT。按第4节执行SQL探针。
- **路径B（有限比较）**：已有授权的数据库诊断工具能执行SQL，但绕过Atlas部分链路。
  记录绕过哪些层，再执行适用探针。该客户端成功只说明该客户端路径成功。
- **路径C（仅EQL数据面）**：只有list_entities / list_attributes / execute_eql /
  analyze_result，且没有可执行本文源探针的接口。此时标记 SOURCE_DIAGNOSTICS_UNAVAILABLE，
  跳到第8节，交付能力清单和下文SQL，供平台人员在受控诊断入口执行。
  EQL能力发现成功不是源连接健康证明。

本文SQL是源级排查用SELECT，不是业务Agent的任意SQL能力扩展。
将SQL交给工具明确提供的源SQL参数；execute_eql仅接受EQL，analyze_result只查询实际
保留结果。不要借这两者执行源SQL，也不创建临时resolver/发布图来绕过能力缺失。
使用已有授权连接，无需新凭据；若现有角色拒绝访问，记录阻塞，不切换为更高权限重试。

完成条件：记录实际工具名、支持的参数、所选路径、连接/角色标识（脱敏）与可比性。
没有源工具时立即交付准确阻塞，不能声称“已执行SELECT 1”。

## 3. 执行记录、期限与资源控制

每次记录：探针ID、完整SQL、实际工具参数、连接与环境/版本、角色、开始/结束时间、
客户端总耗时、数据库耗时（若可见）、排队/取连接耗时（若可见）、返回行数、错误类别、
原始数据库错误/SQLState、query_id、取消/终止状态。未知值写UNKNOWN，不以客户端耗时替代数据库耗时。
如可写文件，将脱敏记录存到当前会话诊断目录；无文件能力则在最终回复展示。

顺序、并发1；每个适用探针执行一次，P0–P4最多5次，条件探针P5最多1次。
发现明确语法/类型错误时，可按真实schema作一次有依据修正，保留前后SQL与差异。
修正只用于诊断，不自动写回生产绑定。相同超时不原样重试。

保留线上现有超时，不延长期限。如果工具支持单次诊断deadline，可取现有更小值，最多30秒；
记录该人为上限，将因诊断上限取消标记 DIAGNOSTIC_LIMIT，而不是线上原始失败复现。
客户端报超时不代表数据库已终止。确认取消/终止后再跑下一源查询；无法确认则停止后续重查询，
先记录 UNKNOWN_CANCELLATION。只能对本次明确关联的query_id使用已授权取消机制。
本轮次数/时长界限仅用于取证，不是产品对自由提问的全局限制。

## 4. 顺序探针SQL与继续条件

SQL以现有ClickHouse绑定的表/字段/转换为依据。实际schema不一致时记录DEPLOYMENT_DIFF。
每个代码块是一条独立SELECT，按工具schema原样传入，不能合并为一个请求。
P1/P2保留旧SQL的toString(toInt64(...))筛选表达式便于对照；表达式是否阻碍下推需要EXPLAIN。
P1的LIMIT 1只限制返回行，底层视图可能仍需扫描/执行关联，不保证轻量。

### P0：连接与常量查询

目的：验证该执行链路能取连接、发送查询并返回一个常量。

```sql
SELECT 1 AS connection_probe
```

完成条件：得到一行connection_probe=1并记录时间。
若P0失败，停止P1–P5，优先检查连接/池等待/请求期限/返回链路。
P0成功不证明业务视图、权限或复杂查询正常。

### P1：限定Vendor的一条明细

```sql
SELECT country_code, from_acct_no
FROM past_due.past_due_detail_view
WHERE country_code = 'US'
  AND from_acct_no IS NOT NULL
  AND toString(toInt64(from_acct_no)) = '64342'
LIMIT 1
```

完成条件：记录一行、空结果或具体错误和耗时。
超时则停止P2–P4，不再扩大扫描；可在数据库终止已确认后进入第5节只读计划/配置取证。
空结果仍可运行P2，但不能称64342 PastDue为0。
权限/对象/转换异常优先定位该异常，隔离受影响探针。

### P2：仅局部ETA Code聚合，不算全国分母

```sql
SELECT
    if(empty(trim(ifNull(eta_code, ''))), 'UNKNOWN', upper(trim(eta_code))) AS eta_code,
    toInt64(count()) AS open_line_count,
    toInt64(countIf(toInt64(is_pastdue) = 1)) AS past_due_count
FROM past_due.past_due_detail_view
WHERE country_code = 'US'
  AND from_acct_no IS NOT NULL
  AND toString(toInt64(from_acct_no)) = '64342'
GROUP BY eta_code
ORDER BY eta_code
```

完成条件：记录桶数、耗时、是否截断，以及该单Vendor的实际返回范围。
这是局部诊断查询，不提供全国share，不替代原接口全部指标。
若P2超时，停止P3/P4，先排查视图扫描、筛选下推和局部聚合成本。
若P2成功但无桶，记录EMPTY，仍可执行P3验证全国聚合路径。

### P3：独立测量全国PastDue分母

```sql
SELECT toInt64(countIf(toInt64(is_pastdue) = 1)) AS country_past_due_count
FROM past_due.past_due_detail_view
WHERE country_code = 'US'
  AND from_acct_no IS NOT NULL
```

该SQL与旧接口country_total的范围相同：US、from_acct_no非空、is_pastdue转Int64等于1。
结果只有一行，但仍需计算总体；COUNT不是轻量健康探针。
完成条件：记录数值与耗时；若超时停止P4，优先调查总体扫描。
P2和P3在不同时刻运行，只用于成本定位，不据此拼接或认证比率。

### P4：旧接口完整绑定SQL对照

仅在P0–P3均成功且前一查询已终止后执行。
下文由本地当前旧接口绑定展开为合法常量：US、64342、VENDOR_CODE、COUNTRY_ALL、无组织过滤。
它是本地参考，不证明线上绑定相同。若可读取线上实际SQL/参数，先比较；有差异优先记录
并执行线上真实版本的合法SELECT（通过工具结构化绑定参数），本文版本保留为参考。
若拿不到线上绑定，执行本文版本时标记 LOCAL_REFERENCE_SQL_ONLY。

```sql
WITH mapped AS (
 SELECT DISTINCT country_code,toInt64(vendor_no) AS vendor_code FROM past_due.vendor_master_mapping_view
 WHERE country_code='US'
 AND (empty('') OR upper(trim(department_type))=upper(trim('')))
 AND (empty('') OR trim(ifNull(master_primary,''))=trim(''))
 AND (empty('') OR trim(ifNull(master_manager,''))=trim(''))),
 detail AS (SELECT t.* FROM past_due.past_due_detail_view t WHERE t.country_code='US' AND t.from_acct_no IS NOT NULL),
 scoped AS (SELECT * FROM detail WHERE (empty('64342') OR toString(toInt64(from_acct_no))='64342')
 AND ((empty('') AND empty('') AND empty(''))
 OR (country_code,toInt64(from_acct_no)) IN (SELECT country_code,vendor_code FROM mapped))),
 totals AS (SELECT (SELECT toInt64(countIf(toInt64(is_pastdue)=1)) FROM detail) AS country_total,
 (SELECT toInt64(countIf(toInt64(is_pastdue)=1)) FROM scoped) AS filtered_total),
 vendor_totals AS (SELECT country_code,toInt64(from_acct_no) AS vendor_code,toInt64(countIf(toInt64(is_pastdue)=1)) AS vendor_past_due_count FROM scoped GROUP BY country_code,vendor_code),
 aggregate_rows AS (
 SELECT country_code,toInt64(from_acct_no) AS vendor_code,min(vendor_name) AS vendor_name,
 if('VENDOR_CODE'='VENDOR','ALL',if('VENDOR_CODE'='VENDOR_CODE',if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))),if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)))) AS eta_value,
 toInt64(count()) AS open_line_count,toInt64(countIf(toInt64(is_pastdue)=1)) AS past_due_count
 FROM scoped WHERE 'VENDOR_CODE' IN ('VENDOR','VENDOR_CODE','VENDOR_SOURCE') AND 'COUNTRY_ALL' IN ('COUNTRY_ALL','FILTERED_POPULATION')
 GROUP BY country_code,vendor_code,eta_value)
 SELECT a.country_code AS country_code,a.vendor_code AS vendor_code,a.vendor_name AS vendor_name,a.eta_value AS eta_value,
 a.open_line_count AS open_line_count,a.past_due_count AS past_due_count,
 v.vendor_past_due_count AS vendor_past_due_count,'VENDOR_CODE' AS grain,'COUNTRY_ALL' AS denominator_scope,
 if('COUNTRY_ALL'='COUNTRY_ALL',t.country_total,t.filtered_total) AS denominator_past_due_count,
 toDecimal128(toDecimal256(a.past_due_count, 18) / nullIf(toDecimal256(a.open_line_count, 18), 0), 18) AS past_due_ratio, toDecimal128(toDecimal256(a.past_due_count, 18) / nullIf(toDecimal256(denominator_past_due_count, 18), 0), 18) AS past_due_share, toDecimal128(toDecimal256(a.past_due_count, 18) / nullIf(toDecimal256(v.vendor_past_due_count, 18), 0), 18) AS within_vendor_share,
 toInt64(row_number() OVER (ORDER BY a.past_due_count DESC,a.vendor_code,a.eta_value)) AS row_ordinal,
 toInt64(count() OVER ()) AS expected_row_count,now('America/Los_Angeles') AS queried_at_utc
 FROM aggregate_rows a INNER JOIN vendor_totals v ON a.country_code=v.country_code AND a.vendor_code=v.vendor_code CROSS JOIN totals t
```

完成条件：记录实际版本、耗时、行数或异常。
P0–P3快而P4失败，增加了完整SQL规划/重复计算/附加依赖/驱动取数的嫌疑，仍须计划或时序确认。
P4在独立数据库客户端成功，不能单独证明Atlas调用期限足够或其绑定正确。

### P5：映射视图条件探针

仅当P4报错/计划指向映射视图，且业务明细探针已成功、无未取消查询时执行。

```sql
SELECT country_code, vendor_no
FROM past_due.vendor_master_mapping_view
WHERE country_code = 'US'
LIMIT 1
```

完成条件：记录映射视图是否可读、耗时及错误。
P5成功不证明完整映射CTE便宜；本例没有组织过滤，映射分支是否执行由真实计划决定。

## 5. 条件取证：执行计划与实际超时配置

可用工具支持EXPLAIN时，只选择最早失败的一条业务探针，执行一次：

```text
EXPLAIN <该探针完整SELECT>
```

这是拼装规则，不是可直接提交的含占位符SQL。复制第4节选中的完整SELECT替换占位部分。
先使用工具和实际ClickHouse版本明确支持的EXPLAIN语法。不要使用EXPLAIN ANALYZE，
它在一些引擎会实际运行查询。EXPLAIN本身也可能消耗规划资源，应保持现有期限。
无法使用EXPLAIN时记录PLAN_UNAVAILABLE，不猜读行数、扫描次数或连接执行情况。

计划重点：视图展开/关联、WHERE与Vendor筛选是否下推、重复读取/聚合、Country总体计算，
及本例未启用的组织过滤是否仍触发映射读取。SQL文本只出现一次表名不代表执行只扫描一次。

通过已暴露的只读配置/状态能力或已授权配置文件，记录以下**实际生效值**，缺失写UNKNOWN：

| 层 | 要记录的配置/时序 |
|---|---|
| 请求入口/Atlas | deadline、是否贯穿子调用、收到请求到取消的时间、超时分类由哪层生成。 |
| 连接池 | 实际解析源、max pool、活跃/空闲/等待数、取连接期限及本次等待时间。 |
| JDBC | 驱动版本、connect/socket/read/query timeout、取消行为；URL/凭据脱敏。 |
| ClickHouse | 实际执行用户/版本、max_execution_time等生效限制、资源排队、开始/结束/取消时间。 |
| 返回链路 | SQL是否已完成、结果首行/取数/映射/序列化耗时。 |

120000只是本地配置声明；确认timeout_ms的语义，可能不覆盖所有上述层。
不得为了定位先统一调大超时、增加连接池或重启服务；先记录限制在哪里触发。
不能仅凭所有请求约6秒就断言存在6秒固定deadline。

若已有只读query log能力且能按本次真实query_id精确关联，读取对应记录的
耗时/读取行字节/异常/取消信息。日志字段按线上schema发现；本文不假设system表权限。
日志异步或不可见时，查不到记录不能证明SQL从未发送。

## 6. 结果判读与最小修复方向

| 证据组合 | 结论边界 | 后续动作 |
|---|---|---|
| 同Atlas执行链路P0失败 | 业务聚合前的基础路径已失败；尚不能只归为数据库故障。 | 查取连接、网络、请求deadline和返回链路时序。 |
| P0成功，P1超时 | 当前视图/筛选/关联读取路径有问题或成本高。 | 看视图计划、筛选下推、类型转换和源等待。 |
| P1成功，P2超时 | LIMIT读到一行可行，局部完整扫描/聚合未完成。 | 查实际读行/视图成本与过滤；不以LIMIT成功证明表很小。 |
| P2成功，P3超时 | 全国分母路径比局部路径更难完成；不证明具体哪种优化有效。 | 测总体计划，优化扫描/复用；保持原分母定义。 |
| P0–P3成功，P4超时 | 完整查询增加了成本或暴露其他依赖。 | 查CTE重复执行、规划、映射、驱动取数；保留指标语义。 |
| SQL服务器完成快，但同调用Atlas随后超时 | 返回/驱动/映射/期限有嫌疑。 | 按同query_id对齐执行结束和取数/取消时序。 |
| 外部客户端快，Atlas失败 | 两条链路有差异。 | 核对源/用户/设置/绑定和连接池；不直接判数据库正常。 |
| 所有适用探针都快 | 当前未复现该超时。 | 记录时刻/环境差异，不声称已经修复；再做一次经授权的实际业务调用验证。 |
| 仅EQL可用 | 本Agent没有能区分故障边界的源探针入口。 | 交付本文SQL与所缺源诊断能力，请平台提供受控只读入口。 |

任何修复应依据证据：优化数据读取和复用、不改变指标定义；或修正实际期限/连接设置。
超时值确实不符合业务SLO时再单独提出变更及回归计划，本轮不改配置。
局部counts成功不能用来替代全国share，也不能静默削减Code×Source等原能力。

## 7. 状态和事实口径

每步使用SUCCESS / EMPTY / TIMEOUT / DIAGNOSTIC_LIMIT / SOURCE_ERROR /
PERMISSION_DENIED / NOT_RUN / UNVERIFIED，并附原始信息。超时不是0行，0行不是PastDue数量0。
Schema发现只确认字段存在，不证明string枚举值或源SQL执行已验证。
本轮源SQL诊断不依赖旧handle；若工具返回handle，只读取该次真实响应提供的值，
核对请求/返回关联并按实际schema读取，关联不明时隔离依赖结果。
结果行/单元格截断时只作诊断，不认证业务全量。原始异常输出先脱敏再交付。

## 8. 最终交付模板

用中文回答，并附可访问的实际请求/原始响应记录或直接贴出脱敏证据。

**结论**：最早失败边界是什么；哪些原因已确认；哪些仍未知。

**执行环境**：路径A/B/C；实际工具；源解析/用户（脱敏）；环境/驱动版本；绕过的层。

| 探针 | 状态/未执行原因 | 客户端耗时 | DB耗时 | 取连接等待 | 行数 | query_id/错误 | 已终止？ |
|---|---|---|---|---|---|---|---|
| P0常量 | 实际值 | 实际值或UNKNOWN | 同左 | 同左 | 同左 | 同左 | 同左 |
| P1单行 | 实际值 | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 |
| P2局部聚合 | 实际值 | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 |
| P3全国分母 | 实际值 | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 |
| P4完整SQL | 实际值 | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 |
| P5映射/EXPLAIN | 实际值 | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 |

**配置对照**：本地timeout_ms=120000声明 vs 实际生效的各层期限；未知项明确写出。

**已确认事实**：每项引用对应探针/时序；**待验证判断**：说明需要哪条证据区分。

**下一步**：只提出最小修复或缺失取证动作。没有工具时明确缺少源诊断入口，
不能只重复“需要recent-id”或要求用户猜驱动参数。

**业务范围**：13 Vendor CURRENT ETA刷新仍未完成，除非另有完整实际业务查询证据。
诊断SQL通过、EXPLAIN通过或配置值已读取，均不等同业务验收通过。

## 文件验证记录

本文6条完整SELECT已在本地合成ClickHouse环境逐条执行通过；局部PastDue计数与
完整SQL的全国分母分别核对通过。该验证仅检查SQL可执行及样本语义，未连接线上
Atlas/JDBC，未验证线上性能。本文P4为生成时本地绑定快照，线上SQL仍须核对。

# CURRENT ETA数量查询：下一步实施与验收

## 当前决定

从反复EQL诊断转入有界的查询实现试验。历史、映射及单Vendor Alert已返回事实，
而完整ETA路径曾超时；全国分母/重复聚合是重点嫌疑，不是已确认根因。
本次新增SQL只是候选，生产图、Prompt、Skill、3.5.0发布包均未更改。

## 第一步：平台用现有受控入口验证候选SQL

执行人：拥有源诊断能力的平台/IT；业务数据面Agent目前没有该工具。
通过实际Atlas源绑定的同用户/驱动/期限执行下文SELECT，记录连接身份（脱敏）、
环境/版本、SQL、总耗时、数据库耗时、读行/字节（可见时）、原始异常和取消状态。
不同客户端/用户的结果单独标记，不能声称等同Atlas执行。
现有角色与访问规则继续生效；Country过滤不代替行权限。
并发1，执行一次，沿用现有期限。失败后不原样重试，不先调大超时。
无需recent-id，不需要把密码或Token发给Agent。

候选只计算13 Vendor CURRENT的ETA Code×Source桶PD/Open与Vendor总量。
筛选在聚合前；一条明细参与一个联合桶；空Code/Source规范为UNKNOWN。
它仍可能展开昂贵视图，CTE也不证明只扫描一次；性能必须在线测量。
这是原始用户范围的性能对照，不是新通用接口的最终设计。

```sql
-- Pilot only: CURRENT counts for one explicit US Vendor set.
-- Reference metric3.1.0: PD = is_pastdue=1; Open = detail-view row count.
-- No organization filters in this pilot. Existing deployment access rules still apply.
-- No country denominator, ratios, ranking, historical source or mapping join.
WITH buckets AS (
    SELECT
        country_code,
        toInt64(from_acct_no) AS vendor_code,
        min(vendor_name) AS vendor_name,
        if(empty(trim(ifNull(eta_code, ''))), 'UNKNOWN', upper(trim(eta_code))) AS eta_code,
        if(empty(trim(ifNull(source, ''))), 'UNKNOWN', trim(source)) AS eta_source,
        toInt64(count()) AS open_line_count,
        toInt64(countIf(toInt64(is_pastdue) = 1)) AS past_due_count
    FROM past_due.past_due_detail_view
    WHERE country_code = 'US'
      AND from_acct_no IS NOT NULL
      AND toInt64(from_acct_no) IN
          (64342,75256,64351,19215,65505,75257,22344,85058,71886,48378,65885,75691,72478)
    GROUP BY country_code, vendor_code, eta_code, eta_source
)
SELECT
    country_code, vendor_code, vendor_name, eta_code, eta_source,
    open_line_count, past_due_count,
    toInt64(sum(open_line_count) OVER (PARTITION BY country_code, vendor_code)) AS vendor_open_count,
    toInt64(sum(past_due_count) OVER (PARTITION BY country_code, vendor_code)) AS vendor_past_due_count,
    toInt64(count() OVER ()) AS expected_bucket_count,
    toInt64(row_number() OVER (ORDER BY country_code, vendor_code, eta_code, eta_source)) AS row_ordinal,
    now('America/Los_Angeles') AS queried_at_local
FROM buckets
ORDER BY row_ordinal
```

## 第二步：核对正确性与完整性

1. 返回范围仅US与所列13 Vendor，时间为真实CURRENT采集；供应商名称按数据保留。
2. ordinal唯一且覆盖1..expected_bucket_count，桶主键Country/Vendor/Code/Source唯一。
3. 桶PD/Open分别加总等于返回的Vendor PD/Open；PD<=Open，测量零与无行分开。
4. 返回0行或某Vendor缺失不能写成PastDue=0；先记录缺失和权限/源范围，查询没有
   发出独立覆盖控制记录，因此尚不能认证13 Vendor全部完成。
5. 分页/宿主裁剪须额外核实。此SQL不LIMIT，不等于工具必然完整返回。
6. 比较现有指标时须固定可用的同一数据版本；不同CURRENT执行时刻可能变化。
   没有版本时记录这一限制，不把差异自动当SQL错误。
7. 旧值639或Alert桶631都不是此次ETA桶必须匹配的固定答案。

本地已在合成ClickHouse上验证：与现有v3同范围联合桶PD/Open一致；Vendor总量、
规范化空桶、重复业务行、零PD、范围排除、ordinal/计数、空源均通过。
此证据不代表线上权限、计划、JDBC或性能通过。

## 第三步：根据试验决定改动位置

| 实际结果 | 后续 |
|---|---|
| 候选在Atlas同链路快且正确 | 将成本按所需指标隔离的实现推广为通用接口，并补齐控制/覆盖/分页。 |
| 候选在同链路仍超时 | 查看视图展开/筛选下推/源等待与执行期限；数量路径本身仍未证实有效。 |
| 外部客户端成功、Atlas失败 | 核对连接、用户、绑定、驱动和生效deadline；不直接改业务SQL。 |
| 返回类型/权限/字段错误 | 按真实异常修正对应实现或配置；保持原有授权及指标口径。 |

## 第四步：推广前的通用设计与文件范围

对外优先评估在既有接口内按请求所需测量选择执行计划；若平台的输出投影不能影响
绑定SQL，才考虑增量的CURRENT数量接口。两种方式都须有真实平台契约依据。
不为13个编号创建固定场景分支，不以用户措辞硬编码路由。

最低接口责任：Country、Vendor选择、需要的组织/桶范围；明确计数粒度；
CONTROL/FACT/显式Vendor覆盖；合法参数与无数据状态；源分页、保留完整性和漂移边界。
无组织条件可使用数量路径；有组织条件必须在源端正确执行组织限制，不能静默忽略。
新路径不得冒充已有字段（如全国Open/share），也不能删除旧接口的承诺输出。

预期修改范围（试验通过后）：

- 查询构建器与生成的atlas/charts/pastdue.edn：把数量、分母和完整分析成本隔离，保持指标语义。
- 必要的输入/输出契约与SQL回归：集合/全部/单个、零/无数据、组织限制、桶粒度、完整性及旧路径兼容。
- agent/SYSTEM_PROMPT.md与分析Skill相关引用：按所需指标选择支持的计划，不能把未部署能力当可用。
- 部署/API/验证说明与新版本发布产物：线上验证状态如实记录，保留旧发布包。

最终上线前，使用只提供数据面和新部署包的独立Agent，重新执行原13 Vendor问题，
核对能力发现、范围、每个Vendor覆盖、ETA桶与总量及最终交付；再回归日报/历史/
排名/组织/全国分母和HTML等原能力。源查询成功不等于Agent交付成功。

## 当前交付边界

已完成候选SQL及本地语义对照；尚未执行平台候选、修改正式图或发布上线。
业务Agent仅有现有EQL时不能直接执行本SQL，也不能把SQL传给execute_eql/analyze_result。
下一项外部依赖是平台代跑上面的候选并返回结果与耗时，或提供受控只读源诊断入口。

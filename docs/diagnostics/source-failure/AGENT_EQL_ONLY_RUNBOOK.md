# 仅有Atlas EQL时的对照排查：Agent单文件指南

## 本轮任务

当前Agent只有list_entities、list_attributes、execute_eql、analyze_result及list_mutations。
上一轮源SQL探针P0–P5均NOT_RUN，没有新增执行证据。第一轮合法旧接口/V2/V3均timeout。
本轮通过现有图路径做对照，不要求新增工具、凭据、发布或直连数据库。
这些不是SQL探针P0–P5的等价替代，不能测出连接池等待或数据库真实耗时。

请实际执行下文可用的查询并交付结果；没有权限/路径时记录准确阻塞。
本指南与源SQL指南是两种取证路径，不要求先完成P0–P5。
不修改原Prompt、Skill、EDN、部署或超时，不重做第一轮失败的A/B/C。

## 1. 确认能力与调用方式

复用同环境有效schema。缺少schema时先通过list_entities({})发现路径，
再按实际工具schema调用list_attributes，所需实体为：

```json
{"entities":["pastdue.country-monitor","pastdue.vendor-contribution","pastdue.vendor-master-mapping","pastdue.current-alert-type-query","pastdue.current-vendor-alert-type-count"]}
```

核对下文join和输入/输出字段；缺少某路径时跳过该项，不猜接口名。
所选三条路径在本地EDN都绑定逻辑源eta-clickhouse，但不证明线上解析相同。
list_attributes成功仅证明图schema可读，不证明源健康或string枚举受支持。

将每个eql代码块整体作为字符串Q，通过真实execute_eql的结构化参数调用：

```text
execute_eql({"query": Q, "retain_result": false})
```

Q是完整EQL，不是字母Q、路径或源SQL。若工具不支持false，按其真实schema使用true，记录差异。
顺序执行、并发1，每项一次。两项都明确超时后，E3记NOT_RUN，避免继续增加源负载；
此时只说明已测路径均失败，不声称所有路径不可用。明确权限拒绝时不更换身份绕过。
若宿主提供取消状态，前次仍运行时不发下一项；不可见则记取消状态UNKNOWN，
本轮仅维持这组最多3次的顺序对照，不扩展重试。

每次保留实际工具名、完整参数、环境/版本、调用ID、开始/结束/总耗时、原始响应、
错误category、行数与裁剪标志；源端耗时/是否取消未知则写UNKNOWN。
不同时间执行可能受负载变化影响，不能视作严格单变量实验。

## 2. E1：US历史快照路径

此路径从vendor_past_due_daily聚合，避开CURRENT明细和Vendor映射视图（依据本地绑定）。
用于判断至少一条历史源查询能否返回。它仍有聚合，不能代替SELECT 1健康检查。
返回的日期才是实际历史日期，不把结果当今天CURRENT。
E1只有实际返回快照日期、计数等源字段才能记为成功；仅回显entity中的US不算源查询成功。

```eql
{:entity {:pastdue.country-monitor/country-code "US"}
  :query [:pastdue.country-monitor/country-code
      :pastdue.country-monitor/latest-snapshot-date
      :pastdue.country-monitor/previous-snapshot-date
      :pastdue.country-monitor/latest-past-due-count
      :pastdue.country-monitor/latest-open-count
      :pastdue.country-monitor/has-comparable-snapshots]}
```

## 3. E2：US64342映射路径

此路径只读vendor_master_mapping_view（依据本地绑定），不查当前明细或全国分母。
仅请求国家、Vendor、部门与时间；不输出个人姓名/联系方式。
映射为空不代表业务明细为空，也不代表用户无权查看。

```eql
{:entity {:pastdue.vendor-contribution/country-code "US"
    :pastdue.vendor-contribution/vendor-code 64342}
  :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/country-code
      :pastdue.vendor-master-mapping/vendor-code
      :pastdue.vendor-master-mapping/department-type
      :pastdue.vendor-master-mapping/queried-at-utc]}]}
```

## 4. E3：US64342当前Alert分组计数

仅在E1/E2至少一项成功时执行；如果两项都超时，停止本轮源查询。
此路径读当前明细并关联映射，按alert_type计数，不计算全国分母（依据本地绑定）。
它仍可能执行昂贵的视图/映射扫描，不保证轻量。
line-count是该alert_type的明细行数；不能把所有Alert桶当PastDue，不能据此推出ETA Code/Source。

```eql
{:entity {:pastdue.current-alert-type-query/country-code "US"
    :pastdue.current-alert-type-query/vendor-code-filter "64342"
    :pastdue.current-alert-type-query/department-type-filter ""
    :pastdue.current-alert-type-query/master-primary-filter ""
    :pastdue.current-alert-type-query/master-manager-filter ""}
  :query [{:pastdue.current-alert-type-query/matching-counts [:pastdue.current-vendor-alert-type-count/country-code
      :pastdue.current-vendor-alert-type-count/vendor-code
      :pastdue.current-vendor-alert-type-count/vendor-name
      :pastdue.current-vendor-alert-type-count/alert-type
      :pastdue.current-vendor-alert-type-count/line-count
      :pastdue.current-vendor-alert-type-count/queried-at-utc]}]}
```

## 5. handle与空结果处理

如果execute直接返回事实，先核对US、64342（E2/E3）、实际日期/采集时间与属性。
如果只返回handle，H必须来自该次真实execute响应。
若工具支持，先调用analyze_result({"result_handle":H,"analysis_version":2})发现列名，
随后用同H调用analyze_result({"result_handle":H,"sql":"SELECT * FROM data","analysis_version":2})。
只保留result_handle一个选择器；query/result_handles键缺省。
读响应的handle若与H不一致，记ASSOCIATION_ERROR并隔离该次事实；来源无法确认记UNVERIFIED。

图返回错误/timeout、集合为空、未知源完整性、读取flatten=0是不同现象。
空集合不证明源无数据，timeout不是0行，不能解释为PastDue=0。
E1是单国家历史控制；E2/E3集合可能被resolver/宿主截断，本轮仅需路径取证。
有事实但完整性未知，记SUCCESS_PARTIAL，不认证总体数量或13 Vendor刷新完成。

## 6. 如何解释这组结果

| 结果组合 | 允许的判断 | 仍不能确定 |
|---|---|---|
| E1成功，E2成功，E3超时 | 历史和映射路径可用；当前明细/其关联聚合路径值得优先查。 | 具体是明细视图、JOIN、类型、资源还是时间限制。 |
| E1成功，E2超时，E3超时 | 历史可用，映射及依赖映射的当前路径失败。 | 映射是否共同根因；CURRENT也可能独立失败。 |
| E1超时，E2成功，E3成功 | 存在当前可用路径，不能笼统认定全源损坏。 | 历史失败的具体原因。 |
| E3成功，而此前A/B/C超时 | 至少一条无全国分母的当前聚合可用。 | 全国分母不是唯一差异，不能直接定为根因。 |
| E1/E2都超时 | 两类已测路径失败，E3不执行；共同执行链路有嫌疑。 | 不能证明数据库离线或固定6秒期限。 |
| 全部适用项成功 | 当前时点至少这些路径可用。 | 不证明原超时已修复或完整业务已通过。 |
| 只有空集合 | 尚未获得可验证事实。 | 不能从空集合断言源健康或故障。 |

根据本地绑定指出查询差异是一条线索，不等于线上SQL/依赖已验证。
成功返回至少说明对应实际请求能完成某条源路径，但不证明相同池、角色或其他源都正常。
如果仍全部失败，不继续堆叠Agent规则或原样重试；将证据交给平台执行源SQL探针。
平台只需提供一个受控只读诊断入口或代跑探针；无需向业务Agent开放任意SQL。

## 7. 最终交付

1. 一句话结论：新增了哪些真实证据，最窄可确认失败范围是什么。
2. 执行表：

| 项 | 实际能力/环境 | 状态 | 客户端耗时 | 行数/裁剪 | 原始错误/日期 | 来源/关联 |
|---|---|---|---|---|---|---|
| E1历史 | 实际值 | SUCCESS/PARTIAL/EMPTY/TIMEOUT/NOT_RUN等 | 实际值 | 实际值 | 实际值 | 实际值 |
| E2映射 | 实际值 | 同上 | 实际值 | 实际值 | 实际值 | 实际值 |
| E3当前Alert | 实际值 | 同上 | 实际值 | 实际值 | 实际值 | 实际值 |

3. 已确认与待验证原因分开，每条引用对应响应；没有工具就写具体能力缺口。
4. 下一步最小取证动作；附实际请求和脱敏原始响应/可访问文件。
5. 说明13 Vendor CURRENT ETA仍未交付，本轮Alert/映射/历史结果不冒充ETA刷新。

完成条件是适用步骤有证据或准确阻塞，不要求凭现有工具强行确定底层根因。

## 文件验证范围

三条EQL已按本地EDN核对必填输入与输出属性，关联源SQL在本地合成数据执行验证。
未连接线上Atlas，不证明线上部署、延迟、权限或业务数据正确。

# current-v2 Code49：3.7.1 / revision10 定点修复

## 现场确认的两个原因

1. 新current-v2：ClickHouse22.8.21.38 Code49，`Different order of columns in UNION subquery`。
   resolver=pastdue_v3_current_v2_query_rows；source=past-due-ck；stage=query；SQLState07000。
2. 旧current-analysis：ClickHouse Code159，elapsed5.000649273秒、maximum5，TIMEOUT_EXCEEDED。
   resolver=pastdue_v3_current_analysis_query_rows；同一source。此路径不是可用兜底。

这两个请求报错不同。先修复新接口的确定兼容缺陷；不把它误判为超时，也不统一放宽源超时。

## 原因及最小改动

current-v2的raw CTE通过UNION ALL合并实际明细和补行：补行确保每个请求Vendor都有覆盖状态，
缺失Vendor和零逾期可区分，并确保始终有CONTROL。
原分支将arrayJoin(...)与3个未命名的空字符串、2个未命名的toInt64(0)、布尔常量放在同一SELECT。
22.8执行UNION所需列裁剪时，arrayJoin输出出现在所需列清单，却没有出现在实际子查询输出头，
触发Code49。不是输入13家太多，不是实际业务列顺序混乱，也不是数据库缺列。

已用官方ClickHouse22.8.21.38 macOS arm64 local、合成明细重放原绑定SQL并追加Atlas默认
ORDER BY/LIMIT，得到与现场相同Code49。新引擎chdb26.11无法捕获这个旧版错误。

修改仅为：arrayJoin放进内层子查询命名seed_vendor_code；外层逐列显式命名
requested/vendor_code/vendor_name/eta_code/eta_source/op/pd/is_seed/org_match。
输入/输出字段、公式、Country分母、组织过滤、requested集合、补行数量、覆盖、排序和分页均不变。
仍保留一次逻辑detail读取，不添加接口或表，不变更Atlas实现/源/timeout/权限。

对比3.7.0，execution中只有`pastdue_v3_current_v2_query_rows-sql`的query变化；
其余42条resolver/binding及所有source、selection完全相同。publication为3.7.1/revision10。
Prompt和Skill继续使用3.7.0，无须再次覆盖。

## 更新什么

- 权威graph：[pastdue.edn](../../../atlas/charts/pastdue.edn)。
- 图补丁包：[pastdue-graph-v3.7.1-rev10.zip](../../../releases/pastdue-graph-v3.7.1-rev10.zip)。
- 对照SQL：[修正绑定SQL](current-env17/CURRENT_V2_REV10_BINDING.sql)。它是给图维护者核对的，
  不能把这个SQL文件发给普通Atlas EQL入口。

若线上图有批准的其他改动，保留现有graph/source ID与权限配置，在测试草稿仅替换上述binding.query；
不要用模板中的占位ID覆盖实际配置。检查resolver默认排序仍保留，SQL最外层末尾没有ORDER BY。
测试草稿通过后发布，并确认Agent使用该版本。env/version由平台分配，不等于revision10。
老3.7.0发布ZIP不覆盖；本轮只提供graph热修，不声称整个graph所有接口已在22.8通过。

## 发布后只跑两条验收EQL

1. [CISCO完整查询](current-env17/01-cisco-current-v2.eql)：原失败请求不变，US/64956、page100。
2. 第一步成功并可读后，再跑[13家完整批量查询](current-env17/03-batch-current-v2.eql)。

成功必须实际返回可读业务结果：CONTROL OK、覆盖状态准确、页内count与行数一致。
若has-more，按next-after-ordinal续页，不能只看第一页就声称完整。
CISCO的Code×Source Open/PD之和须等于其Vendor指标，批量每个输入都有覆盖，零PD与无数据分开。
现有3.7.0 typed retained读仍可用，只绑定本次成功响应的实际handle。
如果修后出现Code159，说明已跨过Code49、暴露了生产数据成本/预算问题；保存实际错误，
按真实SQL与生效source策略定位，不能以本地耗时承诺线上低于5秒。

## 验证及仍存在的边界

- 原SQL在同版22.8原生二进制复现Code49；改后通过7项22.8测试：单Vendor、13家覆盖、
  三类分母、组织过滤、重复映射、缺失/零PD、空值规范化、非法输入、分页/fingerprint。
- 现代引擎合成测试及既有回归另见offline receipt，不能代替真实账号/JDBC/生产体量/RLS验收。
- 22.8测试为临时目录的local执行，没有启动服务，没有业务连接和凭证，没有修改源表。
- 同版测试发现现有explore-v3的条件JOIN可报Code403 INVALID_JOIN_ON_EXPRESSION；
  因此它不是已验证可用的恢复路径。线上env17 explore-v3未提供cause，不能将本地Code403
  自动写成那两次的确定现场原因。此独立缺陷本轮未修改，以免扩大graph热修范围。
- 原current-analysis的5秒限制未改，已知失败不继续重试；不能保证其它未经验收的旧路径。

复现/回归命令（开发者，本地已有可信官方二进制）：

```sh
CLICKHOUSE22_BIN=/path/to/clickhouse python3 -B scripts/test_clickhouse22_current.py
```

脚本严格检查version22.8.21.38；未设置变量时明确skip，不算兼容性通过。二进制不在发布包中。

# F5 identity failure：3.7.2 / revision11

## 记录证明了什么

用户问 `64342 F5 NETWORKS, INC past due 数`。这轮读取Skill3.7.0和相关参考，
执行一次完整list_entities，再执行3次vendor-identity-query；没有current-v2执行，
也没有成功结果handle或业务数据读取。env/version=18，三次均source execution failed。

| 请求 | 名称 | 返回字段 | recent-id |
|---|---|---|---|
| 1 | F5 NETWORKS, INC | 完整identity字段 | c37dea20-54a8-468e-8b55-927f972ec847 |
| 2 | 空数组，仅code | 完整identity字段 | 62b15608-c7ae-425e-82e2-e4e787ca8c94 |
| 3 | F5 NETWORKS, INC | 缩为7字段 | e245ca4a-1392-4cfa-a74d-d5a3cdef0425 |

三次均US/CA、code64342、cursor0、page100；JSON字符串编码正确。Country未提供时先解析
Country+code合理，不能简单把所有名称查询删掉。去名称/减字段没有输入或结果大小错误支持，
没有得到新事实。最终回答把数量误写成金额，这是独立的解释错误。未捏造数字这一点正确。

## 本地可复现的缺陷及证据边界

用官方ClickHouse22.8.21.38 local、临时合成表重放三个请求的绑定SQL，并追加Atlas默认
ORDER BY/LIMIT，均出现Code49 `Different order of columns in UNION subquery`。
问题位于identity的typed CTE CONTROL分支：未命名常量与scalar subquery混合投影，
22.8的UNION所需列头与实际子查询输出不一致。与上次current-v2的arrayJoin补行缺陷
属于不同SQL位置；上次修改没有覆盖这里。现代引擎通过不代表22.8兼容。

单变量探针：只显式命名CONTROL列可通过；只将scalar读取换成FROM input_control也可通过；
最终同时明确命名并从单行input_control读取，仍为一个CONTROL，字段/值/排序/分页均保留。
合成数据不能证明线上cause；线上记录没有底层错误栈，仍需发布后原请求验收。

回归命令（开发用已有可信二进制，不下载、不连业务库）：

```sh
CLICKHOUSE22_BIN=/path/to/clickhouse python3 -B scripts/test_clickhouse22_live.py
CLICKHOUSE22_BIN=/path/to/clickhouse python3 -B scripts/test_clickhouse22_current.py
```

第一条覆盖overview、identity、原F5请求到current-v2的数量读取、13家、名称歧义、非法输入、
零值/缺失、翻页/fingerprint和typed retained读；第二条保留先前current-v2回归。
缺少二进制时skip明确表示兼容性未验证。合成F5数字仅用于断言，不是该Vendor真实值。

## 修改范围

- EDN3.7.2/revision11：相对rev10只有identity binding.query与publication变化；
  其余42条binding、全部43条resolver、sources、selection、vocabulary不变。
- Prompt/Skill3.7.2：数量按count/行回答，包括失败消息；count-only只读必要指标和覆盖，
  分析问题继续支持Open/PD/ratio及Code/Source。明确code+name配对取交集，不扩成OR集合。
- 恢复需要错误证据支持；source通用错误不靠删正确名称、缩字段反复执行。
  未把全部未知Country问题改成US，也没有禁止名称、批量、自由提问或既有report。
- 没有修改Atlas代码、数据库、source策略、超时或权限。旧current-analysis5s超时和
  explore-v3原生22.8条件JOIN缺陷仍存在，不能宣称已修好或作为已验证兜底。

## 用户需要更新什么

1. Atlas测试草稿更新 `atlas/charts/pastdue.edn`；已有批准的定制配置应保留，只替换identity
   binding.query。保留rev10 current-v2修复、实际graph/source ID和权限，不能覆盖成占位ID。
2. 更新安装的pastdue-analysis Skill（包含references/live-current.md）。
3. Agent System Prompt换成agent/SYSTEM_PROMPT.md的3.7.2版本。

完整包 `releases/pastdue-agent-v3.7.2.zip`；Skill包 `releases/pastdue-analysis-skill-v3.7.2.zip`。
历史3.7.0与graph3.7.1包保持不变。没有自动发布或push。

## 线上只验收原始路径

先执行同目录identity-env18/01-f5-identity.eql。确认CONTROL OK，所有token/candidate与分页
完整；code与名称的Country+code交集才是该显式配对的目标。若Country唯一，再用
02-f5-current-v2-template.eql，将country-code改为实际解析值；模板US不是实际归属结论。
读成功响应的实际handle，检查coverage及指标，报告真实Past Due数量与CURRENT capture。
若候选仍歧义，展示候选并要求消歧；没有可读匹配不代表0。最后重跑用户原话。

本地验证结果见同目录identity-env18/VALIDATION.json。生产JDBC、真实数据量下5s预算、权限、
线上Agent行为仍NOT_RUN；不能承诺此次一定给出真实数量，也不能把本地耗时当线上SLA。

# T1–T4：ClickHouse 5秒执行限制已确认

## 用户提供的真实证据

| 请求 | 粒度 | 分母 | 数据库报告elapsed秒 |
|---|---|---|---:|
| T1 | VENDOR | FILTERED_POPULATION | 5.000947135 |
| T2 | VENDOR_CODE | FILTERED_POPULATION | 5.040848872 |
| T3 | VENDOR | COUNTRY_ALL | 5.060546012 |
| T4 | VENDOR_CODE | COUNTRY_ALL | 5.000826215 |

共同信息：resolver=pastdue_v3_current_analysis_query_rows；source-ref=past-due-ck；
stage=:query；ClickHouse22.8.21.38；Code159 TIMEOUT_EXCEEDED；maximum:5；SQLState07000。

## 已确认与未确认

已确认：查询到达ClickHouse执行，数据库报告执行时间超过5秒上限，Atlas将异常归为timeout。
这是本轮四次失败的直接原因，不是仅凭界面约6秒作出的推测。当前失败发生在源查询阶段，
无需用保留handle或Markdown输出解释这四次失败。

未确认：5秒限制由用户profile、会话/查询设置、JDBC或平台注入的哪一处产生；
完整SQL的主要耗时点；旧SQL在数据库客户端同用户/设置下的耗时；
新数量SQL通过Atlas的实际表现。错误强烈指向执行时间限制，但尚未读取max_execution_time生效值。
四个参数组合都超时，不能据此认定全国分母或ETA Code分组是唯一原因。

用户另已在数据库客户端运行精简数量SQL，报告约1秒；这是不同SQL和可能不同会话的证据。
实际ClickHouse版本22.8比本地合成引擎旧，后续SQL须在22.8验证；本地通过不保证旧引擎兼容/性能。

本地timeout_ms=120000不能覆盖数据库5秒执行限制，也未确认它是线上实际配置。
线上source-ref=past-due-ck是当前执行证据；本地逻辑槽eta-clickhouse及配置清单键
eta-clickhouse-prod不能替代真实源映射。它们名称不同本身不证明配置错误。

## 下一步：读设置，比较完整绑定SQL

以下两条SELECT可在数据库客户端执行，但只证明该客户端会话设置。
要确认Atlas限制来源，需要平台核对它实际使用的用户/profile/查询设置；
数据库客户端同用户也可能有不同会话或语句设置。

```sql
SELECT version() AS clickhouse_version, currentUser() AS execution_user;
```

```sql
SELECT name, value, changed
FROM system.settings
WHERE name IN ('max_execution_time', 'timeout_overflow_mode', 'max_estimated_execution_time')
ORDER BY name;
```

这两条源SQL不是EQL，不提交给execute_eql/analyze_result。只读、不修改限制。
若希望通过Atlas观察，只能使用平台已有源诊断能力或核对源配置；现有业务EQL不能凭空读system.settings。
没有这样的能力就交由平台核对，不再堆叠参数试验。

然后在现有5秒限制下执行T1的完整绑定SQL，对照已经约1秒的精简SQL。
优先获取线上实际绑定与参数；若使用本地SQL快照，标记版本差异未核对。
完整SQL也超时则有查询成本问题的直接证据；客户端完整SQL快而Atlas失败则核对
两者用户/设置/参数/SQL版本/负载与驱动差异，不能直接断言JDBC故障。

## EDN交付决定

暂不把counts pilot作为最终修复发布。先取得完整绑定SQL与同条件执行结果，
优先评估保持既有接口语义的SQL优化，再决定需要怎样的增量数量接口。
依据证据优化扫描与复用，保证COUNTRY_ALL仍为全国分母，不通过缩小总体伪造性能改善。
只有业务SLO确实要求更长执行时，才提出独立的期限配置变更；不统一调大所有超时。
正式改动须通过本地兼容回归、ClickHouse22.8实际SQL及Atlas EQL验证。

当前精简数量候选/试点EDN仅为未线上验证候选，不能承诺1秒或根因完全消除。

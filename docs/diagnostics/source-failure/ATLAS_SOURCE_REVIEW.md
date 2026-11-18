# Atlas 源码审阅：对 PastDue graph 修复的帮助

## 结论与证据边界

这份源码很有帮助：它给出了能解释 Atlas 路径 5 秒超时的默认策略传播路径，也明确了下一版 EDN 真正能使用的配置契约。当前应先确认线上版本和数据源有效策略，再决定 graph 的最终改动。

审阅对象：`atlas/open-atlas-studio`，commit `c2796d0714291f928de359bfd54b52aa93f080da`。以下路径均相对此目录，行号对应该提交。

这是源码审阅，不是线上复现。当前环境没有 `clojure` / `bb`，未运行 Atlas Clojure 测试；没有修改 Atlas 实现、业务 EDN、线上配置或发布状态。因此不能宣布线上根因已最终确认。

## 1. 可以解释 5 秒的具体代码路径

用户的 T1–T4 已证明：Atlas 调用的 ClickHouse 语句执行遇到 `maximum: 5`，并返回 Code 159；数据库客户端的 `max_execution_time=1200` 只能说明该客户端会话的设置。二者可以同时成立。

本提交存在以下默认值传播路径，前提是执行走 semantic runtime，且实际注册源没有显式设置 `:resilience :timeout_ms`：

| 环节 | 实际代码行为 | 源码位置 |
|---|---|---|
| 共享平台默认 | `:timeout_ms 5000` | `modules/common/connector-spi/src/open/atlas/common/connector/spi/impl/policy.clj:27` |
| runtime 合并 | `spi/effective-policy [declared] capacity` 没有传入 SQL 类型默认；把合并结果写回连接的 `:resilience` | `modules/runtime/env/src/open/atlas/runtime/env/impl/semantic.clj:114` |
| 编译传递 | 修改后的 connections 进入 compiler，再成为 model 的 sources | 同上 `:159`；`modules/common/engine/src/open/atlas/common/semantic_compiler.clj:231` |
| 最终 source config | 注册源配置与绑定操作块合并 | `modules/common/engine/src/open/atlas/common/engine/impl/compile.clj:316` |
| SQL 取超时 | 读取 `[:resilience :timeout_ms]`，无值才使用 SQL 的 30000 ms 默认 | `modules/common/connector-sql/src/open/atlas/common/connector/sql/impl/exec.clj:19`、`:29` |
| JDBC 执行 | 转为整秒，然后传入 prepared statement 的 `:timeout`，并设置取消计时器 | 同上 `:81`、`:326`；`modules/common/connector-sql/src/open/atlas/common/connector/sql/impl/run.clj:98` |

简化为：

```text
注册源未配置 timeout
  → runtime 用共享默认值生成 resilience.timeout_ms = 5000
  → SQL 收到已经填充的 resilience
  → SQL 自身 30000 ms 默认值不再生效
  → JDBC 查询超时为 5 秒（root 剩余预算还可进一步收紧）
```

SQL 的 `effective-policy` 也按“SQL 默认、source resilience、binding policy”的顺序合并，因此已填入的 5000 会覆盖 30000，见 `exec.clj:45–58`。

这是一个高置信的默认值装配问题候选，不能仅凭源码断言线上一定走到了这里。线上显式配置 5000、JDBC/会话设置、root 剩余预算等仍需区分。ClickHouse 驱动是否把 JDBC timeout 转成 `max_execution_time`，也应通过实际驱动版本和查询记录确认。

现有测试的缺口：

- SQL 单元测试断言连接器自身默认 30000，但输入的 resilience 没有经过 runtime 填充：`modules/common/connector-sql/test/open/atlas/common/connector/sql/failure_test.clj:131`。
- runtime 集成测试验证了显式 `:timeout_ms 12000`，没有验证“不配置 timeout 时 SQL 应使用哪个默认值”：`modules/runtime/env/test/open/atlas/runtime/env/semantic_package_test.clj:193`。

## 2. 对照开发者说明：本提交实际契约

| 项目 | 开发者说明 | 本提交源码 |
|---|---|---|
| EQL 默认执行预算 | 30 秒 | 一致；root graph 可声明 1–900000 ms |
| EQL 默认结果预算 | 1 MB | engine 默认 262144 bytes，即 256 KiB；宿主可覆盖，线上有效值待查 |
| graph/query-budget | execution-ms、result-bytes | 封闭 schema 仅接受 execution-ms，不能在这里添加 result-bytes |
| SQL 默认超时 | 30 秒 | 连接器本身确为 30 秒，但 runtime 提前填入 5000 的路径会覆盖它 |
| source 策略只能收紧 | 所有上限只能收紧 | 此处 capacity 的 timeout_ms 直接覆盖；收紧校验主要约束重试语义等，不能把它概括为 timeout 只能小于 5000 |
| DuckDB 环境变量 | SEMALITH_ANALYSIS_* | 本提交使用 ATLAS_ANALYSIS_TIMEOUT_MS / ATLAS_ANALYSIS_DEADLINE_MS，默认 5 秒 / 30 秒 |

对应依据：

- engine 默认：`modules/common/engine/src/open/atlas/common/engine/impl/limits.clj:12`。
- 宿主结果预算覆盖：`modules/common/engine/src/open/atlas/common/engine.clj:578`。
- graph schema：`modules/common/dialect/src/open/atlas/common/semantic_contract.clj:78`。
- root budget 只传播 execution-ms：`modules/runtime/env/src/open/atlas/runtime/env/impl/semantic.clj:16`。
- capacity 合并：`modules/common/connector-spi/src/open/atlas/common/connector/spi/impl/policy.clj:79`。
- DuckDB：`modules/server/mcp/src/open/atlas/server/mcp/impl/duck.clj:65`。

这些差异可能来自版本或分支变化。应先对齐部署提交，不能拿这份源码的默认值代替线上有效配置。DuckDB 的 5 秒是另一条执行路径，不能用来直接解释现有 SQL source 的异常。

## 3. 对 graph 优化方案的直接影响

### 超时配置要放在正确层

SQL 执行代码读取的是注册源连接配置中的嵌套结构：

```clojure
;; 仅表示注册源配置的相关片段，不是可直接导入的完整 graph EDN。
{:resilience {:timeout_ms 30000}}
```

SQL source schema 不接受顶层 `:timeout_ms`；运行时也不从顶层读取。依据：`modules/common/connector-sql/src/open/atlas/common/connector/sql/impl/validate.clj:26`、`:44`、`:80`。

graph 的 source/binding policy 属于语义配置，不能把 timeout 随意塞进去；SQL binding implementation 仅接受 `:query :batch :policy`，见 `modules/common/connector-sql/src/open/atlas/common/connector/sql.clj:55`。单独放宽 root graph budget 不能覆盖仍然存在的 source 5 秒限制。

### EQL 少选字段不保证减少绑定 SQL 成本

`modules/common/connector-sql/src/open/atlas/common/connector/sql/impl/narrow.clj:1` 明确只追加过滤、排序、分页，不重写基础 SQL 的 FROM/SELECT。因此只把 EQL 返回字段减到 count，不能假设旧 SQL 中复杂的聚合和扫描自动消失。优化应落到真正执行的 binding SQL，并用真实语句验证。

同文件 `:56` 的 list limit 会夹取到声明上限。这与 engine 的“超限拒绝”是两层机制：查询成功不能自动证明业务集合完整。现有完整性、分页、Vendor 覆盖与分母校验应保留。

### Builder 能提供实际语句回执

`modules/studio/tools/src/open/atlas/studio/tools.clj:172` 的 Builder 查询挂载会标记 `:draft true` 并收集 `:statements`。SQL trace 包含实际 SQL、单独的绑定值、耗时、resolver、attempt 和 outcome，见 `modules/common/connector-sql/src/open/atlas/common/connector/sql/impl/exec.clj:289`。

这是 Builder 草稿查询能力；普通 data-plane 查询不因此拥有语句跟踪权限。草稿与已发布 env14 也必须区分，不能混作同一版本。具备对应 Builder 挂载时，可直接使用已有回执能力，无需要求 agent 在 data-plane 执行原始 SQL。

## 4. 建议执行顺序及验收

1. 对齐线上 Atlas 提交、JDBC 驱动版本，确认 env14 的逻辑 source 绑定到了实际注册源 `past-due-ck`。只读取 timeout 等脱敏字段，不输出连接串和凭证。
2. 读取该注册源声明的 resilience 以及编译后的 effective source policy。当前编译返回值已有 `:source-policies` / `:binding-policies`（runtime semantic.clj:168、:286），但不能假设普通 EQL 已暴露它们；由平台现有诊断面读取或开发者核对。
3. 在草稿/测试环境固定原 T1 请求和 SQL，只改变该源的显式 timeout，例如设为 30000，保持 root 足够预算。对比 before/after 的有效策略、JDBC timeout、SQL/参数和错误；这是区分策略问题与 SQL 成本问题的最小实验。共享该注册源的 graph 也会受影响，应先枚举消费者；需要隔离时使用测试源实例。
4. 平台若确认默认值装配问题，统一修正“平台默认 → 连接器类型默认 → 源声明/绑定语义 → 部署 capacity”的装配边界。可选择让 runtime 取得类型默认，或避免提前实体化类型未知的 timeout；依据平台接口设计选择。不要简单把共享 5000 全局改成 30000。
5. 增加穿过 semantic runtime 和 SQL connector 的回归测试：未配 timeout 时 SQL 默认、显式源覆盖、root 剩余预算收紧、API 默认保持预期、包编译与草稿编译一致。
6. 获得平台策略证据后，对现有 graph 的实际 binding SQL 做性能优化。保持 CURRENT/历史、ETA Code/Source、COUNTRY_ALL/FILTERED_POPULATION、排名与明细原有语义。counts pilot 继续作为未线上验证的候选，不能直接替代完整能力。
7. 最终 EDN 经该版本真实契约校验、ClickHouse 22.8 执行验证、Atlas EQL T1–T4 回归及既有能力回归后，再交付发布版本。客户端 5.6 秒是端到端观测值，不能写成数据库执行时间。

当前已完成：源码调用链和契约核对。尚未完成：线上有效策略读取、真实运行复现、Atlas 测试和最终 EDN 验收。

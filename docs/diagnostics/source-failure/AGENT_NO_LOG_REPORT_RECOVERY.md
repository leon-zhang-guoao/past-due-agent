> 后续进展：现场已提供ClickHouse Code62，确认双重ORDER BY。请先按 [revision8修复与验证](REPORT_SORT_REV8_FIX.md) 操作；下文保留为修复前排查记录。

# 无后台日志时：PastDue 报告失败排查

## 给执行 agent 的任务

用户拿不到 recent-id 详情。不要再要求 recent-id、后台日志、数据库权限或原始 SQL。使用当前已连接的 Atlas 工具执行下面的只读 EQL 对照，保留原始响应并交付判定。不要修改 graph、源设置或 Atlas 代码。

此前 env15 的 US/CA report-v2 都返回通用 source execution failed；缺少 ClickHouse cause。目标是用现有能力缩小故障范围，必要时验证可用旧路径，不把对照实验冒充根因确认或完整报告。

## 1. 先做 US 两个请求

使用实际已提供的 execute_eql 工具，顺序执行 A、B，每个仅一次；不要并行、不要自动按 Vendor 重试。请求对象是 `{"query":"<完整EQL>","retain_result":true}`，只带实际工具支持的字段。日期固定为本次故障的2026-11-16→2026-11-17，是故障重放，不代表今天或最新快照。

A、B 的业务选择一致：US、同一P/T、WEEKDAYS、正向贡献Top10。A用旧report，B用新增report-v2并缩小投影/页长。对照改变了查询路径，不能据此直接推断某一个SQL语法或5秒限制；缩小页长不减少核心聚合工作。

### A：原有报告接口

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

### B：新增报告接口

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

## 2. 成功后读真实结果

execute 成功只证明该次查询执行并保留了结果，不能直接当作业务事实成功。复制该次响应的真实 result_handle，再调用工具实际支持的 analyze_result：

```json
{"result_handle":"<刚才这次execute返回的handle>","sql":"SELECT * FROM data ORDER BY row_ordinal","analysis_version":2}
```

只有工具schema要求时才补 input_row_limit=20000；0是非法值。其余选择器query/result_handles省略（若宿主强制填入则记录真实对象和返回，不假设Skill可修正宿主）。查看rows和cells截断状态，核对Country、P/T、selection-mode、scope-status、env/version及capture。

A的scope-status必须为OK才说明旧接口能提供该范围的事实。B最多只返回控制行和一条非控制行；CONTROL/COUNTRY_CONTROL不是完整报告，查看scope-status、expected-record-count和ordinal即可，禁止用这两行编造Vendor分析。

handle无法关联、execute展示与后续读取不同或旧capture异常复用：标记ASSOCIATION_UNVERIFIED，保留原始调用记录，不猜handle、不靠数值合理修补。该问题与report-v2执行失败分开报告。

## 3. 根据真实结果决定下一步

| A旧接口 | B新接口 | 下一步 |
|---|---|---|
| 可读且控制OK | source失败 | 已把差异定位到新增执行路径；旧report可作为当前正向Top10范围的候选恢复路径。停止US新接口重复调用；按需用C、D验证CA。仍不能断言新SQL具体错误。 |
| source失败 | source失败 | 不只是新接口的独立证据；停止重复查询，报告共享执行路径仍失败。保留已正确绑定的独立Country事实。 |
| 可读且控制OK | 可读且控制OK | 新接口的核心查询可执行；必要时仅重放一次下方E的原完整投影，区分最小/完整输出路径。 |
| source失败 | 可读且控制OK | 新接口至少最小范围可用；不要回退旧接口。需要完整报告时按新接口契约取完整投影和全部源页。 |
| 任一路无效日期/分母/选择状态 | 任意 | 属于业务范围或数据状态拒绝，区别于source异常；保留原故障重放结果，不悄悄改日期。若另外要看最新报告，单独发现实际有效P/T。 |
| 任何读取关联异常 | 任意 | 对该结果隔离，标记读取关联未验证；不能用未知handle继续报告。 |

没有cause并不妨碍这个范围定位，但不能把“新失败旧成功”直接解释为SQL语法错误。若出现新环境版本，将其写进表格，避免混作env15原样复现。

## 4. CA仅在需要确认其可恢复路径时执行

同样串行，每个一次。US新旧都失败时不必为了凑表继续执行CA；明确CA本轮未重测。

### C：CA旧接口

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

### D：CA新接口

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

## 5. E仅在US新最小请求成功且需要区分完整投影时执行

这是此前失败的原始输入/投影，一次即可。若它仍source失败，记录“最小成功、完整失败”；字段转换、结果字节、执行计划差异仍待区分，不能自动归为结果太大。若明确返回result-bytes超限，才按预算作一次更小页的修正；若仍通用source失败则停止，不循环尝试页长。

```clojure
{:entity {:pastdue.report-v2-query/country-code "US"
    :pastdue.report-v2-query/target-snapshot-date "2026-11-17"
    :pastdue.report-v2-query/previous-snapshot-date "2026-11-16"
    :pastdue.report-v2-query/calendar-mode "WEEKDAYS"
    :pastdue.report-v2-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.report-v2-query/top-n 10
    :pastdue.report-v2-query/vendor-codes-json "[]"
    :pastdue.report-v2-query/page-after-ordinal 0
    :pastdue.report-v2-query/page-size 50}
  :query [{:pastdue.report-v2-query/rows [:pastdue.report-v2-fact/scope-key :pastdue.report-v2-fact/record-type :pastdue.report-v2-fact/country-code :pastdue.report-v2-fact/vendor-code :pastdue.report-v2-fact/vendor-name :pastdue.report-v2-fact/target-snapshot-date :pastdue.report-v2-fact/previous-snapshot-date :pastdue.report-v2-fact/calendar-mode :pastdue.report-v2-fact/selection-mode :pastdue.report-v2-fact/metric-version :pastdue.report-v2-fact/business-timezone :pastdue.report-v2-fact/queried-at-local :pastdue.report-v2-fact/scope-status :pastdue.report-v2-fact/dimension :pastdue.report-v2-fact/eta-value :pastdue.report-v2-fact/row-kind :pastdue.report-v2-fact/contribution-rank :pastdue.report-v2-fact/selected-vendor-count :pastdue.report-v2-fact/target-past-due-count :pastdue.report-v2-fact/previous-past-due-count :pastdue.report-v2-fact/past-due-count-change :pastdue.report-v2-fact/target-open-count :pastdue.report-v2-fact/previous-open-count :pastdue.report-v2-fact/target-country-open :pastdue.report-v2-fact/previous-country-open :pastdue.report-v2-fact/target-country-past-due-count :pastdue.report-v2-fact/previous-country-past-due-count :pastdue.report-v2-fact/target-country-ratio :pastdue.report-v2-fact/previous-country-ratio :pastdue.report-v2-fact/target-vendor-ratio :pastdue.report-v2-fact/previous-vendor-ratio :pastdue.report-v2-fact/target-country-contribution :pastdue.report-v2-fact/previous-country-contribution :pastdue.report-v2-fact/delta-country-contribution :pastdue.report-v2-fact/positive-contribution-share :pastdue.report-v2-fact/selected-positive-coverage :pastdue.report-v2-fact/display-rank :pastdue.report-v2-fact/expected-bucket-count :pastdue.report-v2-fact/other-bucket-count :pastdue.report-v2-fact/increase-total :pastdue.report-v2-fact/decrease-total :pastdue.report-v2-fact/net-change :pastdue.report-v2-fact/previous-total :pastdue.report-v2-fact/target-total :pastdue.report-v2-fact/previous-expected-total :pastdue.report-v2-fact/target-expected-total :pastdue.report-v2-fact/counts-match :pastdue.report-v2-fact/required :pastdue.report-v2-fact/past-due-count-relative-change :pastdue.report-v2-fact/expected-record-count :pastdue.report-v2-fact/row-ordinal :pastdue.report-v2-fact/requested-vendor-count :pastdue.report-v2-fact/page-after-ordinal :pastdue.report-v2-fact/page-size :pastdue.report-v2-fact/page-record-count :pastdue.report-v2-fact/next-after-ordinal :pastdue.report-v2-fact/has-more :pastdue.report-v2-fact/result-fingerprint]}]}
```

## 6. 恢复与交付边界

- 如果A或C验证成功且控制OK，可采用旧report-query的POSITIVE_TOP_N继续原正向Top10报告。真正交付报告前，按Skill的structured-queries.eql取得完整投影，完成源/retained/cell完整性及Vendor/ETA核对；本文件最小投影不够生成报告。新增一次完整请求单独记录，不能因小投影成功承诺全报告成功。
- 旧SINGLE_VENDOR可以表达明确单Vendor，且不要求贡献为正。多个Vendor可用有限去重工作单，仅在旧接口已验证可用、成本合理、相同P/T且全国分母保留时执行；不递归按Vendor绕过已知source失败。
- 旧POSITIVE_TOP_N不能替代“包含改善和持平的Top N”。用户明确要求该范围时保留需求，报告当前能力缺口，不换成正向排名。对精确集合也不能用Top10偷偷截断。
- 预警状态NORMAL不能阻止用户主动请求报告。TEST只有明确测试请求才能标记。附件没有原用户业务消息，不自行推断测试或普通报告。
- 不回滚整个graph、不覆盖3.6 Skill、不更改Atlas源码，不以缺少后台权限要求用户重复安装。此轮不宣称最终修复。

## 7. 输出给用户

给出实际结果表，不要求用户自己分析原始日志：

| 探针 | 是否执行 | execute结果/错误原文 | env/version | 实际秒数（可得时） | handle是否关联 | 控制状态 | 源/保留完整性 |
|---|---|---|---|---|---|---|---|
| A US旧 | | | | | | | |
| B US新最小 | | | | | | | |
| C CA旧 | | | | | | | |
| D CA新最小 | | | | | | | |
| E US新完整 | | | | | | | |

总结只回答：哪条路径可用、差异定位到哪里、是否恢复了实际完整报告、剩余哪一项未确认。不能凭普通source错误写“已确定超时/驱动bug/SQL语法错误”。不执行的步骤写明条件未满足。若执行agent没有Atlas工具，直接标记工具不可用；文件内EQL可由用户在Studio逐条运行，不能伪造执行结果。

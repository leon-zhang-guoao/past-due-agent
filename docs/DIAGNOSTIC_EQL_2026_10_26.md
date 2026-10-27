# 本轮失败诊断 EQL（固定日期复现，不是日报默认日期）

只用于人工诊断，不修改 Agent 正常路由，不把兼容接口的结果当作新版报告验收。
字段与当前权威合并 EDN 对照通过；未在你的 Atlas 环境执行。

## 执行方式

- 直接在 Atlas EQL 界面逐条运行下面的 `{:entity ... :query ...}`，不是 SQL。
- 先全部使用 US；再将 entity 内的 country-code 改为 CA，分别运行。
- 固定目标 2026-10-26、基准 2026-10-23，复现日志的同一日期对，不选最新日期。
- 按 A→B→C→D→E 串行运行；记录每条的 Country、是否成功、耗时及完整原始错误。
- 同一请求不连续反复重试；D 失败时 E 可暂缓，先返回 A–D 的结果。
- Studio 已验证接受普通关联查询；下面不使用参数化 `({…} {:limit …})`，该形式在本次 Studio 被 query-shape 拒绝。
- 未显式传分页时使用 resolver 默认页；成功不代表全集完整。D/E 需核对 expected-record-count，默认页可能截断。
  本页只用于定位 source 执行问题；全集读取应使用宿主已确认支持的分页方式，不能假定 MCP 和 Studio 完全一致。
- 如果通过 MCP 执行并 retain_result，只使用该次 execute 返回的确切 handle；不得从旧轮次复制。
  同时保留 execute 原始返回和 analyze_result 参数/返回，核对 env/version 和 captured-at。
- 成功回传时可脱敏 Vendor 名称，但保留 Vendor Code、日期、维度、数量和错误字段；不要回传凭据。

## A：Vendor 两期指标及排名

```clojure
{:entity {:pastdue.analysis-query/country-code "US"
          :pastdue.analysis-query/target-snapshot-date "2026-10-26"
          :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
          :pastdue.analysis-query/calendar-mode "WEEKDAYS"
          :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.analysis-query/top-n 10
          :pastdue.analysis-query/vendor-code 0}
 :query [{:pastdue.analysis-query/vendor-comparisons
           [:pastdue.analysis-vendor/country-code
            :pastdue.analysis-vendor/vendor-code
            :pastdue.analysis-vendor/vendor-name
            :pastdue.analysis-vendor/target-snapshot-date
            :pastdue.analysis-vendor/previous-snapshot-date
            :pastdue.analysis-vendor/calendar-mode
            :pastdue.analysis-vendor/contribution-rank
            :pastdue.analysis-vendor/target-past-due-count
            :pastdue.analysis-vendor/previous-past-due-count
            :pastdue.analysis-vendor/target-open-count
            :pastdue.analysis-vendor/previous-open-count
            :pastdue.analysis-vendor/delta-country-contribution
            :pastdue.analysis-vendor/selected-vendor-count]}]}
```

## B：ETA Code 原始两期桶

```clojure
{:entity {:pastdue.analysis-query/country-code "US"
          :pastdue.analysis-query/target-snapshot-date "2026-10-26"
          :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
          :pastdue.analysis-query/calendar-mode "WEEKDAYS"
          :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.analysis-query/top-n 10
          :pastdue.analysis-query/vendor-code 0}
 :query [{:pastdue.analysis-query/eta-code-changes
           [:pastdue.analysis-eta-code/country-code
            :pastdue.analysis-eta-code/vendor-code
            :pastdue.analysis-eta-code/target-snapshot-date
            :pastdue.analysis-eta-code/previous-snapshot-date
            :pastdue.analysis-eta-code/calendar-mode
            :pastdue.analysis-eta-code/eta-value
            :pastdue.analysis-eta-code/target-past-due-count
            :pastdue.analysis-eta-code/previous-past-due-count
            :pastdue.analysis-eta-code/past-due-count-change
            :pastdue.analysis-eta-code/expected-bucket-count
            :pastdue.analysis-eta-code/row-ordinal]}]}
```

## C：ETA Source 原始两期桶

```clojure
{:entity {:pastdue.analysis-query/country-code "US"
          :pastdue.analysis-query/target-snapshot-date "2026-10-26"
          :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
          :pastdue.analysis-query/calendar-mode "WEEKDAYS"
          :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.analysis-query/top-n 10
          :pastdue.analysis-query/vendor-code 0}
 :query [{:pastdue.analysis-query/eta-source-changes
           [:pastdue.analysis-eta-source/country-code
            :pastdue.analysis-eta-source/vendor-code
            :pastdue.analysis-eta-source/target-snapshot-date
            :pastdue.analysis-eta-source/previous-snapshot-date
            :pastdue.analysis-eta-source/calendar-mode
            :pastdue.analysis-eta-source/eta-value
            :pastdue.analysis-eta-source/target-past-due-count
            :pastdue.analysis-eta-source/previous-past-due-count
            :pastdue.analysis-eta-source/past-due-count-change
            :pastdue.analysis-eta-source/expected-bucket-count
            :pastdue.analysis-eta-source/row-ordinal]}]}
```

## D：批量报告的精简投影

```clojure
{:entity {:pastdue.report-query/country-code "US"
          :pastdue.report-query/target-snapshot-date "2026-10-26"
          :pastdue.report-query/previous-snapshot-date "2026-10-23"
          :pastdue.report-query/calendar-mode "WEEKDAYS"
          :pastdue.report-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.report-query/top-n 10
          :pastdue.report-query/vendor-code 0}
 :query [{:pastdue.report-query/rows
           [:pastdue.report-fact/scope-key
            :pastdue.report-fact/record-type
            :pastdue.report-fact/country-code
            :pastdue.report-fact/vendor-code
            :pastdue.report-fact/target-snapshot-date
            :pastdue.report-fact/previous-snapshot-date
            :pastdue.report-fact/calendar-mode
            :pastdue.report-fact/scope-status
            :pastdue.report-fact/dimension
            :pastdue.report-fact/eta-value
            :pastdue.report-fact/row-kind
            :pastdue.report-fact/expected-record-count
            :pastdue.report-fact/row-ordinal]}]}
```

## E：批量报告的完整投影

```clojure
{:entity {:pastdue.report-query/country-code "US"
          :pastdue.report-query/target-snapshot-date "2026-10-26"
          :pastdue.report-query/previous-snapshot-date "2026-10-23"
          :pastdue.report-query/calendar-mode "WEEKDAYS"
          :pastdue.report-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.report-query/top-n 10
          :pastdue.report-query/vendor-code 0}
 :query [{:pastdue.report-query/rows
           [:pastdue.report-fact/scope-key
            :pastdue.report-fact/record-type
            :pastdue.report-fact/country-code
            :pastdue.report-fact/vendor-code
            :pastdue.report-fact/target-snapshot-date
            :pastdue.report-fact/previous-snapshot-date
            :pastdue.report-fact/calendar-mode
            :pastdue.report-fact/vendor-name
            :pastdue.report-fact/dimension
            :pastdue.report-fact/eta-value
            :pastdue.report-fact/row-kind
            :pastdue.report-fact/scope-status
            :pastdue.report-fact/metric-version
            :pastdue.report-fact/selection-mode
            :pastdue.report-fact/target-past-due-count
            :pastdue.report-fact/previous-past-due-count
            :pastdue.report-fact/past-due-count-change
            :pastdue.report-fact/target-open-count
            :pastdue.report-fact/previous-open-count
            :pastdue.report-fact/target-country-open
            :pastdue.report-fact/previous-country-open
            :pastdue.report-fact/contribution-rank
            :pastdue.report-fact/selected-vendor-count
            :pastdue.report-fact/target-vendor-ratio
            :pastdue.report-fact/previous-vendor-ratio
            :pastdue.report-fact/target-country-contribution
            :pastdue.report-fact/previous-country-contribution
            :pastdue.report-fact/delta-country-contribution
            :pastdue.report-fact/positive-contribution-share
            :pastdue.report-fact/selected-positive-coverage
            :pastdue.report-fact/display-rank
            :pastdue.report-fact/expected-bucket-count
            :pastdue.report-fact/other-bucket-count
            :pastdue.report-fact/increase-total
            :pastdue.report-fact/decrease-total
            :pastdue.report-fact/net-change
            :pastdue.report-fact/previous-total
            :pastdue.report-fact/target-total
            :pastdue.report-fact/previous-expected-total
            :pastdue.report-fact/target-expected-total
            :pastdue.report-fact/expected-record-count
            :pastdue.report-fact/row-ordinal
            :pastdue.report-fact/counts-match
            :pastdue.report-fact/required
            :pastdue.report-fact/past-due-count-relative-change
            :pastdue.report-fact/business-timezone
            :pastdue.report-fact/queried-at-local
            :pastdue.report-fact/target-country-past-due-count
            :pastdue.report-fact/previous-country-past-due-count
            :pastdue.report-fact/target-country-ratio
            :pastdue.report-fact/previous-country-ratio]}]}
```

## 结果的含义（不是预先认定根因）

| 结果 | 能缩小的范围 |
|---|---|
| A 失败 | 日期对、基础 Vendor 聚合/排名或共享执行链路；还不能归咎 ETA |
| A 成功，B/C 失败 | 相应 ETA 聚合或其执行路径；原始错误决定是 SQL、类型还是超时 |
| A/B/C 成功，D 失败 | 报告特有的控制/窗口/汇总/UNION/包装路径是重点排查对象 |
| D 成功，E 失败 | 投影、列类型、序列化或字段相关执行路径；不能直接认定 JDBC |
| D/E 都成功但 Agent 失败 | 检查执行环境、版本、并发、handle 关联、调用上下文及间歇性问题 |
| US 超时、CA source | 是不同错误类别，不足以证明相同底层根因 |

D 虽减少投影字段，仍执行批量报告 resolver；不保证是低成本探针。
B/C 是全量桶接口而 D/E 是 Top5+Other 摘要，两者不是同一返回结构。
仅凭通用 source/timeout 错误无法还原数据库原始异常；本组查询用于定位失败边界。

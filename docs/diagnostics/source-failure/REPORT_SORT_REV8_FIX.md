# 3.6.0 revision 8：双重 ORDER BY 修复与验证

## 已确认的根因

现场 US report-v2 在 line 122 col 34 报 ClickHouse Code 62：`ORDER BY row_ordinal ASC LIMIT 200`。图的 binding 原本以 `FROM paged ORDER BY row_ordinal` 结尾，resolver 又声明默认按 row-ordinal 升序排序。

Atlas connector-sql 的 `impl/narrow.clj` 直接向基础 SQL 追加排序和上限，最终得到：

```sql
FROM paged ORDER BY row_ordinal ORDER BY row_ordinal ASC LIMIT 200
```

该语句语法非法。新增 current-v2 有同样缺陷。本地补上 Atlas 追加步骤后，两条查询均复现 Code 62；删除 binding 末尾的排序后回归通过。窗口函数内生成 ordinal 的排序、resolver 排序、分页条件、全国分母和全部指标公式均保留。原39条 resolver 和 SQL binding 不变；不修改 Atlas。

此前仅执行基础 SQL 的测试漏过了连接器追加排序，不能证明发布路径可用。新增测试覆盖追加后的 SQL、US/CA、第一页和续页。本地引擎是现代 chdb，未代替 ClickHouse22.8/JDBC 和线上验收。

## 部署

1. 更新 `atlas/charts/pastdue.edn`，校验图的 `:publication` 为 `:release/version "3.6.0"`、`:source/revision 8`。仅使用本次文件或 `pastdue-graph-v3.6.0-rev8.zip`；此前3.6.0发布包仍是旧图。
2. 在 Studio 保存/校验草稿，确认两条 binding 末尾是 `FROM paged`，没有末尾 `ORDER BY row_ordinal`。不要删除 resolver 的 `:sort`，不要删除窗口函数的 `ORDER BY`。
3. 先验证草稿；发布后 agent 还须确认自己的凭据/连接读取的是这次新图版本，不能仅凭发现 report-v2 字段认定已更新。平台 env/version 由平台分配，不必等于8。
4. 此修复不改 Skill、system prompt、源配置或数据库设置，无需同步替换它们。

## Studio 首轮验证：US 和 CA 各一次

下面只投影字符串、整数、布尔字段，排除响应中的 Date/DateTime 对象，以免 Studio 的 EDN 读取问题遮住 SQL 验证。输入日期仍须保留。固定日期只用于重放本次故障。

### US

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
  :query [{:pastdue.report-v2-query/rows [:pastdue.report-v2-fact/scope-key :pastdue.report-v2-fact/record-type :pastdue.report-v2-fact/scope-status :pastdue.report-v2-fact/country-code :pastdue.report-v2-fact/selection-mode :pastdue.report-v2-fact/metric-version :pastdue.report-v2-fact/selected-vendor-count :pastdue.report-v2-fact/expected-record-count :pastdue.report-v2-fact/row-ordinal :pastdue.report-v2-fact/page-record-count :pastdue.report-v2-fact/next-after-ordinal :pastdue.report-v2-fact/has-more :pastdue.report-v2-fact/result-fingerprint]}]}
```

### CA

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
  :query [{:pastdue.report-v2-query/rows [:pastdue.report-v2-fact/scope-key :pastdue.report-v2-fact/record-type :pastdue.report-v2-fact/scope-status :pastdue.report-v2-fact/country-code :pastdue.report-v2-fact/selection-mode :pastdue.report-v2-fact/metric-version :pastdue.report-v2-fact/selected-vendor-count :pastdue.report-v2-fact/expected-record-count :pastdue.report-v2-fact/row-ordinal :pastdue.report-v2-fact/page-record-count :pastdue.report-v2-fact/next-after-ordinal :pastdue.report-v2-fact/has-more :pastdue.report-v2-fact/result-fingerprint]}]}
```

完成条件：控制行 `COUNTRY_CONTROL` 的 scope-status=OK；ordinal=0在首位；page-record-count与实际行数一致。若存在非控制行，page-size=1时应只带一条、ordinal=1。范围为空时不能强求两行。expected-record-count是全量行数，不等于本页行数。

控制OK且has-more=true时，用返回的next-after-ordinal替换page-after-ordinal，仅再读一页；控制仍为0，非控制ordinal应连续，fingerprint应保持一致。若fingerprint变化，停止合并，标记源数据变化。业务范围无效或数据缺失时按实际scope-status报告，不修改日期来冒充原故障已恢复。

若仍出现同一 Code62，先确认实际草稿/已发布binding是否更新，停止重复查询。若出现新的ClickHouse错误，保留原始cause，按新错误继续诊断；修复语法不能保证消除独立超时或其他错误。

## CA 的 `No reader function for tag object`

这是 Studio 读取服务端 EDN 响应失败，不能据此判断SQL成功或失败。两次request reference：`e1705da1-966d-4af1-988e-aa0cf4ccf91d`（新report）、`b9042752-9450-4401-b879-f525b7e73dcd`（旧report）。

本地Atlas源码的Studio `/api/pathom`使用`pr-str`输出结果，而前端使用EDN reader；Java LocalDate/Instant等值可能被打印为`#object[...]`，前端不认识该tag。数据面另有`semantic-protocol/wire-value`转换，不能把两条传输路径视为等同。没有实际响应体中`#object`的类名，尚不能确定这两次响应具体是哪种对象。

上面的无日期投影查询若可读，再给CA的query字段列表中仅增加 `:pastdue.report-v2-fact/target-snapshot-date` 重测一次。只有增加此字段后才再次报tag object，才能进一步把问题定位到该字段的传输。若无日期仍不可读，保存原始响应/上述reference，不能认定原因已证实。

保持Atlas源码不变：若真实可用的MCP execute_eql/结果读取能正确处理日期，就走该路径读取完整投影，核对实际Country/P/T。Studio无日期探针只用于验证，不能当完整报告；不得为消除前端错误把原有date属性改成string，或伪造缺失日期。

## 完整报告验收

语法探针通过后再使用现有Skill的完整report-v2投影和既定page-size读取所有源页，核对真实Country/P/T、scope、全国分母、Vendor数量、expected-record-count、ordinal、fingerprint及各Vendor校验；同时检查保留结果的行/单元格是否完整。TOP_N、POSITIVE_TOP_N和VENDOR_SET继续各自按原契约工作。未完成全量读取前，只报告“探针通过，完整报告未验收”。CURRENT用用户真实指定的Vendor验证，不猜测Vendor编号。

执行agent仅运行当前实际提供的工具；无工具时交由用户在Studio执行上述EQL，不索要不可访问的recent-id，不递归按Vendor重复重试，不把原始ClickHouse SQL提交到Atlas EQL入口。

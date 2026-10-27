# Standard report rendering — 3.1.2

`table-display-contract.edn` owns module columns, selectors and formats. This file
owns their literal placement. Do not design a new layout while writing each report.
Choose language once: explicit user preference > current request > conversation >
Chinese. Use the same hierarchy in either language; natural diagnosis text may vary.

## Assemble before rendering

1. Fix mode/language and Country/date/filter/denominator scope. Read the binding
   procedure in `data-contract.md#report-row-binding` before using flat report rows.
2. Consume pages and partition by record-type and full identity. Freeze the exact
   VENDOR_METRICS Vendor list/ranks; bind each Vendor's metrics, dimensions and
   checks to that identity, not to row proximity, equal numbers or model memory.
3. Review mapped counts/deltas/checks, then fill the skeleton. Do not requery just
   to change layout. Summary, detailed metrics and diagnosis use the SAME bound
   facts. Fix a draft copy mistake from its raw row, not by calling it a conflict.
4. Before publishing compare each displayed Vendor total/rank/delta with its
   VENDOR_METRICS row; compare Code/Source/US with their own summaries/checks.
   A failed scope keeps its exact position and precise failure; independent valid
   rows remain visible. No question marks or speculative numeric values in tables.

A standard full report uses ALL the following sections, in order. Vendor summary
is a short index, not a place to cram Code/Source lists and replace detailed blocks.
Repeat the entire Vendor block for every selected Vendor in server rank order.
Integer counts have no thousands separators; ratios two decimals; signed contribution
changes four decimals pp. Every pair/header is previous → target. Preserve exact
Code/Source spelling; translate headings, diagnostics, statuses and error explanations
consistently (canonical status tokens may be retained alongside translations).

Missing is not zero. Use localized explicit states: 未查询 / Not queried;
已查询无数据 / Queried, no data; 查询失败 / Query failed;
完整性未核验 / Completeness unverified; 不适用 / Not applicable.
Only verified numeric zero displays 0. Null relative change at zero baseline means
零基线上升 / Zero-baseline increase (positive target), or 无变化 / No change
(both zero). A missing Other row means no remainder only after checks prove that;
never manufacture Other=0. Existing Other rows retain their returned counts,
including zero net change. Never merge distinct zero buckets into “RQ/SC/SH 0”.

## Modes without losing flexibility

- DAILY: all requested Countries appear in Country metrics; only triggered,
  analyzable Countries enter the repeated block. Others get an explicit reason.
- HISTORICAL_FULL: same skeleton; insert verified trigger-events after Country
  metrics, and repeat the Country/event block for selected events. Historical
  events-only renders only the event module, not the full skeleton.
- TEST: same skeleton with [TEST] prefix and final test checks; retain real
  business status and label TEST_FORCED / THRESHOLD_MET per Country.
- INTERACTIVE: only relevant modules, no forced full report, positive Top10 or
  preset date restriction. Follow the requested metric/grain/denominator.
- Explicit “brief”, CSV or custom columns may change presentation; declare CUSTOM
  output and omitted display scope. Do not claim full standard-report test PASS
  for omitted mandatory presentation. Data verification never becomes optional.
- Owner, contacts and PO are CURRENT and on request only. No mail is generated/sent.

## Canonical bilingual skeleton

Replace braces with bound facts, not illustrative data. Labels below are literal;
choose ONE language. A table failure retains its headings and one explicit failure
row (no invented numeric cells). Placeholders are never final report content.
If no selected Vendors, say verified empty selection and skip only inapplicable
Vendor blocks. Do not assert that this exercised multi-Vendor ETA behavior.


### 中文骨架

# {[TEST] 或空} PastDue {日报／历史分析}

## 1. 运行信息、模式与结论

| 模式 | 业务时间 | 输出语言 | 分析范围 | 结论 |
| --- | --- | --- | --- | --- |
| {mode} | {business_time} | {language} | {scope} | {conclusion} |

## 2. Country 指标

| Country | 业务状态 | 数据状态 | 上期日期 | 本期日期 | 日历间隔 | 逾期 上期→本期 | Open 上期→本期 | 逾期率 上期→本期 | 相对变化 | 百分点变化 | 分析状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| {country_code} | {business_status} | {data_status} | {previous_date} | {target_date} | {gap_days} | {past_due_pair} | {open_pair} | {ratio_pair} | {relative_change} | {point_change} | {analysis_status} |

历史完整模式在此插入历史触发事件表。

| Country | 上期日期 | 本期日期 | 日历口径 | 相对变化 | 业务状态 |
| --- | --- | --- | --- | --- | --- |
| {country_code} | {previous_date} | {target_date} | {calendar_mode} | {relative_change} | {business_status} |

## 3. {Country} — {上期日期} → {本期日期}

{实际间隔、下钻原因、选择范围和分母范围}

### Vendor 汇总

| 排名 | Vendor Code | Vendor 名称 | 逾期 上期→本期 | 净变化 | Country 贡献变化 | 正向贡献占比 | 分析状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| {rank} | {vendor_code} | {vendor_name} | {past_due_pair} | {net_change} | {country_delta} | {positive_share} | {analysis_status} |

### {排名}. {Vendor Code} — {Vendor 名称}

#### Vendor 指标

| Vendor 编码 | 逾期 上期→本期 | Open 上期→本期 | Vendor 逾期率 上期→本期 | Country 贡献 上期→本期 | 分析状态 |
| --- | --- | --- | --- | --- | --- |
| {vendor_code} | {past_due_pair} | {open_pair} | {vendor_ratio_pair} | {country_contribution_pair} | {analysis_status} |

#### ETA Code 两期变化

| ETA 值 | 上期数量 | 本期数量 | 净变化 | 相对变化 | Country 贡献变化 |
| --- | --- | --- | --- | --- | --- |
| {eta_value} | {previous_count} | {target_count} | {net_change} | {relative_change} | {contribution_change} |

#### ETA Source 两期变化

| ETA 值 | 上期数量 | 本期数量 | 净变化 | 相对变化 | Country 贡献变化 |
| --- | --- | --- | --- | --- | --- |
| {eta_value} | {previous_count} | {target_count} | {net_change} | {relative_change} | {contribution_change} |

#### 全桶变化汇总

| 维度 | 增加合计 | 减少合计 | 净变化 | 核对状态 |
| --- | --- | --- | --- | --- |
| {dimension} | {increase_total} | {decrease_total} | {net_change} | {evidence_status} |

固定 Code、Source 两行，适用时追加 US 联合来源；减少合计保留负号。

#### US 联合来源变化

| Vendor 编码 | ETA Code | ETA Source | 上期数量 | 本期数量 | 净变化 | 规则解释 |
| --- | --- | --- | --- | --- | --- | --- |
| {vendor_code} | {eta_code} | {eta_source} | {previous_count} | {target_count} | {net_change} | {rule_interpretation} |

根据本 Vendor 的 US 核对决定适用性，与 Country 是否为 US 无关；仅核验有效的联合事实可解释。

#### 业务诊断

| Vendor 编码 | 已核实事实 | 业务信号 | 建议核查 | 限制 |
| --- | --- | --- | --- | --- |
| {vendor_code} | {verified_fact} | {business_signal} | {verification} | {limitations} |

按原排名重复上述完整 Vendor 单元，不可用总表或“其他类似”替代。

### {Country} 小结

| Country | 所选正向贡献覆盖率 | 风险与改善信号 | 下一步核查 | 限制 |
| --- | --- | --- | --- | --- |
| {country_code} | {selected_coverage} | {signals} | {next_checks} | {limitations} |

完成本 Country 后再开始下一个 Country；所有 Country 完成后继续。

## 4. 跨 Country 总结

| 国家 | 信号 | 限制 |
| --- | --- | --- |
| {countries} | {signals} | {limitations} |

## 5. 完成检查

| Country | 检查项 | 已核验 | 预期 | 结果 | 原因 |
| --- | --- | --- | --- | --- | --- |
| {country_code} | {check} | {verified} | {expected} | {result} | {reason} |

## 6. 测试结果（仅 TEST）

| Country | 检查项 | 结果 | 已覆盖范围 | 原因 |
| --- | --- | --- | --- | --- |
| {country_code} | {check} | {result} | {exercised_scope} | {reason} |

### English skeleton

# {[TEST] or empty} PastDue {Daily Monitor / Historical Review}

## 1. Run information, mode and conclusion

| Mode | Business Time | Output Language | Analysis Scope | Conclusion |
| --- | --- | --- | --- | --- |
| {mode} | {business_time} | {language} | {scope} | {conclusion} |

## 2. Country metrics

| Country Code | Business Status | Data Status | Previous Date | Target Date | Gap Days | Past Due Pair | Open Pair | Ratio Pair | Relative Change | Point Change | Analysis Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| {country_code} | {business_status} | {data_status} | {previous_date} | {target_date} | {gap_days} | {past_due_pair} | {open_pair} | {ratio_pair} | {relative_change} | {point_change} | {analysis_status} |

For historical full mode, insert the trigger-events table here.

| Country Code | Previous Date | Target Date | Calendar Mode | Relative Change | Business Status |
| --- | --- | --- | --- | --- | --- |
| {country_code} | {previous_date} | {target_date} | {calendar_mode} | {relative_change} | {business_status} |

## 3. {Country} — {previous date} → {target date}

{Actual gap, drilldown reason, selection scope and denominator scope}

### Vendor summary

| Rank | Vendor Code | Vendor Name | Past Due Pair | Net Change | Country Delta | Positive Share | Analysis Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| {rank} | {vendor_code} | {vendor_name} | {past_due_pair} | {net_change} | {country_delta} | {positive_share} | {analysis_status} |

### {rank}. {Vendor Code} — {Vendor name}

#### Vendor Metrics

| Vendor Code | Past Due Previous→Target | Open Previous→Target | Vendor Ratio Previous→Target | Country Contribution Previous→Target | Analysis Status |
| --- | --- | --- | --- | --- | --- |
| {vendor_code} | {past_due_pair} | {open_pair} | {vendor_ratio_pair} | {country_contribution_pair} | {analysis_status} |

#### ETA Code Changes

| ETA Value | Previous Count | Target Count | Net Change | Relative Change | Contribution Change |
| --- | --- | --- | --- | --- | --- |
| {eta_value} | {previous_count} | {target_count} | {net_change} | {relative_change} | {contribution_change} |

#### ETA Source Changes

| ETA Value | Previous Count | Target Count | Net Change | Relative Change | Contribution Change |
| --- | --- | --- | --- | --- | --- |
| {eta_value} | {previous_count} | {target_count} | {net_change} | {relative_change} | {contribution_change} |

#### All-bucket change totals

| Dimension | Increase total | Decrease total | Net change | Reconciliation |
| --- | --- | --- | --- | --- |
| {dimension} | {increase_total} | {decrease_total} | {net_change} | {evidence_status} |

Code and Source rows are required; add US joint Source when applicable. Decrease total stays negative.

#### US joint Source Changes

| Vendor Code | ETA Code | ETA Source | Previous Count | Target Count | Net Change | Rule Interpretation |
| --- | --- | --- | --- | --- | --- | --- |
| {vendor_code} | {eta_code} | {eta_source} | {previous_count} | {target_count} | {net_change} | {rule_interpretation} |

Applicability comes from this Vendor’s US check, regardless of Country. Interpret verified joint facts only.

#### Business Diagnosis

| Vendor Code | Verified Fact | Business Signal | Suggested Verification | Limitations |
| --- | --- | --- | --- | --- |
| {vendor_code} | {verified_fact} | {business_signal} | {verification} | {limitations} |

Repeat the entire Vendor block in original rank order; never replace it with one overview or “others similar”.

### {Country} summary

| Country | Selected Positive Coverage | Risks and Improvements | Next Checks | Limitations |
| --- | --- | --- | --- | --- |
| {country_code} | {selected_coverage} | {signals} | {next_checks} | {limitations} |

Finish this Country before the next; after all Countries continue below.

## 4. Cross-Country summary

| Countries | Signals | Limitations |
| --- | --- | --- |
| {countries} | {signals} | {limitations} |

## 5. Completion

| Country Code | Check | Verified | Expected | Result | Reason |
| --- | --- | --- | --- | --- | --- |
| {country_code} | {check} | {verified} | {expected} | {result} | {reason} |

## 6. Test checks (TEST only)

| Country Code | Check | Result | Exercised Scope | Reason |
| --- | --- | --- | --- | --- |
| {country_code} | {check} | {result} | {exercised_scope} | {reason} |

## Interpretation and completion

For every Vendor discuss its actual increases, improvement offsets and net change;
separate quantity effects from Country Open denominator effects. Diagnosis cites
raw Code/Source labels, observed changes and the applicable rule, not just PASS.
Code and Source explain the same Vendor total independently; never add them.
The US joint table sums to Code=US, not the entire Vendor Source distribution.
When Code=US is inside Other, use verified US check expected totals. A nonpositive
net change can coexist with positive contribution because denominators changed.
Do not infer the same PO migrated between codes from aggregate changes.

Country coverage must show the actual returned selected-positive-coverage, or a
specific unavailable reason. “Returned by server” is not the coverage value.
It is coverage of ALL POSITIVE contributions, not of net Country increase. Distinguish
server full-population reconciliation from client raw-bucket independent audit.
An unspecified source collection coverage flag alone is a warning when identity,
paging, represented buckets and server checks all pass; do not automatically label
PARTIAL. Completion-contract.md defines the actual evidence requirements.

Completion rows name the actual mandatory checks and show verified / expected:
transport records; selected Vendor set/ranks; Code-reconciled Vendors; Source-
reconciled Vendors; US-joint-reconciled Vendors / applicable Vendors; fully rendered
Vendor blocks. Use actual server counts and observed presentation, not assumed
10/10 or 0/0 success. US required=false is counted as inapplicable only after its
scope and totals check; missing check is unknown, not zero applicable. A zero
selected set explicitly skips only the relevant inapplicable checks. Test result
covers all applicable mandatory facts AND display/diagnosis; report source-coverage
warnings separately rather than treating them as missing work. Optional modules
are outside completion scope unless explicitly requested.

If length is a concern, shorten repeated prose, never delete Vendor tables or
numbers. If the host demonstrably limits output, identify remaining exact
Country/Vendor scopes and mark report incomplete; never invent a host interruption,
auto-continuation or preemptively omit data. These instructions cannot hard-enforce
rendering in a host without a renderer/validation gate.


## Evidence and diagnosis
Count decline with rising Country contribution requires a denominator explanation.
US AutoUS and explicit AUTO US (trimmed case-insensitive EXACT) mean the
Vendor-provided ETA is overdue; OTHER_KNOWN means a rough date ONLY for exact
recognized business Source dictionary values observed in verified joint rows.
Unknown/unrecognized raw Source must use the UNKNOWN diagnostic, retaining its
raw label; never classify every non-AutoUS value as rough-date. Apply rules only to verified joint Source rows; don't
infer US association from marginal Source. OTHER is a mixed aggregate: never
classify all Other as AutoUS/known/unknown or invent its hidden buckets. State
that its source-specific interpretation is unavailable while retaining its counts.

DATA_CONFLICT requires both raw same-scope contradictory responses and provenance;
quarantine both, retain Vendor position, show “数据身份/数量冲突，已隔离” /
“Conflicting identity/count evidence quarantined”. No affected-dimension diagnosis.
ASSOCIATION_ERROR is mismatched attachment; REPORT_ERROR is a bad rendered claim.
Correct the right layer; missing evidence is UNVERIFIED, not a proven server fault.
Completion-contract.md governs classification and test outcome. Only INTERACTIVE
questions have ≤6 DIFFERENT LOGICAL FACT REQUESTS; discovery/materialization/
pagination are excluded, retries stay in the same slot. Report presets have no
six-request cap and must attempt all mandatory work. Bound broad historical or
optional scope explicitly, never silently skip or fabricate host interruption/
promised continuation. Completeness is based on facts/checks, not six repeated
module-status strings; failure/N/A can be stated once at its affected scope.

### Capture and semantic-comparison safeguard
Show available queried-at-local and business-timezone; derive actual PST/PDT offset
only from its trusted corresponding instant/offset, never a guessed tool-capture or
delivery time. Without that evidence retain the returned local timestamp/IANA and
label offset UNVERIFIED. Legacy queried-at-utc keeps UTC semantics unchanged; the
new queried-at-local field never repurposes an old UTC attribute. DATA_CONFLICT
compares COMMON semantic fact fields in paired raw same-scope responses, not whole
payload shape: projection-only fields, handles or timestamps differing aren't a
conflict, and an unprojected field is unknown, not contradictory.

## Rule provenance and recognized Source dictionary
Every rule declares stable rule-id, risk-type and requires-joint-facts. US rules
require joint facts; non-US rules use verified Code facts and don't require a
Source association. Preserve rule IDs in diagnostic evidence without adding
mandatory table columns or repeating status boilerplate. risk-type is the rule's
business signal, not proof of causation or additional source-specific meaning.

The explicit accepted observed Source set is maintained in
eta-diagnostic-rules.edn :source-classification/:recognized-other-sources. Match
trimmed case-insensitive EXACT values; AutoUS/AUTO US take precedence. Dictionary
membership alone never proves a Source exists in the queried population. Apply
OTHER_KNOWN only to an actual verified Code=US×Source BUCKET with membership.
Do not emit absent sources, infer hidden OTHER constituents, invent semantics
from names (EDI/API/BUYER etc.), create a data table, or assume a catalog tool.
New recognized values require explicit governed business dictionary approval;
otherwise diagnostic UNKNOWN, not a catch-all OTHER_KNOWN branch.

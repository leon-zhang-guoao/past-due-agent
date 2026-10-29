# Report modules — 3.3.7 (single layout authority)

Use structured facts, not 3.2 long Markdown blocks. Same modules and column order
in Chinese and English; choose ONE language. Code/Vendor names remain literal data.
Interactive answers compose only relevant modules. Standard reports use all
applicable modules below; explicit brief/custom output discloses its reduced scope.

## Shared formatting

Date YYYY-MM-DD, pairs previous→target, integers without thousands separators,
positive changes +, negative changes −, zero 0. Ratios/shares/relative changes:
100×returned ratio, exactly two decimals %. Contribution levels/changes: 100×ratio,
exactly four decimals pp. Round for display only; don't recompute formulas.
If a nonzero change rounds to zero, use '<0.01%' / '<0.0001 pp' with its actual
direction in words rather than a misleading signed zero. Exact zero remains 0.
Escape table delimiters/newlines in names. Do not change raw identity to fit display.
Null relative change with P=0,T>0: 零基线上升 / Zero-baseline increase; P=T=0:
无变化 / No change. Missing, failed and unqueried are not zero or the same '—'.
Use 无数据/No data only for verified successful empty results; 未查询/Not queried,
查询失败/Query failed, 未验证完整/Completeness unverified describe their actual cases.

## Standard section order

1. **运行信息与结论 / Run information and conclusion**: DAILY/HISTORICAL/TEST,
   resolved language, America/Los_Angeles, actual P/T per Country. Show actual
   PST/PDT+offset only with trusted instant evidence. TEST title [TEST]/[测试],
   preserve actual trigger and show TEST_FORCED/THRESHOLD_MET separately.
2. **Country 指标 / Country metrics**: all requested Countries, default US→CA.
3. For each applicable **{Country} — {P}→{T}**:
   - **Vendor 排名 / Vendor ranking**
   - **Country Vendor 总结分析 / Country Vendor analysis summary**
   - **#{rank} {Vendor Code} — {name}**, EVERY selected Vendor in the same order.
   - **覆盖率与限制 / Coverage and limitations**
4. **跨 Country 总结 / Cross-Country summary** (multiple Countries only).
5. **执行范围与缺失项 / Execution scope and gaps**: compact actual source checks,
   rows retrieved vs expected, Vendors delivered vs selected, exact missing scope.

No-trigger Countries still appear in metrics; ordinary drilldown is not required.
Valid zero-positive selection shows explicit empty selection, not fabricated rows.
History events-only stops at event listing. No current monitor mislabeled historical.

## Country metrics columns (exact order)

| 中文 | English | Binding |
|---|---|---|
| Country | Country | country-code |
| 业务状态 | Business status | business-status |
| 数据状态 | Data status | data-status |
| 上期日期 | Previous date | previous-snapshot-date |
| 本期日期 | Target date | target-snapshot-date |
| 间隔天数 | Gap days | snapshot-gap-days |
| 逾期 上期→本期 | Past due previous→target | previous/target-past-due-count |
| Open 上期→本期 | Open previous→target | previous/target-open-count |
| 逾期率 上期→本期 | Ratio previous→target | previous/target-past-due-ratio |
| 相对变化 | Relative change | relative-ratio-change |
| 百分点变化 | Point change | percentage-point-change (ratio→pp) |

Dates not adjacent: say 较上一可用快照 / Compared with previous available snapshot,
not 'yesterday'; include calendar gap and delay/missing-weekday warnings when returned.

## Vendor ranking columns (VENDOR_METRICS only)

| 中文 | English | Binding |
|---|---|---|
| 排名 | Rank | contribution-rank |
| Vendor Code／名称 | Vendor code / name | vendor-code + vendor-name |
| 逾期 上期→本期 | Past due previous→target | previous/target-past-due-count |
| 净变化 | Net change | past-due-count-change |
| Open 上期→本期 | Open previous→target | previous/target-open-count |
| Vendor 逾期率 上期→本期 | Vendor ratio previous→target | previous/target-vendor-ratio |
| Country 贡献变化 pp | Country contribution change pp | delta-country-contribution |
| 正向贡献占比 | Positive contribution share | positive-contribution-share |
| 分析状态 | Analysis status | actual scoped checks/retrieval, not invented raw field |

Bind overview and details to the SAME metrics row. Never read Vendor totals from
ETA bucket rows. SINGLE_VENDOR retains actual rank and can have nonpositive delta;
positive-share is not applicable there if its contribution is nonpositive.

## Country Vendor analysis summary (immediately after ranking)

Write concise grounded interpretation: leading selected contributors; material
increases and improvement offsets; verified conditional Code=US Source meaning;
priority checks; scope limits. Use rule IDs/meanings from eta-diagnostic-rules.edn.
Preserve Vendor identity. Don't invent Country-wide aggregates from Top10 or add
Code and Source. No standalone US joint-source column/table, no generic tutorial
instead of actual observed evidence. If no applicable joint activity, omit that
interpretation; if needed evidence failed, disclose only the affected limitation.
Unknown/Other Source has no guessed classification. Compact Top5+Other supports
only its visible buckets and returned signed totals, not unseen constituents.

## Every Vendor detail (fixed order)

1. **指标变化 / Metric changes**: PD/Open/Vendor ratio P→T from ranking row;
   Country contribution P→T and delta from that same row. One compact line/list,
   no second wide metrics table.
2. **ETA Code 变化 / ETA Code changes** table.
3. **ETA Source 变化 / ETA Source changes** table.
4. **变化摘要与诊断 / Change summary and diagnosis**: Code and Source EACH have
   increase-total, decrease-total (negative signed), net-change from VENDOR_CHECK;
   identify material worsening/improving categories, residual risk and suggested
   check. Distinguish count change vs denominator effects. No PO migration/causality.

Both ETA tables use exactly these columns in this order:

| ETA 值 / ETA value | 上期数量 / Previous count | 本期数量 / Target count | 净变化 / Net change | 相对变化 / Relative change | Country 贡献变化 pp / Country contribution change pp |
|---|---:|---:|---:|---:|---:|

Choose localized headers, not bilingual duplication. BUCKET order=display-rank;
OTHER is 其他（N项）/Other (N items), server-computed counts and relative change.
Do not invent Other when no OTHER record exists. If the server returned OTHER
with zero counts but positive other-bucket-count, display it: real zero-valued
remainder buckets still belong to the complete compact representation. Do not
re-rank summary rows. A failed/unread dimension retains
its heading and concrete failure/empty status, never a fabricated table.

### Vendor unit skeleton (instantiate for every selected Vendor)

Choose ONE language and retain the fields/order defined above. This is a model
output contract, not generated Markdown or a deterministic renderer.

```text
### #{rank} {vendor_code} — {vendor_name}
指标变化 / Metric changes: PD P→T; Open P→T; Vendor ratio P→T;
Country contribution P→T; contribution change pp.

ETA Code 变化 / ETA Code changes
[the six-column table above: ALL returned Top5 BUCKET rows + returned OTHER]

ETA Source 变化 / ETA Source changes
[the same six-column table, independent Source facts]

变化摘要与诊断 / Change summary and diagnosis
Code: increases +I; decreases −D; net N.
Source: increases +I; decreases −D; net N.
[Evidence-based worsening, improvement offsets, remaining risk, suggested check.]
```

The slash labels indicate language alternatives, not bilingual duplication.
Substitute actual verified facts; no fabricated empty rows. A dimension failure
keeps its heading and exact status/reason instead of a table. A wide Country-level
"Vendor | main Code deltas | main Source deltas | diagnosis" table may be an
explicit brief/custom view, but cannot replace these units in a standard full
report. Full two-period tables are required even when all deltas fit in one line.

## Coverage, context and length

Coverage = selected-positive-coverage once, not sum of repeated metadata:
Top10覆盖全部正向贡献的X% / Top10 covers X% of all positive contributions.
Never describe it as the explained fraction of net Country increase. Mention
negative offsets and limit conclusions to selected Vendors.

Owner/contacts/PO remain on request, CURRENT and display-only; standard report
needn't repeat 'not queried' under every Vendor. If requested, show actual result
or status under that Vendor. PO quotas remain unchanged.

Keep all selected Vendor units. Save space by removing repeated definitions/status
matrices/raw JSON, not mandatory ETA facts. If evidenced output limits require
parts, number them by Country/Vendor range and state delivered/remaining scope;
full report is not delivered until all parts are actually present. Do not promise
unsupported automatic continuation. No model-issued overall report/test PASS.


## Delivery accounting

The final scope section distinguishes:
- source execution and compact-record retrieval (actual unique rows vs expected);
- reconciliation (actual identity, Country and dimension checks or affected gaps);
- presentation (selected Vendor codes/ranks; complete units; units with explicit
  local failures; units not delivered yet).

All selected Vendor positions must exist, but a displayed error placeholder is
not successful ETA analysis. A fully read 177-row result says nothing by itself
about how many Vendor units were delivered. List remaining Country/rank ranges
and missing modules when partial. Do not claim a completed full test path merely
because requests succeeded. Preserve actual business trigger/freshness separately.

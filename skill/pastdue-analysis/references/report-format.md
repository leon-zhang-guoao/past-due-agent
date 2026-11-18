# Report modules — 3.3.16 (single layout authority)

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

1. **运行信息与结论 / Run information and conclusion**: actual DAILY/HISTORICAL/TEST/INTERACTIVE,
   resolved language, America/Los_Angeles, actual P/T per Country. Show actual
   PST/PDT+offset only with trusted instant evidence. TEST title [TEST]/[测试],
   preserve actual trigger and show TEST_FORCED/THRESHOLD_MET separately.
2. **Country 指标 / Country metrics**: all requested Countries, default US→CA.
3. For each applicable **{Country} — {P}→{T}**:
   - **Vendor 排名与主要 ETA 变化 / Vendor ranking with main ETA changes**
   - **Country Vendor 总结分析 / Country Vendor analysis summary**
   - **覆盖率与限制 / Coverage and limitations**
4. **跨 Country 总结 / Cross-Country summary** (multiple Countries only).
5. **执行范围与缺失项 / Execution scope and gaps**: compact actual source checks,
   sum of disjoint statement rows versus expected_record_count, Vendors delivered
   vs selected, exact missing scope. A complete split read still renders every
   selected Vendor's ranking row with main Code/Source cells and signed-total check.

Automatic monitoring includes no-trigger Countries in metrics without requiring
drilldown. A requested report includes applicable Vendor analysis regardless of
trigger, with the requested selection scope and actual business status.
Requested reports retain the actual analysis mode rather than TEST_FORCED.
Valid zero-positive selection shows explicit empty selection, not fabricated rows.
History events-only stops at event listing. No current monitor mislabeled historical.

### CURRENT reports and Vendor questions

CURRENT has capture time and current measurements, without an observed previous
snapshot. Show Vendor code/name, Past Due, Open, ratio with its declared
denominator, requested share and ETA Code/Source distributions. Account for each
requested identity and coverage status. Use joint facts for conditional Source.
Comparison dates/deltas and signed change checks apply only to actual pair facts.
When current status and comparison are both requested, label separate captures
and render verified modules from each. CURRENT alone cannot fill the existing
comparison HTML payload.

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
Copy every column for the chosen language, in this order. Do not drop Previous
date, Target date or Gap days, and do not rename the headers.

## Vendor ranking with main ETA changes

Metric columns bind **VENDOR_METRICS only**. ETA cells bind returned ETA bucket
rows and VENDOR_CHECK for that same Vendor. Never read Vendor totals from ETA
bucket rows. Never add Code and Source together. Never invent OTHER constituents.

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
| 主要 Code 变化 | Main Code changes | returned Code ETA buckets (see cell rules) |
| 主要 Source 变化 | Main Source changes | returned Source ETA buckets (see cell rules) |
| 核对 | Check totals | VENDOR_CHECK signed totals |
| 分析状态 | Analysis status | actual scoped checks/retrieval, not invented raw field |

Bind overview and ETA cells to the SAME Vendor identity and rank. SINGLE_VENDOR
retains actual rank and can have nonpositive delta; positive-share is not
applicable there if its contribution is nonpositive. Copy every ranking column
for the chosen language, in this order. Contribution changes use exactly four
decimal places.

### Main Code / Main Source cell rules

Use ETA_CODE_SUMMARY with dimension=code, ETA_SOURCE_SUMMARY with dimension=source;
conditional-source interpretation uses US_SOURCE_SUMMARY with dimension=us and
eta_value as a Source name. Copy rows from the reader's typed projection or already
verified equivalent fields. For **each dimension independently**:

1. Start from that Vendor's **returned** compact ETA rows for the dimension
   (Top5 BUCKET rows + returned OTHER if present). Do not invent OTHER.
2. Rank those returned rows by `abs(past_due_count_change)` descending, then
   `target_past_due_count` descending, then `eta_value` ascending. These are
   bucket fields, including the returned Other as one compact row.
3. Take at most **5** rows. If fewer than 5 returned rows exist, show only the
   actual returned rows. If zero returned rows after a successful empty read,
   write 无 / None. If the dimension failed or was unread, write the concrete
   failure/unread status — never fabricate values.
4. Format each kept row as `{eta_value} {previous_past_due_count}→{target_past_due_count}
   ({signed past_due_count_change})`. These three quantities come from that same
   SUMMARY row. Join with
   `；` (Chinese) or `; ` (English). Preserve literal ETA identity spelling.
5. OTHER display name remains 其他（N项）/Other (N items) when that row is kept.
   The main-cell ordering uses the bucket delta above; display-rank is the
   server compact-bucket order, while net_change is the whole dimension total.

Signed net uses the shared formatting rules (+/−/0). Relative change and
Country contribution pp are **not** required inside these cells.

### Check totals cell

From VENDOR_CHECK for that Vendor, one compact line:
`Code +I/−D/净N；Source +I/−D/净N` (or English equivalents).
I = increase-total, D = decrease-total (negative signed as returned), N = net-change.
If a dimension's check failed/unread, state that side's status instead of numbers.

## Country Vendor analysis summary (immediately after ranking)

Write concise grounded interpretation: leading selected contributors; material
increases and improvement offsets; verified conditional Code=US Source meaning;
priority checks; scope limits. Use rule IDs/meanings from eta-diagnostic-rules.edn.
Preserve Vendor identity. Don't invent Country-wide aggregates from Top10 or add
Code and Source. No standalone US joint-source column/table, no generic tutorial
instead of actual observed evidence. If no applicable joint activity, omit that
interpretation; if needed evidence failed, disclose only the affected limitation.
Unknown/Other Source has no guessed classification. Compact Top5+Other and the
main-change cells support only their visible buckets and returned signed totals,
not unseen constituents.

## Full six-column ETA tables (on request only)

Standard DAILY / HISTORICAL / TEST reports **do not** instantiate per-Vendor
`### #{rank} …` sections or six-column Code/Source tables. Those tables are an
interactive / follow-up module when the user asks for one Vendor's full ETA
breakdown (or explicitly requests full tables).

When requested, use exactly these columns in this order:

| ETA 值 / ETA value | 上期数量 / Previous count | 本期数量 / Target count | 净变化 / Net change | 相对变化 / Relative change | Country 贡献变化 pp / Country contribution change pp |
|---|---:|---:|---:|---:|---:|

Choose localized headers, not bilingual duplication. BUCKET order=display-rank;
OTHER is 其他（N项）/Other (N items), server-computed counts and relative change.
Do not invent Other when no OTHER record exists. If the server returned OTHER
with zero counts but positive other-bucket-count, display it. Do not re-rank
summary rows. A failed/unread dimension retains its heading and concrete
failure/empty status, never a fabricated table. Include metrics line and
Code/Source signed totals with diagnosis when expanding a Vendor on request.

### Optional on-request Vendor skeleton

```text
### #{rank} {vendor_code} — {vendor_name}
指标变化 / Metric changes: PD P→T; Open P→T; Vendor ratio P→T;
Country contribution P→T; contribution change pp.

ETA Code 变化 / ETA Code changes
[the six-column table: ALL returned Top5 BUCKET rows + returned OTHER]

ETA Source 变化 / ETA Source changes
[the same six-column table, independent Source facts]

变化摘要与诊断 / Change summary and diagnosis
Code: increases +I; decreases −D; net N.
Source: increases +I; decreases −D; net N.
[Evidence-based worsening, improvement offsets, remaining risk, suggested check.]
```

## Coverage, context and length

Coverage = selected-positive-coverage once, not sum of repeated metadata:
Top10覆盖全部正向贡献的X% / Top10 covers X% of all positive contributions.
Never describe it as the explained fraction of net Country increase. Mention
negative offsets and limit conclusions to selected Vendors.

Owner/contacts/PO remain on request, CURRENT and display-only; standard report
needn't repeat 'not queried' under every Vendor. If requested, show actual result
or status under that Vendor. PO quotas remain unchanged.

Keep every selected Vendor as one ranking row with main Code, main Source and
check cells in the same answer. Save space by removing repeated definitions,
status matrices and raw JSON, not by dropping selected Vendor rows or ETA cells.
A predicted response budget is not a reason to stop after metric-only ranking.
Do not promise unsupported automatic continuation. No model-issued overall
report/test PASS.

## Delivery accounting

The final scope section distinguishes:
- source execution and compact-record retrieval (actual unique rows vs expected);
- reconciliation (actual identity, Country and dimension checks or affected gaps);
- presentation (selected Vendor codes/ranks; ranking rows with main Code/Source
  cells and check totals; rows with explicit local failures; rows not delivered).

All selected Vendor positions must exist as ranking rows with the ETA cells filled
from returned facts or with concrete local-failure status. A displayed error
placeholder is not successful ETA analysis. A fully read 177-row result says
nothing by itself about how many Vendor ranking rows were delivered. List
remaining Country/rank ranges and missing modules when partial. Do not claim a
completed full test path merely because requests succeeded. Preserve actual
business trigger/freshness separately.

## HTML companion

Markdown modules above remain the layout authority. Do not auto-emit HTML after
DAILY/TEST. After a successful standard DAILY/TEST Markdown report, ask once
whether to generate Ops/Demo HTML; only on user confirm (or explicit HTML_ONLY)
delegate via `skill/pastdue-html-report/references/sub-agent-contract.md` to
`business-storyteller` (one file, or CAPACITY split). The parent verifies the child's
written artifacts before disclosing `HTML: WRITTEN|SPLIT|SKIPPED — reason=… — detail=…`
with real paths on success. The child owns HTML design and writing.

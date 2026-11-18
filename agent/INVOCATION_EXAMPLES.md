# Requests — Agent contract 3.5.1

Examples illustrate operation composition, not a closed list of supported phrases.
Prefer discovered explore-v2; old explore remains compatible for scopes it expresses.
Vendor sets use vendor-codes-json, detail stays on its own targeted APIs. Follow-ups
modify only changed scope fields. See interactive-planning.md and explore-v2.md.

| Request | Route / resolved intent |
|---|---|
| start daily monitor | Monitor, then structured full-Vendor report for real US/CA triggers only; English |
| start test | Force applicable structured full-Vendor report; real trigger unchanged; fully English |
| 开始测试 | Same test preset, fully Chinese |
| 开始测试，只测试 CA | One Country, all selected Vendors, Chinese |
| start test for snapshot 2026-10-26 | Explicit completed target; previous available weekday; English |
| compare CA 2026-10-26 with 2026-10-22 | Explore PAIR, EXPLICIT; never substitute nearest previous |
| CA 当前逾期数量前十 Vendor | Explore CURRENT, VENDOR, PAST_DUE_COUNT DESC, ALL, top-n=10 |
| CA 当前逾期率最高十家 | Explore CURRENT, VENDOR, PAST_DUE_RATIO DESC, ALL, top-n=10 |
| CA 当前各 Vendor 的 ETA Code 占比，全部 | Explore CURRENT, VENDOR_CODE, WITHIN_VENDOR, NONE, top-n=0 |
| CA Vendor 31420 的 ETA Code × Source 分布 | Explore CURRENT, VENDOR_CODE_SOURCE, vendor-code=31420; no marginal cross-join |
| CA 2026-10-26 比上期逾期数量下降最多十家 | Explore PAIR, PREVIOUS_AVAILABLE, VENDOR, COUNT_CHANGE ASC, NEGATIVE, top-n=10 |
| 过去一周哪些天触发，只列日期 | History D−7..D−1 targets, WEEKDAYS, no report drilldown |
| 过去一周触发事件，分析每国最近一次 | History then pinned report per selected Country/event |
| 历史查询包含周末 | Explicit CALENDAR_DAYS, label it |
| CA 过去一周按天看逾期率 | Explore RANGE, COUNTRY, no summing snapshot stocks |
| Vendor 31420 的两期指标，即使贡献下降 | Report SINGLE_VENDOR or Explore PAIR targeted Vendor; no positive-only gate |
| CA 当前按部门/Primary/Manager筛选，显示占全国家逾期比例 | Explore org filters, COUNTRY_ALL denominator; don't silently rebase |
| CA Vendor 31420 当前逾期明细 | On-demand current detail, not high-risk-only |
| 这些 Vendor 的 ETA Specialist 和通知邮箱 | Targeted current mappings/contacts, display only |

Dates and Vendor codes above are illustrative, never runtime defaults. Exact
controlled inputs and manual EQL examples are in the Skill's data contract and
Atlas query playbook. No arbitrary SQL or Python is needed for these requests.

## Requested reports and Vendor questions — 3.6.0

| Request | Route |
|---|---|
| 没有触发预警也给我 US、CA 的完整报告 | Actual latest eligible P/T per Country, real status; report-v2 TOP_N across all signs. INTERACTIVE, no TEST label. |
| US Vendor 64342 当前怎么样 | current-v2 exact set `[64342]`; metrics, ratios with denominator and joint-derived distributions. |
| 给 US 64342、75256、72478 的报告，包括改善的 | report-v2 VENDOR_SET, top-n=0; every requested identity, pair metrics and ETA checks. |
| 上面的报告再加 65505，其他不变 | Preserve Country/P/T/calendar/selection semantics and presentation; amend the set, capture and verify the revised scope. |
| 当前这些 Vendor 按部门筛选，并看占全国逾期比例 | current-v2 org filters, COUNTRY_ALL; national denominator excludes neither unselected nor other-org Vendors. |
| 把这几家按上周增长排前二，再看全部 ETA | explore-v3 selection/expansion, preserve original candidate denominator and exact time scope. |

Report text alone means Markdown. Current facts and completed-pair comparisons
remain separately labeled. HTML follows the existing explicit handoff contract.


Standard daily/history/test requests use the canonical bilingual skeleton and
separate Vendor Code/Source tables. English and Chinese differ in labels and
interpretation language, not field order or fact definitions. Interactive requests
use only relevant modules. Explicit brief/custom-column output is allowed with
CUSTOM display scope; it does not claim full-report test PASS.

Country Vendor analysis appears immediately after each Country Vendor ranking.
Conditional-source conclusions are integrated there, not a default joint-source
column/table. Explicit requests for ETA Code=US Source detail remain supported.


3.3 uses structured report facts, not long text. Source/result pages and cells need
separate coverage checks. Start a real single-Country test; actual dates and schema
must be verified. No self-awarded report PASS, no platform execution claimed here.


## HTML sub-agent routing

| Request | Route |
|---|---|
| 开始测试 | Core analysis → complete MD → one HTML offer; no child call |
| 开始测试，生成 HTML 报告 | Core verified payload → business-storyteller; no MD body |
| 要，生成 HTML | Reuse exact prior bindings → child; BINDING if unavailable |
| 历史完整报告只要 HTML | HISTORICAL payload → child if complete; keep actual mode |
| 只列历史事件 | Normal events answer; no HTML offer |
| HTML 换成简洁蓝色风格 | Reuse same facts → child with visual preference; no re-query |

The parent waits and independently reads back artifacts before disclosing paths.
Missing child → DELEGATE; changed/missing artifact → VERIFY. No inline rendering
fallback. See `skill/pastdue-html-report/references/sub-agent-contract.md`.

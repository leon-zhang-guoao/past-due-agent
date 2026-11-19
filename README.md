# PastDue Analysis Agent — 3.7.0

“目前／当前／今日情况”现在默认实时明细：Country概况和重点Vendor；单个或一批
Vendor名称/编号先精确识别，再按Country集合查询当前指标与Code/Source分析。
新增live-overview和vendor-identity两个EDN查询，复用current-v2；原41个执行接口不变。
历史报告、无预警report、TEST、explore及可选HTML路径保留。

本次需要一起更新EDN、Prompt及完整Skill。详见
[方案、文件与逐步验收](docs/CURRENT_3_7_0.md)。本地检查不等于线上通过。

Requested reports run independently of alert status. One/several Vendor questions
retain the exact set, including improving and zero-PD Vendors. Graph3.6 adds
report-v2 for paged completed-pair reports and current-v2 for one-scan CURRENT
metrics/distributions; all39 existing resolvers and SQL bindings are preserved.
Atlas implementation is unchanged. See [design, scope and rollout](docs/ON_DEMAND_REPORTS_3_6.md).
Source changes are local and require Atlas validation before deployment.

Composable questions through Prompt + Skill + Atlas. Daily/history/test remain
presets; free questions resolve a working specification and combine selection,
filters, aggregates/comparisons, ranking and requested detail.

Atlas3.5.0/revision6 adds explore-v3-query/rows: graph-side Vendor/grouped-row
selection, GLOBAL/TARGET_DATE ranking and separate ETA expansion. Original candidate
denominators survive Top N; empty selection never means all. Metric3.1.0 and all38
older resolver/SQL bindings remain unchanged. All26 scalar inputs stay explicit;
native defaults/saved queries are not assumed. New questions
composed of existing operations do not require new scenario-specific APIs.

## HTML presentation update (3.5.1)

PastDue now delegates requested HTML design/writing to the existing platform's
`business-storyteller` sub-agent. Core analysis stays with PastDue; verified
payload/provenance crosses the boundary and returned artifacts are read back and
checked before success is reported. Default Markdown + DAILY/TEST HTML offer,
explicit HTML-only and binding reuse remain unchanged.
See [installation and handoff](docs/HTML_SUB_AGENT_3_5_1.md). The full package includes
the child prompt and handoff contract; register the child on the platform before
live use. No Atlas update is needed for this presentation patch.

## What changed in Atlas3.5

- Vendor set is declared JSON array text, not a comma list in a single-value input.
- SQL pages are bounded before transport limits; counts/ordinals/checksum expose
  ordinary omissions/drift. Continuations are not atomic snapshot transactions.
- Skill plans by operations and adapts to actual errors with equivalent bounded
  alternatives. Six scopes is a cost review, not an automatic stop. Existing
  targeted PO/Owner/contact capabilities remain available.
- Actual handle binding remains an Agent obligation; no host middleware, required
  runtime analysis service, arbitrary SQL, new table or mail capability is added.
- Report format, calendars/trigger thresholds, business metrics and on-demand
  HTML behavior retain their existing meaning.

## Delivery and validation

Full package: `releases/pastdue-agent-v3.7.0.zip`; Skill package:
`releases/pastdue-analysis-skill-v3.7.0.zip`. Packaging is local, not deployment.
Preserve approved graph/source IDs and credentials outside the repository.

For3.7.0 deploy the graph extension first to a test graph, then Prompt and full
Skill after direct live EQL verification. Preserve approved IDs/source mappings.
See CURRENT_3_7_0.md for rollout and rollback. Prior3.6.3 was Prompt-only;
its graph-unchanged deployment instructions do not apply to this release.
See [design and rollout](docs/COMPOSABLE_ANALYSIS_3_5.md),
[API](docs/API_V3.md), [deployment](docs/DEPLOYMENT_V3.md) and
[evidence](docs/VALIDATION_V3.md). Actual receipts distinguish offline checks,
synthetic ClickHouse SQL, platform validation and live Agent acceptance.

Historical releases are not production acceptance. Use
`tests/fixtures/live-question-cases.json` for public combination/follow-up trials;
it is a specification, not evidence those trials passed. Prepare separate withheld
questions for unseen acceptance. Live performance, permissions and Agent behavior
remain unverified; current pages/CTEs do not guarantee atomic snapshots.

# Requested reports and Vendor scope — 3.6.0

Read for a report request (including `report` / `reprot` / 报告), one or several
named Vendors, or a follow-up changing report scope. Atlas supplies business facts.

## Resolve independent choices

Track invocation (scheduled monitoring, user request, explicit test), time basis,
Country, Vendor selection and presentation independently. Alert status describes
the facts; it authorizes automatic alert drilldown only. A user report request
already authorizes analysis when data is valid. Preserve actual trigger/freshness
and label “按需报告，未触发预警 / Requested report, no alert triggered” when applicable.
Keep TEST_FORCED for an explicit test invocation.

| Request | Default scope when context does not already specify it |
|---|---|
| “给我报告，即使没有预警” | Latest completed eligible snapshot pair; requested Countries, otherwise US/CA. TOP_N by Country contribution, including improvements/zero. |
| “US Vendor 64342 的情况” | CURRENT, that Vendor; metrics and Code/Source distribution. Comparison only if requested or retained in context. |
| “US 64342、75256 的报告” | Latest completed eligible pair, exact set; all readable requested Vendors regardless of sign. |
| “看这几家的当前情况” | CURRENT, exact set inherited from context; no daily Top10 restriction. |
| “这几家中恶化最多的两家” | Original set is the candidate population; compose ranking/select/expand via explore-v3. |

“报告” without a period uses the completed snapshot pair and states it. Explicit
“当前/实时” uses CURRENT and labels capture time. Clarify ambiguous Country/name
or “恶化” metric when it materially changes the answer. Preserve established scope
on follow-ups, including “改成报告”, “加一家”, and “不要预警筛选”.

## Completed-pair reports

1. Resolve actual dates with monitor for latest, or history-query/comparisons with
   start=end=target for an explicit target; this includes nontrigger dates. Reuse
   verified same-scope Country facts. Arbitrary explicit P/T uses explore
   PAIR/EXPLICIT: report-v2 requires the nearest previous eligible snapshot.
2. Discover report-v2-query/rows and read [its EQL](report-v2-query.eql). All nine
   inputs are explicit. Supply the inactive Vendor set as the string `[]`.
3. Bind Country Control, Vendor metrics, ETA summaries/checks and Vendor coverage
   from the same result. Read source pages and retained projections through
   result-reader.md. Continue at returned next-after-ordinal; count repeated
   control once. Scope, expected count and fingerprint must agree across pages.
4. Deliver report-format.md with actual trigger and selection scope. Account for
   each requested Vendor as matched, no readable data, failed or unfinished.
   Coverage rows are identity/status, never measurements. Completion requires
   the original set to be accounted for, not merely some ranking rows returned.

### report-v2 inputs and semantics

| Input | Meaning |
|---|---|
| country-code, target-snapshot-date, previous-snapshot-date, calendar-mode | Same validated completed pair as report-query; WEEKDAYS or CALENDAR_DAYS |
| selection-mode=TOP_N | Contribution ranks across all signs; default requested report uses top-n=10, vendor-codes-json="[]" |
| selection-mode=POSITIVE_TOP_N | Explicit positive-contributor selection or alert preset; top-n=1..100, vendor-codes-json="[]" |
| selection-mode=VENDOR_SET | Nonempty JSON array of positive numeric Int64 Vendor codes, deduplicated; top-n=0; single Vendor is a one-element set |
| page-after-ordinal | First page 0; then returned next-after-ordinal |
| page-size | 1..199 noncontrol rows; reports start at 50; live result bytes may require smaller pages |

`[]` in VENDOR_SET is INVALID_SELECTION, never all Vendors. Malformed JSON,
strings, nonpositive/fractional codes and Int64 overflow are invalid. Existing
report fields retain metric3.1.0 meanings. Global contribution-rank is computed
before selection and may have gaps in a displayed set. Country denominators
cover the whole Country. Positive contribution share is inapplicable to a
nonpositive Vendor even if a numeric placeholder is present.

VENDOR_COVERAGE has MATCHED or NO_DATA_IN_PAIR. The latter means no readable rows
in either date, including possible access filtering; it is not measured zero.
A Vendor present on one date follows existing report pair semantics on the other.
COUNTRY_CONTROL scope-status must be OK before metrics are usable. Invalid pair,
data, denominator, Vendor selection or page yields control without usable Vendor
facts. Positive-only empty selection is a valid empty report.

Source default200 bounds rows (control + at most199), not serialized bytes.
For a result-size refusal, one smaller-page/projection correction is appropriate.
Smaller pages alone do not reduce SQL aggregation cost for a timeout. Each source
continuation reruns the query; fingerprints detect ordinary drift, not transaction
isolation. Follow existing bounded recapture rules before combining pages.

### Existing deployments

If report-v2 is absent, POSITIVE_TOP_N and a one-element set can use discovered
report-query/rows (SINGLE_VENDOR, top-n=1). A finite set can use a deduped worklist
of SINGLE_VENDOR calls when feasible. Preserve common P/T and Country denominators,
reconcile Country controls and disclose separate captures. Six scopes is a cost
review. Comma lists are invalid for vendor-code; a known source timeout is not a
reason to retry the same expensive SQL for every Vendor. TOP_N across all signs
has no equivalent legacy report mode; compose complete explore facts when possible
or name the exact deployment gap.

## CURRENT and free-form Vendor questions

For an explicit Vendor set needing CURRENT counts, ratios and distributions with
optional org filters, prefer discovered current-v2-query/rows; read
[its complete EQL](current-v2-query.eql). Eight inputs: Country, nonempty numeric
vendor-codes-json, three org filters, denominator-scope, page-after-ordinal and
page-size. Empty org strings mean unrestricted; scopes are COUNTRY_ALL,
FILTERED_POPULATION, WITHIN_VENDOR. Source page rules match report-v2, with CONTROL
instead of COUNTRY_CONTROL; start CURRENT at100 (fewer fields), adapt to actual
bytes. FACT is joint Code×Source, VENDOR_COVERAGE carries
MATCHED / NO_READABLE_ROWS / EXCLUDED_BY_ORG. Only FACT has measured counts.
Vendor totals repeat on FACT: read one consistent value per Vendor, never sum the
repeated totals. Sum disjoint bucket counts to reconcile Vendor PD/Open. Ratio is
bucket PD / scoped Vendor Open; share uses the declared returned denominator.
Use vendor-past-due-ratio and vendor-past-due-share for Vendor-level percentages;
past-due-ratio/share belong to the joint bucket on that FACT row.
Country share preserves nationwide PD before Vendor/org filters. Zero denominator
returns null, not zero. Derive totals/marginals only from complete disjoint facts.
This route has one detail-view reference; its real latency still needs Atlas
validation. It supports neither history nor ranking/selection filters.

For a scope outside that capability, use discovered explore-v3-query/rows:
exact vendor-codes-json, selection-unit=VENDOR,
grain=VENDOR_CODE_SOURCE, ranking-metric=NONE, change-selection=ALL, top-n=0,
selection/expansion filters empty unless requested. This returns Vendor SELECTION
totals and joint FACT distribution in one logical query per Country. Read pages
and coverage. Derive marginal distributions only from complete disjoint joint
facts. Use documented denominators; legacy current-analysis and explore ratio semantics
differ. CURRENT has no observed previous period unless separately queried.

An explicit rank/filter changes those operations, not membership by accident.
Keep candidate denominators before Top N. PO/Owner/contact modules remain on
request. Follow report-format.md for applicable CURRENT modules. CURRENT facts
alone cannot fill the comparison HTML payload. HTML remains an explicit choice
under the existing handoff; “report” alone produces Markdown, and later HTML
reuses complete verified bindings.

# Requested reports and Vendor scope — 3.6.2

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
2. Choose by the requested selection and required evidence, not alert status:

   | Semantics | Default interface |
   |---|---|
   | DAILY/TEST/historical alert preset, or explicit POSITIVE_TOP_N with N=1..10 | Existing report-query/rows, selection-mode=POSITIVE_TOP_N, vendor-code=0 |
   | One explicit Vendor, any contribution sign, without extra coverage requirements | Existing report-query/rows, selection-mode=SINGLE_VENDOR, top-n=1, actual vendor-code |
   | TOP_N across all signs, exact multiple-Vendor set, positive N=11..100, or explicit set-coverage evidence | report-v2-query/rows |
   | Arbitrary explicit P/T, ranking/filtering beyond report semantics | Compose explore operations; preserve scope, denominators and requested modules |

   For the original interface use the complete projection in
   [structured-queries.eql](structured-queries.eql), retaining its seven scalar
   inputs. For v2 use [report-v2-query.eql](report-v2-query.eql), all nine inputs
   explicit; inactive vendor-codes-json is the string `[]`. Legacy report cannot
   supply v2 coverage statuses: choose v2 when those distinctions are required.
3. Read result-reader.md for the chosen interface. Original report uses its
   existing Control/Metrics/ETA typed reads; reuse fields already read. Paged v2
   uses needed typed projections or an optional full projection when output size
   permits. Include v2 metadata and coverage, follow real source cursors and fill
   only actual gaps. No mandatory full-wide-page or extra draft-before-next step.
4. Deliver report-format.md with actual trigger and selection scope. Account for
   every requested identity as matched, no readable data, failed or unfinished,
   using only the statuses the chosen interface actually returns. Coverage rows
   are identity/status, never measurements. No-alert never disables a valid
   report; all-sign TOP_N must never silently become POSITIVE_TOP_N.

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
For a result-size refusal, one evidence-based smaller-page/projection correction
is appropriate. A diagnostic page-size=1 is a one-page probe: restore the planned
production size before starting a full report, with its own capture/coverage.
Smaller pages alone do not reduce SQL aggregation cost for a timeout. Each source
continuation reruns the query; fingerprints detect ordinary drift, not transaction
isolation. Follow existing bounded recapture rules before combining pages.

### Equivalent alternatives when a capability is absent

The old interface is the normal default for the equivalent modes above, not just
a fallback after trying v2. If report-v2 is absent, a finite set can use a deduped worklist
of SINGLE_VENDOR calls when feasible. Preserve common P/T and Country denominators,
reconcile Country controls and disclose separate captures. Six scopes is a cost
review. Comma lists are invalid for vendor-code; a known source timeout is not a
reason to retry the same expensive SQL for every Vendor. TOP_N across all signs
has no equivalent legacy report mode; compose complete explore facts when possible
or name the exact deployment gap.

## CURRENT and free-form Vendor questions

Use discovered explore-v3-query/rows by default, preserving the existing CURRENT
path: exact vendor-codes-json, time-basis=CURRENT, selection-unit=VENDOR,
grain=VENDOR_CODE_SOURCE, ranking-metric=NONE, change-selection=ALL, top-n=0,
selection/expansion filters empty unless requested. Use the complete example in
[explore-v3-query.eql](explore-v3-query.eql), with every scalar input explicit.
SELECTION supplies Vendor totals; FACT supplies joint distributions. Read pages
and coverage under [explore-v3.md](explore-v3.md). Derive marginals only from
complete disjoint facts. Preserve the requested denominator and org filters.
Existing explore-v2/older explore remain usable only within equivalent scopes;
legacy current-analysis ratios and explore ratios have different semantics.
CURRENT has no observed previous period unless separately queried.

### Candidate current-v2 route

Keep current-v2-query/rows available for explicit-set CURRENT counts, ratios and
distributions, but do not switch ordinary requests to it solely because it is
newer or described as one scan. Evaluate it as an explicitly selected candidate
plan; promote it independently only after a same-scope live comparison. It
supports neither history nor ranking/selection filters. Use
[current-v2-query.eql](current-v2-query.eql): eight explicit inputs, nonempty
numeric vendor-codes-json, three org filters, denominator-scope and source paging.
Empty org strings mean unrestricted. COUNTRY_ALL, FILTERED_POPULATION and
WITHIN_VENDOR must match the requested semantics, not just the denominator name.
Start candidate pages at100 and adapt to actual bytes.

CONTROL describes scope; VENDOR_COVERAGE carries MATCHED / NO_READABLE_ROWS /
EXCLUDED_BY_ORG. Only FACT has measured counts. Vendor totals repeat on FACT:
read one consistent value per Vendor, never sum repeated totals. Sum disjoint
bucket counts to reconcile Vendor PD/Open. Ratio is bucket PD / scoped Vendor
Open; share uses the declared returned denominator. Use vendor-past-due-ratio and
vendor-past-due-share for Vendor percentages; past-due-ratio/share are bucket
percentages. Country share preserves nationwide PD before Vendor/org filters.
Zero denominator is null, not zero. Explore uses target-prefixed measurement
fields; bind each route's declared fields instead of mixing their schemas.

An explicit rank/filter changes those operations, not membership by accident.
Keep candidate denominators before Top N. PO/Owner/contact modules remain on
request. Follow report-format.md for applicable CURRENT modules. CURRENT facts
alone cannot fill the comparison HTML payload. HTML remains an explicit choice
under the existing handoff; “report” alone produces Markdown, and later HTML
reuses complete verified bindings.

# Default standard-report route

Agent 3.2.0 uses `pastdue.report-block-query/blocks` and
`pastdue.monitor-block-query/countries`; exact fields/identity/assembly are in
fact-block-contract.md. Existing raw report and monitor semantics below are retained
for structured review/history/interactive use, not an alternative standard layout.
Metric 3.1.0 remains unchanged; block template/publication is 3.2.0.

# Atlas data contract — 3.2.0

Confirm the live MCP schema before execution; these contracts do not prove a
particular environment is deployed. The development source is
`scripts/build_v31_contract.py` (REPORT_FIELDS, EXPLORE_INPUTS, EXPLORE_FIELDS).
It is not an Agent runtime dependency. New display-only block collections are additive. Inventory counts are not online acceptance.
Required new interfaces missing → deployment gap, never silent v3/v2 fallback.

## Flexible analysis: pastdue.explore-query/rows

All 20 entity inputs are explicit:

| Input | Values / semantics |
|---|---|
| country-code | Authorized Country; no implicit cross-Country union |
| time-basis | CURRENT / SNAPSHOT / PAIR / RANGE |
| target-snapshot-date | Exact completed eligible target for SNAPSHOT/PAIR |
| previous-snapshot-date | Exact previous for PAIR+EXPLICIT; PREVIOUS_AVAILABLE resolves it server-side |
| range-start-date, range-end-date | Inclusive target range for RANGE; 1..366 calendar dates, end before local today |
| calendar-mode | WEEKDAYS / CALENDAR_DAYS; default WEEKDAYS, explicit weekend override |
| pair-policy | PREVIOUS_AVAILABLE / EXPLICIT; exact explicit pair must never be substituted |
| grain | COUNTRY / VENDOR / VENDOR_CODE / VENDOR_SOURCE / VENDOR_CODE_SOURCE |
| vendor-code | Numeric Vendor; 0 = all, never a real Vendor row |
| eta-code-filter | Empty = none; trim-uppercase exact code, UNKNOWN for empty code |
| source-filter | Empty = none; trimmed exact raw source, case preserved; UNKNOWN for empty source |
| department-type-filter | Empty = none; trimmed case-insensitive exact mapping value |
| master-primary-filter, master-manager-filter | Empty = none; trimmed exact current mapping values |
| denominator-scope | COUNTRY_ALL / FILTERED_POPULATION / WITHIN_VENDOR |
| ranking-metric | NONE / PAST_DUE_COUNT / PAST_DUE_RATIO / PAST_DUE_SHARE / COUNT_CHANGE / RATIO_CHANGE / CONTRIBUTION_CHANGE |
| sort-direction | ASC / DESC |
| change-selection | ALL / POSITIVE / NEGATIVE / ZERO |
| top-n | 0 = all or integer 1..100; applies to grouped FACT rows, not automatically distinct Vendors |

Supply inactive date inputs as `1970-01-01` typed sentinels; they are NOT observed
snapshot dates. CURRENT returns today's local date as a capture label, not a
completed snapshot. Non-PAIR previous fields are placeholders and must not be
rendered as actual previous facts or meaningful changes. RANGE returns date-grain
stocks, not period sums; top-n is global over result rows, not per date.

Change ranking or non-ALL change-selection requires PAIR. WITHIN_VENDOR is invalid
at COUNTRY grain. With RATIO_CHANGE sign uses ratio delta; with
CONTRIBUTION_CHANGE sign uses contribution delta; otherwise sign uses count delta.
NONE uses deterministic date/Vendor/Code/Source ordering. Rank ties use target date,
Vendor, Code, Source; null rank metrics sort last. Null is not zero.

Rows use **pastdue.explore-fact**. Always project scope-key. One `record-type=CONTROL` plus `FACT` rows only when scope-status=OK:
- Control: scope-status, expected-record-count, row-ordinal and request descriptors.
  Statuses: OK / NO_DATA / DATA_ERROR / INVALID_SELECTION /
  UNSUPPORTED_COMBINATION / INVALID_DATE / INVALID_PAIR / INVALID_RANGE.
- Identity/descriptors: country-code, vendor-code, vendor-name,
  target-snapshot-date, previous-snapshot-date, calendar-mode, time-basis, grain,
  eta-code, eta-source, denominator-scope, metric-version, organization-basis,
  business-timezone, queried-at-local. scope-key encodes ALL required inputs, including filters/pair-policy. Retain
  original inputs with provenance; individual filter attributes are not echoed.
- Counts: target/previous-past-due-count, target/previous-open-count,
  target/previous-ratio-denominator, target/previous-share-denominator,
  target/previous-country-open, past-due-count-change, selection-rank,
  expected-record-count, row-ordinal.
- Decimals: target/previous-past-due-ratio, target/previous-past-due-share,
  past-due-count-relative-change, past-due-ratio-change, delta-country-contribution.

Require exactly one OK control before using FACT rows. Control OK with no FACT
rows is a verified empty selection only after complete pagination and expected
count validation; NO_DATA is not a normal business finding. Sparse control fields
that don't apply to its record-type are placeholders, not measurements.

Denominators are independent of ETA row filters. COUNTRY_ALL share denominator
is whole Country past due; FILTERED_POPULATION is Vendor+org-filtered population
past due before ETA filters; WITHIN_VENDOR is each scoped Vendor's past due before
ETA filters. Ratio denominator is whole Country Open at COUNTRY grain and whole
Vendor Open at Vendor grains, not bucket Open, not share denominator and not
changed by denominator-scope. `*-open-count` is grouped row Open, not necessarily
`*-ratio-denominator`. Org filters use deduplicated CURRENT_MAPPING even for
historical data; disclose that it is not historical ownership.

## Retained raw report audit: pastdue.report-query/rows

Same seven inputs as `pastdue.analysis-query`:
country-code, target-snapshot-date, previous-snapshot-date, calendar-mode,
selection-mode, top-n, vendor-code. POSITIVE_TOP_N: top-n=1..10, vendor-code=0;
SINGLE_VENDOR: actual Vendor, top-n=1, includes zero/negative contributions.
Unlike explore EXPLICIT, the report pair must be the nearest earlier available
eligible snapshot for the target; use explore for arbitrary earlier explicit pairs.

All rows use **pastdue.report-fact**, discriminated by `record-type`:

| record-type | Meaning / fields that matter |
|---|---|
| COUNTRY_CONTROL | Exactly one scope-status, selected-vendor-count, target/previous-country-open, target/previous-country-past-due-count, target/previous-country-ratio |
| VENDOR_METRICS | One per selected Vendor: contribution-rank, vendor-name, target/previous-past-due-count, past-due-count-change, target/previous-open-count, target/previous-vendor-ratio, target/previous-country-contribution, delta-country-contribution, positive-contribution-share, selected-positive-coverage |
| ETA_CODE_SUMMARY | dimension=code; compact full signed Code summary |
| ETA_SOURCE_SUMMARY | dimension=source; compact full signed Source summary |
| US_SOURCE_SUMMARY | dimension=us; Source buckets conditioned on Code=US, not marginal Source |
| VENDOR_CHECK | One per selected Vendor × code/source/us, including empty/nonrequired dimensions |

Common projected fields: scope-key, record-type, country-code, vendor-code,
target-snapshot-date, previous-snapshot-date, calendar-mode, selection-mode,
metric-version, scope-status, row-ordinal, expected-record-count,
business-timezone, queried-at-local. Check COUNTRY_CONTROL scope-status=OK before
claiming selection validity. Analysis scope errors remain INVALID_PAIR /
INVALID_SELECTION / INVALID_DENOMINATOR / DATA_ERROR / VENDOR_NOT_FOUND.

Summary fields: dimension, eta-value, row-kind, display-rank,
expected-bucket-count, other-bucket-count, previous/target-past-due-count,
past-due-count-change, past-due-count-relative-change,
delta-country-contribution, increase-total, decrease-total, net-change,
previous-total, target-total, counts-match, required.
`row-kind=BUCKET`, display-rank=1..5 are actual buckets; `row-kind=OTHER`, rank=6
is the server aggregate remainder, with blank eta-value. Render it as Other/其他;
never as UNKNOWN, never as an extra raw bucket. Other exists only for a remainder.

VENDOR_CHECK fields: dimension, required, counts-match, expected-bucket-count,
previous-total, target-total, previous-expected-total, target-expected-total,
increase-total, decrease-total, net-change. Code/Source expected totals are Vendor
PD; US expected totals are Code=US PD. Required=false is explicit nonapplicability,
not a missing check. Do not read sparse defaults on unrelated record types as facts.

Every report row has expected-record-count (INCLUDING COUNTRY_CONTROL) and a
unique row-ordinal. expected-bucket-count is RAW normalized bucket count, not
number of compact summary records. Validate BUCKET count + sum(OTHER
other-bucket-count) against it. The server computes all full-population metrics,
Top-5, Other and signed totals from shared facts. The Agent verifies, not rebuilds.

## Monitor, history and compatibility

`pastdue.monitor-query/countries`: calendar-mode, relative-change-threshold=0.10;
`pastdue.monitor-fact` contains actual pair, Country PD/Open/ratios, business/data
status, expected-target-date, target-lag-workdays, missing-workdays-between and gap.
`pastdue.history-query/comparisons` or `/trigger-events`: country-code,
calendar-mode, range-start-date, range-end-date, relative-change-threshold;
`pastdue.history-fact` gives eligible target comparisons/events. Previous may
precede range start. Every requested Country must be represented in the report.

Legacy analysis/current-analysis and contact/Owner/detail/mapping interfaces stay
compatible, but are not silent replacements for explore/report. Optional current
context remains on demand. No local graph-file reads at Agent runtime. Absent
live version/watermark is UNVERIFIED, never an invented transaction guarantee.

## Scope and capture provenance
Both fact namespaces include mandatory scope-key (string), the SQL JSON tuple
of ALL required inputs in declaration order, incorporated into row identity.
Always project and retain it; compare exact keys within the captured request/pages.
It preserves complete filter/selection/ranking scope and avoids identity collision;
it is NOT a source revision, watermark, immutable-snapshot or transaction token.
Country control PD/Open/ratios must reconcile to pinned monitor/history T/P facts
before combining captures. Drift is disclosed and affected comparisons unverified,
not silently reconciled by overwriting counts. Paired raw provenance is needed to
classify a hard same-scope DATA_CONFLICT rather than changing capture state.
queried-at-local is an actual capture timestamp and business-timezone is IANA;
trusted runtime supplies the instant's actual PST/PDT and UTC offset. If a local
fall-back timestamp is ambiguous and no trusted instant/offset exists, disclose
the ambiguity rather than choosing an offset. No implied cross-query transaction.

### Capture and semantic-comparison safeguard
Show available queried-at-local and business-timezone; derive actual PST/PDT offset
only from its trusted corresponding instant/offset, never a guessed tool-capture or
delivery time. Without that evidence retain the returned local timestamp/IANA and
label offset UNVERIFIED. Legacy queried-at-utc keeps UTC semantics unchanged; the
new queried-at-local field never repurposes an old UTC attribute. DATA_CONFLICT
compares COMMON semantic fact fields in paired raw same-scope responses, not whole
payload shape: projection-only fields, handles or timestamps differing aren't a
conflict, and an unprojected field is unknown, not contradictory.

## Governed result-handle materialization exception
“No arbitrary SQL” prohibits unconstrained/source/database SQL, not reading an
Atlas-retained result. execute_eql may return only a handle. Discover tool schema,
inspect actual analyze_result columns, then use a read-only SELECT of explicit
discovered columns FROM data ORDER BY discovered row-ordinal, bound to that exact
request/environment/capture handle. Include all required identity/metric/check
columns, preserve values and consume every page. No presumed structured-read tool,
SELECT *, other tables, joins, writes, dropped facts, or recomputing metric,
denominator, selection or rank. Materialization doesn't add a logical fact slot.
See atlas-query-playbook.md for the narrow allowed shape and discovery procedure.

## Report row binding

The flat `pastdue.report-fact` schema is a tagged union, NOT one interchangeable
metric row. Identical field names have different meanings by record-type. Before
rendering, partition returned rows by the following selectors; keep their actual
handle and row-ordinal as provenance. This is an Agent procedure, not a new runtime
service, required tool or claim of machine enforcement.

| Consumer | Required selector | Bound fields / meaning |
|---|---|---|
| Country cross-check | COUNTRY_CONTROL | Country PD/Open/ratio, selected-vendor-count, expected-record-count |
| Vendor overview AND detailed metrics | VENDOR_METRICS + exact Country/Vendor/scope | previous/target-past-due-count, previous/target-open-count, previous/target-vendor-ratio, contribution-rank, previous/target-country-contribution, delta-country-contribution, positive-contribution-share |
| Code table | ETA_CODE_SUMMARY + dimension=code + same Vendor/scope | bucket counts, count change, relative change, delta-country-contribution, row-kind, display-rank |
| Source table | ETA_SOURCE_SUMMARY + dimension=source + same Vendor/scope | independent marginal Source bucket fields |
| Internal conditional-source evidence (optional detail on request) | US_SOURCE_SUMMARY + dimension=us + same Vendor/scope | Source counts conditioned on Code=US, ANY Country |
| Signed totals and applicability | VENDOR_CHECK + same Vendor/scope + exact dimension | required, counts-match, previous/target-total, previous/target-expected-total, increase-total, decrease-total, net-change, expected-bucket-count |
| Selected coverage | agreeing VENDOR_METRICS metadata in same selection | selected-positive-coverage, NOT sum of this repeated field |

Scope includes scope-key, Country, actual P/T dates, calendar, selection mode and
metric version, with exposed environment provenance retained. Maintain one bound
Vendor entry per selected VENDOR_METRICS row, in returned contribution-rank order.
Attach code/source/us summaries and checks by identity, never adjacency or totals.
Duplicate/missing metrics or check rows are errors, not “use first/last”. A summary
failure must not overwrite Vendor metrics or unrelated dimensions.

`target-country-contribution` is a level, NOT a change. Only
`delta-country-contribution` fills contribution-change cells. The value is a ratio;
percentage-point formatting multiplies by 100 once. Vendor ratio uses Vendor Open;
Country contribution uses Country Open. Do not fill one from another field.
Vendor totals cannot be read from a SH or US bucket just because those rows share
previous/target-past-due-count columns. Sparse defaults on unrelated types are
never fallback values. Render overview and detail from the same bound metrics.

Code and Source must each independently reconcile to both Vendor PD totals. US
must reconcile to Code=US (possibly hidden in Code Other), not all Source. Use
per-Vendor dimension=us check in CA as well as US. Positive visible Code=US with
required=false, or differing joint totals, is a reconciliation failure affecting
US joint interpretation; do not call it not applicable or erase valid Code/Source.
If raw Code=US is hidden in Other, verified server checks are the evidence boundary,
not proof of an independent raw-bucket audit. No marginal-distribution stitching.

Use independent arithmetic only as a consistency check on returned counts/rates,
never to silently replace a missing or inconsistent authoritative field. A failed
comparison is explicitly UNVERIFIED/failed at the narrowest affected scope. Keep
raw rows and request references; numeric disagreement inside a single response
is a reconciliation failure, not proof of two-response DATA_CONFLICT.


Agent display contract 3.1.3 hides the dedicated conditional-source table in
standard reports; the facts/checks above remain unchanged. Material business
interpretation is integrated into Country Vendor analysis immediately after Vendor
ranking, with exact Vendor identity retained. This is not a new Country-wide
aggregate or permission to infer associations from independent distributions.

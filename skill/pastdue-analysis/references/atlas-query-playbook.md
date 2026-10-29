# Atlas query playbook — 3.3.6

Retained reads copy the `analyze_result` object and the SQL in
[result-reader.md](result-reader.md).
EQL stays `{:entity ... :query ...}` inside `execute_eql.query`.

## Choose the fact capability

Standard: monitor/history → pinned report-query/rows. Interactive: explore-query/rows.
The retained 3.2 long-text endpoints are compatibility-only. Read the active
section, not all examples. Use live list_entities/list_attributes/tool schemas and
reuse discovery within the same environment. Dates/Vendors below are examples,
not run defaults. EQL wrapper is `{:entity ... :query ...}`, never Datalog.

The examples use plain joins accepted by the observed Studio. They are NOT a
verified paging API. Report3.3 source default/max is1000; compact Top10 bound is221. Other
collections retain default200/max1000. Older report deployments may default200
and cut off a221-row report unless supported source paging/limit is supplied.
MCP source paging/sorting must use its actually supported schema. Do not paste
parameterized joins into Studio after semantic.studio/query-shape rejection.
Incomplete source results cannot be repaired by paging their truncated handle.

Complete plain-join projections for monitor/report/history are in
[structured-queries.eql](structured-queries.eql); below are readable examples.
Read the complete example for HISTORICAL_FULL/explicit-target TEST: the short
event-list projection below alone cannot support Country count/ratio cross-checks.

## Monitor (standard route)
```clojure
{:entity {:pastdue.monitor-query/calendar-mode "WEEKDAYS"
          :pastdue.monitor-query/relative-change-threshold 0.10}
 :query [{:pastdue.monitor-query/countries
           [:pastdue.monitor-fact/country-code
            :pastdue.monitor-fact/target-snapshot-date
            :pastdue.monitor-fact/previous-snapshot-date
            :pastdue.monitor-fact/calendar-mode
            :pastdue.monitor-fact/target-past-due-count
            :pastdue.monitor-fact/previous-past-due-count
            :pastdue.monitor-fact/target-open-count
            :pastdue.monitor-fact/previous-open-count
            :pastdue.monitor-fact/target-past-due-ratio
            :pastdue.monitor-fact/previous-past-due-ratio
            :pastdue.monitor-fact/relative-ratio-change
            :pastdue.monitor-fact/percentage-point-change
            :pastdue.monitor-fact/business-status
            :pastdue.monitor-fact/data-status
            :pastdue.monitor-fact/expected-target-date
            :pastdue.monitor-fact/target-lag-workdays
            :pastdue.monitor-fact/missing-workdays-between
            :pastdue.monitor-fact/has-comparable-snapshots
            :pastdue.monitor-fact/snapshot-gap-days]}
 ]}
```
Consume complete Country facts before locally selecting authorized US/CA. Use
trusted runtime America/Los_Angeles today, not latest snapshot as a clock.
Normal daily only drills TRIGGERED/ZERO_BASELINE_INCREASE with valid comparable
positive Open and data OK/DELAYED. Test forces applicable valid pairs, warns stale.
An explicitly requested test/report target uses history-query/comparisons with
range-start-date=range-end-date=target and the same full Country projection.
This returns its actual prior eligible snapshot even if the target didn't trigger.
Do not use trigger-events to find nontrigger test pairs or silently substitute
the latest monitor dates. An arbitrary explicit P/T uses explore PAIR/EXPLICIT.

## Report (standard route, seven inputs)
```clojure
{:entity {:pastdue.report-query/country-code "CA"
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
            :pastdue.report-fact/vendor-name
            :pastdue.report-fact/target-snapshot-date
            :pastdue.report-fact/previous-snapshot-date
            :pastdue.report-fact/calendar-mode
            :pastdue.report-fact/selection-mode
            :pastdue.report-fact/metric-version
            :pastdue.report-fact/business-timezone
            :pastdue.report-fact/queried-at-local
            :pastdue.report-fact/scope-status
            :pastdue.report-fact/dimension
            :pastdue.report-fact/eta-value
            :pastdue.report-fact/row-kind
            :pastdue.report-fact/contribution-rank
            :pastdue.report-fact/selected-vendor-count
            :pastdue.report-fact/target-past-due-count
            :pastdue.report-fact/previous-past-due-count
            :pastdue.report-fact/past-due-count-change
            :pastdue.report-fact/target-open-count
            :pastdue.report-fact/previous-open-count
            :pastdue.report-fact/target-country-open
            :pastdue.report-fact/previous-country-open
            :pastdue.report-fact/target-country-past-due-count
            :pastdue.report-fact/previous-country-past-due-count
            :pastdue.report-fact/target-country-ratio
            :pastdue.report-fact/previous-country-ratio
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
            :pastdue.report-fact/counts-match
            :pastdue.report-fact/required
            :pastdue.report-fact/past-due-count-relative-change
            :pastdue.report-fact/expected-record-count
            :pastdue.report-fact/row-ordinal]}
 ]}
```
Read one COUNTRY_CONTROL before consuming metrics; it need not be physically
first. Then group VENDOR_METRICS and summaries by Country+Vendor+pair, and
VENDOR_CHECK by dimension=code/source/us. BUCKET rank≤5 and OTHER rank6 are
server summaries. expected-record-count includes the control; raw bucket
coverage = BUCKET rows + Other's other-bucket-count, not summary row count.
Required counts-match/total/expected-total checks are mandatory even when empty.
Reconcile COUNTRY_CONTROL target/previous-country-past-due-count, -open and
-ratio to the pinned monitor/history facts before combining captures. Drift is
explicit PARTIAL/UNVERIFIED unless paired raw evidence proves a hard conflict;
queried-at-local is capture evidence, never a cross-query transaction guarantee.
US summary is already joint Code=US×Source for EVERY Country. Do not call separate legacy dimension
collections. SINGLE_VENDOR changes selection-mode, actual vendor-code and top-n=1;
it has no positive-only filter. Pin nearest-eligible P from monitor/history.

## Flexible analysis: all 20 inputs, explicit denominator and complete projection
This example asks for all current Vendor×Code shares within each Vendor.
```clojure
{:entity {:pastdue.explore-query/country-code "CA"
          :pastdue.explore-query/time-basis "CURRENT"
          :pastdue.explore-query/target-snapshot-date "1970-01-01"
          :pastdue.explore-query/previous-snapshot-date "1970-01-01"
          :pastdue.explore-query/range-start-date "1970-01-01"
          :pastdue.explore-query/range-end-date "1970-01-01"
          :pastdue.explore-query/calendar-mode "WEEKDAYS"
          :pastdue.explore-query/pair-policy "PREVIOUS_AVAILABLE"
          :pastdue.explore-query/grain "VENDOR_CODE"
          :pastdue.explore-query/vendor-code 0
          :pastdue.explore-query/eta-code-filter ""
          :pastdue.explore-query/source-filter ""
          :pastdue.explore-query/department-type-filter ""
          :pastdue.explore-query/master-primary-filter ""
          :pastdue.explore-query/master-manager-filter ""
          :pastdue.explore-query/denominator-scope "WITHIN_VENDOR"
          :pastdue.explore-query/ranking-metric "NONE"
          :pastdue.explore-query/sort-direction "ASC"
          :pastdue.explore-query/change-selection "ALL"
          :pastdue.explore-query/top-n 0}
 :query [{:pastdue.explore-query/rows
           [:pastdue.explore-fact/scope-key
            :pastdue.explore-fact/record-type
            :pastdue.explore-fact/scope-status
            :pastdue.explore-fact/country-code
            :pastdue.explore-fact/vendor-code
            :pastdue.explore-fact/vendor-name
            :pastdue.explore-fact/target-snapshot-date
            :pastdue.explore-fact/previous-snapshot-date
            :pastdue.explore-fact/calendar-mode
            :pastdue.explore-fact/time-basis
            :pastdue.explore-fact/grain
            :pastdue.explore-fact/eta-code
            :pastdue.explore-fact/eta-source
            :pastdue.explore-fact/denominator-scope
            :pastdue.explore-fact/metric-version
            :pastdue.explore-fact/organization-basis
            :pastdue.explore-fact/business-timezone
            :pastdue.explore-fact/queried-at-local
            :pastdue.explore-fact/target-past-due-count
            :pastdue.explore-fact/previous-past-due-count
            :pastdue.explore-fact/target-open-count
            :pastdue.explore-fact/previous-open-count
            :pastdue.explore-fact/target-ratio-denominator
            :pastdue.explore-fact/previous-ratio-denominator
            :pastdue.explore-fact/target-share-denominator
            :pastdue.explore-fact/previous-share-denominator
            :pastdue.explore-fact/target-country-open
            :pastdue.explore-fact/previous-country-open
            :pastdue.explore-fact/past-due-count-change
            :pastdue.explore-fact/target-past-due-ratio
            :pastdue.explore-fact/previous-past-due-ratio
            :pastdue.explore-fact/target-past-due-share
            :pastdue.explore-fact/previous-past-due-share
            :pastdue.explore-fact/past-due-count-relative-change
            :pastdue.explore-fact/past-due-ratio-change
            :pastdue.explore-fact/delta-country-contribution
            :pastdue.explore-fact/selection-rank
            :pastdue.explore-fact/expected-record-count
            :pastdue.explore-fact/row-ordinal]}
 ]}
```
One CONTROL plus FACT rows, with expected-record-count including CONTROL.
Non-PAIR previous date=1970-01-01 and previous counts are placeholders, not a
zero baseline to diagnose. Sparse control default values are not observations.
Use returned business-timezone/queried-at-local and actual DST label/offset from
trusted runtime; an ambiguous fall-back local timestamp without its instant/offset
must not be resolved by guessing. Always project scope-key: JSON tuple of all
required inputs, in declaration order. Retain it with original inputs, check exact
consistency across captured rows/pages; it is not a source revision or watermark.

Resolve other requests by changing the input entity above, preserving all 20
inputs and the same explicit projection (no wildcard or imaginary fields):

| Intent | Changed inputs |
|---|---|
| Current count Top 10 | grain=VENDOR, denominator-scope=COUNTRY_ALL, ranking-metric=PAST_DUE_COUNT, sort-direction=DESC, top-n=10 |
| Single snapshot | time-basis=SNAPSHOT, target-snapshot-date=2026-10-26; keep inactive previous/range sentinels |
| Exact two dates, declining counts | time-basis=PAIR, target=2026-10-26, previous=2026-10-22, pair-policy=EXPLICIT, grain=VENDOR, denominator=COUNTRY_ALL, ranking=COUNT_CHANGE, direction=ASC, change-selection=NEGATIVE, top-n=10 (use full input names above) |
| Nearest previous, contribution growth | time-basis=PAIR, target-snapshot-date=2026-10-26, previous sentinel, pair-policy=PREVIOUS_AVAILABLE, grain=VENDOR, denominator-scope=COUNTRY_ALL, ranking-metric=CONTRIBUTION_CHANGE, sort-direction=DESC, change-selection=POSITIVE, top-n=10 |
| Daily Country stocks | time-basis=RANGE, range-start-date=2026-10-19, range-end-date=2026-10-26, grain=COUNTRY, denominator-scope=COUNTRY_ALL, ranking-metric=NONE, top-n=0 |
| Vendor joint composition | grain=VENDOR_CODE_SOURCE, vendor-code=31420, denominator-scope=WITHIN_VENDOR, ranking-metric=NONE, top-n=0 |

These are illustrative input substitutions, not alternate EQL property aliases.
Use data-contract's exact enums. PAIR-only change selection is not meaningful on
CURRENT/SNAPSHOT/RANGE. WITHIN_VENDOR+COUNTRY is invalid, not silently reinterpreted.

## Historical events
```clojure
{:entity {:pastdue.history-query/country-code "US"
          :pastdue.history-query/calendar-mode "WEEKDAYS"
          :pastdue.history-query/range-start-date "2026-10-01"
          :pastdue.history-query/range-end-date "2026-10-26"
          :pastdue.history-query/relative-change-threshold 0.10}
 :query [{:pastdue.history-query/trigger-events
           [:pastdue.history-fact/country-code
            :pastdue.history-fact/target-snapshot-date
            :pastdue.history-fact/previous-snapshot-date
            :pastdue.history-fact/calendar-mode
            :pastdue.history-fact/business-status
            :pastdue.history-fact/data-status
            :pastdue.history-fact/relative-ratio-change]}
 ]}
```
For full historical reports project the discovered Country counts/Open/ratio and
quality fields just as in monitor, in the same history request. Choose latest
actual event per Country after all pages, not first row. Events-only stops here.


## Read layer 1: source collection coverage

Project expected-record-count, row-ordinal, scope-key and full identity for report/
explore. Use resolver stable ordinal ascending; report3.3 default/max1000, explore default200/max1000. Discover actual
source paging syntax before use; don't guess an EQL/Studio parameter shape. Keep
request scope/projection/order unchanged across pages. Expected counts must agree;
unique identities/ordinals across pages must cover the expected count. A retained
handle can be a single source page, not the full source population.

If the host has no supported way to fetch remaining source records, state the
specific source gap; don't mark a short final preview as complete or requery each
Vendor. The report3.3 default1000 (or supported explicit source limit1000) can contain a compact Top10 report, but does
not remove result-reader row/cell limits or guarantee cheap source execution.
Monitor/history lack expected-record-count/row-ordinal in their actual schema;
use actual source pagination/coverage metadata and discovered stable identity/order,
not invented fields. An incomplete history list cannot establish the latest event.

## Read layer 2: retained-result materialization

The analyze_result object and the two report statements that returned rows are
in result-reader.md. Use those. The SQL examples below are the same partitions
if a reader needs a narrower page; they are not a second argument schema.

1. Bind the exact handle from the actual execute response. Inspect actual flattened
   columns. Schema-only responses aren't fact retrieval. If linkage is missing or
   wrong, stop dependent use; don't obtain a plausible handle from memory.
2. Prefer explicit typed columns, no Markdown, diagnostic prose or embedded arrays.
   Read control/metrics, then checks, then Code, Source and conditional US summaries
   in type-specific projections from the SAME retained result. Each contains the
   actual scope, Country, Vendor, P/T, record type, dimension, ordinal and total;
   keep scope-status and metric-version in every partition as well.
   Control/metrics additionally need dates/calendar/version/status, selected count,
   Country totals, Vendor PD/Open/ratios/contributions/rank/share/coverage. Checks
   need required, counts-match, bucket counts, actual/expected P/T totals and signed
   totals. Summary groups need eta-value,row-kind,display-rank,represented Other
   count, bucket count, P/T PD,delta,relative-change,delta-country-contribution.
3. Lossless type partitions and bounded page windows are permitted over `data`
   ONLY. This is retained-result reading, not source SQL or a new fact request.
   The disjoint partitions must collectively cover all returned record types/rows.
   Any unexpected type must be read and reported, not discarded. Keep a coverage
   ledger of raw identities/ordinals; per-group expected source count remains the
   whole source count, not that group's count. Never compare one partition's length
   to the whole result and call it incomplete without considering the union.
4. Inspect BOTH row and cell truncation metadata on every read. `truncated?=false`
   or result-complete=true alone is insufficient. Start bounded (e.g. ≤40 rows),
   use actual supported paging to continue; don't assume a fixed preview cap.
   Fewer columns won't fix a long string cell. Affected names remain display-limited;
   affected identities/metrics remain unverified. Do not turn cut-off text into facts.
5. Stored full values may still exist when a preview is truncated. Use only a
   discovered supported full-value read/export if available. No invented substring,
   length override, raw export or local script. If unavailable, report the precise
   affected field/scope. No retries of giant Markdown blocks.

Illustrative retained-only SQL, ONLY after discovering these actual column names
and confirming reader support for WHERE/LIMIT/OFFSET. These are NOT ClickHouse
queries, NOT proven host syntax, and NOT a full report projection:

Control+metrics partition (use the exact same handle for the following partitions):

```sql
SELECT "scope_key", "scope_status", "metric_version", "record_type", "country_code",
       "vendor_code", "vendor_name", "previous_snapshot_date", "target_snapshot_date",
       "calendar_mode", "selection_mode", "business_timezone", "queried_at_local",
       "selected_vendor_count", "contribution_rank", "previous_past_due_count",
       "target_past_due_count", "past_due_count_change", "previous_open_count",
       "target_open_count", "previous_vendor_ratio", "target_vendor_ratio",
       "previous_country_open", "target_country_open",
       "previous_country_past_due_count", "target_country_past_due_count",
       "previous_country_ratio", "target_country_ratio", "previous_country_contribution",
       "target_country_contribution", "delta_country_contribution",
       "positive_contribution_share", "selected_positive_coverage",
       "row_ordinal", "expected_record_count"
FROM data
WHERE "record_type" IN ('COUNTRY_CONTROL', 'VENDOR_METRICS')
ORDER BY "row_ordinal" ASC
LIMIT 40 OFFSET 0
```

Check partition (don't replace required=false with a failed result):

```sql
SELECT "scope_key", "scope_status", "metric_version", "record_type", "country_code",
       "vendor_code", "previous_snapshot_date", "target_snapshot_date", "dimension",
       "required", "counts_match", "expected_bucket_count", "previous_total",
       "target_total", "previous_expected_total", "target_expected_total",
       "increase_total", "decrease_total", "net_change", "row_ordinal", "expected_record_count"
FROM data
WHERE "record_type" = 'VENDOR_CHECK'
ORDER BY "row_ordinal" ASC
LIMIT 40 OFFSET 0
```

Code partition (repeat with ETA_SOURCE_SUMMARY and US_SOURCE_SUMMARY as separate
disjoint partitions; preserve columns/handle, read further supported pages as needed):

```sql
SELECT "scope_key", "scope_status", "metric_version", "record_type", "country_code", "vendor_code",
       "previous_snapshot_date", "target_snapshot_date", "dimension",
       "eta_value", "row_kind", "display_rank", "other_bucket_count",
       "expected_bucket_count", "previous_past_due_count", "target_past_due_count",
       "past_due_count_change", "past_due_count_relative_change",
       "delta_country_contribution", "row_ordinal", "expected_record_count"
FROM data
WHERE "record_type" = 'ETA_CODE_SUMMARY'
ORDER BY "row_ordinal" ASC
LIMIT 40 OFFSET 0
```

Source/ETA/rank predicates are NOT allowed here. WHERE may partition only declared
record types or bounded stable row-ordinal windows, always with accounted union.
No metric expressions, aggregation, window functions, joins, other tables, writes,
SELECT *, substring recovery guesses or source/denominator/rank recomputation.
Values must retain original units. Quote actual discovered identifiers; don't
assume underscores. Use native supported reader paging instead where available.
If partition/paging syntax isn't supported, retain the same data projection and
use discovered native paging; if no complete route exists, stop that scope as a
transport limitation rather than substituting a different analysis interface.
The five partitions above cover exactly the six declared report record types.
These SQL examples are reading shapes, not executed evidence. If a source response
contains another type, account for and inspect it before claiming complete coverage.

## Binding, budgets and failures

Interpret record-type before fields: VENDOR_METRICS holds Vendor totals; summary
rows hold bucket counts. See data-contract.md binding table. Check dimension=us
for ANY Country, not only Country US; joint summaries never come from stitching
marginal Code/Source rows. Other stays a mixed remainder. A required=false zero
joint check is not failed. Read all applicable checks before Country diagnosis.

One justified correction retry per logical request; pagination isn't a retry.
Changing source limit/projection does not fix cancelled aggregation. Preserve
independent valid facts; nested errors count even if outer isError=false.
INTERACTIVE: ≤6 different logical fact requests; discovery/result reads/paging
excluded. Presets have no six-request cap, but optional/history breadth is explicit.
No per-Vendor fallback storms. Complete rows/checks do not certify final text.

Capture labels use returned America/Los_Angeles; actual PST/PDT+offset only with
trusted instant evidence. Date pinning is not a transaction. Compare only common
same-scope semantic fields, not projection shape or capture timestamps. Error
classification and delivery checks live in completion-contract.md.

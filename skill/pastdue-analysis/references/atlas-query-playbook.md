# Atlas v3.1 query playbook — manually maintained examples

## Discovery and interaction budget
Discover live entities, inputs, attributes and collection paging with Atlas MCP
list_entities/list_attributes. Copy exact namespaces from the schema; reuse that
knowledge in the same environment/version. EQL is `{:entity ... :query ...}`, not
Datalog. Missing explore/report v3.1 → deployment gap; never silent legacy fallback.
These examples were manually rewritten against EXPLORE_INPUTS, EXPLORE_FIELDS and
REPORT_FIELDS; they are NOT release-generated templates or observed live results.
All dates/Vendors below are examples, never runtime defaults.

For each **INTERACTIVE question only**, at most **6 DIFFERENT LOGICAL FACT
REQUESTS**. Discovery, materialization and pagination are not logical facts;
retries/corrections of the same request stay in its existing slot. A different
substantive fact scope is a separate slot. Read schema and materialize all needed
results without treating those transport steps as extra facts. For example, one
explore scope plus a distinct scoped composition query is two fact requests,
regardless of discovery/read/page calls.

Daily/history/test REPORT PRESETS have **no six-request cap**. Attempt monitor/
history and every applicable Country report with all selected Vendors and checks.
Use one tagged report per Country/event, never separate calls per Vendor/dimension.
Cache valid discovery, batch where supported, and do not invent metadata batching.
Explicitly bound broad historical or optional user scope if necessary and disclose
what was not executed; never silently skip mandatory preset work.

## Transport boundaries (3.1.1 correction)
These parameterized examples are MCP execute_eql examples, NOT automatically
valid Studio POST /api/pathom examples. In the tested Studio, use plain joins
`:query [{:collection [:fields]}]`; parameterized `({…} {:limit …})` was rejected
with semantic.studio/query-shape. Plain joins use resolver defaults and may be
truncated; never assert full coverage without expected counts. Discover actual
paging support in each host instead of guessing a Studio parameter schema.

Start monitor → materialize its EXACT returned handle → validate association →
report US → validate → report CA serially. Only consider independent concurrency
after reliable per-request handle association is established. If execute returned
handle A, analyze_result MUST NOT use B from memory/examples/another capture;
stop that scope and disclose ASSOCIATION_ERROR. Missing linkage is UNVERIFIED,
not a verified monitor. Keep full required field projection on a correction.
Code 394 QUERY_WAS_CANCELLED proves cancellation, not its initiator. Reducing
limit/projection does not fix source aggregation; do not spend a retry on it
without evidence. Do not drop delta-country-contribution to attempt a repair.

## Monitor: one request supplies both Countries
```clojure
{:entity {:pastdue.monitor-query/calendar-mode "WEEKDAYS"
          :pastdue.monitor-query/relative-change-threshold 0.10}
 :query [({:pastdue.monitor-query/countries
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
          {:limit 200 :offset 0})]}
```
Consume complete Country facts before locally selecting authorized US/CA. Use
trusted runtime America/Los_Angeles today, not latest snapshot as a clock.
Normal daily only drills TRIGGERED/ZERO_BASELINE_INCREASE with valid comparable
positive Open and data OK/DELAYED. Test forces applicable valid pairs, warns stale.

## Report preset: one seven-input request, all tagged facts
```clojure
{:entity {:pastdue.report-query/country-code "CA"
          :pastdue.report-query/target-snapshot-date "2026-10-26"
          :pastdue.report-query/previous-snapshot-date "2026-10-23"
          :pastdue.report-query/calendar-mode "WEEKDAYS"
          :pastdue.report-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.report-query/top-n 10
          :pastdue.report-query/vendor-code 0}
 :query [({:pastdue.report-query/rows
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
          {:limit 200 :offset 0})]}
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
 :query [({:pastdue.explore-query/rows
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
          {:limit 200 :offset 0})]}
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
 :query [({:pastdue.history-query/trigger-events
           [:pastdue.history-fact/country-code
            :pastdue.history-fact/target-snapshot-date
            :pastdue.history-fact/previous-snapshot-date
            :pastdue.history-fact/calendar-mode
            :pastdue.history-fact/business-status
            :pastdue.history-fact/data-status
            :pastdue.history-fact/relative-ratio-change]}
          {:limit 200 :offset 0})]}
```
For full historical reports project the discovered Country counts/Open/ratio and
quality fields just as in monitor, in the same history request. Choose latest
actual event per Country after all pages, not first row. Events-only stops here.

## Pages, handles, errors and evidence
- Use discovered resolver ordering row-ordinal ASC, page default 200/max 1000.
  Increase offset by returned rows, retain exact request/projection/order. Never
  apply Top-5 as retrieval limit. Require exact expected-record-count and unique
  identities, not just a short last page.
- execute_eql may return ONLY a retained result handle, not rows. Discover actual
  tool schemas; do not assume a separate structured-read tool exists. Bind the
  handle to this exact request/environment/capture. analyze_result without sql may
  expose only schema: inspect actual flattened columns FIRST, then materialize via
  analyze_result(sql=...) with a narrow read-only SELECT of explicit discovered
  columns FROM data ORDER BY the discovered row-ordinal column. It reads the
  retained result only, not a database source, and is not another logical fact slot.
- Allowed materialization shape (SQL identifiers must match discovered columns):
  SELECT "scope_key", "record_type", "country_code", "vendor_code", "row_ordinal"
  FROM data ORDER BY "row_ordinal" ASC
  This is an illustrative shape, NOT a sufficient report projection: explicitly
  select ALL discovered identity/metric/check columns required by the EQL projection.
  Never assume underscore aliases exist; use the actual quoted column identifiers.
  If a collection lacks row-ordinal, preserve its discovered deterministic order.
  Read every materialized page; materialization doesn't redefine resolver coverage.
- Prohibit direct/source/database SQL, other tables, joins, aggregate/window or
  computed metric expressions, denominator/rank/selection changes, extra WHERE
  filtering that drops facts, DDL/DML/data writes and cross-handle mixing. No
  SELECT * or local Python reconstruction. Keep original values and units; avoid
  flattening cross-products. Unknown columns → inspect schema, never guess names.
- Retain raw response and handle provenance. Avoid flattened nested cross-products;
  never infer identities from a row number. Expired/foreign handles are invalid.
- Inspect nested errors. One corrected retry in the same logical slot; no guessed roots,
  permissions bypass, per-Vendor calls or legacy substitutions. Pagination drift
  is PARTIAL; a same-request restart consumes the retry allowance, not another
  logical fact slot. A new substantive fact scope is a different request.
- DATA_CONFLICT requires paired raw contradictory same-scope facts. Wrong result
  attachment is ASSOCIATION_ERROR; false rendering is REPORT_ERROR. Preserve both
  raw versions before alleging server conflict; missing provenance is UNVERIFIED.
  See completion-contract.md for quarantine, independent dimensions and outcomes.

### Capture and semantic-comparison safeguard
Show available queried-at-local and business-timezone; derive actual PST/PDT offset
only from its trusted corresponding instant/offset, never a guessed tool-capture or
delivery time. Without that evidence retain the returned local timestamp/IANA and
label offset UNVERIFIED. Legacy queried-at-utc keeps UTC semantics unchanged; the
new queried-at-local field never repurposes an old UTC attribute. DATA_CONFLICT
compares COMMON semantic fact fields in paired raw same-scope responses, not whole
payload shape: projection-only fields, handles or timestamps differing aren't a
conflict, and an unprojected field is unknown, not contradictory.

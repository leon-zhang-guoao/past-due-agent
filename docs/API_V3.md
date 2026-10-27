# API v3 and compatibility

Application release 3.0.0, existing Atlas semantic schema 2.0.0. Discover actual
inputs/fields at runtime. No new protocol or arbitrary SQL tool.

| Request entity | Collection | Inputs |
|---|---|---|
| pastdue.monitor-query | countries | calendar-mode, relative-change-threshold |
| pastdue.history-query | comparisons / trigger-events | country-code, calendar-mode, range-start-date, range-end-date, relative-change-threshold |
| pastdue.analysis-query | scope-status / vendor-comparisons / eta-code-changes / eta-source-changes / us-source-changes / vendor-checks | country-code, target-snapshot-date, previous-snapshot-date, calendar-mode, selection-mode, top-n, vendor-code |
| pastdue.current-analysis-query | rows | country-code, grain, vendor-code-filter, department-type-filter, master-primary-filter, master-manager-filter, denominator-scope |

Calendar: WEEKDAYS or CALENDAR_DAYS. Selection POSITIVE_TOP_N requires 1..10;
Vendor=0 sentinel ignored; SINGLE_VENDOR uses actual Vendor and top-n=1.
Current grains VENDOR/VENDOR_CODE/VENDOR_SOURCE. Empty current filters mean none.
Denominator COUNTRY_ALL or FILTERED_POPULATION, independent of row filtering.

`scope-status` returns exactly one control row: OK / INVALID_PAIR /
INVALID_SELECTION / INVALID_DENOMINATOR / DATA_ERROR / VENDOR_NOT_FOUND. Require OK before
claiming an empty selection valid. No transport output/handle means unknown,
not OK. Count discrepancies remain hard failures regardless of scope status.

ETA row namespaces analysis-eta-code/source/us-source share full identity,
eta-value, signed counts, display-rank, row-ordinal, expected-bucket-count and
SQL totals/Other. Identity fields MUST be projected. `vendor-checks` emits one
selected Vendor×dimension record including success-empty dimensions; US checks
are against the Code=US bucket. Page default 200, max 1000. No positive filter
on ETA buckets; source column in physical daily data is `source`, not eta_source.

Legacy APIs remain intact and clearly labeled: their date validation is calendar
nearest and their positive drivers are not full signed populations. Legacy current
ranking's constant-one rate is deprecated. Use the new aggregate for accurate
full-view ratio and explicit denominator. Do not silently repoint old fields.
Existing detail org filter fixed so any supplied org condition constrains rows;
Alert counts no longer implicitly discard unmapped Vendors with empty filters.

All success claims remain scoped to available authorized facts. SQL identities
come from grouped rows, not pasted request values. No source watermark is assumed;
repeat dates don't guarantee historical immutability or cross-query transactions.

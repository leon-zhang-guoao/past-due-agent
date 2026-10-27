# Atlas v3 deployed-interface checklist

This is not live schema; confirm names and required inputs via MCP. Deploy the
combined Atlas contract before this Skill. v2 calendar-day/positive-driver
interfaces remain legacy; absence of v3 is a deployment gap, not silent fallback.

## Monitor
pastdue.monitor-query/countries
Entity: calendar-mode (WEEKDAYS default), relative-change-threshold (policy 0.10).
Facts: pastdue.monitor-fact; T/P, counts/Open, real business/data status,
expected-target-date, target-lag-workdays, missing-workdays-between and gap.
Every requested Country must appear in output/report even if absent upstream.

## Historical
pastdue.history-query/comparisons and /trigger-events
Entity: country-code, calendar-mode, range-start-date, range-end-date,
relative-change-threshold. Max 366 target calendar dates. Facts: history-fact.
Calendar mode controls target and nearest previous eligible date together.

## Fixed pair
pastdue.analysis-query requires ALL seven inputs:
country-code, target-snapshot-date, previous-snapshot-date, calendar-mode,
selection-mode, top-n, vendor-code.
POSITIVE_TOP_N: top-n 1..10, vendor-code 0 sentinel (ignored by SQL selection).
SINGLE_VENDOR: actual numeric vendor-code, top-n 1 (no positive filter).
Before selection query /scope-status → pastdue.analysis-scope: one control row
for invalid pair/denominator/selection or confirmed empty selection. Require OK
and compare selected-vendor-count. Zero rows is not a successful scope.
Collections / row namespaces:
- vendor-comparisons → pastdue.analysis-vendor
- eta-code-changes → pastdue.analysis-eta-code
- eta-source-changes → pastdue.analysis-eta-source
- us-source-changes → pastdue.analysis-eta-us-source (US Code only)
- vendor-checks → pastdue.analysis-check (one Vendor×dimension, including empty)
Every batch repeats the same deterministic selected set under exact pair/rules.
Code/Source return eta-value, complete identities, full signed counts, display-rank,
unique row-ordinal, expected-bucket-count, SQL Other fields and signed totals.
Checks compare Vendor totals and independently expose expected bucket counts.
US checks must reconcile against the received US Code bucket, not whole Vendor.

## Current aggregate
pastdue.current-analysis-query/rows
Inputs: country-code, grain (VENDOR/VENDOR_CODE/VENDOR_SOURCE), vendor-code-filter,
department-type-filter, master-primary-filter, master-manager-filter (empty = none),
denominator-scope (COUNTRY_ALL/FILTERED_POPULATION).
Rows: pastdue.current-analysis-fact. open-line-count is current-view row count,
not an unverified claim about historical Open. Count ranking=row-ordinal. Full
Vendor ETA composition reads all requested Vendor buckets, not first 10 buckets.
For current Top-N+ETA, first select Vendors via VENDOR then use targeted Vendor
scope for composition. Do not call snapshot changes “current”.

## Compatibility
Existing notification, Owner, master-mappings, detail and high-risk interfaces
remain discoverable and on-demand. v3 does not require an external runtime.
Do not use legacy current-vendor-past-due-rank's fixed-one ratio as Vendor rate.
Exact release/environment version is available only if MCP exposes it; document
UNVERIFIED when it doesn't. No graph-file reads at Agent runtime.

# Atlas v3 query playbook

## Discover before execution
list_entities → list_attributes for relevant request/row entities → search only
when needed. Copy actual identity, join, attribute and allowed sort. Use the
installed tool schema; EQL payload is {:entity ... :query ...}, not Datalog find.
Reuse discovery only in same environment/version. Missing required v3 capability
means deployment mismatch; do not guess roots or silently switch calendar.

## Monitor example
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
            :pastdue.monitor-fact/business-status
            :pastdue.monitor-fact/data-status
            :pastdue.monitor-fact/expected-target-date
            :pastdue.monitor-fact/target-lag-workdays
            :pastdue.monitor-fact/missing-workdays-between
            :pastdue.monitor-fact/has-comparable-snapshots
            :pastdue.monitor-fact/snapshot-gap-days
            :pastdue.monitor-fact/target-past-due-ratio
            :pastdue.monitor-fact/previous-past-due-ratio
            :pastdue.monitor-fact/relative-ratio-change
            :pastdue.monitor-fact/percentage-point-change]}
          {:limit 200 :offset 0})]}
```
Filter authorized monitor Country list locally only after complete country facts.
Use trusted runtime PST D; do not infer today from latest snapshot/examples.

## Fixed-pair example — identity fields are mandatory
```clojure
{:entity {:pastdue.analysis-query/country-code "CA"
          :pastdue.analysis-query/target-snapshot-date "2026-10-26"
          :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
          :pastdue.analysis-query/calendar-mode "WEEKDAYS"
          :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
          :pastdue.analysis-query/top-n 10
          :pastdue.analysis-query/vendor-code 0}
 :query [({:pastdue.analysis-query/eta-code-changes
           [:pastdue.analysis-eta-code/country-code
            :pastdue.analysis-eta-code/vendor-code
            :pastdue.analysis-eta-code/target-snapshot-date
            :pastdue.analysis-eta-code/previous-snapshot-date
            :pastdue.analysis-eta-code/calendar-mode
            :pastdue.analysis-eta-code/eta-value
            :pastdue.analysis-eta-code/previous-past-due-count
            :pastdue.analysis-eta-code/target-past-due-count
            :pastdue.analysis-eta-code/past-due-count-change
            :pastdue.analysis-eta-code/display-rank
            :pastdue.analysis-eta-code/row-ordinal
            :pastdue.analysis-eta-code/expected-bucket-count
            :pastdue.analysis-eta-code/increase-total
            :pastdue.analysis-eta-code/decrease-total
            :pastdue.analysis-eta-code/net-change
            :pastdue.analysis-eta-code/other-previous-count
            :pastdue.analysis-eta-code/other-target-count
            :pastdue.analysis-eta-code/other-bucket-count
            :pastdue.analysis-eta-code/other-count-change
            :pastdue.analysis-eta-code/other-relative-change
            :pastdue.analysis-eta-code/other-contribution-change
            :pastdue.analysis-eta-code/past-due-count-relative-change
            :pastdue.analysis-eta-code/delta-country-contribution]}
          {:limit 200 :offset 0})]}
```
Dates above are examples, never reusable defaults. First query scope-status (one control row, require scope-status OK), then vendor-comparisons
with full identity, rank, counts/Open, ratios, contributions and selection coverage;
then Code, Source and checks with unchanged entity. Code/Source are separate calls.
Use matching row namespaces from data-contract. US joint query only if US nonzero
in either period. SINGLE_VENDOR uses actual vendor-code even if negative/zero delta.

## History example
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
            :pastdue.history-fact/relative-ratio-change]}
          {:limit 200 :offset 0})]}
```
Expand metric fields via live schema as needed. List all event pages, then latest
per Country by actual target date, not first returned row. Dates range D−7..D−1
for recent week. Max 366 calendar target dates; weekend inclusion is explicit.

## Paging, handles, retries
- Use resolver's discovery-declared default ascending row-ordinal for batches;
  do not guess SQL sort aliases. Page length 200 (max 1000). Increase offset by
  returned rows; retain scope and ordering. Compare expected per-Vendor counts.
- Never apply Top-5 as fetch limit. A short page is not alone proof of completeness.
- Retain result handles if offered. execute_eql can return facts or only a handle.
  analyze_result without sql may return schema only; use sql over table data to
  materialize facts. Inspect available columns first; local flattened columns are
  not guaranteed identical to graph names. Avoid cross-product flattened joins.
- Bind handles to this request, environment and capture; expired/foreign handles
  are invalid. Never use an old handle because row numbers look plausible.
- Nested errors matter even if isError=false. One corrected retry, no guess-root
  loops or per-Vendor fallback storms. Permission denied is not retryable bypass.
- If pagination reveals duplicates/missing identities or mutable selection drift,
  stop combining that scope and report PARTIAL/UNAVAILABLE. At most one restart
  under the same request retry budget. No advertised transaction guarantee.

## Complete batch/current templates (release-generated reference)

These are examples, not runtime dates. Discover and use actual scope; retain every identity/check field.

### pastdue_v3_analysis_query_scope_status
```clojure
{:entity {:pastdue.analysis-query/country-code "CA"
    :pastdue.analysis-query/target-snapshot-date "2026-10-26"
    :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
    :pastdue.analysis-query/calendar-mode "WEEKDAYS"
    :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.analysis-query/top-n 10
    :pastdue.analysis-query/vendor-code 0}
  :query [({:pastdue.analysis-query/scope-status [:pastdue.analysis-scope/country-code :pastdue.analysis-scope/target-snapshot-date :pastdue.analysis-scope/previous-snapshot-date :pastdue.analysis-scope/calendar-mode :pastdue.analysis-scope/scope-status :pastdue.analysis-scope/selected-vendor-count :pastdue.analysis-scope/target-country-open :pastdue.analysis-scope/previous-country-open :pastdue.analysis-scope/row-ordinal]} {:limit 200
      :offset 0})]}
```

### pastdue_v3_analysis_query_vendor_comparisons
```clojure
{:entity {:pastdue.analysis-query/country-code "CA"
    :pastdue.analysis-query/target-snapshot-date "2026-10-26"
    :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
    :pastdue.analysis-query/calendar-mode "WEEKDAYS"
    :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.analysis-query/top-n 10
    :pastdue.analysis-query/vendor-code 0}
  :query [({:pastdue.analysis-query/vendor-comparisons [:pastdue.analysis-vendor/country-code :pastdue.analysis-vendor/vendor-code :pastdue.analysis-vendor/target-snapshot-date :pastdue.analysis-vendor/previous-snapshot-date :pastdue.analysis-vendor/calendar-mode :pastdue.analysis-vendor/target-past-due-count :pastdue.analysis-vendor/previous-past-due-count :pastdue.analysis-vendor/past-due-count-change :pastdue.analysis-vendor/target-open-count :pastdue.analysis-vendor/previous-open-count :pastdue.analysis-vendor/target-country-open :pastdue.analysis-vendor/previous-country-open :pastdue.analysis-vendor/contribution-rank :pastdue.analysis-vendor/selected-vendor-count :pastdue.analysis-vendor/vendor-name :pastdue.analysis-vendor/target-vendor-ratio :pastdue.analysis-vendor/previous-vendor-ratio :pastdue.analysis-vendor/target-country-contribution :pastdue.analysis-vendor/previous-country-contribution :pastdue.analysis-vendor/delta-country-contribution :pastdue.analysis-vendor/positive-contribution-share :pastdue.analysis-vendor/selected-positive-coverage]} {:limit 200
      :offset 0})]}
```

### pastdue_v3_analysis_query_vendor_checks
```clojure
{:entity {:pastdue.analysis-query/country-code "CA"
    :pastdue.analysis-query/target-snapshot-date "2026-10-26"
    :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
    :pastdue.analysis-query/calendar-mode "WEEKDAYS"
    :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.analysis-query/top-n 10
    :pastdue.analysis-query/vendor-code 0}
  :query [({:pastdue.analysis-query/vendor-checks [:pastdue.analysis-check/country-code :pastdue.analysis-check/vendor-code :pastdue.analysis-check/target-snapshot-date :pastdue.analysis-check/previous-snapshot-date :pastdue.analysis-check/calendar-mode :pastdue.analysis-check/dimension :pastdue.analysis-check/expected-bucket-count :pastdue.analysis-check/previous-total :pastdue.analysis-check/target-total :pastdue.analysis-check/increase-total :pastdue.analysis-check/decrease-total :pastdue.analysis-check/net-change :pastdue.analysis-check/previous-vendor-count :pastdue.analysis-check/target-vendor-count :pastdue.analysis-check/selected-vendor-count :pastdue.analysis-check/row-ordinal :pastdue.analysis-check/counts-match :pastdue.analysis-check/required]} {:limit 200
      :offset 0})]}
```

### pastdue_v3_analysis_query_eta_source_changes
```clojure
{:entity {:pastdue.analysis-query/country-code "CA"
    :pastdue.analysis-query/target-snapshot-date "2026-10-26"
    :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
    :pastdue.analysis-query/calendar-mode "WEEKDAYS"
    :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.analysis-query/top-n 10
    :pastdue.analysis-query/vendor-code 0}
  :query [({:pastdue.analysis-query/eta-source-changes [:pastdue.analysis-eta-source/country-code :pastdue.analysis-eta-source/vendor-code :pastdue.analysis-eta-source/target-snapshot-date :pastdue.analysis-eta-source/previous-snapshot-date :pastdue.analysis-eta-source/calendar-mode :pastdue.analysis-eta-source/eta-value :pastdue.analysis-eta-source/target-past-due-count :pastdue.analysis-eta-source/previous-past-due-count :pastdue.analysis-eta-source/past-due-count-change :pastdue.analysis-eta-source/display-rank :pastdue.analysis-eta-source/row-ordinal :pastdue.analysis-eta-source/expected-bucket-count :pastdue.analysis-eta-source/increase-total :pastdue.analysis-eta-source/decrease-total :pastdue.analysis-eta-source/net-change :pastdue.analysis-eta-source/other-target-count :pastdue.analysis-eta-source/other-previous-count :pastdue.analysis-eta-source/other-bucket-count :pastdue.analysis-eta-source/other-count-change :pastdue.analysis-eta-source/target-country-contribution :pastdue.analysis-eta-source/previous-country-contribution :pastdue.analysis-eta-source/delta-country-contribution :pastdue.analysis-eta-source/past-due-count-relative-change :pastdue.analysis-eta-source/other-relative-change :pastdue.analysis-eta-source/other-contribution-change]} {:limit 200
      :offset 0})]}
```

### pastdue_v3_analysis_query_us_source_changes
```clojure
{:entity {:pastdue.analysis-query/country-code "CA"
    :pastdue.analysis-query/target-snapshot-date "2026-10-26"
    :pastdue.analysis-query/previous-snapshot-date "2026-10-23"
    :pastdue.analysis-query/calendar-mode "WEEKDAYS"
    :pastdue.analysis-query/selection-mode "POSITIVE_TOP_N"
    :pastdue.analysis-query/top-n 10
    :pastdue.analysis-query/vendor-code 0}
  :query [({:pastdue.analysis-query/us-source-changes [:pastdue.analysis-eta-us-source/country-code :pastdue.analysis-eta-us-source/vendor-code :pastdue.analysis-eta-us-source/target-snapshot-date :pastdue.analysis-eta-us-source/previous-snapshot-date :pastdue.analysis-eta-us-source/calendar-mode :pastdue.analysis-eta-us-source/eta-value :pastdue.analysis-eta-us-source/target-past-due-count :pastdue.analysis-eta-us-source/previous-past-due-count :pastdue.analysis-eta-us-source/past-due-count-change :pastdue.analysis-eta-us-source/display-rank :pastdue.analysis-eta-us-source/row-ordinal :pastdue.analysis-eta-us-source/expected-bucket-count :pastdue.analysis-eta-us-source/increase-total :pastdue.analysis-eta-us-source/decrease-total :pastdue.analysis-eta-us-source/net-change :pastdue.analysis-eta-us-source/other-target-count :pastdue.analysis-eta-us-source/other-previous-count :pastdue.analysis-eta-us-source/other-bucket-count :pastdue.analysis-eta-us-source/other-count-change :pastdue.analysis-eta-us-source/target-country-contribution :pastdue.analysis-eta-us-source/previous-country-contribution :pastdue.analysis-eta-us-source/delta-country-contribution :pastdue.analysis-eta-us-source/past-due-count-relative-change :pastdue.analysis-eta-us-source/other-relative-change :pastdue.analysis-eta-us-source/other-contribution-change]} {:limit 200
      :offset 0})]}
```

### pastdue_v3_current_analysis_query_rows
```clojure
{:entity {:pastdue.current-analysis-query/country-code "CA"
    :pastdue.current-analysis-query/grain "VENDOR"
    :pastdue.current-analysis-query/vendor-code-filter ""
    :pastdue.current-analysis-query/department-type-filter ""
    :pastdue.current-analysis-query/master-primary-filter ""
    :pastdue.current-analysis-query/master-manager-filter ""
    :pastdue.current-analysis-query/denominator-scope "COUNTRY_ALL"}
  :query [({:pastdue.current-analysis-query/rows [:pastdue.current-analysis-fact/country-code :pastdue.current-analysis-fact/vendor-code :pastdue.current-analysis-fact/vendor-name :pastdue.current-analysis-fact/eta-value :pastdue.current-analysis-fact/grain :pastdue.current-analysis-fact/denominator-scope :pastdue.current-analysis-fact/open-line-count :pastdue.current-analysis-fact/past-due-count :pastdue.current-analysis-fact/denominator-past-due-count :pastdue.current-analysis-fact/vendor-past-due-count :pastdue.current-analysis-fact/past-due-ratio :pastdue.current-analysis-fact/past-due-share :pastdue.current-analysis-fact/within-vendor-share :pastdue.current-analysis-fact/row-ordinal :pastdue.current-analysis-fact/expected-row-count :pastdue.current-analysis-fact/queried-at-utc]} {:limit 200
      :offset 0})]}
```

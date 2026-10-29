# Retained-result reading — 3.3.7

## 1. Call boundary

This is a call procedure, NOT installed middleware. Prompt/Skill/Atlas EDN cannot
strip fields from a request after a host has constructed or sent it.

For a new source request use the actual discovered `execute_eql` schema:

```json
{"query":"<EQL with {:entity ... :query ...}>","retain_result":true}
```

For SQL reads of that request's retained result, construct only these keys:

```json
{"result_handle":"<handle from that execute response>","sql":"<SQL for that result>","analysis_version":2}
```

The result selector is exactly one of `query`, `result_handle`, `result_handles`.
Unused selectors are absent, not empty strings, empty arrays or null. Do not send
all three and assume empty values will be ignored. Do not send optional defaults
unnecessarily. A populated alternative selector must never be silently dropped to
make an ambiguous request pass. `false`/0 are not missing values.

If columns are not yet known, an optional schema discovery call is valid when the
live tool supports it: `{"result_handle":"<actual handle>","analysis_version":2}`.
It has succeeded in supplied runs. It retrieves schema/counts, not business rows.
Known verified column mappings may be reused in the same environment; discovery
is neither mandatory on every read nor the established cause of selector failure.

For `give exactly one ...`: inspect the actual submitted object. At most ONE
changed, evidence-based correction is allowed for the logical request. Remove
unused selectors if the caller can omit them; preserve the real handle. An
identical resend, version switch or source-query rerun is not a correction.
If the host still submits conflicting fields or cannot represent the valid shape,
stop dependent reads and report TOOL_ARGUMENT_ERROR with component cause
UNCONFIRMED. Do not claim the Skill executed a cleanup hook or fixed the host.

Keep actual execute/read call IDs when exposed, connection, input, handle and
capture/expiry metadata. Read handles only from their originating response, never
from an earlier run or a plausible remembered ID. A read response naming another
handle is ASSOCIATION_ERROR for that result. Missing provenance is UNVERIFIED;
quarantine dependent facts, not independent verified Countries/dimensions. If a
known current response appears expired, disclose the anomaly; only confirmed
expiry justifies a fresh execute, not guessing a replacement handle.

## 2. Small monitor and historical results

For the known small monitor result:

```sql
SELECT * FROM data ORDER BY country_code
```

Read actual rows before fixing dates/triggers, then apply the requested Country
allowlist. Inspect row AND cell truncation; SELECT * does not guarantee coverage.
History results use their discovered explicit columns and supported pagination,
not a blanket small-table assumption. Do not add report-only fields to monitor.

## 3. Report typed reads, one captured result

Use the full report EQL projection in structured-queries.eql before retaining the
result. Below are disjoint projections of the SAME handle, not new source SQL.
Country Control is separate from Vendor Metrics to avoid placeholder ambiguity.
The retained column names below must match the actual environment; if absent,
check actual schema, not approximate aliases. An unprojected field is unknown.

### A. Country Control — read and reconcile first

```sql
SELECT scope_key, record_type, country_code, vendor_code,
       previous_snapshot_date, target_snapshot_date, calendar_mode,
       selection_mode, metric_version, scope_status, business_timezone, queried_at_local,
       selected_vendor_count, selected_positive_coverage,
       previous_country_past_due_count, target_country_past_due_count,
       previous_country_open, target_country_open,
       previous_country_ratio, target_country_ratio,
       expected_record_count, row_ordinal
FROM data
WHERE record_type = 'COUNTRY_CONTROL'
ORDER BY row_ordinal
```

Require exactly one control for the requested scope. Match Country/P/T/calendar,
selection, metric version and scope key to the actual request; scope-status must
be OK. Compare the dedicated Country fields to the pinned monitor/history:

| Report COUNTRY_CONTROL | Monitor/history |
|---|---|
| previous_country_past_due_count / target_country_past_due_count | previous_past_due_count / target_past_due_count |
| previous_country_open / target_country_open | previous_open_count / target_open_count |
| previous_country_ratio / target_country_ratio | previous_past_due_ratio / target_past_due_ratio |

Counts must match exactly; apply documented tolerance for unrounded ratios.
`previous_past_due_count`, `target_past_due_count`, `previous_open_count`,
`target_open_count` on COUNTRY_CONTROL are NOT Country totals. Their zeros are
unrelated placeholders, not a failed cross-check. Do not interpret them.

If dedicated fields were not read, read them before asserting a mismatch. A true
mismatch is disclosed with both original values/captures; no blanket zero-value
exemption. Preserve independently verified report-scope facts with their dates
and denominators, but do not claim validated attribution of the monitor event
until cross-capture reconciliation succeeds. Handle/identity errors are stronger:
no dependent use merely because some numbers look reasonable.

### B. Vendor Metrics — returned ranking and Vendor quantities only

```sql
SELECT scope_key, record_type, country_code, vendor_code, vendor_name,
       previous_snapshot_date, target_snapshot_date, calendar_mode,
       selection_mode, metric_version, scope_status,
       contribution_rank, selected_vendor_count,
       previous_past_due_count, target_past_due_count, past_due_count_change,
       previous_open_count, target_open_count, previous_vendor_ratio, target_vendor_ratio,
       previous_country_contribution, target_country_contribution,
       delta_country_contribution, positive_contribution_share, selected_positive_coverage,
       expected_record_count, row_ordinal
FROM data
WHERE record_type = 'VENDOR_METRICS'
ORDER BY row_ordinal
```

Use returned contribution_rank for presentation; do not replace it with read
position or reselect Vendors. Match the complete set to selected_vendor_count.
Zero selected Vendors after OK control is an explicit empty selection, not an
error or permission to add negative Vendors.

### C. ETA summaries and checks — complete compact representation

```sql
SELECT scope_key, record_type, country_code, vendor_code, vendor_name,
       previous_snapshot_date, target_snapshot_date, calendar_mode,
       selection_mode, metric_version, scope_status,
       dimension, eta_value, row_kind, display_rank, other_bucket_count,
       previous_past_due_count, target_past_due_count, past_due_count_change,
       past_due_count_relative_change, delta_country_contribution,
       expected_bucket_count, increase_total, decrease_total, net_change,
       previous_total, target_total, previous_expected_total, target_expected_total,
       counts_match, required, expected_record_count, row_ordinal
FROM data
WHERE record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY','VENDOR_CHECK')
ORDER BY row_ordinal
```

Summary count fields refer to that bucket/Other, NOT Vendor totals. Read the
signed dimension totals from VENDOR_CHECK: previous_total, target_total,
increase_total, decrease_total, net_change and the expected totals. `us` expected
totals are conditional Code=US, not whole Vendor PD. Unrelated sparse fields on
check records are not substitute check values. Keep Code and Source separate.

### Paging and completion

The three projections together must cover the retained source result, including
control and checks. Keep a compact per-Country ledger: originating call/handle,
scope/P/T/version, expected source count, actual unique ordinals, selected Vendor
codes/ranks, and each dimension's retrieval/check/delivery state. This is an Agent
procedure, not a new persisted service or a substitute original evidence.

When a read is truncated or too large, partition C losslessly by known record_type
or stable row_ordinal windows using the live reader's supported WHERE/ORDER/LIMIT
syntax. Keep every identity column in every partition. Continue from the last
actually read ordinal; don't assume an omitted row was zero. Do not drop summaries
or checks to reduce output, and do not compare one partition's size to the whole
source count. Unexpected record types must be read/disclosed, not silently filtered.
Inspect row and cell flags on EVERY page. A result's top-level row-count can refer
to the underlying retained table; use actual returned rows/truncation metadata for
partition counts. Validate the UNION of unique ordinals against expected_record_count.
This verifies transport of the compact result, not all raw ETA buckets.

Source collection pagination is separate: a handle can contain only one source
page. Follow the playbook's source coverage rules. Never requery source per Vendor
to fix retained preview truncation. When a full read path is unavailable, name the
precise gap and keep dependent scope unverified rather than claim completion.

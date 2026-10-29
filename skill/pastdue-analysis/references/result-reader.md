# Retained-result reading — 3.3.2

## Two calls, one retained result

Use this for monitor, history, report and explore results retained by execute_eql.
Create each argument object from the recipe below, NOT all properties of a tool
form. HANDLE_FROM_THIS_EXECUTE is a placeholder, never submit it literally or use
a handle from examples, previous runs or another connection.

1. Execute the required EQL using the active MCP connection. Keep that response's
   exact result_handle, captured-at/expires-at and exposed env/version. Bind them
   to the actual request. If a newly returned capture appears stale/expired, do not
   silently borrow a newer handle; disclose the association/freshness uncertainty.
2. On the SAME connection, discovery arguments are ONLY:

```json
{"result_handle":"HANDLE_FROM_THIS_EXECUTE"}
```

3. Check nested errors and that discovery's returned handle matches the request.
   Inspect actual column names/types and row-count. Discovery normally has no row
   values (`materialized:false`); this is EXPECTED, not a failure or task completion.
4. On that SAME handle/connection, add nonempty read-only SQL with actual columns.
   The monitor recipe below is ready once its quoted names match discovery.
   Read all requested rows and row/cell truncation flags before fixing dates or
   claiming metrics. Report/explore use their own projections in the playbook.

Only result_handle is the input selector in BOTH calls. sql is a retained-table
projection, not a second selector. Don't submit query, result_handles, empty sql,
nulls or optional default fields. Normal operation needs no analysis_version,
infer_time or input_row_limit. Add an option only for an actual supported need;
changing analysis_version doesn't repair an exactly-one error.

## Concrete monitor row request

After discovery confirms these names, replace ONLY the handle below with the one
from this run. This is DuckDB over retained data, not source ClickHouse SQL.
If the projection differed, use the actual discovered monitor columns; never
invent unavailable fields or apply this monitor projection to report/explore.

```json
{
  "result_handle":"HANDLE_FROM_THIS_EXECUTE",
  "sql":"SELECT \"country_code\", \"previous_snapshot_date\", \"target_snapshot_date\", \"calendar_mode\", \"previous_past_due_count\", \"target_past_due_count\", \"previous_open_count\", \"target_open_count\", \"previous_past_due_ratio\", \"target_past_due_ratio\", \"relative_ratio_change\", \"percentage_point_change\", \"business_status\", \"data_status\", \"expected_target_date\", \"target_lag_workdays\", \"missing_workdays_between\", \"has_comparable_snapshots\", \"snapshot_gap_days\" FROM data ORDER BY \"country_code\" ASC"
}
```

No WHERE here: read the returned Countries, then apply the monitoring allowlist.
Do not invent row-ordinal for monitor. Discovery row-count describes the retained
input, not proof of all-source coverage. Source pages, retained-result pages and
cell completeness are separate. For report/explore read the playbook's complete
Read layer 2 section; source expected-record-count and compact checks still apply.
An actual rows response is required before Vendor/date/trigger decisions.

## Connection/schema evidence and scope

The user supplied a manual test in user-open_atlas, graph version13:
all seven properties were optional, additionalProperties=false, no null types;
handle-only discovery succeeded with row-count2 and matched request/return handle.
Omitting sql intentionally returned schema, not metrics. This is user-provided
evidence for THAT connection/test, not an executed test of this Agent release.
The supplied test did not yet demonstrate the SQL rows call or full report.

Failed Agent logs used ETA-PastDue-Graph. Same graph version doesn't prove the
same MCP tool schema, connection or configuration. Use actual tool names and
metadata in this session; don't switch connections, borrow handles, or replace
tools by assumed names. list_entities/list_attributes are business vocabulary,
NOT analyze_result's inputSchema. No fabricated schema-discovery tool is allowed.

Direct query analysis and multi-handle analysis remain available when deliberately
needed and supported: select query OR result_handles, omitting the other selectors.
They are not recovery modes for a failed single-handle read. Existing handles
should not be reexecuted through query merely to obtain their rows.

## One meaningful correction; diagnose from submitted evidence

Before every call: same connection? exact execute handle? correct input selector?
schema or actual rows step? Check the actual argument object, not intended prose.
This is a model procedure, not an installed machine gate.

On give exactly one of ..., inspect submitted keys. If multiple selectors were
included, reconstruct the call from the minimal recipe ONCE, removing the unused
keys while retaining the correct handle. Don't repeat, change analysis_version,
add empty EQL, or switch input modes to reset the correction allowance.

If actual execute→read handles differ, don't continue on the unexplained handle.
If the correct original handle remains valid, a correction uses that original
handle, never a guessed replacement. One correction total for the same failed
read chain, not one per erroneous field. Missing provenance remains unverified.
Expiration isn't repaired by inventing a handle; a genuinely necessary fresh
capture uses a new complete request/result chain within the same retry bound and
clearly discards dependent assumptions from the old capture. It is not a retry
for selector validation and never licenses repeated source execution.

If correct omission is blocked by the active tool schema, record that actual
constraint. If a selector-only call still errors, record connection, input and
error: root is unconfirmed, not automatically a broken adapter. Attribute host
injection only with evidence distinguishing submitted from transformed arguments.
Stop after the one corrected failure; preserve independent valid results. Never
claim no data/zero values, source SQL failure or full report success from this error.

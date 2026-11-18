# Retained-result reading — 3.6.3

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

For explore-v3 pages read [explore-v3.md](explore-v3.md): SELECTION and FACT have
different scopes, counts and grains; typed placeholders are not observations.
For explore-v2 source pages, use the same actual-handle argument shape above.
Read [explore-v2.md](explore-v2.md) for repeated CONTROL, coverage records,
page-record-count, whole-result ordinals and fingerprint validation. Each source
page has its own actual handle; a cursor is not a handle. The compact report's
record/total rules below do not apply to VENDOR_COVERAGE placeholders.

For the known small monitor result:

```sql
SELECT * FROM data ORDER BY country_code
```

Read actual rows before fixing dates/triggers, then apply the requested Country
allowlist. Inspect row AND cell truncation; SELECT * does not guarantee coverage.
History results use their discovered explicit columns and supported pagination,
not a blanket small-table assumption. Do not add report-only fields to monitor.

## 3. Paged results: report-v2 and current-v2

Use these interfaces when report-planning.md selects their semantics. Original
single-handle report keeps the typed read baseline in section4. This section
adds source-page handling; it does not mandate a new full-wide read for all modes.
Explore retains its SELECTION/FACT and coverage rules from its own reference.

### Choose the read, bind, continue

1. Use the complete EQL projection for the selected interface. First cursor0;
   report-v2 starts page-size50. Candidate current-v2 starts100. Discover only an
   unresolved schema/input gap; reuse compatible mappings in this environment.
2. Retain the execute's actual handle. Select required fields and record types
   according to the report modules. Report typed projections in section4 can be
   adapted to v2 by also reading its page metadata and VENDOR_COVERAGE. These
   extra rows must be accounted for; they are not legacy metrics or summaries.
   If response size permits, [report-page-read.sql](report-page-read.sql) and
   [current-page-read.sql](current-page-read.sql) are optional full-projection
   examples. They are candidate optimizations, not mandatory defaults. A page
   under200 rows can still exceed cell/text/context limits. Choose by actual
   tool limits and prior same-scope results, not row cap alone. Do not perform
   both a full read and typed re-reads of already complete fields.
3. Validate the response handle, environment, Country/scope, dates/filters and
   returned cursor/size against the originating request. Repeated controls agree
   on expected count, scope and fingerprint. Unexplained capture/cursor mismatch
   needs association review; plausible numeric totals cannot repair provenance.
   Use available raw call correlation; if unavailable, keep dependent facts
   UNVERIFIED and preserve independent scopes. Do not guess replacement handles.
4. Bind returned rows and fields before re-reading. Control-first interpretation
   does not require another call if control is already present. A typed WHERE
   excluding VENDOR_METRICS proves nothing about their presence in the source.
   Read only actual missing ordinals/fields from the SAME handle with identity.
5. When has-more=true, advance to exactly next-after-ordinal, strictly greater
   than the request cursor. No new noncontrol ordinals means no progress.
   has-more=false ends source pagination; next-after-ordinal=0 is a sentinel.
   Count repeated control once; union ordinals and reconcile expected-record-count.
   Keep existing serial source flow and each page's own handle. A Vendor can
   span pages; complete its required records before calling it verified.

For adapted typed reads, include cursor/size/page count/has-more/next cursor and
fingerprint from the page control (see the full SQL example for exact names).
Read VENDOR_COVERAGE identity/status/ordinal separately or in an existing read;
include it in the ordinal union without treating it as a measured record.
Control or coverage already available in an intact response needs no extra call.

### Working evidence and progress

For multi-page/multi-Vendor work, keep working notes sized to the task (a workspace
file if useful and available, otherwise task notes), with these two records. They are not a new host
service, mandatory output format, business data source or installed hard gate.
Never describe a note you did not actually save as persistent state.

- Page: request scope/cursor/size → actual execute call/handle/capture/environment
  → actual read call/returned handle → expected count, fingerprint, unique ordinals,
  intact fields, truncation and precise remaining gaps. Keep actual rows/cells or
  their available original response reference; an identifier list is not the data.
- Vendor: full scope + Country/P/T/calendar (or CURRENT filters/denominator/capture),
  Vendor code and metric version → actual metrics, Code/Source/us rows/checks,
  coverage, per-record provenance, validation and delivery state. Preserve
  record_type, dimension, eta_value/row_kind and ordinal when partitioning.
  Bind by this identity, never rank position or Vendor name. Repeated counts on
  other record types do not override the metric row.

Before each Atlas call name the gap it will close: next source page, missing
ordinal/field, unresolved schema item or one diagnosed correction. After it,
record the new facts or the exact blocker. Reuse already complete row×field
coverage; a different SELECT is useful only if it supplies missing evidence.
Reuse bound Country facts when composing its section; deliver the requested
Countries when their finite worklists are done
or locally blocked. A local failure does not erase another Country's work.

All modes count source executions, retained reads, discovery, continuations,
corrections and elapsed time toward cost. After first-page metadata, estimate
remaining source pages as ceil(remaining_noncontrol_rows/page_size), plus actual
retained reads. For example expected170/page50 needs4 source pages, expected136
needs3; this is a planning estimate, not a hard cap. Compare actual work to remaining
gaps. When calls add no new rows/fields and there is no supported changed action,
end that path with verified facts and exact gaps instead of an unchanged loop.
Continue large finite requests while making progress; preserve their scope.

### Local recovery

| Evidence | Action |
|---|---|
| rows?/cells? true, or host explicitly reports omitted tool content | Read only missing ordinal windows/fields from the same handle, with identity. A complete tool read already available in context needs no replay. |
| Result reference unavailable | Use the actual response already present; if essential content truly is unavailable, recover that exact gap with the originating retained handle once and record the recovery. Repeated schema/source requests do not repair the reference. |
| Unknown EQL attribute | Correct that name from the declared EQL schema once; SQL underscores are not EQL hyphens. Keep business scope. |
| Selector/argument rejection | Follow section1's one changed correction; keep the handle. |
| DuckDB configuration failure such as locked memory_limit | Mark this retained read unavailable, retain prior complete reads. Retry only after evidence the condition changed or a supported different read closes the gap; identical SQL/projection/version switches and new source queries do not fix configuration. |
| Confirmed handle expiry | One exact-scope recapture, with new capture and completeness checks. |
| Source byte refusal | One evidenced size/projection correction; account for newly omitted fields. Smaller pages do not fix source aggregation time. |
| Fingerprint drift | One consistent recapture of the affected page union; then disclose instability if still inconsistent. |
| Read handle/cursor differs from request | Isolate dependent facts; inspect available raw correlation or report ASSOCIATION_ERROR/UNVERIFIED. Preserve independent scopes; never repair by similar values. |

At most one evidence-based changed correction per failed work item. An unchanged
rejected call or duplicate successful source page is not recovery. Completion
rules for typed business facts and final copy accuracy live in
[completion-contract.md](completion-contract.md).

## 4. Original single-handle report: typed projections

This is the default existing read procedure for report-query/rows. It also
supplies lossless typed partitions when another reader result needs them. On paged report-v2, reuse any already-read fields and
include its page metadata/coverage as well. This is not a second checklist after
an already complete read.

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

Use [report-eta-read.sql](report-eta-read.sql) as the maintained C projection on
the SAME retained handle. It selects the three SUMMARY types and VENDOR_CHECK in
one read, preserves original identity/ordinal and does no joining or ranking.
SUMMARY rows expose bucket quantities; their repeated dimension-total fields are
NULL in this view. VENDOR_CHECK exposes totals; its bucket placeholders are NULL.
NULL here means not applicable to that record type, not a source zero or failure.
This is a retained SELECT, not source SQL or a new Atlas resolver.

| Record type | P / T / change to bind | Meaning |
|---|---|---|
| ETA_CODE_SUMMARY, dimension=code | previous_past_due_count / target_past_due_count / past_due_count_change | One Code bucket or returned Other |
| ETA_SOURCE_SUMMARY, dimension=source | same three bucket fields | One Source bucket or returned Other |
| US_SOURCE_SUMMARY, dimension=us | same three bucket fields | One Source bucket conditioned on Code=US; eta_value is the Source name |
| VENDOR_CHECK, dimension=code/source/us | previous_total / target_total / net_change, increase_total / decrease_total | Entire dimension, us conditional |

Dimensions are literal lowercase code/source/us. The full raw EQL and optional
wide reads can contain dimension totals repeated on SUMMARY rows; that repetition
is expected. It is not evidence that bucket quantities are repeated. Main cells
follow report-format.md's explicit bucket bindings. Reuse already intact bucket
fields instead of doing another read just to apply the view. If an intermediate
summary looks repeated or empty, first compare it to the original SUMMARY bucket
fields and its literal dimension before diagnosing source data. A confirmed
source inconsistency needs a contradiction in those actual scoped fields.

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
Inspect row and cell flags on EVERY page. `row-count` is the retained table size.
`truncation.rows-total` and `truncation.rows-shown` are this SQL's output.
This SQL is complete when `rows?` is false, `cells?` is false, and `rows-shown`
equals `rows-total`. Render those rows in the report modules.

`expected_record_count` is the whole compact report, not one WHERE clause.
Add `rows-total` across the disjoint statements. When that sum equals
`expected_record_count`, retrieval is complete. Ten Vendor Metrics rows plus
167 other rows is 177: the other statement kept the Vendor rows, it did not
drop them. Same for 10 plus 144 against 154. Deliver Code, Source, signed
totals and the diagnosis from those returned rows.

Treat a read as truncated only when `rows?` is true, `cells?` is true, or
`rows-shown` is below `rows-total`. Then continue from the last `row_ordinal`.
Do not withhold the ETA section while its rows are already in a complete response.
The union of unique ordinals must still equal `expected_record_count`.
This verifies transport of the compact result, not all raw ETA buckets.

Source collection pagination is separate: a handle can contain only one source
page. Follow the playbook's source coverage rules. Never requery source per Vendor
to fix retained preview truncation. When a full read path is unavailable, name the
precise gap and keep dependent scope unverified rather than claim completion.

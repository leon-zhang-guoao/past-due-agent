# Completion and evidence contract — v3.1

Track business/data/analysis status independently for every requested Country.
Freeze the full request (including filters, ranking and denominator), actual pair,
selected Vendors/ranks, environment/metric versions when exposed and result handles.
For INTERACTIVE questions only, at most 6 DIFFERENT LOGICAL FACT REQUESTS.
Discovery, materialization and pagination are excluded; retries/corrections remain
in the same logical slot. Different substantive fact scopes count separately.
Daily/history/test REPORT PRESETS have no six-request cap: attempt all mandatory
work. Broad history/optional scope may be bounded explicitly, never silently skipped.

## Exact association before dependent execution (3.1.1 correction)
Use only the handle literally returned by the matching execute call. No handle
from memory or another turn, even if the values look plausible. Exposed env/version
and captured-at must correspond; analysis/version is separate from env/version.
Handle mismatch is ASSOCIATION_ERROR and a test hard violation, not merely an
external timeout/PARTIAL. Missing export linkage remains UNVERIFIED; don't claim
Country monitor verified or use it to pin dependent dates until recovered.

## Before COMPLETE
Always project scope-key in explore/report rows: JSON tuple of ALL required
inputs in declaration order. Keep original inputs and verify key consistency;
it is part of row identity, not a watermark or a transaction claim.

1. Exactly one control row: report COUNTRY_CONTROL or explore CONTROL. Require
   scope-status OK; empty transport/handle isn't OK. Record every explicit error.
2. Fetch every page, unique row-ordinal, exact expected-record-count INCLUDING
   control. Verify consistent expected count and semantic identity across pages.
   Sorting/pagination is stable; a short page alone doesn't prove completeness.
   Reconcile COUNTRY_CONTROL target/previous-country-past-due-count,
   target/previous-country-open and target/previous-country-ratio to the pinned
   monitor/history facts; if captures drift, mark combining scopes unverified
   and preserve both raw responses rather than overwriting or alleging corruption.
3. Report VENDOR_METRICS covers exactly selected-vendor-count, unique Country+
   Vendor, server ranks and selection scope. All required identities match request.
4. Every selected Vendor has exactly one VENDOR_CHECK per code/source/us, including
   empty or required=false cases. Require counts-match=true for applicable
   dimensions; previous/target-total equal previous/target-expected-total and
   agree with independently received metrics, not merely duplicate metadata.
5. Code and Source summaries each reconcile both periods to Vendor PD. US joint
   summary applies in ALL Countries and reconciles to Code=US. Code=US can be inside OTHER: use US check
   expected totals, not a fabricated visible Code bucket. Apply required from
   the server check; required US dimensions must never be omitted.
6. Summary BUCKET ranks≤5 are unique raw eta-value identities; OTHER rank6 is
   distinguished by row-kind, not blank eta-value. BUCKET count plus OTHER's
   other-bucket-count equals expected-bucket-count. The row count is NOT the
   raw bucket count. For no remainder no Other. Sum compact T/P and delta against
   checks. Check increase-total + decrease-total = net-change = target-total −
   previous-total. Don't reconstruct signed totals from Other's net change.
7. Every selected Vendor appears in report rank order with full compact metrics,
   Code, Source, applicable US joint interpretation and diagnosis, or a specific
   per-dimension failure. Selected sets and field order match tool facts.
8. Explore results preserve all declared dimensions and scope. Non-PAIR previous
   placeholders aren't measured zero or growth. Truncated Top-N doesn't prove
   population totals. Explicit denominator fields govern displayed rates/shares.

Report schema summaries are intentionally compact, not incomplete raw buckets;
expected counts+Other representation+checks are the completeness mechanism. The
Agent must not issue raw per-Vendor calls to fill that intentional compression.
`result-complete` and no truncation describe transport/materialization only.
coverage-unknown alone is neither proof of failure nor proof of completeness.

Work states: verified / failed / completeness-unverified / not-executed. Last two
aren't empty or complete. Inspect nested errors even when isError=false. At most
one corrected retry per request in the same logical slot, no permission bypass or
recursive fanout. Preserve independent verified facts when one scope fails.

## Evidence classification: do not confuse these cases

**DATA_CONFLICT** requires TWO preserved raw tool responses for the SAME complete
fact identity that contradict on COMMON semantic fact fields (for example both
project the same T/P counts but disagree). Differing projections, extra metadata,
row ordering, handles or capture timestamps alone are not contradictions. Retain
both literal raw rows,
request entity/projection, result/handle identifiers, capture time and exposed
versions. Identity includes scope-key, Country, Vendor, dimension, actual T/P/calendar,
time-basis, all filters, denominator, selection/ranking scope, record-type,
row-kind, ETA value(s), metric and environment/graph version when available.
Different versions may be revision drift, not same-version server corruption.
Same scope+value with contradictory commonly projected T/P counts or other common
semantic fields is a hard conflict only with paired raw evidence. Equivalent
common facts may be deduplicated with provenance while retaining projection-only
fields separately; an unprojected field is unknown, not contradictory. Do not
deduplicate distinct row-kind/record-type facts.

Quarantine both conflicting versions, mark affected dimension FAIL and say
“数据身份/数量冲突，已隔离” / “Conflicting identity/count evidence quarantined”.
Never choose first/latest/largest, sum versions, or infer Vendor from equal counts.
Report both request/result provenance references. If raw evidence or scope is
missing, mark PARTIAL/UNVERIFIED and request evidence; don't accuse Atlas of a
proven conflict. Identical repeated numbers across different Vendors aren't proof.

**ASSOCIATION_ERROR**: a result is attached to the wrong request, Vendor or pair.
One mismatched raw response can establish this, but cannot establish two same-scope
server facts. Quarantine the attachment; correct it only with exact provenance,
never by matching numbers. Report data source cause as UNCONFIRMED.

**REPORT_ERROR**: the narrative/table contradicts verified tool facts or mixes
Code with Source. Correct the report, preserve the verified facts; don't blame
the server or label this DATA_CONFLICT. Identify the erroneous claim and its
correct raw-fact reference.

Missing metadata is completeness/identity UNVERIFIED, not a conflict by itself.
Maintain separate error classification, dimension result and analysis status.
A bad Code dimension doesn't erase independently verified Source or another Vendor.
No confidence percentages, invented watermark, immutable-history or transaction claim.

## Test and budget outcome
PASS: all applicable mandatory checks verified; NORMAL isn't automatic PASS.
PARTIAL: external execution failure, unknown completeness, missing evidence or
interactive-limit or explicitly bounded not-executed work. FAIL: proven identity/count/date/contract hard
violation, including paired DATA_CONFLICT, association or report error. A corrected
report must disclose the correction and rerun relevant checks, not hide the failure.
A missing optional context path doesn't fail unrequested mandatory work.
Zero positive selection may PASS the applicable empty path with explicit statement
that multi-Vendor ETA was not exercised. No-trigger alone is neither FAIL nor PASS.
Stop new distinct fact requests at the INTERACTIVE cap, preserving verified work
and identifying unexecuted scope. The cap never stops mandatory report presets.
Explicitly disclose bounds on broad historical/optional scope. Never invent host
interruption or automatic next-turn continuation.
Offline checks never certify real Atlas/JDBC/Agent acceptance.

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

# Execution and evidence — 3.2.0

Three separate questions: Are the server facts reconciled? Did the client retrieve
all requested blocks? Has an independent evaluator verified the final delivered
text? Never answer the third using evidence for the first two.

## Standard fact-block workflow

Follow fact-block-contract.md. Pin request, dates, language, template/metric version
and actual execute→handle linkage. Monitor/history comparison and all pages must
be read before dependent conclusions. A scope-key or timestamp is not a transaction.

Required client checks: one overview, same block identity/version/language, unique
ordinals/IDs, actual count equals expected-block-count, exact selected Vendor set
and rank order equal detail blocks. Expected valid scope count is 1 + selected
Vendors. Empty positive selection is explicit overview-only, not missing data.
Invalid scope is not a successful empty selection. Check raw Country controls
against actual pinned monitor/history, not another model-written table.

Each Vendor block returns server code/source/us check arrays with an explicit
schema and local status/reason. SERVER_RECONCILED means server full-population
aggregation and compact representation checks, NOT client independent raw-bucket
audit or final text verification. A bad Code dimension does not erase verified
Source/metrics; bad US limits that interpretation, including in CA. Removing its
presentation does not remove its required evidence. Other represents its actual
server remainder, not an invented zero/UNKNOWN or individual source identity.
Generic collection coverage UNKNOWN alone is a warning if required server and
client checks actually pass, not a reason for automatic PARTIAL or automatic success.

## Evidence status: no self-certification

Keep business trigger, data freshness, server reconciliation, retrieval and
presentation status separate:

- SERVER_RECONCILED: only if returned applicable server facts/checks support it.
- DATA_UNVERIFIED / EXECUTION_PARTIAL: identify exact failed or missing fact scope.
- Block receipt: actual unique blocks / expected, not guessed 11/11 or “all”.
- NOT_INDEPENDENTLY_VERIFIED: default final-output status in this deployment,
  even if data execution completed and the Agent reviewed its own draft.
- REPORT_ERROR: a confirmed wrong final claim, changed block or omitted required
  content. Disclosure/correction must reference actual original evidence.

No model-issued overall report/test PASS. Do not replace it with a synonymous
“all verified / production passed”. The user can independently evaluate a real
report; record that verdict only when actually supplied, limited to that run.
content-sha256 identifies source bytes only; without a real comparison it cannot
prove the Agent preserved delivered text. Never invent a hash calculation, host
gate, observed platform version or executed SQL check.

Test still forces all applicable required work and preserves genuine business
status. Record exercised Countries/Vendors, queries/checks and blocks received,
plus unexercised paths (e.g. no positive Vendors). No mail/escalation actions.
Execution completion is not report accuracy. A known error remains an error, not
an acceptable PARTIAL success obtained by reducing the report requirements.

## Error classification

DATA_CONFLICT needs TWO preserved raw responses with SAME complete semantic
identity and contradictory COMMON metric fields. Keep literal requests, rows,
handles/capture and exposed environment/metric versions. Scope includes dates,
Country/Vendor, dimension/value, filters/denominator/selection, language/template
where display is compared. Projection/format/ordering/time differences alone do
not establish a fact conflict. Do not compare a model summary as a new raw source.
Quarantine only genuine conflicting scope; no choosing/summing/reassigning versions.

ASSOCIATION_ERROR: wrong request→handle/row attachment, Country/Vendor/date mismatch.
One wrong attachment can prove that error, not two same-scope server facts. Fix
only with original provenance, not matching counts. Expired/foreign handles aren't
usable even if plausible. Missing provenance is UNVERIFIED, not an Atlas accusation.

REPORT_ERROR: copied total, delta sign, date direction, rank, Code label or narrative
contradicts original facts, or required blocks omitted. Correct the report from its
original rows; don't quarantine correct Atlas data. A draft typo fixed before
publishing is not a permanent source fault. Already published wrong reports require
clear correction and preservation of the original error record.

Single-response count inconsistency is reconciliation failure, not proof of
paired DATA_CONFLICT. Retain independently verified data and precise limitations.
Never manufacture confidence percentages, watermarks, cause or responsibility.

## Queries and failure budget

One justified correction retry per logical request. No legacy positive drivers
standing in for full changes, SQL/per-Vendor fallback storm, permission bypass or
retry just to seek values matching a draft. Cancelled source aggregation isn't
fixed by requesting fewer output rows. Read nested errors even when isError=false.

INTERACTIVE only: six different logical fact requests maximum; discovery, paging,
materialization excluded, corrections use same slot. Standard report mandatory
work has no six-request cap. Broad optional/history scope may be explicitly bounded
without silently dropping requested standard content. No invented host interruption
or promised automatic continuation. If actual output cannot be delivered in full,
name exact missing block scope and disclose delivery incomplete.

Governed materialization: discover actual analyze_result columns, read-only SELECT
explicit discovered columns FROM data ORDER BY discovered stable ordinal, bound to
exact retained handle. No SELECT *, other tables, joins, writes, fact-dropping
filters, aggregation or source/metric recomputation. This is not arbitrary source
SQL, not a separate presumed structured-read tool, not another fact request.

Raw structured reviews/interactive modules retain the original row binding in
data-contract.md. They are not a silent block-interface fallback. Offline tools
never certify real SQL/Atlas/LLM output; in this release they were NOT_RUN.

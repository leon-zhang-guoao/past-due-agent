# Real acceptance checklist —3.5.0 (live not executed)

V3 adds graph-side selection/expansion: verify one logical query selects Vendor
before buckets, preserves candidate FILTERED_POPULATION, separates selection and
display filters, and partitions Top N per actual date when requested. Empty
selection must not query all; selected objects without matching expansion keep
SELECTION with an explicit display gap. Check complete SELECTION/FACT counts and
identity/ranks independently. One SQL query is not a transaction guarantee.
All public cases below have informed design; independently withhold additional
questions from the building/tuning Agent. Actual role/performance tests required.

Public acceptance specification: tests/fixtures/live-question-cases.json. Use actual deployed
facts, complete original tool traces and delivered answers; keep evaluation
rubrics outside the tested Agent context. Include unseen condition combinations,
contextual follow-ups, dependent ranking→buckets→PO, denominator changes, finite
>6-scope worklists, source paging >1000 rows, local handle failure and real data
gaps. Repeat fixed scopes to observe variability. Existing standard report and
optional HTML behavior must pass independently. Synthetic SQL/contract receipts
are not real-Agent evidence. See docs/COMPOSABLE_ANALYSIS_3_4.md.

## Historical 3.3.7 checklist

First verify a same-host fresh-handle SQL read, then dedicated Country fields,
one Vendor, one Country Top10, then expand; retain original returned evidence and final output.
Do not substitute offline suites or model PASS for actual acceptance.

- Actual Atlas config-check, approved binding, report default/max1000 honored.
- Same actual valid pair: source expected rows == unique rows retrieved; result
  pages/partitions union matches each source page; no hidden row/cell truncation.
- Supplied prior cancellation/handle anomalies: record actual reproductions;
  don't certify root fixes from changed prompts. Never borrow foreign handles.
- Every selected Vendor in rank order, exact metrics, both ETA tables, Other,
  signed totals, diagnosis/local failure; overview vs details same Vendor set.
- More than200 rows, cut-off cell, duplicate/missing ordinal, wrong Country/date,
  Code failure with Source valid, required=false joint, valid zero-positive selection.
- 0→0 no risk; falling→positive residual improvement; positive→0 category clearance;
  zero baseline; Unknown/Other no guessed classification; denominator effects.
- Code=US joint evidence in CA and US; narrative follows ranking, no technical column.
- Chinese/English same modules/columns/dates/facts, appropriate request language;
  one fixed scope repeat to observe variability, no deterministic-rendering claim.
- Monday/Tuesday run, Friday→Monday snapshot, DST, missing weekday, stale warnings.
- Daily nontrigger metrics only; TEST forces eligible work with true business status;
  history events-only vs latest-event full; explicit historical pair vs nearest pair.
- Interactive current ranking, improvements, single Vendor nonpositive contribution,
  NR share denominator, range trend, org scope/current mapping, contextual follow-up.
- Owner/contacts/PO optional/current, PO5/Vendor20/Country, display only, no mail.
- Full output or numbered continuous parts with exact delivered/remaining scope;
  no missing Vendor disguised as source success or verified final output.

Status for all3.3 runtime cases: PENDING_LIVE_PILOT. Record real outcomes individually.

3.3.7 first gate: actual call has only one selector; one changed correction maximum,
no identical resend. Do not claim host serialization fixed by Skill. Control
comparison uses dedicated Country PD/Open/ratio, never Vendor placeholder zeros.
If these dedicated fields genuinely disagree, preserve original values/captures
and limit cross-capture attribution, not a blanket exception. See
docs/RESULT_READER_FIX_3_3_7.md for current staged acceptance and actual NOT_RUN state.

## 3.6 requested reports and Vendor sets

Use actual deployed scopes for these pending live cases; local synthetic tests do
not prove Agent behavior or ClickHouse22.8 performance. Details and deployment
sequence: docs/ON_DEMAND_REPORTS_3_6.md.

- Nontrigger Country: user explicitly requests a full report; show actual status
  and pair, Vendor/ETA analysis, no inferred TEST flag.
- Explicit set includes positive, nonpositive, zero-PD and missing Vendor: every
  identity accounted for; missing is not zero; default Top10 does not shrink set.
- One and thirteen Vendors: batch CURRENT and completed-pair paths, source and
  retained pages complete, repeated control counted once, no truncated-success claim.
- CURRENT org filters and each share denominator: national share retains whole
  Country PD; duplicate mappings do not multiply rows; compare prior explore facts.
- Follow-up adds Vendor or changes denominator/output: retain all other scope.
- Old automatic thresholds, explicit TEST, ranking/history/selection expansion,
  Owner/contact/PO and HTML handoff retain their prior semantics.
- Live Atlas byte/time budgets: capture effective policy, SQL/params, elapsed,
  page count and bytes. No5-second performance claim until actually measured.

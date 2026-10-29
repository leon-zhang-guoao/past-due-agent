# PastDue Analysis Agent — 3.3.7

**PENDING_LIVE_PILOT** — implementation/package only, not production acceptance.

Atlas computes structured metrics/rank/Top5+Other/checks; Skill binds typed facts,
reconciles them and directs complete Vendor delivery. Interactive current,
snapshot, pair and range questions remain composable, without daily positiveTop10
restrictions. No new required runtime, service, table or mail capability.

## What to update

- Replace `agent/SYSTEM_PROMPT.md` and the **entire** `skill/pastdue-analysis/`,
  or install `releases/pastdue-analysis-skill-v3.3.7.zip`.
- Full delivery: `releases/pastdue-agent-v3.3.7.zip`.
- **Atlas EDN unchanged** from publication3.3.0/revision4. If already deployed,
  do not redeploy it to fix a tool-argument error. Older deployments still need
  compatible structured capabilities and verified complete source pagination.
- No scripts, CSV, chat logs, notification folder or Runtime upload is required.

## This correction

- Country Control reads dedicated Country fields; Vendor placeholder zeros no
  longer create a false Country mismatch or a special reconciliation waiver.
- Three typed report reads preserve scope, Country/Vendor, P/T and metric version.
- Real handle provenance, one changed correction maximum, no identical retries.
- Staged references; one Country captured/read at a time; complete per-Vendor
  two-period Code/Source tables and signed totals, not a delta-only summary.
- Execution, retrieval/reconciliation and actual delivery are counted separately.

**No serialization middleware was added.** If the host injects conflicting
selectors, Prompt/Skill/EDN cannot guarantee their removal. The component causing
this remains UNCONFIRMED; same-host real calls must isolate it before rollout.
Supplied successful discovery calls show discovery is not intrinsically broken.

See [correction and evidence](docs/RESULT_READER_FIX_3_3_7.md),
[deployment/rollback](docs/DEPLOYMENT_V3.md), [validation state](docs/VALIDATION_V3.md)
and [source EQL examples](docs/STRUCTURED_PILOT_3_3.eql).
The Skill's result-reader.md is the only maintained retained-read recipe;
report-format.md is the only maintained human-facing layout.

## Semantics preserved

America/Los_Angeles with DST; completed snapshots earlier than local today;
Monday–Friday, no holidays. US/CA independent, relative Country ratio increase
≥10%; fixed positive-contribution Top10, 80% coverage reference only. Code/Source
absolute count change Top5+Other. Conditional Code=US Source applies to any Country
and informs the Country summary after ranking. Owner/contacts/PO on request,
current and display-only; PO max5/Vendor and20/Country. No mail drafts/sending.

No deterministic renderer or hard output gate is installed. Same module structure
and factual meanings are required, not byte-identical model prose. A self-review
or package hash does not prove final report correctness. No offline test suites,
platform or live Agent tests executed for this release; staged live pilot pending.
Historical docs and ZIPs remain history, not verified production fallbacks.

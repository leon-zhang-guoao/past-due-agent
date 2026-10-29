# PastDue Analysis Agent — 3.3.2

**PENDING_LIVE_PILOT** — local implementation/package, not platform/production acceptance.

Default reports now use compact **structured facts**, not3.2 long Markdown cells.
Atlas computes metrics/rank/Top5+Other/checks; Skill binds and checks reads; Agent
explains and formats with one layout. Flexible current/snapshot/pair/range questions
use composable explore capabilities without daily positiveTop10 restrictions.

## Update

- **From3.3.0/3.3.1: update System Prompt + complete Skill only. Atlas EDN is unchanged.**
- From older versions also deploy `atlas/charts/pastdue.edn`: publication3.3.0/revision4;
  report-query/rows source default1000, max1000.
- `agent/SYSTEM_PROMPT.md`.
- Complete `skill/pastdue-analysis/`, or `releases/pastdue-analysis-skill-v3.3.2.zip`.

Full delivery: `releases/pastdue-agent-v3.3.2.zip`. No scripts, CSV, notification
folder or Runtime required in Agent environment. Preserve environment IDs/bindings.
See [deployment/rollback](docs/DEPLOYMENT_V3.md), [design](docs/STRUCTURED_REPORTS_3_3.md),
[actual evidence](docs/VALIDATION_V3.md) and [live request examples](docs/STRUCTURED_PILOT_3_3.eql).
Result-reader fix: [3.3.2 evidence, scope and real verification steps](docs/RESULT_READER_FIX_3_3_2.md).
The supplied manual handle-only discovery succeeded in its connection; SQL row
reading and the updated Agent's end-to-end behavior remain pending live verification.

## Business semantics retained

America/Los_Angeles with DST; complete snapshot dates earlier than local today;
Mon–Fri no holidays. US/CA independent, relative Country ratio increase≥10%; fixed
positive contributionTop10, coverage reference only, Code/Source abs-count-delta
Top5+Other. Every selected Vendor has analysis or exact local failure. Joint
Code=US Source applies to all Countries and informs Country summary after ranking.
Owner/contacts/PO optional/current/display-only; no mail drafts/sending.

## Important boundary

Source paging, retained-result paging, cell completeness and final delivery differ.
The larger report source page is not a full-read guarantee. Real host reader support
must be confirmed with oneCountry pilot first. Same modules/columns/facts are required,
not byte-identical model prose. No installed deterministic renderer or output gate.
Historical docs/packages are retained in the repo, superseded by this release;
3.2 long-text APIs remain compatibility-only and their defects aren't certified fixed.
No offline tests executed; no automatic git commit/push or production access.

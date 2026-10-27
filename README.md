# PastDue Analysis Agent v3.1

**Release 3.1.1 (compatible v3.1 metrics/API); production acceptance pending.**

The report SQL cancellation patch removes repeated fact-CTE expansion and uses
two bounded source reads. See [patch/deployment notes](docs/REPORT_SQL_FIX_3_1_1.md).
Deploy the regenerated combined EDN, then matching Prompt/Skill. Existing 3.1.0
ZIPs are preserved; new delivery ZIPs are versioned 3.1.1. ClickHouse 22.8 and
Atlas/JDBC live success are NOT claimed by local chdb tests.

A unified governed analysis capability with a compact full-Vendor report preset.
Business facts come only from Atlas MCP; no arbitrary SQL, runtime Python, local
CSV, mail or external execution service. Governed handle-bound analyze_result
SELECT of explicit discovered columns FROM data is allowed for materialization,
not source SQL, joins, writes or metric/denominator/rank recomputation.

## Capabilities
- `pastdue.explore-query/rows`: controlled CURRENT/SNAPSHOT/PAIR/RANGE analysis,
  Country/Vendor/Code/Source/joint grains, Vendor/ETA/org filters, explicit ratio
  and share denominators, rank/sign/direction and top-n=0 all or 1..100.
- `pastdue.report-query/rows`: seven-input compatible analysis preset, tagged
  Country control, Vendor metrics, server Top-5+Other Code/Source/US joint summaries
  and all Vendor checks from shared facts; no per-Vendor request fanout.
- Independent US/CA completed-weekday monitoring, relative Country ratio trigger
  ≥10%; historical events and fixed-pair review. No holiday exclusion calendar.
- `start test` / `开始测试`: force applicable diagnostics, preserve real trigger;
  English/Chinese fixed compact FULL sections for every selected Vendor.
- America/Los_Angeles DST-aware clock, actual PST/PDT and UTC offset. Completed
  snapshots aren't current state; Monday snapshot vs Friday isn't Monday's run.
- Explicit AutoUS/AUTO US rule alias; US joint evidence applies to every Country.
- scope-key/full identity and counts evidence; paired raw DATA_CONFLICT separated
  from ASSOCIATION_ERROR and REPORT_ERROR, independent dimensions preserved.
- INTERACTIVE only: ≤6 different logical fact requests; discovery/materialization/
  paging excluded, retries same slot. Report presets have no six-request cap and
  attempt all mandatory work; broad optional scope is bounded explicitly.
  Missing v3.1 never silently falls
  back. Owner/contacts/PO are current, display-only and only on request.

## Contracts and delivery
See docs/API_V3.md, docs/DEPLOYMENT_V3.md, ACCEPTANCE_TESTS.md and the Skill's
manual atlas-query-playbook.md. Deploy matching System Prompt, complete Skill
references/metadata and combined Atlas graph with approved source binding.
The expanded graph inventory is 35 resolvers / 595 attributes; only live platform
checks prove deployed capabilities. scope-key/capture time is not a transaction.

Release metadata/archive generation is a separate owner-controlled step. A retained
old manifest/hash/PASS cannot certify changed v3.1 contracts; do not ship a mixed
bundle. Development scripts, tests and release assets are not Agent runtime
requirements. `docs/VALIDATION_V3.md` is a versioned evidence record, not blanket
validation of new changes. Offline checks, real Atlas/JDBC and real Agent reports
are distinct gates. A skipped SQL suite isn't SQL PASS; instructions aren't a
host-enforced submission gate. Configure secrets externally; policy isn't RLS.

> 3.1.1 SQL cancellation patch: deploy `atlas/charts/pastdue.edn` from the
> matching 3.1.1 ZIP (publication 3.1.1, source revision 2; metric version stays
> 3.1.0), then Prompt/Skill. See REPORT_SQL_FIX_3_1_1.md for exact Studio smoke
> query, preserved 3.1.0 rollback, and pending ClickHouse 22.8 acceptance.

# Deployment and rollback — v3.1

Status: contracts implemented locally; release packaging and live acceptance are
separate gates. Do not treat a retained v3.0 manifest/archive or historical PASS
as certification of these changed v3.1 contracts.

## Before deployment
1. Preserve approved deployed graph/source binding and matching Prompt/Skill.
   Inspect baseline/rollback artifacts, but don't assume they prove live state.
2. Supply fresh authorized environment configuration through secret management;
   never reuse historical chat tokens. Policy allowlists aren't RLS.
3. Read API_V3.md and ACCEPTANCE_TESTS.md. Run development-only offline regressions
   without invoking a deployed Agent's Python runtime (there is no runtime Python).
   Existing validation records apply only to their recorded artifact/version.
4. Release owner verifies combined Atlas v3.1 graph, source-slot binding and actual
   formal platform config-check. Static inventory: 35 resolvers / 595 attributes;
   inventory count alone is not a platform validation or SQL pass.
5. Release owner updates manifest/version/hashes and produces a matching release
   only after contract/evidence checks. Do not regenerate artifacts as an incidental
   docs edit or package mismatched Prompt/Skill/graph/manifest. NOT RUN isn't PASS.

## Rollout and live gates
1. Deploy expanded compatible Atlas graph first, retaining legacy APIs and source
   binding eta-clickhouse to the approved source. No database migrations.
2. Discover actual MCP explore-query/rows and report-query/rows, all 20 mandatory
   explore inputs, seven report inputs and exact explore-fact/report-fact fields.
   Always project scope-key (all required inputs encoded), full identity, metric
   version, business-timezone, queried-at-local and expected counts/ordinals.
3. Run live monitor, pinned report per Country and flexible CURRENT/SNAPSHOT/PAIR/
   RANGE examples from the manual playbook. Verify control errors and success-empty,
   explicit pair semantics, org dedupe, denominator fields, sign/rank/top-n behavior,
   nullable Decimal JDBC serialization, page 200/1000 and stable ordinals.
4. Verify report expected-record-count includes COUNTRY_CONTROL; reconcile its
   Country PD/Open/ratios to monitor/history. Drift isn't a transaction guarantee:
   separate conflicting captures, preserve raw evidence and mark unavailable scope.
   Check BUCKET ranks≤5, OTHER rank6 and represented raw counts; all Vendor×code/
   source/us checks including required=false. US joint applies in CA too.
5. Deploy complete Prompt/Skill from the SAME release. No local graph-file reads,
   unconstrained/source SQL, Python service, CSV or Kernel is needed at runtime.
   execute_eql can return only a retained handle. Discover analyze_result schema,
   inspect columns, then allow handle-bound read-only SELECT of explicit discovered
   columns FROM data ORDER BY discovered row-ordinal. Do not assume a separate
   structured-read tool. No source SQL, joins, writes or metric/denominator/rank/
   selection changes. Handle materialization isn't a new logical fact request.
6. Run `start test` (fully English), `开始测试` (fully Chinese), normal daily
   no-trigger and real triggered fixtures, historical events/full and interactive
   cases. Test forces applicable drilldown without inventing triggers. Every selected
   Vendor gets fixed compact full sections and bilingual rule diagnosis. AUTO US
   explicitly aliases AutoUS; UNKNOWN is never known other.
7. Instrument ≤6 DIFFERENT LOGICAL FACT REQUESTS per INTERACTIVE question only;
   discovery/materialization/pagination excluded, retries same slot. Report presets
   have no six-request cap and attempt all mandatory work. Bound broad history/
   optional scope explicitly, never silently skip. No per-Vendor request storms.
   Exercise partial failures/interactive limits, paired raw DATA_CONFLICT,
   ASSOCIATION_ERROR and REPORT_ERROR; retain independent valid dimensions.
8. Verify actual PST/PDT + UTC offset from trusted runtime in both DST seasons
   (October 2026 is PDT, November after transition PST), current/snapshot distinction
   and omission of non-PAIR previous sentinels. Rerun a fixed question three times:
   stable metric/denominator, dates, selected set, module/field order.
9. Save release/environment, original inputs/scope-key, all raw results/handles,
   captures, reconciliation, request count and final report in approved restricted
   evidence storage. Production-ready only after real Atlas/JDBC/Agent gates pass.

## Rollback
Restore saved graph/source binding and matching Prompt/Skill/release metadata as
one set. pre-redesign rollback archives preserve local history, not proof of the
previous deployment. Never leave a v3.1 Skill on a graph lacking explore/report.
Missing v3.1 is explicit deployment gap, not silent fallback to v3/v2 semantics.
Rotate previously exposed credentials separately; don't embed them in artifacts.

## Upload scope
- agent/SYSTEM_PROMPT.md and complete skill/pastdue-analysis/ references/UI metadata,
  with release-owner-approved matching manifest.
- Combined atlas/charts/pastdue.edn and approved atlas/sources.edn configuration.
- Sanitized matching release documentation.
No local CSV/chat exports/research runtime/notification service or development
Python scripts are required by Agent operation. Retained skill scripts are legacy
local development helpers, excluded from runtime packaging and never invoked by
the deployed Agent. Prompt instructions are not an installed host hard gate.

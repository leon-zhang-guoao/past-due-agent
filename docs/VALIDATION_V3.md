# Evidence record — Agent3.5.0 / additive Atlas3.5.0

## Current 3.5 evidence

Status: LOCAL_IMPLEMENTATION; platform config-check, production JDBC/query plans,
role access, knowledge activation and real Agent acceptance NOT_RUN.
Actual test outcomes/hashes are captured by releases/validation-v3.5.0-sql.json
and the matching release manifest when built; do not infer results from coverage plans.
Tests exercise v3 selection/expansion, GLOBAL/TARGET_DATE, old grouped-row parity,
candidate denominators, independent selection/display filters, empty/zero/missing,
organization/date coverage, original13-Vendor synthetic data and multi-page unions.
All38 v3.4 resolver definitions/bindings are compared against its immutable ZIP;
generator idempotence and complete EQL are checked offline.
One query/CTEs and equal fingerprints do not certify atomic capture; performance
must be measured on actual deployment. Explicit scalar input count is26, not reduced.
Previously published fixture questions are public design cases, not withheld tests.

Executed locally:35 synthetic ClickHouse SQL tests PASS (462.793s),18 query-contract
tests,17 Top10 tests,38 report/evidence tests,11 release-tooling tests,34 HTML tests
and4 SQL-report adapter tests PASS. Skill quick validation and git diff --check PASS.
The default system Python lacks optional chdb/PyYAML: SQL and Skill validation were
executed with /tmp/pastdue-query-validation-20261116/bin/python, not counted from
skipped default-Python runs. Full-suite duration is test workload, not a production
latency estimate. The first SQL run exposed a grouping substitution bug and failed;
only the repaired complete rerun is recorded in the release SQL receipt.

## Historical 3.4 evidence

Status: LOCAL_IMPLEMENTATION; live Atlas/JDBC and real Agent evaluation NOT_RUN.
The offline runner verifies contracts, generator idempotence, a complete v2 EQL
example and unchanged committed resolver/binding definitions. Synthetic ClickHouse
tests verify the SQL using generated fixtures, not production facts. Exact command
outputs and input hashes are recorded in releases/validation-v3.4.0-sql.json and
the v3.4.0 package's release-manifest.edn when built.

Test coverage: all four time bases and five grains; three denominator modes;
single/set equivalence; order/duplicate invariance; invalid JSON/Int64 overflow;
zero versus no-data/filters; >1000 logical records and page union; checksum drift;
top Vendors before their buckets; original13-ID request on synthetic rows; old
monitor/history/report/current paths, classic/current analyzer report parity.

Several baseline tests still targeted older versions/page defaults and an archived
display-module schema. The validator now honors the actual report page default1000;
archived section-parser tests use an explicit legacy fixture, rather than imposing
the obsolete module schema on the current report-format.md-owned layout. Packaging
tests use the selected release version and preserve optional HTML companion assets.
These are developer checks, not a new production runtime or layout authority.

Unverified: actual platform schema acceptance, production query plans/latency,
JDBC JSON/Decimal/checksum transport, live page caps and fresh Agent behavior.
No installed hard gate/handle interceptor or atomic current-view page guarantee.
Use tests/fixtures/live-question-cases.json for independent live acceptance.

## Historical 3.3.7 evidence (not current validation status)

Status: **PENDING_LIVE_PILOT**. No offline test suite, SQL fixture, live platform
call or Agent end-to-end acceptance executed for this release.

| Evidence | Actual observation | Boundary |
|---|---|---|
| Supplied latest3.3.6 run | All three selectors sent; exactly-one error repeated three times; no business rows read | Offending arguments confirmed, responsible component UNCONFIRMED |
| Supplied success fragment | SQL row reads and handle-only discovery both accepted | Discovery not established as root cause; does not certify3.3.7 |
| Supplied success fragment, compact rows | US177 and CA154 rows in disjoint partitions, 10 metrics/30checks each, shown row/cell flags false, counts_match true | Some handle associations anomalous; scope/date identity was omitted from materialized projections; not independent full raw-bucket audit |
| Country mismatch claim | Reader projected Vendor fields on control, omitted dedicated Country counts/Open/ratio | Country totals actually being0 not established; no SQL repair justified by that claim |
| Supplied final report | Main deltas shown, full per-Vendor two-period tables/signed totals omitted | Read success is not report delivery |
| Local3.3.7 changes | Typed projections, explicit provenance, removed zero waiver, staged refs, Vendor units and delivery accounting | Instructions, not serialization middleware or deterministic renderer |
| Atlas graph / SQL | Unchanged | No performance, JDBC, Decimal or deployment certification |
| Offline suites / Skill validator / SQL fixtures | NOT_RUN | User instruction |
| New same-host selector call / dedicated Country read | NOT_RUN | No Atlas tool available in this session |
| New single-Vendor / Top10 / bilingual / QA Agent acceptance | NOT_RUN | Must use actual returns and final output |
| Packaging | Allowlist, hashes and archive consistency only | Not business correctness or acceptance |

Only release command: `python3 -B scripts/build_release.py --package-only`.
It does not run offline suites; manifest retains NOT_RUN. Packaging checks are
not LLM behavior tests. No prior test receipt is imported. See
RESULT_READER_FIX_3_3_7.md and DEPLOYMENT_V3.md for the live gates and external
host dependency. Historical evidence documents remain history, not current recipes.

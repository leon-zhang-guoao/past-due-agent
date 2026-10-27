# Validation record — v3.0.0

**Release status: PENDING_LIVE_ACCEPTANCE.** This is local implementation evidence,
not production certification. No production ClickHouse or historical MCP token used.

## Local checks

| Check | Result | What it proves |
|---|---|---|
| Authoritative merged EDN validation | PASS | 33 resolvers, 476 attributes; mappings, selection, ownership, inputs/outputs |
| Query/contract regressions | PASS — 11 tests | Actual combined graph, sample EQL, required identity, shared selection, deterministic build |
| Arithmetic/identity/failure fixtures | PASS — 11 tests | Workdays, threshold, incomplete/foreign/duplicate/count mismatches, Other and US rule |
| Report/rule fixtures | PASS — 4 tests | Report set obligations, configured test/routing boundaries, trace regression |
| Skill quick_validate | PASS | Frontmatter/basic Skill structure, not runtime behavior |
| Local ClickHouse SQL fixtures | PASS — 7 tests | Shipped v3 SQL executes on synthetic local tables; not deployed JDBC |

SQL fixture environment: optional chdb 2.1.1, embedded ClickHouse 24.8.4.1,
installed only in a temporary development virtual environment outside delivery.
Synthetic data includes both Countries, 12 Vendors, weekend dates, negative ETA
changes, >5 buckets, US×Source, duplicate mappings, unmapped Vendor, stale/missing
snapshots, zero baseline and invalid counts. Scope-status and all batch/current
queries are executed. Tests verify both period totals and server Other fields.
SQL pagination tests execute an outer ORDER BY/LIMIT/OFFSET wrapper on the shipped
query and verify reconstruction; they do not prove the actual Atlas wrapper.
Actual SQL output column names are checked against the declared output mappings.

During SQL testing, unaliased qualified JOIN keys caused an actual
UNKNOWN_IDENTIFIER; explicit projected aliases fixed it. This is local evidence
for the new code, NOT a claimed root cause of the earlier production trace.

## Not run / release blockers

- Real Atlas schema/config-check and platform-specific source-slot binding.
- Deployed ClickHouse analyzer, JDBC Decimal/null JSON serialization.
- Actual Atlas pagination/wrapping/RLS/timeout/performance.
- End-to-end real Agent reports and same-question repeatability.
- Original identical-request/different-return server root cause (UNCONFIRMED).

No public performance or availability SLA is asserted. These gates require an
authorized deployed environment and records specified in ACCEPTANCE_TESTS.md.

## Development commands

```sh
python3 -B scripts/run_golden_eval.py
# optional local SQL engine installed in a disposable dev environment:
python3 -B scripts/test_sql_fixtures.py
python3 -B scripts/build_release.py --check
```

No Python helper is invoked by the deployed Skill. The retained Kernel prototype
and old specs are not production enforcement evidence. Rollback archive is a
sanitized local pre-change snapshot, not a verified previous online release.

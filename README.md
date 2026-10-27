# PastDue Analysis Agent v3

**Status: local implementation; production acceptance pending.**

Deploy only `agent/SYSTEM_PROMPT.md`, the complete `skill/pastdue-analysis/`
folder and `atlas/charts/pastdue.edn` with `atlas/sources.edn` source-slot binding.
No Python service, email sender, local CSV or runtime Kernel is required.

## Capabilities

- PST completed **weekday** monitoring, Monday snapshot vs Friday.
- US/CA independent alerts at relative Country ratio increase ≥10%.
- Positive contribution Top 10; batch signed Code/Source changes for every Vendor.
- Exact result identity, expected-bucket and period-total checks.
- ETA business diagnosis including conditional US×Source evidence.
- `开始测试`: force applicable standard drilldown, preserve real trigger state.
- Explicit historical date/range review and current Vendor/ETA/organization Q&A.
- Consistent modular reports; Owner, contact display and PO on demand only.

Calendar-day v2 interfaces remain legacy. A weekend run can repeat the same
weekday pair; it is not a new event. Holidays are not excluded in v1 of this
calendar. Model completion is guided, not hard-enforced by an unwired Kernel.

## Delivery and deployment

See `docs/DEPLOYMENT_V3.md`, `docs/API_V3.md`, `ACCEPTANCE_TESTS.md` and
`docs/VALIDATION_V3.md`. Production ZIP contains only needed artifacts and
sanitized documentation. Reference shards, chat exports, CSVs, secrets, caches
and research runtime are excluded. `releases/pre-redesign-rollback.zip` is the
pre-change bundle for rollback reference, **not** a certified production release.

Development checks (never required inside the deployed Agent):

```sh
python3 -B scripts/run_golden_eval.py
python3 -B scripts/test_sql_fixtures.py
python3 -B scripts/build_release.py --check
```

The SQL fixture suite needs optional `chdb`; missing dependency is explicitly
SKIPPED, not a SQL PASS. Static validation is against the actual combined graph,
not `*.attr.edn` reference shards. Atlas config-check, deployed JDBC/SQL and real
Agent acceptance are separate release gates.

No historical credential is used by this implementation. Secrets must be supplied
through your approved source configuration; policy allowlists are not RLS.

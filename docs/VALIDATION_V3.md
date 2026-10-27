# Validation record — v3.1 development

**PENDING_LIVE_ACCEPTANCE.** These tools validate local contracts, synthetic
fixtures, sanitized export regressions and release closure. They are not runtime
gates, do not force an Agent to emit JSON, and do not prove an LLM report or a
production deployment passed. No production connection or token was used.

## Development checks

```sh
python3 -B scripts/run_golden_eval.py --json /tmp/pastdue-offline.json
python3 -B scripts/test_report_contracts.py
python3 -B -m unittest discover -s tests -p 'test_release_tooling.py'
```

The golden runner executes the actual bundle validator, query contracts,
arithmetic/identity fixtures, report/evidence fixtures, and adversarial release
tests; it continues after failures, records actual exit codes/logs/script hashes,
and exits nonzero on failure. Current focused run: query **12**, arithmetic
**17**, report **38**, release **10** tests passed; authoritative bundle: **35
resolvers / 595 attributes**. Counts may increase as coordinated work settles;
the final manifest receipt, not this narrative, is authoritative.

## Offline report evidence

`analysis_validation.py` accepts flat namespaced Atlas `REPORT_FIELDS` rows via
`validate_atlas_report_rows`, and optional parsed report sections via
`validate_report_evidence`. Normalized artifacts are **development-only**. Unless
actual report parsing is supplied, results are labeled `NORMALIZED_FIXTURE_ONLY`,
never real LLM output proof. Source HTML is not shipped; sanitized provenance and
regressions are described in `BASELINE_V3_1.md`.

Checks include Country/Vendor/date/calendar identities; server `scope-key` and
metric version; exact selected Vendor ranks and counts; control-inclusive
expected-record-count and unique ordinals; independent Code/Source/US checks;
BUCKET/OTHER cardinalities and received totals versus expected and independent
Vendor facts; SINGLE_VENDOR preserves global ranks; only positive Top-N requires contiguous
ranks. Checks also support optional monitor/control Country PastDue, Open and ratio
comparison. Default `SERVER_RECONCILED` accepts server expected totals derived from raw
Code US even when US is hidden inside OTHER. Optional `CLIENT_RAW_AUDIT`
requires expanded raw evidence for that extra assurance, not a mandatory query. Code failure does not erase verified
Source evidence.

Typed EDN module/composition/reference keys are checked explicitly (Keyword is a
str subclass, so equality alone misses quoted-string drift); injected-string
regressions FAIL. Parsed-section checks use the shipped display contract compositions, requested
language, module statuses, repeated per-selected-Vendor module blocks and rank
order. Full reports require metrics, both ETA dimensions, conditional US,
business diagnosis, summary and completion; test mode adds test checks. Missing
mandatory work is PARTIAL; hard identity/count/copy/order contradictions FAIL;
coverage unknown alone is neither partial nor complete.

Raw conflicts require **two actual same-scope facts** disagreeing on common
semantic values, normalized numerically. Metadata, pagination and projection-only
differences do not conflict. Missing scope is PARTIAL, never guessed from totals.
Reports with unsupported conflict claims or altered raw-fact copies FAIL.
Handle references are checked separately for request/scope, expiry at explicit
observation time, analysis version and environment version (including raw
`env/version`, `analysis/version`, `result_handle`, `expires-at` aliases). Missing
versions stay unverified; environment versions are never fabricated.

## Local SQL receipt and packaging

Use a disposable development interpreter with chdb installed; it is never added
to deployment. The CLI executes the real SQL tests and captures graph/script/log
hashes and actual test count. A skipped suite does not PASS:

```sh
python3 -B scripts/build_release.py --record-sql-test /tmp/pastdue-v31-sql.json \
  --sql-python /tmp/pastdue-validation-env/bin/python
# After all code/docs edits settle:
python3 -B scripts/build_release.py --sql-receipt /tmp/pastdue-v31-sql.json
python3 -B scripts/build_release.py --check
```

Optional integration against the SQL owner's actual synthetic fixture helper:

```sh
/tmp/pastdue-validation-env/bin/python -B -m unittest discover -s tests -p test_report_sql_adapter.py
```

This exercises the 13 SQL fixtures plus two adapter checks (compressed summary
and SINGLE_VENDOR global ranks); Actual temporary chdb run passed **15/15** locally;
an environment without chdb skips and does not
constitute SQL verification.

Without `--sql-receipt`, local SQL is explicitly `NOT_RUN`. Stale, inconsistent,
failed or zero-execution receipts are rejected. A receipt is local evidence, not
a cryptographically signed external attestation. Skill structure is derived from
the actual bundle-validator result; it is not falsely labeled external Skill
quick_validate. Optional quick_validate/PyYAML can run in a disposable environment
separately; no implicit online validation is asserted.

Build writes the authorized Skill release manifest, full
`releases/pastdue-agent-v3.1.0.zip`, Skill-only
`releases/pastdue-analysis-skill-v3.1.0.zip`, and sanitized pre-v3.1 rollback. Both
release archives contain the same hash/receipt manifest. `--check` independently
verifies both archive file sets, duplicate/path rejection, payload hashes,
manifest bytes, source/validation-input drift and secret allowlist. The manifest
excludes its own hash to avoid recursion. Archives exclude CSV, chat, HTML,
runtime/development scripts, secrets and unrelated files; existing 3.0 artifacts
are retained. Build fails if inputs change during validation.

## Report SQL scan-shape review

The compact report uses one seeded bucket/window pipeline and tagged output
records, rather than repeating Code/Source/check branches over nonmaterialized
ClickHouse CTEs. Synthetic local EXPLAIN (chdb / ClickHouse 24.8.4.1) reduced
report ReadFromMergeTree nodes from 90 to 21; explore has 15. These are static
plan-node counts, NOT measured production latency, I/O savings or an SLA. Actual
Atlas wrappers, RLS, production cardinalities and timeout remain live checks.
A regression also verifies a selected Vendor/organization present only in the
previous period remains visible as a decrease to zero, including null target
ratios/shares when its denominator is zero.

## Still NOT_RUN / release blockers

- Real Atlas config-check, source-slot binding and deployed graph revision.
- Deployed ClickHouse/JDBC Decimal, null serialization and SQL behavior.
- Actual Atlas pagination/wrapping/RLS/timeout/performance.
- Live Agent reports, parsed real report evidence and repeatability acceptance.
- Original inconsistent-request/return server root cause (UNCONFIRMED).

Historical v3.0 local SQL results are not silently carried forward as v3.1 PASS.
No production certification, SLA or host-hard-gate claim is made.

# Task 1 Report: Payload fixture + gate tests

## Status

**DONE**

## Summary

Canonical HTML report JSON payload is locked via two fixtures, a reference copy under the new skill tree, `gate_payload` in `skill/pastdue-html-report/scripts/payload_gate.py`, and five unittest cases in `tests/test_html_report_payload.py`.

## Deliverables

| File | Purpose |
|------|---------|
| `tests/fixtures/html-report-payload-valid.json` | Passing canonical payload |
| `tests/fixtures/html-report-payload-gate-fail.json` | `INTERACTIVE` mode + empty `ranking` |
| `skill/pastdue-html-report/references/sample-payload.json` | Same content as valid fixture |
| `skill/pastdue-html-report/scripts/payload_gate.py` | `REQUIRED_META_KEYS`, column key lists, `gate_payload` |
| `tests/test_html_report_payload.py` | Gate + schema key assertions |

## TDD Evidence

### RED

Command: `python -m unittest tests.test_html_report_payload -v`

```
ImportError: Failed to import test module: test_html_report_payload
...
ModuleNotFoundError: No module named 'payload_gate'
Ran 1 test in 0.001s
FAILED (errors=1)
```

### GREEN

After fixtures + `payload_gate.py` implementation:

```
Ran 5 tests in 0.019s
OK
```

Tests:

- `test_valid_payload_passes_gate` — OK
- `test_gate_fail_payload_is_rejected` — OK
- `test_country_row_has_required_keys` — OK
- `test_ranking_row_has_required_keys` — OK
- `test_scope_gaps_key_required` — OK

## Gate behavior (implemented)

- `meta.mode` must be `DAILY` or `TEST`
- All `REQUIRED_META_KEYS` present on `meta`
- Top-level `scope_gaps`, non-empty `countries`, non-empty `per_country`
- Each per-country block: non-empty `ranking`, selected vendor codes present in ranking rows, ranking cells `main-code-changes`, `main-source-changes`, `check-totals`, `analysis-status`

## Commit

- **SHA:** `4cdf257`
- **Subject:** Lock HTML report JSON payload schema with fixtures and gate_payload unittest.

Only the five Task 1 paths were staged; unrelated dirty/untracked files were left untouched.

## Self-review

- Single implementation: tests import `gate_payload` and key lists from `payload_gate.py` (no duplicate logic in tests).
- `html-report-payload-gate-fail.json` removes `ranking` and sets `mode` to `INTERACTIVE`; gate rejects on mode first (non-empty reason string).
- `sample-payload.json` byte-aligned with valid fixture (same JSON written verbatim per brief).
- No changes to `pastdue-analysis`, animation skills, or other working-tree edits.

## Concerns

None.

# Task 2 Report: `payload-contract.md`

## Status

**DONE**

## Summary

Added `skill/pastdue-html-report/references/payload-contract.md` as the agent-facing
schema for the embedded `pastdue-report` JSON: top-level shape, `meta` / `run` /
`countries` / `per_country` / optional `cross_country_summary` / `scope_gaps`,
verbatim `COUNTRY_COLUMN_KEYS` and `VENDOR_RANKING_KEYS` aligned with
`payload_gate.py`, display formatting delegated to
`skill/pastdue-analysis/references/report-format.md`, a gate checklist matching
`gate_payload`, and explicit HTML prohibitions on inventing OTHER buckets and
recomputing Vendor totals from ETA rows.

## Deliverables

| File | Purpose |
|------|---------|
| `skill/pastdue-html-report/references/payload-contract.md` | Payload contract + column binding + gate mirror |

## Verification

Command: `python -m unittest tests.test_html_report_payload -v`

```
Ran 5 tests in 0.015s
OK
```

All five Task 1 tests still pass; no gate code changes.

## Commit

- **Branch:** `feature/pastdue-html-report`
- **Subject:** Document HTML report payload schema in payload-contract.md.
- Only `payload-contract.md` staged; unrelated working-tree edits untouched.

## Self-review

- Key lists copy the exact order from `payload_gate.py` (not paraphrased).
- Gate section mirrors every branch in `gate_payload` including ranking cell keys.
- Formatting section points to `report-format.md` without duplicating formulas.
- ETA hard rules echo `report-format.md` Vendor ranking / main ETA sections.
- Notes that full column keys are unittest-enforced on fixtures beyond minimal gate.

## Concerns

None.

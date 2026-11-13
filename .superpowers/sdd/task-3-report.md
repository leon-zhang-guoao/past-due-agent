# Task 3 report — Ops HTML shell + deterministic renderer

Status: DONE (with minor concerns)

## Delivered
- `skill/pastdue-html-report/scripts/render_ops_shell.py` — `render_ops_shell(payload) -> str`, CLI (`-o`), format helpers (`fmt_int/fmt_signed/fmt_ratio/fmt_pp/fmt_relative`).
- `skill/pastdue-html-report/references/html-shell.md`
- `skill/pastdue-html-report/scripts/out/.gitkeep`
- `tests/test_html_report_render.py` (written first; failed on import before implementation)

## Behavior
- Gate via `gate_payload`; failure -> `ValueError(reason)`; CLI prints `gate failed: ...`, exit 1, no file written.
- `<html lang>` from meta.language (zh/en headers per report-format.md), sticky header (mode badge, view badge, P/T, Ops/Demo toggle), sections `run`, `country-metrics`, `country-{CODE}`, optional `cross-country`, `scope-gaps`.
- Exact header lists (11 Country, 12 Ranking) in brief order; paired keys merged into `P→T` cells.
- Embedded `<script type="application/json" id="pastdue-report">`; `</` escaped as `<\/` (round-trip tested).
- `body data-mode="ops"`; JS reads `?mode=`; `?t=` reserved; `prefers-reduced-motion` stub only.
- Text HTML-escaped; no metric recomputation.

## Verification
- `python -m unittest tests.test_html_report_render tests.test_html_report_payload` -> 18 tests OK.
- Sample HTML generated at `scripts/out/pastdue-TEST-US-2026-10-23.html` (US metrics, ACME row, gaps section); not committed (only `.gitkeep`).

## Concerns
- Missing values render as 未提供/Not provided (report-format says missing != zero / not same '—'); brief didn't specify.
- Near-zero rule with direction words applied to signed changes only.
- Worktree has many unrelated uncommitted changes (pastdue-analysis etc.); untouched and not committed.
- Demo toggle only switches `data-mode`; Task 5 adds motion.

## Critical review fix (embedded JSON)

- Removed illegal `<!--` → `<\!--` replacement in `_json_for_script`; `\!` is not a valid JSON escape and broke `JSON.parse` / `json.loads` when payload text contained `<!--`.
- Retained `</` → `<\/` escaping so `</script>` substrings stay valid JSON and do not terminate the script element early.
- Added `test_embedded_json_allows_html_comment_in_text` (conclusion contains `<!--`); asserts `json.loads` on `#pastdue-report` script body and full payload round-trip.

```
python -m unittest tests.test_html_report_render -v
Ran 14 tests in 1.353s — OK
```

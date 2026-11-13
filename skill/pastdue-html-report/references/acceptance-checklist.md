# Acceptance checklist — spec §9

Maps [design spec §9](../../../../docs/superpowers/specs/2026-11-13-pastdue-html-report-design.md#9-acceptance-criteria) to automated or file evidence. Manual browser checks are noted where tests do not cover UX.

| # | Spec §9 criterion | Evidence |
|---|-------------------|----------|
| 1 | Auto hook after successful DAILY/TEST Markdown | `skill/pastdue-analysis/SKILL.md` §4 `### HTML companion` → `pastdue-html-report`; pointer in `references/report-format.md` § HTML companion |
| 2 | Ranking numbers / ETA/check cells match Markdown source | Fixture `tests/fixtures/html-report-payload-valid.json` (ACME row); `tests.test_html_report_render`: `test_formatted_values`, `test_ops_html_contains_required_sections_and_payload`, `test_zh_headers_exact_order`; embedded JSON roundtrip |
| 3 | Vendor rows complete; `scope_gaps` visible in Ops and Demo | `gate_payload` + `test_scope_gaps_key_required`; render: `#scope-gaps`, `data-beat="scope-gaps"`; `test_demo_beats_in_order_and_data_not_omitted` (no `display:none` in `<main>`, ACME present) |
| 4 | Default Ops; Demo via toggle / `?mode=demo` | `data-mode="ops"` default; `test_toggle_motion_stub_and_script`, `test_demo_harness_present` (`mode=demo`, `URLSearchParams`) |
| 5 | `prefers-reduced-motion` → final state, no loop | `test_reduced_motion_forces_final_state_without_looping`; `references/html-shell.md` |
| 6 | Demo `?t=N`; open in browser, no build | `test_demo_harness_present` (`get("t")`, `__pastdueTimeline`); single-file inline CSS/JS; regenerate: `python skill/pastdue-html-report/scripts/render_ops_shell.py … -o scripts/out/…` |
| 7 | HISTORICAL / INTERACTIVE / gate fail → no auto HTML + explain | `pastdue-html-report/SKILL.md` §1 & §3; `pastdue-analysis/SKILL.md` exclusions; `tests.test_html_report_payload.test_gate_fail_payload_is_rejected`; `test_gate_fail_raises`, `test_cli_writes_file_and_fails_on_gate` |

## Automated suite (Task 7 gate)

```bash
python -m unittest tests.test_html_report_payload tests.test_html_report_render -v
```

Expected: **24 tests, OK** (5 payload + 19 render).

## Manual browser spot-check (optional)

Open `skill/pastdue-html-report/scripts/out/pastdue-TEST-US-2026-10-23.html` via `file://`:

- Ops: full tables, `#scope-gaps` visible.
- `?mode=demo`: progressive beats; data same as Ops.
- `?mode=demo&t=0` (or other `t`): frozen timeline frame.
- OS **prefers-reduced-motion**: final composed layout, no looping animation.

## Spec coverage — Tasks 1–7 vs design §§4–9

| Design § | Topic | Task(s) | Deliverable |
|----------|--------|---------|-------------|
| §4 | Architecture, trigger matrix | 4, 6 | Companion `SKILL.md`; analysis hook; no Atlas in HTML skill |
| §5 | HTML page structure, hard rules | 3, 5 | `render_ops_shell.py`, `html-shell.md`, Demo beats / `data-beat` |
| §6 | Data contract + gate | 1, 2 | Fixtures, `payload_gate.py`, `payload-contract.md` |
| §7 | Skill changes | 4, 6 | `pastdue-html-report/*`; minimal analysis edits |
| §8 | Error handling | 3, 4 | Gate → `ValueError`; CLI exit 1; skip wording in SKILL |
| §9 | Acceptance criteria | 7 | This checklist + full unittest suite |
| §3 non-goals | No email/Atlas/renderer swap | — | Unchanged Atlas/analysis contracts; no mail/upload paths added |

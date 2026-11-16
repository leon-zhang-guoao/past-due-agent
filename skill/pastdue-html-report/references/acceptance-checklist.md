# Acceptance checklist — HTML on-demand

Maps `docs/superpowers/specs/2026-11-16-pastdue-html-on-demand-design.md` acceptance
plus retained Ops/Demo shell evidence from the original companion suite.

| # | Criterion | Evidence |
|---|-----------|----------|
| 1 | Default TEST/DAILY → MD + one ask; no HTML file; no `WRITTEN` | `pastdue-analysis/SKILL.md` § HTML companion (on-demand); `SYSTEM_PROMPT.md`; `policy.edn` `:ask-after-modes`, `:default-obligation-after-markdown-success false` |
| 2 | User confirms → HTML from reused bindings + real path, or `BINDING` | `pastdue-html-report/SKILL.md` §1 confirm follow-up, §5.3 `BINDING`; no default re-query |
| 3 | HTML_ONLY → no MD body; path or honest SKIPPED | `pastdue-html-report/SKILL.md` §1 HTML_ONLY; analysis handoff |
| 4 | Never false `WRITTEN` (no path / no write) | Companion §2, §5.1, §9; payload-contract producer checklist |
| 5 | HISTORICAL/INTERACTIVE without HTML ask → no offer, no write | Analysis skill; `policy.edn` ask-after only DAILY/TEST |
| 6 | Ranking / ETA cells / scope_gaps in Ops and Demo | Fixtures + `tests.test_html_report_render` (unchanged shell) |
| 7 | Gate fail / CAPACITY SPLIT | `payload_gate` tests; companion §5; `html-shell.md` multi-file naming |

## Automated suite (gate/shell unchanged)

```bash
python -m unittest tests.test_html_report_payload tests.test_html_report_render -v
```

Expected: **34 tests, OK**.

## Manual sandbox spot-check

1. TEST without mentioning HTML → MD ends with ask; no `.html` path.
2. Reply yes → `HTML: WRITTEN` with sandbox path, or `BINDING`.
3. 「只要 HTML」→ no MD report body; HTML path or SKIPPED.

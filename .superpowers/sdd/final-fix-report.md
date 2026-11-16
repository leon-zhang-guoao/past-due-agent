
## Final whole-branch fix pass (feature/pastdue-html-report)

- Important #1: html-report SKILL.md, html-shell.md and the pastdue-analysis HTML companion subsection now state: preferred agent path = emit equivalent HTML per html-shell/payload-contract (no Atlas); `render_ops_shell.py` = optional local verification helper (fixtures/CI), not a runtime service or Markdown renderer; analysis runtime-Python ban unchanged. `policy.edn` has unrelated dirty edits, so it was NOT touched (wording fix only). Only the companion hunk of `pastdue-analysis/SKILL.md` was staged; other dirty WT changes remain uncommitted.
- Important #2: `payload_gate.py` now requires all COUNTRY_COLUMN_KEYS per country row, all VENDOR_RANKING_KEYS per ranking row, non-blank summary/coverage, non-empty `selected-vendor-codes` list (all present in ranking), and per_country codes in countries. CLI catches ValueError and KeyError (prints reason, exit 1, no file). payload-contract.md checklist updated.
- Minor #3: `_json_for_script` replaces `<` with `\u003c` (subsumes `</`); test with `<!--` and `<script`.
- Quick wins: sample-payload.json == valid fixture bytes test; removed duplicate gate-fail render test (replaced by valid-mode incomplete-payload test).
- Tests: `python -m unittest tests.test_html_report_payload tests.test_html_report_render -v` -> 34 tests OK.

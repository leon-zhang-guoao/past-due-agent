### Task 7: Acceptance pass against spec §9

**Files:**
- Create: `skill/pastdue-html-report/references/acceptance-checklist.md`
- Optional sample: regenerate `scripts/out/pastdue-TEST-US-2026-10-23.html`

- [ ] **Step 1: Write checklist mapping spec §9 → evidence**

| # | Criterion | How to verify |
|---|---|---|
| 1 | Auto hook after DAILY/TEST | Hook text present in analysis SKILL |
| 2 | Numbers match Markdown source | Fixture ACME cells appear identically in HTML tables |
| 3 | Gaps visible Ops+Demo | `#scope-gaps` always in DOM; Demo only hides via opacity until final beat |
| 4 | Default Ops; Demo toggle | `data-mode` + `?mode=demo` |
| 5 | reduced-motion | CSS + JS branch |
| 6 | `?t=N` + no build | Seek harness + open file:// |
| 7 | Skip INTERACTIVE/events-only/gate fail | `gate_payload` + SKILL wording |

- [ ] **Step 2: Run full automated suite**

```bash
python -m unittest tests.test_html_report_payload tests.test_html_report_render -v
```

Expected: all PASS (adjust module names to what was created).

- [ ] **Step 3: Manual browser check** of generated HTML (Ops, Demo, `?t=0`, reduced-motion if OS setting available).

- [ ] **Step 4: Spec coverage self-check** — confirm Tasks 1–7 cover design §§4–9 and non-goals untouched (no Atlas/email changes).

---


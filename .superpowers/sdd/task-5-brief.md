### Task 5: Demo mode motion (same file)

**Files:**
- Modify: `skill/pastdue-html-report/scripts/render_ops_shell.py` (add Demo JS/CSS)
- Modify: `skill/pastdue-html-report/references/html-shell.md`
- Modify: `tests/test_html_report_render.py` (or extend existing)

**Interfaces:**
- Consumes: Ops HTML from Task 3
- Produces: same HTML with Demo timeline; `window.__pastdueTimeline` or GSAP `tl` + `?t=` seek

- [ ] **Step 1: Add tests**

```python
def test_demo_harness_present(self):
    html = render_ops_shell(payload)
    self.assertIn("mode=demo", html)  # toggle or docs in script
    self.assertIn("URLSearchParams", html)
    self.assertIn("prefers-reduced-motion", html)

def test_gate_fail_raises(self):
    bad = json.loads((FIX / "html-report-payload-gate-fail.json").read_text(encoding="utf-8"))
    with self.assertRaises(ValueError):
        render_ops_shell(bad)
```

- [ ] **Step 2: Implement Demo behavior in shell JS**
  - Beats: `#run` → `#country-metrics` → each `#country-*` ranking → summary/coverage → `#scope-gaps`
  - Ops: sections visible immediately; optional short count-up on ratios if motion allowed
  - Demo (`data-mode=demo`): progressive opacity/reveal with one master timeline; play/pause button
  - `?t=N`: `tl.pause(); tl.seek(N)` when GSAP used; otherwise set progress variable and apply classes
  - Respect `prefers-reduced-motion: reduce` → force final visible state, no looping
  - Isometric: do **not** add by default; document optional hook in html-shell.md only

- [ ] **Step 3: Re-render sample HTML and manually spot-check** Ops vs `?mode=demo` vs `?t=0`

- [ ] **Step 4: Unit tests pass**

---


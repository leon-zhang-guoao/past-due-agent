### Task 3: Ops HTML shell + deterministic renderer

**Files:**
- Create: `skill/pastdue-html-report/references/html-shell.md`
- Create: `skill/pastdue-html-report/scripts/render_ops_shell.py`
- Create: `skill/pastdue-html-report/scripts/out/.gitkeep` (generated HTML optional under `scripts/out/`)

**Interfaces:**
- Consumes: `sample-payload.json` / fixture path
- Produces: `render_ops_shell(payload: dict) -> str` returning full HTML string; CLI writes file

- [ ] **Step 1: Write a failing render smoke test**

Add to `tests/test_html_report_payload.py` (or new `tests/test_html_report_render.py`):

```python
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "skill" / "pastdue-html-report" / "scripts"))
from render_ops_shell import render_ops_shell

class HtmlRenderTests(unittest.TestCase):
    def test_ops_html_contains_required_sections_and_payload(self):
        payload = json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))
        html = render_ops_shell(payload)
        self.assertIn('id="pastdue-report"', html)
        self.assertIn("ACME", html)
        self.assertIn("scope_gaps", html)
        self.assertIn('data-mode="ops"', html)
        self.assertIn("main-code-changes", html)
        self.assertIn("核对", html)
        self.assertIn("业务状态", html)
```

For `language=zh`, renderer must emit Chinese headers from report-format exactly:
Country metrics: `Country`, `业务状态`, `数据状态`, `上期日期`, `本期日期`, `间隔天数`, `逾期 上期→本期`, `Open 上期→本期`, `逾期率 上期→本期`, `相对变化`, `百分点变化`  
Ranking: `排名`, `Vendor Code／名称`, `逾期 上期→本期`, `净变化`, `Open 上期→本期`, `Vendor 逾期率 上期→本期`, `Country 贡献变化 pp`, `正向贡献占比`, `主要 Code 变化`, `主要 Source 变化`, `核对`, `分析状态`

- [ ] **Step 2: Run — expect import/fail**

- [ ] **Step 3: Implement `render_ops_shell.py`**

Minimum behavior:
1. Call `gate_payload`; if false, raise `ValueError(reason)` (CLI catches and exits non-zero — agents skip HTML instead).
2. Emit HTML5 document with:
   - `<html lang="zh">` or `en` from `meta.language`
   - sticky header with mode badge, P/T, Ops/Demo toggle buttons
   - sections `#run`, `#country-metrics`, `#country-US` (per country), `#cross-country` (if present), `#scope-gaps`
   - tables built from payload keys in exact column order
   - `<script type="application/json" id="pastdue-report">...</script>` with `json.dumps(payload, ensure_ascii=False)`
   - `body` attribute `data-mode="ops"` default; JS reads `?mode=demo` and sets `data-mode`
   - CSS: readable tables, horizontal scroll for ranking, sticky thead
   - `prefers-reduced-motion` media query stub (Demo motion filled in Task 5)
3. Display formatting helpers: ratio→`xx.xx%`, pp→`x.xxxx pp`, integers without thousands separators.

CLI:

```python
if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("payload_json")
    p.add_argument("-o", "--output", required=True)
    args = p.parse_args()
    payload = json.loads(Path(args.payload_json).read_text(encoding="utf-8"))
    Path(args.output).write_text(render_ops_shell(payload), encoding="utf-8")
```

- [ ] **Step 4: Document shell conventions in `html-shell.md`**

Include: section IDs, `data-mode`, `?mode=`, `?t=` reserved for Demo timeline, reduced-motion rules, file naming `pastdue-{mode}-{countries}-{target}.html`, and pointer that agents may either run `render_ops_shell.py` or hand-author equivalent HTML matching this shell.

- [ ] **Step 5: Generate sample HTML**

Run:

```bash
python skill/pastdue-html-report/scripts/render_ops_shell.py skill/pastdue-html-report/references/sample-payload.json -o skill/pastdue-html-report/scripts/out/pastdue-TEST-US-2026-10-23.html
```

Expected: file exists; open in browser shows US metrics + ACME ranking row + gaps section.

- [ ] **Step 6: Run unit tests — expect pass**

---


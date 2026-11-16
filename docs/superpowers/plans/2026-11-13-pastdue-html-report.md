# PastDue HTML Report Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After successful DAILY/TEST Markdown reports, automatically emit one self-contained HTML file with Ops (default) and Demo modes from the same verified payload.

**Architecture:** Keep `pastdue-analysis` as the analysis authority. Add companion skill `pastdue-html-report` that gates on DAILY/TEST completeness, embeds a JSON report payload, and renders Ops/Demo UI. Animation skills are referenced only from the HTML companion. A small Python gate/parity helper locks the payload contract with fixtures.

**Tech Stack:** Agent Skills (Markdown), single-file HTML/CSS/JS, optional GSAP CDN for Demo timeline, Python `unittest` for payload gate + column-header parity.

**Spec:** `docs/superpowers/specs/2026-11-13-pastdue-html-report-design.md`

## Global Constraints

- Markdown + verified facts remain authoritative; HTML is presentation only.
- Auto-HTML only for successful standard DAILY/TEST; never for HISTORICAL events-only or INTERACTIVE.
- Column order/headers/formatting follow `skill/pastdue-analysis/references/report-format.md`.
- Do not re-query Atlas inside `pastdue-html-report`; do not scrape Markdown as truth.
- Demo must not omit ranking rows, ETA/check cells, coverage, or `scope_gaps`.
- Isometric decoration is Demo-optional and Ops-off.
- No email/upload/server renderer in v1.
- Do not commit unless the user explicitly asks.

## File map

| Path | Responsibility |
|---|---|
| `skill/pastdue-html-report/SKILL.md` | When/how to run; gate; Ops/Demo; animation skill invocation |
| `skill/pastdue-html-report/references/payload-contract.md` | JSON payload schema + report-format column mapping |
| `skill/pastdue-html-report/references/html-shell.md` | HTML skeleton, CSS, mode toggle, `?t=`, reduced-motion |
| `skill/pastdue-html-report/references/sample-payload.json` | Minimal valid TEST fixture for local render checks |
| `skill/pastdue-html-report/scripts/render_ops_shell.py` | Fixture → static Ops HTML (deterministic; no Atlas) |
| `skill/pastdue-analysis/SKILL.md` | Minimal post-success hook only |
| `skill/pastdue-analysis/references/report-format.md` | One-line companion pointer at end |
| `tests/test_html_report_payload.py` | Gate + required keys + Country/Vendor header lists |
| `tests/fixtures/html-report-payload-valid.json` | Copy/symlink of sample payload used by tests |
| `tests/fixtures/html-report-payload-gate-fail.json` | Missing ranking row / wrong mode for skip path |

---

### Task 1: Payload fixture + gate tests

**Files:**
- Create: `tests/fixtures/html-report-payload-valid.json`
- Create: `tests/fixtures/html-report-payload-gate-fail.json`
- Create: `tests/test_html_report_payload.py`
- Create: `skill/pastdue-html-report/references/sample-payload.json` (same content as valid fixture)

**Interfaces:**
- Consumes: none
- Produces: JSON shape later tasks treat as canonical; test helpers:
  - `REQUIRED_META_KEYS = ("mode", "language", "timezone", "calendar-mode", "generated-at", "metric-version")`
  - `COUNTRY_COLUMN_KEYS` / `VENDOR_RANKING_KEYS` lists
  - `def gate_payload(payload: dict) -> tuple[bool, str]`

- [ ] **Step 1: Write failing tests**

Create `tests/test_html_report_payload.py`:

```python
import json
import unittest
from pathlib import Path

FIX = Path(__file__).resolve().parent / "fixtures"
COUNTRY_COLUMN_KEYS = [
    "country-code", "business-status", "data-status",
    "previous-snapshot-date", "target-snapshot-date", "snapshot-gap-days",
    "previous-past-due-count", "target-past-due-count",
    "previous-open-count", "target-open-count",
    "previous-past-due-ratio", "target-past-due-ratio",
    "relative-ratio-change", "percentage-point-change",
]
VENDOR_RANKING_KEYS = [
    "contribution-rank", "vendor-code", "vendor-name",
    "previous-past-due-count", "target-past-due-count", "past-due-count-change",
    "previous-open-count", "target-open-count",
    "previous-vendor-ratio", "target-vendor-ratio",
    "delta-country-contribution", "positive-contribution-share",
    "main-code-changes", "main-source-changes", "check-totals", "analysis-status",
]


def gate_payload(payload: dict) -> tuple[bool, str]:
    raise NotImplementedError


class HtmlReportPayloadTests(unittest.TestCase):
    def test_valid_payload_passes_gate(self):
        payload = json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))
        ok, reason = gate_payload(payload)
        self.assertTrue(ok, reason)

    def test_gate_fail_payload_is_rejected(self):
        payload = json.loads((FIX / "html-report-payload-gate-fail.json").read_text(encoding="utf-8"))
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertTrue(reason)

    def test_country_row_has_required_keys(self):
        payload = json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))
        row = payload["countries"][0]
        for key in COUNTRY_COLUMN_KEYS:
            self.assertIn(key, row, key)

    def test_ranking_row_has_required_keys(self):
        payload = json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))
        row = payload["per_country"][0]["ranking"][0]
        for key in VENDOR_RANKING_KEYS:
            self.assertIn(key, row, key)

    def test_scope_gaps_key_required(self):
        payload = json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))
        self.assertIn("scope_gaps", payload)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run tests — expect fail**

Run: `python -m unittest tests.test_html_report_payload -v`  
Expected: FAIL (`NotImplementedError` or missing fixtures)

- [ ] **Step 3: Add fixtures**

`tests/fixtures/html-report-payload-valid.json` (and identical
`skill/pastdue-html-report/references/sample-payload.json`):

```json
{
  "meta": {
    "mode": "TEST",
    "language": "zh",
    "timezone": "America/Los_Angeles",
    "calendar-mode": "WEEKDAYS",
    "generated-at": "2026-11-13T09:00:00-08:00",
    "metric-version": "3.3.16"
  },
  "run": {
    "conclusion": "测试路径：US 相对变化超过阈值，已强制完整分析。",
    "trigger-flags": ["TEST_FORCED", "THRESHOLD_MET"],
    "pairs": {
      "US": {"previous-snapshot-date": "2026-10-22", "target-snapshot-date": "2026-10-23"}
    }
  },
  "countries": [
    {
      "country-code": "US",
      "business-status": "THRESHOLD_MET",
      "data-status": "OK",
      "previous-snapshot-date": "2026-10-22",
      "target-snapshot-date": "2026-10-23",
      "snapshot-gap-days": 1,
      "previous-past-due-count": 1000,
      "target-past-due-count": 1100,
      "previous-open-count": 5000,
      "target-open-count": 5100,
      "previous-past-due-ratio": 0.2,
      "target-past-due-ratio": 0.2156862745,
      "relative-ratio-change": 0.07843137,
      "percentage-point-change": 0.01568627
    }
  ],
  "per_country": [
    {
      "country-code": "US",
      "selected-vendor-codes": [3493],
      "ranking": [
        {
          "contribution-rank": 1,
          "vendor-code": 3493,
          "vendor-name": "ACME",
          "previous-past-due-count": 275,
          "target-past-due-count": 319,
          "past-due-count-change": 44,
          "previous-open-count": 900,
          "target-open-count": 920,
          "previous-vendor-ratio": 0.30555556,
          "target-vendor-ratio": 0.34673913,
          "delta-country-contribution": 0.00400000,
          "positive-contribution-share": 0.40000000,
          "main-code-changes": "US 3→2 (−1)；NR 272→317 (+45)",
          "main-source-changes": "AUTO US 3→2 (−1)；Vendor Report 272→317 (+45)",
          "check-totals": "Code +45/−1/净44；Source +45/−1/净44",
          "analysis-status": "OK"
        }
      ],
      "summary": "ACME 为主要正向贡献；Code=US 与 Source 联合变化已核对。",
      "coverage": "Top1覆盖全部正向贡献的40.00%。"
    }
  ],
  "scope_gaps": {
    "items": [],
    "notes": "无缺失项"
  }
}
```

`tests/fixtures/html-report-payload-gate-fail.json`: copy valid payload but set `"mode": "INTERACTIVE"` and delete `per_country[0].ranking`.

- [ ] **Step 4: Implement `gate_payload` in the same test module (or thin helper)**

```python
def gate_payload(payload: dict) -> tuple[bool, str]:
    meta = payload.get("meta") or {}
    mode = meta.get("mode")
    if mode not in ("DAILY", "TEST"):
        return False, f"mode {mode!r} is not DAILY/TEST"
    for key in ("mode", "language", "timezone", "calendar-mode", "generated-at", "metric-version"):
        if key not in meta:
            return False, f"missing meta.{key}"
    if "scope_gaps" not in payload:
        return False, "missing scope_gaps"
    if "countries" not in payload or not payload["countries"]:
        return False, "missing countries"
    per = payload.get("per_country") or []
    if not per:
        return False, "missing per_country"
    for block in per:
        selected = block.get("selected-vendor-codes") or []
        ranking = block.get("ranking") or []
        if not ranking:
            return False, f"{block.get('country-code')}: empty ranking"
        present = {row.get("vendor-code") for row in ranking}
        for code in selected:
            if code not in present:
                return False, f"{block.get('country-code')}: missing vendor {code}"
        for row in ranking:
            for cell in ("main-code-changes", "main-source-changes", "check-totals", "analysis-status"):
                if cell not in row:
                    return False, f"ranking missing {cell}"
    return True, "ok"
```

Move `gate_payload` + key lists into
`skill/pastdue-html-report/scripts/payload_gate.py` if preferred, and import from tests — keep one implementation.

- [ ] **Step 5: Run tests — expect pass**

Run: `python -m unittest tests.test_html_report_payload -v`  
Expected: OK

- [ ] **Step 6: Commit only if user asked**

---

### Task 2: `payload-contract.md`

**Files:**
- Create: `skill/pastdue-html-report/references/payload-contract.md`

**Interfaces:**
- Consumes: key names from Task 1
- Produces: documented schema agents must copy when building HTML

- [ ] **Step 1: Write the contract file** covering:
  - Top-level keys: `meta`, `run`, `countries`, `per_country`, `cross_country_summary?`, `scope_gaps`
  - Exact `COUNTRY_COLUMN_KEYS` and `VENDOR_RANKING_KEYS` from Task 1
  - Display formatting: dates `YYYY-MM-DD`; ratios as `100×` with two decimals `%`; contribution pp four decimals; signed nets `+/−/0`; zero-baseline copy rules by pointing to `../pastdue-analysis/references/report-format.md` (do not fork formulas)
  - Gate checklist identical to `gate_payload`
  - Explicit: HTML skill never invents OTHER constituents or recomputes Vendor totals from ETA buckets

- [ ] **Step 2: Verify sample payload still validates**

Run: `python -m unittest tests.test_html_report_payload -v`  
Expected: OK

---

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

### Task 4: `pastdue-html-report` SKILL.md

**Files:**
- Create: `skill/pastdue-html-report/SKILL.md`

**Interfaces:**
- Consumes: payload-contract, html-shell, animation skills paths
- Produces: agent-executable instructions for auto HTML

- [ ] **Step 1: Write SKILL.md** with frontmatter:

```yaml
---
name: pastdue-html-report
description: After a successful PastDue DAILY or TEST standard Markdown report, build a verified JSON payload and emit one Ops/Demo HTML file without re-querying Atlas.
---
```

Body must include:
1. Load only after `pastdue-analysis` standard DAILY/TEST Markdown success.
2. Build payload from in-memory verified bindings per `references/payload-contract.md`.
3. Run gate; on failure skip HTML and disclose reason (1–2 sentences).
4. Emit HTML per `references/html-shell.md` (prefer `scripts/render_ops_shell.py` for Ops baseline, then enhance Demo in-page).
5. Write file under workspace (path disclosed in chat).
6. Animation: read `../diagram-animation-*/SKILL.md` for Demo beats and light Ops count-up/bars; `../svg-animation-*/SKILL.md` for status marks; `../isometric-animation-*/SKILL.md` only if Demo decoration explicitly wanted — Ops default off.
7. Never modify Markdown already delivered; never award overall PASS.

Use relative glob note: folder names include version suffixes — instruct agent to resolve the local `diagram-animation-*`, `svg-animation-*`, `isometric-animation-*` directories under `skill/`.

- [ ] **Step 2: Sanity read** — ensure no Atlas query instructions and no contradiction with analysis “no report renderer” (HTML is companion-only).

---

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

### Task 6: Wire `pastdue-analysis` hook

**Files:**
- Modify: `skill/pastdue-analysis/SKILL.md` (section 4 Deliver)
- Modify: `skill/pastdue-analysis/references/report-format.md` (end pointer)

**Interfaces:**
- Consumes: companion skill name `pastdue-html-report`
- Produces: automatic handoff instruction after successful DAILY/TEST

- [ ] **Step 1: Append to section 4 of `SKILL.md`** (after existing deliver paragraphs, before Optional current context):

```markdown
### HTML companion (automatic after standard DAILY/TEST)

After a successful standard DAILY or TEST Markdown report — all applicable
report-format modules delivered, every selected Vendor ranking row present with
main Code/Source/check cells or explicit local failure, and scope/gaps stated —
load and follow [pastdue-html-report](../pastdue-html-report/SKILL.md) in the
same turn. Build the HTML payload from the same verified bindings used for
Markdown; do not re-query. If the HTML gate fails or rendering errors, keep the
Markdown delivery unchanged and disclose the HTML skip/failure in one or two
sentences. Do not auto-run the HTML companion for HISTORICAL events-only or
INTERACTIVE answers.
```

Keep the existing sentence “There is no report renderer” — clarify in the new
subsection that the companion is optional presentation, not a substitute
renderer for Markdown completeness.

- [ ] **Step 2: Append to `report-format.md`:**

```markdown
## HTML companion

After a successful standard DAILY/TEST report, the agent also emits an Ops/Demo
HTML file per `skill/pastdue-html-report/`. Markdown modules above remain the
layout authority for analysis content.
```

- [ ] **Step 3: Grep check**

Run: `rg -n "pastdue-html-report|HTML companion" skill/pastdue-analysis`  
Expected: hits in `SKILL.md` and `report-format.md` only for this feature.

---

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

## Self-review (plan vs spec)

| Spec item | Task |
|---|---|
| Companion skill + auto DAILY/TEST | 4, 6 |
| Ops + Demo same payload | 3, 5 |
| report-format columns/authority | 1, 2, 3 |
| Payload gate + scope_gaps | 1, 2 |
| Animation skill usage | 4, 5 |
| Minimal analysis hook | 6 |
| Acceptance / skip paths | 1, 5, 7 |
| Non-goals (no email/Atlas/six-col ETA) | Global + Task 4 wording |

No TBD placeholders in tasks. Key lists are shared across Task 1–3.

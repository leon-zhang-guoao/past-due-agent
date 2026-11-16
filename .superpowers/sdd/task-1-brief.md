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


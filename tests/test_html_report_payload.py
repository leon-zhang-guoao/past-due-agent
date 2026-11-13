import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "skill" / "pastdue-html-report" / "scripts"))
from payload_gate import (  # noqa: E402
    COUNTRY_COLUMN_KEYS,
    VENDOR_RANKING_KEYS,
    gate_payload,
)

FIX = Path(__file__).resolve().parent / "fixtures"


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

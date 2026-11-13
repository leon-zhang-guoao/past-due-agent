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
SAMPLE = ROOT / "skill" / "pastdue-html-report" / "references" / "sample-payload.json"


def load_valid():
    return json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))


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

    def test_sample_payload_matches_valid_fixture_bytes(self):
        self.assertEqual(SAMPLE.read_bytes(), (FIX / "html-report-payload-valid.json").read_bytes())

    def test_missing_or_empty_coverage_fails_gate(self):
        payload = load_valid()
        del payload["per_country"][0]["coverage"]
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertIn("coverage", reason)
        payload = load_valid()
        payload["per_country"][0]["coverage"] = ""
        self.assertFalse(gate_payload(payload)[0])
        payload = load_valid()
        payload["per_country"][0]["summary"] = None
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertIn("summary", reason)

    def test_missing_country_column_fails_gate(self):
        payload = load_valid()
        del payload["countries"][0]["relative-ratio-change"]
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertIn("relative-ratio-change", reason)

    def test_missing_ranking_column_fails_gate(self):
        payload = load_valid()
        del payload["per_country"][0]["ranking"][0]["previous-vendor-ratio"]
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertIn("previous-vendor-ratio", reason)

    def test_empty_selected_vendor_codes_fails_gate(self):
        for value in ([], None):
            payload = load_valid()
            payload["per_country"][0]["selected-vendor-codes"] = value
            ok, reason = gate_payload(payload)
            self.assertFalse(ok)
            self.assertIn("selected-vendor-codes", reason)

    def test_selected_vendor_missing_from_ranking_fails_gate(self):
        payload = load_valid()
        payload["per_country"][0]["selected-vendor-codes"].append(999999)
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertIn("999999", reason)

    def test_per_country_must_appear_in_countries(self):
        payload = load_valid()
        payload["per_country"][0]["country-code"] = "ZZ"
        ok, reason = gate_payload(payload)
        self.assertFalse(ok)
        self.assertIn("ZZ", reason)


if __name__ == "__main__":
    unittest.main()

"""Artifact tampering checks at the parent/child handoff boundary."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / 'skill/pastdue-html-report/scripts'
sys.path.insert(0, str(SCRIPTS))
from payload_gate import gate_payload
from render_ops_shell import render_ops_shell
from verify_artifact import verify_artifact


class SubAgentArtifactTests(unittest.TestCase):
    def setUp(self):
        self.payload = json.loads((ROOT / 'tests/fixtures/html-report-payload-valid.json').read_text())
        self.html = render_ops_shell(self.payload)

    def test_real_file_readback_cli(self):
        with tempfile.TemporaryDirectory() as tmp:
            payload, html = Path(tmp)/'payload.json', Path(tmp)/'report.html'
            payload.write_text(json.dumps(self.payload))
            html.write_text(self.html)
            proc = subprocess.run([sys.executable, str(SCRIPTS/'verify_artifact.py'), str(payload), str(html)],
                                  capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            html.unlink()
            proc = subprocess.run([sys.executable, str(SCRIPTS/'verify_artifact.py'), str(payload), str(html)],
                                  capture_output=True, text=True)
            self.assertNotEqual(proc.returncode, 0)

    def test_accepts_css_design_changes(self):
        verify_artifact(self.payload, self.html.replace('<style>', '<style>body{background:#fafafa}'))

    def test_rejects_changed_embedded_facts(self):
        changed = copy.deepcopy(self.payload)
        changed['countries'][0]['target-past-due-count'] += 1
        with self.assertRaisesRegex(ValueError, 'embedded payload'):
            verify_artifact(changed, self.html)

    def test_rejects_missing_or_duplicate_embedded_payload(self):
        for html in (self.html.replace('id="pastdue-report"', 'id="other"'),
                     self.html.replace('</body>', '<script id="pastdue-report">{}</script></body>')):
            with self.assertRaises(ValueError):
                verify_artifact(self.payload, html)

    def test_rejects_changed_visible_value_even_with_correct_json(self):
        with self.assertRaisesRegex(ValueError, 'table content'):
            verify_artifact(self.payload, self.html.replace('<td>1000→1100</td>', '<td>1000→9999</td>'))

    def test_rejects_missing_vendor_table(self):
        start = self.html.index('<table class="ranking">')
        end = self.html.index('</table>', start) + len('</table>')
        with self.assertRaisesRegex(ValueError, 'table content'):
            verify_artifact(self.payload, self.html[:start] + self.html[end:])

    def test_rejects_missing_gaps(self):
        with self.assertRaises(ValueError):
            verify_artifact(self.payload, self.html.replace('id="scope-gaps"', 'id="gone"'))

    def test_rejects_changed_summary(self):
        summary = self.payload['per_country'][0]['summary']
        with self.assertRaisesRegex(ValueError, 'narrative'):
            verify_artifact(self.payload, self.html.replace(summary, 'unsupported claim', 1))

    def test_preserves_actual_mode_for_full_reports(self):
        for mode in ('DAILY', 'TEST', 'HISTORICAL', 'INTERACTIVE'):
            with self.subTest(mode=mode):
                self.payload['meta']['mode'] = mode
                self.assertTrue(gate_payload(self.payload)[0])
                verify_artifact(self.payload, render_ops_shell(self.payload))

    def test_events_only_still_fails(self):
        for mode in ('HISTORICAL', 'INTERACTIVE', 'UNKNOWN'):
            self.assertFalse(gate_payload({'meta': {'mode': mode}, 'scope_gaps': []})[0])


if __name__ == '__main__':
    unittest.main()

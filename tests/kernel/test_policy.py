import unittest
from pathlib import Path

import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from runtime.pastdue_kernel.policy import load_policy  # noqa: E402


class TestPolicy(unittest.TestCase):
    def test_load_policy_1_7(self):
        p = load_policy()
        self.assertEqual(p.version, "1.8.0")
        self.assertIsNotNone(p.capability("current-vendor-past-due-ranks"))
        self.assertIsNotNone(p.capability("current-detail-slice"))
        self.assertTrue(p.disambiguation_rules)
        mm = p.capability("master-mappings")
        self.assertIsNotNone(mm)
        assert mm is not None
        self.assertIn("pastdue.vendor-master-mapping", mm.forbidden_roots)
        match = p.capability("matching-mappings")
        self.assertIsNotNone(match)
        assert match is not None
        self.assertIn("master-mappings", match.completeness_notes)
        eta = p.capability("eta-code-changes")
        self.assertIsNotNone(eta)
        assert eta is not None
        self.assertIn("占比", eta.completeness_notes)


if __name__ == "__main__":
    unittest.main()

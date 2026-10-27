import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from runtime.pastdue_kernel.gate import preflight  # noqa: E402
from runtime.pastdue_kernel.policy import load_policy  # noqa: E402


class TestGate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = load_policy()

    def test_multi_collection_reject(self):
        eql = (
            "[:find (pull ?e [{:pastdue.historical-vendor-contribution/eta-code-changes [*]} "
            "{:pastdue.historical-vendor-contribution/eta-source-changes [*]}]) :where [?e]]"
        )
        r = preflight(eql, policy=self.policy, capability="eta-code-changes")
        self.assertFalse(r.ok)
        self.assertIn("multi-collection", r.reason)

    def test_forbidden_mapping_root(self):
        eql = (
            "[:find (pull ?e [{:pastdue.vendor-master-mapping/master-mappings [*]}]) "
            ':where [?e :pastdue.vendor-master-mapping/vendor-code "X"]]'
        )
        r = preflight(eql, policy=self.policy, capability="master-mappings")
        self.assertFalse(r.ok)

    def test_contribution_scalar_root(self):
        eql = (
            "[:find (pull ?e [:pastdue.vendor-contribution/vendor-name "
            ":pastdue.vendor-contribution/latest-past-due-count]) "
            ':where [?e :pastdue.vendor-contribution/vendor-code "40979"]]'
        )
        r = preflight(eql, policy=self.policy, capability="positive-vendor-contributors")
        self.assertFalse(r.ok)
        self.assertIn("scalar", r.reason.lower())

    def test_single_collection_pass(self):
        eql = (
            "[:find (pull ?e [{:pastdue.vendor-contribution/master-mappings [*]}]) "
            ':where [?e :pastdue.vendor-contribution/country-code "CA"] '
            '[?e :pastdue.vendor-contribution/vendor-code "V1"]]'
        )
        r = preflight(eql, policy=self.policy, capability="master-mappings")
        self.assertTrue(r.ok, r.reason)

    def test_duplicate_terminal_key(self):
        eql = (
            "[:find (pull ?e [{:pastdue.vendor-contribution/master-mappings [*]}]) "
            ':where [?e :pastdue.vendor-contribution/country-code "CA"] '
            '[?e :pastdue.vendor-contribution/vendor-code "V1"]]'
        )
        wk = ("master-mappings", "CA", "V1")
        r = preflight(
            eql,
            policy=self.policy,
            capability="master-mappings",
            session_terminals={wk},
            work_key=wk,
        )
        self.assertFalse(r.ok)
        self.assertIn("duplicate", r.reason)

    def test_adversarial_corpus(self):
        path = Path(__file__).parent / "adversarial_eql.json"
        cases = json.loads(path.read_text(encoding="utf-8"))
        expect_reject = [c for c in cases if c.get("expect") == "reject"]
        rejected = 0
        for c in expect_reject:
            r = preflight(
                c["eql"],
                policy=self.policy,
                capability=c.get("capability"),
            )
            if not r.ok:
                rejected += 1
        rate = rejected / max(len(expect_reject), 1)
        self.assertGreaterEqual(rate, 0.95, f"reject rate {rate} ({rejected}/{len(expect_reject)})")


if __name__ == "__main__":
    unittest.main()

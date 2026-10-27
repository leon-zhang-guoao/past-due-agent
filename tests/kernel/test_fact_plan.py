import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from runtime.pastdue_kernel.disambiguate import match_intents_from_text  # noqa: E402
from runtime.pastdue_kernel.fact_plan import build_fact_plan  # noqa: E402
from runtime.pastdue_kernel.policy import load_policy  # noqa: E402
from runtime.pastdue_kernel.types import IntentIR, IntentSpec  # noqa: E402


class TestFactPlan(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = load_policy()

    def test_eta_share_forces_changes_not_drivers(self):
        ir = match_intents_from_text(
            "CA Top-10 Vendor 的 ETA Code 占比和构成", self.policy
        )
        plan = build_fact_plan(
            ir,
            self.policy,
            context={
                "countries": ["CA"],
                "vendors": ["V1", "V2"],
                "target-snapshot-date": "2026-10-18",
                "previous-snapshot-date": "2026-10-17",
            },
        )
        caps = [i.capability for i in plan.items]
        self.assertIn("eta-code-changes", caps)
        self.assertNotIn("positive-vendor-drivers", caps)

    def test_specialist_uses_master_mappings(self):
        ir = match_intents_from_text("这几个 Vendor 的 ETA Specialist 是谁", self.policy)
        plan = build_fact_plan(
            ir,
            self.policy,
            context={"countries": ["CA"], "vendors": ["A", "B"]},
        )
        caps = {i.capability for i in plan.items}
        self.assertIn("master-mappings", caps)
        self.assertTrue(any(i.post_filter and "ETAD" in i.post_filter for i in plan.items))

    def test_detail_po_lines(self):
        ir = IntentIR(
            intents=[IntentSpec("po-line-detail")],
            scope={"countries": ["US"], "vendors": ["X"]},
            raw_text="give me detail 每一行",
        )
        plan = build_fact_plan(ir, self.policy, context={"vendors": ["X"], "countries": ["US"]})
        self.assertTrue(any(i.capability == "current-high-risk-lines" for i in plan.items))

    def test_unknown_intent_gap(self):
        ir = IntentIR(intents=[IntentSpec("not-a-real-intent")], raw_text="zzz")
        plan = build_fact_plan(ir, self.policy)
        self.assertTrue(plan.capability_gap)


if __name__ == "__main__":
    unittest.main()

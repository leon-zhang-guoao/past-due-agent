import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from runtime.pastdue_kernel.fake_mcp import FakeMCP  # noqa: E402
from runtime.pastdue_kernel.fact_plan import build_fact_plan  # noqa: E402
from runtime.pastdue_kernel.kernel import KernelSession  # noqa: E402
from runtime.pastdue_kernel.policy import load_policy  # noqa: E402
from runtime.pastdue_kernel.types import (  # noqa: E402
    FailureCode,
    FactPlan,
    IntentIR,
    IntentSpec,
    WorkItem,
)


class TestKernel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = load_policy()

    def test_concurrency_cap(self):
        vendors = [f"V{i}" for i in range(5)]
        ir = IntentIR(
            intents=[IntentSpec("eta-decomposition")],
            scope={"countries": ["CA"], "vendors": vendors},
            raw_text="ETA 构成",
        )
        plan = build_fact_plan(
            ir,
            self.policy,
            context={
                "countries": ["CA"],
                "vendors": vendors,
                "target-snapshot-date": "2026-10-18",
                "previous-snapshot-date": "2026-10-17",
            },
        )
        # Only eta-code items for concurrency observation
        code_items = [i for i in plan.items if i.capability == "eta-code-changes"]
        plan2 = FactPlan(plan_id="t", items=code_items)

        in_flight = {"n": 0, "max": 0}

        def on_exec(_eql):
            in_flight["n"] += 1
            in_flight["max"] = max(in_flight["max"], in_flight["n"])
            in_flight["n"] -= 1

        mcp = FakeMCP(on_execute=on_exec)
        # Monkey: track concurrent by wrapping — FakeMCP is sync so max in_flight in kernel
        session = KernelSession(self.policy, ask="concurrency")
        session.run_plan(plan2, mcp)
        self.assertLessEqual(session.max_in_flight_seen, self.policy.kernel.concurrency_max)
        self.assertEqual(len(mcp.calls), len(code_items))

    def test_duplicate_terminal_not_reissued(self):
        item = WorkItem(
            work_key=("master-mappings", "CA", "V1"),
            capability="master-mappings",
            inputs={"country-code": "CA", "vendor-code": "V1"},
        )
        plan = FactPlan(plan_id="d", items=[item, item])
        # dedupe in builder normally; kernel should still mark terminals
        mcp = FakeMCP()
        session = KernelSession(self.policy)
        # First run
        session.run_plan(FactPlan(plan_id="d1", items=[item]), mcp)
        n1 = len(mcp.calls)
        # Second identical key
        item2 = WorkItem(
            work_key=("master-mappings", "CA", "V1"),
            capability="master-mappings",
            inputs={"country-code": "CA", "vendor-code": "V1"},
        )
        session.run_plan(FactPlan(plan_id="d2", items=[item2]), mcp)
        self.assertEqual(len(mcp.calls), n1)  # gate blocks duplicate

    def test_handle_mismatch(self):
        mcp = FakeMCP()
        session = KernelSession(self.policy)
        item = WorkItem(
            work_key=("master-mappings", "CA", "V1"),
            capability="master-mappings",
            inputs={"country-code": "CA", "vendor-code": "V1"},
        )
        session.run_plan(FactPlan(plan_id="h", items=[item]), mcp)
        bad = session.analyze("not-a-real-handle", "select 1", mcp)
        self.assertFalse(bad.get("ok"))
        self.assertEqual(bad.get("error"), FailureCode.HANDLE_MISMATCH.value)

    def test_bulkhead_retry_once(self):
        item = WorkItem(
            work_key=("master-mappings", "CA", "V9"),
            capability="master-mappings",
            inputs={"country-code": "CA", "vendor-code": "V9"},
        )
        wk = str(item.work_key)
        mcp = FakeMCP(inject={wk: ["BULKHEAD", "OK"]})
        session = KernelSession(self.policy)
        results = session.run_plan(FactPlan(plan_id="b", items=[item]), mcp)
        self.assertEqual(session.concurrency, self.policy.kernel.concurrency_degraded)
        self.assertTrue(any(r.status == FailureCode.OK for r in results))
        self.assertEqual(len(mcp.calls), 2)


if __name__ == "__main__":
    unittest.main()

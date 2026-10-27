#!/usr/bin/env python3
"""Smoke: load policy, build plan for CA Top-10 ETA share, gate-check EQL."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from runtime.pastdue_kernel import (  # noqa: E402
    build_fact_plan,
    load_policy,
    match_intents_from_text,
    preflight,
)
from runtime.pastdue_kernel.eql import render_eql  # noqa: E402


def main() -> int:
    policy = load_policy()
    print(f"policy-version: {policy.version}")
    ask = "CA Top-10 Vendor ETA Code 占比和构成"
    ir = match_intents_from_text(ask, policy)
    print(f"intents: {[i.id for i in ir.intents]}")
    plan = build_fact_plan(
        ir,
        policy,
        context={
            "countries": ["CA"],
            "vendors": ["V1", "V2"],
            "target-snapshot-date": "2026-10-18",
            "previous-snapshot-date": "2026-10-17",
        },
    )
    print(f"plan items: {len(plan.items)} gap={plan.capability_gap}")
    for item in plan.items[:6]:
        if item.inputs.get("_pending_vendors"):
            print(f"  {item.capability} PENDING_VENDORS")
            continue
        eql = render_eql(item.capability, item.inputs, policy)
        g = preflight(eql, policy=policy, capability=item.capability)
        print(f"  {item.capability} gate={'OK' if g.ok else g.reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

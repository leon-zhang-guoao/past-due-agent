"""Session trace helpers."""

from __future__ import annotations

from typing import Any

from .types import FailureCode, SessionTrace, WorkItem


def record_call(
    trace: SessionTrace,
    item: WorkItem,
    eql: str,
    status: FailureCode,
) -> None:
    trace.calls.append(
        {
            "work_key": list(item.work_key),
            "capability": item.capability,
            "eql_shape": _shape_summary(eql),
            "result": status.value,
        }
    )


def record_gate_reject(
    trace: SessionTrace,
    eql: str,
    reason: str,
    work_key: tuple | None,
) -> None:
    trace.gate_rejects.append(
        {
            "work_key": list(work_key) if work_key else None,
            "reason": reason,
            "eql_shape": _shape_summary(eql),
        }
    )


def _shape_summary(eql: str) -> str:
    s = " ".join(eql.split())
    return s if len(s) <= 240 else s[:237] + "..."


def trace_to_dict(trace: SessionTrace) -> dict[str, Any]:
    return {
        "ask": trace.ask,
        "intent_ir": trace.intent_ir,
        "fact_plan": trace.fact_plan,
        "calls": trace.calls,
        "gate_rejects": trace.gate_rejects,
        "answer_meta": trace.answer_meta,
    }

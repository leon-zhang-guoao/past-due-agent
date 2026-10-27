"""Universal Execution Kernel — worklist, concurrency, handles, retries."""

from __future__ import annotations

from typing import Any, Protocol

from .eql import render_eql
from .gate import preflight
from .policy import Policy
from .trace import record_call, record_gate_reject
from .types import (
    FactPlan,
    FailureCode,
    SessionTrace,
    WorkItem,
    WorkResult,
    WorkState,
)


class MCPClient(Protocol):
    def execute_eql(self, eql: str, *, work_key: tuple | None = None) -> dict[str, Any]: ...

    def analyze_result(self, result_handle: str, sql: str | None = None) -> dict[str, Any]: ...


class KernelSession:
    def __init__(self, policy: Policy, *, ask: str = "") -> None:
        self.policy = policy
        self.trace = SessionTrace(ask=ask)
        self.terminals: set[tuple] = set()
        self.handle_owner: dict[str, tuple] = {}  # handle → work_key
        self.concurrency = policy.kernel.concurrency_start
        self._consecutive_ok = 0
        self._in_flight = 0
        self.max_in_flight_seen = 0
        self.results: list[WorkResult] = []

    def analyze(self, result_handle: str, sql: str | None, mcp: MCPClient) -> dict[str, Any]:
        owner = self.handle_owner.get(result_handle)
        if owner is None:
            return {
                "ok": False,
                "error": FailureCode.HANDLE_MISMATCH.value,
                "detail": "handle not owned by this session",
            }
        out = mcp.analyze_result(result_handle, sql)
        if not out.get("ok"):
            err = out.get("error", "HANDLE_EXPIRED")
            code = (
                FailureCode.HANDLE_EXPIRED
                if "EXPIRED" in str(err).upper()
                else FailureCode.HANDLE_MISMATCH
            )
            return {"ok": False, "error": code.value, "detail": str(err)}
        return out

    def run_plan(self, plan: FactPlan, mcp: MCPClient) -> list[WorkResult]:
        if plan.capability_gap:
            self.results.append(
                WorkResult(
                    work_key=("capability-gap",),
                    capability="",
                    status=FailureCode.CAPABILITY_GAP,
                    detail=plan.gap_reason,
                )
            )
            return self.results

        self.trace.fact_plan = {
            "plan_id": plan.plan_id,
            "items": [
                {"work_key": list(i.work_key), "capability": i.capability} for i in plan.items
            ],
        }

        pending = list(plan.items)
        by_key = {i.work_key: i for i in pending}

        while True:
            ready = [
                i
                for i in pending
                if i.state == WorkState.PENDING
                and all(
                    by_key[d].state == WorkState.OK
                    for d in i.depends_on
                    if d in by_key
                )
            ]
            # Also allow deps that are not in plan (external)
            ready = [
                i
                for i in pending
                if i.state == WorkState.PENDING
                and all(
                    (d not in by_key) or by_key[d].state == WorkState.OK for d in i.depends_on
                )
            ]
            if not ready and not any(i.state == WorkState.IN_FLIGHT for i in pending):
                # retryables
                retryables = [i for i in pending if i.state == WorkState.FAILED_RETRYABLE]
                if not retryables:
                    break
                ready = retryables

            if not ready:
                break

            # Process up to concurrency (sequential loop simulating budget)
            slot = 0
            for item in ready:
                if slot >= self.concurrency:
                    break
                if item.state == WorkState.FAILED_RETRYABLE:
                    item.state = WorkState.PENDING
                if item.work_key in self.terminals:
                    item.state = WorkState.FAILED_FINAL
                    item.failure = FailureCode.GATE_REJECTED
                    item.failure_detail = "duplicate terminal work-key"
                    continue
                self._run_one(item, mcp)
                slot += 1

        return self.results

    def _run_one(self, item: WorkItem, mcp: MCPClient) -> None:
        if item.capability == "historical-range":
            # Marker — no Atlas call in v1
            item.state = WorkState.OK
            self.terminals.add(item.work_key)
            self.results.append(
                WorkResult(
                    work_key=item.work_key,
                    capability=item.capability,
                    status=FailureCode.OK,
                    detail="historical-range marker; use playbook history EQL outside stub",
                )
            )
            return

        if item.inputs.get("_pending_vendors"):
            item.state = WorkState.FAILED_FINAL
            item.failure = FailureCode.HOST_PARTIAL
            item.failure_detail = "vendors not expanded"
            self.terminals.add(item.work_key)
            self.results.append(
                WorkResult(
                    work_key=item.work_key,
                    capability=item.capability,
                    status=FailureCode.HOST_PARTIAL,
                    detail=item.failure_detail,
                )
            )
            return

        try:
            eql = render_eql(item.capability, item.inputs, self.policy)
        except (KeyError, ValueError) as exc:
            item.state = WorkState.FAILED_FINAL
            item.failure = FailureCode.UNREACHABLE
            item.failure_detail = str(exc)
            self.terminals.add(item.work_key)
            self.results.append(
                WorkResult(
                    work_key=item.work_key,
                    capability=item.capability,
                    status=FailureCode.UNREACHABLE,
                    detail=str(exc),
                )
            )
            return

        gate = preflight(
            eql,
            policy=self.policy,
            capability=item.capability,
            session_terminals=self.terminals,
            work_key=item.work_key,
        )
        if not gate.ok:
            record_gate_reject(self.trace, eql, gate.reason, item.work_key)
            item.state = WorkState.FAILED_FINAL
            item.failure = FailureCode.GATE_REJECTED
            item.failure_detail = gate.reason
            self.terminals.add(item.work_key)
            self.results.append(
                WorkResult(
                    work_key=item.work_key,
                    capability=item.capability,
                    status=FailureCode.GATE_REJECTED,
                    detail=gate.reason,
                )
            )
            return

        item.state = WorkState.IN_FLIGHT
        self._in_flight += 1
        self.max_in_flight_seen = max(self.max_in_flight_seen, self._in_flight)

        resp = mcp.execute_eql(eql, work_key=item.work_key)
        self._in_flight -= 1

        # coverage-unknown alone is never failure
        if resp.get("ok"):
            handle = resp.get("result_handle")
            item.result_handle = handle
            if handle:
                self.handle_owner[handle] = item.work_key
            item.state = WorkState.OK
            self.terminals.add(item.work_key)
            self._on_success()
            record_call(self.trace, item, eql, FailureCode.OK)
            self.results.append(
                WorkResult(
                    work_key=item.work_key,
                    capability=item.capability,
                    status=FailureCode.OK,
                    result_handle=handle,
                )
            )
            return

        err = str(resp.get("error", "SOURCE_ERROR")).upper()
        if "BULKHEAD" in err:
            code = FailureCode.BULKHEAD
            self.concurrency = self.policy.kernel.concurrency_degraded
            self._consecutive_ok = 0
            if item.retries < self.policy.kernel.max_retries_per_work_item:
                item.retries += 1
                item.state = WorkState.FAILED_RETRYABLE
                record_call(self.trace, item, eql, code)
                return
            item.state = WorkState.FAILED_FINAL
            item.failure = code
            self.terminals.add(item.work_key)
            record_call(self.trace, item, eql, code)
            self.results.append(
                WorkResult(
                    work_key=item.work_key,
                    capability=item.capability,
                    status=code,
                    detail="bulkhead exhausted retries",
                )
            )
            return

        if "UNREACHABLE" in err:
            code = FailureCode.UNREACHABLE
        else:
            code = FailureCode.SOURCE_ERROR
        item.state = WorkState.FAILED_FINAL
        item.failure = code
        self.terminals.add(item.work_key)
        record_call(self.trace, item, eql, code)
        self.results.append(
            WorkResult(
                work_key=item.work_key,
                capability=item.capability,
                status=code,
                detail=err,
            )
        )

    def _on_success(self) -> None:
        self._consecutive_ok += 1
        kn = self.policy.kernel
        if (
            self.concurrency < kn.concurrency_max
            and self._consecutive_ok >= kn.bulkhead_recovery_successes
        ):
            self.concurrency = min(kn.concurrency_max, self.concurrency + 1)
            self._consecutive_ok = 0

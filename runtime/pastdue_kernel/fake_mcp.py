"""In-memory MCP stand-in for unit and golden tests."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable
from uuid import uuid4


@dataclass
class FakeMCP:
    """Records execute_eql calls; returns handles or injected failures."""

    calls: list[dict[str, Any]] = field(default_factory=list)
    # work_key or eql substring → failure sequence e.g. ["BULKHEAD", "OK"]
    inject: dict[str, list[str]] = field(default_factory=dict)
    in_flight_max_seen: int = 0
    _open_handles: dict[str, dict[str, Any]] = field(default_factory=dict)
    on_execute: Callable[[str], None] | None = None

    def execute_eql(self, eql: str, *, work_key: tuple | None = None) -> dict[str, Any]:
        self.calls.append({"eql": eql, "work_key": work_key})
        if self.on_execute:
            self.on_execute(eql)

        key = str(work_key) if work_key is not None else eql
        seq = self.inject.get(key) or self.inject.get(eql)
        if seq:
            code = seq.pop(0)
            if code != "OK":
                return {"ok": False, "error": code, "result_handle": None}

        handle = f"h-{uuid4().hex[:8]}"
        self._open_handles[handle] = {"eql": eql, "work_key": work_key}
        return {"ok": True, "result_handle": handle, "coverage": "collection-coverage-unknown"}

    def analyze_result(self, result_handle: str, sql: str | None = None) -> dict[str, Any]:
        if result_handle not in self._open_handles:
            return {"ok": False, "error": "HANDLE_EXPIRED"}
        return {
            "ok": True,
            "rows": [{"_handle": result_handle}],
            "sql": sql,
        }

    def expire(self, result_handle: str) -> None:
        self._open_handles.pop(result_handle, None)

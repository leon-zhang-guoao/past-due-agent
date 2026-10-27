"""Shared types for Intent IR, Fact Plan, Kernel, Gate."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class FailureCode(str, Enum):
    OK = "OK"
    BULKHEAD = "BULKHEAD"
    HANDLE_MISMATCH = "HANDLE_MISMATCH"
    HANDLE_EXPIRED = "HANDLE_EXPIRED"
    UNREACHABLE = "UNREACHABLE"
    SOURCE_ERROR = "SOURCE_ERROR"
    HOST_PARTIAL = "HOST_PARTIAL"
    GATE_REJECTED = "GATE_REJECTED"
    CAPABILITY_GAP = "CAPABILITY_GAP"


class WorkState(str, Enum):
    PENDING = "pending"
    IN_FLIGHT = "in_flight"
    OK = "ok"
    FAILED_RETRYABLE = "failed_retryable"
    FAILED_FINAL = "failed_final"


@dataclass
class IntentSpec:
    id: str
    priority: int = 0


@dataclass
class IntentIR:
    intents: list[IntentSpec]
    scope: dict[str, Any] = field(default_factory=dict)
    ambiguities: list[str] = field(default_factory=list)
    language: str = "zh"
    raw_text: str = ""
    capability_gap: bool = False
    gap_reason: str = ""


@dataclass
class WorkItem:
    work_key: tuple
    capability: str
    inputs: dict[str, Any]
    depends_on: list[tuple] = field(default_factory=list)
    post_filter: str | None = None
    state: WorkState = WorkState.PENDING
    retries: int = 0
    result_handle: str | None = None
    failure: FailureCode | None = None
    failure_detail: str = ""


@dataclass
class FactPlan:
    plan_id: str
    items: list[WorkItem]
    capability_gap: bool = False
    gap_reason: str = ""
    meta: dict[str, Any] = field(default_factory=dict)


@dataclass
class GateResult:
    ok: bool
    reason: str = ""
    code: FailureCode = FailureCode.OK


@dataclass
class WorkResult:
    work_key: tuple
    capability: str
    status: FailureCode
    result_handle: str | None = None
    detail: str = ""
    rows: list[dict[str, Any]] | None = None


@dataclass
class SessionTrace:
    ask: str
    intent_ir: dict[str, Any] = field(default_factory=dict)
    fact_plan: dict[str, Any] = field(default_factory=dict)
    calls: list[dict[str, Any]] = field(default_factory=list)
    gate_rejects: list[dict[str, Any]] = field(default_factory=list)
    answer_meta: dict[str, Any] = field(default_factory=dict)

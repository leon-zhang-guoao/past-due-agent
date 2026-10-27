"""PastDue Kernel Runtime — machine-enforced Fact Plan + gated Atlas calls."""

from .disambiguate import apply_disambiguation, match_intents_from_text
from .fact_plan import build_fact_plan, expand_pending_vendors
from .gate import preflight
from .kernel import KernelSession
from .policy import DEFAULT_POLICY_PATH, load_policy
from .types import (
    FailureCode,
    FactPlan,
    GateResult,
    IntentIR,
    IntentSpec,
    WorkItem,
    WorkResult,
)

__all__ = [
    "DEFAULT_POLICY_PATH",
    "FailureCode",
    "FactPlan",
    "GateResult",
    "IntentIR",
    "IntentSpec",
    "KernelSession",
    "WorkItem",
    "WorkResult",
    "apply_disambiguation",
    "build_fact_plan",
    "expand_pending_vendors",
    "load_policy",
    "match_intents_from_text",
    "preflight",
]

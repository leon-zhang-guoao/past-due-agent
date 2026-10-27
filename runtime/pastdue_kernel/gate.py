"""Preflight Gate — reject illegal EQL before Atlas."""

from __future__ import annotations

import re
from typing import Iterable

from .policy import CapabilityDecl, Policy
from .types import FailureCode, GateResult

# Attr keys that look like contribution scalars when projected from entity root.
_CONTRIBUTION_SCALAR_ATTRS = re.compile(
    r":(?:pastdue\.)?vendor-contribution/(?:vendor-name|latest-|previous-|vendor-code)\b"
    r"|:latest-past-due|:previous-past-due|:latest-ratio|:previous-ratio",
    re.I,
)

_JOIN_KEYS = re.compile(
    r":(?:[a-zA-Z0-9._-]+/)?(?:eta-code-changes|eta-source-changes|master-mappings|"
    r"matching-mappings|current-owner-risks|current-high-risk-lines|"
    r"notification-recipients|positive-vendor-contributors|positive-vendor-drivers|"
    r"vendor-changes|vendor-master-mapping-rollups|all|trigger-events)\b"
)
# Any nested map key that looks like a collection pull: {:ns/name [...]
_NESTED_COLL = re.compile(r"\{:([a-zA-Z0-9._/-]+)\s+\[")


def _strip_strings(eql: str) -> str:
    return re.sub(r'"[^"\\]*(?:\\.[^"\\]*)*"', '""', eql)


def count_collections(eql: str) -> int:
    """Count join/collection attribute keys in an EQL form (heuristic)."""
    body = _strip_strings(eql)
    found = set(_JOIN_KEYS.findall(body))
    nested = set(_NESTED_COLL.findall(body))
    # Prefer nested map keys when present (more complete)
    if nested:
        return len(nested)
    return len(found)


def extract_root_entity(eql: str) -> str | None:
    """Best-effort root entity keyword from [:entity ...] or leading keyword."""
    body = _strip_strings(eql)
    m = re.search(r"\[:(?:pastdue\.)?([a-zA-Z0-9-]+)\b", body)
    if m:
        name = m.group(1)
        # Prefer full keyword if present
        m2 = re.search(r"\[:(pastdue\.[a-zA-Z0-9.-]+)\b", body)
        if m2:
            return m2.group(1).removeprefix(":")
        return f"pastdue.{name}" if not name.startswith("pastdue.") else name
    m3 = re.search(r":(pastdue\.[a-zA-Z0-9.-]+)\b", body)
    if m3:
        # First entity-like keyword that is not an attribute path with /
        for cand in re.findall(r":(pastdue\.[a-zA-Z0-9.-]+)\b", body):
            if "/" not in cand:
                return cand
    return None


def looks_like_contribution_scalar_root(eql: str) -> bool:
    body = _strip_strings(eql)
    root = extract_root_entity(eql) or ""
    if "vendor-contribution" not in root:
        # entity pull on contribution without collection
        if ":pastdue.vendor-contribution" in body and not re.search(
            r"/(?:master-mappings|current-owner-risks|current-high-risk-lines|"
            r"notification-recipients|positive-vendor-drivers)\b",
            body,
        ):
            if _CONTRIBUTION_SCALAR_ATTRS.search(body) or re.search(
                r":vendor-name|:latest-past-due-count|:previous-past-due-count", body
            ):
                return True
        return False
    # Root is contribution entity — forbid scalar attrs without a declared collection
    if re.search(
        r"/(?:master-mappings|current-owner-risks|current-high-risk-lines|"
        r"notification-recipients)\b",
        body,
    ):
        return False
    if _CONTRIBUTION_SCALAR_ATTRS.search(body) or re.search(
        r":vendor-name|:latest-past-due-count|:previous-past-due-count|"
        r":latest-past-due-ratio|:previous-past-due-ratio",
        body,
    ):
        return True
    return False


def _eql_mentions_entity(eql: str, entity: str) -> bool:
    """True if entity name appears as a keyword namespace in EQL."""
    ent = entity.removeprefix(":").removeprefix("pastdue.")
    body = _strip_strings(eql)
    patterns = [
        rf":pastdue\.{re.escape(ent)}\b",
        rf":pastdue\.{re.escape(ent)}/",
        rf"\[:pastdue\.{re.escape(ent)}\b",
    ]
    if ent == "vendor-master-mapping":
        # Also bare mapping entity without confusing with vendor-master-mapping-query
        patterns.append(r":pastdue\.vendor-master-mapping(?:/|\b)")
    return any(re.search(p, body) for p in patterns)


def preflight(
    eql: str,
    *,
    policy: Policy,
    capability: CapabilityDecl | str | None = None,
    session_terminals: Iterable[tuple] | None = None,
    work_key: tuple | None = None,
) -> GateResult:
    if not eql or not str(eql).strip():
        return GateResult(False, "empty EQL", FailureCode.GATE_REJECTED)

    cap: CapabilityDecl | None
    if isinstance(capability, str):
        cap = policy.capability(capability)
    else:
        cap = capability

    if policy.kernel.forbid_multi_collection_query and count_collections(eql) > 1:
        return GateResult(
            False,
            f"multi-collection EQL forbidden (found {count_collections(eql)} collections)",
            FailureCode.GATE_REJECTED,
        )

    root = extract_root_entity(eql)
    body = _strip_strings(eql)

    # Capability forbidden roots — mention anywhere as entity namespace
    if cap:
        for fr in cap.forbidden_roots:
            fr_n = fr.removeprefix(":")
            if _eql_mentions_entity(eql, fr_n):
                # matching-mappings legitimately uses vendor-master-mapping-query, not mapping entity
                if fr_n.endswith("vendor-master-mapping") and "vendor-master-mapping-query" in body:
                    if not re.search(r":pastdue\.vendor-master-mapping(?:/|\b)", body):
                        continue
                return GateResult(
                    False,
                    f"forbidden root {fr_n} for capability {cap.id}",
                    FailureCode.GATE_REJECTED,
                )

    # master-mappings must not use mapping entity (only vendor-contribution/…)
    if cap and cap.id == "master-mappings":
        if re.search(r":pastdue\.vendor-master-mapping(?:/|\b)", body):
            return GateResult(
                False,
                "must not start EQL on vendor-master-mapping entity for enrichment",
                FailureCode.GATE_REJECTED,
            )

    if looks_like_contribution_scalar_root(eql):
        return GateResult(
            False,
            "contribution-entity root scalars forbidden; use contributors/vendor-changes + filter",
            FailureCode.GATE_REJECTED,
        )

    # Global: mapping entity as enrichment root when capability says master-mappings
    if root and "vendor-master-mapping" in root and "vendor-contribution" not in root:
        if cap is None or cap.id in ("master-mappings", "owner-current-risks", "current-high-risk-lines"):
            if "vendor-master-mapping-query" not in root:
                return GateResult(
                    False,
                    "must not start EQL on vendor-master-mapping entity for enrichment",
                    FailureCode.GATE_REJECTED,
                )

    if work_key is not None and session_terminals is not None:
        terminals = set(session_terminals)
        if work_key in terminals:
            return GateResult(
                False,
                f"duplicate terminal work-key {work_key!r}",
                FailureCode.GATE_REJECTED,
            )

    return GateResult(True, "ok", FailureCode.OK)

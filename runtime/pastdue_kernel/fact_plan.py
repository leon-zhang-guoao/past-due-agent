"""Deterministic Fact Plan Builder: Intent IR + policy (+ context) → FactPlan."""

from __future__ import annotations

import uuid
from typing import Any

from .disambiguate import apply_disambiguation
from .policy import Policy
from .types import FactPlan, IntentIR, WorkItem


def _scope_countries(scope: dict[str, Any]) -> list[str]:
    c = scope.get("countries") or scope.get("country")
    if c is None:
        return []
    if isinstance(c, str):
        return [c]
    return list(c)


def _scope_vendors(scope: dict[str, Any], context: dict[str, Any]) -> list[str]:
    v = scope.get("vendors")
    if v == "top-n-from-drivers" or v is None:
        ctx = context.get("vendors") or context.get("top_vendors") or []
        return list(ctx)
    if isinstance(v, str):
        return [v]
    if isinstance(v, list):
        return list(v)
    return []


def _dates(scope: dict[str, Any], context: dict[str, Any]) -> tuple[str | None, str | None]:
    target = (
        scope.get("target-snapshot-date")
        or scope.get("target_snapshot_date")
        or context.get("target-snapshot-date")
        or context.get("target_snapshot_date")
    )
    prev = (
        scope.get("previous-snapshot-date")
        or scope.get("previous_snapshot_date")
        or context.get("previous-snapshot-date")
        or context.get("previous_snapshot_date")
    )
    return target, prev


def _post_filter_for(intent_id: str, policy: Policy, cap_id: str) -> str | None:
    entry = policy.intent_catalog.get(intent_id)
    if entry and entry.post_filter and cap_id == "master-mappings":
        return entry.post_filter
    for rule in policy.disambiguation_rules:
        if rule.force_intent == intent_id and rule.post_filter and cap_id in rule.force_capabilities:
            return rule.post_filter
    return None


def build_fact_plan(
    intent_ir: IntentIR,
    policy: Policy,
    context: dict[str, Any] | None = None,
) -> FactPlan:
    context = context or {}
    ir = apply_disambiguation(intent_ir, policy)
    plan_id = str(uuid.uuid4())

    if ir.capability_gap:
        return FactPlan(
            plan_id=plan_id,
            items=[],
            capability_gap=True,
            gap_reason=ir.gap_reason or "capability gap",
        )

    if not ir.intents:
        # Try unknown
        return FactPlan(
            plan_id=plan_id,
            items=[],
            capability_gap=True,
            gap_reason="no intents resolved; declare capability or report gap",
        )

    countries = _scope_countries(ir.scope) or list(context.get("countries") or [])
    vendors = _scope_vendors(ir.scope, context)
    target, prev = _dates(ir.scope, context)
    items: list[WorkItem] = []
    parent_keys: dict[str, list[tuple]] = {}

    for spec in sorted(ir.intents, key=lambda s: s.priority):
        catalog = policy.intent_catalog.get(spec.id)
        if catalog is None:
            return FactPlan(
                plan_id=plan_id,
                items=[],
                capability_gap=True,
                gap_reason=f"unknown intent id: {spec.id}",
            )
        if spec.id == "historical-range" and not catalog.capabilities:
            # Marker only — caller/playbook fills history; keep gap false with meta
            items.append(
                WorkItem(
                    work_key=("historical-range", tuple(countries)),
                    capability="historical-range",
                    inputs={"countries": countries, **{k: ir.scope.get(k) for k in ir.scope}},
                )
            )
            continue

        for cap_id in catalog.capabilities:
            if policy.capability(cap_id) is None and cap_id != "historical-range":
                return FactPlan(
                    plan_id=plan_id,
                    items=[],
                    capability_gap=True,
                    gap_reason=f"unknown capability: {cap_id}",
                )
            decl = policy.capability(cap_id)
            fan = decl.fan_out if decl else "none"
            post = _post_filter_for(spec.id, policy, cap_id)

            if fan == "per-vendor":
                if not vendors:
                    # Placeholder parent dependency: enqueue country-level contributors first if needed
                    for cc in countries or ["_"]:
                        # Defer concrete vendor items until context supplies vendors
                        wk = (cap_id, cc, "*pending-vendors*")
                        items.append(
                            WorkItem(
                                work_key=wk,
                                capability=cap_id,
                                inputs={
                                    "country-code": cc if cc != "_" else None,
                                    "target-snapshot-date": target,
                                    "previous-snapshot-date": prev,
                                    "_pending_vendors": True,
                                },
                                post_filter=post,
                            )
                        )
                else:
                    for cc in countries or ["_"]:
                        for vc in vendors:
                            inputs = {
                                "country-code": None if cc == "_" else cc,
                                "vendor-code": vc,
                                "target-snapshot-date": target,
                                "previous-snapshot-date": prev,
                            }
                            wk = (cap_id, inputs.get("country-code"), vc, target, prev)
                            dep: list[tuple] = []
                            # Depend on contributors if present
                            for pk in parent_keys.get("positive-vendor-contributors", []):
                                if pk[1] == inputs.get("country-code"):
                                    dep.append(pk)
                            items.append(
                                WorkItem(
                                    work_key=wk,
                                    capability=cap_id,
                                    inputs={k: v for k, v in inputs.items() if v is not None},
                                    depends_on=dep,
                                    post_filter=post,
                                )
                            )
            else:
                for cc in countries or [None]:
                    inputs = {"country-code": cc} if cc else {}
                    if target:
                        inputs["target-snapshot-date"] = target
                    if prev:
                        inputs["previous-snapshot-date"] = prev
                    # optional empty-string filters (mapping / current edges)
                    if cap_id in (
                        "matching-mappings",
                        "current-vendor-past-due-ranks",
                        "current-vendor-alert-type-counts",
                        "current-detail-slice",
                    ):
                        inputs.setdefault(
                            "department-type-filter",
                            context.get("department-type-filter", ""),
                        )
                        inputs.setdefault(
                            "master-primary-filter",
                            context.get("master-primary-filter", ""),
                        )
                        inputs.setdefault(
                            "master-manager-filter",
                            context.get("master-manager-filter", ""),
                        )
                    if cap_id in ("current-vendor-alert-type-counts", "current-detail-slice"):
                        inputs.setdefault(
                            "vendor-code-filter", context.get("vendor-code-filter", "")
                        )
                    if cap_id == "current-detail-slice":
                        for fk in (
                            "eta-code-filter",
                            "alert-type-filter",
                            "is-pastdue-filter",
                            "risk-level-filter",
                        ):
                            inputs.setdefault(fk, context.get(fk, ""))
                    wk = (cap_id, cc)
                    items.append(
                        WorkItem(
                            work_key=wk,
                            capability=cap_id,
                            inputs={k: v for k, v in inputs.items() if v is not None},
                            post_filter=post,
                        )
                    )
                    parent_keys.setdefault(cap_id, []).append(wk)

    # Specialist rule: if vendors known and plan only has matching-mappings for specialist ask, upgrade
    text = (ir.raw_text or "").lower()
    if vendors and any(s.id == "eta-specialist" for s in ir.intents):
        only_matching = [i for i in items if i.capability == "matching-mappings"]
        has_master = any(i.capability == "master-mappings" for i in items)
        if only_matching and not has_master:
            items = [i for i in items if i.capability != "matching-mappings"]
            for cc in countries or ["_"]:
                for vc in vendors:
                    items.append(
                        WorkItem(
                            work_key=("master-mappings", None if cc == "_" else cc, vc),
                            capability="master-mappings",
                            inputs={
                                k: v
                                for k, v in {
                                    "country-code": None if cc == "_" else cc,
                                    "vendor-code": vc,
                                }.items()
                                if v is not None
                            },
                            post_filter="department-type = ETAD",
                        )
                    )

    # Drop pending vendor stubs when we have concrete vendors (cleanup)
    if vendors:
        items = [i for i in items if not i.inputs.get("_pending_vendors")]

    # Dedupe by work_key preserving order
    seen_keys: set[tuple] = set()
    deduped: list[WorkItem] = []
    for it in items:
        if it.work_key in seen_keys:
            continue
        seen_keys.add(it.work_key)
        deduped.append(it)

    return FactPlan(
        plan_id=plan_id,
        items=deduped,
        meta={"raw_text": ir.raw_text, "intent_ids": [s.id for s in ir.intents]},
    )


def expand_pending_vendors(plan: FactPlan, vendors: list[str], policy: Policy) -> FactPlan:
    """Replace *pending-vendors* stubs with per-vendor work items."""
    new_items: list[WorkItem] = []
    for it in plan.items:
        if it.inputs.get("_pending_vendors") or (
            len(it.work_key) >= 3 and it.work_key[2] == "*pending-vendors*"
        ):
            cc = it.inputs.get("country-code")
            target = it.inputs.get("target-snapshot-date")
            prev = it.inputs.get("previous-snapshot-date")
            for vc in vendors:
                inputs = {
                    k: v
                    for k, v in {
                        "country-code": cc,
                        "vendor-code": vc,
                        "target-snapshot-date": target,
                        "previous-snapshot-date": prev,
                    }.items()
                    if v is not None
                }
                new_items.append(
                    WorkItem(
                        work_key=(it.capability, cc, vc, target, prev),
                        capability=it.capability,
                        inputs=inputs,
                        post_filter=it.post_filter,
                    )
                )
        else:
            new_items.append(it)
    return FactPlan(
        plan_id=plan.plan_id,
        items=new_items,
        capability_gap=plan.capability_gap,
        gap_reason=plan.gap_reason,
        meta=plan.meta,
    )

"""Render single-collection EQL from capability templates + inputs."""

from __future__ import annotations

from typing import Any

from .policy import CapabilityDecl, Policy


def _ident(entity: str, *idents: tuple[str, str]) -> str:
    """Build [:entity/id "v" ...] vector fragment."""
    parts = [f":{entity}"]
    for attr, val in idents:
        if val is None or val == "":
            continue
        parts.append(f':{attr} "{val}"')
    return "[" + " ".join(parts) + "]"


def render_eql(
    capability_id: str,
    inputs: dict[str, Any],
    policy: Policy,
) -> str:
    cap = policy.capability(capability_id)
    if cap is None:
        raise KeyError(f"unknown capability: {capability_id}")
    return render_eql_for_cap(cap, inputs)


def render_eql_for_cap(cap: CapabilityDecl, inputs: dict[str, Any]) -> str:
    """Produce a single-collection EQL pull. Shape is Kernel-owned."""
    entity = cap.entity
    coll = cap.collection
    cc = inputs.get("country-code") or inputs.get("country_code")
    vc = inputs.get("vendor-code") or inputs.get("vendor_code")
    target = inputs.get("target-snapshot-date") or inputs.get("target_snapshot_date")
    prev = inputs.get("previous-snapshot-date") or inputs.get("previous_snapshot_date")
    recipient = inputs.get("recipient-type") or inputs.get("recipient_type")

    if cap.id == "country-monitor-all":
        if cc:
            return (
                f"[:find (pull ?e [{{:{entity}/all [*]}}]) "
                f':where [?e :{entity}/country-code "{cc}"]]'
            )
        return f"[:find (pull ?e [{{:{entity}/all [*]}}]) :where [?e]]"

    if coll is None:
        raise ValueError(f"capability {cap.id} has no collection in entry {cap.entry}")

    # Historical vendor ETA — parent historical-vendor-contribution
    if cap.id in ("eta-code-changes", "eta-source-changes"):
        missing = [k for k in ("country-code", "vendor-code", "target-snapshot-date", "previous-snapshot-date")
                   if not (inputs.get(k) or inputs.get(k.replace("-", "_")))]
        # normalize check
        if not all([cc, vc, target, prev]):
            raise ValueError(f"{cap.id} requires country, vendor, target, previous dates; missing inputs")
        ident = (
            f'[:pastdue.historical-vendor-contribution '
            f':country-code "{cc}" :vendor-code "{vc}" '
            f':target-snapshot-date "{target}" :previous-snapshot-date "{prev}"]'
        )
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f":where [?e :pastdue.historical-vendor-contribution/country-code \"{cc}\"] "
            f"[?e :pastdue.historical-vendor-contribution/vendor-code \"{vc}\"] "
            f"[?e :pastdue.historical-vendor-contribution/target-snapshot-date \"{target}\"] "
            f"[?e :pastdue.historical-vendor-contribution/previous-snapshot-date \"{prev}\"]]"
        )

    if cap.id in (
        "master-mappings",
        "owner-current-risks",
        "current-high-risk-lines",
    ):
        if not cc or not vc:
            raise ValueError(f"{cap.id} requires country-code and vendor-code")
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f':where [?e :{entity}/country-code "{cc}"] '
            f'[?e :{entity}/vendor-code "{vc}"]]'
        )

    if cap.id == "notification-recipients":
        if not cc or not vc or not recipient:
            raise ValueError("notification-recipients requires country, vendor, recipient-type")
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f':where [?e :{entity}/country-code "{cc}"] '
            f'[?e :{entity}/vendor-code "{vc}"] '
            f'[?e :{entity}/recipient-type "{recipient}"]]'
        )

    if cap.id in ("positive-vendor-contributors", "vendor-changes", "mapping-rollups"):
        if not cc:
            raise ValueError(f"{cap.id} requires country-code")
        # Prefer country-monitor entry when that is the declared entity
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f':where [?e :{entity}/country-code "{cc}"]]'
        )

    if cap.id == "matching-mappings":
        if not cc:
            raise ValueError("matching-mappings requires country-code")
        dept = inputs.get("department-type-filter", "")
        primary = inputs.get("master-primary-filter", "")
        manager = inputs.get("master-manager-filter", "")
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f':where [?e :{entity}/country-code "{cc}"] '
            f'[?e :{entity}/department-type-filter "{dept}"] '
            f'[?e :{entity}/master-primary-filter "{primary}"] '
            f'[?e :{entity}/master-manager-filter "{manager}"]]'
        )

    if cap.id == "current-vendor-past-due-ranks":
        if not cc:
            raise ValueError("current-vendor-past-due-ranks requires country-code")
        dept = inputs.get("department-type-filter", "")
        primary = inputs.get("master-primary-filter", "")
        manager = inputs.get("master-manager-filter", "")
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f':where [?e :{entity}/country-code "{cc}"] '
            f'[?e :{entity}/department-type-filter "{dept}"] '
            f'[?e :{entity}/master-primary-filter "{primary}"] '
            f'[?e :{entity}/master-manager-filter "{manager}"]]'
        )

    if cap.id == "current-vendor-alert-type-counts":
        if not cc:
            raise ValueError("current-vendor-alert-type-counts requires country-code")
        vcf = inputs.get("vendor-code-filter", "")
        dept = inputs.get("department-type-filter", "")
        primary = inputs.get("master-primary-filter", "")
        manager = inputs.get("master-manager-filter", "")
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) "
            f':where [?e :{entity}/country-code "{cc}"] '
            f'[?e :{entity}/vendor-code-filter "{vcf}"] '
            f'[?e :{entity}/department-type-filter "{dept}"] '
            f'[?e :{entity}/master-primary-filter "{primary}"] '
            f'[?e :{entity}/master-manager-filter "{manager}"]]'
        )

    if cap.id == "current-detail-slice":
        if not cc:
            raise ValueError("current-detail-slice requires country-code")
        filters = {
            "vendor-code-filter": inputs.get("vendor-code-filter", ""),
            "eta-code-filter": inputs.get("eta-code-filter", ""),
            "alert-type-filter": inputs.get("alert-type-filter", ""),
            "is-pastdue-filter": inputs.get("is-pastdue-filter", ""),
            "risk-level-filter": inputs.get("risk-level-filter", ""),
            "department-type-filter": inputs.get("department-type-filter", ""),
            "master-primary-filter": inputs.get("master-primary-filter", ""),
            "master-manager-filter": inputs.get("master-manager-filter", ""),
        }
        where = [f'[?e :{entity}/country-code "{cc}"]']
        for attr, val in filters.items():
            where.append(f'[?e :{entity}/{attr} "{val}"]')
        return (
            f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) :where {' '.join(where)}]"
        )

    # Generic fallback: single collection on entity with country (+ vendor if present)
    where = []
    if cc:
        where.append(f'[?e :{entity}/country-code "{cc}"]')
    if vc:
        where.append(f'[?e :{entity}/vendor-code "{vc}"]')
    if not where:
        where.append("[?e]")
    return (
        f"[:find (pull ?e [{{:{entity}/{coll} [*]}}]) :where {' '.join(where)}]"
    )

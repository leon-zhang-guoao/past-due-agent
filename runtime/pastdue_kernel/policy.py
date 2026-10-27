"""Load and normalize policy.edn for the Kernel Runtime."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .edn_util import keyword_name, read_edn

DEFAULT_POLICY_PATH = (
    Path(__file__).resolve().parents[2]
    / "skill"
    / "pastdue-analysis"
    / "references"
    / "policy.edn"
)


def _kw(value: Any) -> str:
    return keyword_name(value)


def _kw_list(values: Any) -> list[str]:
    if not values:
        return []
    if isinstance(values, list):
        return [_kw(v) for v in values]
    return [_kw(values)]


@dataclass
class CapabilityDecl:
    id: str
    entry: str
    required_inputs: list[str] = field(default_factory=list)
    forbidden_roots: list[str] = field(default_factory=list)
    shares_global_budget: bool = True
    fan_out: str = "none"
    notes: str = ""
    completeness_notes: str = ""
    post_filter_notes: str = ""
    alt_entries: list[str] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def entity(self) -> str:
        if "/" in self.entry:
            return self.entry.split("/", 1)[0]
        return self.entry

    @property
    def collection(self) -> str | None:
        if "/" in self.entry:
            return self.entry.split("/", 1)[1]
        return None


@dataclass
class KernelKnobs:
    concurrency_start: int = 2
    concurrency_max: int = 2
    concurrency_degraded: int = 1
    bulkhead_recovery_successes: int = 3
    max_retries_per_work_item: int = 1
    forbid_multi_collection_query: bool = True
    require_reachable_entry: bool = True
    forbid_guess_root_on_empty_search: bool = True
    coverage_unknown_is_failure: bool = False


@dataclass
class DisambiguationRule:
    id: str
    signals: list[str]
    force_intent: str | None = None
    force_capabilities: list[str] = field(default_factory=list)
    forbid_capabilities: list[str] = field(default_factory=list)
    forbid_roots: list[str] = field(default_factory=list)
    forbid_sole_capability_when_vendors_known: list[str] = field(default_factory=list)
    post_filter: str | None = None
    forbid_pattern: str | None = None
    forbid_multi_collection_owner_po: bool = False
    forbid_as_share_of_past_due: bool = False
    notes: str = ""


@dataclass
class IntentCatalogEntry:
    id: str
    capabilities: list[str]
    synonyms: list[str] = field(default_factory=list)
    notes: str = ""
    post_filter: str | None = None
    forbidden_pattern: str | None = None
    default_for_ambiguous_detail: bool = False


@dataclass
class Policy:
    version: str
    kernel: KernelKnobs
    capabilities: dict[str, CapabilityDecl]
    intent_catalog: dict[str, IntentCatalogEntry]
    disambiguation_rules: list[DisambiguationRule]
    raw: dict[str, Any] = field(default_factory=dict)

    def capability(self, cap_id: str) -> CapabilityDecl | None:
        return self.capabilities.get(_kw(cap_id))


def _parse_capability(cap_id: str, body: dict[str, Any]) -> CapabilityDecl:
    entry = _kw(body.get(":entry") or body.get("entry") or "")
    return CapabilityDecl(
        id=_kw(cap_id),
        entry=entry,
        required_inputs=_kw_list(body.get(":required-inputs") or body.get("required-inputs")),
        forbidden_roots=_kw_list(body.get(":forbidden-roots") or body.get("forbidden-roots")),
        shares_global_budget=bool(
            body.get(":shares-global-budget", body.get("shares-global-budget", True))
        ),
        fan_out=_kw(body.get(":fan-out") or body.get("fan-out") or "none"),
        notes=str(body.get(":notes") or body.get("notes") or ""),
        completeness_notes=str(
            body.get(":completeness-notes") or body.get("completeness-notes") or ""
        ),
        post_filter_notes=str(
            body.get(":post-filter-notes") or body.get("post-filter-notes") or ""
        ),
        alt_entries=_kw_list(body.get(":alt-entries") or body.get("alt-entries")),
        raw=body,
    )


def _parse_kernel(body: dict[str, Any] | None) -> KernelKnobs:
    body = body or {}
    return KernelKnobs(
        concurrency_start=int(body.get(":concurrency-start", body.get("concurrency-start", 2))),
        concurrency_max=int(body.get(":concurrency-max", body.get("concurrency-max", 2))),
        concurrency_degraded=int(
            body.get(":concurrency-degraded", body.get("concurrency-degraded", 1))
        ),
        bulkhead_recovery_successes=int(
            body.get(
                ":bulkhead-recovery-successes",
                body.get("bulkhead-recovery-successes", 3),
            )
        ),
        max_retries_per_work_item=int(
            body.get(
                ":max-retries-per-work-item",
                body.get("max-retries_per_work_item", 1),
            )
        ),
        forbid_multi_collection_query=bool(
            body.get(
                ":forbid-multi-collection-query",
                body.get("forbid-multi-collection-query", True),
            )
        ),
        require_reachable_entry=bool(
            body.get(
                ":require-reachable-entry",
                body.get("require-reachable-entry", True),
            )
        ),
        forbid_guess_root_on_empty_search=bool(
            body.get(
                ":forbid-guess-root-on-empty-search",
                body.get("forbid-guess-root-on-empty-search", True),
            )
        ),
        coverage_unknown_is_failure=bool(
            body.get(
                ":coverage-unknown-is-failure",
                body.get("coverage-unknown-is-failure", False),
            )
        ),
    )


def _parse_disambiguation(block: dict[str, Any] | None) -> list[DisambiguationRule]:
    if not block:
        return []
    rules_raw = block.get(":rules") or block.get("rules") or []
    out: list[DisambiguationRule] = []
    for r in rules_raw:
        if not isinstance(r, dict):
            continue
        out.append(
            DisambiguationRule(
                id=_kw(r.get(":id") or r.get("id") or ""),
                signals=list(r.get(":signals") or r.get("signals") or []),
                force_intent=_kw(r.get(":force-intent") or r.get("force-intent") or "")
                or None,
                force_capabilities=_kw_list(
                    r.get(":force-capabilities") or r.get("force-capabilities")
                ),
                forbid_capabilities=_kw_list(
                    r.get(":forbid-capabilities") or r.get("forbid-capabilities")
                ),
                forbid_roots=_kw_list(r.get(":forbid-roots") or r.get("forbid-roots")),
                forbid_sole_capability_when_vendors_known=_kw_list(
                    r.get(":forbid-sole-capability-when-vendors-known")
                    or r.get("forbid-sole-capability-when-vendors-known")
                ),
                post_filter=(r.get(":post-filter") or r.get("post-filter") or None),
                forbid_pattern=_kw(r.get(":forbid-pattern") or r.get("forbid-pattern") or "")
                or None,
                forbid_multi_collection_owner_po=bool(
                    r.get(
                        ":forbid-multi-collection-owner-po",
                        r.get("forbid-multi-collection-owner-po", False),
                    )
                ),
                forbid_as_share_of_past_due=bool(
                    r.get(
                        ":forbid-as-share-of-past-due",
                        r.get("forbid-as-share-of-past-due", False),
                    )
                ),
                notes=str(r.get(":notes") or r.get("notes") or ""),
            )
        )
    return out


def _parse_intent_catalog(block: dict[str, Any] | None) -> dict[str, IntentCatalogEntry]:
    if not block:
        return {}
    out: dict[str, IntentCatalogEntry] = {}
    for key, body in block.items():
        if not isinstance(body, dict):
            continue
        iid = _kw(key)
        out[iid] = IntentCatalogEntry(
            id=iid,
            capabilities=_kw_list(body.get(":capabilities") or body.get("capabilities")),
            synonyms=list(body.get(":synonyms") or body.get("synonyms") or []),
            notes=str(body.get(":notes") or body.get("notes") or ""),
            post_filter=body.get(":post-filter") or body.get("post-filter"),
            forbidden_pattern=_kw(
                body.get(":forbidden-pattern") or body.get("forbidden-pattern") or ""
            )
            or None,
            default_for_ambiguous_detail=bool(
                body.get(
                    ":default-for-ambiguous-detail",
                    body.get("default-for-ambiguous-detail", False),
                )
            ),
        )
    return out


def load_policy(path: Path | str | None = None) -> Policy:
    path = Path(path) if path else DEFAULT_POLICY_PATH
    raw = read_edn(path)
    if not isinstance(raw, dict):
        raise ValueError(f"policy root must be a map: {path}")

    plane = raw.get(":execution-plane") or raw.get("execution-plane") or {}
    caps_raw = plane.get(":capabilities") or plane.get("capabilities") or {}
    capabilities: dict[str, CapabilityDecl] = {}
    for cid, body in caps_raw.items():
        if isinstance(body, dict):
            decl = _parse_capability(cid, body)
            capabilities[decl.id] = decl

    kernel_raw = plane.get(":kernel") or plane.get("kernel")
    version = str(raw.get(":policy-version") or raw.get("policy-version") or "")

    return Policy(
        version=version,
        kernel=_parse_kernel(kernel_raw if isinstance(kernel_raw, dict) else {}),
        capabilities=capabilities,
        intent_catalog=_parse_intent_catalog(
            raw.get(":intent-catalog") or raw.get("intent-catalog")
        ),
        disambiguation_rules=_parse_disambiguation(
            raw.get(":disambiguation") or raw.get("disambiguation")
        ),
        raw=raw,
    )

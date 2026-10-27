"""Apply hard disambiguation rules from policy to Intent IR / text."""

from __future__ import annotations

from .policy import Policy
from .types import IntentIR, IntentSpec


def apply_disambiguation(intent_ir: IntentIR, policy: Policy) -> IntentIR:
    text = (intent_ir.raw_text or "").lower()
    if not text and not intent_ir.intents:
        return intent_ir

    forced: list[IntentSpec] = []
    seen: set[str] = set()
    matched_rule_ids: list[str] = []

    for rule in policy.disambiguation_rules:
        hit = False
        for sig in rule.signals:
            if sig.lower() in text or sig in (intent_ir.raw_text or ""):
                hit = True
                break
        if not hit:
            continue
        matched_rule_ids.append(rule.id)
        if rule.force_intent and rule.force_intent not in seen:
            forced.append(IntentSpec(id=rule.force_intent, priority=0))
            seen.add(rule.force_intent)

    # Ambiguous detail
    if "detail" in intent_ir.ambiguities or (
        "detail" in text and "po-line-detail" not in seen
    ):
        for iid, entry in policy.intent_catalog.items():
            if entry.default_for_ambiguous_detail and iid not in seen:
                forced.append(IntentSpec(id=iid, priority=1))
                seen.add(iid)

    if not forced:
        return intent_ir

    # Merge: forced intents first, then originals not overridden by force
    force_ids = {s.id for s in forced}
    rest = [s for s in intent_ir.intents if s.id not in force_ids]
    # If eta-share rule matched, drop vendor-drivers when used as share confusion
    for rule in policy.disambiguation_rules:
        if rule.id not in matched_rule_ids:
            continue
        if rule.forbid_as_share_of_past_due:
            rest = [s for s in rest if s.id != "vendor-drivers"]

    merged = forced + rest
    return IntentIR(
        intents=merged,
        scope=dict(intent_ir.scope),
        ambiguities=list(intent_ir.ambiguities),
        language=intent_ir.language,
        raw_text=intent_ir.raw_text,
        capability_gap=intent_ir.capability_gap,
        gap_reason=intent_ir.gap_reason,
    )


def match_intents_from_text(text: str, policy: Policy) -> IntentIR:
    """Deterministic synonym match (no LLM)."""
    found: list[IntentSpec] = []
    seen: set[str] = set()
    lower = text.lower()
    for iid, entry in policy.intent_catalog.items():
        for syn in entry.synonyms:
            if syn.lower() in lower or syn in text:
                if iid not in seen:
                    found.append(IntentSpec(id=iid, priority=len(found)))
                    seen.add(iid)
                break
    ir = IntentIR(intents=found, raw_text=text)
    return apply_disambiguation(ir, policy)

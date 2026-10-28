# Architecture — Agent3.3.0

User intent → analysis spec → governed Atlas facts → complete reading/binding →
rule application → standard or composed modules. No added runtime service.

Shared business definitions: Skill references/metric-definitions.md and policy.edn.
Facts/schema: Atlas combined graph and Skill data-contract.md.
Reading: atlas-query-playbook.md. Presentation: report-format.md only.
Diagnostics: eta-diagnostic-rules.edn. Completion: completion-contract.md.
Full design/tradeoffs: docs/STRUCTURED_REPORTS_3_3.md.

SQL retains deterministic computation, not bilingual Markdown narration. Existing
structured report preset and explore share calculations; presets don't limit free QA.
Result reader may still truncate short-text fields; row/cell/source checks are separate.
Checks are procedures/evidence, not a host-enforced gate. Final layout is model-
generated; deterministic byte equality is not promised. Live pilot is pending.

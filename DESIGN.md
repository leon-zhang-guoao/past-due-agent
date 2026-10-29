# Architecture — Agent3.3.7

User intent → analysis spec → governed Atlas facts → complete reading/binding →
rule application → standard or composed modules. No added runtime service.

Shared business definitions: Skill references/metric-definitions.md and policy.edn.
Facts/schema: Atlas combined graph and Skill data-contract.md.
Source queries: atlas-query-playbook.md. Typed retained reads: result-reader.md.
Presentation: report-format.md only.
Diagnostics: eta-diagnostic-rules.edn. Completion: completion-contract.md.
Full design/tradeoffs: docs/STRUCTURED_REPORTS_3_3.md.

SQL retains deterministic computation, not bilingual Markdown narration. Existing
structured report preset and explore share calculations; presets don't limit free QA.
Result reader may still truncate short-text fields; row/cell/source checks are separate.
Checks are procedures/evidence, not a host-enforced gate. Final layout is model-
generated; deterministic byte equality is not promised. Live pilot is pending.

3.3.7 corrections and staged live gates: docs/RESULT_READER_FIX_3_3_7.md.
There is no installed MCP serializer; malformed host arguments are an external
call-boundary issue, not something Atlas business SQL can repair. Country Control
uses dedicated Country fields; no zero-total reconciliation waiver.

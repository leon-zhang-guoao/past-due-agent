# Architecture — Agent3.5.1

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


Authorized HTML: verified analysis bindings + provenance → native registered
business-storyteller sub-agent → actual written artifact → parent readback/content
comparison → user artifact links. Default DAILY/TEST stops after Markdown plus one
HTML offer. See docs/HTML_SUB_AGENT_3_5_1.md and the HTML Skill's sub-agent-contract.
The child changes presentation, not business facts or query scope.

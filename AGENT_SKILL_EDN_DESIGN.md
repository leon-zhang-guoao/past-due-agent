# Agent / Skill / EDN responsibilities — v3

- Agent Prompt: governed role, current/historical boundary, no fabricated facts.
- Skill: analysis specification, daily/history/test/current workflows, required
  evidence checks, diagnosis and report assembly; no Python runtime invocation.
- Policy: PST weekdays, 10% trigger, fixed positive Top 10, ETA Top 5+Other.
- Atlas EDN: reachable parameterized facts, fixed pairs, batched independent
  dimensions, real identity, deterministic ranks/Other/totals and check records.
- Diagnostic rules: ETA meaning and verification suggestions, not proven causes.
- Display contract: reusable consistent modules, generic declared combinations.
- Release manifest: exact hashes/required entries, separate offline and live status.

Detailed rationale: `docs/PASTDUE_AGENT_REDESIGN.md`. Concrete APIs/deployment:
`docs/API_V3.md`, `docs/DEPLOYMENT_V3.md`. Current Owner/contacts/PO are optional;
contact display only. No Kernel, mail sender or external service is presumed.

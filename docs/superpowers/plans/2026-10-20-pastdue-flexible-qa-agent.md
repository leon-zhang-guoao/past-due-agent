> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# Flexible Q&A Agent Implementation Plan

**Spec:** `docs/superpowers/specs/2026-10-20-pastdue-flexible-qa-agent-design.md`  
**Approach:** C (intent → Fact Plan → Kernel → compose)

## Done (P1–P3)

- [x] SP: Q&A identity; intent→plan→compose; contribution-root scalar ban; detail default
- [x] Skill: Interactive path + intent table + 40979-style Fact Plan example
- [x] Playbook: vendor metrics dictionary; split Owner / PO templates
- [x] policy 1.6.0: `:intent-catalog` + `:vendor-changes` capability
- [x] report-format interactive composition; ACCEPTANCE; layering Related

## Deploy

`agent/SYSTEM_PROMPT.md` + entire `skill/pastdue-analysis/`. Atlas EDN unchanged for this pass.
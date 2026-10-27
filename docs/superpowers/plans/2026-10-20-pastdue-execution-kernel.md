> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# Universal Execution Kernel Implementation Plan

**Goal:** Replace ETA-only adaptive plane with a default-on Execution Kernel so new Atlas scenarios inherit worklist, shared concurrency, handle ownership, and reachable-entry rules.

**Spec:** `docs/superpowers/specs/2026-10-20-pastdue-execution-kernel-design.md`

## Done in this pass

- [x] Design spec
- [x] `policy.edn` 1.5.0 `:execution-plane` + `:capabilities`
- [x] Playbook kernel + org forbidden root
- [x] SP / SKILL / report-format / ACCEPTANCE alignment

## Deploy

Redeploy `agent/SYSTEM_PROMPT.md` + entire `skill/pastdue-analysis/`. Atlas EDN unchanged for this change.
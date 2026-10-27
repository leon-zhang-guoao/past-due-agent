> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# Adaptive Execution Plane Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Encode the Adaptive Execution Plane (contract + adaptive scheduler + narrative) into policy, Skill, playbook, SP, and report-format so daily ETA drilldowns stop bulkhead/handle/nested failures without forcing blunt always-serial behavior.

**Architecture:** Policy owns knobs; playbook owns the state machine and anti-examples; Skill wires daily/historical steps; SP keeps a short completion contract; report-format separates coverage-unknown from partial.

**Tech Stack:** Markdown + EDN Skill/policy only (no Atlas/CH code).

**Spec:** `docs/superpowers/specs/2026-10-20-pastdue-adaptive-execution-plane-design.md`

## File map

| File | Responsibility |
|---|---|
| `skill/pastdue-analysis/references/policy.edn` | `:eta-execution` knobs |
| `skill/pastdue-analysis/references/atlas-query-playbook.md` | Execution plane + anti-examples |
| `skill/pastdue-analysis/SKILL.md` | Daily/historical ETA steps → scheduler |
| `agent/SYSTEM_PROMPT.md` | Slim completion contract |
| `skill/pastdue-analysis/references/report-format.md` | Narrative / partial rules |

## Task 1: policy.edn

Add `:eta-execution` map per spec §5. Keep existing `:eta-selection`. Bump `:policy-version` minor.

## Task 2: playbook

Replace “Prefer serial (or at most 1–2)” ETA section with Adaptive Execution Plane: worklist, concurrency state machine, handle checks, anti-examples. Keep EQL templates unchanged (still single-collection).

## Task 3: SKILL.md

Daily + historical steps: reference policy knobs; worklist; forbid combine; tiered enqueue; single-response still requires full Top-N when Atlas allows.

## Task 4: SYSTEM_PROMPT.md

Rewrite Completion contract to worklist + taxonomy + adaptive scheduler (pointer to Skill/policy).

## Task 5: report-format.md

Evidence/coverage: coverage-unknown ≠ partial; status codes; driver note prefers Tier A complete rows.

## Task 6: Alignment check

Grep for contradictory “always serial only” / combined query allowance; ensure R1 org text untouched.

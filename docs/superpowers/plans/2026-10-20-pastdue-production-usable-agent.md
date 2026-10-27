> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Production-Usable Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a machine-enforced PastDue Kernel Runtime (Intent IR → Fact Plan → gated EQL → typed results) so the agent is production-usable under interactive Q&A, not prompt-only.

**Architecture:** Deterministic Python package `runtime/pastdue_kernel` loads `policy.edn`, builds plans, runs a worklist kernel against a pluggable MCP client, and rejects illegal EQL via Preflight Gate. Agent SP/Skill are thinned to call kernel APIs. Golden eval scripts prove G1–G8 call shapes.

**Tech Stack:** Python 3.12+, existing EDN parser patterns from `scripts/validate_bundle.py`, stdlib `unittest`, no new external deps.

**Spec:** `docs/superpowers/specs/2026-10-20-pastdue-production-usable-agent-design.md`

## Global Constraints

- Atlas graph stays strict; never loosen EDN to silence gate rejects.
- LLM must not choose raw Atlas roots for covered intents; Kernel templates only.
- `collection-coverage-unknown` is never a work-item failure.
- Shared concurrency defaults: start=2, max=2, degraded=1; ≤1 retry per work item.
- Do not commit unless the user explicitly asks.
- Respond to the user in Chinese-simplified for status updates.

## File structure

| Path | Responsibility |
|---|---|
| `runtime/pastdue_kernel/__init__.py` | Public exports |
| `runtime/pastdue_kernel/edn_util.py` | Load/parse policy EDN (reuse validate_bundle tokenizer/parser) |
| `runtime/pastdue_kernel/types.py` | IntentIR, FactPlan, WorkItem, WorkResult, FailureCode |
| `runtime/pastdue_kernel/policy.py` | Load execution-plane + intent-catalog + disambiguation |
| `runtime/pastdue_kernel/disambiguate.py` | Apply hard signal → intent overrides |
| `runtime/pastdue_kernel/fact_plan.py` | `build_fact_plan(intent_ir, policy, context)` |
| `runtime/pastdue_kernel/eql.py` | Capability → single-collection EQL string |
| `runtime/pastdue_kernel/gate.py` | Preflight checks on EQL + session worklist |
| `runtime/pastdue_kernel/kernel.py` | Worklist runner, concurrency, handles, retries |
| `runtime/pastdue_kernel/trace.py` | Session trace record |
| `runtime/pastdue_kernel/fake_mcp.py` | In-memory MCP for unit/golden tests |
| `tests/kernel/test_*.py` | Unit tests |
| `tests/kernel/golden/*.json` | Golden ask → expected capability sequences |
| `scripts/run_kernel_tests.py` | Test runner entry |
| `scripts/run_golden_eval.py` | G1–G8 eval |
| `skill/.../policy.edn` | Disambiguation + notes; version bump |
| `agent/SYSTEM_PROMPT.md` | Thin SP |
| `skill/pastdue-analysis/SKILL.md` | Kernel tool path |
| `ACCEPTANCE_TESTS.md` | Point at golden eval |

---

### Task 1: Policy machine pack (P1)

**Files:**
- Modify: `skill/pastdue-analysis/references/policy.edn`
- Modify: `docs/superpowers/specs/2026-10-20-pastdue-production-usable-agent-design.md` (Status → Approved)

- [x] **Step 1:** Bump `:policy-version` to `"1.7.0"`.

- [x] **Step 2:** Add `:disambiguation` map under root (after `:intent-catalog`) with signal patterns and force/forbid rules from spec §5. Add `:completeness-notes` on `:matching-mappings` and `:eta-code-changes` capabilities.

- [x] **Step 3:** Add `:intent-catalog` entries / synonym strings for 占比、构成、share if missing; ensure `:eta-decomposition` synonyms include them.

- [x] **Step 4:** Run `python scripts/validate_bundle.py` and confirm PASS (or only expected chart warnings unchanged).

---

### Task 2: Types + EDN load + policy reader

**Files:**
- Create: `runtime/pastdue_kernel/edn_util.py`
- Create: `runtime/pastdue_kernel/types.py`
- Create: `runtime/pastdue_kernel/policy.py`
- Create: `runtime/pastdue_kernel/__init__.py`
- Create: `tests/kernel/test_policy.py`

- [x] **Step 1–4:** Policy loader + tests PASS.

---

### Task 3: Preflight Gate (P3 early — used by Kernel)

**Files:**
- Create: `runtime/pastdue_kernel/gate.py`
- Create: `tests/kernel/test_gate.py`
- Create: `tests/kernel/adversarial_eql.json`

- [ ] **Step 1:** Write tests: multi-collection reject; forbidden root `pastdue.vendor-master-mapping`; contribution scalar root reject; single-collection allowed entry pass; duplicate terminal work-key reject.

- [ ] **Step 2:** Implement `preflight(eql: str, *, capability, policy, session_terminals) -> GateResult`.

- [ ] **Step 3:** Load adversarial corpus (≥10 cases); assert reject rate ≥ 95% of expected-reject cases.

- [ ] **Step 4:** Run unittest → PASS.

---

### Task 4: EQL templates + Fact Plan Builder (P4)

**Files:**
- Create: `runtime/pastdue_kernel/eql.py`
- Create: `runtime/pastdue_kernel/disambiguate.py`
- Create: `runtime/pastdue_kernel/fact_plan.py`
- Create: `tests/kernel/test_fact_plan.py`

- [ ] **Step 1:** Test: ask signals with 占比 → plan capabilities include `eta-code-changes` not drivers-as-share; specialist → `master-mappings`; detail → `current-high-risk-lines`; unknown intent → `capability_gap`.

- [ ] **Step 2:** Implement disambiguate from policy `:disambiguation` + user text.

- [ ] **Step 3:** Implement `build_fact_plan` expanding intents to ordered items; fan-out placeholders when vendors provided in context.

- [ ] **Step 4:** Implement `render_eql(capability_id, inputs, policy)` single-collection only.

- [ ] **Step 5:** unittest → PASS.

---

### Task 5: Kernel Runtime v1 (P2)

**Files:**
- Create: `runtime/pastdue_kernel/fake_mcp.py`
- Create: `runtime/pastdue_kernel/kernel.py`
- Create: `tests/kernel/test_kernel.py`

- [ ] **Step 1:** Fake MCP: returns handles; can inject `BULKHEAD` then OK; tracks call count.

- [ ] **Step 2:** Test worklist: 3 vendors eta-code → ≤ concurrency 2 in flight; duplicate key not re-issued; analyze wrong handle → `HANDLE_MISMATCH`; bulkhead → one retry then degrade.

- [ ] **Step 3:** Implement `KernelSession.run_plan(plan, mcp)`.

- [ ] **Step 4:** unittest → PASS.

---

### Task 6: Trace + Golden Eval G1–G8 (P6)

**Files:**
- Create: `runtime/pastdue_kernel/trace.py`
- Create: `tests/kernel/golden/g1_eta_share.json` … `g8_bulkhead.json`
- Create: `scripts/run_golden_eval.py`
- Create: `scripts/run_kernel_tests.py`
- Modify: `ACCEPTANCE_TESTS.md`

- [ ] **Step 1:** Define golden JSON schema: `ask`, `intent_ir` or `signals`, `context`, `expect.capabilities_ordered_prefix`, `expect.forbid_entries`, `expect.forbid_eql_substrings`.

- [ ] **Step 2:** Implement runner: build plan → dry-run or fake MCP → assert shapes.

- [ ] **Step 3:** All G1–G8 green via `python scripts/run_golden_eval.py`.

- [ ] **Step 4:** Document in ACCEPTANCE_TESTS.md under Agent section.

---

### Task 7: Agent rewire — thin SP + Skill (P5)

**Files:**
- Modify: `agent/SYSTEM_PROMPT.md`
- Modify: `skill/pastdue-analysis/SKILL.md`
- Modify: `skill/pastdue-analysis/references/atlas-query-playbook.md` (top kernel pointer)
- Modify: `skill/pastdue-analysis/references/report-format.md` (composer uses typed results)

- [ ] **Step 1:** Rewrite SP to ≤~80 lines: identity, evidence boundaries, modes, **all factual Atlas via Kernel Runtime tools / Fact Plan**, no free-form roots.

- [ ] **Step 2:** Skill: document Intent IR → `build_fact_plan` / kernel procedure; daily = preset plan id `daily-monitor`; point to `runtime/pastdue_kernel`.

- [ ] **Step 3:** Playbook: open with “enforcement is runtime; this doc is dictionary”; keep capability templates as reference for operators / EQL template source of truth alignment.

- [ ] **Step 4:** Ensure no instruction tells the model to combine Owner+PO or guess mapping roots.

---

### Task 8: Public API + smoke CLI

**Files:**
- Modify: `runtime/pastdue_kernel/__init__.py`
- Create: `scripts/pastdue_kernel_smoke.py`

- [ ] **Step 1:** Export `load_policy`, `build_fact_plan`, `preflight`, `KernelSession`, `GateResult`.

- [ ] **Step 2:** Smoke script: load policy, build plan for sample Intent IR (CA Top-10 ETA share), print plan + gate-check rendered EQL.

- [ ] **Step 3:** Run smoke + full unittest + golden eval → all PASS.

---

## Self-review checklist

- [x] Spec P1–P6 mapped to tasks (P7/P8 deferred as follow-on).
- [x] No placeholder “add tests later”.
- [x] Gate before/with Kernel so Kernel cannot send illegal EQL.
- [x] Commits omitted per user rule unless requested.

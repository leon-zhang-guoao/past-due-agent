> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# Current-Detail Edges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Checkbox tracking.

**Goal:** Add three controlled current-state Atlas edges (ranks, alert-type counts, detail slice) and wire Agent policy/Kernel without changing daily snapshot presets.

**Architecture:** Query-entity pattern (like `vendor-master-mapping-query`) so optional filters are empty-string identity fields; SQL reads `past_due_detail_view` + `vendor_master_mapping_view` only for DNA. Kernel templates + policy capabilities + goldens.

**Tech Stack:** EDN in `atlas/charts/pastdue.edn`, `policy.edn`, Python Kernel, unittest.

**Spec:** `docs/superpowers/specs/2026-10-21-pastdue-current-detail-edges-design.md`

## Global Constraints

- No free SQL; no detail-view DNA columns as truth.
- Daily preset must not auto-call new edges.
- `validate_bundle.py` must PASS.
- Do not commit unless user asks.

---

### Task 1: Atlas Edge 1 — current vendor past-due ranks

**Files:** `atlas/charts/pastdue.edn`

- [ ] Add entities `pastdue.current-vendor-past-due-rank`, `pastdue.current-vendor-rank-query`
- [ ] Add collection `matching-ranks` + resolver + SQL binding + selection/ownership
- [ ] Run `python scripts/validate_bundle.py`

### Task 2: Atlas Edge 2 — alert-type counts

- [ ] Entities + resolver + SQL + wire into graph lists
- [ ] validate_bundle PASS

### Task 3: Atlas Edge 3 — current-detail-query matching-lines

- [ ] Entity + filters + line row entity + resolver (limit 50/200)
- [ ] validate_bundle PASS

### Task 4: Policy + Kernel + Skill

- [ ] policy 1.8.0 capabilities + intents + disambiguation
- [ ] Kernel eql templates + fact_plan awareness
- [ ] SP/Skill/playbook pointers for 实时/当前
- [ ] Golden G-current-* + run tests

### Task 5: Smoke docs

- [ ] atlas/README note on new current edges
- [ ] ACCEPTANCE_TESTS bullet

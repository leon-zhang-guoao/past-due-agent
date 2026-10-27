> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Agent layering redesign

**Date:** 2026-10-20  
**Status:** Approved (R1 + implement)  
**Scope:** System Prompt / Skill / report contracts / validate; Atlas mapping already present in `pastdue.edn`.

## Goal

Beautiful, complete, clear: one responsibility per layer; org mapping as a first-class multi-scenario capability; lean default daily path; hard evidence boundaries.

## Layering

| Layer | Owns | Does not own |
|---|---|---|
| System Prompt | Hard bans, mode choice, ETA single-response completion, evidence boundaries, short capability index | Long procedures, EQL samples, cell formats |
| Skill + references | Workflows, selection, org R1 rules, playbook, display | Physical SQL |
| `atlas/charts/pastdue.edn` | Sole deploy semantic contract | Split `*.attr.edn` as authority |
| ClickHouse | Facts | Agent direct access |

## Org mapping product rule (R1)

- Default daily/historical summary: **do not** auto-query Top-10 `master-mappings`.
- Query org when the user asks about department / Primary / Manager / concentration / org slice, or explicitly asks to enrich.
- When Owner is queried: BUYER from detail `buyer_email`; PRIMARY/MANAGER from `vendor_master_mapping_view` via Atlas.
- Never use detail `department_type` / `master_primary` / `master_manager` as mapping truth.

## Capability map

1. Attribution: Country → Top-10 → ETA brief (required on trigger).
2. Org: enrich / filter / rollup (on-demand, R1).
3. Owner / PO: on-demand; Owner PRIMARY/MANAGER from mapping.
4. Notification: on-demand / alert body; display only.

## Validation

Offline validator must treat `pastdue.edn` as authority (include mapping resolvers). Split `*.attr.edn` are non-authoritative unless regenerated from the combined file.

## Related

- Flexible Q&A (intent → Fact Plan → Kernel):
  `2026-10-20-pastdue-flexible-qa-agent-design.md`
- Universal Execution Kernel (all Atlas fan-out):
  `2026-10-20-pastdue-execution-kernel-design.md`
- Earlier ETA-only adaptive plane (superseded for scope):
  `2026-10-20-pastdue-adaptive-execution-plane-design.md`

## Self-review

- No placeholder TBD left for R1 vs R2 (R1 chosen).
- SP slim and Skill/org/report-format stay aligned.
- Scope excludes inventing new Atlas entities beyond those already in `pastdue.edn`.

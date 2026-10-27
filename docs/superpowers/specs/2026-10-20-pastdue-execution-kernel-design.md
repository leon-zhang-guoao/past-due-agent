> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Agent — Universal Execution Kernel

**Date:** 2026-10-20  
**Status:** Approved for implementation  
**Supersedes (narrow scope):** ETA-only Adaptive Execution Plane knobs in
`2026-10-20-pastdue-adaptive-execution-plane-design.md` — that doc’s contract
ideas remain; concurrency/worklist now apply to **all** Atlas data calls.  
**Depends on:** R1 layering design.

## 1. Problem

Per-scenario patches (ETA only, then org only) do not protect **new** asks.
Failures (bulkhead, unreachable wrong root, stale handle, multi-collection
cartesian, duplicate storms) are properties of **how** MCP is called, not of
one business table.

## 2. Goal

One **Execution Kernel** every Atlas factual query must pass through.
Capabilities (ETA, org, Owner, PO, notification, …) are **declarations**
plugged into the kernel. A new scenario adds a declaration (or proves a path
via discovery); it does not invent a new concurrency philosophy.

## 3. Kernel (universal, default-on)

### Hard contract

1. **Reachability first:** choose an entry that discovery shows is reachable
   (join/collection with required inputs). If search is empty and no declared
   capability matches, report unknown / contract gap — **do not** invent a
   root entity projection.
2. **Single-collection EQL:** at most one join/collection per `execute_eql`.
3. **Worklist dedupe:** key =
   `(capability-id, country?, vendor?, collection, identity-tuple…)`.
   States: `pending` → `in_flight` → `ok` | `failed_retryable` → (≤1 retry) →
   `ok` | `failed_final`. Never re-issue terminal states.
4. **Handle ownership:** analyze only the handle from that work item’s
   execute; reject stale/unrelated/wrong-schema handles
   (`HANDLE_MISMATCH` / `HANDLE_EXPIRED`).
5. **Shared concurrency budget** across **all** capabilities that opt in
   (`shares-global-budget true`, default for fan-out queries).
6. **Failure taxonomy:** `OK`, `BULKHEAD`, `HANDLE_MISMATCH`, `HANDLE_EXPIRED`,
   `UNREACHABLE`, `SOURCE_ERROR`, `HOST_PARTIAL`.  
   `collection-coverage-unknown` is **never** a failure.

### Soft scheduler

- Start concurrency = `concurrency-start` (2); max = `concurrency-max` (2).
- On bulkhead: backoff, one retry for that item, concurrency → degraded (1);
  restore after `bulkhead-recovery-successes` consecutive OK across the
  **shared** budget.
- Parent fan-out (e.g. Top-10): enqueue by rank; optional Tier A before Tier B;
  capability may set `obligation: full-parent-set` vs interactive narrow.

### Out of kernel budget

Light one-shot reads (e.g. single Country monitor `/all`) may run outside the
fan-out budget but still obey single-collection + handle ownership.

## 4. Capability declaration shape

```edn
{:id :master-mappings
 :entry :pastdue.vendor-contribution/master-mappings
 :parent-entity :pastdue.vendor-contribution
 :required-inputs [:country-code :vendor-code]
 :forbidden-roots [:pastdue.vendor-master-mapping] ; do not start here
 :shares-global-budget true
 :fan-out :per-vendor
 :post-filter optional} ; e.g. department-type = ETAD in Agent
```

New capability = new map entry + playbook template. Kernel rules unchanged.

## 5. Layer ownership

| Layer | Owns |
|---|---|
| SP | “All Atlas factual calls go through Execution Kernel”; no per-pipe concurrency essays |
| `policy.edn` `:execution-plane` | Kernel knobs + capability registry |
| Playbook | Kernel procedure, anti-examples, per-capability entry templates |
| Skill | Mode steps call capabilities by id |
| Atlas | Unchanged unless a new resolver is productized |

## 6. Migration

- Replace `:eta-execution` with `:execution-plane` (policy 1.5.0).
- ETA Code/Source become two capabilities under the registry.
- Org / Owner / PO / notification declared explicitly (fixes Specialist path).

## 7. Acceptance

1. SP forbids bypassing the kernel for factual Atlas queries.
2. Policy has `:execution-plane` with shared knobs + `:capabilities`.
3. Playbook documents kernel once; capabilities list allowed entries and
   forbidden roots.
4. Org Specialist path: `master-mappings` only; never mapping-entity root.
5. New undocumented ask: discovery → declare or fail closed (no guess root).

## 8. Self-review

- [x] Not a closed list of “fixed scenarios only” — extension = declaration.
- [x] Semantic mistakes (wrong field meaning) still Skill/Atlas, not kernel.
- [x] No Atlas batch requirement for this revision.

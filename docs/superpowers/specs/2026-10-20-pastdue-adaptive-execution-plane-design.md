> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Agent — Adaptive Execution Plane

**Date:** 2026-10-20  
**Status:** Superseded for scope — see
`2026-10-20-pastdue-execution-kernel-design.md` (universal kernel).  
Contract ideas below remain valid; ETA-only `:eta-execution` was generalized to
`:execution-plane` for all capabilities.  
**Depends on:** `2026-10-20-pastdue-agent-layering-design.md` (R1 layering)  
**Original scope:** ETA drilldown scheduling. **No Atlas EDN / ClickHouse
changes** required for the kernel generalization.

## 1. Problem

A pre-R1 daily trigger trace (US/CA, 2026-10-16→17) showed:

| Symptom | Actual cause |
|---|---|
| `result/collection-coverage-unknown` | Normal MCP default for resolver collections — not a data failure |
| `:bulkhead_rejected` (mostly US) | Unbounded parallel `execute_eql` + duplicate same Vendor queries |
| CA “inconsistent nested results” | Code + Source combined in one `:query` → cartesian flatten |
| Partial / unavailable Vendor ETA | Stale/wrong `result_handle`, expired handles, retry storms |

Existing text already said “prefer serial” and “separate Code/Source”, but
runtime treated that as optional soft advice and used a one-size-fits-all
parallel storm. A pure “always serial” fix would be too blunt: slow, inflexible,
and unable to adapt to load or interaction intent.

## 2. Goals

- **Correct:** never combine ETA dimensions; never analyze the wrong handle.
- **Elegant:** hard rules stay few; scheduling is a first-class soft plane.
- **Complete:** default daily still finishes Top-10 Code+Source in one response
  when Atlas allows; failures are classified, not vague.
- **Flexible:** concurrency and depth adapt to pressure, mode, and user intent
  — not a single global “always serial” knife.

## Non-goals

- Changing trigger math, Top-10 selection, or R1 org on-demand rules.
- Inventing Country-batch ETA entities that are not in the live graph.
- Runtime host code / MCP server changes (Agent is prompt+Skill governed).

## 3. Design: three planes

```
Contract plane   — never violate (correctness)
Scheduler plane  — adapt (throughput / resilience)
Narrative plane  — explain (report honesty)
```

### 3.1 Contract plane (hard)

1. **Single-collection EQL**  
   One `execute_eql` may request at most one of
   `eta-code-changes` / `eta-source-changes` (or any other single join).  
   Combining Code and Source in the same `:query` is a **contract violation**.
   If detected in a draft call, discard and re-issue as two separate calls;
   do not surface the cartesian table to the user.

2. **Handle ownership**  
   `analyze_result` may use only the `result_handle` returned by the matching
   `execute_eql` for that logical work item.  
   Reject (treat as failure, do not report as Country/Vendor facts) when:
   - handle equals a known earlier unrelated capture (e.g. Country monitor
     handle reused after a Vendor ETA call);
   - column set does not match the expected collection;
   - handle is unknown / expired / inaccessible.

3. **Worklist identity**  
   Logical key:
   `(country-code, vendor-code, collection, target-snapshot-date, previous-snapshot-date)`.  
   States: `pending` → `in_flight` → `ok` | `failed_retryable` → (one retry) →
   `ok` | `failed_final`.  
   Never re-issue `ok` or `failed_final` items in the same run.

4. **Retry budget**  
   At most **one** corrected retry per work item after inspecting the error
   (bulkhead backoff, field repair, handle mismatch → re-execute once).  
   No retry storms; no “retry by combining collections to save a call”.

5. **Failure taxonomy** (report / `analysis_status` may only use these for ETA)

| Code | Meaning | Counts as partial? |
|---|---|---|
| `OK` | Usable rows for brief list | No |
| `BULKHEAD` | `:source` / `bulkhead_rejected` after budget | Yes |
| `HANDLE_MISMATCH` | Wrong/stale handle vs expected collection | Yes |
| `HANDLE_EXPIRED` | Unknown/expired/inaccessible handle | Yes |
| `SOURCE_ERROR` | Other source/execution failure after budget | Yes |
| `HOST_PARTIAL` | Host hard-kill before worklist drained | Yes |
| *(not a failure)* `COVERAGE_UNKNOWN` | MCP `collection-coverage-unknown` alert | **No** |

`CONTRACT_VIOLATION` is internal only (self-correct before user-visible status).

### 3.2 Scheduler plane (soft, adaptive)

#### 3.2.1 Concurrency knob (`eta.concurrency`)

| Mode | Behavior |
|---|---|
| Start | `concurrency = 2` (global across Countries for ETA work items) |
| On `bulkhead_rejected` | That item: backoff then one retry; **global** concurrency → `1` |
| Recovery | After `eta.bulkhead_recovery_successes` consecutive OK ETA items at concurrency 1, restore to `2` |
| Cap | Never exceed `eta.concurrency_max` (default 2). Never invent unbounded parallel Vendor ETA |

Country metrics and Top-10 vendor lists remain independently queryable (light
weight). ETA phase shares one global concurrency budget.

**Not** “always serial”: serial (`1`) is the degraded state under pressure.

#### 3.2.2 Ordering (priority, not skipping)

Within each triggered Country, enqueue Vendors by contribution rank ascending
(1 first). For each Vendor: Code work item before Source work item.

Cross-Country: interleave is allowed if it respects the global concurrency
budget (e.g. CA rank1 Code and US rank1 Code when concurrency=2).

#### 3.2.3 Depth policy (`eta.depth_policy`)

| Policy value | When | Behavior |
|---|---|---|
| `full_top_n` | Default `DAILY_MONITOR` scheduled + interactive “完整日报” | All selected Top-N Vendors: Code then Source. Single-response rule unchanged. |
| `tiered_priority` | Same as full for **obligation**, but **schedule** Tier A (ranks 1…`eta.tier_a_ranks`, default 3) before Tier B (remaining). Under host pressure, unfinished items are named; Agent must not *voluntarily* abandon Tier B. | |
| `core_plus_ondemand_source` | **Only** when user explicitly asks for summary / Top drivers only | Tier A: Code+Source; Tier B: Code required, Source may remain on-demand placeholder with explicit user-scoped note — not for scheduled daily. |
| `historical_latest_full` | `HISTORICAL_REVIEW` default deep-dive | Latest event per Country: `full_top_n`. Older events (if requested beyond latest): Tier A only unless user asks full. |

Default for scheduler daily: `tiered_priority` scheduling + `full_top_n`
completion obligation (finish all when Atlas allows). This is **not** a license
to leave ranks 4–10 as `未查询` for capacity.

#### 3.2.4 Signals that change behavior

| Signal | Scheduler response |
|---|---|
| `bulkhead_rejected` | concurrency↓, one retry, continue other pending items |
| Handle mismatch/expired | one re-execute; do not analyze bad handle |
| Host timeout pressure | Drain Tier A first; then Tier B; never invent briefs |
| User narrows depth | Switch depth policy for this turn only |

### 3.3 Narrative plane

1. **`collection-coverage-unknown`**: at most one generic caveat in Limitations.
   Must not drive Country `TRIGGERED`/`NORMAL`, must not alone justify
   “部分完成 / Partial”.
2. **Partial report**: only when one or more work items end in
   `BULKHEAD` / `HANDLE_*` / `SOURCE_ERROR` / `HOST_PARTIAL`.
3. **Status cells**: use taxonomy codes (or short Chinese/English labels mapped
   from them). Prefer `—` + status over inventing zero movement.
4. **Driver note**: prefer complete Tier A Vendors; never claim a dimension
   that failed.
5. Preserve independent Country successes when the other Country fails.

## 4. Layer ownership

| Layer | Owns |
|---|---|
| `SYSTEM_PROMPT.md` | Short completion contract: worklist + taxonomy + adaptive scheduler pointer; no long concurrency essay |
| `policy.edn` | Knobs: concurrency, recovery, tier ranks, depth policy defaults, taxonomy list |
| `SKILL.md` | Daily/historical steps reference scheduler; forbid combine; single-response + worklist |
| `atlas-query-playbook.md` | Execution plane procedure, anti-examples, handle checks, bulkhead state machine |
| `report-format.md` | Narrative rules for coverage vs partial; status column semantics |
| Atlas / CH | Unchanged this revision |

## 5. Policy knobs (authoritative defaults)

```edn
:eta-execution
 {:concurrency-start 2
  :concurrency-max 2
  :concurrency-degraded 1
  :bulkhead-recovery-successes 3
  :depth-policy-default :tiered-priority-full-obligation
  :tier-a-ranks 3
  :worklist-dedupe true
  :max-retries-per-work-item 1
  :forbid-multi-collection-query true
  :failure-codes [:OK :BULKHEAD :HANDLE_MISMATCH :HANDLE_EXPIRED
                  :SOURCE_ERROR :HOST_PARTIAL]
  :coverage-unknown-is-failure false}
```

Interactive override of depth is request-local and must be stated in the report
when used.

## 6. Anti-examples (must appear in playbook)

1. Same `:query` vector containing both `eta-code-changes` and
   `eta-source-changes`.
2. Calling `analyze_result` on Country monitor handle after Vendor ETA
   `execute_eql`.
3. Re-issuing the same work-item key after `ok` or after final failure.
4. Treating `collection-coverage-unknown` as reason to mark Vendor ETA
   unavailable.
5. Setting all ETA concurrency to unbounded parallel “to finish faster”.
6. Voluntarily writing `未查询` for ranks 4–10 under `full` obligation.

## 7. Acceptance

1. Contract: no Skill path documents combined Code+Source EQL as allowed.
2. Policy: `:eta-execution` knobs present and referenced from Skill/playbook.
3. SP: completion section mentions worklist, taxonomy, adaptive concurrency —
   not “prefer serial only”.
4. Playbook: handle ownership + bulkhead state machine + anti-examples.
5. Report: coverage-unknown ≠ partial; partial tied to failure codes.
6. Behavior (manual / future eval): under simulated bulkhead, concurrency
   drops to 1 and worklist continues without duplicate storms.
7. R1 org rules unchanged: no auto Top-10 mapping on default daily.

## 8. Rollout

| Phase | Deliverable |
|---|---|
| P0 | Contract: forbid multi-collection, handle ownership, worklist, taxonomy |
| P1 | Scheduler: adaptive concurrency + tiered enqueue order |
| P2 | Narrative: coverage vs partial; status labels |
| P3 (optional later) | Interactive `core_plus_ondemand_source`; graph batch ETA if deployed |

This revision implements **P0 + P1 + P2** in Agent docs only.

## 9. Spec self-review

- [x] No TBD left for default depth (tiered schedule + full obligation).
- [x] No contradiction with R1 org on-demand.
- [x] No requirement for missing Atlas batch ETA.
- [x] Hard vs soft planes explicitly separated (not one-knife serial).
- [x] Failure taxonomy closed; coverage-unknown excluded from failures.

> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Agent — Production-Usable Architecture (Enforced Kernel)

**Date:** 2026-10-20  
**Status:** Approved for implementation (authoritative “most complete” path)  
**Supersedes (governance model):** prompt-only enforcement assumed by
`2026-10-20-pastdue-execution-kernel-design.md` and
`2026-10-20-pastdue-flexible-qa-agent-design.md`  
**Keeps:** those docs’ contracts (intent catalog, capability registry, Kernel
rules, R1 evidence boundaries, Atlas closed graph).  
**Changes:** enforcement moves from “model must obey docs” to
**machine-enforced Fact Plan + Kernel Runtime**, with eval gates for release.

## 1. Diagnosis (why the agent keeps failing)

Prior work correctly identified *what* to do (intent → Fact Plan → Kernel →
compose) and *how* to call Atlas (single-collection, worklist, handles,
declared entries). It was implemented as **SP + Skill + policy prose**.

That is insufficient for a production-usable agent:

| Layer today | Reality |
|---|---|
| Atlas EDN | Strict, correct — rejects bad roots (`UNREACHABLE`) |
| Kernel / intent rules | Written for humans (LLM); not executed as code |
| Model under Q&A pressure | Improvises EQL: wrong roots, multi-collection, parallel storms, stale handles, near-miss capabilities (`drivers` vs `changes`, nationwide `matching` vs per-vendor `master-mappings`) |
| Acceptance | Mostly offline chart lint + narrative cases — does not block release on bad *call shapes* |

**Root cause:** the control plane is advisory. The data plane (Atlas) is
strict. Advisory + pressure = recurring the same failure classes on *new*
questions, even after more documentation.

**Non-root-causes (do not “fix” these):**

- Atlas being strict (`unreachable` is a feature).
- Lack of “more scene scripts”.
- Need to loosen EDN so every entity is a free root.

## 2. Goal / non-goals

### Goal

A PastDue Q&A agent that is **complete and usable** under interactive and
scheduled load:

1. **Correct entry** for covered intents (no systematic `UNREACHABLE` / false
   “未找到” from wrong capability).
2. **Controlled calls** (no multi-collection cartesian, no duplicate storms,
   no analyze of foreign handles).
3. **Clear semantics** (占比 / specialist / detail / drivers map to one
   capability path each).
4. **Typed failures** users can trust (`BULKHEAD` vs gap vs denial).
5. **Release gate:** golden traces must pass before claiming “usable”.

### Non-goals

- Free-form ClickHouse / inventing graph edges.
- Historical causal attribution from current PO lines.
- Replacing Open Atlas as the factual store.
- Relying on longer prompts as the primary fix.

## 3. Target architecture (definitive)

```text
User ask / scheduler
        │
        ▼
┌──────────────────────┐
│  Intent Compiler       │  NL → Intent IR (ids + scope + ambiguity flags)
│  (LLM, constrained)    │  may ONLY emit catalog ids + scope fields
└──────────┬───────────┘
           │ Intent IR (JSON/EDN)
           ▼
┌──────────────────────┐
│  Fact Plan Builder     │  pure function: Intent IR + policy → Fact Plan IR
│  (deterministic code)  │  no free-form EQL here
└──────────┬───────────┘
           │ Fact Plan IR (ordered work items)
           ▼
┌──────────────────────┐
│  Kernel Runtime        │  worklist, concurrency, retries, handle table
│  (deterministic code)  │  builds EQL from capability templates ONLY
└──────────┬───────────┘
           │ gated MCP calls
           ▼
┌──────────────────────┐
│  Open Atlas MCP        │  list / execute_eql / analyze_result
│  (unchanged graph)     │
└──────────┬───────────┘
           │ typed WorkItemResults
           ▼
┌──────────────────────┐
│  Answer Composer       │  LLM + display contract; facts only from results
└──────────────────────┘

Side paths (also mandatory):
  • Preflight Gate — rejects illegal EQL if anything bypasses Kernel
  • Trace Recorder — every session → structured call log
  • Golden Eval — CI / pre-release against fixed asks + expected shapes
```

**Principle:** the LLM chooses *what the user wants* (intent) and *how to
word the answer*. It does **not** choose raw Atlas roots, join shapes, or
parallel fan-out policy. Those are code + `policy.edn`.

## 4. Component design

### 4.1 Intent Compiler (LLM, schema-locked)

**Input:** user message + mode hint (interactive | scheduled).  
**Output:** only an **Intent IR** object, validated against JSON Schema / EDN
spec derived from `:intent-catalog`.

Required fields:

```edn
{:intents [{:id :eta-decomposition :priority 1}
           {:id :vendor-drivers :priority 0}]
 :scope {:countries ["CA"] :vendors :top-n-from-drivers :top-n 10
         :dates :latest-trigger-pair}
 :ambiguities []  ; or [:detail] etc.
 :language :zh}
```

Rules:

- Unknown intent id → reject IR; force `capability-gap` answer path.
- Ambiguous “detail” → resolve via policy
  (`:default-for-ambiguous-detail` → `:po-line-detail`).
- Synonym matching may use LLM *or* deterministic keyword overlay; final IR
  must still validate.

**SP role:** instruct the model to emit Intent IR (or call
`pastdue.compile_intent`) — not to invent EQL.

### 4.2 Fact Plan Builder (deterministic)

Pure function:

```text
build_fact_plan(intent_ir, policy) -> fact_plan_ir
```

Responsibilities:

- Expand intents → ordered capability ids (from `:intent-catalog`).
- Apply **hard disambiguation table** (see §5) — overrides near-miss picks.
- Expand fan-out (`:per-vendor`) into work items once parents exist.
- Attach `post-filter` (e.g. ETAD for specialist).
- Insert discovery steps only when capability marks `:requires-discovery`.
- Never emit a work item whose `entry` is not in `:capabilities` or
  explicitly discovery-proven and registered mid-session.

Fact Plan IR example:

```edn
{:plan-id "…"
 :items
 [{:work-key [:positive-vendor-contributors "CA"]
   :capability :positive-vendor-contributors
   :inputs {:country-code "CA"}}
  {:work-key [:eta-code-changes "CA" "V1" "2026-10-18" "2026-10-17"]
   :capability :eta-code-changes
   :depends-on [[:positive-vendor-contributors "CA"]]
   :inputs {:country-code "CA" :vendor-code "V1"
            :target-snapshot-date "2026-10-18"
            :previous-snapshot-date "2026-10-17"}}]}
```

### 4.3 Kernel Runtime (deterministic) — the core upgrade

Implements the Universal Execution Kernel **in code**, not as Skill prose:

1. Worklist states: `pending → in_flight → ok | failed_retryable → … → failed_final`.
2. Shared concurrency budget from `:execution-plane :kernel`.
3. Build EQL **only** from capability templates (entry + required inputs).
4. Single-collection assertion before send.
5. Handle ownership table: `work-key → result_handle`; analyze rejects mismatches.
6. ≤1 corrected retry; terminal keys never re-issued.
7. Failure taxonomy unchanged (`BULKHEAD`, `UNREACHABLE`, …).
8. `collection-coverage-unknown` never fails a work item.

**Agent-facing tools (replace free `execute_eql` for PastDue facts):**

| Tool | Purpose |
|---|---|
| `pastdue.compile_intent` | Optional helper; or Agent emits IR and host validates |
| `pastdue.build_plan` | Intent IR → Fact Plan IR |
| `pastdue.kernel_run` | Execute plan (or next N ready items); returns typed results |
| `pastdue.kernel_status` | Worklist snapshot for partial answers |
| `pastdue.analyze` | SQL over owned handle only |

Raw Open Atlas `execute_eql` remains available **only** behind Preflight Gate
for discovery / emergency, and must still pass §4.4.

### 4.4 Preflight Gate (mandatory safety net)

Before any `execute_eql` leaves the host:

1. Parse projection: **at most one** join/collection key.
2. Root entity ∈ allowlist for this capability **or** discovery-session allowlist.
3. Reject forbidden roots from capability (`:forbidden-roots`).
4. Reject contribution-entity **scalar root loads** (metrics pattern).
5. Reject duplicate `work-key` if terminal in session worklist.
6. On reject: return structured `GATE_REJECTED` with reason — model must
   replan via catalog, not retry same shape.

Gate config lives next to policy (machine-readable), e.g.
`skill/.../kernel-gate.edn` or generated from `:execution-plane`.

### 4.5 Answer Composer (LLM)

- Consumes only `WorkItemResult` payloads + failure codes.
- Applies `table-display-contract` when modules match.
- Labels historical vs current (R1).
- Partial answers must name unfinished work-keys and failure codes.
- Forbidden: inventing numbers when work item failed.

### 4.6 Trace Recorder + Golden Eval

Every interactive/scheduled run writes a **trace**:

```edn
{:ask "…"
 :intent-ir {…}
 :fact-plan {…}
 :calls [{:work-key … :entry … :eql-shape … :result :ok|:UNREACHABLE|…}]
 :gate-rejects […]
 :answer-meta {:modules […] :partial? false}}
```

**Golden suite** (minimum for “usable”):

| ID | Ask (representative) | Must use | Must not |
|---|---|---|---|
| G1 | CA Top-10 + ETA 占比/构成 | contributors → `eta-code-changes` (+ source if asked) | `drivers` as 占比; multi-collection |
| G2 | ETA Specialist for Top vendors | per-vendor `master-mappings` + ETAD filter | mapping entity root; nationwide `matching` alone for “each vendor’s specialist” |
| G3 | Vendor detail / 每一行 | `current-high-risk-lines` | claim lines caused historical ratio |
| G4 | Single vendor past-due 多少 | contributors/`vendor-changes` + filter | contribution root scalars |
| G5 | Daily latest monitor | preset plan; Code then Source briefs | mid-list stop; Owner+PO combined EQL |
| G6 | Historical 过去一周触发 | history four-part identity | guessed attrs |
| G7 | Unknown fact family | capability-gap | invented root |
| G8 | Bulkhead simulation | backoff + ≤1 retry; concurrency degrade | duplicate storm |

Release rule: **all goldens green** (shape + taxonomy), not “looks good once”.

Offline `validate_bundle.py` remains for charts; golden eval is **behavioral**.

## 5. Hard disambiguation table (machine)

These overrides live in policy (not Skill essays). Fact Plan Builder applies
them after intent match:

| User signal | Force intent / capability | Forbid |
|---|---|---|
| 占比 / share / 构成 / composition of ETA | `:eta-decomposition` → `eta-*-changes` | treating `drivers` as share-of-past-due |
| ETA Specialist / ETAD 专员 | `:eta-specialist` → `master-mappings` + ETAD post-filter | `vendor-master-mapping` root; using only `matching-mappings` without per-vendor plan when Top-N vendors known |
| detail / 每一行 / PO 明细 | `:po-line-detail` | bundling Owner+PO in one EQL |
| vendor 多少 / snapshot counts | `:vendor-snapshot-metrics` | contribution entity root scalars |
| 谁贡献最大 / Top drivers (signed contribution) | `:vendor-drivers` | confusing with ETA 占比 |
| 按部门筛全国 | `:org-filter` → `matching-mappings` | using it as specialist-for-each-top-vendor |

Near-synonyms that historically caused false negatives must include
**pagination / completeness notes** on the capability (e.g. `matching-mappings`
limit 200 → never claim “no specialist” without per-vendor `master-mappings`
when vendor set is known).

## 6. Layer ownership (updated)

| Layer | Owns |
|---|---|
| **Kernel Runtime + Gate (new code)** | Enforcement of Kernel + EQL legality |
| **Fact Plan Builder (new code)** | Intent IR → work items; disambiguation table |
| `policy.edn` | Knobs, capabilities, intent catalog, disambiguation |
| SP | Thin: identity, evidence boundaries, “use Kernel tools”, no EQL tutorials |
| Skill / playbook | Human-readable dictionary, display, mode presets; **not** the enforcer |
| Atlas EDN | Product graph only; stay strict |
| `ACCEPTANCE_TESTS.md` + golden traces | Release usability gate |
| Composer (LLM) | Wording and module layout from typed results |

## 7. SP / Skill thinning (required alongside runtime)

Without thinning, long conflicting prose still steers the model to bypass
tools. After Kernel Runtime exists:

- **SP ≤ ~80 lines:** product identity, evidence boundaries, mode names,
  “all facts via `pastdue.kernel_*`”, completion contract pointer.
- **Skill:** intent list + when to call compile/build/run; daily preset =
  fixed plan id.
- **Playbook:** capability dictionary + anti-examples for *operators*, not
  for the model to re-implement Kernel.

## 8. Implementation phases (complete path — no “cheap subset”)

| Phase | Deliverable | Done when |
|---|---|---|
| **P0 Spec lock** | This document approved | Stakeholders agree gate+runtime is mandatory |
| **P1 Policy machine pack** | Disambiguation table + capability completeness notes in `policy.edn`; version bump | Builder can read all rules without Skill |
| **P2 Kernel Runtime v1** | Worklist, template EQL, concurrency, handle table, `kernel_run` | Unit tests pass on fake MCP |
| **P3 Preflight Gate** | Blocks multi-collection / forbidden roots / scalar metrics root | Gate unit tests + adversarial EQL corpus |
| **P4 Intent IR + Builder** | Schema + `build_fact_plan`; LLM compile path | IR validation rejects unknown intents |
| **P5 Agent rewire** | SP/Skill call Kernel tools; raw EQL only via gate | Manual smoke: G1–G5 |
| **P6 Trace + Golden Eval** | Recorder + CI job / script | G1–G8 green in CI |
| **P7 Composer hardening** | Partial/failure rendering; module selection | Goldens check answer labels |
| **P8 Ops** | Metrics: gate reject rate, UNREACHABLE rate, bulkhead rate | Dashboards / weekly review |

Atlas changes only when a golden proves a true **capability gap** (no reachable
path), not to silence gate rejects.

## 9. Success criteria (“完善可用”)

The agent is **production-usable** when all are true:

1. Goldens G1–G8 pass on the target runtime.
2. `UNREACHABLE` rate on covered intents ≈ 0 except injected adversarial cases.
3. Gate rejects illegal shapes before Atlas in ≥95% of adversarial corpus.
4. Interactive Top-N ETA + Specialist sessions complete without duplicate-key
   storms and without stale-handle analyze.
5. Unknown asks return capability-gap, never invented roots.
6. Daily preset still meets existing daily acceptance (Country + briefs).
7. SP no longer contains alternative EQL “tips” that contradict the Kernel.

## 10. Relation to prior specs

| Prior spec | Fate |
|---|---|
| Execution Kernel design | **Contracts kept**; enforcement moves to Runtime |
| Flexible Q&A design | **Intent/plan/compose kept**; Builder becomes code |
| Adaptive ETA plane | Superseded earlier; no revive |
| Layering R1 | Evidence / org on-demand unchanged |

## 11. Spec self-review

- [x] No “prompt-only” primary path.
- [x] No “loosen Atlas” path.
- [x] Deterministic vs LLM responsibilities split.
- [x] Disambiguation for known false-friend intents explicit.
- [x] Eval/release gate defined.
- [x] Phases cover full stack (not A+B subset).
- [x] No TBD placeholders for core control plane.

## 12. Next step after approval

Invoke **writing-plans** to produce
`docs/superpowers/plans/2026-10-20-pastdue-production-usable-agent.md`
with bite-sized tasks for P1–P6 (P7–P8 as follow-on if needed for one PR
series).

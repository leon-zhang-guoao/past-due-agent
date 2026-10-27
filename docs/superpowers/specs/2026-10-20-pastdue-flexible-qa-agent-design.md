> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Agent — Flexible Q&A Analysis Design

**Date:** 2026-10-20  
**Status:** Approved for implementation (approach C)  
**Depends on:**
- `2026-10-20-pastdue-agent-layering-design.md` (R1 layers)
- `2026-10-20-pastdue-execution-kernel-design.md` (how to call Atlas)

## 1. Problem

Users expect an **open Q&A analysis Agent**, not a machine that only runs
“daily Top-10” or a few scripted scenes. Recent failures (unreachable roots,
multi-collection `:source`, bulkhead) felt like “EDN is too rigid,” but:

| Misdiagnosis | Actual |
|---|---|
| EDN too strict → loosen schema | Graph must stay closed for auditability |
| Only N scenarios supported | Templates were used as **scripts**, not as an **intent→capability dictionary** |
| Flexible = any EQL shape | Flexible = compose **reachable** facts; inventing paths fails |

Without an intent layer, free-form questions push the model to guess roots and
bundle joins — exactly when errors spike.

## 2. Goals

- **Flexible:** arbitrary PastDue-related questions by composing capabilities.
- **Honest:** if the graph has no path, say unknown / needs modeling — never fake.
- **Safe:** all factual Atlas calls still go through the Execution Kernel.
- **Extensible:** new question families = new capability (+ optional intent
  synonym), not a new concurrency philosophy.
- **Modes remain:** scheduled `DAILY_MONITOR` is one **preset composition**,
  not the only product.

## Non-goals

- Direct ClickHouse / free-form SQL from the Agent.
- Historical PO-line causal attribution (not in graph today).
- Weakening Atlas contract validation (`unreachable` is a feature).
- Replacing R1 org on-demand rules or evidence boundaries (historical vs current).

## 3. Approaches considered

| Approach | Pros | Cons |
|---|---|---|
| A. Loosen EDN / add root resolvers for every entity | Fewer unreachable errors | Large Atlas surface; easy to leak bad roots; slow |
| B. Ever-longer scene scripts in Skill | Familiar | Explodes; new asks still break |
| **C. Intent router + fact dictionary + Kernel (recommended)** | Flexible Q&A; EDN stays strict; reuses Kernel | Needs intent catalog + playbook rewrite |

**Recommend C.**

## 4. Architecture

```text
User question (any language)
        │
        ▼
┌───────────────────┐
│  Intent Router    │  classify ask → 1..N intents + scope (country/vendor/dates)
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│  Fact Plan        │  map intents → ordered capability ids (+ post-filters)
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│  Execution Kernel │  worklist, single-collection, shared concurrency, handles
└─────────┬─────────┘
          │
          ▼
┌───────────────────┐
│  Answer Composer  │  modules/prose; label historical vs current; failures typed
└───────────────────┘
```

Scheduled daily = **preset Fact Plan** (country monitor → trigger → Top-10 ETA
briefs). Interactive Q&A builds a Fact Plan from the intent router.

## 5. Intent catalog (extensible)

Intents are **what the user wants to know**, not EQL shapes.

| Intent id | User language examples (zh/en) | Default capabilities | Evidence tier |
|---|---|---|---|
| `:country-health` | 国家怎么样 / latest US CA | `:country-monitor-all` | historical pair |
| `:who-triggered` | 谁触发了 / which countries triggered | monitor + classify | historical |
| `:vendor-drivers` | Top vendor / 谁贡献最大 | `:positive-vendor-contributors` | historical |
| `:eta-decomposition` | ETA code/source 构成 | `:eta-code-changes`, `:eta-source-changes` | historical |
| `:vendor-snapshot-metrics` | 这个 vendor past due 多少 | contributors/vendor-changes **filtered**, not contribution-root scalars | historical |
| `:org-mapping` / `:eta-specialist` | 负责人 / ETA specialist | `:master-mappings` (+ ETAD filter) | **current** |
| `:org-filter` | 按部门筛 vendor | `:matching-mappings` | current |
| `:org-concentration` | 集中度 | `:mapping-rollups` | current |
| `:owner-risk` | owner 风险 | `:owner-current-risks` | current |
| `:po-line-detail` | detail / 每一行 / PO 明细 | `:current-high-risk-lines` | current |
| `:notify-who` | 该通知谁 | `:notification-recipients` | current display |
| `:historical-range` | 过去一周触发 | history-query trigger-events | historical |

**Ambiguous “detail”:** prefer clarify once if unclear; default for a named
Vendor after attribution context → `:po-line-detail` **plus** keep prior
snapshot metrics if already known. Never claim PO rows caused the historical
delta.

Unknown intent after discovery: answer with **capability gap** (what is missing
in the graph), not a guessed root query.

## 6. Fact dictionary (playbook role change)

Playbook shifts from “run these steps” to **entry dictionary**:

For each capability (already in `policy.edn` `:execution-plane :capabilities`):

- Allowed entry / required inputs  
- Forbidden roots  
- Typical analyze SQL filters (e.g. `vendor_code = ?`)  
- Anti-examples  
- Evidence label (historical vs current)

**Vendor snapshot metrics rule (fixes 40979-class errors):**  
Scalars on `pastdue.vendor-contribution` are **not** root-loadable. Plan must
use `:positive-vendor-contributors` or `:vendor-changes` (country entity) then
filter, or reuse an in-session result handle’s rows. Never
`:entity` on contribution alone projecting `latest-*` / `vendor-name`.

**Single-collection:** Owner and PO are **two** kernel calls (remove combined
template anti-pattern).

## 7. Answer Composer

- Compose only from successful worklist items + clearly marked failures.  
- Mix historical + current only with explicit labels (existing R1 rule).  
- Module selection: use `table-display-contract` when a module fits; otherwise
  prose + small tables still following cell formats where possible.  
- Partial = typed failure codes only.

## 8. Layer ownership

| Layer | Owns |
|---|---|
| SP | Product identity: Q&A analysis Agent; modes are presets; all facts via Kernel; intent→plan→compose; no guess roots |
| Skill | Intent catalog summary; Fact Plan rules; daily = preset; flexible path |
| `policy.edn` | Optional `:intent-catalog` synonyms → capability ids; Kernel unchanged |
| Playbook | Fact dictionary + anti-examples (not only daily script) |
| report-format | Composer rules for mixed interactive answers |
| Atlas EDN | New **product** paths only when intent has no reachable entry |

## 9. Extending for a new question family

1. Can discovery already reach the facts? → add intent synonyms + Fact Plan
   mapping only.  
2. If not → Atlas capability (resolver) + policy capability entry + playbook
   row + intent.  
3. Never add a one-off “scene script” that bypasses Kernel.

## 10. Acceptance

1. SP states flexible Q&A as primary interactive posture; daily is a preset.  
2. Intent catalog documented; “detail” → PO lines default documented.  
3. Playbook documents contribution-scalar forbidden root.  
4. Owner+PO split into two EQL templates.  
5. Interactive Vendor detail Fact Plan = metrics (filter) → mappings → PO
   lines as **separate** kernel items.  
6. Unknown ask after discovery → capability gap, not invent entity.  
7. Kernel + R1 evidence rules unchanged.

## 11. Implementation phases (after approval)

| Phase | Work |
|---|---|
| P1 | SP + Skill intent/plan/composer framing |
| P2 | Playbook dictionary rewrite + fix Owner/PO + vendor metrics entry |
| P3 | Optional `policy.edn` `:intent-catalog` |
| P4 | ACCEPTANCE cases for flexible Vendor detail / specialist / gap |

No Atlas change required for P1–P3 if existing resolvers suffice.

## 12. Spec self-review

- [x] No “loosen EDN” path chosen.  
- [x] Kernel dependency explicit.  
- [x] Flexible ≠ unlimited graph.  
- [x] “detail” default disambiguated.  
- [x] Extension path for new families clear.

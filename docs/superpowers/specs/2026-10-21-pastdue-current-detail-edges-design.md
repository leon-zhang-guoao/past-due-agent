> ARCHIVED PRE-v3 DESIGN: historical record, not an installed runtime or current production contract. See ../../PASTDUE_AGENT_REDESIGN.md and ../../API_V3.md.

# PastDue Agent — Current-State Detail Edges (Controlled)

**Date:** 2026-10-21  
**Status:** Approved for implementation  
**Depends on:** R1 layering, Execution Kernel, Flexible Q&A, Production-usable Kernel Runtime  
**Approach:** Controlled filterable collections (option A) — not free SQL

## 1. Problem

Users ask for **real-time / current** country-level Vendor analysis (ranks,
alert mixes, line slices). Today Atlas only exposes:

- Snapshot ranks via `country-monitor` / `vendor-contribution` (daily table)
- Current PO lines only **per known Vendor** (`current-high-risk-lines`)

There is no country-rooted current aggregation edge, so the Agent correctly
reports a capability gap. Detail-view DNA columns must not be used (R1).

## 2. Goals

1. Add three **current-context** Atlas edges backed by
   `past_due.past_due_detail_view` + `past_due.vendor_master_mapping_view`.
2. Keep **DAILY_MONITOR** snapshot semantics and presets unchanged.
3. Support broad interactive asks via **declared optional filters + paging**,
   not arbitrary SQL.
4. Ignore `department_type` / `master_primary` / `master_manager` on
   `past_due_detail_view`; resolve org only from `vendor_master_mapping_view`.

## 3. Non-goals

- Free-form ClickHouse SQL / Metabase-style open query tool for the Agent.
- Using country-specific mapping tables `_us` / `_ca` when unified
  `vendor_master_mapping_view` exists.
- Treating current ranks as historical cause of snapshot ratio moves.
- Auto-running these edges on scheduled daily preset.

## 4. Edges

### 4.1 `current-vendor-past-due-ranks`

**Parent:** `pastdue.country-monitor`  
**Collection:** `pastdue.country-monitor/current-vendor-past-due-ranks`  
**Row entity:** `pastdue.current-vendor-past-due-rank`

**Identity / inputs (on monitor or attached query map — prefer required on
collection resolver inputs):**

| Input | Required | Notes |
|---|---|---|
| `country-code` | yes | Parameterized (not hard-coded US/CA) |
| `department-type-filter` | no | `""` = no constraint |
| `master-primary-filter` | no | `""` = no constraint |
| `master-manager-filter` | no | `""` = no constraint |

**Row attributes:**

- `country-code`, `vendor-code` (from `from_acct_no`), `vendor-name`
- `vendor-total-count`, `vendor-pastdue-count`
- `pastdue-rate-in-vendor`, `pastdue-rate-in-total`
- `rank` (1-based over `pastdue-rate-in-total` DESC)
- `queried-at-utc`

**SQL shape (normative):**

1. Same mapped-vendor join shape as alert-type edge. Scan **only**
   `toInt64(is_pastdue) = 1` lines (ranking by country past-due share ≡ order by
   `vendor_pastdue_count`). Denominator = `sum(vendor_pastdue_count) OVER ()`
   among mapped vendors. SQL rank column **`vendor_rank`** (never `AS rank`).
2. `vendor_total_count` / `pastdue_rate_in_vendor` are past-due-scoped
   (`total = pastdue_count`, in-vendor rate = 1) — not full open-line rates.
3. Restrict vendors via `vendor_master_mapping_view` when any org filter
   non-empty; if all filters empty, still optionally require vendor ∈ mapping
   view for that country **only if** product wants “mapped vendors only”
   (default: **yes**, match user SQL `IN (SELECT vendor_no …)`).
4. Order by `pastdue_rate_in_total` DESC; apply Atlas limit (default 200, max 1000).
5. Never select DNA columns from detail_view.

### 4.2 `current-vendor-alert-type-counts`

**Parent:** `pastdue.country-monitor`  
**Collection:** `pastdue.country-monitor/current-vendor-alert-type-counts`  
**Row entity:** `pastdue.current-vendor-alert-type-count`

| Input | Required | Notes |
|---|---|---|
| `country-code` | yes | |
| `vendor-code-filter` | no | `""` = all vendors in country |
| org three filters | no | Same empty-string rule; mapping view only |

**Row attributes:** `country-code`, `vendor-code`, `vendor-name`, `alert-type`,
`line-count`, `queried-at-utc`.

Group by vendor + `alert_type` from detail_view; org filter via mapping view.

### 4.3 `current-detail-query` / `matching-lines` (controlled free ask)

**Entity:** `pastdue.current-detail-query`  
**Identity:** country + all filter strings (empty = unconstrained), mirroring
`vendor-master-mapping-query` style.

| Input | Required |
|---|---|
| `country-code` | yes |
| `vendor-code-filter` | no (`""` or omit-as-empty) |
| `eta-code-filter` | no |
| `alert-type-filter` | no |
| `is-pastdue-filter` | no (`""` / `"1"` / `"0"`) |
| `risk-level-filter` | no |
| `department-type-filter` | no |
| `master-primary-filter` | no |
| `master-manager-filter` | no |

**Collection:** `pastdue.current-detail-query/matching-lines`  
**Row entity:** reuse or thin-extend line projection (prefer dedicated
`pastdue.current-detail-line` to avoid overloading high-risk-line semantics).

**Line attributes (minimum):** order-type, po-number, po-line-number, sku,
qtys, eta-code, eta-date, eta-source, is-pastdue, pastdue-days, alert-type,
risk-level, action-type, buyer-owner, primary-owner, manager-owner,
vendor-code, vendor-name, country-code, queried-at-utc.

**Paging:** default limit **50**, max **1000** (platform `:many` bounds; also requires `:sort`).  
**Org:** JOIN/filter `vendor_master_mapping_view` only for DNA fields and org
filters.

## 5. Evidence and mode rules

- All three edges: **current context only**.
- Answers must label “当前态 / current detail-view aggregate”, never claim
  completed-snapshot causality.
- `DAILY_MONITOR` preset Fact Plan: **unchanged**; does not enqueue these
  capabilities.
- Interactive intents (examples):
  - 实时/现在/当前 + 排名/Top → `:current-vendor-ranks`
  - alert 分布 / alert_type → `:current-alert-type-counts`
  - 当前行 / 明细切片 / 按 ETA·alert 筛 → `:current-detail-slice`

## 6. Agent / Kernel wiring

| Layer | Change |
|---|---|
| `pastdue.edn` | Entities, attributes, resolvers, bindings |
| `policy.edn` | New `:capabilities` + intent/disambiguation (version bump) |
| `runtime/pastdue_kernel` | EQL templates + gate allowlist |
| playbook | Dictionary entries + anti-examples (no detail DNA) |
| SP/Skill | Point “实时排名” at new capability; keep daily preset |
| Golden eval | G-current-rank, G-alert, G-detail-slice |
| Display | Prefer new modules or prose+disclose; optional later modules |

## 7. Acceptance

1. MCP discovery shows the three collections with required inputs.
2. US/CA current rank EQL succeeds without hard-coded country in SQL text
   (bind param only).
3. Org filters use mapping view; flipping detail DNA columns must not change
   Primary/Manager outputs.
4. Empty org filters behave like matching-mappings (no constraint / mapped-only
   rule as specified in §4.1).
5. Daily preset goldens still pass without calling new edges.
6. Ask “CA 实时 past due vendor 排名” plans `:current-vendor-past-due-ranks`,
   not snapshot contributors alone.
7. `validate_bundle.py` PASS; Atlas config-check PASS after deploy.

## 8. Implementation order

1. Edge 1 ranks + policy/kernel/golden  
2. Edge 2 alert-type counts + wiring  
3. Edge 3 detail-query + wiring  
4. Optional display modules  

## 9. Spec self-review

- [x] Option A locked (controlled filters).  
- [x] Daily isolation explicit.  
- [x] Detail DNA forbidden; mapping view required.  
- [x] No free SQL path.  
- [x] Limits specified.  
- [x] No TBD on core edges.

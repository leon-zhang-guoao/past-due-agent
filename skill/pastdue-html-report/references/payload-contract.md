# HTML report payload contract

Agents embed the canonical report bindings as JSON in

`<script type="application/json" id="pastdue-report">…</script>`.

The HTML skill **reformats for display only**. It does not re-query Atlas, re-run
analysis, or recompute metrics. Field meanings, section order, column order, and
number copy rules are defined in
`skill/pastdue-analysis/references/report-format.md` — follow that document;
do not fork formulas here.

Reference sample: `sample-payload.json` in this directory.

Implementation gate: `skill/pastdue-html-report/scripts/payload_gate.py`
(`gate_payload`, `REQUIRED_META_KEYS`, `COUNTRY_COLUMN_KEYS`, `VENDOR_RANKING_KEYS`).

---

## Top-level object

| Key | Required | Role |
|-----|----------|------|
| `meta` | yes | Run metadata (`REQUIRED_META_KEYS`) |
| `run` | yes | Conclusion, trigger flags, P/T pairs per Country |
| `countries` | yes | Non-empty array; one row per Country metrics table row |
| `per_country` | yes | Non-empty array; drilldown blocks (ranking + summary + coverage) |
| `cross_country_summary` | no | Text for module 4 when multiple Countries apply |
| `scope_gaps` | yes | Execution scope and gaps (may be an explicit empty structure) |

---

## `meta`

All keys in `REQUIRED_META_KEYS` (same tuple as `payload_gate.py`):

```text
mode
language
timezone
calendar-mode
generated-at
metric-version
```

`mode` must be `DAILY` or `TEST` for HTML auto-delivery (see Gate below).
`language` is the single resolved report language (`zh` or `en` per analysis delivery).

---

## `run`

| Field | Type | Notes |
|-------|------|-------|
| `conclusion` | string | Run conclusion as already delivered in Markdown |
| `trigger-flags` | string[] | e.g. `THRESHOLD_MET`, `TEST_FORCED` — preserve actual flags |
| `pairs` | object | Map `country-code` → `{ previous-snapshot-date, target-snapshot-date }` |

Date values in `pairs` use `YYYY-MM-DD`.

---

## `countries[]`

Each element is one Country metrics row. **Key order for binding** (exact list from
`COUNTRY_COLUMN_KEYS` in `payload_gate.py`):

```text
country-code
business-status
data-status
previous-snapshot-date
target-snapshot-date
snapshot-gap-days
previous-past-due-count
target-past-due-count
previous-open-count
target-open-count
previous-past-due-ratio
target-past-due-ratio
relative-ratio-change
percentage-point-change
```

Scalars in the payload are stored as returned by analysis (ratios as unit fractions,
e.g. `0.2` not `20%`). HTML display applies `report-format.md` shared formatting.

Column header labels and table order: `report-format.md` § Country metrics columns.

---

## `per_country[]`

One block per Country that received Vendor drilldown in the standard report.

| Field | Required | Notes |
|-------|----------|-------|
| `country-code` | yes | Matches a `countries[]` row |
| `selected-vendor-codes` | yes | Every code must appear on a `ranking[]` row |
| `ranking` | yes | Non-empty; one row per delivered selected Vendor |
| `summary` | yes | Country Vendor analysis summary text as delivered |
| `coverage` | yes | Coverage and limitations text as delivered |

### `ranking[]` row keys

**Exact order** (`VENDOR_RANKING_KEYS` in `payload_gate.py`):

```text
contribution-rank
vendor-code
vendor-name
previous-past-due-count
target-past-due-count
past-due-count-change
previous-open-count
target-open-count
previous-vendor-ratio
target-vendor-ratio
delta-country-contribution
positive-contribution-share
main-code-changes
main-source-changes
check-totals
analysis-status
```

Metric columns bind **VENDOR_METRICS only**. The three ETA/check columns are
**pre-rendered strings** copied from the Markdown delivery (same Vendor identity and
rank as the overview row).

Gate requires **every** key in `VENDOR_RANKING_KEYS` on every ranking row, including
`main-code-changes`, `main-source-changes`, `check-totals` and `analysis-status`.

Vendor ranking headers and order: `report-format.md` § Vendor ranking with main ETA changes.

### HTML skill — ETA / Vendor hard rules

From `report-format.md` (binding, not optional for HTML):

- **Never invent OTHER** constituents or ETA buckets. Only display what the returned
  compact rows / pre-built cell strings already contain.
- **Never read Vendor totals from ETA bucket rows** and never recompute Vendor past-due
  or open counts by summing buckets.
- **Never add Code and Source together** into a single synthetic total.
- Do not fabricate `analysis-status`; use actual scoped check/retrieval status strings.

The HTML layer must not expand `main-code-changes` / `main-source-changes` with buckets
that were not in the delivered analysis output.

---

## `cross_country_summary` (optional)

When multiple Countries are in scope and the Markdown report included module 4,
include a string (or structured text field) with the cross-Country summary as delivered.
Omit the key when the module does not apply.

---

## `scope_gaps`

Required top-level key. Structure must support rendering module 5 (execution scope and
gaps). An explicit “no gaps” payload is valid (e.g. `items: []` plus notes).

Example shape (illustrative; follow actual delivery):

```json
{
  "items": [],
  "notes": "无缺失项"
}
```

---

## Display formatting (authority: `report-format.md`)

Apply **`skill/pastdue-analysis/references/report-format.md` § Shared formatting** when
turning payload scalars and strings into HTML text. Summary for implementers:

| Kind | Rule |
|------|------|
| Dates | `YYYY-MM-DD`; pairs `previous→target` |
| Integers | No thousands separators |
| Signed nets | Positive `+`, negative `−`, zero `0` |
| Ratios / shares / relative changes | `100×` stored ratio, **two** decimal places `%` |
| Contribution levels / `delta-country-contribution` | `100×` ratio, **four** decimal places `pp` |
| Round for display only | Do not recompute underlying formulas in HTML |
| Near-zero display | `<0.01%` / `<0.0001 pp` with direction in words when applicable |
| Zero baseline | `report-format.md`: null relative with P=0,T>0 vs P=T=0 copy |
| Main ETA cells / check totals | Use delivered strings; cell rules in `report-format.md` § Main Code / Main Source / Check totals |

If payload numbers and formatted Markdown disagree, the Markdown delivery is wrong —
fix upstream bindings, not HTML math.

---

## Gate checklist (`gate_payload`)

Run `gate_payload(payload)` before emitting HTML. Behavior matches
`skill/pastdue-html-report/scripts/payload_gate.py` exactly:

1. **`meta.mode`** ∈ `DAILY`, `TEST` — otherwise reject (e.g. `INTERACTIVE`, `HISTORICAL` events-only).
2. **Every `REQUIRED_META_KEYS` entry** present on `meta`.
3. **Top-level `scope_gaps`** present.
4. **`countries`** present and non-empty; **every** row contains **all** `COUNTRY_COLUMN_KEYS`.
5. **`per_country`** present and non-empty.
6. For **each** `per_country` block:
   - `country-code` appears in `countries[]`.
   - `summary` and `coverage` present, non-null and non-empty strings.
   - `ranking` non-empty (else reject with `{country-code}: empty ranking`).
   - `selected-vendor-codes` is a **non-empty list** (DAILY/TEST standard reports always
     select Vendors); for each code a ranking row with matching `vendor-code` exists.
   - Every ranking row contains **all** `VENDOR_RANKING_KEYS`.

On success: `(True, "ok")`. On failure: `(False, reason)` — skip HTML and state the
reason briefly (same honesty as analysis delivery gate).

The renderer CLI (`render_ops_shell.py`, optional verification helper) catches
`ValueError` and `KeyError`, prints `gate failed: <reason>`, exits 1 and writes no file.

---

## Producer checklist

1. Markdown standard modules delivered and gated per `report-format.md`.
2. Build payload from the **same verified bindings** used for Markdown.
3. `gate_payload` passes.
4. Embed JSON in the HTML shell (`html-shell.md`, later tasks) without altering facts.

---
name: pastdue-html-report
description: On user HTML_ONLY request or after user confirms HTML following a DAILY/TEST Markdown ask, build a verified JSON payload and emit Ops/Demo HTML (one file or per-Country split under CAPACITY) without re-querying Atlas.
---

# PastDue HTML Report — companion

HTML is a **presentation companion** only. When Markdown was delivered,
`pastdue-analysis` stays authoritative. This skill does not re-query Atlas and
does not scrape delivered Markdown as the source of truth.

## 1. When to load

Load and follow this skill **only** when one of:

1. **HTML_ONLY** — the user clearly wants HTML / a web report and **no Markdown
   body** this turn. Analysis must already have verified bindings sufficient to
   assemble a gated payload (any analysis mode that can do so).
2. **Confirm follow-up** — after a prior standard **DAILY / TEST** Markdown turn
   that asked whether to generate HTML, the user confirmed yes. Build from
   **prior-turn verified bindings** (no Atlas re-query by default).

Do **not** load solely because Markdown succeeded. Default DAILY/TEST turns end
with an ask owned by `pastdue-analysis`; that turn must **not** write HTML and
must **not** emit `HTML: WRITTEN` from this skill.

Do not auto-offer or auto-write for HISTORICAL / INTERACTIVE unless the user
initiates HTML (then treat as HTML_ONLY or confirm-export with binding reuse).

## 2. Attempt rules (when §1 applies)

When loaded under §1, assemble the payload from verified bindings, pass the gate,
then write HTML. Vague refusal is not allowed.

**Forbidden:**

- `HTML: WRITTEN` or `HTML: SPLIT` without listing **real path(s)** written this turn
- Claiming WRITTEN before the host write succeeds
- 「未构建可验证 payload」/ “did not build a verifiable payload” as the only reason
- 「incomplete payload」without naming the gate field/country/vendor
- 「skipped HTML」with no `reason=` / `detail=`

## 3. Build the payload

Construct a JSON object from **in-memory verified bindings** — same facts, same
ordinals, same failure disclosures as the analysis delivery (or prior MD turn).

Read [payload contract](references/payload-contract.md) for shape, required keys,
column order, and formatting rules. Layout and number-copy authority remain in
`skill/pastdue-analysis/references/report-format.md`; do not recompute metrics
or fork formulas here.

Set `meta.mode` to `DAILY` or `TEST` only (gate requirement). Embed nothing that
was not verified during analysis.

**Prefer one file** with full `countries` / `per_country` / complete `ranking[]`.
Do not leave `ranking: []` and invent Vendor tables only in page JS.

## 4. Gate

Before writing any HTML, apply the gate checklist in
[payload-contract.md](references/payload-contract.md) (hand-check is enough;
running `scripts/payload_gate.py` / `render_ops_shell.py` is optional).

Hard rejects (do **not** write that payload as HTML):

- `meta.mode` not `DAILY`/`TEST`
- empty `per_country[].ranking`
- any `selected-vendor-codes` entry missing a ranking row
- missing/empty `summary` or `coverage`, or missing `scope_gaps`

Ranking rows **with** explicit local-failure status are allowed. Do not park
Vendor facts only in page JS while leaving `ranking: []`.

## 5. Outcomes: WRITTEN | SPLIT | SKIPPED

Disclose with a **fixed observation line** (plus at most one short sentence):

```text
HTML: WRITTEN|SPLIT|SKIPPED — reason=<CODE> — detail=<specifics>
```

### 5.1 `WRITTEN`

Host write succeeded for one self-contained file; gate passed.
`reason=OK`. **Must list the real path.** No path ⇒ must not use `WRITTEN`.

### 5.2 `SPLIT` — `reason=CAPACITY`

Use **only** when a single file cannot hold the full ranking/countries payload
(context or output budget).

Rules:

- Split by **Country**: one HTML per gated country slice.
- Each file self-contained and gated on its own slice.
- Naming: `pastdue-{DAILY|TEST}-{COUNTRY}-{target-date}.html` (see html-shell).
- Chat must list **every** written path.
- Do **not** use empty `ranking` + JS-only Vendor tables.

### 5.3 `SKIPPED` — only these `reason` codes

| `reason` | When | `detail` must name |
|----------|------|--------------------|
| `GATE` | Payload fails gate checklist | Exact gate reason |
| `HOST` | Write/path failed after a gated payload | Host/tool error text |
| `MODE` | Skill loaded inappropriately for this mode/intent | Mode / intent |
| `BINDING` | User confirmed HTML but prior verified bindings are unavailable | What is missing; **no default Atlas re-query** |

On `GATE` / `HOST` / `BINDING`:

- **Skip HTML entirely** for that attempt — no partial or “complete-looking” file.
- Do not modify Markdown already delivered in a prior turn.
- Never use `GATE` to mean laziness when ranking rows exist in bindings — assemble
  `ranking[]` or `CAPACITY` → `SPLIT`.

## 6. Emit HTML

Read [html-shell](references/html-shell.md) for document structure, section IDs,
mode toggle, naming, and reduced-motion rules.

**Preferred agent path (no Atlas, no runtime Python):** emit equivalent HTML directly,
matching `html-shell.md` and the payload contract. Apply the gate checklist by hand
before writing.

**Optional local verification helper:** `scripts/render_ops_shell.py` is fixtures/CI
only — not a required runtime service or Markdown renderer:

```bash
python skill/pastdue-html-report/scripts/render_ops_shell.py <payload.json> -o <out.html>
```

Enhance **Demo** motion after the Ops baseline exists. Ops remains readable without
Demo JS.

## 7. Write and disclose

Write under the host-allowed working directory. Suggested name:
`pastdue-{DAILY|TEST}-{countries}-{target-date}.html` (see html-shell).

**CAPACITY split:** one file per country as in §5.2.

**Deliverable path vs skill-package hygiene**

- Session deliverables: host working directory (e.g. `/sandbox/…`).
- `scripts/out/*.html` gitignore is only for local/CI samples — not a reason to
  withhold session HTML.

**Retention after write (narrow)**

- After a deliverable HTML is written and disclosed, do not `rm`/`delete` it
  without a same-turn overwrite.
- Gate failure: simply do not write.

On `WRITTEN` / `SPLIT`, list path(s). On `SKIPPED`, observation line only — no fake path.

## 8. Animation skills (optional, same workspace)

Prefer skill names `diagram-animation`, `svg-animation`, `isometric-animation`
when installed. Ops/Demo HTML works without them. Demo must not omit ranking,
ETA/check, coverage, or `scope_gaps`. Respect `prefers-reduced-motion: reduce`.

## 9. Hard boundaries

- **Never** invent Markdown that was not requested in HTML_ONLY turns.
- **Never** award or restate an overall PASS.
- **Never** re-query Atlas inside this skill (default); BINDING if bindings are gone.
- **Never** write HTML with empty `ranking` or Vendor tables only in page JS.
- **Never** delete a disclosed session HTML without overwrite in the same turn.
- **Never** report `WRITTEN`/`SPLIT` without real path(s) written this turn.
- HTML reformats verified bindings for display; it is not a second analysis pass.

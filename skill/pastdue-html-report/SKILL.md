---
name: pastdue-html-report
description: After a successful PastDue DAILY or TEST standard Markdown report, build a verified JSON payload and emit one Ops/Demo HTML file without re-querying Atlas.
---

# PastDue HTML Report — companion

HTML is a **presentation companion** only. Markdown from `pastdue-analysis` stays
authoritative. This skill does not render or reinterpret analysis modules, does not
re-query Atlas, and does not scrape delivered Markdown as the source of truth.

## 1. When to load

Load and follow this skill **only after** `pastdue-analysis` has completed a
**successful standard DAILY or TEST** Markdown report in the same turn:

- All applicable `report-format.md` modules delivered in the resolved language.
- Every selected Vendor has a ranking row with main Code/Source/check cells or an
  explicit local failure — not metric-only ranking.
- Execution scope and gaps stated; no self-awarded overall PASS.

Do **not** auto-emit HTML for HISTORICAL events-only lists, INTERACTIVE partial
answers, incomplete gates, or failed standard reports.

## 2. Build the payload

Construct a JSON object from **in-memory verified bindings** already used for
Markdown — same facts, same ordinals, same failure disclosures.

Read [payload contract](references/payload-contract.md) for shape, required keys,
column order, and formatting rules. Layout and number-copy authority remain in
`skill/pastdue-analysis/references/report-format.md`; do not recompute metrics
or fork formulas here.

Set `meta.mode` to `DAILY` or `TEST` only. Embed nothing that was not verified
during analysis.

## 3. Gate

Run `gate_payload` from `scripts/payload_gate.py` (or let `render_ops_shell` call
it). On failure:

- **Skip HTML entirely** — do not write a partial or “complete-looking” file.
- Disclose the gate reason in **one or two sentences** in chat only.
- Do not modify Markdown already delivered.

## 4. Emit HTML

Read [html-shell](references/html-shell.md) for document structure, section IDs,
mode toggle, naming, and reduced-motion rules.

**Preferred agent path (no Atlas, no runtime Python):** emit equivalent HTML directly,
matching `html-shell.md` and the payload contract (same section IDs, `data-mode`,
embedded `#pastdue-report` JSON, column order, headers). Apply the gate checklist in
`payload-contract.md` by hand before writing the file.

**Optional local verification helper:** `scripts/render_ops_shell.py` is a
fixture/CI aid for checking that the shell and gate behave as documented. It is not a
required runtime service, not a Markdown report renderer, and not something the
agent must invoke to deliver HTML:

```bash
python skill/pastdue-html-report/scripts/render_ops_shell.py <payload.json> -o <out.html>
```

`render_ops_shell(payload) -> str` is importable; it gates first and raises
`ValueError(reason)` on failure (CLI: `gate failed: …`, exit 1, no file).

The `pastdue-analysis` ban on runtime Python/service still applies to the analysis
itself. The helper is out-of-band verification tooling only.

Enhance **Demo** motion in-page after the Ops baseline exists (`data-mode=demo`,
`?mode=demo`, master timeline per html-shell). Ops remains fully readable without
Demo JS.

## 5. Write and disclose

Write exactly **one** self-contained `.html` file under the workspace. Suggested
name: `pastdue-{DAILY|TEST}-{countries}-{target-date}.html` (see html-shell).

Tell the user the **full path** in chat. On success, a brief note that HTML is a
companion to the Markdown report is enough. On skip (gate failure), reason only —
no file path.

## 6. Animation skills (local paths)

Animation skills live under `skill/` with **versioned directory names**. Before
reading, resolve the local match from this skill directory:

| Pattern | Use |
|---------|-----|
| `../diagram-animation-*/SKILL.md` | Demo section beats; light Ops count-up / bar emphasis |
| `../svg-animation-*/SKILL.md` | Status marks, light stroke accents |
| `../isometric-animation-*/SKILL.md` | Demo decoration **only** when explicitly wanted — **Ops default off** |

Glob or list `skill/diagram-animation-*`, `skill/svg-animation-*`,
`skill/isometric-animation-*` and open the single matching `SKILL.md` in each
family. Invoke motion patterns only inside the HTML file; never change payload
facts. Demo must not omit ranking rows, ETA/check cells, coverage, or
`scope_gaps` for polish.

Respect `prefers-reduced-motion: reduce` — final composed state, no looping.

## 7. Hard boundaries

- **Never** modify Markdown already delivered in the analysis turn.
- **Never** award or restate an overall PASS for the run.
- **Never** re-query Atlas, re-execute EQL, or bind new facts inside this skill.
- HTML reformats verified bindings for display; it is not a second analysis pass.

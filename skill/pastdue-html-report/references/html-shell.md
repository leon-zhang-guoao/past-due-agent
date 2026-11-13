# HTML shell conventions (Ops baseline)

The Ops shell is a single self-contained HTML5 file produced deterministically from a
gated payload (`payload-contract.md`). It reformats for display only; metrics and
layout authority stay in `skill/pastdue-analysis/references/report-format.md`.

## Producing the file

Agents may either:

1. Run the renderer:

   ```bash
   python skill/pastdue-html-report/scripts/render_ops_shell.py <payload.json> -o <out.html>
   ```

   `render_ops_shell(payload) -> str` is also importable. It calls `gate_payload` first
   and raises `ValueError(reason)` on failure; the CLI prints `gate failed: <reason>`,
   exits non-zero and writes no file. On gate failure agents skip HTML and state the
   reason briefly.

2. Hand-author equivalent HTML that matches this shell (same section IDs, `data-mode`
   handling, embedded JSON script, column order and headers).

## Document structure

- `<html lang="zh|en">` from `meta.language` (single language, no bilingual duplication).
- Sticky `header.top`: report title (`[测试]`/`[TEST]` prefix when `meta.mode=TEST`),
  run-mode badge (`TEST`/`DAILY`), view-mode badge (`OPS`/`DEMO`), `P/T` pairs from
  `run.pairs`, and Ops/Demo toggle buttons (`data-set-mode`).
- `<body data-mode="ops">` is the default.
- `<script type="application/json" id="pastdue-report">` embeds the full payload
  (`json.dumps(..., ensure_ascii=False)`; `</` escaped as `<\/` so JSON stays valid).

## Section IDs (in order)

| ID | Content |
|----|---------|
| `run` | Run information and conclusion, trigger flags, P/T |
| `country-metrics` | Country metrics table (all `countries[]` rows) |
| `country-{CODE}` | One per `per_country[]`: ranking table, summary, coverage (e.g. `country-US`) |
| `cross-country` | Only when `cross_country_summary` is present |
| `scope-gaps` | Execution scope and gaps (`scope_gaps`) |

## Tables

Header text and column order follow `report-format.md` (Chinese for `zh`, English for
`en`). 11 Country metric columns and 12 Vendor ranking columns; paired payload keys are
merged into `previous→target` cells. Ranking ETA/check cells are delivered strings,
escaped for HTML only. Ranking tables scroll horizontally; `thead` is sticky.

Formatting helpers: integers without separators; signed nets `+`/`−`/`0`; ratios
`xx.xx%`; contribution `x.xxxx pp`; nonzero values that round to zero render as
`<0.01%` / `<0.0001 pp` with direction in words; null relative change with P=0 renders
零基线上升 / Zero-baseline increase (P=T=0: 无变化 / No change). Missing values render
未提供 / Not provided, never as zero.

## Query parameters and modes

- `?mode=ops|demo` sets `data-mode` on load (default `ops`); toggle buttons update it
  and the URL.
- `?t=` is **reserved** for the Demo timeline (Task 5). The Ops shell does not read it.
- Demo motion is not implemented in the Ops shell; only the mode switch exists.

## Reduced motion

A `@media (prefers-reduced-motion: reduce)` rule disables animations and transitions.
Task 5 Demo motion must respect it (static final state, no autoplay) and extend rather
than remove this rule.

## File naming

`pastdue-{mode}-{countries}-{target}.html`, e.g.
`pastdue-TEST-US-2026-10-23.html`, `pastdue-DAILY-US-CA-2026-10-23.html`.
Generated samples may live under `skill/pastdue-html-report/scripts/out/`.

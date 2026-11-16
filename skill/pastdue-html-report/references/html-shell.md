# HTML shell conventions (Ops baseline)

The Ops shell is a single self-contained HTML5 file produced deterministically from a
gated payload (`payload-contract.md`). It reformats for display only; metrics and
layout authority stay in `skill/pastdue-analysis/references/report-format.md`.

## Producing the file

Preferred agent path: hand-author equivalent HTML matching this shell (no Atlas, no
runtime Python). `scripts/render_ops_shell.py` is an **optional local verification
helper** for fixtures/CI, not a required runtime service or Markdown renderer.

Agents may either:

1. (Optional, verification only) Run the renderer:

   ```bash
   python skill/pastdue-html-report/scripts/render_ops_shell.py <payload.json> -o <out.html>
   ```

   `render_ops_shell(payload) -> str` is also importable. It calls `gate_payload` first
   and raises `ValueError(reason)` on failure; the CLI prints `gate failed: <reason>`,
   exits non-zero and writes no file. On gate failure agents skip HTML and state the
   reason briefly.

2. (Preferred) Hand-author equivalent HTML that matches this shell (same section IDs, `data-mode`
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
  and the URL (and drop `t`).
- `?t=N` (seconds) is the seek/pause harness: pauses the Demo timeline and seeks to `N`.
  It implies `mode=demo` unless `?mode=` is given. `?mode=demo&t=0` shows the first frame;
  `?mode=demo&t=99` (>= duration) shows the final state. `body[data-t]` mirrors the time.
- Harness API: `window.__pastdueTimeline` = `{ duration(), beats(), time(), paused(), play(), pause(), seek(t) }`.
  The shell uses its own `requestAnimationFrame` timeline (no external library, so the
  file stays self-contained). If a hand-authored shell uses GSAP instead, the equivalent
  is `tl.pause(); tl.seek(N)`.

## Ops vs Demo motion

- **Ops (default)**: every section is fully visible immediately. When motion is allowed
  (no `prefers-reduced-motion`, no `?t=`), ratio cells in `#country-metrics` (`xx.xx%→xx.xx%`)
  do a short (~0.6 s) count-up and always end on the exact delivered text.
- **Demo (`data-mode="demo"`)**: one master timeline reveals `[data-beat]` elements in DOM
  order: `#run` → `#country-metrics` → each `#country-{CODE}` (ranking table) → its
  `summary` → its `coverage` → `#cross-country` (if present) → `#scope-gaps`. Each beat
  fades/slides in (opacity + small `translateY`) and ends with its inline style cleared.
  A play/pause button (`#demo-play`, visible only in Demo) pauses/resumes; after the end
  it becomes "Replay". Autoplays once on entering Demo; never loops.
- **Demo never omits data.** Only reveal order and opacity change; no `display:none`,
  no text/number rewriting, and the embedded JSON is untouched. Print always shows
  everything.
- Beat markup: `data-beat` on each `<section>`; per-country `summary` / `coverage` blocks are
  `<div data-beat="summary|coverage">` wrappers around the same `<h3>` + text as Ops.

## Reduced motion

`prefers-reduced-motion: reduce` disables CSS animation/transition, forces `[data-beat]`
to full opacity, skips the Ops count-up, and makes Demo jump straight to the final state
(no autoplay, no looping; Play just re-asserts the final state). The JS also listens for
the media query changing at runtime.

## Optional isometric hook (not implemented by default)

The shell contains no isometric/3D visuals. A deployment may opt in by adding its own
decorative element (e.g. an inline SVG) inside `#run` marked `data-decor="isometric"` and
driving it from `window.__pastdueTimeline.time()`. It must be `aria-hidden`, must not carry
report data, and must be disabled under `prefers-reduced-motion`.
## File naming

**Single-file (default):** `pastdue-{mode}-{countries}-{target}.html`, e.g.
`pastdue-TEST-US-2026-10-23.html`, `pastdue-DAILY-US-CA-2026-10-23.html`.

**CAPACITY split (one Country per file):** `pastdue-{mode}-{COUNTRY}-{target}.html`,
e.g. `pastdue-TEST-US-2026-10-23.html` and `pastdue-TEST-CA-2026-10-23.html`.
Each file embeds only that country’s gated payload slice and remains a full Ops/Demo
shell (same section IDs; omit empty `country-*` sections for other countries). Chat
must list every path. Do not use split naming to hide empty `ranking`.

Local/CI samples may use this skill’s `scripts/out/` (`*.html` gitignored for
skill upload only). Session deliverables use the host working directory
(e.g. `/sandbox/pastdue-….html`) and must not be deleted after disclose unless
replaced by an overwrite in the same turn.

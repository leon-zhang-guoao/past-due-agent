# PastDue — HTML Report Companion (Ops + Demo)

**Date:** 2026-11-13  
**Status:** Approved — implementation plan next  
**Depends on:** `skill/pastdue-analysis` (3.3.16 layout authority `report-format.md`); animation skills `diagram-animation`, `svg-animation`, `isometric-animation`  
**Approach:** Companion skill (option 1) — keep analysis authoritative; auto-emit single-file HTML after successful DAILY/TEST

## 1. Problem

PastDue standard reports are delivered as structured Markdown modules. There is
no report renderer. Stakeholders also want:

- an **Ops** HTML artifact (complete tables, archive/share in browser);
- a **Demo** viewing mode (progressive reveal / light motion for training and
  presentations);

without inventing a second metric definition or dropping ETA/gap disclosure.

Three local animation skills can produce standalone HTML motion, but they are
not wired to PastDue facts or to the DAILY/TEST delivery path.

## 2. Goals

1. After a **successful standard DAILY or TEST** Markdown report in the same
   turn, **automatically** generate one self-contained `.html` file.
2. One file, two modes: default **Ops**; **Demo** via `?mode=demo` or an
   in-page toggle — same data, different reveal/motion only.
3. Preserve `report-format.md` as layout and formatting authority: same section
   order, Country columns, Vendor ranking columns, main Code/Source/check cells,
   coverage, and execution scope/gaps.
4. Consume **already bound, verified facts** from the analysis turn (structured
   payload). Do not re-query under a different metric contract; do not scrape
   Markdown as the source of truth.
5. Use animation skills only inside the HTML companion: diagram for charts and
   staged reveals; svg for light marks; isometric optional and Demo-only.

## 3. Non-goals

- Replacing Markdown as the authoritative analysis delivery.
- Email send, object-store upload, server-side scheduled renderer, or build
  pipelines.
- Auto-HTML for HISTORICAL events-only lists or INTERACTIVE partial answers.
- Fabricating a “complete-looking” HTML when the analysis gate fails.
- Full six-column per-Vendor ETA tables in the standard HTML (remain
  on-request / interactive, same as Markdown).
- Changing Atlas EQL, completion contracts, or diagnostic rules.

## 4. Architecture

```text
pastdue-analysis (unchanged analysis contract)
  → bind + verify facts
  → deliver Markdown per report-format.md
  → gate: standard DAILY/TEST complete
  → pastdue-html-report (new companion)
       build report payload from in-memory verified facts
       emit single HTML (Ops default + Demo mode)
  → on HTML failure: Markdown already delivered; one-line disclosure only
```

### 4.1 Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `pastdue-analysis` | Query, verify, Markdown modules; **minimal hook** after success | Atlas / existing references |
| `pastdue-html-report` | Gate, payload shape, HTML shell, Ops/Demo behavior | Analysis turn facts; `report-format.md` rules; animation skills as needed |
| `diagram-animation` | Progressive disclosure, bar/line/count-up patterns | Invoked only from HTML skill |
| `svg-animation` | Icons, status marks, light stroke accents | Invoked only from HTML skill |
| `isometric-animation` | Optional Demo decoration layer (default off in Ops) | Invoked only from HTML skill |

### 4.2 Trigger matrix

| Mode | Auto HTML? |
|---|---|
| DAILY with complete standard report | Yes |
| TEST with complete standard report | Yes |
| HISTORICAL events-only | No |
| INTERACTIVE | No |
| Gate failure / material incompleteness | No (disclose why) |

## 5. HTML page structure

**Deliverable:** one `.html` file, inline CSS/JS, optional CDN for motion libs,
no build step. Suggested name:
`pastdue-{DAILY|TEST}-{countries}-{target-date}.html`.

| # | Block | report-format | Ops | Demo |
|---|---|---|---|---|
| 0 | Chrome | — | Mode, language, TZ, P/T, Ops/Demo toggle | Same + play/pause |
| 1 | Run + conclusion | 运行信息与结论 | Conclusion card + trigger / TEST_FORCED tags | Conclusion beat first |
| 2 | Country metrics | Country metrics table | Full column HTML table + light bar/count-up | Chart beat then full table |
| 3a | Vendor ranking | Ranking + main ETA cells | Full-width table (horizontal scroll OK) | Top contributors then full table |
| 3b | Vendor summary | Country Vendor analysis summary | Text immediately after ranking | Same text after reveal beat |
| 3c | Coverage | Coverage and limitations | Required | Required (never omit for polish) |
| 4 | Cross-country | Only if multiple Countries | Text | Text |
| 5 | Scope and gaps | Execution scope and gaps | Required | Required |

**Hard rules**

- Column headers, order, number formatting, zero-baseline copy match
  `report-format.md` for the chosen language.
- Every selected Vendor has a ranking row; ETA/check cells from returned facts
  or explicit local failure — never invented buckets.
- Demo must not reduce data completeness relative to Ops.
- `prefers-reduced-motion`: final composed state, no looping motion.
- Demo seek harness: `?t=N` freezes a master timeline for screenshots.

## 6. Data contract (report payload)

HTML must not re-analyze. After the Markdown delivery gate, the agent builds a
**structured payload** from the same verified bindings used for Markdown and
embeds it in the HTML as
`<script type="application/json" id="pastdue-report">` so Ops and Demo share
one source.

### 6.1 Shape (logical)

```text
meta: mode, language, timezone, calendar-mode, generated-at, metric-version
run: conclusion, trigger flags (THRESHOLD_MET / TEST_FORCED), P/T per country
countries[]: scalars matching Country metrics columns (exact order binding)
per_country[]:
  ranking[]: all ranking columns + main Code/Source cell strings + check cell
              + analysis-status
  summary: analysis summary text as delivered
  coverage: coverage + limitations as delivered
cross_country_summary?: optional
scope_gaps: required key (may be an empty explicit “no gaps” structure)
```

Field meanings and display formatting follow `report-format.md` and existing
metric/data contracts. The HTML skill reformats for display only; it does not
recompute formulas.

### 6.2 Gate (all required)

1. `mode` ∈ {DAILY, TEST}
2. Standard Markdown modules already delivered per `report-format.md`
3. Every selected Vendor present in `per_country[].ranking` with ETA/check or
   explicit local failure
4. `scope_gaps` present

If any check fails → skip HTML; state the reason in one or two sentences.

## 7. Changes to existing skills

### 7.1 `pastdue-analysis` (minimal)

- In the deliver section of `SKILL.md`: after a successful standard DAILY/TEST
  report, load and run `pastdue-html-report`.
- Optional one-line pointer at the end of `report-format.md` to the companion.
- **Do not** move animation recipes, HTML templates, or payload schemas into
  the analysis skill.

### 7.2 New `skill/pastdue-html-report/`

| Path | Role |
|---|---|
| `SKILL.md` | When to run, gate, Ops/Demo, when to invoke animation skills |
| `references/payload-contract.md` | Payload shape + column mapping to report-format |
| `references/html-shell.md` | Page skeleton, CSS variables, mode toggle, `?t=`, reduced-motion |

No Atlas queries inside this skill.

## 8. Error handling

- HTML generation errors never retract or rewrite the Markdown report.
- Disclose HTML skip/failure briefly in the same turn.
- Partial Country success follows analysis rules: HTML may include verified
  Countries and must surface remaining gaps in `scope_gaps` — same honesty as
  Markdown. Do not pad missing Vendor ranks.

## 9. Acceptance criteria

1. Auto hook runs after successful DAILY/TEST Markdown in the same turn.
2. Spot-check: ranking row numbers and ETA/check cell text match Markdown for
   sampled Vendors.
3. Selected Vendor rows are complete; `scope_gaps` visible in Ops and Demo.
4. Default view is Ops; Demo toggle / `?mode=demo` works without changing data.
5. `prefers-reduced-motion` shows final state without looping motion.
6. Demo supports `?t=N`; file opens in a browser with no build step.
7. HISTORICAL events-only, INTERACTIVE, and gate failure do not auto-emit HTML
   and explain the skip.

## 10. Implementation outline (post-spec)

1. Add `skill/pastdue-html-report/` with SKILL + payload-contract + html-shell.
2. Add minimal success hook to `pastdue-analysis`.
3. Build Ops HTML from a sample TEST/DAILY payload; verify column parity.
4. Add Demo timeline + diagram/svg; keep isometric Demo-optional and Ops-off.
5. Verify reduced-motion, seek harness, and skip paths.

## 11. Decisions already locked

| Topic | Decision |
|---|---|
| Audience | Both Ops archive and Demo presentation (C) |
| Integration | New companion skill, not inlining HTML into analysis (approach 1) |
| Trigger | Automatic after DAILY/TEST success (A) |
| Authority | Markdown modules + verified facts; HTML is presentation |
| HISTORICAL / INTERACTIVE auto HTML | Out of scope for v1 |

# PastDue HTML on-demand redesign

Date: 2026-11-16  
Status: approved — implementing per plan 2026-11-16-pastdue-html-on-demand  
Supersedes (trigger/behavior only): auto-HTML obligation in
`2026-11-13-pastdue-html-report-design.md` after successful DAILY/TEST Markdown.
Payload contract, Ops/Demo shell, and `payload_gate` remain unless noted.

## 1. Goal

Keep **Markdown as the primary deliverable**. HTML is an optional Ops/Demo
presentation layer, produced only when the user asks for it (up front or after a
prompt), never as a silent same-turn obligation after MD.

Problems this redesign addresses:

- Auto-HTML after DAILY/TEST caused context pressure, false `WRITTEN`, and skips
  framed as GATE.
- Users who only want MD should not get HTML side effects.
- Users who only want HTML should not be forced through a full MD body.

## 2. Decisions (approved)

| Topic | Choice |
|-------|--------|
| Approach | Trigger-matrix rewrite (cancel default auto-write) |
| Explicit HTML-only scope | Any analysis that can assemble a valid HTML payload (not limited to DAILY/TEST) |
| After user confirms HTML | Reuse in-memory bindings from the prior delivery; do **not** re-query Atlas by default |
| Ask-after-MD offer | **Only** after standard DAILY / TEST MD; HISTORICAL / INTERACTIVE do not ask unless user initiates |

## 3. Trigger matrix and turn protocol

Markdown remains authoritative for analysis content. HTML never becomes a second
analysis pass and must not scrape delivered Markdown as truth.

| Intent | Behavior |
|--------|----------|
| **HTML_ONLY** (user clearly wants HTML / web report only) | Run analysis bind/complete as usual; **do not** emit MD body; same turn: payload → gate → write HTML → disclose real path(s). Any mode if payload can be built. If not buildable: short gap disclosure; no fake MD; no fake `WRITTEN`. |
| **Default DAILY / TEST** (HTML not mentioned) | Emit full MD; end the turn with **one** ask (e.g.「需要我再生成一份 Ops/Demo HTML 报告吗？」). **No** HTML write; **no** `HTML: WRITTEN` this turn. |
| **Follow-up confirm** (user says yes) | Build payload from **prior-turn bindings** (no Atlas re-query). Success → write + `WRITTEN`/`SPLIT` with real path(s). Missing bindings → `SKIPPED — reason=BINDING`; do not auto re-query; may tell user to re-run analysis or explicitly allow re-query. |
| **HISTORICAL / INTERACTIVE** without HTML ask | MD (or interactive answer) only; **do not** offer HTML. Later explicit HTML request → HTML_ONLY or confirm-export with binding reuse. |

Hard rules:

- `HTML: WRITTEN` without a real written path is forbidden.
- No “claim success then write later.”
- When HTML is produced in a later turn, do not re-dump the full MD report.

## 4. Components and data flow

### 4.1 `pastdue-analysis`

- Owns intent, Atlas fetch, binding, MD layout (`report-format.md`).
- Resolves `HTML_ONLY` vs default.
- After DAILY/TEST MD only: append the single HTML offer question.
- Does **not** write HTML or emit `HTML: WRITTEN`.

### 4.2 `pastdue-html-report`

- Load only for: (1) same-turn `HTML_ONLY`, or (2) user-confirmed HTML follow-up.
- Same pipeline: verified bindings → payload → gate → shell → disk.
- Keep CAPACITY country split, retention-after-disclose, optional verification helper.
- Remove “default obligation to write immediately after successful MD.”

### 4.3 `SYSTEM_PROMPT` / `policy.edn`

- Align with matrix: no auto-write after MD; ask-then-confirm for DAILY/TEST;
  HTML_ONLY; binding reuse; `BINDING` skip reason.

### 4.4 Data flow

```text
User request
  ├─ HTML_ONLY → bind → payload/gate → write .html → disclose path (no MD body)
  └─ Default DAILY/TEST → bind → MD → ask HTML? → end turn
                              └─ next turn “yes” → reuse bindings → payload/gate → write .html
                                 (if bindings gone → BINDING skip; no default re-query)
```

## 5. Error handling and anti-false-WRITTEN

Disclose only when an HTML attempt actually runs:

```text
HTML: WRITTEN|SPLIT|SKIPPED — reason=<CODE> — detail=<specifics>
```

| Case | Action |
|------|--------|
| Write OK | Host write succeeded first; then `WRITTEN`/`SPLIT` with **real path(s)** |
| Gate fail | `SKIPPED — reason=GATE — detail=<gate reason>`; no partial file |
| Host write fail | `SKIPPED — reason=HOST — detail=<error>` |
| Confirm HTML but bindings lost | `SKIPPED — reason=BINDING — detail=…`; no default re-query |
| Capacity | `SPLIT — reason=CAPACITY` by Country; each slice gated; list all paths |
| HTML_ONLY but incomplete bind | No MD body; no `WRITTEN`; name missing modules/bindings |
| Default DAILY/TEST ask turn | MD + question only; must not emit `HTML: WRITTEN` |

Anti-false-WRITTEN:

1. Necessary and sufficient for `WRITTEN`/`SPLIT`: successful write to an accessible path this turn.
2. Forbidden: claim success before write.
3. The offer question is not HTML delivery; no write until user confirms (unless HTML_ONLY).
4. Retention after disclose unchanged (no delete without overwrite in the same turn).

## 6. Implementation surface (out of scope until plan)

| File | Change |
|------|--------|
| `agent/SYSTEM_PROMPT.md` | Replace auto-HTML with HTML_ONLY / ask / confirm |
| `skill/pastdue-analysis/SKILL.md` | Companion subsection → ask-then-confirm |
| `skill/pastdue-analysis/references/policy.edn` | Turn off auto obligation; add ask-after, html-only, binding-reuse, BINDING |
| `report-format.md` / `table-display-contract.edn` | Sync “no auto HTML” |
| `skill/pastdue-html-report/SKILL.md` | New triggers; path-required WRITTEN; keep gate/SPLIT/retention |
| `payload-contract.md` / `acceptance-checklist.md` | Align triggers and anti-false-WRITTEN |
| `release-manifest.edn` | Refresh hashes |

Unchanged unless plan says otherwise: `payload_gate.py`, `render_ops_shell.py` core behavior, Atlas query paths.

## 7. Acceptance

1. TEST with no HTML mention → MD + one ask; no HTML file; no `WRITTEN`.
2. User says yes → HTML from reused bindings + real path; no Atlas re-query (or `BINDING` if lost).
3. “HTML only” → no MD body; HTML with path (or honest SKIPPED).
4. HISTORICAL/INTERACTIVE without HTML ask → no offer, no HTML write.
5. Never again: `WRITTEN` with no file on disk.

## 8. Non-goals

- Host-enforced middleware that blocks false `WRITTEN`.
- Persisted cross-session binding store (beyond normal agent turn memory).
- Changing metric formulas or MD module layout authority.
- Making HTML a substitute for incomplete MD ranking delivery when MD was requested.

## Spec self-review

- Placeholders: none intentional; ask example is illustrative, language follows request language.
- Consistency: HTML_ONLY scope (any payload-capable analysis) vs ask-after (DAILY/TEST only) is intentional, not a contradiction.
- Ambiguity: “clearly wants HTML” left to normal intent resolution; exact synonym list not frozen in this spec.
- Scope: trigger/prompt/skill policy only; gate/shell code paths retained.

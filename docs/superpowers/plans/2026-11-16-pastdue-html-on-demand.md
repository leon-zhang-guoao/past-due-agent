# PastDue HTML On-Demand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace auto-HTML after DAILY/TEST Markdown with MD-first ask-then-confirm, plus HTML_ONLY when the user explicitly wants HTML and no MD body.

**Architecture:** Keep `pastdue-analysis` as analysis/MD authority and `pastdue-html-report` as presentation-only. Change only trigger/protocol wording in prompt, analysis skill, policy, and companion skill. Reuse existing payload gate, shell, CAPACITY split, and retention rules. No Atlas changes.

**Tech Stack:** Agent Skills (Markdown), `policy.edn`, existing Python `unittest` for gate/render (unchanged behavior).

**Spec:** `docs/superpowers/specs/2026-11-16-pastdue-html-on-demand-design.md`

## Global Constraints

- Markdown remains primary; HTML is on-demand presentation only.
- No auto-write HTML after successful DAILY/TEST MD; ask one question instead.
- HTML_ONLY: any mode that can assemble a valid payload; no MD body that turn.
- Confirm-HTML follow-up: reuse prior bindings; no default Atlas re-query; `BINDING` if lost.
- Ask-after-MD only for standard DAILY/TEST; HISTORICAL/INTERACTIVE do not offer unless user initiates.
- `HTML: WRITTEN`/`SPLIT` require a real written path this turn; no false WRITTEN.
- Disclose line adds `BINDING`; keep `GATE|CAPACITY|HOST|MODE|OK`.
- Do not scrape Markdown as truth; do not re-query inside html-report.
- Do not commit unless the user explicitly asks.
- Refresh `release-manifest.edn` hashes for every edited tracked source file.

## File map

| Path | Responsibility |
|---|---|
| `agent/SYSTEM_PROMPT.md` | Agent-facing on-demand / HTML_ONLY / ask / anti-false-WRITTEN |
| `skill/pastdue-analysis/SKILL.md` | Replace auto companion with ask-then-confirm + HTML_ONLY handoff |
| `skill/pastdue-analysis/references/policy.edn` | `html-companion` keys for new matrix |
| `skill/pastdue-analysis/references/report-format.md` | HTML companion pointer text |
| `skill/pastdue-analysis/references/table-display-contract.edn` | Sync flags if present |
| `skill/pastdue-analysis/references/release-manifest.edn` | sha256 for changed paths |
| `skill/pastdue-html-report/SKILL.md` | Load only HTML_ONLY or user-confirm; path-required WRITTEN; BINDING |
| `skill/pastdue-html-report/references/payload-contract.md` | Producer checklist / BINDING |
| `skill/pastdue-html-report/references/acceptance-checklist.md` | New acceptance rows |
| `skill/pastdue-analysis/references/release-manifest.edn` | Also `:html-companion-auto-after` if present at top |

---

### Task 1: Rewrite `pastdue-html-report` trigger protocol

**Files:**
- Modify: `skill/pastdue-html-report/SKILL.md`
- Modify: `skill/pastdue-html-report/references/payload-contract.md`
- Modify: `skill/pastdue-html-report/references/acceptance-checklist.md`

**Interfaces:**
- Consumes: design §§3–5
- Produces: companion skill that loads only for HTML_ONLY or confirmed follow-up; skip reasons include `BINDING`; `WRITTEN` requires path

- [ ] **Step 1: Replace frontmatter description and §§1–2**

Remove “after successful MD, must attempt write.” Replace with:

- Load when: (a) user requested HTML_ONLY this turn, or (b) user confirmed HTML after a prior DAILY/TEST MD ask.
- Do not load solely because MD succeeded.
- Forbidden: `HTML: WRITTEN` without listing real path(s) written this turn.

- [ ] **Step 2: Update outcomes table**

Keep `WRITTEN|SPLIT|SKIPPED`. Add:

| `reason` | When |
|----------|------|
| `BINDING` | User confirmed HTML but prior verified bindings are unavailable; no default re-query |

State: default DAILY/TEST MD turn must not emit any `HTML:` success line from this skill (analysis owns the ask only).

- [ ] **Step 3: Align payload-contract producer checklist**

Replace “must attempt after Markdown success” with HTML_ONLY / confirm-follow-up. Document `BINDING`. Keep gate checklist and CAPACITY split unchanged.

- [ ] **Step 4: Update acceptance-checklist.md**

Replace auto-hook AC with:

1. Default TEST → MD + ask, no file / no WRITTEN  
2. Confirm → path listed WRITTEN (or BINDING)  
3. HTML_ONLY → no MD body + path or honest SKIPPED  
4. No false WRITTEN  

Keep existing gate/render unittest pointer (`34` tests) as still green.

- [ ] **Step 5: Verify by reading**

Confirm SKILL no longer contains `default obligation` / “automatic after” write. Confirm `BINDING` appears in skip table.

- [ ] **Step 6: Commit only if user asked** — otherwise stop for review.

---

### Task 2: Analysis skill + report-format + table-display

**Files:**
- Modify: `skill/pastdue-analysis/SKILL.md` (HTML companion subsection ~L93+)
- Modify: `skill/pastdue-analysis/references/report-format.md` (`## HTML companion`)
- Modify: `skill/pastdue-analysis/references/table-display-contract.edn` (html-companion keys if any)

**Interfaces:**
- Consumes: Task 1 companion load rules
- Produces: analysis owns ask; never writes HTML / never emits WRITTEN

- [ ] **Step 1: Replace `### HTML companion` subsection**

Title suggestion: `### HTML companion (on-demand)`

Required behaviors:

1. Resolve HTML_ONLY from user intent → after bind/complete, load `pastdue-html-report`; **no MD body**.
2. Default successful standard DAILY/TEST MD → append one ask in request language; do not load html-report this turn.
3. Follow-up yes → load html-report; payload from prior bindings; no re-query.
4. HISTORICAL/INTERACTIVE: no ask unless user initiates HTML.
5. Analysis never emits `HTML: WRITTEN`.

Suggested ask (zh): `需要我再生成一份 Ops/Demo HTML 报告吗？`  
(en): `Would you like me to generate an Ops/Demo HTML report as well?`

- [ ] **Step 2: Update report-format.md HTML companion**

Match ask-then-confirm / HTML_ONLY; remove “must attempt” auto emit.

- [ ] **Step 3: Sync table-display-contract.edn**

If keys exist, set companion to on-demand (e.g. keep `:html-companion-skill`, clear any auto-after implication). Do not invent unrelated keys.

- [ ] **Step 4: Grep verification**

Run from repo root `pastdue-agent/`:

```bash
rg -n "default obligation|must attempt Ops/Demo HTML|automatic after standard DAILY" skill/pastdue-analysis/SKILL.md skill/pastdue-analysis/references/report-format.md skill/pastdue-html-report/SKILL.md
```

Expected: no matches for auto-write obligation (ask/HTML_ONLY wording OK).

---

### Task 3: SYSTEM_PROMPT + policy.edn

**Files:**
- Modify: `agent/SYSTEM_PROMPT.md`
- Modify: `skill/pastdue-analysis/references/policy.edn`
- Modify: `skill/pastdue-analysis/references/release-manifest.edn` (top-level `:html-companion-auto-after` if present)

**Interfaces:**
- Consumes: Tasks 1–2
- Produces: prompt/policy matrix matching spec §3

- [ ] **Step 1: Rewrite SYSTEM_PROMPT HTML paragraph**

Replace current “must attempt Ops/Demo HTML” block with:

- MD primary
- HTML_ONLY → no MD body; companion write + path
- DAILY/TEST default → MD then one ask; no HTML that turn
- Confirm → reuse bindings; BINDING if lost
- No WRITTEN without path
- Keep retention / no rm after disclose / optional render script wording

Keep Atlas-only / no runtime Python analysis bans unchanged.

- [ ] **Step 2: Update `policy.edn` `:html-companion` map**

Set approximately:

```edn
:html-companion {:skill "pastdue-html-report"
  :auto-after-modes []
  :ask-after-modes ["DAILY" "TEST"]
  :html-only-when-user-requests true
  :html-only-any-payload-capable-mode true
  :skip-modes ["HISTORICAL_EVENTS_ONLY" "INTERACTIVE"] ;; no auto-ask; user may still request
  :payload-from "same verified bindings as Markdown"
  :re-query false
  :confirm-reuses-prior-bindings true
  :scrape-markdown-as-truth false
  :authority "Markdown modules remain layout authority when MD was delivered"
  :presentation-only true
  :default-obligation-after-markdown-success false
  :forbid-vague-incomplete-payload-skip true
  :forbid-written-without-path true
  :disclose-line "HTML: WRITTEN|SPLIT|SKIPPED — reason=GATE|CAPACITY|HOST|MODE|BINDING|OK — detail=…"
  :capacity-split-by-country true
  :gate-failure-keeps-markdown true
  :preferred-emit "equivalent HTML matching html-shell.md / payload-contract"
  :optional-verification-script "skill/pastdue-html-report/scripts/render_ops_shell.py"
  :not-a-host-markdown-renderer true}
```

Also update release-manifest top key `:html-companion-auto-after` to `[]` or remove auto list if that key exists (currently `["DAILY" "TEST"]`).

- [ ] **Step 3: Grep SYSTEM_PROMPT**

```bash
rg -n "must attempt|auto-HTML|WRITTEN" agent/SYSTEM_PROMPT.md
```

Expected: no “must attempt”; WRITTEN only with path requirement.

---

### Task 4: Manifest hashes + regression tests

**Files:**
- Modify: `skill/pastdue-analysis/references/release-manifest.edn` (all sha256 entries for edited files)

**Interfaces:**
- Consumes: Tasks 1–3 file bytes
- Produces: matching hashes; gate/render still green

- [ ] **Step 1: Recompute sha256** for every edited path under `skill/` and `agent/SYSTEM_PROMPT.md`; replace all occurrences in `:sha256` / `:skill-sha256` maps (same pattern as prior HTML work).

- [ ] **Step 2: Run unit tests**

```bash
python -m unittest tests.test_html_report_payload tests.test_html_report_render -v
```

Expected: **34 tests, OK** (gate/shell unchanged).

- [ ] **Step 3: Doc consistency spot-check**

```bash
rg -n "default-obligation-after-markdown-success true|automatic after standard DAILY/TEST|must attempt Ops/Demo HTML" agent skill/pastdue-analysis skill/pastdue-html-report
```

Expected: no hits (false may appear; true must not).

- [ ] **Step 4: Manual acceptance notes** (for deployer / sandbox pilot)

Record in chat or SDD note:

1. TEST without HTML mention → MD + ask only  
2. Reply 要 → HTML path or BINDING  
3. 只要 HTML → no MD body + path  

- [ ] **Step 5: Commit only if user asked.**

---

## Execution notes

- Prefer **subagent-driven-development** with one task per subagent review cycle.
- Do not expand into animation packaging or renderer code changes unless a test fails.
- If `table-display-contract.edn` has no auto flag, skip inventing keys beyond a one-line comment-free boolean already used by the project.
- Spec status may be updated to `approved` in the same PR/change set after implementation starts, only if user agrees.

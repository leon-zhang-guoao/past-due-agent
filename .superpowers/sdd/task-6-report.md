# Task 6 Report — Wire pastdue-analysis hook

**Status:** DONE  
**Commit:** `5335e25` (HEAD was `9ed76d5`)

## Changes
- `skill/pastdue-analysis/SKILL.md` §4: `### HTML companion` after Deliver, before Optional current context; links `pastdue-html-report`, same-turn handoff, no re-query; excludes HISTORICAL events-only / INTERACTIVE; clarifies Ops/Demo presentation ≠ Markdown renderer substitute.
- `skill/pastdue-analysis/references/report-format.md`: trailing `## HTML companion` pointer; Markdown modules remain layout authority.

## Verification
- `rg -n "pastdue-html-report|HTML companion" skill/pastdue-analysis` → `SKILL.md` + `report-format.md` only (task-6 feature hits).

## Commit scope
- Staged commit contains **+20 lines** HTML hook only (HEAD reset → append → commit → restore WT).
- Unrelated WT edits on both files remain **uncommitted** (3.3.16 deliver/ranking refactor, etc.).

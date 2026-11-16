### Task 6: Wire `pastdue-analysis` hook

**Files:**
- Modify: `skill/pastdue-analysis/SKILL.md` (section 4 Deliver)
- Modify: `skill/pastdue-analysis/references/report-format.md` (end pointer)

**Interfaces:**
- Consumes: companion skill name `pastdue-html-report`
- Produces: automatic handoff instruction after successful DAILY/TEST

- [ ] **Step 1: Append to section 4 of `SKILL.md`** (after existing deliver paragraphs, before Optional current context):

```markdown
### HTML companion (automatic after standard DAILY/TEST)

After a successful standard DAILY or TEST Markdown report — all applicable
report-format modules delivered, every selected Vendor ranking row present with
main Code/Source/check cells or explicit local failure, and scope/gaps stated —
load and follow [pastdue-html-report](../pastdue-html-report/SKILL.md) in the
same turn. Build the HTML payload from the same verified bindings used for
Markdown; do not re-query. If the HTML gate fails or rendering errors, keep the
Markdown delivery unchanged and disclose the HTML skip/failure in one or two
sentences. Do not auto-run the HTML companion for HISTORICAL events-only or
INTERACTIVE answers.
```

Keep the existing sentence “There is no report renderer” — clarify in the new
subsection that the companion is optional presentation, not a substitute
renderer for Markdown completeness.

- [ ] **Step 2: Append to `report-format.md`:**

```markdown
## HTML companion

After a successful standard DAILY/TEST report, the agent also emits an Ops/Demo
HTML file per `skill/pastdue-html-report/`. Markdown modules above remain the
layout authority for analysis content.
```

- [ ] **Step 3: Grep check**

Run: `rg -n "pastdue-html-report|HTML companion" skill/pastdue-analysis`  
Expected: hits in `SKILL.md` and `report-format.md` only for this feature.

---


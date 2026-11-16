### Task 4: `pastdue-html-report` SKILL.md

**Files:**
- Create: `skill/pastdue-html-report/SKILL.md`

**Interfaces:**
- Consumes: payload-contract, html-shell, animation skills paths
- Produces: agent-executable instructions for auto HTML

- [ ] **Step 1: Write SKILL.md** with frontmatter:

```yaml
---
name: pastdue-html-report
description: After a successful PastDue DAILY or TEST standard Markdown report, build a verified JSON payload and emit one Ops/Demo HTML file without re-querying Atlas.
---
```

Body must include:
1. Load only after `pastdue-analysis` standard DAILY/TEST Markdown success.
2. Build payload from in-memory verified bindings per `references/payload-contract.md`.
3. Run gate; on failure skip HTML and disclose reason (1–2 sentences).
4. Emit HTML per `references/html-shell.md` (prefer `scripts/render_ops_shell.py` for Ops baseline, then enhance Demo in-page).
5. Write file under workspace (path disclosed in chat).
6. Animation: read `../diagram-animation-*/SKILL.md` for Demo beats and light Ops count-up/bars; `../svg-animation-*/SKILL.md` for status marks; `../isometric-animation-*/SKILL.md` only if Demo decoration explicitly wanted — Ops default off.
7. Never modify Markdown already delivered; never award overall PASS.

Use relative glob note: folder names include version suffixes — instruct agent to resolve the local `diagram-animation-*`, `svg-animation-*`, `isometric-animation-*` directories under `skill/`.

- [ ] **Step 2: Sanity read** — ensure no Atlas query instructions and no contradiction with analysis “no report renderer” (HTML is companion-only).

---


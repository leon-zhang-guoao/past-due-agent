---
name: pastdue-html-report
description: Business Storyteller sub-agent presentation workflow for an authorized PastDue HTML work order; render verified parent-supplied bindings as self-contained Ops/Demo HTML and return artifact evidence without querying business data.
---

# PastDue HTML Report — Business Storyteller

This skill runs inside the registered `business-storyteller` sub-agent. PastDue
owns business analysis and output intent; this child owns design, HTML/SVG,
animation, writing and presentation checks. The parent delegates through the
[sub-agent contract](references/sub-agent-contract.md), not an inline role switch.

## 1. Accept a work order

Require `HTML_ONLY` or `CONFIRM_EXPORT` and a verified canonical payload, provenance
and allowed output location. Default DAILY/TEST Markdown success alone does not
authorize rendering. Read [payload contract](references/payload-contract.md) for
required fields and `pastdue-analysis/references/report-format.md` for canonical
sections, columns and number formatting. Prior bindings unavailable → `BINDING`.
Keep actual mode; complete DAILY/TEST/HISTORICAL/INTERACTIVE payloads are supported.

## 2. Gate before presentation

Apply `scripts/payload_gate.py` or its documented checklist before writing.
Require Country metrics, complete selected Vendor ranking rows including main
Code/Source/check cells, summaries, coverage and scope_gaps. Exact local failure
strings remain valid facts. A partial interactive answer or events-only list
without these modules returns `GATE`; never relabel its mode or invent missing data.

Use only parent bindings, without re-querying, recomputing metrics or scraping
Markdown. Missing fields must name the Country/Vendor/key in the failure receipt.

## 3. Design and write

Read [HTML shell](references/html-shell.md). Produce a self-contained HTML with
the canonical embedded JSON and complete Ops tables. Ops is default; Demo changes
the reveal sequence, not data completeness. Preserve summaries, ETA/check cells,
coverage and gaps. Use animation skills selectively after Ops is complete;
diagram-animation, svg-animation and isometric-animation are optional.

Prefer one file, `pastdue-{mode}-{countries}-{target}.html`, under the work order's
allowed directory. If capacity requires a split, write gated Country slices and
return every path. Keep the original Country order and complete combined scope.
Escape source strings and embedded JSON. Respect `prefers-reduced-motion`, print
and `?t=N`. Readable Ops content must exist independently of Demo JavaScript.

`scripts/render_ops_shell.py` is an optional local fixture/verification helper,
not a required analysis runtime or a replacement for this sub-agent call.

## 4. Verify and return

Read back the actual file, verify unchanged payload and canonical table/text
content. `scripts/verify_artifact.py <payload.json> <report.html>` is an optional
local content check. Validate Ops/Demo, seek, print and reduced motion with browser
tools when available; disclose `NOT_RUN` when unavailable.

Return the structured receipt defined in the sub-agent contract: work-order ID,
WRITTEN/SPLIT/SKIPPED, reason, actual path(s) and validation evidence. Do not issue
a separate user-facing report. The parent independently checks the artifact and
owns the final observation line and links. A receipt alone is not delivery proof.

After write/disclosure retain session artifacts. Gate failure writes no report;
write failures return `HOST`; content mismatches return `VERIFY`. Do not claim
success before a file is written or invent a path. Preserve any already-delivered
Markdown. Business changes go back to the parent, not into the visual layer.

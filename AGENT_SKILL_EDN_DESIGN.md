# Responsibilities —3.5.1

Interactive planning: one analysis specification, supported operation composition,
actual-feedback recovery and bounded cost. Atlas explore-v2 adds Vendor-set
selection/coverage/source paging using unchanged metric3.1.0; existing paths stay
compatible. Skill handles planning guidance, not automatic host enforcement.
See docs/COMPOSABLE_ANALYSIS_3_4.md and the Skill's interactive-planning.md.

| Artifact | Responsibility | Not responsible for |
|---|---|---|
| System Prompt | Role, routing intent, language, trust boundaries | Repeated formulas, giant templates |
| Skill | Resolve scope, call/read/bind, checks, diagnose, report modules | Runtime Python, mail, source SQL |
| Atlas EDN | Governed structured metrics, denominators, rank, compact ETA summaries, checks | Whole-report Markdown, final-output certification |
| Model output | Relevant business interpretation and module formatting | Guaranteed deterministic rendering or independent evaluation |

One layout: Skill references/report-format.md. One metric source: metric-definitions.md.
One diagnostic dictionary: eta-diagnostic-rules.edn. Runtime can upload only Prompt,
complete Skill and EDN; no notification/scripts folder required. Details and real
validation plan: docs/STRUCTURED_REPORTS_3_3.md / DEPLOYMENT_V3.md / VALIDATION_V3.md.

3.3.7 separates business fact types, transport association and actual delivery.
The Skill cannot install a parameter cleanup interceptor; final visible malformed
arguments do not identify which host/model component produced them. Current
correction/live boundary: docs/RESULT_READER_FIX_3_3_7.md.


HTML presentation uses the existing platform's native `business-storyteller`
sub-agent. PastDue retains business facts, conclusions, Markdown and final artifact
verification; the child owns HTML/SVG design, animation and writing. The complete
HTML Skill contains its scoped prompt and the single handoff/receipt contract.
See docs/HTML_SUB_AGENT_3_5_1.md for platform registration and actual live evidence
requirements. Prompt/Skill files alone do not register or invoke a platform agent.

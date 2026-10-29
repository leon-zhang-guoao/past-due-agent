# Responsibilities —3.3.7

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

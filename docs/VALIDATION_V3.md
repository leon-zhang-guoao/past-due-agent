# Validation record — 3.1.3 Agent/report update

Status: **PENDING_LIVE_ACCEPTANCE**.

| Area | Actual status | Evidence boundary |
|---|---|---|
| Prompt / Skill / display changes | Implemented locally | Not a real Agent execution |
| Offline contracts / Skill structure / regressions | NOT_RUN | Explicit user request; no suites run |
| Local ClickHouse SQL | NOT_RUN | Atlas SQL unchanged from 3.1.1 |
| Platform config / deployed JDBC / pagination | NOT_RUN | No live environment used |
| Real Agent report correctness / repeatability | NOT_RUN | Requires raw tool evidence and final reports |
| Packaging | Content hashes and ZIPs only | Does not certify any analysis or report |

Agent contract 3.1.3 uses unchanged Atlas publication 3.1.1/revision 2 and compatible
metric version 3.1.0. `COUNTRY_VENDOR_SUMMARY_3_1_3.md` records the current presentation change;
`REPORT_RENDERING_3_1_2.md` records the base rendering changes and evidence
limits, deployment instructions and pending real-run checks. `VALIDATION_V3_1_1.md`
is historical evidence only; its PASS claims do NOT apply to changed 3.1.3 files.

Packaging command used for this delivery:

```sh
python3 -B scripts/build_release.py --package-only
```

This path does not import or execute the offline test runner or accept prior SQL
receipts. It records NOT_RUN, no validation input hashes, and separate artifact
hashes. The archive-integrity/sensitive-file checks are packaging safeguards, not
business-logic tests. Production acceptance stays pending even when ZIPs are built.

No credentials, direct production database access, mail or runtime Python used.
No actual Agent/Atlas verification, performance measurement or host hard gate is
claimed. Real-run acceptance must record identities, handle linkage, expected vs
received scope, reconciliations and final bilingual output, not just file counts.

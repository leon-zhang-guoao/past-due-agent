# v3.1 implementation notes

The authoritative merged graph is built by the development-only builders, sharing
SQL selection, ratios and calendar logic. Existing v3 entry semantics remain intact;
new explore/report entries have independent identities including full bound scope.

The current Chinese HTML export contains an incorrect final-report assignment:
Vendor 3493 is rank 8, 275→319 in comparison, Code, Source and checks. The report
copied 75225's 66→305 and declared conflict. This supports REPORT_ERROR, not a
confirmed underlying data conflict. CA 12570 SH+7 and 37108 BUYER+9 are preserved
as sanitized regressions. The English export has no expanded tool payload.

The visible handle/version association anomaly remains UNCONFIRMED without raw
platform call-ID logs. No production access was attempted and no old token reused.

Formal config-check, deployed JDBC/pagination/performance and real Agent behavior
remain separate release blockers. Instructions and offline validators cannot hard
force model completion. See docs/VALIDATION_V3.md for actual executed evidence.

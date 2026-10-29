# Deploy / rollback — Agent3.3.2, Atlas3.3.0

## Patch from3.3.0/3.3.1

Replace System Prompt and the ENTIRE Skill using3.3.2; do not change already deployed
Atlas3.3.0/revision4 for this patch. No SQL/metric/layout changes. See
RESULT_READER_FIX_3_3_2.md: same-connection execute→discovery→actual row reading.
The supplied user-open_atlas manual discovery accepted handle-only arguments;
it does not verify ETA-PastDue-Graph configuration or the Agent's SQL row reads.
Don't blame adapters without actual evidence. Then continue single-Country pilot
below; it is not already passed. Use a fresh chat after replacing complete Prompt/
Skill to reduce carry-over of prior failed calls/old handles; this isn't a fix for
an unconfirmed platform association defect. Never reuse the supplied test handle.

**PENDING_LIVE_PILOT**. No offline suites or real platform/Agent acceptance performed
for this change. Historical3.2 notes are not the current deployment instructions.

## Update these artifacts

1. `atlas/charts/pastdue.edn`: publication3.3.0/revision4, compatible existing APIs.
   Preserve your approved graph/workspace IDs, source binding and external secrets;
   don't overwrite environment configuration with repository placeholders.
   Report rows default source page1000/max1000; SQL bindings unchanged.
2. `agent/SYSTEM_PROMPT.md`: replace the complete deployed System Prompt.
3. Entire `skill/pastdue-analysis/` (or matching Skill ZIP), not SKILL.md alone.
   Retire old installed copies/instructions; report-format.md is the sole layout.

Atlas first, then Prompt+Skill. New3.3 source default reduces report source paging;
older structured APIs remain semantically compatible ONLY if actual full source
pagination/limit is verified. Don't require block endpoints or silently use them.
No notification folder, CSV, scripts or Runtime upload required for the Agent.
No new table/source service. Contacts/Owner/PO remain on demand and display-only.

## Required live gate, one Country first

Use independent plain requests in STRUCTURED_PILOT_3_3.eql; replace example dates
with real complete eligible snapshots. Platform owner performs real config-check,
then actual report query. Confirm typed Decimal/null transport, source limit behavior,
retained handle linkage, reader paging/partition support and row/cell flags.
Default1000 does not prove full reads or fix cancelled queries.

Run `开始测试，只测试 CA` / `start test, CA only` against the SAME actual pair.
Compare selected Vendors/rank and all Code/Source/Other values and checks to real
returns. Confirm 0→0 no risk, required=false no failed label, correct language and
exact Vendor units. Current raw-bucket audit is not performed by compact checks.
Then US+CA, historical and free questions; actual output limits need explicit
continuous parts, never omitted Vendors. Don't use model-issued PASS as evidence.

Record environment, actual input/execute handles, all source/result pages and flags,
original counts/checks, final output, missing scope and human review findings.
If reader supports no complete path, stop rollout and report that gap, don't
modify SQL blindly or fan out to Vendors. Root cause of previous handle anomaly
is UNCONFIRMED until actual call/handle evidence isolates it.

## Rollback

Retain the currently deployed Prompt+complete Skill+Atlas as one environment-specific
backup BEFORE deployment. Roll back that set, not individual files. Repository
3.2 ZIPs are historical code artifacts, NOT a verified working fallback: supplied
run showed truncation. Existing compatible APIs are not deleted by3.3. If a rollback
requires returning to an older page default, use the saved environment graph.
Secrets/IDs stay external. No database rollback or mailing side effect exists.

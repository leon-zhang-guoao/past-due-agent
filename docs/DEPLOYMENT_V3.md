# Deployment and rollback — v3

## Before deployment
1. Keep the old deployment/version and inspect `releases/baseline.json`.
2. Obtain fresh authorized environment configuration; never reuse chat tokens.
3. Run offline regressions and inspect `docs/VALIDATION_V3.md`. NOT RUN is not PASS.
4. Verify the platform can bind logical eta-clickhouse to your approved source.
   Replace workspace/graph placeholders consistently; use secret management.
5. Run the real platform's config-check on the combined artifact. This repository
   does not contain that platform CLI; do not invent a successful check.

## Rollout
1. Deploy compatible expanded Atlas graph first, retaining legacy resolvers.
2. Through actual MCP discover new entities/collections and required inputs.
3. Execute live monitor, fixed weekday pair, all batch pages/checks and current
   aggregate. Verify nullable Decimal serialization, ordering and page bounds.
4. Deploy the full Skill references and System Prompt from the same ZIP.
5. Run 开始测试; then a real triggered daily pair and a historical event. Use
   a known triggered fixture/date if latest isn't triggered. Verify both Countries
   and every selected Vendor, then repeat a fixed question three times.
6. Record actual environment/release, request identities, result evidence,
   reconciliation and final report in an authorized restricted log location.

Do not mark production-ready until these live gates pass. Agent instructions
are not an installed hard submission gate. Missing v3 entry means deployment
mismatch, not permission to use old calendar or positive-only facts.

## Rollback
Restore the saved graph/source binding and matching Prompt/Skill as one set.
`pre-redesign-rollback.zip` preserves pre-change local artifacts; it is not proof
of the previously deployed version. Keep actual approved deployment snapshots.
No database migrations are introduced. Never combine a v3 Skill with a graph
that lacks the required entries. Rotate exposed old credentials separately.

## What to upload
- System Prompt content.
- Complete `skill/pastdue-analysis/` folder, including rules/contracts/manifest.
- `atlas/charts/pastdue.edn` and approved `atlas/sources.edn` binding/configuration.
No notification documentation directory, local CSV, Python scripts or Kernel
service is needed for Agent operation.

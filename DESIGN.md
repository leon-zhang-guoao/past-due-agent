# Active design — v3.1

The analysis bottom layer serves both flexible user questions and standard daily,
history and test presets. Report queries are an optimization, not the only API.
See `docs/API_V3.md` for the current wire contract and `docs/DEPLOYMENT_V3.md`
for deployment/rollback gates. `docs/PASTDUE_AGENT_REDESIGN.md` records the design.

Deploy Prompt + complete Skill + Atlas EDN. Development scripts are not a runtime
service; no host hard gate, SQL console, email sender or external Kernel is presumed.
Business dates use America/Los_Angeles with actual PST/PDT offset. SQL determines
counts, denominators, ranks and summaries; the model interprets verified facts.

# Active design

v3 implementation follows `docs/PASTDUE_AGENT_REDESIGN.md` and the approved
implementation plan. See `docs/API_V3.md` for concrete entry names and
`docs/DEPLOYMENT_V3.md` for actual deployment gates. Historical specs under
`docs/superpowers/` describe prior iterations, not current runtime guarantees.

The production architecture is Prompt + self-contained Skill + Atlas EDN. No
required local Python service or machine-enforced Kernel is installed. Determinism
is concentrated in SQL selection, counts, ranks and checks; language interpretation
and presentation remain guided by contracts. Host hard-gating is out of scope.

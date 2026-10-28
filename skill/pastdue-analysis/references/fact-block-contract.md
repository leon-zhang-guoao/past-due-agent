# Compatibility-only: Atlas 3.2 fact blocks

Agent 3.3 does not use these endpoints by default. Long content-markdown cells
were truncated in the supplied real run; complete row counts did not imply full
text retrieval. Do not use these blocks as a fallback for structured report facts.
The preserved APIs have their original fields/SQL; this is not a promise that
long-text transport or formatting defects are repaired. Historical deployment
notes are docs/FACT_BLOCKS_3_2_0.md in the repository, not runtime instructions.
Use atlas-query-playbook.md and report-format.md for the current workflow.

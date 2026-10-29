# MCP arguments — 3.3.7

[result-reader.md](result-reader.md#1-call-boundary) is the single maintained
argument procedure. It describes calls, not an executable interceptor. There is
no MCP client/serialization middleware installed by this Skill or Atlas EDN.

For platform investigation, record the schema exposed to the model, model-produced
arguments, outbound MCP arguments and original response under the same tool-call
ID, to the extent the host exposes them. Do not invent unavailable stages or blame
one component from the final visible request alone. Deployment steps are outside
this Skill in the project's docs/DEPLOYMENT_V3.md.

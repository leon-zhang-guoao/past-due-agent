(ns open.atlas.server.mcp.impl.instructions
  "The `initialize` instructions an MCP client plans against.

   Migrated **verbatim** from `ent.dx.mcp.instructions` — this text is a tuned
   artefact, not prose to be improved in passing: `one-rule` and
   `query-format` in particular were written to stop two specific and
   expensive agent failure modes (hunting for paths between entities, and
   splitting one record's data across many calls).

   Two edits, both because the subject stopped existing here:

   - the legacy `:rls_denied` / `:role_denied` bullet is replaced by the
     graph-level fact (D5.11: a token grants one graph, and that IS the
     grant) — telling an agent to reason about roles it cannot have would
     teach it to explain empty results with a cause this platform has none of;
   - the entity-index renderers (`render-domains`, `data-domains-section`) are
     not carried: they fed the legacy skill generator, and instructions here
     deliberately embed no index — clients discover by CALLING the tools, so
     this string stays fixed and small however large the model grows.

   One addition since (proposal studio-saved-queries, 2026-10-10): step 0 points
   at `list_saved_queries`, worded conditionally because a host without saved
   queries does not mount it."
  (:require [clojure.string :as str]))

(def one-rule
  "The engine-owns-the-path rule — channel-agnostic. Shared VERBATIM by the MCP
   instructions and the generated agent skill so the two can't drift on the single
   most important behaviour.

   The rule's own text is untouched here: it is the shared artefact, and the
   only other copy (`ent.dx.mcp.instructions`) is outside this change's scope."
  "## THE ONE RULE THAT MATTERS

You say WHAT you want (the attributes); the engine works out HOW to get it (the path). NEVER try to discover, verify, or reason about paths between entities — the engine auto-navigates via resolver inputs/outputs; path-finding is ITS job, not yours.

From a SINGLE starting record, pull data from EVERY entity it connects to — the vendor, the customer, the linked orders, their line items, the credit profile — in ONE query, no matter how many entity TYPES that spans. One question about one record = ONE query. If two attributes exist, put them in the same query: the engine either finds the path or tells you exactly what's missing (`:meta :unreached` + `:hint`). Splitting one record's related data across several calls (one call per entity type), hunting for relationships, splitting \"to be safe\", or giving up because no link is apparent are all failure modes.")

(def query-format
  "The EDN query format + framing rules — channel-agnostic. Shared verbatim by the
   MCP instructions and the generated agent skill so the EQL syntax can't drift."
  "### Query Format

All queries use this EDN map format:
```
{:entity {:ns/key \"value\"} :query [:ns/attr1 :ns/attr2]}
```

- `:entity` — root entity context. Identity attributes scope the query; `{}` for global/collection queries.
- `:query` — EQL vector of attributes to return. ALL scalar attributes go FLAT, even cross-domain ones — Pathom resolves them automatically. A to-ONE related entity's fields are scalar too — list them FLAT, never wrapped in a join. e.g. a VPO's vendor → RIGHT: `:cis.master.vendor/vend_name` directly in `:query`; WRONG: `{:cis.master.vendor/_vend_no [...]}`.
- Join syntax `{:parent/children [:child/attr ...]}` is ONLY for the one-to-many collections an entity lists under \"Joins\". NEVER use a reverse reference (`:ns/_attr`) — to-one related data is always flat.
- Parameters (optional) wrap the key in a list: `({:coll/all [...]} {:limit 10})` or `{(:join {:limit 5}) [...]}`. Common params: :limit :offset :order_by :sort_dir.
- `:entity` roots at exactly ONE identity — but that one root REACHES every connected entity. List every attribute you want (vpo, mso, customer, credit, line items, …) in that single `:query` and the engine navigates to each entity type for you. \"Several entities\" means several SEPARATE starting records — e.g. two unrelated order numbers, or three customers you're comparing — and ONLY those get one call each; different entity TYPES reached from one record never do. Do NOT cram multiple identity values into one `:entity` — with one exception: some things are recognised by two attributes together, and for those you give BOTH in the same `:entity` map (list_entities shows every identity key of an entity). That is still ONE record. NEVER claim an entity \"doesn't exist\" or \"wasn't found\" unless you queried that exact identity and it came back empty.")

(def ^:private usage-template
  (str "## How to Query

Pick attributes → query. There is NO \"understand relationships\" step.

0. **list_saved_queries** — when this server offers it, look there first: a saved query whose description fits your question is run by name with run_saved_query, and is more reliable than EQL you write yourself. Otherwise continue below.
1. **list_entities** — START HERE. Browse the entities in this graph, their identity keys and sizes — pick the ones your question touches.\n1b. **search_attributes** — when you do not know which entity holds a field, search for the word a human would use for it, in Chinese or English, typos and partial words included. A \"term\" hit is a glossary term and lists every attribute bound to it.
2. **list_attributes** — for the entities you picked, dump their attribute set (names, types, params) and copy the EXACT attribute names to query. Rows of :type \"join\" hold rows of another entity — nest into them with their declared parameters. A join row with :targets reaches several entities (nest one branch per entity, keyed by its identity attribute, as the row spells); one with :recursion_depth may be recursed into up to that depth.
3. **execute_eql** — run ONE EQL query combining ALL desired attributes, regardless of entity. If something was unreachable, the refusal names the attribute and why — fix and retry once.

" query-format "

### Rules

- NEVER guess attribute names — discover them with list_entities then list_attributes
- Combine ALL attributes reachable from your starting record into ONE query — across every entity TYPE it connects to (the orders, the customer, the credit profile, the line items, …); never split one record's related data across calls. Only genuinely SEPARATE starting records — different identity values you're comparing — get one call each (see the format note above)
- A list whose rows carry columns from SEVERAL domains is fine and PREFERRED in ONE query: the engine BATCH-FOLDS the related fetches (one batched call per related source, not N+1 per row). Do NOT pre-split a cross-domain query to \"avoid N+1\" — batching is the engine's job.
- Entity identity values must match their types (strings for order numbers, integers for IDs)
- Invalid queries are rejected BEFORE execution with a structured finding naming the offending key — fix and retry once
- A refusal is an ANSWER, not an outage: it is structured (a code, a path, what was expected). Read it and change the query. Never retry the same shape hoping for a different result, and never present a narrower result as if it were the full one
- You see exactly one graph — the one your token grants. There is no broader view to fall back to, and \"I lack access to X\" is never the explanation for an empty result: an attribute either is in this graph's catalog or is refused by name"))



(def instructions
  "The MCP server instructions (the `initialize` response). Rules + tool-based
   discovery only — NO entity index."
  (str "# Open Atlas — Graph Query (MCP)

You are connected to one Open Atlas graph — a federated model that resolves
cross-domain business data through EQL. The graph your token grants is the
whole world for this session: everything in it is yours to read, and nothing
outside it exists.

" one-rule "\n\n" usage-template))

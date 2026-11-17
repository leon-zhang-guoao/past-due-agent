---
name: graph-authoring
description: The full procedure for building a graph and its knowledge over the Builder MCP, from a written brief (sources, business goals, the questions people ask) to a published graph that is shown to answer every question. Covers connecting sources, profiling the data, deciding what goes into the graph and what into knowledge articles, verifying, tuning without fitting to the test, and handing over. Use when building a new graph, extending one, or writing its knowledge articles.
---

# Building a graph and its knowledge

A graph is built once and used for years, so it is built in stages, each with a written result that a
person can check. This skill gives the stages, the judgement in each, and the points where a person
must decide.

How to call a tool or write a contract, a saved query or an article is in the Builder itself: read
`get_contract_guide` before the first draft, and each tool's description before using it. This skill
does not repeat them.

## What you need before you start

A **brief**, written by IT and the business owner together. Use
[brief-template.md](brief-template.md). It holds:

1. **Goal**: who will use the graph, for what decisions.
2. **Sources**: each database or API, what it holds, who owns it, how to reach it. No passwords.
3. **Questions**: the business questions the graph must answer, by department, in the users' own
   words. At least 20 for a real graph. For as many as possible, a **reference figure** from a report
   people already trust, with its period and scope.
4. **Definitions**: the formulas the business already uses (margin, on-time, active customer) and
   who owns each.
5. **Access rules**: who may see which rows and fields; what must never leave the source.
6. **Words**: the names users use for things, in each language they work in.
7. **Out of scope**: what this graph will not answer.

If the brief has no question list, stop and ask for one. A graph built without questions cannot be
checked. If a definition is missing, do not invent it: list it under open items (stage 4).

Keep a **working folder** for this build with four files, and update them as you go. They are the
hand-over:

- `findings.md`: what the data shows (stage 2)
- `coverage.md`: one row per question (stages 3 to 6)
- `open-items.md`: what a person must decide or supply
- `changes.md`: what was changed in each tuning round and why

## Stage 1: Connect the sources

- A person enters each credential value in Studio. Ask for the variable names you need; never ask
  for, print or store a secret.
- Declare each source as a plan first, read the plan, then apply. For an API, probe it first and
  keep one real response per endpoint in `findings.md` (with personal data removed).
- Use a read-only account. If the account can write, say so in `open-items.md`.
- Describe each source and check it against the brief: every table or endpoint the questions need
  is there, and you can read it.

**Result:** every source in the brief is connected and readable, or listed as blocked with the
reason.

## Stage 2: Profile the data

Look at the data before modelling it. For each table or endpoint the questions touch, record in
`findings.md`:

- **Grain and keys**: what one row is; the key; whether the key is really unique.
- **Range and gaps**: first and last date, per table and per channel; thin or empty months; whether
  the latest period is complete.
- **Codes and scales**: every status, flag and rating value with its meaning and count; which way a
  scale runs. Take meanings from column comments, code tables or the source owner, not from the
  name.
- **Joins**: for each relation, how many rows on each side have no match, and whether it multiplies
  rows.
- **Duplicates**: one real thing with several records (a customer entered twice, a product with
  versions).
- **Things that should match and do not**: header against lines, stock against movements, plan
  against actual, one source against another.
- **Fields that look useful and are not**: always zero, never filled, a default value.
- **Units, currency, tax, time zone**: per amount and date field.
- **Sensitive fields**: personal data, pay, prices under contract.

Write what the data shows. When the reason for a pattern is not recorded anywhere, write "not
recorded" and add a question for the source owner to `open-items.md`. Never guess a reason.

**Result:** `findings.md`. Every later decision cites it.

## Stage 3: Design against the questions

Fill `coverage.md`, one row per question in the brief:

| Question | Owner | Answered by | Needs to know | Reference figure | Status |
|---|---|---|---|---|---|
| the user's words | department | metric, saved query or entity path | article ids | from the brief, with period | planned / draft ok / published ok / cannot answer: reason |

Rules for the design:

- **Every question needs a path.** If no entity, metric or query can produce the figure, add one.
  An article cannot make up for a missing path.
- **A question the data cannot answer is marked so**, with the reason, and goes to `open-items.md`.
  Do not model a guess.
- Give metrics small summary entities (by month, by category), not detail rows.
- Build a ratio from two declared metrics, so an agent can say which part moved.
- A question asked often, or one with a trap in it (a distinct count, a date basis, a filter that is
  easy to forget), gets a saved query.
- Write names, descriptions and synonyms in the words from the brief, so an agent can match a
  question to them.
- Map only what the questions and the access rules need. A field nobody asks about and that is
  sensitive stays out.

### Put each piece of business knowledge in one place

Ask these in order and stop at the first yes:

1. **Can the system enforce or compute it?** A formula, a definition, who may see which rows, which
   writes are allowed, a query people ask often. → The graph: a metric, a term, row security, an
   operation, a saved query. Every agent gets the same number, and a mistake is fixed once.
2. **Is it a fact about this data that an agent must know to answer correctly?** A data boundary, a
   cost basis, a field that means something unexpected, figures that do not reconcile. → A knowledge
   article, linked to the entities, terms or metrics whose answers need it.
3. **Is it a way of working that holds for any data?** Report format, steps of a review, tone. → A
   skill, not the graph.

One piece of knowledge often splits: the gross margin formula is a metric, "cost is today's standard
cost, not the cost at the time" is an article, "show margin to one decimal" is a skill. When
something belongs in the graph but cannot be modelled yet, write it as an article for now and say so
in the article.

Common mistakes:

- A formula in an article or a skill: agents compute it differently.
- A data boundary in a skill: nothing ties it to the data, so it is not read when it is needed.
- Steps in an article: articles arrive with the answer, after the work is done.

**Stop here for a person.** Show `coverage.md` and `open-items.md` to the business owner before
building. They confirm the metric definitions, the questions marked "cannot answer", and the access
rules. This is the cheapest point to correct the design.

## Stage 4: Build the draft

1. Write the contract in small steps: sources and entities first, then relations, terms, metrics,
   row security, saved queries. Validate after each step and save only when it validates.
2. Every metric has an owner from the brief. A metric without a confirmed definition is not
   certified, and is listed in `open-items.md`.
3. Write the knowledge articles from `findings.md`. One fact per article, the fact in the title,
   linked narrowly to what it affects. Over the Builder an agent saves drafts only.
4. Read the quality view. Every entity and every attribute an agent may use has a description;
   fill what is missing.
5. Write the graph's usage guide: what the graph covers, its date range, and what it does not
   answer (from "out of scope" and the "cannot answer" rows).

**Result:** a saved draft that validates, with draft articles.

## Stage 5: Verify on the draft

For every row of `coverage.md`, run what an agent would run (the saved query, the metric, or the
entity path) on the draft, and read what comes back:

- **The figure**, against the reference figure. A difference is explained (scope, period,
  definition) or it is a defect. Never change a definition only to match a report; ask the owner
  which one is right.
- **The scope**: date range, filters, row security for the roles in the brief. Test one role that
  must not see a row, and confirm it does not.
- **The knowledge**: the right article is in `related_knowledge`, near the top, and no unrelated
  article is above it.
- **The join**: totals are not multiplied; a distinct count is distinct.

Where the brief has no reference figure, check the figure a second way (a different path through
the graph, or a count IT runs on the source), and mark the row "no reference" so a person knows.

Set each row to `draft ok` or back to a defect. Do not go on while a row is neither `draft ok` nor
`cannot answer` with a reason the owner accepted.

## Stage 6: Review, publish, confirm

1. **A person reviews** in Studio: reads each article and makes it active, confirms the certified
   metrics, checks row security. An article that is not made active is not published.
2. Check the cut: read the changes and the warnings. Fix warnings or record why each is accepted.
3. Publish, and check the version now serving.
4. **Confirm with a fresh agent that has only the data-plane endpoint**, not the Builder and not
   this conversation. It gets the questions in the users' words, nothing else. Run each question
   several times. This is the only test that shows what users will get: the Builder reads the draft,
   and an agent that built the graph already knows the answers.
5. Score each run: correct, wrong, refused or said "cannot answer" correctly, invented. Set the row
   to `published ok` only when every run is correct or correctly declined.

## Stage 7: Tune

For each failed run, read the transcript and find the cause before changing anything:

| What happened | Likely cause | Fix, and where |
|---|---|---|
| Took a wrong entity or metric | Descriptions or synonyms do not match the users' words; two things look alike | Descriptions, synonyms, terms (graph) |
| Right path, wrong figure | Definition, join, grain, missing filter | Metric or saved query (graph) |
| Right figure, wrong meaning | A data fact the agent did not know | Article, or its links and title if it existed but did not arrive |
| Answered what the data cannot support | The limit is not stated | Usage guide and an article on the boundary |
| Many turns, same result | No direct path for a common question | Saved query or summary entity (graph) |
| Different answers in different runs | The question has two readings | A term or metric that fixes the reading; ask the owner which |
| Wrong format, weak analysis | Not a graph matter | The agent's skill, not the graph |

Then:

- Fix the cause in general terms. **Before keeping a sentence, ask: would it still be true for
  another question, or another copy of this data?** If not, it is fitted to the test and will
  mislead the next question.
- Never copy a test question, its expected answer, its date window or its wording into an article,
  a description or a saved query.
- After each round, run **all** questions again, not only the failed ones, and compare with the
  previous round. A change that fixes one question and breaks another is not kept.
- Keep some questions aside that are never used for tuning, and run them at the end. If they score
  much lower than the tuned ones, the graph is fitted.
- Record each round in `changes.md`: what failed, the cause, the change, the scores before and
  after.
- Stop when two rounds bring no gain. What is left goes to `open-items.md` with its cause.

## Stage 8: Hand over

Give the business owner and IT:

- `coverage.md`: every question with its final status, the number of runs and the score.
- `open-items.md`: definitions not confirmed, questions the data cannot answer, source problems
  found, warnings accepted.
- `findings.md` and `changes.md`.
- What was **not** checked: roles not tested, sources not reached, periods with no data.
- How to keep it right: who reviews new articles, who owns each metric, and that the same question
  set is run again after every change to the graph or the source.

## When to stop and ask a person

- A credential, a token, or anything secret is needed.
- A definition is missing, or two sources of the definition disagree.
- A reference figure and the data disagree and the data gives no reason.
- A question needs a sensitive field the access rules do not mention.
- The source account can write, or a change would delete or replace something already published.
- Articles are ready for review; the graph is ready to publish for the first time.

## Done means

- Every question in the brief is `published ok`, or `cannot answer` with a reason the owner
  accepted.
- No invented answer in the last full run, including the questions kept aside.
- Every certified metric has an owner; every active article was read by a person.
- The hand-over files are complete and say what was not checked.

# Atlas result reader — 3.3.1

Read this short file completely BEFORE calling analyze_result. Tool metadata for
the deployed host remains authoritative. Examples are argument shapes, not tools
executed or validated by this release. Replace HANDLE_FROM_EXECUTE with the exact
handle returned by this run, never an example/remembered handle.

## 1. Choose ONE input selector

| Task | Include | Omit entirely |
|---|---|---|
| Read this run's existing result (normal path) | result_handle | query, result_handles |
| Direct query analysis (only if deliberately needed and supported) | query | result_handle, result_handles |
| Multiple-result analysis (only if deliberately needed and supported) | result_handles | query, result_handle |

Omitted means the property is absent. `""`, `[]` and `null` are NOT safe ways to
disable an unused selector. Do not fill every tool property or copy an expanded
default form. An error on one mode is not a reason to switch modes, reexecute EQL,
add `:query []`, invent handles or try multi-result analysis.

## 2. Inspect the retained result

When the tool schema permits omission of nonessential properties, submit:

```json
{"result_handle":"HANDLE_FROM_EXECUTE"}
```

This initial call may expose schema/columns only. It is NOT a completed data read.
Do not add empty query/result_handles/sql. Only add nonselector options if the
actual schema requires them; don't assume analysis_version/input_row_limit values
from another environment. A required/defaulted sql value must follow that schema,
but never add a second input selector to satisfy a tool form.

If schema requires BOTH query and result_handle (or all three selectors), it
conflicts with the observed runtime's exactly-one requirement. Do not send a
knowingly conflicting request or guess null semantics. Report the schema/adapter
incompatibility; Prompt/Skill cannot change required tool fields.

## 3. Read actual rows

After discovering actual column names, use result_handle PLUS a nonempty supported
read-only sql projection. sql transforms the selected retained input; it is NOT
one of the three input selectors. The shape is:

```json
{
  "result_handle":"HANDLE_FROM_EXECUTE",
  "sql":"SELECT <explicit discovered columns> FROM data ORDER BY <discovered stable order>"
}
```

The angle-bracket placeholders are NOT executable SQL. Construct the actual
projection from returned schema, preserving required identity, metrics and checks.
For monitor, use discovered Country ordering (it has no row-ordinal). Report uses
actual row-ordinal. See atlas-query-playbook.md, Read layer 2, for partition/page
projections; read that section completely before report materialization. If a file
read says more lines remain, don't assume you've read its examples or stopping rules.

Keep the same selector shape on ALL schema/row/page reads: no query or result_handles.
Verify source coverage, result rows and cell truncation separately. Schema receipt,
handle receipt and outer Completed/isError=false do not prove successful row reads.
Inspect nested error text, including `give exactly one of ...`.

## 4. One bounded correction, not three attempts

On an exactly-one error, compare the actual submitted keys against the table.
If conflicting keys were present, permit ONE correction on the SAME handle that
actually omits them. Don't add a query, change handle, repeat the original payload,
or reset the retry allowance by changing selector mode. Initial call + one corrected
retry is the limit for the same failed read; ordinary successful paging isn't retry.

If a selector-only request is refused by the schema, or the single corrected call
still fails, stop this read. Retain original request/error evidence and distinguish:
- Submitted conflicting selectors: invalid call arguments, not ClickHouse failure.
- Host requires/adds conflicting fields: suspected tool schema/adapter issue;
  only attribute injection if the actual outgoing request proves it.
- Selector-only request still fails: tool invocation incompatibility, root unconfirmed.

Don't re-run source EQL to repair argument validation or claim no data/zero values.
Preserve other independently read scopes. Report exact blocked stage and avoid
claiming source execution or the whole report has passed. No runtime scripts.

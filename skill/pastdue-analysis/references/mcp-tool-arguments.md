# MCP arguments — 3.3.6

Follow [result-reader.md](result-reader.md).

`execute_eql` is the query string plus `retain_result` true.

`analyze_result` is the object in result-reader.md: `result_handle`, the SQL for that result, and `analysis_version` 2. Monitor and report SQL are in that file. On that call only, a blank string or an empty list is removed before send. `sql` with the statement, `analysis_version`, false, and 0 stay.

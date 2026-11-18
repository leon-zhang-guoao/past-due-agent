#!/usr/bin/env python3
"""Read-only audit of a pasted Atlas tool trace; no business SQL execution.

Outputs counts and anomaly line numbers, not business rows or credentials.
This observes the export and cannot attribute mismatches to server vs host/UI.
"""
import argparse
import collections
import json
import re
from pathlib import Path


def unwrap(value):
    if isinstance(value, str):
        try:
            return unwrap(json.loads(value))
        except (ValueError, TypeError):
            return value
    if isinstance(value, list) and value and all(
            isinstance(v, dict) and v.get('type') == 'text' for v in value):
        values = [unwrap(v['text']) for v in value]
        return values[0] if len(values) == 1 else values
    if isinstance(value, dict) and 'content' in value:
        return unwrap(value['content'])
    return value


def audit(path):
    lines = path.read_text().splitlines()
    calls = []
    for index, line in enumerate(lines):
        match = re.match(r'mcp__open_atlas_pastdue__(\w+) .*Completed', line)
        if match:
            calls.append(dict(line=index + 1, tool=match[1],
                              args=json.loads(lines[index + 1]),
                              result=unwrap(json.loads(lines[index + 2]))))
    counts = collections.Counter(c['tool'] for c in calls)
    executes = [c for c in calls if c['tool'] == 'execute_eql']
    reads = [c for c in calls if c['tool'] == 'analyze_result']
    successful_reads = [c for c in reads if isinstance(c['result'], dict)
                        and 'rows' in c['result']]
    groups = collections.defaultdict(list)
    for call in executes:
        groups[json.dumps(call['args'], sort_keys=True)].append(call['line'])
    handles = {c['result']['result_handle'] for c in executes
               if isinstance(c['result'], dict) and 'result_handle' in c['result']}
    report_inputs = []
    for c in executes:
        if ':pastdue.report-v2-query/' in c['args']['query']:
            report_inputs.append(dict(line=c['line'], **dict(re.findall(
                r'/(country-code|selection-mode|page-after-ordinal|page-size)\s+("[^"]*"|\d+)',
                c['args']['query']))))
    return dict(
        atlas_calls=sum(counts.values()), counts=dict(counts),
        all_completed_tool_entries=sum('Completed' in l and not l.startswith(('[', '{'))
                                       for l in lines),
        report_inputs=report_inputs,
        identical_source_request_line_groups=[v for v in groups.values() if len(v) > 1],
        successful_retained_reads=len(successful_reads),
        truncated_read_lines=[c['line'] for c in successful_reads
                              if c['result'].get('truncation', {}).get('rows?')
                              or c['result'].get('truncation', {}).get('cells?')],
        memory_configuration_error_lines=[c['line'] for c in reads
                                           if 'memory_limit' in str(c['result'])
                                           and 'locked' in str(c['result'])],
        read_handle_response_mismatch_lines=[c['line'] for c in reads
                                             if isinstance(c['result'], dict)
                                             and c['result'].get('result_handle')
                                             != c['args'].get('result_handle')],
        read_handles_not_seen_in_execute_responses=len({c['args'].get('result_handle')
                                                       for c in reads} - handles),
        interpretation='Export anomalies only; no live-server attribution or report certification')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace', type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.trace), ensure_ascii=False, indent=2))

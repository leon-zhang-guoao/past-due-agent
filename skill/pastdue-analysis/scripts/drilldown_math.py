#!/usr/bin/env python3
"""Optional local arithmetic on Atlas facts; never connects to a data source.

JSON stdin: {"operation":"vendors", "rows":[...]}, or
{"operation":"eta", "vendor":{...}, "rows":[...], "complete":true}.
Use unqualified Atlas field names (hyphenated); daily latest-* may be supplied
in place of target-* and are copied, never recomputed. Context is one pair and
Country. Caller must establish retrieval completeness from Atlas metadata.
"""
import json
import math
import sys

PAIR = ('country-code', 'target-snapshot-date', 'previous-snapshot-date')
IDENTITY = PAIR + ('vendor-code',)


def target_fields(row):
    result = dict(row)
    for key, value in row.items():
        if key.startswith('latest-'):
            new = 'target-' + key[len('latest-'):]
            if new in result and result[new] != value:
                raise ValueError('conflicting daily and explicit-pair fields')
            result[new] = value
    return result


def identity(row, keys=IDENTITY):
    return tuple(row[key] for key in keys)


def finite(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError('expected finite numeric fact')
    return value


def count(value):
    if type(value) is not int or value < 0:
        raise ValueError('expected nonnegative integer count')
    return value


def close(a, b):
    if not math.isclose(finite(a), finite(b), rel_tol=1e-9, abs_tol=1e-9):
        raise ValueError('contribution reconciliation failed')


def select_vendors(rows):
    """Input must be a complete collection or a verified correctly ranked prefix.

    Positive share/coverage is not recalculated on a top-N subset.
    """
    rows = [target_fields(row) for row in rows]
    if len({identity(row, PAIR) for row in rows}) > 1:
        raise ValueError('mixed Country or snapshot pairs')
    if len({identity(row) for row in rows}) != len(rows):
        raise ValueError('duplicate Vendor')
    for row in rows:
        finite(row['delta-country-contribution'])
        if type(row['vendor-code']) is not int:
            raise ValueError('Vendor Code must be an integer')
    return sorted((r for r in rows if r['delta-country-contribution'] > 0),
                  key=lambda r: (-r['delta-country-contribution'], r['vendor-code']))[:10]


def totals(rows):
    previous = sum(r['previous-past-due-count'] for r in rows)
    target = sum(r['target-past-due-count'] for r in rows)
    pc = math.fsum(r['previous-country-contribution'] for r in rows)
    tc = math.fsum(r['target-country-contribution'] for r in rows)
    return {'previous-past-due-count': previous, 'target-past-due-count': target,
            'past-due-count-change': target - previous,
            'past-due-count-relative-change': (target - previous) / previous if previous else None,
            'previous-country-contribution': pc, 'target-country-contribution': tc,
            'delta-country-contribution': tc - pc}


def summarize_eta(vendor, rows, complete):
    """One dimension only. Refuse Other/totals on incomplete/inconsistent data."""
    if complete is not True:
        raise ValueError('incomplete collection: no Other or totals permitted')
    vendor = target_fields(vendor)
    rows = [target_fields(r) for r in rows]
    values = set()
    dimension = None
    for row in rows:
        if identity(row) != identity(vendor):
            raise ValueError('Country/Vendor/date mismatch')
        dims = [k for k in ('eta-code', 'eta-source') if k in row]
        if len(dims) != 1 or (dimension and dimension != dims[0]):
            raise ValueError('mixed or missing ETA dimension')
        dimension = dims[0]
        value = row[dimension]
        if not isinstance(value, str) or not value.strip() or value in values:
            raise ValueError('duplicate or unnormalized ETA value')
        values.add(value)
        previous = count(row['previous-past-due-count'])
        target = count(row['target-past-due-count'])
        delta = target - previous
        if row['past-due-count-change'] != delta:
            raise ValueError('row count delta mismatch')
        actual = row['past-due-count-relative-change']
        if previous:
            close(actual, delta / previous)
        elif actual is not None:
            raise ValueError('zero baseline requires null relative change')
        close(row['delta-country-contribution'], row['target-country-contribution'] - row['previous-country-contribution'])
    aggregate = totals(rows)
    for key in ('previous-past-due-count', 'target-past-due-count'):
        if aggregate[key] != count(vendor[key]):
            raise ValueError('count reconciliation failed')
    for key in ('previous-country-contribution', 'target-country-contribution', 'delta-country-contribution'):
        close(aggregate[key], vendor[key])
    ordered = sorted(rows, key=lambda r: (-abs(r['past-due-count-change']), -r['target-past-due-count'], r[dimension]))
    other = None
    if len(ordered) > 5:
        other = dict(totals(ordered[5:]), bucket_count=len(ordered) - 5)
    return {'top': ordered[:5], 'other': other, 'totals': aggregate,
            'increase_total': sum(max(r['past-due-count-change'], 0) for r in rows),
            'decrease_total': sum(min(r['past-due-count-change'], 0) for r in rows)}


def verify_sections(selected, sections):
    """Ensure a detailed section survives even when its dimension failed."""
    expected = [identity(target_fields(r)) for r in selected]
    actual = [identity(target_fields(r)) for r in sections]
    if expected != actual or len(set(actual)) != len(actual):
        raise ValueError('summary/detail Vendor identity or order mismatch')
    for row in sections:
        for key in ('metrics-status', 'eta-code-status', 'eta-source-status', 'interpretation-status', 'current-context-status'):
            if not row.get(key):
                raise ValueError('missing detailed section status: ' + key)


if __name__ == '__main__':
    try:
        request = json.load(sys.stdin)
        if request['operation'] == 'vendors':
            result = select_vendors(request['rows'])
        elif request['operation'] == 'eta':
            result = summarize_eta(request['vendor'], request['rows'], request['complete'])
        else:
            raise ValueError('unsupported operation')
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    except (KeyError, TypeError, ValueError) as exc:
        print(json.dumps({'error': str(exc)}))
        sys.exit(1)

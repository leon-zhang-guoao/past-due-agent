"""Development-only reference validator for integration fixtures and regressions.

Not invoked by the deployed Agent. The Skill/SQL ship the same contract; this
module tests it offline and never supplies invented production facts.
"""
from collections import Counter,defaultdict
from datetime import date,timedelta
from decimal import Decimal


# These fields are deliberately stricter than the fields needed for a single
# arithmetic assertion.  A result which cannot be tied to this scope must not
# be used to complete a report, even when its totals happen to match.
_SCOPE_FIELDS = (
    'country_code',
    'vendor_code',
    'dimension',
    'target_snapshot_date',
    'previous_snapshot_date',
    'calendar_mode',
    'selection_scope',
    'metric_version',
    'environment_version',
)
_REQUIRED_SCOPE_FIELDS = _SCOPE_FIELDS[:-1]
_FACT_META_FIELDS = {
    'request_id', 'response_id', 'query_id', 'handle', 'result_handle',
    'page', 'page_number', 'retrieved_at', 'received_at', 'transport',
}
_COUNT_ALIASES = {
    'previous': ('previous_past_due_count', 'previous_total', 'previous_count'),
    'target': ('target_past_due_count', 'target_total', 'target_count'),
}


def _flatten_result(item):
    """Yield flat fact rows from either a fact list or a single fact.

    The deployed Agent consumes Atlas responses, while these development
    checks also accept a compact list of normalized facts.  Supporting both
    shapes keeps the validator useful without making either response shape an
    implicit production fallback.
    """
    if not isinstance(item, dict):
        return
    inherited = {}
    scope = item.get('scope')
    if isinstance(scope, dict):
        inherited.update(scope)
    for field in _SCOPE_FIELDS:
        if field in item:
            inherited[field] = item[field]
    facts = item.get('facts')
    if isinstance(facts, list):
        for fact in facts:
            if isinstance(fact, dict):
                row = dict(inherited)
                row.update(fact)
                yield row
    else:
        yield dict(item)


def _value(row, name):
    """Read a normalized field, accepting the common Atlas aliases."""
    if name in row:
        return row[name]
    aliases = {
        'selection_scope': ('filter_scope', 'selection_filter_scope'),
        'metric_version': ('metric_contract_version',),
        'environment_version': ('graph_version', 'environment', 'atlas_version'),
    }
    for alias in aliases.get(name, ()):
        if alias in row:
            return row[alias]
    return None


def _scope(row):
    """Return (scope key, missing required fields) for a normalized fact."""
    values = {field: _value(row, field) for field in _SCOPE_FIELDS}
    missing = [field for field in _REQUIRED_SCOPE_FIELDS
               if values[field] is None or values[field] == '']
    # Environment/graph version is useful for comparing results, but may be
    # absent on older MCP responses; it is therefore not a required field.
    return tuple(values[field] for field in _SCOPE_FIELDS), missing


def _period_counts(row):
    values = {}
    for period, aliases in _COUNT_ALIASES.items():
        values[period] = next((row[a] for a in aliases if a in row), None)
    return values


def _payload_signature(row):
    """Canonical semantic payload used to identify an identical duplicate."""
    return tuple(sorted((str(k), repr(v)) for k, v in row.items()
                        if k not in _FACT_META_FIELDS and k != 'scope'))


def detect_cross_request_conflicts(results):
    """Detect contradictory facts returned by multiple Atlas requests.

    Results are grouped by the complete request scope plus ETA value.  Exact
    duplicate facts are deduplicated.  A complete-scope group with different
    period counts (or a non-identical payload) is quarantined in its entirety;
    the validator never selects first/latest/largest and never reassigns the
    fact to another Vendor.  Incomplete scope metadata is retained only as
    unverified evidence and cannot support COMPLETE.

    Return keys are intentionally structured for report assembly:
    ``usable_facts``, ``conflicts``, ``duplicates``, ``unverified`` and an
    overall ``status`` of PASS, PARTIAL or FAIL.
    """
    rows = [row for item in (results or []) for row in _flatten_result(item)]
    complete_groups = defaultdict(list)
    incomplete = []
    for row in rows:
        scope_key, missing = _scope(row)
        eta = row.get('eta_value', row.get('value'))
        if missing or eta is None:
            incomplete.append({'fact': row, 'missing_scope_fields': missing or ['eta_value']})
            continue
        complete_groups[scope_key + (eta,)].append(row)

    usable = []
    duplicates = []
    conflicts = []
    for identity, group in complete_groups.items():
        unique = {}
        for row in group:
            unique.setdefault(_payload_signature(row), row)
        if len(unique) == 1:
            usable.append(next(iter(unique.values())))
            if len(group) > 1:
                duplicates.append({
                    'status': 'DUPLICATE_IDENTICAL_FACT',
                    'identity': identity,
                    'count': len(group),
                })
            continue

        counts = {
            (_period_counts(row)['previous'], _period_counts(row)['target'])
            for row in group
        }
        # Any period-count disagreement is the hard case called out by the
        # contract.  A non-identical payload with equal counts is also
        # quarantined: it is not safe to decide which representation wins.
        conflicts.append({
            'status': 'CONFLICTING_FACTS',
            'identity': identity,
            'facts': group,
            'period_count_variants': sorted(repr(x) for x in counts),
        })

    status = 'FAIL' if conflicts else ('PARTIAL' if incomplete else 'PASS')
    return {
        'status': status,
        'usable_facts': usable,
        'conflicts': conflicts,
        'duplicates': duplicates,
        'unverified': incomplete,
    }


def previous_weekday(d):
    d-=timedelta(days=1)
    while d.weekday()>4:d-=timedelta(days=1)
    return d


def select_pair(dates,today,calendar='WEEKDAYS'):
    dates=sorted({d for d in dates if d<today and (calendar=='CALENDAR_DAYS' or d.weekday()<5)})
    if len(dates)<2:raise ValueError('INSUFFICIENT')
    return dates[-1],dates[-2]


def country_status(pd_t,open_t,pd_p,open_p,threshold=Decimal('.10')):
    if min(pd_t,open_t,pd_p,open_p)<0 or pd_t>open_t or pd_p>open_p:return 'NOT_EVALUABLE','DATA_ERROR'
    if not open_t or not open_p:return 'NOT_EVALUABLE','OK'
    if not pd_p:return ('ZERO_BASELINE_INCREASE' if pd_t else 'NORMAL'),'OK'
    return ('TRIGGERED' if Decimal(pd_t*open_p-pd_p*open_t)>=threshold*pd_p*open_t else 'NORMAL'),'OK'


def validate_dimension(vendors,rows,checks,dimension):
    """Reject missing/duplicate/foreign identities even when totals happen to match."""
    errors=[];vs={tuple(v[k] for k in ('country_code','vendor_code','target_snapshot_date','previous_snapshot_date','calendar_mode')):v for v in vendors}
    grouped=defaultdict(list);seen=set();ordinals=set()
    for row in rows:
        key=tuple(row[k] for k in ('country_code','vendor_code','target_snapshot_date','previous_snapshot_date','calendar_mode'))
        identity=key+(row['eta_value'],)
        if key not in vs:errors.append('IDENTITY_MISMATCH')
        if identity in seen or row['row_ordinal'] in ordinals:errors.append('DUPLICATE')
        seen.add(identity);ordinals.add(row['row_ordinal']);grouped[key].append(row)
    ck={}
    for c in checks:
        if c['dimension']!=dimension:continue
        key=tuple(c[k] for k in ('country_code','vendor_code','target_snapshot_date','previous_snapshot_date','calendar_mode'))
        if key in ck:errors.append('DUPLICATE_CHECK')
        ck[key]=c
    if set(ck)!=set(vs):errors.append('CHECK_SET_MISMATCH')
    for key,v in vs.items():
        if key not in ck:continue
        c=ck[key];rs=grouped[key]
        if len(rs)!=c['expected_bucket_count']:errors.append('INCOMPLETE')
        if any(r['expected_bucket_count']!=c['expected_bucket_count'] for r in rs):errors.append('COUNT_METADATA_DRIFT')
        p=sum(r['previous_past_due_count'] for r in rs);t=sum(r['target_past_due_count'] for r in rs)
        if (p,t)!=(c['previous_total'],c['target_total']):errors.append('CHECK_TOTAL_MISMATCH')
        if dimension!='us' and (p,t)!=(v['previous_past_due_count'],v['target_past_due_count']):errors.append('VENDOR_TOTAL_MISMATCH')
        if any(r['past_due_count_change']!=r['target_past_due_count']-r['previous_past_due_count'] for r in rs):errors.append('DELTA_MISMATCH')
        if not c['counts_match']:errors.append('SOURCE_RECONCILIATION_FAILED')
    return errors


def us_rule(source):
    source=(source or '').strip()
    if not source or source.upper()=='UNKNOWN':return 'UNKNOWN_SOURCE'
    return 'ETA_EXPIRED' if source.upper()=='AUTOUS' else 'ROUGH_DATE'


def summarize(rows):
    rows=sorted(rows,key=lambda r:(-abs(r['target']-r['previous']),-r['target'],r['value']))
    other=rows[5:];p=sum(r['previous'] for r in other);t=sum(r['target'] for r in other)
    return {'top':rows[:5],'other':{'previous':p,'target':t,'delta':t-p,'relative':Decimal(t-p)/p if p else None},'increase':sum(max(r['target']-r['previous'],0) for r in rows),'decrease':sum(min(r['target']-r['previous'],0) for r in rows)}

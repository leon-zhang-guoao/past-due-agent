"""Development-only reference validator for integration fixtures and regressions.

Not invoked by the deployed Agent. The Skill/SQL ship the same contract; this
module tests it offline and never supplies invented production facts.
"""
from collections import Counter,defaultdict
from datetime import date,timedelta
from decimal import Decimal
import json


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
    item = normalize_row(item)
    inherited = {}
    scope = item.get('scope')
    if isinstance(scope, dict):
        inherited.update(normalize_row(scope))
    for field in _SCOPE_FIELDS + ('selection_mode', 'filter_scope', 'selection_filter_scope',
                                    'metric_contract_version', 'graph_version', 'environment',
                                    'atlas_version', 'result_handle', 'evidence_handle', 'handle'):
        if field in item:
            inherited[field] = item[field]
    facts = item.get('facts')
    if isinstance(facts, list):
        for fact in facts:
            if isinstance(fact, dict):
                row = dict(inherited)
                row.update(normalize_row(fact))
                yield row
    else:
        yield dict(inherited, **{k:v for k,v in item.items() if k != 'scope'})


def _value(row, name):
    """Read a normalized field, accepting the common Atlas aliases."""
    if name in row:
        return row[name]
    aliases = {
        'selection_scope': ('filter_scope', 'selection_filter_scope', 'selection_mode'),
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
    # Missing environment version cannot be invented. Handle association
    # validation separately marks absent raw platform versions UNVERIFIED.
    return tuple(values[field] for field in _SCOPE_FIELDS), missing


def _period_counts(row):
    values = {}
    for period, aliases in _COUNT_ALIASES.items():
        values[period] = next((row[a] for a in aliases if a in row), None)
    return values


# Only common semantic fields can contradict one another. Pagination, transport,
# sparse projections and display order are not business facts.
_NUMERIC_FIELDS = {
    'previous_past_due_count', 'target_past_due_count', 'past_due_count_change',
    'previous_open_count', 'target_open_count', 'previous_total', 'target_total',
    'previous_expected_total', 'target_expected_total', 'increase_total',
    'decrease_total', 'net_change', 'contribution_rank', 'expected_bucket_count',
    'selected_vendor_count', 'delta_country_contribution', 'previous_vendor_ratio',
    'target_vendor_ratio', 'previous_country_contribution', 'target_country_contribution',
}
_SEMANTIC_FIELDS = _NUMERIC_FIELDS | {'counts_match', 'required'}


def normalize_row(row):
    """Accept flat SQL aliases or namespaced Atlas attributes, never infer identity."""
    aliases = {'env/version': 'environment_version', 'analysis/version': 'analysis_version',
               'graph/version': 'environment_version'}
    return {aliases.get(str(k).lstrip(':'), str(k).lstrip(':').split('/')[-1].replace('-', '_')): v
            for k, v in row.items()}


def _canonical(value):
    if isinstance(value, dict):
        return tuple(sorted((str(k), _canonical(v)) for k, v in value.items()))
    if isinstance(value, (list, tuple)):
        return tuple(_canonical(v) for v in value)
    if isinstance(value, bool) or value is None:
        return value
    try:
        number = Decimal(str(value).strip())
        if number.is_finite():
            return number
    except (ValueError, ArithmeticError):
        pass
    return str(value).strip()


def _semantic(row):
    values = {k: _canonical(v) for k, v in row.items()
              if k in _SEMANTIC_FIELDS and v is not None}
    for period, aliases in _COUNT_ALIASES.items():
        for alias in aliases:
            if alias in row and row[alias] is not None:
                values[period + '_past_due_count'] = _canonical(row[alias])
                break
    return values


def detect_cross_request_conflicts(results):
    """Quarantine actual same-scope contradictions, not metadata/projection drift.

    At least two raw facts with overlapping, unequal semantic values are needed
    for CONFLICTING_FACTS. Missing scope stays PARTIAL; never guess a Vendor by
    matching totals. Independent dimensions remain usable after a conflict.
    """
    rows = [normalize_row(row) for item in (results or [])
            for row in _flatten_result(item)]
    groups = defaultdict(list)
    incomplete = []
    for row in rows:
        key, missing = _scope(row)
        eta = row.get('eta_value', row.get('value'))
        if missing or eta is None:
            incomplete.append({'fact': row, 'missing_scope_fields': missing or ['eta_value']})
        else:
            groups[tuple(_canonical(v) for v in key) + (_canonical(eta),)].append(row)
    usable, duplicates, conflicts = [], [], []
    for identity, group in groups.items():
        disagreements = set()
        for i, left in enumerate(group):
            a = _semantic(left)
            for right in group[i + 1:]:
                b = _semantic(right)
                disagreements.update(k for k in a.keys() & b.keys() if a[k] != b[k])
        if disagreements:
            conflicts.append({'status': 'CONFLICTING_FACTS', 'identity': identity,
                              'facts': group, 'fields': sorted(disagreements)})
            continue
        merged = {}
        for row in group:
            merged.update({k: v for k, v in row.items() if v is not None})
        usable.append(merged)
        signatures = [tuple(sorted(_semantic(r).items())) for r in group]
        if len(signatures) > len(set(signatures)):
            duplicates.append({'status': 'DUPLICATE_IDENTICAL_FACT',
                               'identity': identity, 'count': len(group)})
    return {'status': 'FAIL' if conflicts else ('PARTIAL' if incomplete else 'PASS'),
            'usable_facts': usable, 'conflicts': conflicts, 'duplicates': duplicates,
            'unverified': incomplete}


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


def us_rule(source, observed_in_joint=True):
    """Offline configured US classification; a dictionary is not presence evidence.

    Legacy fixture callers supply already-observed joint Source values. New
    callers must pass observed_in_joint=False when the joint row is unverified.
    """
    from pathlib import Path
    from edn_io import read
    rules = read(Path(__file__).resolve().parents[1] /
                 'skill/pastdue-analysis/references/eta-diagnostic-rules.edn')
    value = (source or '').strip().upper()
    if not observed_in_joint or not value or value == 'UNKNOWN':
        return 'UNKNOWN_SOURCE'
    automatic = {str(x).strip().upper() for x in
                 [rules[':autous-canonical']] + rules.get(':autous-aliases', [])}
    if value in automatic:
        return 'ETA_EXPIRED'
    recognized = {x.strip().upper() for x in
                  rules[':source-classification'][':recognized-other-sources']}
    return 'ROUGH_DATE' if value in recognized else 'UNKNOWN_SOURCE'


def summarize(rows):
    rows=sorted(rows,key=lambda r:(-abs(r['target']-r['previous']),-r['target'],r['value']))
    other=rows[5:];p=sum(r['previous'] for r in other);t=sum(r['target'] for r in other)
    return {'top':rows[:5],'other':{'previous':p,'target':t,'delta':t-p,'relative':Decimal(t-p)/p if p else None},'increase':sum(max(r['target']-r['previous'],0) for r in rows),'decrease':sum(min(r['target']-r['previous'],0) for r in rows)}


# Offline artifact schema. These helpers are intentionally not runtime gates.
REPORT_MODULES = ('run_info', 'country_metrics', 'vendor_summary', 'vendor_metrics', 'eta_code_changes',
                  'eta_source_changes', 'us_source_changes', 'business_diagnosis',
                  'country_summary', 'cross_country_summary', 'completion')
_RECORD_DIMENSIONS = {'ETA_CODE_SUMMARY': 'code', 'ETA_SOURCE_SUMMARY': 'source',
                      'US_SOURCE_SUMMARY': 'us'}
_ID_FIELDS = ('country_code', 'vendor_code', 'target_snapshot_date',
              'previous_snapshot_date', 'calendar_mode')


def _identity(row, fields=_ID_FIELDS):
    return tuple(_canonical(row.get(k)) for k in fields)


def _result(errors, missing, **extra):
    return dict(status='FAIL' if errors else ('PARTIAL' if missing else 'PASS'),
                errors=sorted(set(errors)), missing=sorted(set(missing)), **extra)


def validate_evidence_handles(handles, references, now):
    """Validate recorded handle associations at capture time, not wall-clock now.

    ``now`` is an explicit epoch-ms observation time. Historical fixtures must
    not become expired simply because this offline test runs later.
    """
    errors, missing = [], []
    registry = {}
    for h in handles:
        h = normalize_row(h)
        key = h.get('handle', h.get('result_handle'))
        if not key:
            missing.append('MISSING_HANDLE')
        elif key in registry:
            errors.append('DUPLICATE_HANDLE')
        else:
            registry[key] = h
    for ref in references:
        ref = normalize_row(ref)
        key = ref.get('handle', ref.get('evidence_handle'))
        h = registry.get(key)
        if h is None:
            missing.append('HANDLE_NOT_AVAILABLE')
            continue
        expiry = h.get('expires_at')
        if expiry is None or now is None:
            missing.append('HANDLE_LIFETIME_UNVERIFIED')
        elif _canonical(expiry) <= _canonical(now):
            errors.append('HANDLE_EXPIRED')
        for field in ('request_id', 'analysis_version', 'environment_version'):
            if h.get(field) is None or ref.get(field) is None:
                missing.append('HANDLE_' + field.upper() + '_UNVERIFIED')
            elif _canonical(h[field]) != _canonical(ref[field]):
                errors.append('HANDLE_' + field.upper() + '_MISMATCH')
        hs, rs = h.get('scope', {}), ref.get('scope', {})
        hs, rs = normalize_row(hs), normalize_row(rs)
        for field in _REQUIRED_SCOPE_FIELDS:
            a, b = _value(hs, field), _value(rs, field)
            if a is None or b is None:
                missing.append('HANDLE_SCOPE_UNVERIFIED')
            elif _canonical(a) != _canonical(b):
                errors.append('HANDLE_SCOPE_MISMATCH')
    return _result(errors, missing)


def validate_atlas_report_rows(rows, selected_vendors=None, expected_scope=None, monitor_facts=None, audit_grade="SERVER_RECONCILED"):
    """Validate v3.1 REPORT_FIELDS flat rows, including COUNTRY_CONTROL.

    SQL supplies top-five + exact OTHER, not expanded raw buckets. Reconcile
    received rows with checks AND independent Vendor counts (US with Code US).
    Expected record count includes controls. Coverage unknown is not a failure.
    """
    errors, missing = [], []
    if audit_grade not in ('SERVER_RECONCILED', 'CLIENT_RAW_AUDIT'):
        errors.append('AUDIT_GRADE_INVALID')
    rows = [normalize_row(r) for r in rows]
    integer_fields = {'expected_record_count','row_ordinal','display_rank','other_bucket_count',
                      'contribution_rank','expected_bucket_count','selected_vendor_count',
                      'vendor_code','previous_past_due_count','target_past_due_count',
                      'past_due_count_change','previous_total','target_total',
                      'previous_expected_total','target_expected_total'}
    for row in rows:
        for field in integer_fields & row.keys():
            value = row[field]
            if value is None:
                continue
            normalized = _canonical(value)
            if isinstance(value, bool) or not isinstance(normalized, Decimal) or normalized != normalized.to_integral_value():
                errors.append('INVALID_INTEGER_FIELD_' + field.upper())
        for field in ('counts_match','required'):
            if field in row and row[field] is not None and not isinstance(row[field], bool):
                errors.append('INVALID_BOOLEAN_FIELD_' + field.upper())
    if errors:
        return _result(errors, missing, dimensions={})
    groups = defaultdict(list)
    dimensions = {}
    expected_scope = normalize_row(expected_scope or {})
    scope_keys = {}
    for row in rows:
        key = row.get('scope_key')
        if not key:
            missing.append('SCOPE_KEY_MISSING')
        else:
            try:
                decoded = json.loads(key)
                if not isinstance(decoded, list) or len(decoded) != 7:
                    errors.append('SCOPE_KEY_INVALID')
                elif any(_canonical(row.get(field)) != _canonical(value) for field,value in
                         zip(('country_code','target_snapshot_date','previous_snapshot_date',
                              'calendar_mode','selection_mode'),decoded[:5])):
                    errors.append('SCOPE_KEY_ROW_MISMATCH')
            except (ValueError, TypeError):
                errors.append('SCOPE_KEY_INVALID')
            identity = _identity(row, ('country_code', 'target_snapshot_date',
                                      'previous_snapshot_date', 'calendar_mode'))
            if identity in scope_keys and scope_keys[identity] != key:
                errors.append('SCOPE_KEY_DRIFT')
            scope_keys[identity] = key
        if not row.get('metric_version'):
            missing.append('METRIC_VERSION_MISSING')
        elif row['metric_version'] != '3.1.0':
            errors.append('METRIC_VERSION_MISMATCH')
        for field, value in expected_scope.items():
            if row.get(field) is None:
                missing.append('REQUEST_SCOPE_UNVERIFIED')
            elif _canonical(row[field]) != _canonical(value):
                errors.append('REQUEST_SCOPE_MISMATCH')
    for row in rows:
        scope = _identity(row, ('country_code', 'target_snapshot_date',
                                'previous_snapshot_date', 'calendar_mode'))
        if any(x is None or x == '' for x in scope):
            missing.append('REPORT_SCOPE_MISSING')
        groups[scope].append(row)
    if not rows:
        missing.append('REPORT_ROWS_MISSING')
    all_vendors = []
    for scope, group in groups.items():
        controls = [r for r in group if r.get('record_type') == 'COUNTRY_CONTROL']
        if not controls:
            missing.append('COUNTRY_CONTROL_MISSING')
        if len(controls) > 1:
            errors.append('DUPLICATE_COUNTRY_CONTROL')
        expected = {_canonical(r.get('expected_record_count')) for r in group}
        if None in expected:
            missing.append('EXPECTED_RECORD_COUNT_MISSING')
        elif len(expected) != 1:
            errors.append('EXPECTED_RECORD_COUNT_DRIFT')
        else:
            count = next(iter(expected))
            if count < len(group):
                errors.append('EXTRA_REPORT_RECORDS')
            elif count > len(group):
                missing.append('REPORT_RECORDS_INCOMPLETE')
        ordinals = [_canonical(r.get('row_ordinal')) for r in group]
        if None in ordinals:
            missing.append('ROW_ORDINAL_MISSING')
        elif len(set(ordinals)) != len(ordinals):
            errors.append('DUPLICATE_ROW_ORDINAL')
        elif expected == {Decimal(len(group))} and set(ordinals) != set(range(1, len(group) + 1)):
            errors.append('ROW_ORDINAL_SET_MISMATCH')
        vendors = [r for r in group if r.get('record_type') == 'VENDOR_METRICS']
        all_vendors.extend(vendors)
        vmap = {_identity(v): v for v in vendors}
        if len(vmap) != len(vendors):
            errors.append('DUPLICATE_VENDOR')
        ranks = [_canonical(v.get('contribution_rank')) for v in vendors]
        if any(r is None for r in ranks):
            missing.append('VENDOR_RANK_MISSING')
        elif len(set(ranks)) != len(ranks) or any(r <= 0 for r in ranks):
            errors.append('VENDOR_RANK_SET_MISMATCH')
        elif any(v.get('selection_mode') in ('POSITIVE_TOP_N', 'POSITIVE_TOP_10') for v in vendors) and set(ranks) != set(range(1, len(vendors) + 1)):
            errors.append('VENDOR_RANK_SET_MISMATCH')
        for row in group:
            if row.get('selected_vendor_count') is None:
                missing.append('SELECTED_VENDOR_COUNT_MISSING')
            elif _canonical(row['selected_vendor_count']) != len(vendors):
                # Missing rows are incomplete, contradictory metadata is hard.
                if expected == {Decimal(len(group))}:
                    errors.append('SELECTED_VENDOR_COUNT_MISMATCH')
                else:
                    missing.append('SELECTED_VENDOR_SET_INCOMPLETE')
            state = row.get('scope_status')
            if state in ('DATA_ERROR', 'INVALID_SELECTION', 'INVALID_PAIR', 'INVALID_DATE'):
                errors.append('SCOPE_' + state)
            elif state not in ('OK', 'NO_POSITIVE_VENDORS'):
                missing.append('SCOPE_NOT_VERIFIED')
            kind = row.get('record_type')
            if kind not in {'COUNTRY_CONTROL', 'VENDOR_METRICS', 'VENDOR_CHECK'} | set(_RECORD_DIMENSIONS):
                errors.append('UNKNOWN_RECORD_TYPE')
            if kind not in ('COUNTRY_CONTROL', 'VENDOR_METRICS'):
                if _identity(row) not in vmap:
                    errors.append('FOREIGN_VENDOR_IDENTITY')
                elif _canonical(row.get('contribution_rank')) != _canonical(vmap[_identity(row)].get('contribution_rank')):
                    errors.append('CONTRIBUTION_RANK_MISMATCH')
        for key, vendor in vmap.items():
            for dim in ('code', 'source', 'us'):
                related = [r for r in group if _identity(r) == key and r.get('dimension') == dim]
                checks = [r for r in related if r.get('record_type') == 'VENDOR_CHECK']
                buckets = [r for r in related if r.get('record_type') in _RECORD_DIMENSIONS]
                de, dm = [], []
                if not checks:
                    dm.append('DIMENSION_CHECK_MISSING')
                if len(checks) > 1:
                    de.append('DUPLICATE_DIMENSION_CHECK')
                for b in buckets:
                    if _RECORD_DIMENSIONS[b['record_type']] != dim:
                        de.append('RECORD_DIMENSION_MISMATCH')
                if checks:
                    c = checks[0]
                    if c.get('counts_match') is not True:
                        de.append('SOURCE_RECONCILIATION_FAILED')
                    if c.get('required') is None:
                        dm.append('DIMENSION_REQUIRED_MISSING')
                    elif dim != 'us' and c['required'] is not True:
                        de.append('MANDATORY_DIMENSION_NOT_REQUIRED')
                    elif dim == 'us' and c['required'] is False:
                        if any(_canonical(c.get(period + '_expected_total', 0)) != 0
                               for period in ('previous', 'target')):
                            de.append('NONZERO_US_NOT_REQUIRED')
                    elif dim == 'us' and c['required'] is True and all(
                            _canonical(c.get(period + '_expected_total')) == 0
                            for period in ('previous', 'target')):
                        de.append('ZERO_US_REQUIRED')
                    expected_buckets = _canonical(c.get('expected_bucket_count'))
                    if expected_buckets is None:
                        dm.append('EXPECTED_BUCKET_COUNT_MISSING')
                    else:
                        represented = sum(_canonical(b.get('other_bucket_count', 0))
                                          if b.get('row_kind') == 'OTHER' else 1 for b in buckets)
                        if represented < expected_buckets:
                            dm.append('BUCKETS_INCOMPLETE')
                        elif represented > expected_buckets:
                            de.append('BUCKET_COUNT_MISMATCH')
                        if any(_canonical(b.get('expected_bucket_count')) != expected_buckets for b in buckets):
                            de.append('BUCKET_COUNT_METADATA_DRIFT')
                        if expected_buckets > 5:
                            if len(buckets) != 6 or sum(b.get('row_kind') == 'OTHER' for b in buckets) != 1:
                                dm.append('OTHER_MISSING')
                        elif any(b.get('row_kind') == 'OTHER' for b in buckets):
                            de.append('UNEXPECTED_OTHER')
                    display_ranks = [_canonical(b.get('display_rank')) for b in buckets]
                    if None in display_ranks:
                        dm.append('DISPLAY_RANK_MISSING')
                    elif len(set(display_ranks)) != len(display_ranks):
                        de.append('DUPLICATE_DISPLAY_RANK')
                    elif set(display_ranks) != set(range(1, len(buckets) + 1)):
                        de.append('DISPLAY_RANK_SET_MISMATCH')
                    if all(b.get('previous_past_due_count') is not None and b.get('target_past_due_count') is not None for b in buckets):
                        top = [b for b in buckets if b.get('row_kind') == 'BUCKET']
                        ordered = sorted(top, key=lambda b: (-abs(_canonical(b['target_past_due_count'])-_canonical(b['previous_past_due_count'])),
                                                           -_canonical(b['target_past_due_count']), str(b.get('eta_value'))))
                        if any(_canonical(b.get('display_rank')) != rank for rank,b in enumerate(ordered,1)):
                            de.append('BUCKET_DISPLAY_ORDER_MISMATCH')
                    bucket_keys = [(b.get('row_kind'), b.get('eta_value')) for b in buckets]
                    if len(set(bucket_keys)) != len(bucket_keys):
                        de.append('DUPLICATE_BUCKET')
                    for b in buckets:
                        if b.get('row_kind') not in ('BUCKET', 'OTHER'):
                            de.append('INVALID_ROW_KIND')
                        if b.get('row_kind') == 'OTHER' and b.get('eta_value') not in ('', None):
                            de.append('OTHER_LABEL_IS_NOT_FACT')
                        p, t = b.get('previous_past_due_count'), b.get('target_past_due_count')
                        if p is None or t is None:
                            dm.append('BUCKET_COUNTS_MISSING')
                        elif min(_canonical(p), _canonical(t)) < 0:
                            de.append('NEGATIVE_COUNT')
                        elif _canonical(b.get('past_due_count_change')) != _canonical(t) - _canonical(p):
                            de.append('BUCKET_DELTA_MISMATCH')
                    if buckets and all(b.get('previous_past_due_count') is not None and b.get('target_past_due_count') is not None for b in buckets):
                        for period in ('previous', 'target'):
                            total = sum(_canonical(b[period + '_past_due_count']) for b in buckets)
                            if c.get(period + '_total') is None:
                                dm.append('CHECK_TOTAL_MISSING')
                            elif total != _canonical(c[period + '_total']):
                                (dm if 'BUCKETS_INCOMPLETE' in dm else de).append('CHECK_TOTAL_MISMATCH')
                    for period in ('previous', 'target'):
                        total = c.get(period + '_total')
                        expected_total = c.get(period + '_expected_total')
                        if total is None or expected_total is None:
                            dm.append('EXPECTED_TOTAL_MISSING')
                        elif _canonical(total) != _canonical(expected_total):
                            de.append('EXPECTED_TOTAL_MISMATCH')
                        if dim != 'us':
                            authoritative = vendor.get(period + '_past_due_count')
                        else:
                            code_us = [b for b in group if _identity(b) == key and b.get('record_type') == 'ETA_CODE_SUMMARY' and b.get('eta_value') == 'US']
                            # A hidden US inside OTHER has server expected-total evidence.
                            authoritative = code_us[0].get(period + '_past_due_count') if code_us else (0 if not any(b.get('row_kind') == 'OTHER' for b in group if _identity(b) == key and b.get('dimension') == 'code') else None)
                        if authoritative is None:
                            # SQL expected totals are derived independently from
                            # raw Code US before compression. The default grade
                            # accepts this server evidence; raw client audit is
                            # an optional stricter grade, never a default query.
                            if dim != 'us' or audit_grade == 'CLIENT_RAW_AUDIT':
                                dm.append('INDEPENDENT_TOTAL_UNVERIFIED')
                        elif expected_total is not None and _canonical(expected_total) != _canonical(authoritative):
                            de.append('VENDOR_TOTAL_MISMATCH' if dim != 'us' else 'US_CODE_TOTAL_MISMATCH')
                outcome = _result(de, dm)
                dimensions[key + (dim,)] = outcome['status']
                errors.extend(de); missing.extend(dm)
    if monitor_facts is not None:
        monitors = {_identity(normalize_row(m), ('country_code','target_snapshot_date',
                    'previous_snapshot_date','calendar_mode')): normalize_row(m) for m in monitor_facts}
        for scope, group in groups.items():
            controls = [r for r in group if r.get('record_type') == 'COUNTRY_CONTROL']
            monitor = monitors.get(scope)
            if monitor is None:
                missing.append('MONITOR_FACT_MISSING')
                continue
            for control in controls:
                for period in ('previous', 'target'):
                    for report_field, monitor_field in [(period+'_country_past_due_count',period+'_past_due_count'),
                            (period+'_country_open',period+'_open_count'),
                            (period+'_country_ratio',period+'_past_due_ratio')]:
                        if control.get(report_field) is None or monitor.get(monitor_field) is None:
                            missing.append('MONITOR_COMPARISON_UNVERIFIED')
                        elif _canonical(control[report_field]) != _canonical(monitor[monitor_field]):
                            errors.append('MONITOR_REPORT_MISMATCH')
    if selected_vendors is not None:
        selected = [normalize_row(v) for v in selected_vendors]
        actual = {_identity(v): v for v in all_vendors}
        wanted = {_identity(v): v for v in selected}
        if actual.keys() - wanted.keys():
            errors.append('SELECTED_VENDOR_IDENTITY_MISMATCH')
        if wanted.keys() - actual.keys():
            missing.append('SELECTED_VENDORS_MISSING')
        for key in actual.keys() & wanted.keys():
            for field in ('contribution_rank', 'previous_past_due_count', 'target_past_due_count'):
                if wanted[key].get(field) is None or actual[key].get(field) is None:
                    missing.append('VENDOR_FACT_MISSING')
                elif _canonical(actual[key][field]) != _canonical(wanted[key][field]):
                    errors.append('REPORT_COPY_ERROR')
    return _result(errors, missing, dimensions=dimensions)


def validate_display_contract(contract):
    """Enforce EDN keyword types; Keyword inherits str so equality is insufficient."""
    from edn_io import Keyword
    errors = []
    modules = contract.get(':modules', {})
    compositions = contract.get(':compositions', {})
    for key in modules:
        if not isinstance(key, Keyword):
            errors.append('MODULE_KEY_NOT_EDN_KEYWORD')
    for key, sequence in compositions.items():
        if not isinstance(key, Keyword):
            errors.append('COMPOSITION_KEY_NOT_EDN_KEYWORD')
        for reference in sequence:
            if not isinstance(reference, Keyword):
                errors.append('MODULE_REFERENCE_NOT_EDN_KEYWORD')
            if reference not in modules:
                errors.append('UNRESOLVED_MODULE_REFERENCE')
    for reference in contract.get(':composition-rules', {}).get(':per-selected-vendor', []):
        if not isinstance(reference, Keyword):
            errors.append('MODULE_REFERENCE_NOT_EDN_KEYWORD')
        if reference not in modules:
            errors.append('UNRESOLVED_MODULE_REFERENCE')
    return _result(errors, [])


def validate_report_evidence(artifact, contract=None):
    """Check a parsed report or development fixture; NOT an Agent wire requirement.

    ``modules`` are ordered parsed sections; ``atlas_rows`` are optional flat
    report-query evidence. Actual prose needs a parser before this checker can
    make claims about it. A normalized fixture proves only validator behavior.
    """
    errors, missing = [], []
    if artifact.get('language') not in ('zh', 'en'):
        errors.append('REPORT_LANGUAGE_INVALID')
    mode = artifact.get('mode', 'daily_full')
    if contract is None:
        required = list(REPORT_MODULES)
        if mode == 'test_full':
            required.append('test_checks')
        elif mode == 'historical_events_only':
            required = ['trigger_events']
        elif mode == 'historical_full':
            required[required.index('country_metrics')] = 'trigger_events'
    else:
        errors.extend(validate_display_contract(contract)['errors'])
        compositions = contract.get(':compositions', contract.get('compositions', {}))
        required = [str(x).lstrip(':') for x in compositions.get(':' + mode, compositions.get(mode, []))]
        if not required:
            errors.append('UNKNOWN_REPORT_MODE')
    # Empty selected scope exercises an explicit applicable empty path, not
    # fabricated per-Vendor sections. Nonempty selections require all blocks.
    repeated_ids = {'vendor_metrics','eta_code_changes','eta_source_changes',
                    'us_source_changes','business_diagnosis'}
    if contract is not None and artifact.get('selected_vendors') == []:
        required = [x for x in required if x not in repeated_ids]
    modules = artifact.get('modules', [])
    ids = [m.get('id') for m in modules]
    repeat = ('vendor_metrics', 'eta_code_changes', 'eta_source_changes',
              'us_source_changes', 'business_diagnosis')
    module_keys = [(m.get('id'), m.get('country_code'), m.get('vendor_code')) for m in modules]
    if len(set(module_keys)) != len(module_keys):
        errors.append('DUPLICATE_REPORT_MODULE')
    if set(required) - set(ids):
        missing.append('MANDATORY_REPORT_MODULE_MISSING')
    # Compare the first-occurrence skeleton, then each repeated Vendor block.
    present = list(dict.fromkeys(x for x in ids if x in required))
    if present != [x for x in required if x in ids]:
        errors.append('REPORT_MODULE_ORDER')
    if contract is not None and mode not in ('historical_events_only','interactive'):
        selected = [normalize_row(v) for v in artifact.get('selected_vendors', [])]
        country_order = list(artifact.get('country_order', ['US','CA']))
        for v in selected:
            if v.get('country_code') not in country_order:
                country_order.append(v.get('country_code'))
        selected.sort(key=lambda v: (country_order.index(v.get('country_code')), _canonical(v.get('contribution_rank', 0)), _canonical(v.get('vendor_code', 0))))
        expected_blocks = [(v.get('country_code'), v.get('vendor_code'), module)
                           for v in selected for module in repeat]
        actual_blocks = [(m.get('country_code'), m.get('vendor_code'), m.get('id'))
                         for m in modules if m.get('id') in repeat]
        if set(expected_blocks) - set(actual_blocks):
            missing.append('VENDOR_MODULES_MISSING')
        if set(actual_blocks) - set(expected_blocks):
            errors.append('FOREIGN_VENDOR_MODULE')
        if actual_blocks != [x for x in expected_blocks if x in actual_blocks]:
            errors.append('VENDOR_MODULE_ORDER')
    allowed_states = {'PASS', 'PARTIAL', 'FAIL', 'NOT_REQUIRED', 'verified',
                      'failed', 'completeness-unverified', 'not-executed'}
    for module in modules:
        state = module.get('status')
        if state not in allowed_states:
            errors.append('MODULE_STATUS_INVALID')
        elif state in ('FAIL',):
            errors.append('MODULE_HARD_ERROR')
        elif state in ('PARTIAL', 'failed', 'completeness-unverified', 'not-executed'):
            missing.append('MODULE_NOT_VERIFIED')
        elif state == 'NOT_REQUIRED' and (module.get('required', True) or not module.get('reason')):
            errors.append('UNJUSTIFIED_NOT_REQUIRED')
        if module.get('id') == 'us_source_changes' and contract is not None:
            us_checks = [normalize_row(r) for r in artifact.get('atlas_rows', [])
                         if normalize_row(r).get('record_type') == 'VENDOR_CHECK'
                         and normalize_row(r).get('dimension') == 'us'
                         and normalize_row(r).get('vendor_code') == module.get('vendor_code')
                         and normalize_row(r).get('country_code') == module.get('country_code')]
            if not us_checks:
                missing.append('US_REQUIREMENT_UNVERIFIED')
            elif us_checks[0].get('required') is False:
                if state != 'NOT_REQUIRED' or not module.get('reason'):
                    errors.append('US_NOT_REQUIRED_NOT_DISCLOSED')
            elif state == 'NOT_REQUIRED':
                errors.append('REQUIRED_US_MODULE_SKIPPED')
        if module.get('language', artifact.get('language')) != artifact.get('language'):
            errors.append('MODULE_LANGUAGE_MISMATCH')
    dimensions = {}
    if 'atlas_rows' in artifact:
        result = validate_atlas_report_rows(artifact['atlas_rows'], artifact.get('selected_vendors'),
                                            artifact.get('expected_scope'), artifact.get('monitor_facts'),
                                            artifact.get('audit_grade', 'SERVER_RECONCILED'))
        errors.extend(result['errors']); missing.extend(result['missing'])
        dimensions.update(result['dimensions'])
    elif mode != 'historical_events_only':
        missing.append('ATLAS_REPORT_EVIDENCE_MISSING')
    if artifact.get('evidence_references'):
        result = validate_evidence_handles(artifact.get('evidence', []),
                                           artifact['evidence_references'],
                                           artifact.get('observed_at'))
        errors.extend(result['errors']); missing.extend(result['missing'])
    else:
        missing.append('EVIDENCE_ASSOCIATION_UNVERIFIED')
    raw = artifact.get('raw_facts', [])
    conflict = detect_cross_request_conflicts(raw)
    if conflict['conflicts']:
        errors.append('CONFLICTING_FACTS')
    if conflict['unverified']:
        missing.append('RAW_FACT_SCOPE_UNVERIFIED')
    # Copy claims are compared to referenced raw facts, never diagnosed as a
    # server conflict unless two independent raw facts actually disagree.
    facts = defaultdict(list)
    for item in raw:
        for row in _flatten_result(item):
            row = normalize_row(row)
            facts[_identity(row) + (row.get('dimension'), row.get('eta_value'))].append(row)
    for claim in artifact.get('claims', []):
        claim = normalize_row(claim)
        key = _identity(claim) + (claim.get('dimension'), claim.get('eta_value'))
        sources = facts.get(key, [])
        if not sources:
            missing.append('CLAIM_RAW_EVIDENCE_MISSING')
        elif claim.get('evidence_handle') and not any(
                source.get('evidence_handle', source.get('result_handle', source.get('handle'))) == claim['evidence_handle']
                for source in sources):
            errors.append('RAW_HANDLE_ASSOCIATION_MISMATCH')
        elif not any(all(_canonical(source.get(k)) == _canonical(v)
                         for k, v in _semantic(claim).items()) for source in sources):
            errors.append('REPORT_COPY_ERROR')
    if artifact.get('claimed_conflict') and not conflict['conflicts']:
        errors.append('UNSUPPORTED_CONFLICT_CLAIM')
    result = _result(errors, missing, dimensions=dimensions,
                     evidence_kind=artifact.get('evidence_kind', 'NORMALIZED_FIXTURE_ONLY'))
    claimed = artifact.get('status')
    if claimed == 'COMPLETE' and result['status'] != 'PASS':
        result['errors'].append('FALSE_COMPLETE_CLAIM'); result['status'] = 'FAIL'
    elif claimed == 'FAIL' and result['status'] == 'PASS':
        result['errors'].append('UNSUPPORTED_FAIL_CLAIM'); result['status'] = 'FAIL'
    return result

"""Synthetic flat v3.1 report fixture, not a captured Agent report."""
from copy import deepcopy
import json


def report_rows(bucket_count=2):
    scope = dict(country_code='US', vendor_code=3493,
                 target_snapshot_date='2026-10-23', previous_snapshot_date='2026-10-22',
                 calendar_mode='WEEKDAYS', metric_version='3.1.0',
                 selection_mode='POSITIVE_TOP_10', scope_status='OK',
                 selected_vendor_count=1, contribution_rank=1)
    scope['scope_key'] = json.dumps(['US','2026-10-23','2026-10-22','WEEKDAYS','POSITIVE_TOP_10',10,0],separators=(',',':'))
    rows = [dict(scope, record_type='COUNTRY_CONTROL', vendor_code=0, contribution_rank=0),
            dict(scope, record_type='VENDOR_METRICS', previous_past_due_count=275,
                 target_past_due_count=319, past_due_count_change=44)]
    for dim, record in [('code', 'ETA_CODE_SUMMARY'), ('source', 'ETA_SOURCE_SUMMARY'),
                        ('us', 'US_SOURCE_SUMMARY')]:
        count = 1 if dim == 'us' else bucket_count
        # US stays visible so the independent US total is checkable.
        raw = [('US' if dim == 'code' else 'AUTO US', 3, 2)]
        if dim != 'us':
            raw += [(('B' + str(i)), 1, 1) for i in range(count - 2)]
            raw += [('NR' if dim == 'code' else 'Vendor Report', 275 - 3 - (count-2), 319 - 2 - (count-2))]
        raw.sort(key=lambda r: (-abs(r[2]-r[1]), -r[2], r[0]))
        summary = [(value, p, t, 'BUCKET', rank, 0) for rank, (value, p, t) in enumerate(raw[:5], 1)]
        if len(raw) > 5:
            summary.append(('', sum(r[1] for r in raw[5:]), sum(r[2] for r in raw[5:]), 'OTHER', 6, len(raw)-5))
        for value,p,t,kind,rank,other in summary:
            rows.append(dict(scope, record_type=record, dimension=dim, eta_value=value,
                             previous_past_due_count=p, target_past_due_count=t,
                             past_due_count_change=t-p, expected_bucket_count=count,
                             row_kind=kind, display_rank=rank, other_bucket_count=other))
        p,t = (3,2) if dim == 'us' else (275,319)
        rows.append(dict(scope, record_type='VENDOR_CHECK', dimension=dim,
                         previous_total=p, target_total=t, previous_expected_total=p,
                         target_expected_total=t, expected_bucket_count=count,
                         counts_match=True, required=True))
    for i,row in enumerate(rows,1):
        row.update(row_ordinal=i, expected_record_count=len(rows))
    return rows


def artifact(bucket_count=2):
    rows = report_rows(bucket_count)
    scope = dict(country_code='US',vendor_code=3493, dimension='code',
                 target_snapshot_date='2026-10-23', previous_snapshot_date='2026-10-22',
                 calendar_mode='WEEKDAYS', selection_scope='POSITIVE_TOP_10',
                 metric_version='3.1.0', environment_version=9)
    reference = dict(handle='sanitized-h1', request_id='synthetic-q1',
                     analysis_version=2, environment_version=9, scope=scope)
    from analysis_validation import REPORT_MODULES
    return dict(schema_version='3.1.0', language='zh', mode='daily_full',
                evidence_kind='NORMALIZED_FIXTURE_ONLY',
                modules=[dict(id=x,status='PASS',rows=[]) for x in REPORT_MODULES],
                atlas_rows=rows, selected_vendors=[deepcopy(rows[1])],
                observed_at=1000, evidence=[dict(reference,expires_at=2000,coverage='unknown')],
                evidence_references=[reference])

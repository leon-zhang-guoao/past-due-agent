#!/usr/bin/env python3
"""Replay observed report transformations on exported retained rows (dev only).

Requires optional duckdb. Does not execute EQL, contact Atlas or certify handles.
Prints diagnostics, never the full trace/credentials. Captured data stays local.
"""
import argparse
import json
import re
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from audit_pasted_atlas_trace import unwrap


def parse_calls(path):
    lines = path.read_text().splitlines()
    calls = []
    for i, line in enumerate(lines):
        if re.match(r'mcp__open_atlas_pastdue__\w+ .*Completed', line):
            calls.append(dict(line=i+1, args=json.loads(lines[i+1]),
                              result=unwrap(json.loads(lines[i+2]))))
    return calls


def replay(path):
    import duckdb
    calls = parse_calls(path)
    raw = [c for c in calls if c['args'].get('sql','').startswith('SELECT record_type, vendor_code, vendor_name, dimension')
           and 'past_due_count_change' in c['args']['sql']]
    formatted = [c for c in calls if c['args'].get('sql','').startswith('WITH m AS')
                 and "THEN 'Other ('" in c['args']['sql']]
    assert raw and len(raw)==len(formatted), 'Missing original and formatted result pairs'
    findings = []
    for original, bad in zip(raw, formatted):
        r = original['result']
        assert original['args']['result_handle']==bad['args']['result_handle']
        assert r['truncation']==dict(zip(('rows?','cells?','rows-shown','rows-total'),
                                        (False,False,len(r['rows']),len(r['rows']))))
        with duckdb.connect() as db:
            columns = ','.join('"'+x['name']+'" '+x['type'] for x in r['column-types'])
            db.execute('CREATE TABLE data ('+columns+')')
            db.executemany('INSERT INTO data VALUES ('+','.join('?' for _ in r['columns'])+')',r['rows'])
            observed = db.execute(bad['args']['sql']).fetchall()
            recorded = bad['result']
            # Prove the exact erroneous presentation, not only equal row counts.
            ix = recorded['columns'].index('code_changes')
            assert [(x[1],x[ix],x[ix+1]) for x in observed] == [
                    (x[1],x[ix],x[ix+1]) for x in recorded['rows']]
            before_e, after_e = bad['args']['sql'].split('e AS (',1)
            before_e = before_e.replace('abs(net_change) DESC, target_total DESC',
                                        'abs(past_due_count_change) DESC, target_past_due_count DESC')
            text_ctes, final_select = after_e.split('SELECT m.contribution_rank',1)
            for wrong, right in [('previous_total','previous_past_due_count'),
                                 ('target_total','target_past_due_count'),
                                 ('net_change','past_due_count_change')]:
                text_ctes=text_ctes.replace(wrong,right)
            corrected_sql=before_e+'e AS ('+text_ctes+'SELECT m.contribution_rank'+final_select
            corrected=db.execute(corrected_sql).fetchall()
            source=[dict(zip(r['columns'],x)) for x in r['rows']]
            # Independent selection from source bucket quantities, not check totals.
            expected={}
            for vendor in {x['vendor_code'] for x in source if x['record_type']=='VENDOR_METRICS'}:
                for dim in ('code','source'):
                    buckets=[x for x in source if x['vendor_code']==vendor and x['dimension']==dim
                             and x['record_type'] in ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY')]
                    buckets.sort(key=lambda x:(-abs(x['past_due_count_change']),
                                               -x['target_past_due_count'],x['eta_value']))
                    expected[vendor,dim]='; '.join(
                        ('Other ('+str(x['other_bucket_count'])+' items)' if x['row_kind']=='OTHER' else x['eta_value'])
                        +' '+str(x['previous_past_due_count'])+'→'+str(x['target_past_due_count'])
                        +' ('+('+' if x['past_due_count_change']>0 else '')+str(x['past_due_count_change'])+')'
                        for x in buckets[:5])
            # Execute the SHIPPED repair on the same captured rows. Some identity
            # columns were omitted by this historical SELECT; retain them as
            # unknown instead of fabricating provenance for this local replay.
            projection_path=Path(__file__).resolve().parents[3]/'skill/pastdue-analysis/references/report-eta-read.sql'
            for missing in ('scope_key','country_code','previous_snapshot_date',
                            'target_snapshot_date','calendar_mode','selection_mode',
                            'metric_version','scope_status','selected_vendor_count',
                            'past_due_count_relative_change','expected_record_count'):
                if missing not in r['columns']:
                    db.execute('ALTER TABLE data ADD COLUMN "'+missing+'" VARCHAR')
            view=db.execute(projection_path.read_text())
            names=[c[0] for c in view.description]
            typed=[dict(zip(names,row)) for row in view.fetchall()]
            assert len(typed)==len(source)-sum(x['record_type']=='VENDOR_METRICS' for x in source)
            for row in typed:
                original_row=next(x for x in source if x['row_ordinal']==row['row_ordinal'])
                if row['record_type']=='VENDOR_CHECK':
                    for field in ('previous_total','target_total','net_change','counts_match','required'):
                        assert row[field]==original_row[field]
                    assert row['previous_past_due_count'] is None
                else:
                    for field in ('previous_past_due_count','target_past_due_count','past_due_count_change'):
                        assert row[field]==original_row[field]
                    assert row['net_change'] is None and row['previous_total'] is None
            # Every check's period and delta totals still reconcile to buckets.
            for check in (x for x in typed if x['record_type']=='VENDOR_CHECK'):
                buckets=[x for x in typed if x['record_type']!='VENDOR_CHECK'
                         and (x['vendor_code'],x['dimension'])==(check['vendor_code'],check['dimension'])]
                for bucket_field,total_field in (('previous_past_due_count','previous_total'),
                                                ('target_past_due_count','target_total'),
                                                ('past_due_count_change','net_change')):
                    assert sum(x[bucket_field] for x in buckets)==check[total_field]
            for (vendor,dim),text in expected.items():
                buckets=[x for x in typed if x['vendor_code']==vendor and x['dimension']==dim
                         and x['record_type'] in ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY')]
                buckets.sort(key=lambda x:(-abs(x['past_due_count_change']),
                                           -x['target_past_due_count'],x['eta_value']))
                typed_text='; '.join(
                    ('Other ('+str(x['other_bucket_count'])+' items)' if x['row_kind']=='OTHER' else x['eta_value'])
                    +' '+str(x['previous_past_due_count'])+'→'+str(x['target_past_due_count'])
                    +' ('+('+' if x['past_due_count_change']>0 else '')+str(x['past_due_count_change'])+')'
                    for x in buckets[:5])
                assert typed_text==text
            wrong_cells=0
            for old,new in zip(observed,corrected):
                assert old[:ix]==new[:ix] # Vendor metrics unchanged.
                assert old[ix+2]==new[ix+2] # Signed checks unchanged.
                for offset,dim in enumerate(('code','source')):
                    assert new[ix+offset]==expected[new[1],dim]
                    wrong_cells+=old[ix+offset]!=new[ix+offset]
            # Original earlier variant used uppercase dimension enums.
            earlier=next(c for c in calls if c['args'].get('result_handle')==original['args']['result_handle']
                         and c['args'].get('sql','').startswith('WITH m AS')
                         and "e1.dimension='CODE'" in c['args']['sql'])
            uppercase=db.execute(earlier['args']['sql']).fetchall()
            assert all(x[ix] is None and x[ix+1] is None for x in uppercase)
            findings.append(dict(raw_line=original['line'],bad_transform_line=bad['line'],
                raw_rows=len(r['rows']),vendors=len(corrected),wrong_main_cells=wrong_cells,
                exact_bad_sql_reproduced=True,uppercase_join_reproduced_null=True,
                corrected_main_cells_match_bucket_fields=True,
                shipped_typed_projection_verified=True,
                check_period_and_delta_sums_verified=True,
                metrics_and_signed_checks_unchanged=True,
                example=dict(vendor=corrected[0][1],incorrect_code=observed[0][ix],correct_code=corrected[0][ix])))
    return dict(kind='EXPORTED_DATA_REPLAY_NOT_LIVE_ATLAS',results=findings,
                total_wrong_main_cells=sum(x['wrong_main_cells'] for x in findings))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace',type=Path)
    parser.add_argument('--json',type=Path)
    args=parser.parse_args()
    result=replay(args.trace)
    output=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.json: args.json.write_text(output)
    print(output,end='')

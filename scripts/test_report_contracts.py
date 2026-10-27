#!/usr/bin/env python3
"""Offline report/evidence checks. Does not simulate a real language model."""
import unittest
from edn_io import read
from pathlib import Path

R=Path(__file__).resolve().parents[1]


class Reports(unittest.TestCase):
    def test_selected_report_set_exact(self):
        selected=[('CA',v) for v in range(1,11)]
        def complete(report):return [x['identity'] for x in report]==selected and all(x['code'] in ('verified','failed') and x['source'] in ('verified','failed') for x in report)
        rows=[dict(identity=k,code='verified',source='verified') for k in selected]
        self.assertTrue(complete(rows));self.assertFalse(complete(rows[:2]))
        rows[5]['source']='not-executed';self.assertFalse(complete(rows))
        rows[5]['source']='failed';self.assertTrue(complete(rows)) # accounted for, still PARTIAL not verified complete
    def test_rules(self):
        d=read(R/'skill/pastdue-analysis/references/eta-diagnostic-rules.edn')
        self.assertEqual({x[':code'] for x in d[':rules']},{'DD','SH','SC','US','NR','RQ'})
        self.assertTrue(d[':requires-joint-facts-for-us'])
        self.assertEqual({x.get(':source-condition') for x in d[':rules'] if x[':code']=='US'},{'AUTOUS','OTHER_KNOWN','UNKNOWN'})
    def test_modes_and_caps(self):
        d=read(R/'skill/pastdue-analysis/references/policy.edn')
        self.assertTrue(d[':test-mode'][':force-drilldown']);self.assertTrue(d[':test-mode'][':preserve-real-business-status'])
        self.assertFalse(d[':notification-routing'][':send-enabled'])
        self.assertEqual(d[':po-line-limits'][':maximum-per-country'],20)
        self.assertFalse(d[':execution'][':host-hard-gate-installed'])
    def test_trace_regression(self):
        import json
        d=json.loads((R/'tests/fixtures/latest-trace-regression.json').read_text())
        self.assertLess(d['eta_requests_observed'],d['expected_vendor_dimensions'])
        self.assertEqual(len({r['vendor'] for r in d['requests']}),1)
        self.assertNotEqual(d['requests'][0]['target_total'],d['requests'][2]['target_total'])
        self.assertEqual(d['conflict_classification'],'CONFLICTING_FACTS')
        self.assertEqual(d['expected_final_status'],'FAIL')
        self.assertIn('reassign_to_vendor_31420',d['prohibited_actions'])
        self.assertIn('sum_conflicting_results',d['prohibited_actions'])




# All fixtures below are development-only; no LLM or production calls.
import copy
import json
import sys
sys.path.insert(0, str(R / 'tests'))
from report_fixtures import artifact, report_rows
from analysis_validation import (validate_atlas_report_rows, validate_report_evidence,
                                 validate_evidence_handles, detect_cross_request_conflicts,
                                 normalize_row, us_rule, validate_display_contract)


class EvidenceReports(unittest.TestCase):
    def test_complete_flat_report_and_both_languages(self):
        for language in ('zh', 'en'):
            a = artifact(); a['language'] = language
            self.assertEqual(validate_report_evidence(a)['status'], 'PASS')

    def test_atlas_namespaced_fields(self):
        rows = [{':pastdue.report-fact/' + k.replace('_','-'): v for k,v in row.items()}
                for row in report_rows()]
        self.assertEqual(validate_atlas_report_rows(rows)['status'], 'PASS')

    def test_exact_other(self):
        self.assertEqual(validate_atlas_report_rows(report_rows(8))['status'], 'PASS')
        rows=report_rows(8)
        next(r for r in rows if r.get('row_kind')=='OTHER')['other_bucket_count'] += 1
        self.assertIn('BUCKET_COUNT_MISMATCH', validate_atlas_report_rows(rows)['errors'])

    def test_missing_mandatory_is_partial(self):
        a=artifact(); a['modules'].pop()
        self.assertEqual(validate_report_evidence(a)['status'],'PARTIAL')
        a=artifact();a['atlas_rows'].pop()
        self.assertEqual(validate_report_evidence(a)['status'],'PARTIAL')
        a=artifact();a['modules'][1]['status']='not-executed'
        self.assertEqual(validate_report_evidence(a)['status'],'PARTIAL')

    def test_unknown_coverage_not_alone_partial(self):
        self.assertEqual(validate_report_evidence(artifact())['status'],'PASS')

    def test_module_order_language_status(self):
        for mutate, code in [
            (lambda a:a['modules'].reverse(),'REPORT_MODULE_ORDER'),
            (lambda a:a.update(language='xx'),'REPORT_LANGUAGE_INVALID'),
            (lambda a:a['modules'][0].update(language='en'),'MODULE_LANGUAGE_MISMATCH'),
            (lambda a:a['modules'][0].update(status='COMPLETE'),'MODULE_STATUS_INVALID')]:
            a=artifact();mutate(a);self.assertIn(code,validate_report_evidence(a)['errors'])

    def test_hard_counts_rank_identity_fail(self):
        for field,value,code in [('target_past_due_count',999,'BUCKET_DELTA_MISMATCH'),
                                 ('contribution_rank',8,'CONTRIBUTION_RANK_MISMATCH'),
                                 ('vendor_code',123,'FOREIGN_VENDOR_IDENTITY')]:
            rows=report_rows();rows[2][field]=value
            result=validate_atlas_report_rows(rows)
            self.assertEqual(result['status'],'FAIL');self.assertIn(code,result['errors'])

    def test_selected_vendor_copy_errors(self):
        a=artifact();a['selected_vendors'][0]['target_past_due_count']=999
        result=validate_report_evidence(a)
        self.assertIn('REPORT_COPY_ERROR',result['errors'])
        self.assertNotIn('CONFLICTING_FACTS',result['errors'])

    def test_expected_total_is_independent(self):
        rows=report_rows();c=next(r for r in rows if r.get('dimension')=='code' and r['record_type']=='VENDOR_CHECK')
        c.update(previous_total=9,previous_expected_total=9)
        self.assertIn('VENDOR_TOTAL_MISMATCH', validate_atlas_report_rows(rows)['errors'])

    def test_duplicates_and_controls(self):
        rows=report_rows();rows[2]['row_ordinal']=1
        self.assertIn('DUPLICATE_ROW_ORDINAL',validate_atlas_report_rows(rows)['errors'])
        rows=report_rows();rows.pop(0)
        self.assertEqual(validate_atlas_report_rows(rows)['status'],'PARTIAL')

    def test_code_error_keeps_source_verified(self):
        rows=report_rows();rows[2]['target_past_due_count']=999
        result=validate_atlas_report_rows(rows)
        states={k[-1]:v for k,v in result['dimensions'].items()}
        self.assertEqual(states['code'],'FAIL');self.assertEqual(states['source'],'PASS')

    def test_handle_expired_foreign_version_and_scope(self):
        for mutate,code in [
            (lambda h:h.update(expires_at=1000),'HANDLE_EXPIRED'),
            (lambda h:h.update(request_id='foreign'),'HANDLE_REQUEST_ID_MISMATCH'),
            (lambda h:h.update(analysis_version=3),'HANDLE_ANALYSIS_VERSION_MISMATCH'),
            (lambda h:h.update(environment_version=10),'HANDLE_ENVIRONMENT_VERSION_MISMATCH'),
            (lambda h:h['scope'].update(vendor_code=999),'HANDLE_SCOPE_MISMATCH')]:
            a=artifact();a=copy.deepcopy(a);mutate(a['evidence'][0])
            # deepcopy preserves shared dict identity; disconnect expected scope.
            a['evidence_references']=copy.deepcopy(artifact()['evidence_references'])
            self.assertIn(code,validate_report_evidence(a)['errors'])
        a=artifact();a['evidence']=[]
        self.assertEqual(validate_report_evidence(a)['status'],'PARTIAL')

    def test_raw_env_version_alias(self):
        a=artifact();h=a['evidence'][0]
        h['env/version']=h.pop('environment_version')
        h['analysis/version']=h.pop('analysis_version')
        h['result_handle']=h.pop('handle')
        h['expires-at']=h.pop('expires_at')
        self.assertEqual(validate_report_evidence(a)['status'],'PASS')

    def test_normalized_common_semantics_not_payload_equality(self):
        f=ReportsFact.fact()
        g=dict(f,target_past_due_count='20.00',previous_past_due_count='10',
               query_id='different',row_ordinal=100,coverage='unknown',
               projection=['extra'],new_display_label='not-semantic')
        g.pop('past_due_count_change')
        result=detect_cross_request_conflicts([f,g])
        self.assertEqual(result['status'],'PASS')
        g['target_past_due_count']=21
        self.assertEqual(detect_cross_request_conflicts([f,g])['status'],'FAIL')

    def test_nested_scope_canonical_filter(self):
        f=ReportsFact.fact(); f['selection_scope']={'top_n':10,'mode':'POSITIVE'}
        g=copy.deepcopy(f); g['selection_scope']={'mode':'POSITIVE','top_n':'10'}
        self.assertEqual(detect_cross_request_conflicts([f,g])['status'],'PASS')

    def test_missing_scope_not_conflict(self):
        f=ReportsFact.fact();g=dict(f,target_past_due_count=999);g.pop('metric_version')
        result=detect_cross_request_conflicts([f,g])
        self.assertEqual(result['status'],'PARTIAL');self.assertEqual(result['conflicts'],[])

    def test_cn_regression_values_and_en_limit(self):
        d=json.loads((R/'tests/fixtures/report-export-v3.1.json').read_text())
        v=next(v for v in d['vendors'] if v['vendor_code']==3493)
        self.assertEqual((v['country_code'],v['contribution_rank'],v['previous_past_due_count'],v['target_past_due_count']),('US',8,275,319))
        for vendor,dimension,value,p,t in [(12570,'code','SH',1,8),(37108,'source','BUYER',0,9)]:
            f=next(f for f in d['raw_facts'] if f['vendor_code']==vendor and f['dimension']==dimension and f['eta_value']==value)
            self.assertEqual((f['previous_past_due_count'],f['target_past_due_count']),(p,t))
        self.assertFalse(d['provenance']['en_expanded_payloads'])
        self.assertEqual(detect_cross_request_conflicts(d['raw_facts'])['status'],'PASS')

    def test_cn_copy_claim_not_server_conflict(self):
        d=json.loads((R/'tests/fixtures/report-export-v3.1.json').read_text())
        a=artifact();a['raw_facts']=d['raw_facts']
        claim=copy.deepcopy(next(f for f in d['raw_facts'] if f['vendor_code']==12570 and f['eta_value']=='SH'))
        claim['past_due_count_change']=9;a['claims']=[claim]
        result=validate_report_evidence(a)
        self.assertIn('REPORT_COPY_ERROR',result['errors'])
        self.assertNotIn('CONFLICTING_FACTS',result['errors'])
        a['claimed_conflict']=True
        self.assertIn('UNSUPPORTED_CONFLICT_CLAIM',validate_report_evidence(a)['errors'])

    def test_false_complete(self):
        a=artifact();a['modules'].pop();a['status']='COMPLETE'
        self.assertIn('FALSE_COMPLETE_CLAIM',validate_report_evidence(a)['errors'])

    def test_scope_key_request_and_metric_version(self):
        rows=report_rows();scope=dict(scope_key=rows[0]['scope_key'],country_code='US',metric_version='3.1.0')
        self.assertEqual(validate_atlas_report_rows(rows,expected_scope=scope)['status'],'PASS')
        bad=copy.deepcopy(rows);bad[2]['scope_key']=bad[2]['scope_key'].replace('US','CA')
        self.assertIn('SCOPE_KEY_ROW_MISMATCH',validate_atlas_report_rows(bad)['errors'])
        bad=copy.deepcopy(rows);bad[2]['scope_key']=bad[2]['scope_key'].replace(',10,',',9,')
        self.assertIn('SCOPE_KEY_DRIFT',validate_atlas_report_rows(bad)['errors'])
        bad=copy.deepcopy(rows);bad[2]['metric_version']='3.0.0'
        self.assertIn('METRIC_VERSION_MISMATCH',validate_atlas_report_rows(bad)['errors'])
        self.assertIn('REQUEST_SCOPE_MISMATCH',validate_atlas_report_rows(rows,expected_scope={'country_code':'CA'})['errors'])

    def test_monitor_control_independently_compared(self):
        rows=report_rows();control=rows[0]
        control.update(previous_country_past_due_count=300,target_country_past_due_count=400,
                       previous_country_open=1000,target_country_open=1000,
                       previous_country_ratio='0.3',target_country_ratio='0.4')
        monitor=dict(control,previous_past_due_count=300,target_past_due_count=400,
                     previous_open_count=1000,target_open_count=1000,
                     previous_past_due_ratio='.30',target_past_due_ratio='.40')
        self.assertEqual(validate_atlas_report_rows(rows,monitor_facts=[monitor])['status'],'PASS')
        monitor['target_past_due_count']=399
        self.assertIn('MONITOR_REPORT_MISMATCH',validate_atlas_report_rows(rows,monitor_facts=[monitor])['errors'])

    def test_actual_contract_compositions_and_vendor_repetition(self):
        contract=read(R/'skill/pastdue-analysis/references/table-display-contract.edn')
        a=artifact();vendor=a['selected_vendors'][0]
        repeat={'vendor_metrics','eta_code_changes','eta_source_changes','us_source_changes','business_diagnosis'}
        a['modules']=[dict(id=str(x).lstrip(':'),status='PASS',**(
            {'country_code':vendor['country_code'],'vendor_code':vendor['vendor_code']} if str(x).lstrip(':') in repeat else {}))
            for x in contract[':compositions'][':daily_full']]
        self.assertEqual(validate_report_evidence(a,contract)['status'],'PASS')
        a['modules']=[m for m in a['modules'] if m['id']!='business_diagnosis']
        self.assertEqual(validate_report_evidence(a,contract)['status'],'PARTIAL')

    def test_country_order_and_global_report_sections(self):
        contract=read(R/'skill/pastdue-analysis/references/table-display-contract.edn')
        a=artifact(); a['modules']=[]; a['selected_vendors']=[]; a['atlas_rows']=[]
        repeat={'vendor_metrics','eta_code_changes','eta_source_changes','us_source_changes','business_diagnosis'}
        for module in ['run_info','country_metrics']:
            a['modules'].append(dict(id=module,status='PASS'))
        for cc in ['US','CA']:
            rows=report_rows()
            for row in rows:
                row['country_code']=cc;row['scope_key']=row['scope_key'].replace('US',cc)
            vendor=rows[1];a['atlas_rows']+=rows;a['selected_vendors'].append(vendor)
            a['modules'].append(dict(id='vendor_summary',status='PASS',country_code=cc))
            for module in ['vendor_metrics','eta_code_changes','eta_source_changes','us_source_changes','business_diagnosis']:
                a['modules'].append(dict(id=module,status='PASS',country_code=cc,vendor_code=vendor['vendor_code']))
            a['modules'].append(dict(id='country_summary',status='PASS',country_code=cc))
        for module in ['cross_country_summary','completion']:
            a['modules'].append(dict(id=module,status='PASS'))
        self.assertEqual(validate_report_evidence(a,contract)['status'],'PASS')
        a['modules']=[m for m in a['modules'] if m['id']!='cross_country_summary']
        self.assertEqual(validate_report_evidence(a,contract)['status'],'PARTIAL')

    def test_empty_selected_applicable_path(self):
        contract=read(R/'skill/pastdue-analysis/references/table-display-contract.edn')
        a=artifact();a['selected_vendors']=[]
        control=a['atlas_rows'][0];control.update(selected_vendor_count=0,expected_record_count=1)
        a['atlas_rows']=[control]
        repeated={'vendor_metrics','eta_code_changes','eta_source_changes','us_source_changes','business_diagnosis'}
        a['modules']=[dict(id=str(m).lstrip(':'),status='PASS') for m in contract[':compositions'][':daily_full'] if str(m).lstrip(':') not in repeated]
        self.assertEqual(validate_report_evidence(a,contract)['status'],'PASS')

    def test_raw_envelope_env_alias(self):
        f=ReportsFact.fact();f.pop('environment_version')
        a={'scope':{k:f.pop(k) for k in list(f) if k not in ('eta_value','previous_past_due_count','target_past_due_count','past_due_count_change')},
           'env/version':9,'facts':[f]}
        b=copy.deepcopy(a);b['facts'][0]['target_past_due_count']=21
        self.assertEqual(detect_cross_request_conflicts([a,b])['status'],'FAIL')

    def test_nonrequired_us_zero_rows_explicit_record(self):
        rows=report_rows()
        for row in rows:
            if row.get('dimension')=='code' and row.get('eta_value')=='US':
                row['eta_value']='DD'
            if row.get('dimension')=='us':
                if row['record_type']=='VENDOR_CHECK':
                    row.update(previous_total=0,target_total=0,previous_expected_total=0,
                               target_expected_total=0,required=False)
                else:
                    row.update(previous_past_due_count=0,target_past_due_count=0,past_due_count_change=0)
        result=validate_atlas_report_rows(rows)
        self.assertEqual(result['status'],'PASS',result)

    def test_compressed_us_server_grade_not_partial(self):
        rows=report_rows(8)
        us=next(r for r in rows if r.get('dimension')=='code' and r.get('eta_value')=='US')
        other=next(r for r in rows if r.get('dimension')=='code' and r.get('row_kind')=='OTHER')
        # Exchange values/counts so US is hidden in OTHER; preserve exact total.
        us.update(eta_value='B-unknown',previous_past_due_count=3,target_past_due_count=2)
        self.assertEqual(validate_atlas_report_rows(rows)['status'],'PASS')
        self.assertEqual(validate_atlas_report_rows(rows,audit_grade='CLIENT_RAW_AUDIT')['status'],'PARTIAL')

    def test_single_vendor_rank_not_contiguous(self):
        rows=report_rows()
        import json as json_module
        for row in rows:
            row['selection_mode']='SINGLE_VENDOR'
            row['scope_key']=json_module.dumps(['US','2026-10-23','2026-10-22','WEEKDAYS','SINGLE_VENDOR',1,3493])
            if row['record_type']!='COUNTRY_CONTROL':row['contribution_rank']=12
        self.assertEqual(validate_atlas_report_rows(rows)['status'],'PASS')

    def test_auto_us_source_alias(self):
        for source in ('AutoUS','AUTO US','  auto us  '):
            self.assertEqual(us_rule(source),'ETA_EXPIRED')
        self.assertEqual(us_rule('BUYER'),'ROUGH_DATE')
        self.assertEqual(us_rule('UNKNOWN'),'UNKNOWN_SOURCE')

    def test_malformed_hard_fields_fail_not_crash(self):
        for field,value in [('expected_record_count','bad'),('row_ordinal',1.5),
                            ('target_past_due_count',True),('required','false')]:
            rows=report_rows();rows[2][field]=value
            self.assertEqual(validate_atlas_report_rows(rows)['status'],'FAIL')

    def test_claim_handle_association_not_fact_conflict(self):
        f=ReportsFact.fact();f['evidence_handle']='recorded-raw-handle'
        a=artifact();a['raw_facts']=[f]
        a['claims']=[dict(f,evidence_handle='foreign-handle')]
        result=validate_report_evidence(a)
        self.assertIn('RAW_HANDLE_ASSOCIATION_MISMATCH',result['errors'])
        self.assertNotIn('CONFLICTING_FACTS',result['errors'])

    def test_us_source_dictionary_not_catch_all(self):
        rules=read(R/'skill/pastdue-analysis/references/eta-diagnostic-rules.edn')
        ids=[r[':rule-id'] for r in rules[':rules']]
        self.assertEqual(len(ids),len(set(ids)))
        self.assertTrue(all(r[':risk-type'] and isinstance(r[':requires-joint-facts'],bool) for r in rules[':rules']))
        for source in rules[':source-classification'][':recognized-other-sources']:
            self.assertEqual(us_rule(source.lower()),'ROUGH_DATE')
            self.assertEqual(us_rule(source,observed_in_joint=False),'UNKNOWN_SOURCE')
        for source in ('unknown-new-source','EDI','EDI855 extra','AUTO   US','OTHER'):
            self.assertEqual(us_rule(source),'UNKNOWN_SOURCE')

    def test_every_composition_module_resolves_by_exact_edn_keyword(self):
        from edn_io import K
        contract=read(R/'skill/pastdue-analysis/references/table-display-contract.edn')
        modules=contract[':modules']
        for composition, module_ids in contract[':compositions'].items():
            for module_id in module_ids:
                self.assertIsInstance(module_id,type(K(':module')))
                self.assertIn(module_id,modules, f'{composition}: unresolved {module_id}')
                self.assertIsInstance(next(k for k in modules if k==module_id),type(K(':module')),
                                      f'{module_id}: string key is not an EDN keyword')
        for module_id in contract[':composition-rules'][':per-selected-vendor']:
            self.assertIn(module_id,modules)

    def test_injected_string_contract_keys_and_references_rejected(self):
        from edn_io import K
        original=read(R/'skill/pastdue-analysis/references/table-display-contract.edn')
        self.assertEqual(validate_display_contract(original)['status'],'PASS')
        for key in (':business_diagnosis',':country_summary'):
            contract=copy.deepcopy(original)
            value=contract[':modules'].pop(K(key));contract[':modules'][str(key)]=value
            self.assertIn('MODULE_KEY_NOT_EDN_KEYWORD',validate_display_contract(contract)['errors'])
        contract=copy.deepcopy(original)
        contract[':compositions'][K(':daily_full')][0]=':country_metrics'
        self.assertIn('MODULE_REFERENCE_NOT_EDN_KEYWORD',validate_display_contract(contract)['errors'])


class ReportsFact:
    @staticmethod
    def fact():
        return dict(country_code='US',vendor_code=3493,dimension='code',eta_value='NR',
                    target_snapshot_date='2026-10-23',previous_snapshot_date='2026-10-22',
                    calendar_mode='WEEKDAYS',selection_scope='POSITIVE_TOP_10',
                    metric_version='3.1.0',environment_version=9,
                    previous_past_due_count=10,target_past_due_count=20,past_due_count_change=10)


if __name__=='__main__':unittest.main()

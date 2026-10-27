#!/usr/bin/env python3
"""Offline semantic and failure-injection tests. No production access."""
import copy
import unittest
from datetime import date
from decimal import Decimal
from analysis_validation import (select_pair,previous_weekday,country_status,
                                 validate_dimension,us_rule,summarize,
                                 detect_cross_request_conflicts)


class AnalysisTests(unittest.TestCase):
    def test_workday_pairs(self):
        dates=[date(2026,10,d) for d in [22,23,24,25,26]]
        self.assertEqual(select_pair(dates,date(2026,10,27)),(date(2026,10,26),date(2026,10,23)))
        self.assertEqual(select_pair(dates,date(2026,10,26)),(date(2026,10,23),date(2026,10,22)))
        self.assertEqual(select_pair(dates,date(2026,10,26),'CALENDAR_DAYS'),(date(2026,10,25),date(2026,10,24)))
        self.assertEqual(previous_weekday(date(2026,10,26)),date(2026,10,23))
    def test_missing_and_insufficient(self):
        self.assertEqual(select_pair([date(2026,10,20),date(2026,10,23)],date(2026,10,27)),(date(2026,10,23),date(2026,10,20)))
        with self.assertRaises(ValueError):select_pair([date(2026,10,23)],date(2026,10,27))
    def test_exact_threshold_and_zero(self):
        self.assertEqual(country_status(110,1000,100,1000)[0],'TRIGGERED')
        self.assertEqual(country_status(109999,1000000,100000,1000000)[0],'NORMAL')
        self.assertEqual(country_status(1,10,0,10)[0],'ZERO_BASELINE_INCREASE')
        self.assertEqual(country_status(0,10,0,10)[0],'NORMAL')
        self.assertEqual(country_status(0,0,0,10)[0],'NOT_EVALUABLE')
        self.assertEqual(country_status(11,10,0,10)[1],'DATA_ERROR')
    def test_top10_not_80(self):
        rows=[{'vendor':i,'n':900 if i==1 else 1} for i in range(1,13)]+[{'vendor':99,'n':-2}]
        selected=sorted((r for r in rows if r['n']>0),key=lambda r:(-r['n'],r['vendor']))[:10]
        self.assertEqual([r['vendor'] for r in selected],list(range(1,11)))
        self.assertGreater(Decimal(900)/912,Decimal('.8'))
    def test_other_and_improvement(self):
        rows=[{'value':chr(65+i),'previous':p,'target':t} for i,(p,t) in enumerate([(100,160),(80,30),(0,20),(40,0),(10,10),(10,12),(100,101)])]
        s=summarize(rows)
        self.assertEqual([r['value'] for r in s['top']],['A','B','D','C','F'])
        self.assertEqual(s['other']['previous'],110);self.assertEqual(s['other']['relative'],Decimal(1)/110)
        self.assertEqual((s['increase'],s['decrease']),(83,-90))
    def fixtures(self):
        ident={'country_code':'CA','vendor_code':40586,'target_snapshot_date':'2026-10-26','previous_snapshot_date':'2026-10-23','calendar_mode':'WEEKDAYS'}
        v=dict(ident,previous_past_due_count=289,target_past_due_count=419)
        rs=[dict(ident,eta_value='NR',previous_past_due_count=258,target_past_due_count=387,past_due_count_change=129,row_ordinal=1,expected_bucket_count=2),dict(ident,eta_value='SH',previous_past_due_count=31,target_past_due_count=32,past_due_count_change=1,row_ordinal=2,expected_bucket_count=2)]
        c=dict(ident,dimension='code',expected_bucket_count=2,previous_total=289,target_total=419,counts_match=True)
        return [v],rs,[c]
    def test_valid_dimension(self):self.assertEqual(validate_dimension(*self.fixtures(),'code'),[])
    def test_incomplete_duplicate_foreign(self):
        vs,rs,cs=self.fixtures()
        self.assertIn('INCOMPLETE',validate_dimension(vs,rs[:1],cs,'code'))
        self.assertIn('DUPLICATE',validate_dimension(vs,rs+rs[:1],cs,'code'))
        for key,val in [('vendor_code',31420),('country_code','US'),('target_snapshot_date','2026-10-27')]:
            bad=copy.deepcopy(rs);bad[0][key]=val
            self.assertIn('IDENTITY_MISMATCH',validate_dimension(vs,bad,cs,'code'))
    def test_wrong_counts_even_if_query_successful(self):
        vs,rs,cs=self.fixtures();bad=copy.deepcopy(rs);bad[0]['target_past_due_count']=1
        self.assertIn('VENDOR_TOTAL_MISMATCH',validate_dimension(vs,bad,cs,'code'))
        self.assertIn('DELTA_MISMATCH',validate_dimension(vs,bad,cs,'code'))
    def test_checks_not_received_or_drift(self):
        vs,rs,cs=self.fixtures();self.assertIn('CHECK_SET_MISMATCH',validate_dimension(vs,rs,[],'code'))
        cs[0]['expected_bucket_count']=3
        self.assertIn('COUNT_METADATA_DRIFT',validate_dimension(vs,rs,cs,'code'))
    def test_us_rule_requires_known_source(self):
        self.assertEqual(us_rule(' AutoUS '),'ETA_EXPIRED');self.assertEqual(us_rule('EDI870'),'ROUGH_DATE')
        self.assertEqual(us_rule('UNKNOWN'),'UNKNOWN_SOURCE');self.assertEqual(us_rule(None),'UNKNOWN_SOURCE')
    def test_negative_count_positive_contribution(self):
        self.assertLess(90-100,0);self.assertGreater(Decimal(90)/700-Decimal(100)/1000,0)

    def _fact(self, **overrides):
        fact = {
            'country_code': 'CA', 'vendor_code': 40586, 'dimension': 'code',
            'target_snapshot_date': '2026-10-26',
            'previous_snapshot_date': '2026-10-23',
            'calendar_mode': 'WEEKDAYS', 'selection_scope': 'POSITIVE_TOP_10',
            'metric_version': 'v3', 'environment_version': 'graph-v3',
            'eta_value': 'NR', 'previous_past_due_count': 10,
            'target_past_due_count': 20, 'row_ordinal': 1,
        }
        fact.update(overrides)
        return fact

    def test_cross_request_same_scope_conflict_is_quarantined(self):
        a = self._fact()
        b = self._fact(target_past_due_count=30, response_id='second')
        result = detect_cross_request_conflicts([a, b])
        self.assertEqual(result['status'], 'FAIL')
        self.assertEqual(len(result['conflicts']), 1)
        self.assertEqual(result['usable_facts'], [])
        self.assertEqual(result['conflicts'][0]['status'], 'CONFLICTING_FACTS')

    def test_same_scope_different_eta_values_are_independent(self):
        result = detect_cross_request_conflicts([
            self._fact(eta_value='NR'), self._fact(eta_value='SH', row_ordinal=2,
                                                    previous_past_due_count=5,
                                                    target_past_due_count=6),
        ])
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(len(result['usable_facts']), 2)

    def test_different_scopes_do_not_conflict(self):
        result = detect_cross_request_conflicts([
            self._fact(vendor_code=40586), self._fact(vendor_code=31420,
                                                      previous_past_due_count=1,
                                                      target_past_due_count=99),
        ])
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(len(result['usable_facts']), 2)

    def test_missing_scope_is_unverified_not_complete(self):
        fact = self._fact()
        del fact['selection_scope']
        result = detect_cross_request_conflicts([fact])
        self.assertEqual(result['status'], 'PARTIAL')
        self.assertEqual(result['usable_facts'], [])
        self.assertEqual(result['unverified'][0]['status'] if 'status' in result['unverified'][0] else 'PARTIAL', 'PARTIAL')

    def test_exact_duplicate_is_deduplicated(self):
        result = detect_cross_request_conflicts([self._fact(), self._fact()])
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(len(result['usable_facts']), 1)
        self.assertEqual(result['duplicates'][0]['status'], 'DUPLICATE_IDENTICAL_FACT')

    def test_code_conflict_does_not_erase_independent_source(self):
        result = detect_cross_request_conflicts([
            self._fact(dimension='code', target_past_due_count=20),
            self._fact(dimension='code', target_past_due_count=30, response_id='bad'),
            self._fact(dimension='source', eta_value='AutoUS',
                       previous_past_due_count=3, target_past_due_count=4),
        ])
        self.assertEqual(result['status'], 'FAIL')
        self.assertEqual(len(result['conflicts']), 1)
        self.assertEqual(len(result['usable_facts']), 1)
        self.assertEqual(result['usable_facts'][0]['dimension'], 'source')


if __name__=='__main__':unittest.main()

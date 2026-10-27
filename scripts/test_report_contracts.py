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


if __name__=='__main__':unittest.main()

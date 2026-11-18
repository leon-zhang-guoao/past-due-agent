#!/usr/bin/env python3
"""Regression against authoritative deployed-shaped v3 graph, not reference shards."""
import copy
import unittest
import validate_bundle as b
from edn_io import loads,dumps,K


class QueryContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.doc=b.graph()

    def test_authoritative_contract(self):self.assertEqual(b.validate(self.doc),[])
    def test_examples(self):self.assertEqual(b.check_examples(self.doc),[])
    def test_namespaced_roundtrip(self):self.assertEqual(loads(dumps(self.doc)),self.doc)
    def test_duplicate_and_trailing_forms_rejected(self):
        for s in ['{:a 1 :a 2}','{:a 1} {}','{:a}']:
            with self.assertRaises(ValueError):loads(s)
    def test_missing_binding_is_rejected(self):
        d=copy.deepcopy(self.doc);d[':execution'][':selection'].pop(next(iter(d[':execution'][':selection'])))
        self.assertTrue(b.validate(d))
    def test_wrong_input_mapping_is_rejected(self):
        d=copy.deepcopy(self.doc);bid='pastdue_v3_analysis_query_eta_code_changes-sql';d[':execution'][':bindings'][bid][':binding/input-map'].pop(':pastdue.analysis-query/top-n')
        self.assertTrue(b.validate(d))
    def test_missing_output_rejected(self):
        d=copy.deepcopy(self.doc);bid='pastdue_v3_analysis_query_eta_code_changes-sql';d[':execution'][':bindings'][bid][':binding/output-map'].pop(':pastdue.analysis-eta-code/vendor-code')
        self.assertTrue(b.validate(d))
    def test_sql_selection_shared(self):
        from build_analysis_contract import pair_ctes
        sqls=[v[':binding/implementation'][':query'] for k,v in self.doc[':execution'][':bindings'].items() if k.startswith('pastdue_v3_analysis_query_')]
        self.assertEqual(len(sqls),6)
        for sql in sqls:self.assertTrue(sql.startswith('WITH '+pair_ctes()))
    def test_identity_projected_in_all_eta(self):
        for suffix in ['code','source','us_source']:
            r=self.doc[':execution'][':resolvers']['pastdue_v3_analysis_query_'+('us_source_changes' if suffix=='us_source' else 'eta_'+suffix+'_changes')]
            fields=b.flatten_output(r[':output'])
            for f in ['country-code','vendor-code','target-snapshot-date','previous-snapshot-date','calendar-mode','row-ordinal','expected-bucket-count']:
                self.assertTrue(any(a.endswith('/'+f) for a in fields))
    def test_policy_and_display(self):self.assertEqual(b.check_skill(),[])
    def test_v31_input_output_contracts(self):
        from build_v31_contract import EXPLORE_INPUTS,EXPLORE_FIELDS,REPORT_FIELDS
        for prefix,fields,inputs in [('explore',EXPLORE_FIELDS,[n for n,_ in EXPLORE_INPUTS]),('report',REPORT_FIELDS,['country-code','target-snapshot-date','previous-snapshot-date','calendar-mode','selection-mode','top-n','vendor-code'])]:
            rid='pastdue_v3_'+prefix+'_query_rows';r=self.doc[':execution'][':resolvers'][rid]
            self.assertEqual({a.rsplit('/',1)[1] for a in r[':input']},set(inputs))
            self.assertEqual({a.rsplit('/',1)[1] for a in b.flatten_output(r[':output']) if not a.endswith('/rows')},{n for n,_ in fields})
            entity=self.doc[':vocabulary'][':entities']['pastdue.'+prefix+'-fact']
            self.assertIn(':pastdue.'+prefix+'-fact/scope-key',entity[':entity/identity'])
        self.assertEqual(self.doc[':publication'][':release/version'],'3.6.0')

    def test_additive_explore_preserves_existing_interfaces(self):
        from build_explore_v2 import add_explore_v2, INPUTS, FIELDS
        d=copy.deepcopy(self.doc)
        existing={k:copy.deepcopy(v) for k,v in d[':execution'][':resolvers'].items()
                  if k!='pastdue_v3_explore_v2_query_rows'}
        bindings={k:copy.deepcopy(v) for k,v in d[':execution'][':bindings'].items()
                  if k!='pastdue_v3_explore_v2_query_rows-sql'}
        add_explore_v2(d)
        for k,v in existing.items():self.assertEqual(d[':execution'][':resolvers'][k],v,k)
        for k,v in bindings.items():self.assertEqual(d[':execution'][':bindings'][k],v,k)
        r=d[':execution'][':resolvers']['pastdue_v3_explore_v2_query_rows']
        self.assertEqual({a.rsplit('/',1)[1] for a in r[':input']},{n for n,_ in INPUTS})
        self.assertEqual({a.rsplit('/',1)[1] for a in b.flatten_output(r[':output']) if not a.endswith('/rows')},{n for n,_ in FIELDS})

    def test_no_existing_metric_changes_against_committed_baseline(self):
        import subprocess
        baseline=loads(subprocess.check_output(['git','show','HEAD:atlas/charts/pastdue.edn'],cwd=b.ROOT,text=True))
        for section in (':resolvers',':bindings'):
            for name,value in baseline[':execution'][section].items():
                self.assertEqual(self.doc[':execution'][section][name],value,name)

    def test_complete_v2_example_matches_actual_contract(self):
        from build_explore_v2 import INPUTS,FIELDS
        example=loads((b.SKILL_DIR/'references/explore-v2-query.eql').read_text())
        self.assertEqual(set(example[':entity']),{K(':pastdue.explore-v2-query/'+n) for n,_ in INPUTS})
        self.assertEqual(set(example[':query'][0][':pastdue.explore-v2-query/rows']),{K(':pastdue.explore-v2-fact/'+n) for n,_ in FIELDS})

    def test_complete_v3_example_matches_actual_contract(self):
        from build_explore_v3 import INPUTS,FIELDS
        example=loads((b.SKILL_DIR/'references/explore-v3-query.eql').read_text())
        self.assertEqual(set(example[':entity']),{K(':pastdue.explore-v3-query/'+n) for n,_ in INPUTS})
        self.assertEqual(set(example[':query'][0][':pastdue.explore-v3-query/rows']),{K(':pastdue.explore-v3-fact/'+n) for n,_ in FIELDS})

    def test_new_capability_preserves_v2_release_bindings(self):
        import zipfile
        with zipfile.ZipFile(b.ROOT/'releases/pastdue-agent-v3.4.0.zip') as archive:
            baseline=loads(archive.read('atlas/charts/pastdue.edn').decode())
        for section in (':resolvers',':bindings'):
            for name,value in baseline[':execution'][section].items():
                self.assertEqual(self.doc[':execution'][section][name],value,name)

    def test_report_query_has_bounded_linear_sources(self):
        sql=self.doc[':execution'][':bindings']['pastdue_v3_report_query_rows-sql'][':binding/implementation'][':query']
        self.assertEqual(sql.count('FROM past_due.vendor_past_due_daily'),2)
        self.assertNotIn('SELECT max(target_country_open)',sql)
        self.assertNotIn('FROM country_pair',sql)
        self.assertIn('snapshot_date BETWEEN toDate(:previous_snapshot_date) AND toDate(:target_snapshot_date)',sql)
        self.assertIn('snapshot_date IN (toDate(:target_snapshot_date),toDate(:previous_snapshot_date))',sql)
        self.assertIn('FROM scoped WHERE is_control OR (chosen',sql)
        self.assertNotIn('SELECT count() FROM',sql)

    def test_generator_idempotent(self):
        from build_analysis_contract import build,GRAPH
        import tempfile
        from pathlib import Path
        before=GRAPH.read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            graph=Path(tmp)/'pastdue.edn';graph.write_bytes(before)
            build(graph);once=graph.read_bytes();build(graph)
            self.assertEqual(once,graph.read_bytes())
            self.assertEqual(before,once)
        self.assertEqual(before,GRAPH.read_bytes())


if __name__=='__main__':unittest.main()

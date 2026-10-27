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
        self.assertEqual(self.doc[':publication'][':release/version'],'3.1.0')

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

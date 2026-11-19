#!/usr/bin/env python3
"""Native22.8 live overview/identity and original env18 request regressions.

Synthetic local data only. Missing binary is NOT compatibility acceptance.
"""
import unittest
from test_live_current import Live
from test_clickhouse22_current import BINARY, ClickHouse22Current


@unittest.skipUnless(BINARY, 'CLICKHOUSE22_BIN missing;22.8 compatibility NOT VERIFIED')
class ClickHouse22Live(Live):
    __unittest_skip__ = not bool(BINARY)
    setUpClass = classmethod(ClickHouse22Current.setUpClass.__func__)

    def test_original_f5_identity_requests(self):
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES "
                      "('US',64342,'F5 NETWORKS, INC','NR','EDI',1,'LATE'),"
                      "('US',64342,'F5 NETWORKS, INC','SH','EDI',0,'OPEN')")
        for names in ('["F5 NETWORKS, INC"]', '[]'):
            with self.subTest(names=names):
                rows = self.resolve(countries_json='["US","CA"]',
                                    vendor_codes_json='[64342]',vendor_names_json=names)
                self.assertEqual(rows[0]['record_type'], 'CONTROL')
                self.assertEqual(rows[0]['scope_status'], 'OK')
                facts = rows[1:]
                self.assertEqual(len(facts), 2 if names != '[]' else 1)
                self.assertEqual({(r['country_code'],int(r['vendor_code']),r['scope_status'])
                                  for r in facts}, {('US',64342,'MATCHED')})
                current = self.sql('pastdue_v3_current_v2_query_rows',country_code='US',
                                   vendor_codes_json='[64342]',page_size=100,
                                   denominator_scope='WITHIN_VENDOR')
                measured = [r for r in current if r['record_type'] == 'FACT']
                self.assertEqual({(int(r['vendor_open_count']),int(r['vendor_past_due_count']))
                                  for r in measured}, {(2,1)})


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ClickHouse22Live)
    raise SystemExit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())

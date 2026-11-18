"""Exercise the shipped retained ETA projection, including summary/check separation."""
import unittest
from test_page_reads import retained_read, REFS


def sample(kind='ETA_CODE_SUMMARY', **values):
    row=dict(scope_key='scope',record_type=kind,country_code='US',vendor_code=1,
             vendor_name='Fixture',previous_snapshot_date='2026-11-16',
             target_snapshot_date='2026-11-17',calendar_mode='WEEKDAYS',
             selection_mode='SINGLE_VENDOR',metric_version='3.1.0',scope_status='OK',
             contribution_rank=1,selected_vendor_count=1,dimension='code',eta_value='SH',
             row_kind='BUCKET',display_rank=1,other_bucket_count=0,
             previous_past_due_count=485,target_past_due_count=908,past_due_count_change=423,
             past_due_count_relative_change='0.87',delta_country_contribution='0.001',
             expected_bucket_count=9,increase_total=857,decrease_total=-9,net_change=848,
             previous_total=3450,target_total=4298,previous_expected_total=3450,
             target_expected_total=4298,counts_match=True,required=True,
             expected_record_count=6,row_ordinal=1)
    row.update(values)
    return row


class EtaRead(unittest.TestCase):
    def read(self, rows):
        return retained_read(rows,sql=(REFS/'report-eta-read.sql').read_text())

    def test_bucket_fields_keep_counts_and_hide_repeated_dimension_totals(self):
        rows=[sample(),sample('ETA_SOURCE_SUMMARY',dimension='source',eta_value='Carrier API',
                              previous_past_due_count=312,target_past_due_count=707,
                              past_due_count_change=395,row_ordinal=2),
              sample('US_SOURCE_SUMMARY',dimension='us',eta_value='AUTO US',row_ordinal=3)]
        bound=self.read(rows)
        self.assertEqual(len(bound),3)
        for original,returned in zip(rows,bound):
            for f in ('previous_past_due_count','target_past_due_count','past_due_count_change',
                      'eta_value','dimension','row_ordinal','record_type'):
                self.assertEqual(returned[f],original[f])
            for f in ('previous_total','target_total','net_change','increase_total','decrease_total',
                      'previous_expected_total','target_expected_total','required','counts_match'):
                self.assertIsNone(returned[f],f)

    def test_check_fields_preserved_bucket_placeholders_hidden(self):
        row=sample('VENDOR_CHECK',eta_value='',row_kind='',row_ordinal=5)
        returned=self.read([row])[0]
        for f in ('previous_total','target_total','net_change','increase_total','decrease_total',
                  'previous_expected_total','target_expected_total','required','counts_match'):
            self.assertEqual(returned[f],row[f])
        for f in ('previous_past_due_count','target_past_due_count','past_due_count_change'):
            self.assertIsNone(returned[f],f)

    def test_other_zero_negative_and_lowercase_dimensions_not_dropped(self):
        rows=[sample(eta_value='',row_kind='OTHER',display_rank=6,other_bucket_count=4),
              sample(eta_value='DD',previous_past_due_count=196,target_past_due_count=187,
                     past_due_count_change=-9,row_ordinal=2),
              sample(eta_value='NS',previous_past_due_count=0,target_past_due_count=0,
                     past_due_count_change=0,row_ordinal=3),
              sample('US_SOURCE_SUMMARY',dimension='us',eta_value='Vendor Email',row_ordinal=4)]
        out=self.read(rows)
        self.assertEqual([r['past_due_count_change'] for r in out],[423,-9,0,423])
        self.assertEqual(out[0]['other_bucket_count'],4)
        self.assertEqual(out[-1]['eta_value'],'Vendor Email')


if __name__=='__main__': unittest.main()

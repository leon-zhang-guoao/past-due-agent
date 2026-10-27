#!/usr/bin/env python3
"""Idempotent patch: inject current-detail edges into atlas/charts/pastdue.edn."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDN = ROOT / "atlas" / "charts" / "pastdue.edn"
MARKER = "pastdue.current-vendor-rank-query"

# Clone alert-type join/filter shape. Scan only past-due lines so ranking by
# share of country past-due == order by vendor_pastdue_count (constant denom).
# Never SQL-alias `rank` / join-alias `map` (ClickHouse builtins).
RANK_SQL = (
    "WITH mapped AS ("
    " SELECT DISTINCT toInt64(vendor_no) AS vendor_code"
    " FROM past_due.vendor_master_mapping_view"
    " WHERE country_code = :country_code"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:department_type_filter, '')))"
    "  OR upper(trim(department_type)) = upper(trim(:department_type_filter)))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:master_primary_filter, '')))"
    "  OR trim(BOTH ' ' FROM ifNull(master_primary, '')) = trim(BOTH ' ' FROM :master_primary_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:master_manager_filter, '')))"
    "  OR trim(BOTH ' ' FROM ifNull(master_manager, '')) = trim(BOTH ' ' FROM :master_manager_filter))),"
    " agg AS ("
    " SELECT t.country_code, toInt64(t.from_acct_no) AS vendor_code,"
    " any(t.vendor_name) AS vendor_name,"
    " toInt64(count()) AS vendor_pastdue_count"
    " FROM past_due.past_due_detail_view AS t"
    " INNER JOIN mapped AS m ON toInt64(t.from_acct_no) = m.vendor_code"
    " WHERE t.country_code = :country_code"
    " AND t.from_acct_no IS NOT NULL"
    " AND toInt64(t.is_pastdue) = 1"
    " GROUP BY t.country_code, vendor_code)"
    " SELECT country_code, vendor_code, vendor_name,"
    " vendor_pastdue_count AS vendor_total_count,"
    " vendor_pastdue_count,"
    " toDecimal64(1, 9) AS pastdue_rate_in_vendor,"
    " toDecimal64("
    "  toFloat64(vendor_pastdue_count)"
    "  / toFloat64(nullIf(sum(vendor_pastdue_count) OVER (), 0)), 9)"
    "  AS pastdue_rate_in_total,"
    " toInt64(row_number() OVER ("
    "  ORDER BY vendor_pastdue_count DESC, vendor_code ASC)) AS vendor_rank,"
    " now('America/Los_Angeles') AS queried_at_utc"
    " FROM agg"
)

ALERT_SQL = (
    "WITH mapped AS ("
    " SELECT DISTINCT toInt64(vendor_no) AS vendor_code"
    " FROM past_due.vendor_master_mapping_view"
    " WHERE country_code = :country_code"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:department_type_filter, '')))"
    "  OR upper(trim(department_type)) = upper(trim(:department_type_filter)))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:master_primary_filter, '')))"
    "  OR trim(BOTH ' ' FROM ifNull(master_primary, '')) = trim(BOTH ' ' FROM :master_primary_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:master_manager_filter, '')))"
    "  OR trim(BOTH ' ' FROM ifNull(master_manager, '')) = trim(BOTH ' ' FROM :master_manager_filter)))"
    " SELECT t.country_code, toInt64(t.from_acct_no) AS vendor_code,"
    " any(t.vendor_name) AS vendor_name,"
    " t.alert_type AS alert_type,"
    " toInt64(count()) AS line_count,"
    " now('America/Los_Angeles') AS queried_at_utc"
    " FROM past_due.past_due_detail_view AS t"
    " INNER JOIN mapped AS m ON toInt64(t.from_acct_no) = m.vendor_code"
    " WHERE t.country_code = :country_code AND t.from_acct_no IS NOT NULL"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:vendor_code_filter, '')))"
    "  OR toString(toInt64(t.from_acct_no)) = trim(BOTH ' ' FROM :vendor_code_filter))"
    " GROUP BY t.country_code, vendor_code, t.alert_type"
)

DETAIL_SQL = (
    "WITH mapped AS ("
    " SELECT country_code, toInt64(vendor_no) AS vendor_code,"
    " if(empty(arrayStringConcat(arraySort(arrayDistinct(groupArrayIf("
    "  trim(BOTH ' ' FROM ifNull(master_primary, '')),"
    "  notEmpty(trim(BOTH ' ' FROM ifNull(master_primary, '')))))), '; ')),"
    "  'UNASSIGNED',"
    "  arrayStringConcat(arraySort(arrayDistinct(groupArrayIf("
    "   trim(BOTH ' ' FROM ifNull(master_primary, '')),"
    "   notEmpty(trim(BOTH ' ' FROM ifNull(master_primary, '')))))), '; ')) AS primary_owner,"
    " if(empty(arrayStringConcat(arraySort(arrayDistinct(groupArrayIf("
    "  trim(BOTH ' ' FROM ifNull(master_manager, '')),"
    "  notEmpty(trim(BOTH ' ' FROM ifNull(master_manager, '')))))), '; ')),"
    "  'UNASSIGNED',"
    "  arrayStringConcat(arraySort(arrayDistinct(groupArrayIf("
    "   trim(BOTH ' ' FROM ifNull(master_manager, '')),"
    "   notEmpty(trim(BOTH ' ' FROM ifNull(master_manager, '')))))), '; ')) AS manager_owner"
    " FROM past_due.vendor_master_mapping_view"
    " WHERE country_code = :country_code"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:department_type_filter, '')))"
    "  OR upper(trim(department_type)) = upper(trim(:department_type_filter)))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:master_primary_filter, '')))"
    "  OR trim(BOTH ' ' FROM ifNull(master_primary, '')) = trim(BOTH ' ' FROM :master_primary_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:master_manager_filter, '')))"
    "  OR trim(BOTH ' ' FROM ifNull(master_manager, '')) = trim(BOTH ' ' FROM :master_manager_filter))"
    " GROUP BY country_code, vendor_code)"
    " SELECT t.country_code, toInt64(t.from_acct_no) AS vendor_code, t.vendor_name,"
    " t.order_type, t.order_no AS po_number, t.order_line_no AS po_line_number,"
    " t.sku_no AS sku_number, t.order_qty AS ordered_quantity,"
    " t.ship_qty AS shipped_quantity, t.rec_qty AS received_quantity,"
    " if(empty(trim(BOTH ' ' FROM ifNull(t.eta_code, ''))), 'UNKNOWN',"
    "  trim(BOTH ' ' FROM ifNull(t.eta_code, ''))) AS eta_code,"
    " t.eta_date,"
    " if(empty(trim(BOTH ' ' FROM ifNull(t.source, ''))), 'UNKNOWN',"
    "  trim(BOTH ' ' FROM ifNull(t.source, ''))) AS eta_source,"
    " t.risk_level, if(toInt64(t.is_pastdue) = 1, true, false) AS is_past_due,"
    " if(toInt64(t.is_pastdue) = 1, toNullable(toInt64OrZero(toString(t.pastdue_days))), NULL)"
    "  AS past_due_days,"
    " t.alert_type, t.action_type,"
    " if(empty(trim(BOTH ' ' FROM ifNull(t.buyer_email, ''))), 'UNASSIGNED',"
    "  trim(BOTH ' ' FROM ifNull(t.buyer_email, ''))) AS buyer_owner,"
    " ifNull(m.primary_owner, 'UNASSIGNED') AS primary_owner,"
    " ifNull(m.manager_owner, 'UNASSIGNED') AS manager_owner,"
    " now('America/Los_Angeles') AS queried_at_utc"
    " FROM past_due.past_due_detail_view AS t"
    " LEFT JOIN mapped AS m ON t.country_code = m.country_code"
    "  AND toInt64(t.from_acct_no) = m.vendor_code"
    " WHERE t.country_code = :country_code AND t.from_acct_no IS NOT NULL"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:vendor_code_filter, '')))"
    "  OR toString(toInt64(t.from_acct_no)) = trim(BOTH ' ' FROM :vendor_code_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:eta_code_filter, '')))"
    "  OR upper(trim(BOTH ' ' FROM ifNull(t.eta_code, '')))"
    "     = upper(trim(BOTH ' ' FROM :eta_code_filter)))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:alert_type_filter, '')))"
    "  OR t.alert_type = trim(BOTH ' ' FROM :alert_type_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:is_pastdue_filter, '')))"
    "  OR toString(toInt64(t.is_pastdue)) = trim(BOTH ' ' FROM :is_pastdue_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:risk_level_filter, '')))"
    "  OR t.risk_level = trim(BOTH ' ' FROM :risk_level_filter))"
    " AND (empty(trim(BOTH ' ' FROM ifNull(:department_type_filter, '')))"
    "  OR empty(trim(BOTH ' ' FROM ifNull(:master_primary_filter, '')))"
    "  OR empty(trim(BOTH ' ' FROM ifNull(:master_manager_filter, '')))"
    "  OR m.vendor_code IS NOT NULL)"
)


def inject_once(text: str, needle: str, insertion: str, *, after: bool = True) -> str:
    if insertion.strip()[:40] in text and MARKER in text and "pastdue.current-detail-query" in text:
        return text
    idx = text.find(needle)
    if idx < 0:
        raise SystemExit(f"anchor not found: {needle[:80]!r}")
    if after:
        at = idx + len(needle)
        return text[:at] + insertion + text[at:]
    return text[:idx] + insertion + text[idx:]


def main() -> int:
    text = EDN.read_text(encoding="utf-8")
    if MARKER in text and "pastdue.current-detail-query" in text:
        print("already patched; skip")
        return 0

    # 1) graph entity list
    text = inject_once(
        text,
        '"pastdue.current-high-risk-line"\n',
        '          "pastdue.current-vendor-past-due-rank"\n'
        '          "pastdue.current-vendor-rank-query"\n'
        '          "pastdue.current-vendor-alert-type-count"\n'
        '          "pastdue.current-alert-type-query"\n'
        '          "pastdue.current-detail-line"\n'
        '          "pastdue.current-detail-query"\n',
    )

    # 2) resolver names list
    text = inject_once(
        text,
        '"pastdue_current_high_risk_lines_by_vendor"\n',
        '          "pastdue_current_vendor_past_due_ranks"\n'
        '          "pastdue_current_vendor_alert_type_counts"\n'
        '          "pastdue_current_detail_matching_lines"\n',
    )

    # 3) vocabulary entities — before closing of :entities map `}\n  :attributes`
    vocab = r'''
   "pastdue.current-vendor-past-due-rank"
   #:entity{:label "PastDue Current Vendor Past-Due Rank",
            :identity
            [:pastdue.current-vendor-past-due-rank/country-code
             :pastdue.current-vendor-past-due-rank/vendor-code],
            :attributes
            [:pastdue.current-vendor-past-due-rank/country-code
             :pastdue.current-vendor-past-due-rank/vendor-code
             :pastdue.current-vendor-past-due-rank/vendor-name
             :pastdue.current-vendor-past-due-rank/vendor-total-count
             :pastdue.current-vendor-past-due-rank/vendor-pastdue-count
             :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor
             :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total
             :pastdue.current-vendor-past-due-rank/rank
             :pastdue.current-vendor-past-due-rank/queried-at-utc],
            :description
            "Current-state vendor past-due share rank from past_due_detail_view; org filter via vendor_master_mapping_view only. Not historical snapshot attribution."},
   "pastdue.current-vendor-rank-query"
   #:entity{:label "PastDue Current Vendor Rank Query",
            :identity
            [:pastdue.current-vendor-rank-query/country-code
             :pastdue.current-vendor-rank-query/department-type-filter
             :pastdue.current-vendor-rank-query/master-primary-filter
             :pastdue.current-vendor-rank-query/master-manager-filter],
            :attributes
            [:pastdue.current-vendor-rank-query/country-code
             :pastdue.current-vendor-rank-query/department-type-filter
             :pastdue.current-vendor-rank-query/master-primary-filter
             :pastdue.current-vendor-rank-query/master-manager-filter
             :pastdue.current-vendor-rank-query/matching-ranks],
            :description
            "Filter request for current vendor past-due ranks. Empty-string org filters mean no constraint on that dimension; vendors must appear in vendor_master_mapping_view for the country."},
   "pastdue.current-vendor-alert-type-count"
   #:entity{:label "PastDue Current Vendor Alert-Type Count",
            :identity
            [:pastdue.current-vendor-alert-type-count/country-code
             :pastdue.current-vendor-alert-type-count/vendor-code
             :pastdue.current-vendor-alert-type-count/alert-type],
            :attributes
            [:pastdue.current-vendor-alert-type-count/country-code
             :pastdue.current-vendor-alert-type-count/vendor-code
             :pastdue.current-vendor-alert-type-count/vendor-name
             :pastdue.current-vendor-alert-type-count/alert-type
             :pastdue.current-vendor-alert-type-count/line-count
             :pastdue.current-vendor-alert-type-count/queried-at-utc],
            :description
            "Current-state line counts by vendor and alert_type from past_due_detail_view."},
   "pastdue.current-alert-type-query"
   #:entity{:label "PastDue Current Alert-Type Query",
            :identity
            [:pastdue.current-alert-type-query/country-code
             :pastdue.current-alert-type-query/vendor-code-filter
             :pastdue.current-alert-type-query/department-type-filter
             :pastdue.current-alert-type-query/master-primary-filter
             :pastdue.current-alert-type-query/master-manager-filter],
            :attributes
            [:pastdue.current-alert-type-query/country-code
             :pastdue.current-alert-type-query/vendor-code-filter
             :pastdue.current-alert-type-query/department-type-filter
             :pastdue.current-alert-type-query/master-primary-filter
             :pastdue.current-alert-type-query/master-manager-filter
             :pastdue.current-alert-type-query/matching-counts],
            :description
            "Filter request for current vendor×alert_type counts. Empty filters mean no constraint."},
   "pastdue.current-detail-line"
   #:entity{:label "PastDue Current Detail Line",
            :identity
            [:pastdue.current-detail-line/order-type
             :pastdue.current-detail-line/po-number
             :pastdue.current-detail-line/po-line-number],
            :attributes
            [:pastdue.current-detail-line/country-code
             :pastdue.current-detail-line/vendor-code
             :pastdue.current-detail-line/vendor-name
             :pastdue.current-detail-line/order-type
             :pastdue.current-detail-line/po-number
             :pastdue.current-detail-line/po-line-number
             :pastdue.current-detail-line/sku-number
             :pastdue.current-detail-line/ordered-quantity
             :pastdue.current-detail-line/shipped-quantity
             :pastdue.current-detail-line/received-quantity
             :pastdue.current-detail-line/eta-code
             :pastdue.current-detail-line/eta-date
             :pastdue.current-detail-line/eta-source
             :pastdue.current-detail-line/risk-level
             :pastdue.current-detail-line/is-past-due
             :pastdue.current-detail-line/past-due-days
             :pastdue.current-detail-line/alert-type
             :pastdue.current-detail-line/action-type
             :pastdue.current-detail-line/buyer-owner
             :pastdue.current-detail-line/primary-owner
             :pastdue.current-detail-line/manager-owner
             :pastdue.current-detail-line/queried-at-utc],
            :description
            "Current detail-view PO line slice; Buyer from detail buyer_email; Primary/Manager from vendor_master_mapping_view only."},
   "pastdue.current-detail-query"
   #:entity{:label "PastDue Current Detail Query",
            :identity
            [:pastdue.current-detail-query/country-code
             :pastdue.current-detail-query/vendor-code-filter
             :pastdue.current-detail-query/eta-code-filter
             :pastdue.current-detail-query/alert-type-filter
             :pastdue.current-detail-query/is-pastdue-filter
             :pastdue.current-detail-query/risk-level-filter
             :pastdue.current-detail-query/department-type-filter
             :pastdue.current-detail-query/master-primary-filter
             :pastdue.current-detail-query/master-manager-filter],
            :attributes
            [:pastdue.current-detail-query/country-code
             :pastdue.current-detail-query/vendor-code-filter
             :pastdue.current-detail-query/eta-code-filter
             :pastdue.current-detail-query/alert-type-filter
             :pastdue.current-detail-query/is-pastdue-filter
             :pastdue.current-detail-query/risk-level-filter
             :pastdue.current-detail-query/department-type-filter
             :pastdue.current-detail-query/master-primary-filter
             :pastdue.current-detail-query/master-manager-filter
             :pastdue.current-detail-query/matching-lines],
            :description
            "Controlled current detail-view slice. Empty-string filters mean no constraint. Not free SQL."},
'''
    # Insert before the closing `}\n  :attributes` of vocabulary entities
    anchor = '            "Country-level concentration of vendors by department / primary / manager from vendor_master_mapping_view."},\n}'
    if anchor not in text:
        raise SystemExit("vocab entities close anchor not found")
    text = text.replace(anchor, '            "Country-level concentration of vendors by department / primary / manager from vendor_master_mapping_view."},' + vocab + "}", 1)

    # 4) attribute definitions — before close of vocabulary :attributes
    attr_block = r'''
   :pastdue.current-vendor-rank-query/country-code
   #:attribute{:label "Country Code", :type :string, :nullable? false, :description "Country for current rank query.", :format :code},
   :pastdue.current-vendor-rank-query/department-type-filter
   #:attribute{:label "Department Type Filter", :type :string, :nullable? false, :description "Optional; empty string means no filter.", :format :text},
   :pastdue.current-vendor-rank-query/master-primary-filter
   #:attribute{:label "Master Primary Filter", :type :string, :nullable? false, :description "Optional; empty string means no filter.", :format :text},
   :pastdue.current-vendor-rank-query/master-manager-filter
   #:attribute{:label "Master Manager Filter", :type :string, :nullable? false, :description "Optional; empty string means no filter.", :format :text},
   :pastdue.current-vendor-rank-query/matching-ranks
   #:attribute{:label "Matching Ranks", :type :ref, :nullable? false,
               :description "Current vendor past-due ranks matching country and optional org filters.",
               :target "pastdue.current-vendor-past-due-rank", :cardinality :many},
   :pastdue.current-vendor-past-due-rank/country-code
   #:attribute{:label "Country Code", :type :string, :nullable? false, :format :code},
   :pastdue.current-vendor-past-due-rank/vendor-code
   #:attribute{:label "Vendor Code", :type :integer, :nullable? false, :format :id},
   :pastdue.current-vendor-past-due-rank/vendor-name
   #:attribute{:label "Vendor Name", :type :string, :nullable? false, :format :text},
   :pastdue.current-vendor-past-due-rank/vendor-total-count
   #:attribute{:label "Vendor Total Line Count", :type :integer, :nullable? false, :format :number},
   :pastdue.current-vendor-past-due-rank/vendor-pastdue-count
   #:attribute{:label "Vendor Past-Due Line Count", :type :integer, :nullable? false, :format :number},
   :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor
   #:attribute{:label "Past-Due Rate In Vendor", :type :decimal, :nullable? false, :unit "%", :format :percent},
   :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total
   #:attribute{:label "Past-Due Rate In Country Total", :type :decimal, :nullable? false, :unit "%", :format :percent},
   :pastdue.current-vendor-past-due-rank/rank
   #:attribute{:label "Rank", :type :integer, :nullable? false, :format :number},
   :pastdue.current-vendor-past-due-rank/queried-at-utc
   #:attribute{:label "Queried At PST", :type :datetime, :nullable? false, :format :datetime},
   :pastdue.current-alert-type-query/country-code
   #:attribute{:label "Country Code", :type :string, :nullable? false, :format :code},
   :pastdue.current-alert-type-query/vendor-code-filter
   #:attribute{:label "Vendor Code Filter", :type :string, :nullable? false, :description "Optional; empty means all vendors.", :format :text},
   :pastdue.current-alert-type-query/department-type-filter
   #:attribute{:label "Department Type Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-alert-type-query/master-primary-filter
   #:attribute{:label "Master Primary Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-alert-type-query/master-manager-filter
   #:attribute{:label "Master Manager Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-alert-type-query/matching-counts
   #:attribute{:label "Matching Counts", :type :ref, :nullable? false,
               :target "pastdue.current-vendor-alert-type-count", :cardinality :many},
   :pastdue.current-vendor-alert-type-count/country-code
   #:attribute{:label "Country Code", :type :string, :nullable? false, :format :code},
   :pastdue.current-vendor-alert-type-count/vendor-code
   #:attribute{:label "Vendor Code", :type :integer, :nullable? false, :format :id},
   :pastdue.current-vendor-alert-type-count/vendor-name
   #:attribute{:label "Vendor Name", :type :string, :nullable? false, :format :text},
   :pastdue.current-vendor-alert-type-count/alert-type
   #:attribute{:label "Alert Type", :type :string, :nullable? false, :format :code},
   :pastdue.current-vendor-alert-type-count/line-count
   #:attribute{:label "Line Count", :type :integer, :nullable? false, :format :number},
   :pastdue.current-vendor-alert-type-count/queried-at-utc
   #:attribute{:label "Queried At PST", :type :datetime, :nullable? false, :format :datetime},
   :pastdue.current-detail-query/country-code
   #:attribute{:label "Country Code", :type :string, :nullable? false, :format :code},
   :pastdue.current-detail-query/vendor-code-filter
   #:attribute{:label "Vendor Code Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/eta-code-filter
   #:attribute{:label "ETA Code Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/alert-type-filter
   #:attribute{:label "Alert Type Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/is-pastdue-filter
   #:attribute{:label "Is Past Due Filter", :type :string, :nullable? false, :description "Empty, 1, or 0.", :format :text},
   :pastdue.current-detail-query/risk-level-filter
   #:attribute{:label "Risk Level Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/department-type-filter
   #:attribute{:label "Department Type Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/master-primary-filter
   #:attribute{:label "Master Primary Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/master-manager-filter
   #:attribute{:label "Master Manager Filter", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-query/matching-lines
   #:attribute{:label "Matching Lines", :type :ref, :nullable? false,
               :target "pastdue.current-detail-line", :cardinality :many},
   :pastdue.current-detail-line/country-code
   #:attribute{:label "Country Code", :type :string, :nullable? false, :format :code},
   :pastdue.current-detail-line/vendor-code
   #:attribute{:label "Vendor Code", :type :integer, :nullable? false, :format :id},
   :pastdue.current-detail-line/vendor-name
   #:attribute{:label "Vendor Name", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-line/order-type
   #:attribute{:label "Order Type", :type :integer, :nullable? false, :format :code},
   :pastdue.current-detail-line/po-number
   #:attribute{:label "PO Number", :type :integer, :nullable? false, :format :id},
   :pastdue.current-detail-line/po-line-number
   #:attribute{:label "PO Line Number", :type :integer, :nullable? false, :format :id},
   :pastdue.current-detail-line/sku-number
   #:attribute{:label "SKU Number", :type :integer, :nullable? true, :format :id},
   :pastdue.current-detail-line/ordered-quantity
   #:attribute{:label "Ordered Quantity", :type :integer, :nullable? true, :format :number},
   :pastdue.current-detail-line/shipped-quantity
   #:attribute{:label "Shipped Quantity", :type :integer, :nullable? true, :format :number},
   :pastdue.current-detail-line/received-quantity
   #:attribute{:label "Received Quantity", :type :integer, :nullable? true, :format :number},
   :pastdue.current-detail-line/eta-code
   #:attribute{:label "ETA Code", :type :string, :nullable? false, :format :code},
   :pastdue.current-detail-line/eta-date
   #:attribute{:label "ETA Date", :type :datetime, :nullable? true, :format :datetime},
   :pastdue.current-detail-line/eta-source
   #:attribute{:label "ETA Source", :type :string, :nullable? false, :format :code},
   :pastdue.current-detail-line/risk-level
   #:attribute{:label "Risk Level", :type :string, :nullable? false, :format :code},
   :pastdue.current-detail-line/is-past-due
   #:attribute{:label "Is Past Due", :type :boolean, :nullable? false},
   :pastdue.current-detail-line/past-due-days
   #:attribute{:label "Past Due Days", :type :integer, :nullable? true, :format :number},
   :pastdue.current-detail-line/alert-type
   #:attribute{:label "Alert Type", :type :string, :nullable? false, :format :code},
   :pastdue.current-detail-line/action-type
   #:attribute{:label "Action Type", :type :string, :nullable? true, :format :code},
   :pastdue.current-detail-line/buyer-owner
   #:attribute{:label "Buyer Owner", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-line/primary-owner
   #:attribute{:label "Primary Owner", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-line/manager-owner
   #:attribute{:label "Manager Owner", :type :string, :nullable? false, :format :text},
   :pastdue.current-detail-line/queried-at-utc
   #:attribute{:label "Queried At PST", :type :datetime, :nullable? false, :format :datetime}
'''
    attr_close = (
        '               :description "PST time at which the rollup was queried.",\n'
        '               :format :datetime}},\n\n'
        '  :terms {},'
    )
    attr_close_new = (
        '               :description "PST time at which the rollup was queried.",\n'
        '               :format :datetime},\n'
        + attr_block
        + '},\n\n'
        '  :terms {},'
    )
    if attr_close not in text:
        raise SystemExit("attributes close anchor not found")
    text = text.replace(attr_close, attr_close_new, 1)

    # 5) resolver contracts
    resolver_defs = '''
   "pastdue_current_vendor_past_due_ranks"
   {:label "Current Vendor Past-Due Ranks",
    :description
    "Current-state vendor ranks by share of country past-due lines from detail_view; org via mapping view.",
    :effect :read,
    :input
    [:pastdue.current-vendor-rank-query/country-code
     :pastdue.current-vendor-rank-query/department-type-filter
     :pastdue.current-vendor-rank-query/master-primary-filter
     :pastdue.current-vendor-rank-query/master-manager-filter],
    :output
    [#:pastdue.current-vendor-rank-query{:matching-ranks
                                          [:pastdue.current-vendor-past-due-rank/country-code
                                           :pastdue.current-vendor-past-due-rank/vendor-code
                                           :pastdue.current-vendor-past-due-rank/vendor-name
                                           :pastdue.current-vendor-past-due-rank/vendor-total-count
                                           :pastdue.current-vendor-past-due-rank/vendor-pastdue-count
                                           :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor
                                           :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total
                                           :pastdue.current-vendor-past-due-rank/rank
                                           :pastdue.current-vendor-past-due-rank/queried-at-utc]}],
    :result/cardinality :many,
    :pagination {:default 200, :max 1000},
    :sort
    {:allowed
     [:pastdue.current-vendor-past-due-rank/rank
      :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total
      :pastdue.current-vendor-past-due-rank/vendor-code],
     :default [:pastdue.current-vendor-past-due-rank/rank :asc]}},
   "pastdue_current_vendor_alert_type_counts"
   {:label "Current Vendor Alert-Type Counts",
    :description "Current-state counts by vendor and alert_type from detail_view.",
    :effect :read,
    :input
    [:pastdue.current-alert-type-query/country-code
     :pastdue.current-alert-type-query/vendor-code-filter
     :pastdue.current-alert-type-query/department-type-filter
     :pastdue.current-alert-type-query/master-primary-filter
     :pastdue.current-alert-type-query/master-manager-filter],
    :output
    [#:pastdue.current-alert-type-query{:matching-counts
                                         [:pastdue.current-vendor-alert-type-count/country-code
                                          :pastdue.current-vendor-alert-type-count/vendor-code
                                          :pastdue.current-vendor-alert-type-count/vendor-name
                                          :pastdue.current-vendor-alert-type-count/alert-type
                                          :pastdue.current-vendor-alert-type-count/line-count
                                          :pastdue.current-vendor-alert-type-count/queried-at-utc]}],
    :result/cardinality :many,
    :pagination {:default 500, :max 1000}},
   "pastdue_current_detail_matching_lines"
   {:label "Current Detail Matching Lines",
    :description "Controlled current detail-view line slice with optional filters; DNA from mapping view only.",
    :effect :read,
    :input
    [:pastdue.current-detail-query/country-code
     :pastdue.current-detail-query/vendor-code-filter
     :pastdue.current-detail-query/eta-code-filter
     :pastdue.current-detail-query/alert-type-filter
     :pastdue.current-detail-query/is-pastdue-filter
     :pastdue.current-detail-query/risk-level-filter
     :pastdue.current-detail-query/department-type-filter
     :pastdue.current-detail-query/master-primary-filter
     :pastdue.current-detail-query/master-manager-filter],
    :output
    [#:pastdue.current-detail-query{:matching-lines
                                     [:pastdue.current-detail-line/country-code
                                      :pastdue.current-detail-line/vendor-code
                                      :pastdue.current-detail-line/vendor-name
                                      :pastdue.current-detail-line/order-type
                                      :pastdue.current-detail-line/po-number
                                      :pastdue.current-detail-line/po-line-number
                                      :pastdue.current-detail-line/sku-number
                                      :pastdue.current-detail-line/ordered-quantity
                                      :pastdue.current-detail-line/shipped-quantity
                                      :pastdue.current-detail-line/received-quantity
                                      :pastdue.current-detail-line/eta-code
                                      :pastdue.current-detail-line/eta-date
                                      :pastdue.current-detail-line/eta-source
                                      :pastdue.current-detail-line/risk-level
                                      :pastdue.current-detail-line/is-past-due
                                      :pastdue.current-detail-line/past-due-days
                                      :pastdue.current-detail-line/alert-type
                                      :pastdue.current-detail-line/action-type
                                      :pastdue.current-detail-line/buyer-owner
                                      :pastdue.current-detail-line/primary-owner
                                      :pastdue.current-detail-line/manager-owner
                                      :pastdue.current-detail-line/queried-at-utc]}],
    :result/cardinality :many,
    :pagination {:default 50, :max 200}},
'''
    text = text.replace(
        '\n   "pastdue_current_high_risk_lines_by_vendor"\n',
        resolver_defs + '\n   "pastdue_current_high_risk_lines_by_vendor"\n',
        1,
    )

    # 6) SQL bindings — insert before selection map
    bindings = f'''
   "pastdue_current_vendor_past_due_ranks-sql"
   #:binding{{:for [:resolver "pastdue_current_vendor_past_due_ranks"],
             :source "eta-clickhouse",
             :effect :read,
             :implementation {{:query "{RANK_SQL}"}},
             :input-map
             #:pastdue.current-vendor-rank-query{{:country-code "country_code",
                                                   :department-type-filter "department_type_filter",
                                                   :master-primary-filter "master_primary_filter",
                                                   :master-manager-filter "master_manager_filter"}},
             :output-map
             {{:pastdue.current-vendor-past-due-rank/country-code "country_code",
              :pastdue.current-vendor-past-due-rank/vendor-code "vendor_code",
              :pastdue.current-vendor-past-due-rank/vendor-name "vendor_name",
              :pastdue.current-vendor-past-due-rank/vendor-total-count "vendor_total_count",
              :pastdue.current-vendor-past-due-rank/vendor-pastdue-count "vendor_pastdue_count",
              :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor "pastdue_rate_in_vendor",
              :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total "pastdue_rate_in_total",
              :pastdue.current-vendor-past-due-rank/rank "vendor_rank",
              :pastdue.current-vendor-past-due-rank/queried-at-utc "queried_at_utc",
              :pastdue.current-vendor-rank-query/matching-ranks "matching_ranks"}},
   "pastdue_current_vendor_alert_type_counts-sql"
   #:binding{{:for [:resolver "pastdue_current_vendor_alert_type_counts"],
             :source "eta-clickhouse",
             :effect :read,
             :implementation {{:query "{ALERT_SQL}"}},
             :input-map
             #:pastdue.current-alert-type-query{{:country-code "country_code",
                                                 :vendor-code-filter "vendor_code_filter",
                                                 :department-type-filter "department_type_filter",
                                                 :master-primary-filter "master_primary_filter",
                                                 :master-manager-filter "master_manager_filter"}},
             :output-map
             {{:pastdue.current-vendor-alert-type-count/country-code "country_code",
              :pastdue.current-vendor-alert-type-count/vendor-code "vendor_code",
              :pastdue.current-vendor-alert-type-count/vendor-name "vendor_name",
              :pastdue.current-vendor-alert-type-count/alert-type "alert_type",
              :pastdue.current-vendor-alert-type-count/line-count "line_count",
              :pastdue.current-vendor-alert-type-count/queried-at-utc "queried_at_utc",
              :pastdue.current-alert-type-query/matching-counts "matching_counts"}},
   "pastdue_current_detail_matching_lines-sql"
   #:binding{{:for [:resolver "pastdue_current_detail_matching_lines"],
             :source "eta-clickhouse",
             :effect :read,
             :implementation {{:query "{DETAIL_SQL}"}},
             :input-map
             #:pastdue.current-detail-query{{:country-code "country_code",
                                             :vendor-code-filter "vendor_code_filter",
                                             :eta-code-filter "eta_code_filter",
                                             :alert-type-filter "alert_type_filter",
                                             :is-pastdue-filter "is_pastdue_filter",
                                             :risk-level-filter "risk_level_filter",
                                             :department-type-filter "department_type_filter",
                                             :master-primary-filter "master_primary_filter",
                                             :master-manager-filter "master_manager_filter"}},
             :output-map
             {{:pastdue.current-detail-line/country-code "country_code",
              :pastdue.current-detail-line/vendor-code "vendor_code",
              :pastdue.current-detail-line/vendor-name "vendor_name",
              :pastdue.current-detail-line/order-type "order_type",
              :pastdue.current-detail-line/po-number "po_number",
              :pastdue.current-detail-line/po-line-number "po_line_number",
              :pastdue.current-detail-line/sku-number "sku_number",
              :pastdue.current-detail-line/ordered-quantity "ordered_quantity",
              :pastdue.current-detail-line/shipped-quantity "shipped_quantity",
              :pastdue.current-detail-line/received-quantity "received_quantity",
              :pastdue.current-detail-line/eta-code "eta_code",
              :pastdue.current-detail-line/eta-date "eta_date",
              :pastdue.current-detail-line/eta-source "eta_source",
              :pastdue.current-detail-line/risk-level "risk_level",
              :pastdue.current-detail-line/is-past-due "is_past_due",
              :pastdue.current-detail-line/past-due-days "past_due_days",
              :pastdue.current-detail-line/alert-type "alert_type",
              :pastdue.current-detail-line/action-type "action_type",
              :pastdue.current-detail-line/buyer-owner "buyer_owner",
              :pastdue.current-detail-line/primary-owner "primary_owner",
              :pastdue.current-detail-line/manager-owner "manager_owner",
              :pastdue.current-detail-line/queried-at-utc "queried_at_utc",
              :pastdue.current-detail-query/matching-lines "matching_lines"}},
'''
    sel_anchor = "\n  :selection\n  {[:resolver \"pastdue_country_monitor_all\"]"
    if sel_anchor not in text:
        raise SystemExit("selection anchor not found")
    # bindings live inside previous map — find last binding before :selection
    # Insert bindings before `  :selection`
    text = text.replace("\n  :selection\n", bindings + "\n  :selection\n", 1)

    # 7) selection entries
    text = text.replace(
        '[:resolver "pastdue_current_high_risk_lines_by_vendor"] "pastdue_current_high_risk_lines_by_vendor-sql",\n',
        '[:resolver "pastdue_current_high_risk_lines_by_vendor"] "pastdue_current_high_risk_lines_by_vendor-sql",\n'
        '   [:resolver "pastdue_current_vendor_past_due_ranks"] "pastdue_current_vendor_past_due_ranks-sql",\n'
        '   [:resolver "pastdue_current_vendor_alert_type_counts"] "pastdue_current_vendor_alert_type_counts-sql",\n'
        '   [:resolver "pastdue_current_detail_matching_lines"] "pastdue_current_detail_matching_lines-sql",\n',
        1,
    )

    # 8) ownership entities
    text = text.replace(
        '"pastdue.current-high-risk-line" "REPLACE-WITH-GRAPH-ID",\n',
        '"pastdue.current-high-risk-line" "REPLACE-WITH-GRAPH-ID",\n'
        '   "pastdue.current-vendor-past-due-rank" "REPLACE-WITH-GRAPH-ID",\n'
        '   "pastdue.current-vendor-rank-query" "REPLACE-WITH-GRAPH-ID",\n'
        '   "pastdue.current-vendor-alert-type-count" "REPLACE-WITH-GRAPH-ID",\n'
        '   "pastdue.current-alert-type-query" "REPLACE-WITH-GRAPH-ID",\n'
        '   "pastdue.current-detail-line" "REPLACE-WITH-GRAPH-ID",\n'
        '   "pastdue.current-detail-query" "REPLACE-WITH-GRAPH-ID",\n',
        1,
    )

    EDN.write_text(text, encoding="utf-8")
    print(f"patched {EDN}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

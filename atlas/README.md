# Atlas v3 deployment contract

Authoritative artifact: `charts/pastdue.edn` (Atlas semantic format 2.0;
application release 3.0.0). Load it with the actual environment's approved source
slot configuration. The graph declares logical `eta-clickhouse`; bind it to
`eta-clickhouse-prod` in `sources.edn` according to the deployment platform.
Do not assume physical source IDs and logical slots auto-match.

Set workspace/graph IDs consistently through the platform. `sources.edn` uses
PASTDUE_CLICKHOUSE_JDBC_URL/USERNAME/PASSWORD placeholders. Do not deploy
`charts/source.edn` or `*.attr.edn`; they are reference-only and not validation
sources. No new database objects or ETL required for v3 analysis.

v3 adds monitor-query, history-query, analysis-query (including scope-status),
and current-analysis-query. v2 calendar-day/positive-driver paths remain legacy.
Never pass a weekday Friday/Monday pair to a legacy calendar-nearest validator.
The fixed-pair SQL shares one selection CTE built by development-only
`scripts/build_analysis_contract.py`; no generator runs in production.

All list resolvers have page bounds. Batch queries default to ascending unique
row-ordinal; read all facts and checks. Bucket cardinality and totals are computed
before pagination. Top-5 is presentation rank, not retrieval limit. Code/Source
are separate independent aggregations, not a Cartesian product. US Source uses
actual joint rows, not marginal inference.

Local fixture SQL execution does not prove production ClickHouse analyzer,
Atlas SQL wrapping or JDBC nullable Decimal support. Test config-check, paging,
Decimal serialization, UNION ALL equality joins, permissions and actual Agent
reports before release. Top-level ORDER BY/LIMIT is left to Atlas; SQL window
ordering expresses deterministic calculation, not external pagination.

Current aggregate counts rows in detail_view. Its full-row denominator fixes the
legacy past-due-only constant-one ratio but must be reconciled with the business
view's actual Open semantics before labeling it identical to snapshots. Mapping
is a distinct set and only filters when org restrictions exist. Existing current
Owner, PO and contact sources are unchanged; detail org filtering is repaired.

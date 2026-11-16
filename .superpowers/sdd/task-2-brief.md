### Task 2: `payload-contract.md`

**Files:**
- Create: `skill/pastdue-html-report/references/payload-contract.md`

**Interfaces:**
- Consumes: key names from Task 1
- Produces: documented schema agents must copy when building HTML

- [ ] **Step 1: Write the contract file** covering:
  - Top-level keys: `meta`, `run`, `countries`, `per_country`, `cross_country_summary?`, `scope_gaps`
  - Exact `COUNTRY_COLUMN_KEYS` and `VENDOR_RANKING_KEYS` from Task 1
  - Display formatting: dates `YYYY-MM-DD`; ratios as `100×` with two decimals `%`; contribution pp four decimals; signed nets `+/−/0`; zero-baseline copy rules by pointing to `../pastdue-analysis/references/report-format.md` (do not fork formulas)
  - Gate checklist identical to `gate_payload`
  - Explicit: HTML skill never invents OTHER constituents or recomputes Vendor totals from ETA buckets

- [ ] **Step 2: Verify sample payload still validates**

Run: `python -m unittest tests.test_html_report_payload -v`  
Expected: OK

---


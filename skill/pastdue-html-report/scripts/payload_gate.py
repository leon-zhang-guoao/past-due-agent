REQUIRED_META_KEYS = (
    "mode",
    "language",
    "timezone",
    "calendar-mode",
    "generated-at",
    "metric-version",
)

COUNTRY_COLUMN_KEYS = [
    "country-code",
    "business-status",
    "data-status",
    "previous-snapshot-date",
    "target-snapshot-date",
    "snapshot-gap-days",
    "previous-past-due-count",
    "target-past-due-count",
    "previous-open-count",
    "target-open-count",
    "previous-past-due-ratio",
    "target-past-due-ratio",
    "relative-ratio-change",
    "percentage-point-change",
]

VENDOR_RANKING_KEYS = [
    "contribution-rank",
    "vendor-code",
    "vendor-name",
    "previous-past-due-count",
    "target-past-due-count",
    "past-due-count-change",
    "previous-open-count",
    "target-open-count",
    "previous-vendor-ratio",
    "target-vendor-ratio",
    "delta-country-contribution",
    "positive-contribution-share",
    "main-code-changes",
    "main-source-changes",
    "check-totals",
    "analysis-status",
]


def _blank(value) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def gate_payload(payload: dict) -> tuple[bool, str]:
    meta = payload.get("meta") or {}
    mode = meta.get("mode")
    if mode not in ("DAILY", "TEST", "HISTORICAL", "INTERACTIVE"):
        return False, f"unsupported analysis mode {mode!r}"
    for key in REQUIRED_META_KEYS:
        if key not in meta:
            return False, f"missing meta.{key}"
    if "scope_gaps" not in payload:
        return False, "missing scope_gaps"
    countries = payload.get("countries")
    if not countries:
        return False, "missing countries"
    country_codes = set()
    for row in countries:
        for key in COUNTRY_COLUMN_KEYS:
            if key not in row:
                return False, f"countries row {row.get('country-code')}: missing {key}"
        country_codes.add(row.get("country-code"))
    per = payload.get("per_country") or []
    if not per:
        return False, "missing per_country"
    for block in per:
        code = block.get("country-code")
        if code not in country_codes:
            return False, f"{code}: per_country not in countries"
        for key in ("summary", "coverage"):
            if _blank(block.get(key)):
                return False, f"{code}: missing {key}"
        ranking = block.get("ranking") or []
        if not ranking:
            return False, f"{code}: empty ranking"
        selected = block.get("selected-vendor-codes")
        if not isinstance(selected, list) or not selected:
            return False, f"{code}: empty selected-vendor-codes"
        present = {row.get("vendor-code") for row in ranking}
        for vendor in selected:
            if vendor not in present:
                return False, f"{code}: missing vendor {vendor}"
        for row in ranking:
            for cell in VENDOR_RANKING_KEYS:
                if cell not in row:
                    return False, f"{code}: ranking row {row.get('vendor-code')} missing {cell}"
    return True, "ok"

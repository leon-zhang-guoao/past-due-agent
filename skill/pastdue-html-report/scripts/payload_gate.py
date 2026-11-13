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


def gate_payload(payload: dict) -> tuple[bool, str]:
    meta = payload.get("meta") or {}
    mode = meta.get("mode")
    if mode not in ("DAILY", "TEST"):
        return False, f"mode {mode!r} is not DAILY/TEST"
    for key in REQUIRED_META_KEYS:
        if key not in meta:
            return False, f"missing meta.{key}"
    if "scope_gaps" not in payload:
        return False, "missing scope_gaps"
    if "countries" not in payload or not payload["countries"]:
        return False, "missing countries"
    per = payload.get("per_country") or []
    if not per:
        return False, "missing per_country"
    for block in per:
        selected = block.get("selected-vendor-codes") or []
        ranking = block.get("ranking") or []
        if not ranking:
            return False, f"{block.get('country-code')}: empty ranking"
        present = {row.get("vendor-code") for row in ranking}
        for code in selected:
            if code not in present:
                return False, f"{block.get('country-code')}: missing vendor {code}"
        for row in ranking:
            for cell in (
                "main-code-changes",
                "main-source-changes",
                "check-totals",
                "analysis-status",
            ):
                if cell not in row:
                    return False, f"ranking missing {cell}"
    return True, "ok"

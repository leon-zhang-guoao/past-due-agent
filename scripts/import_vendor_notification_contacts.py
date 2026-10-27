#!/usr/bin/env python3
"""Create and import vendor notification contacts into ClickHouse.

This is an operator/ETL utility, not an Agent runtime dependency. The Agent
must query the imported rows through Atlas and must not execute this script.

Only these CSV columns are read:
  Vendor Number, Vendor Name, ETA Title, Vendor Contact information

All other columns are ignored, including website IDs, URLs, usernames,
passwords, and other credential fields. Raw contact text is never inserted;
only a short redacted evidence snippet around each extracted email is stored.

Example:
  python3 scripts/import_vendor_notification_contacts.py \
    --csv '/path/(US) Vendor ETA Method List.csv' \
    --country-code US \
    --clickhouse-url 'http://host:8123/?database=past_due' \
    --username "$PASTDUE_CLICKHOUSE_USERNAME" \
    --password "$PASTDUE_CLICKHOUSE_PASSWORD"

Use --dry-run to inspect extraction counts without connecting to ClickHouse.
The default import replaces active contacts for the supplied Country. Use
--append when an additive import is explicitly intended.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo


TABLE = "past_due.vendor_eta_notification_contact"
ALLOWED_COLUMNS = {
    "Vendor Number",
    "Vendor Name",
    "ETA Title",
    "Vendor Contact information",
}
EMAIL_RE = re.compile(
    r"(?i)(?<![\w.+-])([a-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    r"(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)"
)
NAME_EMAIL_RE = re.compile(r"(?i)([^<\n;,]{2,100})\s*<\s*([^>\s]+@[^>\s]+)\s*>")
URL_RE = re.compile(r"(?i)\b(?:https?://|www\.)\S+")
SECRET_RE = re.compile(
    r"(?is)\b(?:password|passwd|pwd|user\s*name|username|login|portal\s*id|website\s*id|secret)\s*[:=]?\s*[^\s,;]+"
)
TOKEN_RE = re.compile(r"(?i)\b(?:id|account)\s*[:=]\s*[A-Z0-9._-]+")
EMAIL_LOCAL_RE = re.compile(r"(?i)^(?:no-?reply|do-?not-?reply|mailer-daemon|fax|noreply)")


CREATE_SQL = f"""
CREATE TABLE IF NOT EXISTS {TABLE}
(
    country_code LowCardinality(String),
    vendor_code Int64,
    vendor_name String,
    recipient_type LowCardinality(String),
    email String,
    contact_name Nullable(String),
    source_field LowCardinality(String),
    source_row_number UInt32,
    email_domain LowCardinality(String),
    contact_role_hint LowCardinality(String),
    contact_priority_score Float32,
    is_internal_candidate UInt8,
    is_recommended UInt8,
    selection_reason String,
    evidence_text String,
    source_file_name String,
    source_row_hash FixedString(64),
    is_active UInt8 DEFAULT 1,
    source_system LowCardinality(String),
    record_version UInt64,
    updated_at_utc DateTime('America/Los_Angeles')
)
ENGINE = ReplacingMergeTree(record_version)
ORDER BY (country_code, recipient_type, vendor_code, email)
""".strip()


def clean(value: Any) -> str:
    return "" if value is None else str(value).strip()


def normalize_country(value: str) -> str:
    country = clean(value).upper()
    if not re.fullmatch(r"[A-Z]{2,8}", country):
        raise ValueError("country-code must contain 2-8 ASCII letters, for example US")
    return country


def normalize_vendor_code(value: Any) -> int | None:
    text = clean(value).replace(",", "")
    if not text:
        return None
    if not re.fullmatch(r"\d+", text):
        return None
    return int(text)


def normalized_email(value: str) -> str:
    return clean(value).rstrip(".,;:)]}").lower()


def redact_context(text: str, email: str, start: int, end: int) -> str:
    left = max(0, start - 110)
    right = min(len(text), end + 110)
    context = text[left:right].replace("\r", " ").replace("\n", " ")
    context = URL_RE.sub("[REDACTED_URL]", context)
    context = SECRET_RE.sub("[REDACTED_SECRET]", context)
    context = TOKEN_RE.sub("[REDACTED_ID]", context)
    context = re.sub(r"\s+", " ", context).strip()
    context = context.replace(email, "[EMAIL]").replace(email.lower(), "[EMAIL]")
    if left:
        context = "…" + context
    if right < len(text):
        context += "…"
    return context[:320]


def name_before_email(text: str, email: str, start: int) -> str | None:
    match = None
    for candidate in NAME_EMAIL_RE.finditer(text):
        if normalized_email(candidate.group(2)) == email:
            match = candidate.group(1).strip(" \t\r\n;,:-_")
            break
    if match and not EMAIL_RE.search(match):
        return match[:120]
    prefix = text[max(0, start - 100):start]
    token = re.split(r"[\n;,|]", prefix)[-1].strip(" \t:-_")
    if token and len(token.split()) <= 8 and not re.search(r"(?i)\b(?:email|contact|cc|to|eta|esd)\b\s*$", token):
        return token[:120]
    return None


def classify(email: str, context: str, source_field: str, internal_domains: set[str]) -> tuple[str, int, bool, bool, str]:
    domain = email.rsplit("@", 1)[-1].lower()
    local = email.split("@", 1)[0].lower()
    text = f"{context} {source_field}".lower()
    internal = domain in internal_domains
    score = 40 if source_field == "Vendor Contact information" else 20
    reasons: list[str] = ["contact-info" if source_field == "Vendor Contact information" else "eta-title-fallback"]
    if internal:
        score -= 30
        reasons.append("internal-domain")
    else:
        score += 20
        reasons.append("external-domain")
    if EMAIL_LOCAL_RE.match(local):
        score -= 40
        reasons.append("automated-mailbox")
    if re.search(r"(?i)\b(?:eta|esd|order|sales|customer|support|shipping|ship|availability|vendor|logistics)\b", text):
        score += 15
        role = "VENDOR_CONTACT"
        reasons.append("vendor-context")
    elif re.search(r"(?i)\b(?:cc|buyer|emt|internal|tdsynnex)\b", text):
        score -= 10
        role = "INTERNAL_OR_CC"
        reasons.append("internal-context")
    elif re.search(r"(?i)\b(?:warehouse|receiving|receipt|dock|pod)\b", text):
        role = "WAREHOUSE_CONTEXT"
        reasons.append("warehouse-context")
    else:
        role = "UNKNOWN"
    recommended = score >= 50 and not EMAIL_LOCAL_RE.match(local)
    return role, score, internal, recommended, "; ".join(reasons)


def extract_rows(csv_path: Path, country_code: str, internal_domains: set[str], source_system: str) -> tuple[list[dict[str, Any]], dict[str, int]]:
    candidates: dict[tuple[str, int, str, str], dict[str, Any]] = {}
    stats = defaultdict(int)
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = sorted(ALLOWED_COLUMNS - set(reader.fieldnames or []))
        if missing:
            raise ValueError(f"CSV missing required columns: {missing}")
        for row_number, row in enumerate(reader, start=2):
            stats["rows_read"] += 1
            vendor_code = normalize_vendor_code(row.get("Vendor Number"))
            if vendor_code is None:
                stats["rows_without_numeric_vendor_code"] += 1
                continue
            vendor_name = clean(row.get("Vendor Name"))
            fields = [
                ("Vendor Contact information", clean(row.get("Vendor Contact information"))),
                ("ETA Title", clean(row.get("ETA Title"))),
            ]
            row_found = 0
            for source_field, text in fields:
                for match in EMAIL_RE.finditer(text):
                    email = normalized_email(match.group(1))
                    if not email:
                        continue
                    row_found += 1
                    context = redact_context(text, email, match.start(1), match.end(1))
                    role, score, internal, recommended, reason = classify(email, context, source_field, internal_domains)
                    key = (country_code, vendor_code, "VENDOR", email)
                    existing = candidates.get(key)
                    record = {
                        "country_code": country_code,
                        "vendor_code": vendor_code,
                        "vendor_name": vendor_name,
                        "recipient_type": "VENDOR",
                        "email": email,
                        "contact_name": name_before_email(text, email, match.start(1)),
                        "source_field": source_field,
                        "source_row_number": row_number,
                        "email_domain": email.rsplit("@", 1)[-1],
                        "contact_role_hint": role,
                        "contact_priority_score": float(score),
                        "is_internal_candidate": 1 if internal else 0,
                        "is_recommended": 1 if recommended else 0,
                        "selection_reason": reason,
                        "evidence_text": context,
                        "source_file_name": csv_path.name,
                        "source_row_hash": hashlib.sha256(f"{country_code}|{vendor_code}|{email}".encode()).hexdigest(),
                        "is_active": 1,
                        "source_system": source_system,
                    }
                    if existing is None:
                        candidates[key] = record
                    else:
                        existing["source_field"] = "; ".join(dict.fromkeys((existing["source_field"] + "; " + source_field).split("; ")))
                        existing["source_row_number"] = min(existing["source_row_number"], row_number)
                        existing["contact_priority_score"] = max(existing["contact_priority_score"], record["contact_priority_score"])
                        existing["is_internal_candidate"] = min(existing["is_internal_candidate"], record["is_internal_candidate"])
                        existing["is_recommended"] = max(existing["is_recommended"], record["is_recommended"])
                        existing["selection_reason"] = "; ".join(dict.fromkeys((existing["selection_reason"] + "; " + reason).split("; ")))
                        if len(existing["evidence_text"]) < 300 and context not in existing["evidence_text"]:
                            existing["evidence_text"] = (existing["evidence_text"] + " | " + context)[:320]
                        if not existing.get("contact_name") and record.get("contact_name"):
                            existing["contact_name"] = record["contact_name"]
            if row_found:
                stats["rows_with_email_candidates"] += 1
                stats["email_matches_before_dedup"] += row_found
            else:
                stats["rows_without_email_candidates"] += 1
    stats["unique_email_rows"] = len(candidates)
    stats["recommended_rows"] = sum(r["is_recommended"] for r in candidates.values())
    stats["internal_candidate_rows"] = sum(r["is_internal_candidate"] for r in candidates.values())
    return sorted(candidates.values(), key=lambda r: (r["vendor_code"], -r["is_recommended"], -r["contact_priority_score"], r["email"])), dict(stats)


def clickhouse_request(url: str, query: str, username: str, password: str, body: bytes | None = None) -> str:
    separator = "&" if "?" in url else "?"
    endpoint = url + separator + urllib.parse.urlencode({"query": query})
    # ClickHouse treats HTTP GET as readonly; CREATE/ALTER/INSERT need POST.
    request = urllib.request.Request(endpoint, data=body, method="POST")
    if username:
        token = (username + ":" + password).encode("utf-8")
        import base64
        request.add_header("Authorization", "Basic " + base64.b64encode(token).decode("ascii"))
    if body is not None:
        request.add_header("Content-Type", "application/jsonEachRow")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1000]
        raise RuntimeError(f"ClickHouse HTTP {exc.code}: {detail}") from exc


def import_rows(args: argparse.Namespace, rows: list[dict[str, Any]]) -> None:
    url = args.clickhouse_url or os.environ.get("PASTDUE_CLICKHOUSE_HTTP_URL")
    if not url:
        raise ValueError("--clickhouse-url or PASTDUE_CLICKHOUSE_HTTP_URL is required unless --dry-run is used")
    username = args.username if args.username is not None else os.environ.get("PASTDUE_CLICKHOUSE_USERNAME", "")
    password = args.password if args.password is not None else os.environ.get("PASTDUE_CLICKHOUSE_PASSWORD", "")
    clickhouse_request(url, CREATE_SQL, username, password)
    now = datetime.now(ZoneInfo("America/Los_Angeles"))
    version = int(now.timestamp() * 1000)
    if not args.append:
        country_sql = country_sql_literal(args.country_code)
        # Do not mutate record_version: it is the ReplacingMergeTree version column.
        clickhouse_request(
            url,
            f"ALTER TABLE {TABLE} UPDATE is_active = 0, updated_at_utc = now('America/Los_Angeles') WHERE country_code = {country_sql} AND recipient_type = 'VENDOR' SETTINGS mutations_sync = 1",
            username,
            password,
        )
    payload = []
    for row in rows:
        item = dict(row)
        item["record_version"] = version
        item["updated_at_utc"] = now.strftime("%Y-%m-%d %H:%M:%S")
        payload.append(json.dumps(item, ensure_ascii=False, separators=(",", ":")))
    if payload:
        body = ("\n".join(payload) + "\n").encode("utf-8")
        clickhouse_request(url, f"INSERT INTO {TABLE} FORMAT JSONEachRow", username, password, body)


def country_sql_literal(country: str) -> str:
    return "'" + country.replace("'", "''") + "'"


def main() -> int:
    parser = argparse.ArgumentParser(description="Create/import Vendor notification contacts into ClickHouse")
    parser.add_argument("--csv", required=True, help="contact CSV path")
    parser.add_argument("--country-code", required=True, help="Country code assigned to every imported row")
    parser.add_argument("--clickhouse-url", help="ClickHouse HTTP URL, or PASTDUE_CLICKHOUSE_HTTP_URL")
    parser.add_argument("--username", help="ClickHouse username, or PASTDUE_CLICKHOUSE_USERNAME")
    parser.add_argument("--password", help="ClickHouse password, or PASTDUE_CLICKHOUSE_PASSWORD")
    parser.add_argument("--source-system", default="vendor-eta-contact-csv", help="audit source label")
    parser.add_argument("--internal-domain", action="append", default=[], help="domain treated as internal; repeatable")
    parser.add_argument("--append", action="store_true", help="do not deactivate existing Vendor contacts for this Country")
    parser.add_argument("--dry-run", action="store_true", help="extract and summarize without ClickHouse")
    args = parser.parse_args()
    try:
        args.country_code = normalize_country(args.country_code)
        env_domains = os.environ.get("PASTDUE_INTERNAL_EMAIL_DOMAINS", "tdsynnex.com")
        internal_domains = {x.strip().lower().lstrip("@").rstrip(".") for x in (args.internal_domain + env_domains.split(",")) if x.strip()}
        rows, stats = extract_rows(Path(args.csv), args.country_code, internal_domains, args.source_system)
        print(json.dumps({"country_code": args.country_code, "stats": stats, "dry_run": args.dry_run, "internal_domains_used": sorted(internal_domains)}, ensure_ascii=False, indent=2))
        if not args.dry_run:
            import_rows(args, rows)
            print(f"Imported {len(rows)} deduplicated Vendor contact rows into {TABLE} for {args.country_code}.")
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"contact import failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

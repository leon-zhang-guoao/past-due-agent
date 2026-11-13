"""Deterministic Ops HTML shell renderer for the PastDue HTML companion.

Reformats an already-gated payload for display only. It never re-queries Atlas
or recomputes metrics; formatting follows
``skill/pastdue-analysis/references/report-format.md`` § Shared formatting.
"""
from __future__ import annotations

import argparse
import json
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from payload_gate import COUNTRY_COLUMN_KEYS, VENDOR_RANKING_KEYS, gate_payload  # noqa: E402,F401

# --- i18n -----------------------------------------------------------------

COUNTRY_HEADERS = {
    "zh": [
        "Country", "业务状态", "数据状态", "上期日期", "本期日期", "间隔天数",
        "逾期 上期→本期", "Open 上期→本期", "逾期率 上期→本期", "相对变化", "百分点变化",
    ],
    "en": [
        "Country", "Business status", "Data status", "Previous date", "Target date",
        "Gap days", "Past due previous→target", "Open previous→target",
        "Ratio previous→target", "Relative change", "Point change",
    ],
}

RANKING_HEADERS = {
    "zh": [
        "排名", "Vendor Code／名称", "逾期 上期→本期", "净变化", "Open 上期→本期",
        "Vendor 逾期率 上期→本期", "Country 贡献变化 pp", "正向贡献占比",
        "主要 Code 变化", "主要 Source 变化", "核对", "分析状态",
    ],
    "en": [
        "Rank", "Vendor code / name", "Past due previous→target", "Net change",
        "Open previous→target", "Vendor ratio previous→target",
        "Country contribution change pp", "Positive contribution share",
        "Main Code changes", "Main Source changes", "Check totals", "Analysis status",
    ],
}

LABELS = {
    "zh": {
        "run": "运行信息与结论",
        "country_metrics": "Country 指标",
        "ranking": "Vendor 排名与主要 ETA 变化",
        "summary": "Country Vendor 总结分析",
        "coverage": "覆盖率与限制",
        "cross": "跨 Country 总结",
        "gaps": "执行范围与缺失项",
        "mode": "模式",
        "language": "语言",
        "timezone": "时区",
        "calendar": "日历模式",
        "generated": "生成时间",
        "metric_version": "指标版本",
        "conclusion": "结论",
        "flags": "触发标记",
        "pairs": "上期→本期",
        "no_items": "无缺失项记录",
        "notes": "备注",
        "items": "条目",
        "missing": "未提供",
        "zero_up": "零基线上升",
        "no_change": "无变化",
        "up": "上升",
        "down": "下降",
        "test_title": "[测试]",
        "title": "PastDue 报告",
        "demo_hint": "Demo 动效将在后续任务中提供。",
    },
    "en": {
        "run": "Run information and conclusion",
        "country_metrics": "Country metrics",
        "ranking": "Vendor ranking with main ETA changes",
        "summary": "Country Vendor analysis summary",
        "coverage": "Coverage and limitations",
        "cross": "Cross-Country summary",
        "gaps": "Execution scope and gaps",
        "mode": "Mode",
        "language": "Language",
        "timezone": "Timezone",
        "calendar": "Calendar mode",
        "generated": "Generated at",
        "metric_version": "Metric version",
        "conclusion": "Conclusion",
        "flags": "Trigger flags",
        "pairs": "Previous→target",
        "no_items": "No gap items recorded",
        "notes": "Notes",
        "items": "Items",
        "missing": "Not provided",
        "zero_up": "Zero-baseline increase",
        "no_change": "No change",
        "up": "increase",
        "down": "decrease",
        "test_title": "[TEST]",
        "title": "PastDue report",
        "demo_hint": "Demo motion arrives in a later task.",
    },
}

MINUS = "\u2212"

# --- display formatting helpers ---------------------------------------------


def _missing(lang: str = "zh") -> str:
    return LABELS.get(lang, LABELS["zh"])["missing"]


def fmt_int(value, lang: str = "zh") -> str:
    if value is None:
        return _missing(lang)
    return str(int(value))


def fmt_signed(value, lang: str = "zh") -> str:
    """Signed integer: + for positive, U+2212 for negative, 0 for zero."""
    if value is None:
        return _missing(lang)
    n = int(value)
    if n > 0:
        return f"+{n}"
    if n < 0:
        return f"{MINUS}{abs(n)}"
    return "0"


def _scaled(value, decimals: int, unit: str, threshold: float, signed: bool, lang: str) -> str:
    if value is None:
        return _missing(lang)
    v = float(value) * 100.0
    labels = LABELS.get(lang, LABELS["zh"])
    if v != 0 and round(abs(v), decimals) == 0:
        text = f"<{threshold}{unit}"
        if signed:
            text += f"（{labels['up'] if v > 0 else labels['down']}）" if lang == "zh" else (
                f" ({labels['up'] if v > 0 else labels['down']})"
            )
        return text
    body = f"{abs(v):.{decimals}f}{unit}"
    if v < 0:
        return f"{MINUS}{body}"
    if v > 0 and signed:
        return f"+{body}"
    return body


def fmt_ratio(value, signed: bool = False, lang: str = "zh") -> str:
    """Ratio -> ``xx.xx%`` (100x, two decimals)."""
    return _scaled(value, 2, "%", 0.01, signed, lang)


def fmt_pp(value, signed: bool = False, lang: str = "zh") -> str:
    """Ratio -> ``x.xxxx pp`` (100x, four decimals)."""
    return _scaled(value, 4, " pp", 0.0001, signed, lang)


def fmt_relative(row: dict, lang: str = "zh") -> str:
    """Relative ratio change with zero-baseline copy per report-format.md."""
    labels = LABELS.get(lang, LABELS["zh"])
    value = row.get("relative-ratio-change")
    if value is None:
        prev = row.get("previous-past-due-count")
        tgt = row.get("target-past-due-count")
        if prev == 0 and tgt is not None and tgt > 0:
            return labels["zero_up"]
        if prev == 0 and tgt == 0:
            return labels["no_change"]
        return labels["missing"]
    return fmt_ratio(value, signed=True, lang=lang)


def _pair(a: str, b: str) -> str:
    return f"{a}→{b}"


def _esc(value) -> str:
    """Escape text for HTML; keep newlines visible, never raw table delimiters."""
    if value is None:
        return ""
    return escape(str(value), quote=True).replace("\r\n", "\n").replace("\n", "<br>")


# --- table builders -----------------------------------------------------------


def _table(headers: list[str], rows: list[list[str]], css: str = "") -> str:
    head = "".join(f"<th>{_esc(h)}</th>" for h in headers)
    body = "".join(
        "<tr>" + "".join(f"<td>{cell}</td>" for cell in row) + "</tr>" for row in rows
    )
    cls = f' class="{css}"' if css else ""
    return (
        f'<div class="table-wrap{" scroll-x" if css == "ranking" else ""}">'
        f"<table{cls}><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>"
    )


def _country_row(row: dict, lang: str) -> list[str]:
    return [
        _esc(row["country-code"]),
        _esc(row["business-status"]),
        _esc(row["data-status"]),
        _esc(row["previous-snapshot-date"]),
        _esc(row["target-snapshot-date"]),
        _esc(fmt_int(row["snapshot-gap-days"], lang)),
        _esc(_pair(fmt_int(row["previous-past-due-count"], lang), fmt_int(row["target-past-due-count"], lang))),
        _esc(_pair(fmt_int(row["previous-open-count"], lang), fmt_int(row["target-open-count"], lang))),
        _esc(_pair(fmt_ratio(row["previous-past-due-ratio"], lang=lang), fmt_ratio(row["target-past-due-ratio"], lang=lang))),
        _esc(fmt_relative(row, lang)),
        _esc(fmt_pp(row["percentage-point-change"], signed=True, lang=lang)),
    ]


def _ranking_row(row: dict, lang: str) -> list[str]:
    code_name = f"{_esc(row['vendor-code'])}／{_esc(row['vendor-name'])}"
    return [
        _esc(fmt_int(row["contribution-rank"], lang)),
        code_name,
        _esc(_pair(fmt_int(row["previous-past-due-count"], lang), fmt_int(row["target-past-due-count"], lang))),
        _esc(fmt_signed(row["past-due-count-change"], lang)),
        _esc(_pair(fmt_int(row["previous-open-count"], lang), fmt_int(row["target-open-count"], lang))),
        _esc(_pair(fmt_ratio(row["previous-vendor-ratio"], lang=lang), fmt_ratio(row["target-vendor-ratio"], lang=lang))),
        _esc(fmt_pp(row["delta-country-contribution"], signed=True, lang=lang)),
        _esc(fmt_ratio(row["positive-contribution-share"], lang=lang)),
        _esc(row["main-code-changes"]),
        _esc(row["main-source-changes"]),
        _esc(row["check-totals"]),
        _esc(row["analysis-status"]),
    ]


def _text_block(value) -> str:
    if isinstance(value, str):
        return f"<p>{_esc(value)}</p>"
    return f"<pre>{_esc(json.dumps(value, ensure_ascii=False, indent=2))}</pre>"


def _scope_gaps_html(scope_gaps, labels: dict) -> str:
    if isinstance(scope_gaps, dict):
        items = scope_gaps.get("items") or []
        notes = scope_gaps.get("notes")
        parts = []
        if items:
            lis = "".join(
                f"<li>{_esc(i) if isinstance(i, str) else _esc(json.dumps(i, ensure_ascii=False))}</li>"
                for i in items
            )
            parts.append(f"<ul>{lis}</ul>")
        else:
            parts.append(f'<p class="muted">{_esc(labels["no_items"])}</p>')
        if notes:
            parts.append(f'<p><strong>{_esc(labels["notes"])}:</strong> {_esc(notes)}</p>')
        extra = {k: v for k, v in scope_gaps.items() if k not in ("items", "notes")}
        if extra:
            parts.append(f"<pre>{_esc(json.dumps(extra, ensure_ascii=False, indent=2))}</pre>")
        return "".join(parts)
    return _text_block(scope_gaps)


def _json_for_script(payload: dict) -> str:
    text = json.dumps(payload, ensure_ascii=False)
    # Prevent `</script>`-style substrings from terminating the script element early.
    return text.replace("</", "<\\/")


# --- CSS / JS -------------------------------------------------------------------

CSS = """
:root { --bg:#f7f8fa; --fg:#1b1f24; --muted:#5b6672; --line:#d6dbe1; --accent:#1f5fbf; --card:#fff; --warn:#b45309; }
* { box-sizing: border-box; }
body { margin:0; font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; background:var(--bg); color:var(--fg); line-height:1.5; }
header.top { position: sticky; top: 0; z-index: 20; display:flex; flex-wrap:wrap; gap:.75rem; align-items:center; padding:.6rem 1rem; background:var(--card); border-bottom:1px solid var(--line); }
header.top h1 { font-size:1.05rem; margin:0; }
.badge { display:inline-block; padding:.1rem .55rem; border-radius:999px; background:var(--accent); color:#fff; font-size:.8rem; }
.badge.test { background:var(--warn); }
.pt { font-size:.85rem; color:var(--muted); }
.toggle button { border:1px solid var(--line); background:#fff; padding:.25rem .7rem; cursor:pointer; font:inherit; font-size:.85rem; }
.toggle button:first-child { border-radius:6px 0 0 6px; }
.toggle button:last-child { border-radius:0 6px 6px 0; border-left:0; }
body[data-mode="ops"] .toggle button[data-set-mode="ops"], body[data-mode="demo"] .toggle button[data-set-mode="demo"] { background:var(--accent); color:#fff; border-color:var(--accent); }
nav.toc { margin-left:auto; font-size:.85rem; }
nav.toc a { margin-left:.6rem; color:var(--accent); text-decoration:none; }
main { max-width: 1400px; margin: 0 auto; padding: 1rem; }
section { background:var(--card); border:1px solid var(--line); border-radius:8px; padding:1rem; margin-bottom:1rem; scroll-margin-top:4rem; }
section h2 { margin:.1rem 0 .6rem; font-size:1.1rem; }
.table-wrap { overflow:auto; max-height:70vh; }
.table-wrap.scroll-x { overflow-x:auto; }
table { border-collapse: collapse; width:100%; font-size:.88rem; }
th, td { border:1px solid var(--line); padding:.35rem .55rem; text-align:left; vertical-align:top; }
thead th { position: sticky; top: 0; background:#eef1f5; z-index:5; white-space:nowrap; }
table.ranking td { min-width:6rem; }
table.ranking td:nth-last-child(-n+4) { min-width:14rem; }
.muted { color:var(--muted); }
dl.meta { display:grid; grid-template-columns: max-content 1fr; gap:.2rem 1rem; margin:0 0 .6rem; font-size:.9rem; }
dl.meta dt { color:var(--muted); }
dl.meta dd { margin:0; }
.demo-note { display:none; color:var(--muted); font-size:.85rem; }
body[data-mode="demo"] .demo-note { display:block; }
/* Demo motion is filled in by a later task; keep reduced-motion safe by default. */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
}
""".strip()

JS = """
(function () {
  var body = document.body;
  // ?mode=ops|demo selects the initial view (default ops).
  var params = new URLSearchParams(location.search);
  function setMode(mode) {
    if (mode !== "ops" && mode !== "demo") { mode = "ops"; }
    body.setAttribute("data-mode", mode);
    var badge = document.getElementById("view-mode");
    if (badge) { badge.textContent = mode.toUpperCase(); }
  }
  setMode(params.get("mode") || "ops");
  // ?t= is reserved for the Demo timeline (later task).
  document.querySelectorAll("[data-set-mode]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var mode = btn.getAttribute("data-set-mode");
      setMode(mode);
      var url = new URL(location.href);
      url.searchParams.set("mode", mode);
      history.replaceState(null, "", url);
    });
  });
})();
""".strip()


# --- main renderer ----------------------------------------------------------------


def _pairs_text(payload: dict) -> str:
    pairs = (payload.get("run") or {}).get("pairs") or {}
    return "；".join(
        f"{code} {p.get('previous-snapshot-date')}→{p.get('target-snapshot-date')}"
        for code, p in pairs.items()
    )


def render_ops_shell(payload: dict) -> str:
    ok, reason = gate_payload(payload)
    if not ok:
        raise ValueError(reason)

    meta = payload["meta"]
    lang = meta.get("language") if meta.get("language") in ("zh", "en") else "zh"
    labels = LABELS[lang]
    run = payload.get("run") or {}
    is_test = meta.get("mode") == "TEST"
    title = f"{labels['test_title']} {labels['title']}" if is_test else labels["title"]
    pairs_text = _pairs_text(payload)

    # Header
    badge_cls = "badge test" if is_test else "badge"
    header = (
        '<header class="top">'
        f"<h1>{_esc(title)}</h1>"
        f'<span class="{badge_cls}" id="run-mode">{_esc(meta["mode"])}</span>'
        f'<span class="badge" id="view-mode">OPS</span>'
        f'<span class="pt" title="P/T">P/T: {_esc(pairs_text)}</span>'
        '<span class="toggle" role="group" aria-label="Ops/Demo">'
        '<button type="button" data-set-mode="ops">Ops</button>'
        '<button type="button" data-set-mode="demo">Demo</button>'
        "</span>"
        '<nav class="toc">'
        f'<a href="#run">{_esc(labels["run"])}</a>'
        f'<a href="#country-metrics">{_esc(labels["country_metrics"])}</a>'
        f'<a href="#scope-gaps">{_esc(labels["gaps"])}</a>'
        "</nav>"
        "</header>"
    )

    sections: list[str] = []

    # #run
    flags = run.get("trigger-flags") or []
    meta_rows = [
        (labels["mode"], meta["mode"]),
        (labels["language"], meta["language"]),
        (labels["timezone"], meta["timezone"]),
        (labels["calendar"], meta["calendar-mode"]),
        (labels["generated"], meta["generated-at"]),
        (labels["metric_version"], meta["metric-version"]),
        (labels["flags"], " / ".join(flags) if flags else "—"),
        (labels["pairs"], pairs_text or "—"),
    ]
    dl = "".join(f"<dt>{_esc(k)}</dt><dd>{_esc(v)}</dd>" for k, v in meta_rows)
    sections.append(
        f'<section id="run"><h2>{_esc(labels["run"])}</h2>'
        f'<dl class="meta">{dl}</dl>'
        f'<p><strong>{_esc(labels["conclusion"])}:</strong> {_esc(run.get("conclusion", ""))}</p>'
        f'<p class="demo-note">{_esc(labels["demo_hint"])}</p>'
        "</section>"
    )

    # #country-metrics
    sections.append(
        f'<section id="country-metrics"><h2>{_esc(labels["country_metrics"])}</h2>'
        + _table(COUNTRY_HEADERS[lang], [_country_row(r, lang) for r in payload["countries"]])
        + "</section>"
    )

    # #country-XX
    for block in payload["per_country"]:
        code = block["country-code"]
        pair = (run.get("pairs") or {}).get(code)
        if not pair:
            row = next((r for r in payload["countries"] if r.get("country-code") == code), {})
            pair = {
                "previous-snapshot-date": row.get("previous-snapshot-date", "—"),
                "target-snapshot-date": row.get("target-snapshot-date", "—"),
            }
        heading = f"{code} — {pair['previous-snapshot-date']}→{pair['target-snapshot-date']}"
        sid = f"country-{_esc(code)}"
        sections.append(
            f'<section id="{sid}" data-country="{_esc(code)}"><h2>{_esc(heading)}</h2>'
            f'<h3>{_esc(labels["ranking"])}</h3>'
            + _table(RANKING_HEADERS[lang], [_ranking_row(r, lang) for r in block["ranking"]], css="ranking")
            + f'<h3>{_esc(labels["summary"])}</h3>{_text_block(block.get("summary", ""))}'
            + f'<h3>{_esc(labels["coverage"])}</h3>{_text_block(block.get("coverage", ""))}'
            + "</section>"
        )

    # #cross-country
    if payload.get("cross_country_summary"):
        sections.append(
            f'<section id="cross-country"><h2>{_esc(labels["cross"])}</h2>'
            + _text_block(payload["cross_country_summary"])
            + "</section>"
        )

    # #scope-gaps
    sections.append(
        f'<section id="scope-gaps" data-key="scope_gaps"><h2>{_esc(labels["gaps"])}</h2>'
        + _scope_gaps_html(payload["scope_gaps"], labels)
        + "</section>"
    )

    return (
        "<!DOCTYPE html>\n"
        f'<html lang="{lang}">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f"<title>{_esc(title)}</title>\n<style>\n{CSS}\n</style>\n</head>\n"
        f'<body data-mode="ops">\n{header}\n<main>\n' + "\n".join(sections) + "\n</main>\n"
        f'<script type="application/json" id="pastdue-report">{_json_for_script(payload)}</script>\n'
        f"<script>\n{JS}\n</script>\n</body>\n</html>\n"
    )


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Render the PastDue Ops HTML shell.")
    p.add_argument("payload_json")
    p.add_argument("-o", "--output", required=True)
    args = p.parse_args(argv)
    payload = json.loads(Path(args.payload_json).read_text(encoding="utf-8"))
    try:
        html = render_ops_shell(payload)
    except ValueError as exc:
        print(f"gate failed: {exc}", file=sys.stderr)
        return 1
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())

import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "skill" / "pastdue-html-report" / "scripts"
sys.path.insert(0, str(SCRIPTS))
from render_ops_shell import (  # noqa: E402
    fmt_int,
    fmt_pp,
    fmt_ratio,
    fmt_signed,
    render_ops_shell,
)

FIX = Path(__file__).resolve().parent / "fixtures"

ZH_COUNTRY_HEADERS = [
    "Country", "业务状态", "数据状态", "上期日期", "本期日期", "间隔天数",
    "逾期 上期→本期", "Open 上期→本期", "逾期率 上期→本期", "相对变化", "百分点变化",
]
ZH_RANKING_HEADERS = [
    "排名", "Vendor Code／名称", "逾期 上期→本期", "净变化", "Open 上期→本期",
    "Vendor 逾期率 上期→本期", "Country 贡献变化 pp", "正向贡献占比",
    "主要 Code 变化", "主要 Source 变化", "核对", "分析状态",
]


def load_valid():
    return json.loads((FIX / "html-report-payload-valid.json").read_text(encoding="utf-8"))


class HtmlRenderTests(unittest.TestCase):
    def test_ops_html_contains_required_sections_and_payload(self):
        html = render_ops_shell(load_valid())
        self.assertIn('id="pastdue-report"', html)
        self.assertIn("ACME", html)
        self.assertIn("scope_gaps", html)
        self.assertIn('data-mode="ops"', html)
        self.assertIn("main-code-changes", html)
        self.assertIn("核对", html)
        self.assertIn("业务状态", html)

    def test_section_ids_and_lang(self):
        html = render_ops_shell(load_valid())
        self.assertIn('<html lang="zh">', html)
        for sid in ("run", "country-metrics", "country-US", "scope-gaps"):
            self.assertIn(f'id="{sid}"', html)
        self.assertNotIn('id="cross-country"', html)

    def test_cross_country_when_present(self):
        payload = load_valid()
        payload["cross_country_summary"] = "US 与 CA 对比摘要"
        html = render_ops_shell(payload)
        self.assertIn('id="cross-country"', html)
        self.assertIn("US 与 CA 对比摘要", html)

    def test_zh_headers_exact_order(self):
        html = render_ops_shell(load_valid())
        for headers in (ZH_COUNTRY_HEADERS, ZH_RANKING_HEADERS):
            pos = -1
            for h in headers:
                nxt = html.find(f"<th>{h}</th>", pos + 1)
                self.assertGreater(nxt, pos, h)
                pos = nxt

    def test_en_headers(self):
        payload = load_valid()
        payload["meta"]["language"] = "en"
        html = render_ops_shell(payload)
        self.assertIn('<html lang="en">', html)
        self.assertIn("<th>Business status</th>", html)
        self.assertIn("<th>Check totals</th>", html)

    def test_embedded_json_roundtrip(self):
        payload = load_valid()
        html = render_ops_shell(payload)
        start = html.index('id="pastdue-report">') + len('id="pastdue-report">')
        end = html.index("</script>", start)
        self.assertEqual(json.loads(html[start:end]), payload)

    def test_embedded_json_escapes_script_close(self):
        payload = load_valid()
        payload["run"]["conclusion"] = "x</script><b>y"
        html = render_ops_shell(payload)
        start = html.index('id="pastdue-report">') + len('id="pastdue-report">')
        end = html.index("</script>", start)
        self.assertEqual(json.loads(html[start:end])["run"]["conclusion"], "x</script><b>y")

    def test_embedded_json_allows_html_comment_in_text(self):
        payload = load_valid()
        payload["run"]["conclusion"] = "note <!--not a comment--> ok"
        html = render_ops_shell(payload)
        start = html.index('id="pastdue-report">') + len('id="pastdue-report">')
        end = html.index("</script>", start)
        script_text = html[start:end]
        parsed = json.loads(script_text)
        self.assertEqual(parsed["run"]["conclusion"], payload["run"]["conclusion"])
        self.assertEqual(parsed, payload)

    def test_embedded_json_escapes_lt_for_comment_and_script_open(self):
        payload = load_valid()
        text = "a <!-- b <script>alert(1)</script> c"
        payload["run"]["conclusion"] = text
        html = render_ops_shell(payload)
        start = html.index('id="pastdue-report">') + len('id="pastdue-report">')
        end = html.index("</script>", start)
        script_text = html[start:end]
        self.assertNotIn("<", script_text)
        self.assertNotIn("<!--", script_text)
        self.assertNotIn("<script", script_text)
        self.assertEqual(json.loads(script_text)["run"]["conclusion"], text)

    def test_html_escapes_text(self):
        payload = load_valid()
        payload["per_country"][0]["ranking"][0]["vendor-name"] = "A<b>&C"
        html = render_ops_shell(payload)
        self.assertIn("A&lt;b&gt;&amp;C", html)

    def test_formatted_values(self):
        html = render_ops_shell(load_valid())
        self.assertIn("1000→1100", html)
        self.assertIn("20.00%→21.57%", html)
        self.assertIn("7.84%", html)
        self.assertIn("1.5686 pp", html)
        self.assertIn("2026-10-22", html)
        self.assertIn("0.4000 pp", html)
        self.assertIn("40.00%", html)
        self.assertIn("+44", html)

    def test_toggle_motion_stub_and_script(self):
        html = render_ops_shell(load_valid())
        self.assertIn("prefers-reduced-motion", html)
        self.assertIn("?mode=", html)
        self.assertIn("data-mode", html)
        self.assertIn("position: sticky", html)

    def test_demo_harness_present(self):
        html = render_ops_shell(load_valid())
        self.assertIn("mode=demo", html)
        self.assertIn("URLSearchParams", html)
        self.assertIn("prefers-reduced-motion", html)
        self.assertIn("__pastdueTimeline", html)
        self.assertIn('get("t")', html)
        self.assertIn('id="demo-play"', html)
        self.assertIn("requestAnimationFrame", html)

    def test_demo_beats_in_order_and_data_not_omitted(self):
        payload = load_valid()
        html = render_ops_shell(payload)
        order = ['id="run"', 'id="country-metrics"', 'id="country-US"',
                 'data-beat="summary"', 'data-beat="coverage"', 'id="scope-gaps"']
        pos = -1
        for marker in order:
            nxt = html.find(marker, pos + 1)
            self.assertGreater(nxt, pos, marker)
            pos = nxt
        # every section is a beat; Demo only reveals, never drops data
        for sid in ("run", "country-metrics", "country-US", "scope-gaps"):
            self.assertRegex(html, rf'<section id="{sid}"[^>]*data-beat=')
        self.assertIn("ACME", html)
        self.assertNotIn("display:none", html.split("<main>")[1].split("</main>")[0])

    def test_reduced_motion_forces_final_state_without_looping(self):
        html = render_ops_shell(load_valid())
        self.assertIn('matchMedia("(prefers-reduced-motion: reduce)")', html)
        self.assertNotIn("repeat: -1", html)
        self.assertNotIn("setInterval", html)
        self.assertNotIn("iteration-count: infinite", html)

    def test_no_isometric_by_default(self):
        html = render_ops_shell(load_valid()).lower()
        self.assertNotIn("isometric", html)

    def test_gate_fail_raises(self):
        bad = json.loads((FIX / "html-report-payload-gate-fail.json").read_text(encoding="utf-8"))
        with self.assertRaises(ValueError):
            render_ops_shell(bad)

    def test_incomplete_payload_with_valid_mode_raises(self):
        payload = load_valid()
        del payload["per_country"][0]["coverage"]
        with self.assertRaises(ValueError):
            render_ops_shell(payload)

    def test_format_helpers(self):
        self.assertEqual(fmt_ratio(0.2), "20.00%")
        self.assertEqual(fmt_ratio(None), "未提供")
        self.assertEqual(fmt_ratio(0.0784, signed=True), "+7.84%")
        self.assertEqual(fmt_ratio(-0.0784, signed=True), "−7.84%")
        self.assertEqual(fmt_ratio(0.00001, signed=True), "<0.01%（上升）")
        self.assertEqual(fmt_ratio(0.00001), "<0.01%")
        self.assertEqual(fmt_ratio(0), "0.00%")
        self.assertEqual(fmt_pp(0.01568627), "1.5686 pp")
        self.assertEqual(fmt_pp(0.0000001), "<0.0001 pp")
        self.assertEqual(fmt_int(12345), "12345")
        self.assertEqual(fmt_signed(44), "+44")
        self.assertEqual(fmt_signed(-3), "−3")
        self.assertEqual(fmt_signed(0), "0")

    def test_cli_writes_file_and_fails_on_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "r.html"
            good = FIX / "html-report-payload-valid.json"
            r = subprocess.run(
                [sys.executable, str(SCRIPTS / "render_ops_shell.py"), str(good), "-o", str(out)],
                capture_output=True, text=True,
            )
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("ACME", out.read_text(encoding="utf-8"))
            bad_out = Path(tmp) / "bad.html"
            bad = FIX / "html-report-payload-gate-fail.json"
            r = subprocess.run(
                [sys.executable, str(SCRIPTS / "render_ops_shell.py"), str(bad), "-o", str(bad_out)],
                capture_output=True, text=True,
            )
            self.assertNotEqual(r.returncode, 0)
            self.assertFalse(bad_out.exists())

    def test_cli_keyerror_exits_1_without_file(self):
        import render_ops_shell as mod
        from unittest import mock
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "k.html"
            good = FIX / "html-report-payload-valid.json"
            stderr = io.StringIO()
            with mock.patch.object(mod, "render_ops_shell", side_effect=KeyError("boom")), \
                    contextlib.redirect_stderr(stderr):
                rc = mod.main([str(good), "-o", str(out)])
            self.assertEqual(rc, 1)
            self.assertIn("boom", stderr.getvalue())
            self.assertFalse(out.exists())

    def test_cli_incomplete_payload_exits_1_without_file(self):
        payload = load_valid()
        del payload["countries"][0]["target-open-count"]
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "p.json"
            src.write_text(json.dumps(payload), encoding="utf-8")
            out = Path(tmp) / "o.html"
            r = subprocess.run(
                [sys.executable, str(SCRIPTS / "render_ops_shell.py"), str(src), "-o", str(out)],
                capture_output=True, text=True,
            )
            self.assertEqual(r.returncode, 1)
            self.assertIn("target-open-count", r.stderr)
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_check_wiki_docs.py — check_wiki_docs.py 的回归夹具（Python 3 标准库 unittest）。

本文件是本校验器**唯一**的回归面：每个用例在临时目录里造一个最小 vault，
直接调 check() / main() 断言违规清单与退出码。跑法：

    python3 -m unittest test_check_wiki_docs.py -v          # 在本目录下
    python3 plugins/visual-report/skills/wiki-docs/scripts/test_check_wiki_docs.py

覆盖面：
  · R1–R5 的冒烟面（干净的样例 vault 必须零违规——防止 R6 改动误伤历史行为）
  · R6 image-outside-vault（作者令 2026-11-05 09:24「图随文档走」）：
      合规相对路径 / 越出 vault 的 ../ / 绝对路径 / file:// / http(s) 外链 /
      vault 内不存在 / 行内豁免（同行 / 上一行 / 空理由不生效）/ 围栏块与
      行内代码样张不算引用 / data: 内联图自包含 / wikilink 内嵌走 Obsidian 语义
  · 端到端退出码：红 → EXIT 1，删掉违规样例 → EXIT 0
  · R7 doc-color / R8 doc-indent / R9 abbrev-expansion（文档排版与缩写批，作者
    裁定 2026-11-05 11:09 / 11:11）：缩写未展开⇒报错 / 已展开⇒通过 / 挂词条
    链接⇒通过 / 允许清单（单位、通用技术词）⇒通过且清空清单后同词报错 /
    围栏代码块内⇒不扫 / 带点缩写⇒报错 / 同档小写词形的大写词⇒不是缩写 /
    彩色超 4 处⇒报错 / 非语义色⇒报错 / 三语义色⇒通过 / 缺段首缩进⇒报错 /
    四空格缩进⇒按代码块陷阱报 / 结构性行与短行⇒豁免 / 存量文档⇒只记
    advisory 不影响退出码 / --strict-style⇒进违规面并改退出码 /
    允许清单确实来自 SKILL.md 且不含领域与机构缩写
"""

import base64
import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "check_wiki_docs.py"

spec = importlib.util.spec_from_file_location("check_wiki_docs", SCRIPT)
MOD = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MOD)

PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/wH+3wAAAABJRU5ErkJggg=="
)

INDEX_MD = """---
type: index
status: open
---

# fixture index

- [[sample#sample]] — the document under test
"""


def write(root, rel, text, binary=False):
    path = Path(root) / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    if binary:
        path.write_bytes(PNG_1PX)
    else:
        path.write_text(text, encoding="utf-8")
    return path


def doc(body, frontmatter="type: evidence-note\nstatus: open"):
    return f"---\n{frontmatter}\n---\n\n# sample\n\n{body}\n"


def validate(root, strict=True, allow=None, advisories=None):
    """按 main() 的 prepare 语义跑一遍 check()，返回全部违规。

    style 面默认 strict=True：夹具文档名（sample.md）没有时间块，走时间界会被判
    存量而只记 advisory——用例要断言「违规」，所以显式全库硬查。"""
    docs, atts = MOD.collect_docs(root, [])
    glossary = next((rel for rel in docs if "glossary" in Path(rel).stem.lower()), "")
    terms = {rel: MOD.terms_of(rel, d) for rel, d in docs.items()}
    term_resolver = MOD.make_term_resolver([t for ts in terms.values() for t in ts])
    resolver = MOD.make_resolver(list(docs.keys()) + atts)
    style_opts = {"enabled": True, "strict": strict, "since": MOD.STYLE_CONTRACT_STAMP,
                  "allow": MOD.load_abbrev_allowlist() if allow is None else allow,
                  "stats": {}}
    return MOD.check(root, docs, resolver, glossary, False,
                     terms_by_rel=terms, resolve_term=term_resolver,
                     style_opts=style_opts, advisories=advisories)


def image_violations(violations):
    return [v for v in violations if v["rule"] == "image-outside-vault"]


def rule_violations(violations, rule):
    return [v for v in violations if v["rule"] == rule]


class Fixture(unittest.TestCase):
    """每个用例一个干净临时 vault：index.md + sample.md（+ 需要时再补文件）。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name) / "docs"
        self.root.mkdir(parents=True)
        write(self.root, "index.md", INDEX_MD)

    def tearDown(self):
        self._tmp.cleanup()

    def run_case(self, body, extra=None, strict=True, allow=None, advisories=None):
        write(self.root, "sample.md", doc(body))
        for rel, text, binary in (extra or []):
            write(self.root, rel, text, binary=binary)
        return validate(self.root, strict=strict, allow=allow, advisories=advisories)

    # ── 基线：合规 vault 零违规（R1–R5 零误伤）───────────────────────
    def test_clean_vault_has_no_violations(self):
        self.assertEqual([], self.run_case("See also [[index#fixture index]]."))

    # ── R6 合规面 ───────────────────────────────────────────────────
    def test_relative_image_inside_vault_passes(self):
        violations = self.run_case("![ok](assets/pic.png)\n\nSee also [[index#fixture index]].",
                                   extra=[("assets/pic.png", "", True)])
        self.assertEqual([], image_violations(violations))

    def test_wikilink_embed_resolves_by_vault_path(self):
        violations = self.run_case("![[attachments/pic.png]]\n\nSee also [[index#fixture index]].",
                                   extra=[("attachments/pic.png", "", True)])
        self.assertEqual([], image_violations(violations))

    def test_data_uri_is_self_contained(self):
        body = '<img src="data:image/png;base64,AAAA">\n\nSee also [[index#fixture index]].'
        self.assertEqual([], image_violations(self.run_case(body)))

    def test_fenced_and_inline_code_samples_are_not_references(self):
        body = (
            "```markdown\n![图](../../reports-evidence/x/y.png)\n```\n\n"
            "语法样张：`![[reports-evidence/x/y.png]]` 与 `![](/abs/pic.png)` 都不是真引用。\n\n"
            "See also [[index#fixture index]]."
        )
        self.assertEqual([], image_violations(self.run_case(body)))

    def test_human_fence_is_content_not_code(self):
        """人面子围栏里的关键图必须被查（条款 ⑤ 的主图就在围栏里）。"""
        body = (
            "```human\n## 印象\n结论一句话。\n\n**关键数字**：1 张图\n"
            "![[key-chart-missing.png]]\n```\n\nSee also [[index#fixture index]]."
        )
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations), "human 围栏里的图不能被当成代码样张跳过")
        self.assertIn("不存在", violations[0]["detail"])

    def test_human_fence_inside_outer_fence_stays_a_sample(self):
        """外层 ```markdown 里的 ```human 模板仍是样张（不能反过来误报）。"""
        body = (
            "~~~~markdown\n```human\n![[key-chart.png]]\n```\n~~~~\n\nSee also [[index#fixture index]]."
        )
        self.assertEqual([], image_violations(self.run_case(body)))

    # ── R6 违规面：四类位置失败 + 死引用 ────────────────────────────
    def test_relative_path_escaping_vault_is_violation(self):
        write(Path(self._tmp.name), "reports-evidence/20261105_demo/overview.png", "", binary=True)
        body = "![](../reports-evidence/20261105_demo/overview.png)\n\nSee also [[index#fixture index]]."
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations))
        self.assertIn("越出 vault", violations[0]["detail"])
        self.assertIn("reports-evidence/", violations[0]["detail"])

    def test_absolute_path_is_violation(self):
        abs_path = str(Path(self._tmp.name) / "reports-evidence/pic.png").replace("\\", "/")
        body = f"![](file://{abs_path})\n\nSee also [[index#fixture index]]."
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations))
        self.assertIn("绝对路径", violations[0]["detail"])

    def test_tilde_path_is_violation(self):
        body = "![x](~/Pictures/pic.png)\n\nSee also [[index#fixture index]]."
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations))
        self.assertIn("家目录", violations[0]["detail"])

    def test_external_url_is_violation(self):
        body = '<img src="https://cdn.example.com/key-chart.png">\n\nSee also [[index#fixture index]].'
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations))
        self.assertIn("外链", violations[0]["detail"])

    def test_missing_file_inside_vault_is_violation(self):
        body = "![[attachments/not-there.png]]\n\nSee also [[index#fixture index]]."
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations))
        self.assertIn("不存在", violations[0]["detail"])

    # ── R6 行内豁免 ─────────────────────────────────────────────────
    def test_inline_exemption_same_line_and_previous_line(self):
        write(Path(self._tmp.name), "reports-evidence/x/pic.png", "", binary=True)
        body = (
            "![](../reports-evidence/x/pic.png) <!-- wiki-img-exempt: 等作者批迁移 -->\n\n"
            "<!-- wiki-img-exempt: 上一行标注同样生效 -->\n"
            "![](../reports-evidence/x/pic.png)\n\n"
            "See also [[index#fixture index]]."
        )
        violations = self.run_case(body)
        self.assertEqual([], image_violations(violations), "行内豁免应放行这两条")
        docs, _atts = MOD.collect_docs(self.root, [])
        total, exempt, orphans = MOD.image_readout(docs)
        self.assertEqual(2, len(exempt), "豁免必须逐条计数")
        self.assertEqual([], orphans)

    def test_empty_exemption_reason_does_not_apply(self):
        write(Path(self._tmp.name), "reports-evidence/x/pic.png", "", binary=True)
        body = "![](../reports-evidence/x/pic.png) <!-- wiki-img-exempt:   -->\n\nSee also [[index#fixture index]]."
        violations = image_violations(self.run_case(body))
        self.assertEqual(1, len(violations), "空理由的豁免不生效")

    def test_dangling_exemption_marker_is_reported(self):
        body = "<!-- wiki-img-exempt: 标错位置了 -->\n\n正文没有图片。\n\nSee also [[index#fixture index]]."
        self.run_case(body)
        docs, _atts = MOD.collect_docs(self.root, [])
        _total, exempt, orphans = MOD.image_readout(docs)
        self.assertEqual([], exempt)
        self.assertEqual(1, len(orphans), "悬空标注必须单列，防止整文件豁免的错觉")

    # ── 端到端退出码（红 → 绿）──────────────────────────────────────
    def run_main(self, *extra):
        argv = sys.argv
        sys.argv = ["check_wiki_docs.py", "--root", str(self.root), *extra]
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                code = MOD.main()
        finally:
            sys.argv = argv
        return code, buf.getvalue()

    def test_exit_codes_red_then_green(self):
        write(Path(self._tmp.name), "reports-evidence/x/pic.png", "", binary=True)
        write(self.root, "sample.md",
              doc("![](../reports-evidence/x/pic.png)\n\nSee also [[index#fixture index]]."))
        code, out = self.run_main()
        self.assertEqual(1, code, out)
        self.assertIn("image-outside-vault", out)
        self.assertIn("图片纪律（R6 image-outside-vault）", out)
        (self.root / "sample.md").unlink()
        write(self.root, "index.md", INDEX_MD.replace("- [[sample#sample]] — the document under test\n", ""))
        code, out = self.run_main()
        self.assertEqual(0, code, out)


class TypographyAndAbbrev(Fixture):
    """R7 彩色 / R8 缩进 / R9 缩写（作者裁定 2026-11-05 11:09 / 11:11）。

    六条必过用例 + 精度面：缩写未展开⇒报错 / 已展开⇒通过 / 单位（允许清单）⇒通过 /
    代码块内⇒不扫 / 彩色超限⇒报错 / 缺缩进⇒报错。"""

    # ── R9 缩写 ─────────────────────────────────────────────────────
    def test_abbrev_first_use_without_expansion_is_flagged(self):
        body = ("&emsp;&emsp;先说结论：GCR 通量随太阳活动周期起伏，这条判断来自多年实测的"
                "同一组口径，值得单独成段记录。\n\nSee also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "abbrev-expansion")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("GCR", hits[0]["detail"])
        self.assertEqual(8, hits[0]["line"], "报的是首次出现的那一行（front-matter/H1 之后第 8 行）")

    def test_abbrev_expanded_in_place_passes(self):
        body = ("&emsp;&emsp;银河宇宙线（GCR）是进入大气层之前的高能粒子流，通量随太阳"
                "活动周期起伏，值得单独成段记录。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "abbrev-expansion"))

    def test_abbrev_via_term_link_passes(self):
        body = ("&emsp;&emsp;[[GCR]]（Galactic Cosmic Rays，银河宇宙线）的通量随太阳活动"
                "周期起伏，值得单独成段记录。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "abbrev-expansion"))

    def test_abbrev_allowlisted_unit_passes_and_flags_without_allowlist(self):
        body = ("&emsp;&emsp;接口返回 JSON 结构，字段名与上游口径逐字一致，不经任何改写，"
                "也不做四舍五入。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "abbrev-expansion"),
                         "允许清单里的通用技术词不要求展开")
        strict_hits = rule_violations(self.run_case(body, allow={}), "abbrev-expansion")
        self.assertEqual(1, len(strict_hits), "清空允许清单后同一个词就该报出来")

    def test_abbrev_inside_code_fence_is_not_scanned(self):
        body = ("&emsp;&emsp;下面的围栏是代码样张，里面的全大写 token 属于代码面，"
                "不是读者需要理解的概念。\n\n```text\nGCR ICRP MC NIEL\n```\n\n"
                "See also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "abbrev-expansion"))

    def test_abbrev_dotted_form_is_flagged(self):
        body = ("&emsp;&emsp;这条记录按 E.C. 编号归档，编号规则来自机构口径，"
                "值得单独说明一句。\n\nSee also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "abbrev-expansion")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("E.C", hits[0]["detail"])

    def test_emphasis_uppercase_word_is_not_an_abbreviation(self):
        body = ("&emsp;&emsp;读数口径：PASS 的条目与 pass 的条目同表，全部通过之后"
                "才进入下一阶段。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "abbrev-expansion"),
                         "同档有小写形态的全大写词是强调，不是缩写")

    def test_allowlist_lives_in_the_skill_file(self):
        allow = MOD.load_abbrev_allowlist()
        self.assertEqual("wiki-docs", MOD.ALLOWLIST_DEFAULT_DOC.parent.name)
        for token in ("JSON", "API", "DPI", "HTTP"):
            self.assertIn(token, allow)
        for token in ("GCR", "ICRP", "IAEA", "CERN", "NIST", "SCD", "DPC", "MC", "NIEL"):
            self.assertNotIn(token, allow, f"{token} 是领域/机构缩写，永远不进允许清单")

    # ── R7 彩色 ─────────────────────────────────────────────────────
    def test_color_over_budget_is_flagged(self):
        body = ("&emsp;&emsp;配色示例："
                '<span style="color:#5e6ad2">结论一</span>、'
                '<span style="color:#d0342c">风险一</span>、'
                '<span style="color:#12874a">通过一</span>、'
                '<span style="color:#5e6ad2">结论二</span>、'
                '<span style="color:#d0342c">风险二</span>——五处颜色。\n\n'
                "See also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "doc-color")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("超出预算", hits[0]["detail"])

    def test_color_off_palette_is_flagged(self):
        body = ('&emsp;&emsp;结论：<span style="color:#ff0000">这个词用错了色</span>。\n\n'
                "See also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "doc-color")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("非语义色", hits[0]["detail"])

    def test_three_semantic_colors_pass(self):
        body = ("&emsp;&emsp;结论：<span style=\"color:#5e6ad2\">判定在此</span>；"
                "<span style=\"color:#d0342c\">风险</span>与"
                "<span style=\"color:#12874a\">已验证</span>各一处。\n\n"
                "See also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "doc-color"))

    # ── R8 缩进 ─────────────────────────────────────────────────────
    def test_missing_indent_is_flagged(self):
        body = ("这一段落本身足够长却没有段首缩进实体，渲染出来是顶格的一整段，读起来"
                "既没有边界感也不好定位，因此要补上两个全角缩进实体。\n\nSee also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "doc-indent")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("未缩进", hits[0]["detail"])

    def test_indented_paragraph_passes(self):
        body = ("&emsp;&emsp;这一段落本身足够长而且有段首缩进实体，渲染出来是缩进两格"
                "的一整段，读起来有边界感也好定位。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "doc-indent"))

    def test_four_space_indent_is_reported_as_code_block(self):
        body = ("    这一行用四个半角空格起行，渲染器会把它整块当成代码块而不是缩进，"
                "等宽字体加灰底，位置也会错。\n\nSee also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "doc-indent")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("代码块", hits[0]["detail"])

    # ── R8 盲区回归（收尾批 2026-11-05：段首两空格起行）──────────────────
    def test_two_space_indented_paragraph_start_is_flagged(self):
        """正例：上一行是空行 ⇒ 本行是段首，两空格起行 = 缩进不足，必须报。

        修复前 `paragraph_starts()` 不看 `prev_blank`，把这类行当成「缩进续行」
        直接吞掉（实测零报错 = 假通过），而两个半角空格在渲染器里被 HTML 折叠，
        视觉上就是顶格——正是 R8 要报的形态。"""
        body = ("  这一行以两个半角空格起行并且写得足够长，用来钉死段首缩进不足必须被报出来，"
                "两个半角空格会被渲染器折叠吃掉，看起来与顶格没有区别。\n\n"
                "See also [[index]].")
        hits = rule_violations(self.run_case(body), "doc-indent")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("未缩进", hits[0]["detail"])

    def test_indented_continuation_and_structural_lines_are_not_flagged(self):
        """反例（同一条判定的另一面）：续行与结构行带缩进不许被误报。

        `prev_blank` 条件只该把「空行之后的段首」收进判定面——空行前的续行
        （上一行非空）与缩进的列表/引用行豁免面必须原样保住，否则修一个盲区
        会造出一批假报。四空格代码块陷阱由上面 `test_four_space_indent_…` 钉死。"""
        body = ("&emsp;&emsp;这一段用实体缩进起行并且写得足够长，用来确认续行判定面真的被走到，"
                "后文续行不以实体起行。\n"
                "  这一行是上一段的续行（两空格起行、上一行非空），属于续行不是段首，不许误报。\n\n"
                "  - 缩进的结构行（列表条目）不套段首缩进规则\n"
                "  > 缩进的引用行同样豁免\n\n"
                "See also [[index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "doc-indent"))

    def test_short_lines_and_structural_lines_are_exempt(self):
        body = ("## 小节标题\n\n- 列表条目不需要缩进\n| 列 | 列 |\n|---|---|\n| 值 | 值 |\n\n"
                "> 引用块自带缩排\n\n短句。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "doc-indent"))

    # ── 存量过渡与退出码 ────────────────────────────────────────────
    def test_legacy_stock_is_advisory_not_violation(self):
        body = ("这一段落本身足够长却没有段首缩进实体，渲染出来是顶格的一整段，读起来"
                "没有边界感也不好定位。\n\nGCR 通量随太阳活动周期起伏。\n\nSee also [[index#fixture index]].")
        advisories = []
        violations = self.run_case(body, strict=False, advisories=advisories)
        style_rules = ("doc-indent", "doc-color", "abbrev-expansion")
        self.assertEqual([], [v for v in violations if v["rule"] in style_rules])
        rules = {a["rule"] for a in advisories}
        self.assertIn("doc-indent", rules)
        self.assertIn("abbrev-expansion", rules)
        self.assertTrue(all(a["scope"] == "legacy" for a in advisories))

    def test_strict_style_flag_drives_the_exit_code(self):
        write(self.root, "sample.md", doc(
            "这一段落本身足够长却没有段首缩进实体，渲染出来是顶格的一整段，读起来既没有"
            "边界感也不好定位。\n\nSee also [[index#fixture index]]."))
        code, out = self.run_main("--strict-style")
        self.assertEqual(1, code, out)
        self.assertIn("doc-indent", out)
        code, out = self.run_main()          # 缺省时间界：存量 → advisory，退出码不变
        self.assertEqual(0, code, out)
        self.assertIn("doc-indent", out, "advisory 也要打印，不许静默")


if __name__ == "__main__":
    unittest.main(verbosity=2)

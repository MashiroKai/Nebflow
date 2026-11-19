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
  · R10 plain-language / R11 doc-structure + R16 detail-fold / R12 image-caption
    （写作风格批 v2，作者令 2026-11-05 12:09；detail-fold 于重号修正批让号到 R16）：禁用词命中⇒报且带建议 /
    行内代码引词表⇒不报 / 词表确实来自 SKILL.md / 缺分点结论⇒报 /
    有加粗结论⇒通过 / 敞开细节超阈值⇒报 / 有折叠块⇒通过 / 缺图注⇒报 /
    图注过长⇒只提示不计违规 / 孤儿文档⇒建议不计违规（图谱面收缩）
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


def doc(body, frontmatter="type: evidence-note\nstatus: open", figure_exempt=True):
    """夹具文档外壳。

    `figure_exempt=True`（缺省）在正文顶部补一行 `<!-- wiki-figure-exempt:
    夹具文档 -->`：R19「必须有图」只该在**它的专门用例**里判这些短文本夹具，
    否则每个断言 R7/R8/R9/R10 的用例都会顺手多出一条 image-required，把断言
    淹掉。要验 R19 的用例显式传 `figure_exempt=False`。"""
    head = "<!-- wiki-figure-exempt: 夹具文档，非图文报告 -->\n\n" if figure_exempt else ""
    return f"---\n{frontmatter}\n---\n\n# sample\n\n{head}{body}\n"


def validate(root, strict=True, allow=None, advisories=None, plainwords=None):
    """按 main() 的 prepare 语义跑一遍 check()，返回全部违规。

    style 面默认 strict=True：夹具文档名（sample.md）没有时间块，走时间界会被判
    存量而只记 advisory——用例要断言「违规」，所以显式全库硬查。"""
    docs, atts = MOD.collect_docs(root, [])
    glossary = next((rel for rel in docs if "glossary" in Path(rel).stem.lower()), "")
    terms = {rel: MOD.terms_of(rel, d) for rel, d in docs.items()}
    term_resolver = MOD.make_term_resolver([t for ts in terms.values() for t in ts])
    resolver = MOD.make_resolver(list(docs.keys()) + atts)
    style_opts = {"enabled": True, "strict": strict, "since": MOD.STYLE_CONTRACT_STAMP,
                  "since_v2": MOD.STYLE_V2_STAMP,
                  "allow": MOD.load_abbrev_allowlist() if allow is None else allow,
                  "plainwords": (MOD.load_plainword_blocklist()
                                 if plainwords is None else plainwords),
                  "fold_threshold": MOD.DETAIL_FOLD_THRESHOLD,
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

    def run_case(self, body, extra=None, strict=True, allow=None, advisories=None,
                 figure_exempt=True):
        write(self.root, "sample.md", doc(body, figure_exempt=figure_exempt))
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
        # 行号 = 正文里那一行的真实行号（front-matter 5 行 + 夹具的
        # `wiki-figure-exempt` 声明 2 行 = 第 10 行）。断言的是「报首次出现处」，
        # 不是某个魔法数字：夹具外壳一改，这里跟着改。
        self.assertEqual(10, hits[0]["line"], "报的是首次出现的那一行（front-matter/H1/夹具声明之后）")

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
                "See also [[index#fixture index]].")
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
                "See also [[index#fixture index]].")
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


class WritingStyleV2(Fixture):
    """R10 平实语言 / R11 结构三层 / R12 图注 + 图谱面收缩（写作风格批 v2）。

    作者令 2026-11-05 12:09。九条用例：禁用词命中⇒报 / 行内代码引用⇒不报 /
    词表确实来自 SKILL.md 且不空 / 缺分点结论⇒报 / 有加粗结论⇒通过 /
    敞开细节超阈值⇒报 / 有折叠块⇒通过 / 缺图注⇒报 / 图注过长⇒只提示不影响退出码 /
    孤儿文档⇒建议不计违规。"""

    # ── R10 平实语言 ────────────────────────────────────────────────
    def test_blocked_word_is_flagged_with_a_suggestion(self):
        body = ("&emsp;&emsp;这次交付的**结论**是先把文档骨架钉死，其余细节按需展开，"
                "读者不用在长段落里找答案。\n\n下面这句话用了一个要读者二次解码的词："
                "报告的人面子要先写完。\n\nSee also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "plain-language")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("人面子", hits[0]["detail"])
        self.assertIn("建议", hits[0]["detail"], "报错必须带替换建议，不能只说『用了禁词』")

    def test_blocked_word_inside_inline_code_is_not_flagged(self):
        body = ("&emsp;&emsp;词表里收了 `印象` 与 `人面子` 这两个词作为反例，"
                "行内代码是在引词谈词表，不是在用它写作。\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "plain-language"))

    def test_plainword_blocklist_lives_in_the_skill_file(self):
        block = MOD.load_plainword_blocklist()
        self.assertTrue(block, "禁用词表必须从 SKILL.md 标记块读出来，不许空表")
        for word in ("人面子", "印象"):
            self.assertIn(word, block, f"{word} 是作者点名的禁词，必须在词表里")
            self.assertTrue(block[word], f"{word} 必须带建议替换")

    # ── R11 结构三层 ────────────────────────────────────────────────
    def test_section_without_bold_conclusion_is_flagged(self):
        body = ("## 结论\n\n这一节开头是一整段平铺直叙的正文，读者要读到句号才知道"
                "作者到底想说什么，因此缺一句加粗的分点结论。\n\nSee also [[index#fixture index]].")
        hits = rule_violations(self.run_case(body), "doc-structure")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("缺分点结论句", hits[0]["detail"])

    def test_section_with_bold_conclusion_passes(self):
        body = ("## 结论\n\n**这一节先把答案写在第一行。**\n\n"
                "论证细节放在折叠块里，读者点开才看到：\n\n"
                "<details><summary>论证细节</summary>\n\n"
                "第一步、第二步、第三步都在这里。\n\n</details>\n\nSee also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "doc-structure"),
                         "加粗一句话开头的章节不该报结构违规")

    def test_unfolded_detail_over_threshold_is_flagged(self):
        para = "这一段的细节没有任何折叠容器包着，" * 40      # >600 可视字符
        body = f"## 结论\n\n**答案在第一行。**\n\n&emsp;&emsp;{para}\n\nSee also [[index#fixture index]]."
        hits = rule_violations(self.run_case(body), "detail-fold")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("折叠块", hits[0]["detail"])

    def test_folded_detail_over_threshold_passes(self):
        para = "这一段的细节被折叠容器包着，" * 40
        body = ("## 结论\n\n**答案在第一行。**\n\n"
                f"<details><summary>论证细节</summary>\n\n&emsp;&emsp;{para}\n\n</details>\n\n"
                "See also [[index#fixture index]].")
        self.assertEqual([], rule_violations(self.run_case(body), "detail-fold"))

    # ── R12 图注 ────────────────────────────────────────────────────
    def test_image_without_caption_is_flagged(self):
        violations = self.run_case("![](assets/pic.png)\n\nSee also [[index#fixture index]].",
                                   extra=[("assets/pic.png", "", True)])
        hits = rule_violations(violations, "image-caption")
        self.assertEqual(1, len(hits), hits)
        self.assertIn("没有图注", hits[0]["detail"])

    def test_image_with_caption_passes_and_long_caption_only_hints(self):
        long_caption = "图 1 " + "这张图说明了舱内中子能谱的加权贡献分布，" * 8
        body = ("![](assets/pic.png)\n" + long_caption + "\n\nSee also [[index#fixture index]].")
        advisories = []
        violations = self.run_case(body, extra=[("assets/pic.png", "", True)],
                                   advisories=advisories)
        self.assertEqual([], rule_violations(violations, "image-caption"),
                         "有图注就不该报缺图注")
        self.assertIn("image-caption-long", {a["rule"] for a in advisories},
                      "图注过长只提示，不进违规清单")
        self.assertTrue(all(a.get("scope") != "graph" for a in advisories))

    def test_caption_line_is_not_treated_as_an_indented_paragraph(self):
        """图注行（`图 N …`）紧贴图片，不该被 R8 当成「未缩进的正文段落」报出来。"""
        body = ("![](assets/pic.png)\n\n图 1 三条曲线累积到 100% 的形状几乎一样，"
                "危害贡献的主体都落在 1–10 MeV 段。\n\nSee also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/pic.png", "", True)])
        self.assertEqual([], rule_violations(violations, "doc-indent"), violations)
        self.assertEqual([], rule_violations(violations, "image-caption"), "图注在位")

    # ── 图谱面收缩：孤儿文档不再计违规 ──────────────────────────────
    def test_orphan_document_is_advisory_not_violation(self):
        write(self.root, "lonely.md", doc("&emsp;&emsp;这一篇既不引用别人也不被引用，"
                                          "按旧制要被当成孤儿文档报违规。"))
        advisories = []
        violations = validate(self.root, strict=True, advisories=advisories)
        self.assertEqual([], rule_violations(violations, "orphan-doc"),
                         "图谱面已收缩：孤儿文档不进违规清单")
        graph = [a for a in advisories if a.get("scope") == "graph"]
        self.assertEqual(["lonely.md"], [a["file"] for a in graph],
                         "孤儿文档仍要打印，只是降为建议")


class FigureRenderBreakpoints(Fixture):
    """R17 bare-name-embed / R18 fence-embed-vault-relative（「图不显示」批 2026-11-05，
    卡片出图批同日反转 R18 方向）。

    真报障：`![[after-states-dark.png]]` 写在 ```human 围栏里，图实际在
    `assets/<batch>/after-states-dark.png`。当时两个断点：`imageCandidates()` 不递归
    子目录（R17），`expandImageEmbeds()` 不展开围栏内嵌入（R18 原义）。
    卡片出图批之后：围栏内嵌图**已放行**（前端展开已闭合围栏正文），R18 改判
    「路径形态」= 必须 vault 相对；两条按位置分片，一行只报一条。"""

    def test_bare_name_for_subdir_file_is_flagged_with_the_path_to_write(self):
        body = ("![[after-states-dark.png]]\n\n"
                "图 1 八个状态同屏。\n\nSee also [[index#fixture index]].")
        violations = self.run_case(
            body, extra=[("assets/batch01/after-states-dark.png", "", True)])
        hits = rule_violations(violations, "bare-name-embed")
        self.assertEqual(1, len(hits), violations)
        self.assertIn("![[assets/batch01/after-states-dark.png]]", hits[0]["detail"],
                      "违规必须给出应写路径（可粘贴）")

    def test_vault_relative_path_with_subdir_passes(self):
        body = ("![[assets/batch01/after-states-dark.png]]\n\n"
                "图 1 八个状态同屏。\n\nSee also [[index#fixture index]].")
        violations = self.run_case(
            body, extra=[("assets/batch01/after-states-dark.png", "", True)])
        self.assertEqual([], rule_violations(violations, "bare-name-embed"), violations)

    def test_bare_name_at_vault_asset_root_is_resolvable_and_passes(self):
        """`assets/x.png` 在渲染器第 ④ 格里，裸名能命中 ⇒ 不该误报。"""
        body = ("![[pic.png]]\n\n图 1 一张图。\n\nSee also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/pic.png", "", True)])
        self.assertEqual([], rule_violations(violations, "bare-name-embed"), violations)

    def test_ambiguous_basename_lists_candidates(self):
        body = ("![[pic.png]]\n\n图 1 一张图。\n\nSee also [[index#fixture index]].")
        violations = self.run_case(body, extra=[
            ("assets/batchA/pic.png", "", True),
            ("assets/batchB/pic.png", "", True),
        ])
        hits = rule_violations(violations, "bare-name-embed")
        self.assertEqual(1, len(hits), violations)
        self.assertIn("assets/batchA/pic.png", hits[0]["detail"])
        self.assertIn("assets/batchB/pic.png", hits[0]["detail"])

    def test_markdown_and_html_syntax_get_matching_suggestions(self):
        body = ("![图 1 说明](pic.png)\n\n<img src=\"pic.png\">\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/batchA/pic.png", "", True)])
        hits = rule_violations(violations, "bare-name-embed")
        details = " ".join(h["detail"] for h in hits)
        self.assertIn("![](assets/batchA/pic.png)", details)
        self.assertIn('<img src="assets/batchA/pic.png">', details)

    def test_image_inside_human_fence_with_vault_relative_path_passes(self):
        """卡片出图批（2026-11-05）：围栏内嵌图**放行**，路径 vault 相对即合规。"""
        body = ("```human\n## 结论\n结论一句话。\n\n图：\n"
                "![[assets/batch01/pic.png]]\n```\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/batch01/pic.png", "", True)])
        self.assertEqual([], rule_violations(violations, "fence-embed-vault-relative"), violations)
        self.assertEqual([], rule_violations(violations, "bare-name-embed"), violations)

    def test_image_inside_human_fence_with_bare_name_is_flagged(self):
        """围栏内**裸名**仍违规：卡片体必须写 vault 相对路径（位置无关的形态）。"""
        body = ("```human\n## 结论\n结论一句话。\n\n图：\n"
                "![[pic.png]]\n```\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/batch01/pic.png", "", True)])
        hits = rule_violations(violations, "fence-embed-vault-relative")
        self.assertEqual(1, len(hits), violations)
        self.assertIn("vault 相对路径", hits[0]["detail"])
        self.assertIn("![[assets/batch01/pic.png]]", hits[0]["detail"], "必须给出应写路径")
        self.assertEqual([], rule_violations(violations, "bare-name-embed"),
                         "围栏内的行只归 R18，R17 不得复述（两条按位置分片）")

    def test_image_inside_human_fence_bare_name_that_resolves_is_still_flagged(self):
        """裸名恰好能解析（图在 `<vault>/assets/`）也要报：卡片体不吃位置依赖。"""
        body = ("```human\n## 结论\n结论一句话。\n\n图：\n"
                "![[pic.png]]\n```\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/pic.png", "", True)])
        hits = rule_violations(violations, "fence-embed-vault-relative")
        self.assertEqual(1, len(hits), violations)
        self.assertIn("能", hits[0]["detail"])
        self.assertIn("![[assets/pic.png]]", hits[0]["detail"])

    def test_image_inside_human_fence_at_vault_root_passes(self):
        """图就在 vault 根、没有更好的目录形态可写 ⇒ 不报（规则不空转）。"""
        body = ("```human\n## 结论\n结论一句话。\n\n图：\n"
                "![[pic.png]]\n```\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("pic.png", "", True)])
        self.assertEqual([], rule_violations(violations, "fence-embed-vault-relative"), violations)

    def test_image_after_the_fence_passes(self):
        body = ("```human\n## 结论\n结论一句话。\n```\n\n"
                "![[assets/batch01/pic.png]]\n\n图 1 一张图。\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/batch01/pic.png", "", True)])
        self.assertEqual([], rule_violations(violations, "fence-embed-vault-relative"), violations)

    def test_fence_and_outside_bare_names_never_double_report(self):
        """同行只归一条：围栏内 → R18，围栏外 → R17（两条不打架）。"""
        body = ("```human\n## 结论\n图 A：\n![[pic.png]]\n```\n\n"
                "![[pic.png]]\n\n图 1 围栏外同一张图。\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body, extra=[("assets/batch01/pic.png", "", True)])
        fence_hits = rule_violations(violations, "fence-embed-vault-relative")
        bare_hits = rule_violations(violations, "bare-name-embed")
        self.assertEqual(1, len(fence_hits), violations)
        self.assertEqual(1, len(bare_hits), violations)
        self.assertNotEqual(fence_hits[0]["line"], bare_hits[0]["line"])
        self.assertLess(fence_hits[0]["line"], bare_hits[0]["line"], "围栏内那条在前面")

    def test_dead_reference_inside_fence_is_reported_once_by_r6_only(self):
        body = ("```human\n## 结论\n图：\n![[nope.png]]\n```\n\n"
                "See also [[index#fixture index]].")
        violations = self.run_case(body)
        self.assertEqual(1, len(image_violations(violations)), violations)
        self.assertEqual([], rule_violations(violations, "bare-name-embed"), violations)
        self.assertEqual([], rule_violations(violations, "fence-embed-vault-relative"), violations)

    def test_dead_reference_is_reported_once_by_r6_only(self):
        """R6 已经报的死引用，R17 不复述（一处只报一次，修完重跑看下一处）。"""
        body = ("![[nope.png]]\n\n图 1 一张图。\n\nSee also [[index#fixture index]].")
        violations = self.run_case(body)
        self.assertEqual(1, len(image_violations(violations)), violations)
        self.assertEqual([], rule_violations(violations, "bare-name-embed"), violations)

    def test_legacy_document_is_advisory_not_violation(self):
        """存量（无时间块的活文档 + 非 strict）只打 advisory，不动退出码。"""
        advisories = []
        violations = self.run_case(
            "![[after-states-dark.png]]\n\n图 1 说明。\n\nSee also [[index#fixture index]].",
            extra=[("assets/batch01/after-states-dark.png", "", True)],
            strict=False, advisories=advisories)
        self.assertEqual([], rule_violations(violations, "bare-name-embed"))
        self.assertIn("bare-name-embed", {a["rule"] for a in advisories})


class FigureFirst(Fixture):
    """R19 image-required / R20 stacked-detail（以图为主批 2026-11-05 21:24）。

    阈值逐字：R19 = 图片引用或 ```mermaid 块至少一处；R20 = 一个 `##` 章节里
    连续 ≥12 行逐条细节（列表项 / 代码围栏内的行；mermaid 与表格不算）且该节
    没有 `<details>`。用例把两个阈值都钉在边界上。"""

    def test_document_without_figure_is_flagged(self):
        violations = self.run_case("&emsp;&emsp;只有散文，没有图。",
                                   figure_exempt=False)
        hits = rule_violations(violations, "image-required")
        self.assertEqual(1, len(hits), violations)
        self.assertIn("wiki-figure-exempt", hits[0]["detail"], "违规要给出例外声明写法")

    def test_declared_figure_exemption_passes(self):
        body = ("<!-- wiki-figure-exempt: 推导型文档，公式逐步变形无法成图 -->\n\n"
                "&emsp;&emsp;只有散文，但已显式声明例外。")
        violations = self.run_case(body, figure_exempt=False)
        self.assertEqual([], rule_violations(violations, "image-required"), violations)

    def test_mermaid_block_counts_as_a_figure(self):
        body = ("```mermaid\ngraph LR\n  A --> B\n```\n\n"
                "&emsp;&emsp;图是代码画的也算图。")
        violations = self.run_case(body, figure_exempt=False)
        self.assertEqual([], rule_violations(violations, "image-required"), violations)

    def _details_run(self, n):
        items = "\n".join(f"- 读数 {i}: EXIT 0" for i in range(n))
        return ("## 一、取证读数\n\n**结论一句话。**\n\n" + items + "\n\n"
                "±0 行\n\nSee also [[index#fixture index]].")

    def test_twelve_unfolded_detail_lines_are_flagged(self):
        violations = self.run_case(self._details_run(12))
        hits = rule_violations(violations, "stacked-detail")
        self.assertEqual(1, len(hits), violations)
        self.assertIn("<details>", hits[0]["detail"])
        self.assertIn("12", hits[0]["detail"])

    def test_eleven_detail_lines_stay_below_the_threshold(self):
        violations = self.run_case(self._details_run(11))
        self.assertEqual([], rule_violations(violations, "stacked-detail"), violations)

    def test_same_lines_inside_details_pass(self):
        items = "\n".join(f"- 读数 {i}: EXIT 0" for i in range(14))
        body = ("## 一、取证读数\n\n**结论一句话。**\n\n<details>\n<summary>过程</summary>\n\n"
                + items + "\n\n</details>\n\nSee also [[index#fixture index]].")
        violations = self.run_case(body)
        self.assertEqual([], rule_violations(violations, "stacked-detail"), violations)

    def test_table_rows_do_not_count_as_stacked_detail(self):
        rows = "\n".join(f"| 行 {i} | 0 |" for i in range(16))
        body = ("## 一、读数表\n\n**结论一句话。**\n\n| 项 | 值 |\n|---|---|\n"
                + rows + "\n\nSee also [[index#fixture index]].")
        violations = self.run_case(body)
        self.assertEqual([], rule_violations(violations, "stacked-detail"),
                         "表格是压缩形态，不算逐条铺散文")

    def test_fold_exemption_declaration_passes(self):
        body = ("<!-- wiki-fold-exempt: 结论本身就是逐条读数，已是最简形态 -->\n\n"
                + self._details_run(14))
        violations = self.run_case(body)
        self.assertEqual([], rule_violations(violations, "stacked-detail"), violations)

    def test_legacy_document_is_advisory_not_violation(self):
        advisories = []
        violations = self.run_case(self._details_run(14), strict=False,
                                   advisories=advisories)
        self.assertEqual([], rule_violations(violations, "stacked-detail"))
        self.assertIn("stacked-detail", {a["rule"] for a in advisories})


class RuleTableHygiene(unittest.TestCase):
    """RULES 表自身的卫生面（重号修正批 2026-11-05）。

    一次真实事故：`detail-fold` 与 `doc-structure` 同时占 R11 落盘，校验器照常
    能跑、夹具照常全绿——因为**没有任何断言看着编号表本身**，重号只在计数汇总
    里表现成两行 `R11 …`。这里把「一个号只准指一条规则」钉成断言：重号 = 红，
    跳号 = 红（跳号要么是笔误，要么必须写成显式豁免才准过）。
    """

    def _ids(self):
        return [rid for rid, _name, _text in MOD.RULES]

    def test_rule_ids_are_unique(self):
        ids = self._ids()
        dup = sorted({i for i in ids if ids.count(i) > 1})
        self.assertEqual([], dup, f"规则号重号（一个号指两条规则）：{dup} —— 重号的那条让号到最大号之后")

    def test_rule_ids_are_ordered_and_contiguous(self):
        ids = self._ids()
        nums = [int(i[1:]) for i in ids]
        self.assertEqual(sorted(nums), nums,
                         f"RULES 表必须按号升序：实际 {ids}（让号后别把新号插回表中间）")
        self.assertEqual(list(range(1, len(nums) + 1)), nums,
                         f"规则号必须 R1..R{len(nums)} 连续无跳号：实际 {ids}"
                         "（确实要留空号，在此处写明理由并把断言改成显式豁免）")

    def test_rule_names_are_unique(self):
        names = [n for _rid, n, _text in MOD.RULES]
        dup = sorted({n for n in names if names.count(n) > 1})
        self.assertEqual([], dup, f"规则重名：{dup}")


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_wiki_docs.py — wiki-docs 文档纪律校验器 + 图谱数据导出器。

wiki-docs SKILL.md 强制条款的机器面（条款与检查一一对应，见 --rules）：

  R1 Obsidian 格式        → 每篇 vault 文档必须带 front-matter（type/status）
  R2 论文式引用关系       → 断链检测（目标不存在 / #标题锚不存在 / ^块锚不存在）；
                            零出链且零入链的孤儿文档（index/glossary 豁免）
  R3 概念首现必释或必引用 → 词表术语在文档首次出现处，既无就地解释（破折号/冒号/
                            括注定义式）又未 [[引用]] 词表 ⇒ 报清单；
                            同一术语在多篇非词表文档各自定义 ⇒ 重复定义清单
  R4 词条注册表面         → 词条声明块结构合法（词表锚段落 / ^term-* 块必须能按
                            「名 — 定义」分隔约定抽出词条名）；同一文档内词条名
                            不得重复；词条标签（v2）缺失 ⇒ term-tag-missing
                            （必须恰好一个 #proj/<slug>、至少一个 #topic/<slug>，
                            其余 #facet/value 入 extra；标签必须写在定义文字之后、
                            ^块锚之前）。`[[名]]` 短形引用在文档与词条注册表均未
                            命中 ⇒ R9 悬空词条（带路径/带锚的断链仍归 R2）
  R5 文档命名制           → 阶段件文件名 = `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`：
                            主题打头、时间块后置（时间打头 = 旧制 = 违规）；topic 不得
                            是 report/doc/notes 这类空词单独成题。校验面 = `.md` 文档；
                            无时间块的活文档（`<topic>.md`）合法；非 `.md` 资产不在本制范围
  R7 doc-reference        → 只许引用词条（作者令 2026-11-05）：正文裸 `.md` 路径、
                            markdown 链接指向文档、无锚短形 `[[X]]` 解析到文档却
                            解析不到已注册词条 —— 都是「引用文档」，一律违规；
                            带锚引用（`[[X#^term-slug]]` / `[[X#Heading]]`）、
                            已注册词条短形 `[[词条名]]`、`![[…]]` 嵌入通过
  R8 bare-image-path      → 图片文件名/路径以纯文本出现在正文（`attachments/a.png`、
                            `../x.jpg`、`/abs/y.png`）即违规；图片要么直接嵌入
                            （`![[a.png]]` / `![](…)` / `<img src=…>`），要么写成
                            可点击链接（`[说明](attachments/a.png)`）
  R9 dangling-term        → 无锚短形 `[[Name]]` 既解析不到文档也解析不到已注册词条
                            ⇒ 悬空（从 R2 broken-link 移出，同一行不双报）；带路径/
                            带锚的断链仍归 R2

三条门禁共用四条纪律：① 输出 `文件:行 → 原文 → 原因`；② 行内豁免
`<!-- wiki-term-exempt: 理由 -->`（同行或紧邻上一行、理由非空才生效）单独计数并
显形（含「标了但该行无违规」的悬空标注），不接受整文件豁免；③ 汇总段出按类计数
小表；④ 有违规即非零退出。

段落锚（引用语法，与 R2 校验同源）：`[[doc#^块锚]]` 精确到单个段落（名词解释
场景主形态，词表条目即块锚段落），`[[doc#标题]]` 精确到整节。--graph 导出
为每篇文档内嵌段落索引 paragraphs（与 WikiIndex.scala 逐字段同构，设计稿
docs/design/wiki-graph-paragraph-ref-design.zh-CN.md §3）：hover 小窗按
edge.anchor 直查目标段落，点击经 markdown 查看器锚点通道跳转高亮。

同一脚本兼任图谱样张/离线分析的数据导出：--graph 按 WikiIndex.scala 的解析语义
（wikilink/markdown 链接、front-matter、块锚、fence 屏蔽）产出 {nodes, edges} JSON。

落位单源（作者令 2026-11-03 20:02）：「项目名 → 文档根」= 项目注册目录（
project.json 所在）下的 `.stand/docs/`，即
`<data_root>/projects/<project>/.stand/docs`——**不落**项目 workspace 仓库的
`docs/`。数据根解析序与引擎 PathUtil.dataRoot 同序：`STAND_HOME` → `NEBFLOW_HOME`
（legacy）→ `~/.stand`。`--root` 显式传参仍然胜出（向后兼容：未迁移项目 /
workspace 内 docs/ 的存量 vault 照旧可校验）。

用法：
  python3 check_wiki_docs.py                             # 校验，根按 --project 解析
                                                         # （默认 stand，见上「落位单源」）
  python3 check_wiki_docs.py --project stand             # 同上，显式项目名
  python3 check_wiki_docs.py --root docs                 # 显式根覆盖（向后兼容）
  python3 check_wiki_docs.py --json                      # 校验，机器读 JSON
  python3 check_wiki_docs.py --graph out.json --project stand
  python3 check_wiki_docs.py --root <vault> --dump-terms --json
                                                         # 词条 + 短形引用的机器出口
                                                         # （下游 CLI / 索引引擎的
                                                         # 唯一抽取入口，形状见下）
  python3 check_wiki_docs.py --include-subvaults
                                                         # 子项目图谱批（#744-B）：
                                                         # 发现 <sub>/docs 子 vault 后
                                                         # 逐 vault 独立跑 R1-R3；
                                                         # 跨 vault 引用 = info 不计违规
  python3 check_wiki_docs.py --rules                     # 打印规则与检查的对照表

退出码：0 = 无违规；1 = 有违规；2 = 用法/环境错误。仅依赖 Python 3 标准库。

词条标签（v2）声明语法（写死，见 SKILL.md「Term tags」）：

  - 词条名（别名1，别名2） — 一句话定义。 #proj/<slug> #topic/<slug> #facet/<value> ^term-<slug>

  · 恰好一个 `#proj/<slug>`；至少一个 `#topic/<slug>`；其余 `#facet/value` 入 extra。
  · 标签必须写在定义文字之后、该段落的 `^块锚` 之前（`^块锚` 必须在行末——写在
    块锚之后的标签不被识别，等价于缺标签）。
  · 行内代码 / 围栏里的 `#x` 不是标签（围栏行本就不入段落，行内代码 span 先剔除）。
  · 缺 project 或缺 topic ⇒ 违规 `term-tag-missing`；行内豁免
    `<!-- wiki-term-exempt: 理由 -->`（同行或紧邻上一行、理由非空）单独计数，
    不接受整文件豁免。

机器出口 `--dump-terms`（下游 CLI / 索引引擎的唯一抽取入口；纯导出、不跑校验、
exit 0）：按 rel,line 排序，形状固定

  {"root":…, "generated_at":…,
   "terms":[{"name","aliases","rel","anchor","line","end_line","definition",
             "source","tags":{"project":[…],"topic":[…],"extra":[…]}}],
   "refs": [{"name_key","from_rel","line","raw","resolved"}]}

`refs` = 所有 `[[名]]` 短形引用（无锚、无路径分隔）；`resolved` = 词条注册表唯一
命中（与 R2 第三优先级回退同一判据），供下游建 term_refs 表。

链接解析语义（与 nebflow.core.wiki.WikiIndex 一致）：先精确 vault 相对路径，再大小写
不敏感精确匹配，再全库唯一 basename 匹配，最后（词条引用批）词条注册表第三优先级
回退——带锚引用不回退；注册表唯一命中（词表命中优先于 ^term-* 声明块命中）= 消解
到「所在文档 + 位置锚」；同级多命中不猜（未解析）。frontmatter `terms:` 声明面
被设计裁定否决（双真源漂移，design 20261029_111029 §4.2-B）——词条真源只有正文块锚。
"""

import argparse
import fnmatch
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path

# ── 与 WikiIndex.scala 同语义的解析正则 ──────────────────────────────

WIKILINK_RE = re.compile(r"(!?)\[\[([^\[\]]+?)\]\]")
MDLINK_RE = re.compile(r"\[([^\]]*)\]\(([^)\s]+\.md(?:#[^\)\s]*)?)\)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
BLOCKID_RE = re.compile(r"^(.*\S)\s+\^([A-Za-z0-9][A-Za-z0-9-]*)\s*$")
FENCE_RE = re.compile(r"^\s*(```+|~~~+)")
FRONTMATTER_KV_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$")

DOC_EXTENSIONS = {".md", ".markdown", ".html"}

# 定义式（就地解释）的形态：术语后紧跟 破折号 / 冒号 / 开括号注解。
DEFINITION_TAIL_RE = re.compile(r"^\s*(?:[—–-]{1,2}|[:：])\s*\S")
DEFINITION_PAREN_RE = re.compile(r"^\s*[（(]\s*\S")

RULES = (
    ("R1", "obsidian-format", "所有文档一律 Obsidian 格式产出：front-matter（type/status）+ [[wiki-link]] 引用 + vault 目录约定（vault 根 = 项目注册目录 .stand/docs、attachments/ 附件）"),
    ("R2", "citation-graph", "文档之间建立论文式引用关系：断链（目标/标题锚/块锚不存在，含引用词条未注册）即缺陷；零出链且零入链的孤儿文档即缺陷（index/glossary 豁免）"),
    ("R3", "concept-first-use", "名词概念第一次出现必须就地解释，或 [[引用]] 已解释它的文档；多篇文档不得各自发明同一概念的定义（词表 = 唯一定义源）"),
    ("R4", "term-registry", "词条注册表面：词表锚段落与 ^term-* 声明块必须按「名 — 定义」分隔约定抽出词条名（词条块结构合法）；同一文档内词条名不得重复；词条标签 v2 = 恰好一个 #proj/<slug> + 至少一个 #topic/<slug> + 其余 #facet/value，写在定义文字之后、^块锚之前，缺 project 或 topic 即 term-tag-missing；`[[名]]` 短形引用经词条注册表消解（词表命中优先，未注册 = R2 断链）"),
    ("R5", "doc-naming", "文档命名制：阶段件文件名 = `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`（topic 前置、时间块后置，`__` 分主题与时间、时间块内用 `_`）；时间打头（`^YYYYMMDD_...`）即旧制即违规；topic 不得是 report/doc/notes 空词单独成题。校验面 = `.md` 文档，无时间块的活文档合法，非 `.md` 资产不在本制范围"),
    ("R7", "doc-reference", "只许引用词条（作者令 2026-11-05「只有引用词条，不允许引用文档」）：正文裸 `.md` 路径（`见 design/foo.md`）、markdown 链接指向文档（`[x](foo.md)` / `[x](foo.md#sec)`）、无锚短形 `[[X]]` 解析到文档却解析不到已注册词条 —— 三种形态都算「引用文档」= 违规；带锚引用（`[[X#^term-slug]]` / `[[X#Heading]]`）、已注册词条短形 `[[词条名]]`、`![[…]]` 嵌入通过。行内代码/围栏不算"),
    ("R8", "bare-image-path", "图片路径不得以纯文本出现：`attachments/a.png`、`../x.jpg`、`/abs/y.png` 写成正文即违规。图片只有两种合法形态——直接嵌入（`![[a.png]]` / `![](…)` / `<img src=…>`）或可点击跳转（`[说明](attachments/a.png)`）。行内代码/围栏不算"),
    ("R9", "dangling-term", "悬空词条引用：无锚短形 `[[Name]]` 既解析不到文档也解析不到已注册词条 ⇒ 违规（该形状从 R2 broken-link 移出，同一行不双报；带路径/带锚的断链仍归 R2/R2 的 broken-anchor / broken-block）"),
)

# ── R5 文档命名制（作者令 2026-11-04 18:12；条款原文见 wiki-docs SKILL.md
# 「Document naming」唯一命名条款）─────────────────────────────────
#
# 合法形态（阶段件）：<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>][.<lang>].md
#   · topic 前置、时间块后置；topic 与时间之间 `__`，时间块内部 `_`
#   · 语言后缀（.zh-CN / .en）整体留在最后
#   · chainId 尾段可缺省，有归属才带
# 合法形态（活文档）：<topic>[.<lang>].md —— 无时间块，不套时间制
# 违规：时间打头的旧制名（本制要消灭的形态）、含时间块但不符新制、topic 为空词
DOC_NAMING_TIME_RE = re.compile(r"(?<!\d)(\d{8})_(\d{6})(?!\d)")
DOC_NAMING_OK_RE = re.compile(
    r"^(?P<topic>.+?)__(?P<time>\d{8}_\d{6})"
    r"(?:__(?P<chain>[A-Za-z0-9][\w.-]*))?"
    r"(?:\.(?:zh-CN|zh-TW|en|ja))?"
    r"\.md$"
)
DOC_NAMING_OLD_RE = re.compile(r"^\d{8}(?:_|\b)")
DOC_NAMING_BLAND_TOPICS = {"report", "doc", "docs", "note", "notes", "tmp", "temp", "untitled", "draft", "stage", "output"}


# ── R7/R8/R9 引用门禁（作者令 2026-11-05：只许引用词条）──────────────────
#
# 作者原话：「以后我们就只允许引用词条，不允许引用文档」「不允许引用文档和图片
# 路径，文档就用词条，图片要么直接嵌入，要么就可以点击跳转」。三条机器面：
#
#   R7 doc-reference    ｜ 正文裸 `.md` 路径 / markdown 链接指向文档 / 无锚短形
#                          `[[X]]` 解析到文档却解析不到已注册词条
#   R8 bare-image-path  ｜ 图片文件名/路径以纯文本出现（不在嵌入/可点击链接里）
#   R9 dangling-term    ｜ 无锚短形 `[[Name]]` 文档与词条双双解析不到（脱离 R2）
#
# 靶场只在**正文残片**上打：围栏行上游已剔（body_lines），行内代码 span、wikilink
# （含 ![[嵌入]]）、markdown 链接/图片（`[x](…)` / `![](…)`）、HTML 标签
# （`<img src>`）、裸 URL 都是合法承载面，先整段剥掉再找裸路径——否则
# `![[a.png]]` 里的 `a.png` 会被误判成 R8。

IMAGE_EXTS = ("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
              "avif", "tif", "tiff", "heic", "apng")

# 路径片段：ASCII 词字符 + 中文（文档里 `见 设计文档.md` 这种中文名同样算路径）。
_PATH_SEG = r"[A-Za-z0-9_\u4e00-\u9fa5.@+~%-]+"
_PATH_PREFIX = r"(?:\.{0,2}/|/|~/)?(?:" + _PATH_SEG + r"/)*"
_FILE_STEM = r"[A-Za-z0-9_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5.@+~%-]*"
# 左边界只挡 ASCII 路径字符：中文紧贴路径（「详见设计/foo.md」）必须仍能命中。
_BOUND_L = r"(?<![A-Za-z0-9_./~%-])"
_BOUND_R = r"(?![A-Za-z0-9_])"

BARE_DOC_PATH_RE = re.compile(
    _BOUND_L + _PATH_PREFIX + _FILE_STEM + r"\.(?:md|markdown)" + _BOUND_R, re.IGNORECASE)
BARE_IMAGE_PATH_RE = re.compile(
    _BOUND_L + _PATH_PREFIX + _FILE_STEM + r"\.(?:" + "|".join(IMAGE_EXTS) + r")" + _BOUND_R,
    re.IGNORECASE)

# 「合法承载面」剥除用：整段构造（连括号一起）抹成空格，剩下的裸文本才是靶场。
WIKILINK_ANY_RE = re.compile(r"!?\[\[[^\[\]\n]*\]\]")
MD_LINK_ANY_RE = re.compile(r"!?\[[^\]\n]*\]\([^)\n]*\)")
HTML_TAG_ANY_RE = re.compile(r"<[^>\n]+>")
URL_ANY_RE = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://\S+")

# 每条门禁每行只报一次（同一行多症会淹没原文；一处修完再跑能看到下一处）。
REF_GATE_MAX_LINE_DETAIL = 200


def is_short_form_ref(target, anchor):
    """无锚短形引用：无 #锚、无路径分隔、无文档扩展名 —— 即 `[[名]]` 这一形状。

    带锚（`[[X#…]]`）与带路径/带扩展名（`[[design/foo.md]]`）的断链不在此列，
    仍归 R2（broken-link / broken-anchor / broken-block）。"""
    if anchor is not None:
        return False
    raw = (target or "").strip()
    if not raw or "/" in raw.replace("\\", "/"):
        return False
    return not re.search(r"\.(?:md|markdown|html)$", raw, re.IGNORECASE)


def mask_legal_carriers(line):
    """正文残片：行内代码 / wikilink（含嵌入）/ markdown 链接与图片 / HTML 标签 /
    裸 URL 全部抹成空格。只剩「没有被任何合法语法承载的裸文本」。"""
    text = INLINE_CODE_SPAN_RE.sub(" ", line or "")
    text = WIKILINK_ANY_RE.sub(" ", text)
    text = MD_LINK_ANY_RE.sub(" ", text)
    text = HTML_TAG_ANY_RE.sub(" ", text)
    text = URL_ANY_RE.sub(" ", text)
    return text


def clip_line(text):
    """原文摘录：去首尾空白 + 超长截断（违规定位仍靠行号，摘录只做「看得出错在哪」）。"""
    t = (text or "").strip()
    return t if len(t) <= REF_GATE_MAX_LINE_DETAIL else t[:REF_GATE_MAX_LINE_DETAIL - 1] + "…"


def live_wikilinks(line):
    """行内代码**之外**的 wikilink 三形集合 {(target, anchor|None, kind)}。

    doc.links 是整行的解析结果（不剔行内代码——R2 的历史口径），但「举例」面
    （`` `[[词条名]]` `` 这种写进代码的语法示例）不是引用：门禁只认行内代码之外
    的实链，否则文档里一写语法示例就自伤。"""
    text = INLINE_CODE_SPAN_RE.sub(lambda m: " " * len(m.group(0)), line or "")
    out = set()
    for m in WIKILINK_RE.finditer(text):
        target = m.group(2).split("|")[0]
        anchor = None
        if "#" in target:
            target, anchor = target.split("#", 1)
        out.add((target.strip(), (anchor or "").strip() or None,
                 "embed" if m.group(1) else "link"))
    return out


# ── 落位解析：项目名 → 文档根（作者令 2026-11-03 20:02，单源）────────────
#
# 知识文档（设计/审计/裁定/调研/Obsidian 文档）落**项目注册目录**（project.json
# 所在目录，即 <data_root>/projects/<项目名>/）下的 `.stand/docs/`，不落项目
# workspace 仓库的 `docs/`。本函数是该规则的 Python 面单源；Scala 面单源 =
# `ProjectStore.ledgerDocsDir` / `ProjectStore.localVault`（两处同公式，改则同改）。
#
# 数据根解析序（与引擎 PathUtil.dataRoot 同序）：STAND_HOME → NEBFLOW_HOME
# （legacy 兼容）→ ~/.stand。显式 `--root` 恒胜出（向后兼容：未迁移项目、或
# workspace 内 docs/ 的存量 vault 照旧可校验）。

DATA_ROOT_ENV_VARS = ("STAND_HOME", "NEBFLOW_HOME")
DEFAULT_HOME_DIRNAME = ".stand"
LEDGER_DOCS_REL = (".stand", "docs")


def resolve_data_root(env=None):
    """数据根：STAND_HOME → NEBFLOW_HOME → ~/.stand（与 PathUtil.dataRoot 同序）。"""
    env = os.environ if env is None else env
    for key in DATA_ROOT_ENV_VARS:
        raw = (env.get(key) or "").strip()
        if raw:
            return Path(raw).expanduser()
    return Path.home() / DEFAULT_HOME_DIRNAME


def resolve_project_docs_root(project, env=None):
    """项目名 → 文档根：<data_root>/projects/<project>/.stand/docs。

    project 为空/缺省时按 `<data_root>/projects/stand/.stand/docs` 解析——绝不
    回落到相对 cwd 的 `docs`（那正是本批要废掉的旧落位）。"""
    name = (project or "").strip() or "stand"
    root = resolve_data_root(env)
    for part in ("projects", name):
        root = root / part
    for part in LEDGER_DOCS_REL:
        root = root / part
    return root


# ── 文档模型 ─────────────────────────────────────────────────────────

class Doc:
    def __init__(self, rel):
        self.rel = rel                    # vault 相对路径（正斜杠）
        self.frontmatter = {}             # 顶层标量键值
        self.title = ""                   # 首个 H1，缺省文件名
        self.headings = []                # [(text, line)]
        self.blocks = set()               # 块锚 ^id 集合
        self.links = []                   # [(target, anchor|None, kind, line)]
        self.body_lines = []              # [(line_no, text)]（front-matter 之后、fence 屏蔽后的行）
        self.glossary_entries = []        # 仅词表文档使用：[(term, block_id, line)]


def path_of(root, rel):
    return root / rel


def read_text(path):
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def parse_frontmatter(lines):
    """返回 (fields|None, body 起始行索引)。逻辑同 WikiIndex.frontmatterOf。"""
    if not lines or lines[0].strip() != "---":
        return None, 0
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            fields = {}
            for raw in lines[1:i]:
                m = FRONTMATTER_KV_RE.match(raw)
                if m:
                    val = m.group(2).strip().strip('"').strip("'")
                    if val:
                        fields[m.group(1)] = val
            return fields, i + 1
    return None, 0


def fenced_mask(lines):
    """code fence 内的行不产生链接/标题/块锚/术语命中（同 WikiIndex.fencedMask）。"""
    mask = [False] * len(lines)
    opened = False
    marker = ""
    for i, line in enumerate(lines):
        m = FENCE_RE.match(line)
        if m and not opened:
            opened = True
            marker = m.group(1)[:3]
        elif m and opened and m.group(1).startswith(marker):
            opened = False
            marker = ""
            mask[i] = True
            continue
        mask[i] = opened or bool(m)
    return mask


def strip_inline_markup(text):
    """摘要用：把行内 markdown 折叠成纯文本。"""
    text = WIKILINK_RE.sub(lambda m: (m.group(2).split("|")[-1] if "|" in m.group(2) else m.group(2)).split("#")[0], text)
    text = MDLINK_RE.sub(lambda m: m.group(1), text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = re.sub(r"(?<!\w)\*([^*\n]+)\*(?!\w)", r"\1", text)
    text = text.replace("`", "")
    return text.strip()


def parse_doc(rel, text):
    doc = Doc(rel)
    lines = text.splitlines()
    fm, body_start = parse_frontmatter(lines)
    if fm:
        doc.frontmatter = fm
    mask = fenced_mask(lines)
    title = Path(rel).stem
    for i in range(body_start, len(lines)):
        line = lines[i]
        line_no = i + 1
        if mask[i]:
            continue
        doc.body_lines.append((line_no, line))
        hm = HEADING_RE.match(line)
        if hm:
            heading_text = hm.group(2).strip()
            if heading_text:
                doc.headings.append((heading_text, line_no))
                if line.startswith("# "):
                    title = heading_text
            continue
        bm = BLOCKID_RE.match(line)
        if bm:
            doc.blocks.add(bm.group(2))
        for wm in WIKILINK_RE.finditer(line):
            inner = wm.group(2)
            target = inner.split("|")[0]
            anchor = None
            hash_idx = target.find("#")
            if hash_idx >= 0:
                anchor = target[hash_idx + 1:].strip()
                target = target[:hash_idx]
            target = target.strip()
            if target:
                doc.links.append((target, anchor, "embed" if wm.group(1) else "link", line_no))
        for mm in MDLINK_RE.finditer(line):
            dest = mm.group(2)
            anchor = None
            hash_idx = dest.find("#")
            if hash_idx >= 0:
                anchor = dest[hash_idx + 1:].strip()
                dest = dest[:hash_idx]
            doc.links.append((dest.strip(), anchor, "link", line_no))
    doc.title = title
    return doc


def parse_glossary(doc):
    """词表条目：以 ^block-id 结尾的连续非空行组。术语 = 组首行第一个 —/——/：/: 之前的部分；
    组首行是标题时术语 = 标题文本。空行断组（组必须整体连续）。"""
    if not doc.body_lines:
        return
    solid = [(line_no, text) for line_no, text in doc.body_lines if text.strip()]
    region_start = {}
    prev_no = None
    for line_no, _ in solid:
        region_start[line_no] = line_no if prev_no is None or line_no != prev_no + 1 else region_start[prev_no]
        prev_no = line_no
    region_lines = {}
    for line_no, text in solid:
        region_lines.setdefault(region_start[line_no], []).append((line_no, text))
    for start, group in region_lines.items():
        bm = BLOCKID_RE.match(group[-1][1])
        if not bm:
            continue
        block_id = bm.group(2)
        first_no, first_text = group[0]
        hm = HEADING_RE.match(first_text)
        if hm:
            term = hm.group(2).strip()
        else:
            seg = re.split(r"\s*[—–]\s*|\s*[:：]\s*", first_text, maxsplit=1)
            term = seg[0].strip().lstrip("#*- ").strip()
            term = re.sub(r"^\*+|\*+$", "", term).strip()
        if term:
            doc.glossary_entries.append((term, block_id, first_no))


# ── 段落索引（wiki-graph 段落级引用批；与 WikiIndex.scala 同构） ───────

PARAGRAPH_MAX_CHARS = 280    # text 摘录上限（截断加 …）
PARAGRAPH_TITLE_MAX = 60     # block 段 title 上限
PARAGRAPH_MAX_PER_DOC = 64   # 每文档段落条数上限（图谱规模保护）


def _para_text(raw):
    """段内文本：行内标记折叠 + 空白归一 + 截 280（加 … 不劈码点）。"""
    cleaned = re.sub(r"\s+", " ", strip_inline_markup(raw))
    if len(cleaned) > PARAGRAPH_MAX_CHARS:
        cleaned = cleaned[:PARAGRAPH_MAX_CHARS - 1].rstrip() + "…"
    return cleaned


def block_groups(doc):
    """块段落分组：连续非空、非标题行的行组（空行 / 标题 / 围栏缺口断组）。

    与 WikiIndex.contentRuns 同构，且是**唯一**的分组定义——段落索引
    （build_paragraphs）与词条标签解析（tag_window）都从这一份拿边界，
    杜绝两处各写一套导致标签窗口与词条段落错位。"""
    groups = []
    cur = []
    prev_no = None
    for line_no, line in doc.body_lines:
        content = bool(line.strip()) and not HEADING_RE.match(line)
        if content and cur and prev_no is not None and line_no == prev_no + 1:
            cur.append((line_no, line))
        elif content:
            if cur:
                groups.append(cur)
            cur = [(line_no, line)]
        else:
            if cur:
                groups.append(cur)
            cur = []
        prev_no = line_no
    if cur:
        groups.append(cur)
    return groups


def build_paragraphs(doc):
    """→ [{anchor, kind, title, line, endLine, text}]（anchor 与 edge 同形，
    带 # 前缀：块锚 '#^id'、标题 '#标题'）。行号 = 全文件坐标（含 front-matter）。
    block = 连续非空行组（遇空行/围栏缺口/标题截止，组尾行带 ^id）；
    heading = 标题行至下一标题行前一行。按行号排序，每文档至多 64 段。"""
    if not doc.body_lines or not doc.rel.lower().endswith((".md", ".markdown")):
        return []
    paras = []
    # 块锚段落：连续非空行组（body_lines 已剔 fence 行——围栏缺口天然断组）。
    for group in block_groups(doc):
        bm = BLOCKID_RE.match(group[-1][1])
        if not bm:
            continue
        # title = 段首行的「术语」部分（—/：之前，词表条目约定；无分隔符取前 60 字符）。
        first = group[0][1]
        seg = re.split(r"\s*[—–]\s*|\s*[:：]\s*", BLOCKID_RE.sub(r"\1", first), maxsplit=1)
        title = _para_text(seg[0])[:PARAGRAPH_TITLE_MAX].strip()
        title = re.sub(r"^[#*-]+\s*", "", title).strip()
        if not title:
            title = _para_text(BLOCKID_RE.sub(r"\1", first))[:PARAGRAPH_TITLE_MAX].strip() or bm.group(2)
        # text 去掉组尾行的 ^id（锚记号不是内容）。
        body_lines = [BLOCKID_RE.sub(r"\1", t) if i == len(group) - 1 else t
                      for i, (_, t) in enumerate(group)]
        paras.append({
            "anchor": "#^" + bm.group(2),
            "kind": "block",
            "title": title,
            "line": group[0][0], "endLine": group[-1][0],
            "text": _para_text(" ".join(body_lines)),
        })
    # 标题节：标题行 → 下一标题行前一行。
    for i, (text, line) in enumerate(doc.headings):
        end_line = doc.headings[i + 1][1] - 1 if i + 1 < len(doc.headings) else doc.body_lines[-1][0]
        seg = [t for no, t in doc.body_lines if line < no <= end_line and t.strip() and not HEADING_RE.match(t)]
        paras.append({
            "anchor": "#" + text,
            "kind": "heading",
            "title": text,
            "line": line, "endLine": end_line,
            "text": _para_text(" ".join(seg)),
        })
    paras.sort(key=lambda p: p["line"])
    return paras[:PARAGRAPH_MAX_PER_DOC]


def doc_paragraphs(doc):
    """build_paragraphs 的按文档缓存版（图谱导出 / 词条注册表 / R4 共用一次解析）。"""
    if not hasattr(doc, "_paras_cache"):
        doc._paras_cache = build_paragraphs(doc)
    return doc._paras_cache


# ── 词条注册表（词条引用批；与 WikiIndex.termsOf 同构） ───────────────
# 词条 = 显式声明 + 惯例抽取的块锚段落，两路来源：
#   (a) glossary — 词表文档（rel basename 去尾扩展名含 "glossary"，同 R3 探测）
#       的每个块锚段落；
#   (b) doc — 普通文档中块 id 带 `term-` 前缀的块段落（`^term-<slug>`；SKILL 的
#       「## 词条」开头词条块惯例即用此锚记名）。
# 普通 `^锚` 段落一律不是词条（噪音控制）。无 `—`/`：` 分隔符的合格块抽不出
# 词条名 ⇒ 不注册（R4 term-format 负责报缺陷）。frontmatter `terms:` 已被
# 设计裁定否决（§4.2-B 双真源漂移），声明面只有正文块锚。

TERM_ANCHOR_PREFIX = "#^term-"
TERM_NAME_SEP_RE = re.compile(r"\s*[—–]\s*|\s*[:：]\s*")


def is_glossary_rel(rel):
    return "glossary" in Path(rel).stem.lower()


def term_alias_keys(name):
    """尾括号组派生查找键：`DCL（DOMContentLoaded 事件）` → ["DCL", "DOMContentLoaded 事件"]。
    显式 `|` 别名不用于声明（竖线是引用侧别名分隔符，声明面零新语法）。"""
    t = name.strip()
    for close, open_ch in (("）", "（"), (")", "(")):
        if t.endswith(close) and t.rfind(open_ch) > 0:
            start = t.rfind(open_ch)
            keys = [t[:start].strip(), t[start + 1:-1].strip()]
            return [k for k in keys if k and k != t]
    return []


# ── 词条标签 v2（term-system v2；与 WikiIndex.termTagsOf 同口径） ───────
# 语法（派发侧定死，本脚本只是执行面）：
#   - 词条名（别名1，别名2） — 一句话定义。 #proj/stand #topic/term-index ^term-<slug>
# · 恰好一个 `#proj/<slug>`、至少一个 `#topic/<slug>`，其余 `#facet/value` 入 extra；
# · 标签窗口 = 定义文字之后 → 该段落的 `^块锚` 之前（块锚必须在行末：写在块锚
#   之后的标签不被识别，等价于缺标签 ⇒ term-tag-missing）；
# · 行内代码 span / 围栏里的 `#x` 不是标签（围栏行本就不进 body_lines）；
# · 缺 project 或缺 topic ⇒ 违规 term-tag-missing；行内豁免
#   `<!-- wiki-term-exempt: 理由 -->`（同行或紧邻上一行、理由非空）单独计数。

TERM_TAG_NAMESPACES = {"proj": "project", "topic": "topic"}
TERM_TAG_RE = re.compile(r"#([A-Za-z0-9_\u4e00-\u9fa5.-]+)/([A-Za-z0-9_\u4e00-\u9fa5.-]+)")
INLINE_CODE_SPAN_RE = re.compile(r"`[^`\n]*`")
TERM_EXEMPT_RE = re.compile(r"<!--\s*wiki-term-exempt\s*:\s*(.*?)\s*-->")
# 块锚之后还有内容的行（块锚不在行末）——Obsidian 不认这种锚，词条标签也不认。
ANCHOR_MIDLINE_RE = re.compile(r"^(.*\S)\s+\^([A-Za-z0-9][A-Za-z0-9-]*)\s+(\S.*)$")


def empty_term_tags():
    """词条标签的唯一固定形状（三桶；桶内去重 + 排序，跨语言同序）。"""
    return {"project": [], "topic": [], "extra": []}


def parse_term_tags(window):
    """标签窗口 → {"project": […], "topic": […], "extra": […]}

    · `#proj/<slug>` → project；`#topic/<slug>` → topic；
      其余 `#<facet>/<value>` → extra（原样 "facet/value"，不带 `#`）。
    · 行内代码 span 先整段剔除——示例里的 `#proj/x` 不是标签。
    · 窗口之外的 `#x` 不是标签（调用方负责给窗口：定义文字之后、块锚之前）。"""
    tags = empty_term_tags()
    text = INLINE_CODE_SPAN_RE.sub(" ", window or "")
    for m in TERM_TAG_RE.finditer(text):
        namespace, value = m.group(1), m.group(2)
        bucket = TERM_TAG_NAMESPACES.get(namespace)
        if bucket:
            tags[bucket].append(value)
        else:
            tags["extra"].append(f"{namespace}/{value}")
    for key in tags:
        tags[key] = sorted(set(tags[key]))
    return tags


def block_group_index(doc):
    """{(首行号, 尾行号): 行组} —— 只收尾行带 `^块锚` 的组（词条候选段落）。"""
    idx = {}
    for group in block_groups(doc):
        if BLOCKID_RE.match(group[-1][1]):
            idx[(group[0][0], group[-1][0])] = group
    return idx


def tag_window(group):
    """词条标签窗口（原文行，**不**剥行内标记——清洗过的段落文本会丢反引号，
    行内代码里的 `#x` 就分不出来了，且定义超 280 字符时尾部标签会被截断）。

    窗口 = 组内全部原文（尾行的 `^块锚` 先剥掉）里的第一处「名 —/：」分隔符之后
    到组尾。块锚之后的内容不进窗口（AnchorMidline 另行报位置错）。"""
    if not group:
        return ""
    parts = []
    for i, (_line_no, text) in enumerate(group):
        if i == len(group) - 1:
            bm = BLOCKID_RE.match(text)
            if bm:
                text = bm.group(1)
        parts.append(text)
    window = " ".join(parts)
    m = TERM_NAME_SEP_RE.search(window)
    return window[m.end():] if m else ""


def term_tag_problem(tags):
    """标签合规判据 → None（合规）或人话原因（说明缺哪个 facet）。"""
    reasons = []
    project, topic = tags["project"], tags["topic"]
    if not project:
        reasons.append("缺 #proj/<slug>（必须恰好一个项目标签）")
    elif len(project) > 1:
        reasons.append("`#proj/` 多于一个（%s）——必须恰好一个"
                       % "、".join("#proj/" + p for p in project))
    if not topic:
        reasons.append("缺 #topic/<slug>（至少一个主题标签）")
    return "；".join(reasons) if reasons else None


def doc_line_text(doc, line_no):
    """全文件行号 → 原文行（缺返回 None）。doc.body_lines 不含围栏行。"""
    if not hasattr(doc, "_line_cache"):
        doc._line_cache = {no: text for no, text in doc.body_lines}
    return doc._line_cache.get(line_no)


def exempt_markers(doc):
    """全档行内豁免标注 → [(行号, 理由)]（理由为空的标注不算——空理由不接受，
    整文件豁免也不接受：豁免只挂在具体行上）。围栏内的标注不进 body_lines。"""
    if not hasattr(doc, "_exempt_cache"):
        out = []
        for line_no, text in doc.body_lines:
            m = TERM_EXEMPT_RE.search(text)
            if m and m.group(1).strip():
                out.append((line_no, m.group(1).strip()))
        doc._exempt_cache = out
    return doc._exempt_cache


def term_exemption(doc, *line_nos):
    """行内豁免：给定行及其紧邻上一行里的 `<!-- wiki-term-exempt: 理由 -->`。
    理由为空 = 无效豁免（不接受整文件豁免——豁免只挂在具体声明行）。"""
    table = dict(exempt_markers(doc))
    for line_no in line_nos:
        for probe in (line_no, line_no - 1):
            if probe in table:
                return table[probe]
    return None


def misplaced_term_tags(doc, rel, glossary):
    """块锚写在行中（`^id` 后还有内容，通常是标签）的词条声明行。

    这类行的 `^id` 在 Obsidian 里根本不是块锚 ⇒ 该段落抽不出词条、标签也一律
    不认（规范：标签必须在 `^块锚` 之前）。返回 [(行号, 块 id, 原文)]。"""
    out = []
    for line_no, text in doc.body_lines:
        m = ANCHOR_MIDLINE_RE.match(text)
        if not m:
            continue
        block_id, rest = m.group(2), m.group(3)
        if not (glossary or block_id.startswith("term-")):
            continue
        if not TERM_TAG_RE.search(INLINE_CODE_SPAN_RE.sub(" ", rest)):
            continue
        if not TERM_NAME_SEP_RE.search(m.group(1)):
            continue  # 无「名 —/：定义」分隔 ⇒ 本就不是词条声明行
        out.append((line_no, block_id, text.strip()))
    return out


def terms_of(rel, doc):
    glossary = is_glossary_rel(rel)
    groups = block_group_index(doc)
    out = []
    for p in doc_paragraphs(doc):
        if p["kind"] != "block" or not p["anchor"].startswith("#^"):
            continue
        if not (glossary or p["anchor"].startswith(TERM_ANCHOR_PREFIX)):
            continue
        m = TERM_NAME_SEP_RE.search(p["text"])
        if not m:
            continue
        name = p["text"][:m.start()].strip()
        if not name:
            continue
        group = groups.get((p["line"], p["endLine"]), [])
        out.append({
            "name": name,
            "aliases": term_alias_keys(name),
            "source": "glossary" if glossary else "doc",
            "rel": rel,
            "anchor": p["anchor"],
            "line": p["line"], "endLine": p["endLine"],
            "definition": p["text"],
            "tags": parse_term_tags(tag_window(group)),
        })
    return out


def term_keys(t):
    keys = [t["name"], *t["aliases"]]
    return [k.strip().lower() for k in keys]


def select_term(name, terms):
    """消解裁定（与 WikiIndex.selectTerm 同构）：词表唯一命中优先；否则唯一命中
    消解；同级多命中 = ambiguous（不猜）；零命中 = notfound。"""
    key = name.strip().lower()
    if not key:
        return ("notfound", [])
    hits = [t for t in terms if key in term_keys(t)]
    gloss = [t for t in hits if t["source"] == "glossary"]
    if len(gloss) == 1:
        return ("resolved", gloss)
    if len(gloss) > 1:
        return ("ambiguous", gloss)
    if len(hits) == 1:
        return ("resolved", hits)
    if len(hits) > 1:
        return ("ambiguous", hits)
    return ("notfound", [])


def term_target_key(normalized_target):
    """消解目标 → 注册表查找键（去尾文档扩展名：[[GCR]] → GCR.md → gcr）。"""
    low = normalized_target.lower()
    if low.endswith(".markdown"):
        return normalized_target[:-9]
    if low.endswith(".md"):
        return normalized_target[:-3]
    return normalized_target


def make_term_resolver(terms):
    def resolve_term(target):
        """第三优先级回退：文档腿全未命中的无锚目标 → 注册表唯一命中 =
        (所在文档 rel, 位置锚)；多命中/未命中 = None（引擎同样不猜）。"""
        verdict, hits = select_term(term_target_key(normalize_target(target)), terms)
        if verdict == "resolved":
            return (hits[0]["rel"], hits[0]["anchor"])
        return None
    return resolve_term


# ── 链接解析 ─────────────────────────────────────────────────────────

def normalize_target(target):
    t = target.replace("\\", "/").strip()
    while t.startswith("./"):
        t = t[2:]
    if not t.lower().endswith(".md") and "." not in t:
        t += ".md"
    return t


def basename(rel):
    return rel.replace("\\", "/").rsplit("/", 1)[-1]


def make_resolver(doc_rels):
    rels = set(doc_rels)
    by_lower = {}
    for r in doc_rels:
        by_lower.setdefault(r.lower(), r)

    def stem_key(name):
        """basename 去掉已知文档扩展名（多点文件名如 x.zh-CN.md 的 key = x.zh-CN）。"""
        b = basename(name)
        low = b.lower()
        for ext in (".markdown", ".md"):
            if low.endswith(ext):
                return b[: -len(ext)]
        return b

    by_base = {}
    for r in doc_rels:
        by_base.setdefault(stem_key(r).lower(), []).append(r)

    def resolve(target, from_rel=None):
        # 同文档引用：[[#标题]] / [[#^块锚]] —— 空目标解析到当前文档（Obsidian 语义）。
        if target.strip() == "" and from_rel is not None:
            return from_rel
        t = normalize_target(target)
        if t in rels:
            return t
        low = t.lower()
        if low in by_lower:
            return by_lower[low]
        matches = by_base.get(stem_key(t).lower())
        if matches and len(matches) == 1:
            return matches[0]
        return None

    return resolve


# ── 校验 ─────────────────────────────────────────────────────────────

def term_regex(term):
    """英文术语加词边界防子串误伤；中文术语要求两侧不紧邻汉字（「静态样张」≠「样张」）。"""
    escaped = re.escape(term)
    if re.fullmatch(r"[A-Za-z0-9 .+#^_-]+", term):
        return re.compile(r"(?<![\w" + re.escape("-_.") + r"])" + escaped + r"(?![\w" + re.escape("-_.") + r"])", re.IGNORECASE)
    return re.compile(r"(?<![)\]}\u4e00-\u9fff])" + escaped + r"(?![\u4e00-\u9fff])")


def first_occurrence(doc, regex):
    """首个正文命中（跳过标题行命中——标题不算「使用处」）。返回 (line_no, line, match) 或 None。"""
    for line_no, line in doc.body_lines:
        if HEADING_RE.match(line):
            continue
        m = regex.search(line)
        if m:
            return line_no, line, m
    return None


def check_reference_gate(docs, resolve, resolve_term, add, suppress):
    """R7/R8/R9 —— 只许引用词条的门禁（作者令 2026-11-05）。

    三条共用纪律：每行每规则至多一条（同一行多症先修一处，重跑看下一处）、输出
    `原文 → 原因`、行内豁免经 suppress 记账（生效/悬空都由 check() 汇出显形）。"""
    for rel, doc in sorted(docs.items()):
        if rel.lower().endswith(".html"):
            continue  # 正文面 = markdown（与 R4/R5 同口径；html 样张不是引用面）

        emitted = set()

        def emit(rule, line_no, reason):
            if (rule, line_no) in emitted:
                return
            emitted.add((rule, line_no))
            raw = clip_line(doc_line_text(doc, line_no) or "")
            if suppress(rel, line_no):
                return  # 行内豁免：计数显形由豁免段承担，此处不计违规
            add(rule, rel, line_no, f"{raw} → {reason}")

        for line_no, line in doc.body_lines:
            # ② markdown 链接指向文档：`[x](foo.md)` / `[x](foo.md#sec)`。
            #    `![…](…)` 是嵌入面（R8 豁免的承载形态），不归本条。
            code_free = INLINE_CODE_SPAN_RE.sub(" ", line)
            for m in MDLINK_RE.finditer(code_free):
                if m.start() > 0 and code_free[m.start() - 1] == "!":
                    continue
                emit("doc-reference", line_no,
                     f"markdown 链接指向文档「{m.group(0)}」——只许引用词条，"
                     "改写为 `[[词条名]]`（或带锚 `[[doc#^term-slug]]`）")
            # ① 正文裸 .md 路径（`见 design/foo.md`）：合法承载面先剥掉再找。
            for m in BARE_DOC_PATH_RE.finditer(mask_legal_carriers(line)):
                emit("doc-reference", line_no,
                     f"正文裸 .md 引用「{m.group(0)}」——不允许引用文档路径，"
                     "改写为已注册词条 `[[词条名]]`（或带锚 `[[doc#^term-slug]]`）")
            # R8 裸图片路径：纯文本形态（嵌入 / 可点击链接已整段剥掉）。
            for m in BARE_IMAGE_PATH_RE.finditer(mask_legal_carriers(line)):
                raw = m.group(0)
                emit("bare-image-path", line_no,
                     f"图片路径以纯文本出现「{raw}」——图片只有两种合法形态："
                     f"直接嵌入 `![[{basename(raw)}]]` 或可点击跳转 `[说明]({raw})`")

        # ③ 引用文档：无锚、非嵌入的 wikilink 解析到**文档**却解析不到**词条**。
        #    R9 悬空词条：同样的形状但文档与词条双双解析不到（这一形状从 R2 移出，
        #    同一行不双报；带路径 / 带扩展名 / 带锚的断链仍归 R2）。
        for target, anchor, kind, line_no in doc.links:
            if kind == "embed" or anchor is not None:
                continue  # 嵌入面、带锚引用（指名词条块 / 整节引用）不在本门禁
            line = doc_line_text(doc, line_no) or ""
            if (target, anchor, kind) not in live_wikilinks(line):
                continue  # 行内代码里的语法示例，不是引用
            hit_term = resolve_term(target) if resolve_term else None
            resolved = resolve(target, from_rel=rel)
            if resolved and not hit_term:
                if not re.search(r"\.(?:md|markdown|html)$", resolved, re.IGNORECASE):
                    continue  # 附件目标（图片 / PDF）= 可点击跳转，不是引用文档
                emit("doc-reference", line_no,
                     f"`[[{target}]]` 解析到文档 {resolved} 而非词条——这是引用文档；"
                     "改写为已注册词条 `[[词条名]]`，或带锚 `[[doc#^term-slug]]`")
                continue
            if hit_term or resolved:
                continue
            if not is_short_form_ref(target, anchor):
                continue  # 带路径/带扩展名的断链归 R2
            emit("dangling-term", line_no,
                 f"`[[{target}]]` 既解析不到文档也解析不到已注册词条——先声明词条"
                 "（`^term-<slug>` 段落），再按词条名引用")


def check(root, docs, resolve, glossary_rel, exclude_glossary_check, terms_by_rel=None, resolve_term=None,
          exempt_sink=None):
    violations = []

    def add(rule, file, line, detail):
        violations.append({"rule": rule, "file": file, "line": line, "detail": detail})

    if terms_by_rel is None:
        terms_by_rel = {}
    exempts = exempt_sink if exempt_sink is not None else []

    # 行内豁免账本：先把全档标注收齐（含「标了但该行无违规」的悬空标注），
    # 违规点经 suppress() 认领；末尾一次汇出——生效与悬空都必须显形。
    markers = {}
    used_markers = {}
    for rel, doc in sorted(docs.items()):
        ms = exempt_markers(doc)
        if ms:
            markers[rel] = ms
            used_markers[rel] = set()

    def suppress(rel, *line_nos):
        """该行（或紧邻上一行）有非空理由的豁免标注 ⇒ 记账并返回理由；否则 None。
        一个标注可压同行的多条违规（同一行的标签/形状是一件事）。"""
        for line_no in line_nos:
            for probe in (line_no, line_no - 1):
                for mline, reason in markers.get(rel, ()):
                    if mline == probe:
                        used_markers.setdefault(rel, set()).add(mline)
                        return reason
        return None

    # R1 — front-matter 在位
    for rel, doc in sorted(docs.items()):
        if rel.endswith(".html"):
            continue
        if not doc.frontmatter:
            add("obsidian-format", rel, 1, "缺 front-matter（type/status）——所有文档一律 Obsidian 格式产出")
        elif "type" not in doc.frontmatter and "status" not in doc.frontmatter:
            add("obsidian-format", rel, 1, "front-matter 缺 type/status 字段")

    # R2 — 断链（目标 / 标题锚 / 块锚；词条引用批：文档腿全未命中的无锚目标
    # 先过词条注册表第三优先级回退，唯一命中 = 非断链）
    # 引用门禁批：无锚短形 `[[Name]]` 的未命中不再归本条 —— 移出到 R9
    # dangling-term，同一行不双报；带路径/带扩展名的断链仍留在这里。
    for rel, doc in sorted(docs.items()):
        for target, anchor, kind, line_no in doc.links:
            resolved = resolve(target, from_rel=rel)
            if resolved is None:
                thit = resolve_term(target) if (anchor is None and resolve_term) else None
                if thit:
                    continue  # 词条命中：边 = (词条所在文档, 词条锚)，与引擎同构
                if kind != "embed" and is_short_form_ref(target, anchor):
                    continue  # 归 R9 悬空词条
                add("broken-link", rel, line_no, f"引用目标不存在：[[{target}]]")
                continue
            if anchor:
                if anchor.startswith("^"):
                    if anchor[1:] not in docs[resolved].blocks:
                        add("broken-block", rel, line_no, f"块锚不存在：[[{target}#{anchor}]]（目标无 ^{anchor[1:]}）")
                else:
                    wanted = anchor.lstrip("#").strip().lower()
                    heads = {h.lower() for h, _ in docs[resolved].headings}
                    if wanted not in heads:
                        add("broken-anchor", rel, line_no, f"标题锚不存在：[[{target}#{anchor}]]")

    # R2 — 孤儿文档（零出链且零入链；index/glossary 豁免；词条命中计入入链面——
    # 与引擎图谱边一致，纯词条引用也算接入图谱）
    linked = set()
    for rel, doc in docs.items():
        for target, _anchor, _kind, _line in doc.links:
            resolved = resolve(target)
            if resolved:
                linked.add(resolved)
            elif resolve_term:
                thit = resolve_term(target)
                if thit:
                    linked.add(thit[0])
    for rel, doc in sorted(docs.items()):
        stem = Path(rel).stem.lower()
        if stem in ("index", "glossary", "readme"):
            continue
        if not doc.links and rel not in linked:
            add("orphan-doc", rel, 1, "零出链且零入链——至少引用词表或 index，接入图谱")

    # R3 — 概念首现必释或必引用 + 重复定义
    glossary_doc = docs.get(glossary_rel)
    terms = []
    if glossary_doc:
        for term, block_id, line in glossary_doc.glossary_entries:
            terms.append((term, block_id, line))
        for term, block_id, line in terms:
            if block_id not in glossary_doc.blocks:
                add("glossary-format", glossary_rel, line, f"词表条目缺块锚 ^{block_id}（术语「{term}」）")
    if terms and not exclude_glossary_check:
        terms.sort(key=lambda t: -len(t[0]))
        definition_docs = {}
        for rel, doc in sorted(docs.items()):
            if rel == glossary_rel or rel.endswith(".html"):
                continue
            links_by_line = {}
            for target, _anchor, _kind, line_no in doc.links:
                resolved = resolve(target)
                if resolved is None and resolve_term:
                    thit = resolve_term(target)
                    resolved = thit[0] if thit else None  # [[词条]] 短形引用也算词表引用面
                if resolved == glossary_rel:
                    links_by_line.setdefault(line_no, True)
            for term, block_id, _gline in terms:
                occ = first_occurrence(doc, term_regex(term))
                if not occ:
                    continue
                line_no, line, m = occ
                tail = line[m.end():]
                cited = links_by_line.get(line_no, False)
                defined = bool(DEFINITION_TAIL_RE.match(tail) or DEFINITION_PAREN_RE.match(tail))
                # 引用优先：同行已引用词表即合规（后随括注不是「重新定义」）
                if cited:
                    continue
                if defined:
                    definition_docs.setdefault(term, []).append(rel)
                else:
                    add("concept-first-use", rel, line_no,
                        f"概念「{term}」首现未解释且未引用——就地一句话解释，或 [[{Path(glossary_rel).stem}#^{block_id}]]")
        for term, where in sorted(definition_docs.items()):
            if len(where) > 1:
                add("duplicate-definition", glossary_rel, 1,
                    f"概念「{term}」在多篇文档各自定义（{', '.join(where)}）——定义唯一化：词表定义，其余引用")

    # R4 — 词条标签 v2：恰好一个 #proj/<slug> + 至少一个 #topic/<slug>，缺一个即
    # term-tag-missing。豁免只认行内 `<!-- wiki-term-exempt: 理由 -->`（同行或紧邻
    # 上一行、理由非空），单独计入 exempts 显形——不接受整文件豁免。
    for rel, doc in sorted(docs.items()):
        if rel.lower().endswith(".html"):
            continue
        for t in terms_by_rel.get(rel, []):
            problem = term_tag_problem(t["tags"])
            if not problem:
                continue
            raw = (doc_line_text(doc, t["line"]) or "").strip()
            if suppress(rel, t["line"], t["endLine"]):
                continue  # 豁免：记账由 check() 末尾统一汇出（生效/悬空都显形）
            add("term-tag-missing", rel, t["line"], f"{raw} → 词条「{t['name']}」{problem}")
        # 标签写在 ^块锚 之后 ⇒ 标签不被识别（等价缺标签）。单独判据：这类行的
        # ^id 在 Obsidian 里根本不是块锚，段落抽不出词条，上面那圈看不到它。
        for line_no, block_id, raw in misplaced_term_tags(doc, rel, is_glossary_rel(rel)):
            if suppress(rel, line_no):
                continue
            add("term-tag-missing", rel, line_no,
                f"{raw} → 词条标签写在 ^{block_id} 之后，不被识别（等价缺标签）——"
                f"标签必须在定义文字之后、^块锚 之前，且 ^块锚 独占行末")

    # R4 — 词条注册表面：词条块结构合法（合格块必须抽得出词条名）+ 同档词条名唯一
    for rel, doc in sorted(docs.items()):
        seen = {}
        for t in terms_by_rel.get(rel, []):
            k = t["name"].strip().lower()
            if k in seen:
                add("term-duplicate", rel, t["line"],
                    f"词条「{t['name']}」在本档重复声明（{seen[k]} 与本条）——同一文档内词条名唯一")
            else:
                seen[k] = t["anchor"]
        if rel.lower().endswith(".html"):
            continue  # html 无 markdown 段落索引，词条定义面 v1 仅 md（设计 §4.4）
        glossary = is_glossary_rel(rel)
        for p in doc_paragraphs(doc):
            if p["kind"] != "block" or not p["anchor"].startswith("#^"):
                continue
            if not (glossary or p["anchor"].startswith(TERM_ANCHOR_PREFIX)):
                continue
            m = TERM_NAME_SEP_RE.search(p["text"])
            if not m or not p["text"][:m.start()].strip():
                add("term-format", rel, p["line"],
                    f"词条块结构非法：{p['anchor']} 抽不出词条名（缺「名 — 定义」分隔）——声明形态「名 — 定义 ^锚」，`^term-*` 或词表锚段落")

    # R5 — 文档命名制：<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md（topic 前置、时间块后置）
    for rel in sorted(docs):
        name = rel.rsplit("/", 1)[-1]
        if not name.lower().endswith(".md"):
            continue  # 非 .md 资产（html 样张 / 截图 / patch）不在命名制范围
        if DOC_NAMING_OLD_RE.match(name):
            add("doc-naming", rel, 1,
                "旧制文件名（时间打头）——新制为 `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`："
                "topic 前置、时间块后置，`__` 分主题与时间；改名并同步全部引用")
            continue
        if DOC_NAMING_TIME_RE.search(name):
            m = DOC_NAMING_OK_RE.match(name)
            if not m:
                add("doc-naming", rel, 1,
                    "文件名含时间块但不符新制形态 `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`"
                    "（主题与时间之间必须 `__`、时间块内部 `_`；语言后缀留最后）")
            elif m.group("topic").strip().lower() in DOC_NAMING_BLAND_TOPICS:
                add("doc-naming", rel, 1,
                    f"topic「{m.group('topic')}」是空词，不构成主题——用一句话说清这份文档是什么"
                    "（小写 kebab 或原样中文），空词不得单独成题")

    # R7/R8/R9 — 引用门禁：只许引用词条（文档路径、图片裸路径、悬空词条三种形状）
    check_reference_gate(docs, resolve, resolve_term, add, suppress)

    # 行内豁免一次汇出：生效（压住了违规）与悬空（标了但该行无违规）都显形，
    # 均不计违规——豁免不是沉默，理由必须看得见。
    for rel in sorted(markers):
        for mline, reason in markers[rel]:
            exempts.append({
                "file": rel, "line": mline, "reason": reason,
                "text": (doc_line_text(docs[rel], mline) or "").strip(),
                "used": mline in used_markers.get(rel, set()),
            })
    return violations


# ── 图谱导出（WikiIndex 语义） ───────────────────────────────────────

def extract_summary(doc):
    for line_no, line in doc.body_lines:
        if HEADING_RE.match(line) or BLOCKID_RE.match(line):
            continue
        text = strip_inline_markup(line).lstrip("#*- ").strip()
        if len(text) < 8:
            continue
        return text[:140] + ("…" if len(text) > 140 else "")
    return ""


def build_graph(docs, resolve, project, terms_by_rel=None, resolve_term=None):
    nodes = []
    edges = []
    unresolved = []
    degree = {}
    doc_rels = set(docs)
    if terms_by_rel is None:
        terms_by_rel = {}
    for rel, doc in sorted(docs.items()):
        for target, anchor, kind, line_no in doc.links:
            resolved = resolve(target, from_rel=rel)
            edge_anchor = ("#" + anchor) if anchor else None
            if resolved is None and anchor is None and resolve_term:
                thit = resolve_term(target)
                if thit:
                    resolved, edge_anchor = thit  # 词条命中：边 = (词条所在文档, 词条锚)，与引擎同构
            if resolved is None:
                unresolved.append({"from": rel, "target": target, "line": line_no})
                continue
            edges.append({"from": rel, "to": resolved, "kind": kind,
                          "anchor": edge_anchor, "line": line_no})
            degree[rel] = degree.get(rel, 0) + 1
            degree[resolved] = degree.get(resolved, 0) + 1
            # 附件/媒体目标不在 doc 集合（如 ![[x.png]] 指向 attachments/）→ 补附件节点
            if resolved not in doc_rels:
                pass
    seen_targets = {e["to"] for e in edges} - set(docs)
    attachment_targets = sorted(seen_targets)
    for rel, doc in sorted(docs.items()):
        top = rel.split("/", 1)[0] if "/" in rel else "."
        nodes.append({
            "id": rel,
            "project": project,
            "rel": rel,
            "title": doc.title,
            "dir": top,
            "kind": "doc",
            "type": doc.frontmatter.get("type", ""),
            "status": doc.frontmatter.get("status", ""),
            "stub": False,
            "degree": degree.get(rel, 0),
            "summary": extract_summary(doc) if rel.endswith((".md", ".markdown")) else "",
            "headings": [h for h, _ in doc.headings][:8] if rel.endswith((".md", ".markdown")) else [],
            "paragraphs": doc_paragraphs(doc),
            "terms": terms_by_rel.get(rel, []),
        })
    for rel in attachment_targets:
        top = rel.split("/", 1)[0] if "/" in rel else "."
        nodes.append({
            "id": rel, "project": project, "rel": rel,
            "title": basename(rel), "dir": top, "kind": "attachment",
            "type": "", "status": "", "stub": False,
            "degree": degree.get(rel, 0), "summary": "",
        })
    edges.sort(key=lambda e: (e["from"], e["line"]))
    return {"project": project, "nodes": nodes, "edges": edges, "unresolved": unresolved}


# ── 主流程 ───────────────────────────────────────────────────────────

def collect_docs(root, excludes, skip_dirs=()):
    """返回 (docs, attachments)。docs = 解析过的文档（.md/.markdown/.html）；
    attachments = 其余文件的 vault 相对路径（同 WikiIndex：附件在解析命名空间内，
    被引用即图谱附件节点；只有文档扩展名进 front-matter/概念等文档级检查）。
    skip_dirs = vault 相对目录（子 vault 根，#744-B）——子 vault 是独立图谱，
    父 vault 收集时整枝剪掉，绝不吸收子 vault 文档。"""
    docs = {}
    attachments = []

    def skipped(rel):
        return any(rel == d or rel.startswith(d + "/") for d in skip_dirs)

    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if skipped(rel):
            continue
        if path.suffix.lower() not in DOC_EXTENSIONS:
            attachments.append(rel)
            continue
        if any(fnmatch.fnmatch(rel, pat) or fnmatch.fnmatch("/" + rel, pat) for pat in excludes):
            continue
        text = read_text(path)
        if text is None:
            print(f"WARN: 无法读取（非 UTF-8 或 IO 错误），跳过：{rel}", file=sys.stderr)
            continue
        doc = parse_doc(rel, text)
        if rel.lower().endswith((".md", ".markdown")) and "glossary" in Path(rel).stem.lower():
            parse_glossary(doc)
        docs[rel] = doc
    return docs, attachments


# ── 子 vault（#744-B 多项目图谱批） ─────────────────────────────────

MAX_SUBVAULT_DEPTH = 3
MAX_SUBVAULTS = 32


def discover_subvaults(root, max_depth=MAX_SUBVAULT_DEPTH, max_n=MAX_SUBVAULTS):
    """发现 root vault 下的子 vault：直接子目录里的 <sub>/docs（递归）。

    与引擎 WikiService.discoverLocalSubVaults 同约定（#744-B 设计 §1）：
    每个 `<sub>/docs` 目录自成一张图谱，逐 vault 独立校验。
    返回 [(name, rel, path)]，rel 相对 root（如 "sub/docs"）。
    """
    out = []

    def walk(vault, prefix, depth):
        if depth >= max_depth or len(out) >= max_n or not vault.is_dir():
            return
        for child in sorted(vault.iterdir()):
            if len(out) >= max_n or not child.is_dir():
                continue
            docs = child / "docs"
            if docs.is_dir():
                rel = prefix + child.name + "/docs"
                out.append((child.name, rel, docs))
                walk(docs, rel + "/", depth + 1)

    walk(root, "", 0)
    return out


def resolve_cross_vault(resolver_by_vault, target):
    """target 在其它 vault 中能否解析（exact/大小写/唯一 basename，同 make_resolver）。

    返回 (vault_rel, resolved_rel) 或 None。只做提示级判定——跨 vault 引用
    不是违规（各 vault 图谱独立，引用别的 vault = 跨库引用面）。"""
    if not target.strip():
        return None
    t = normalize_target(target)
    for vrel, resolver in resolver_by_vault.items():
        hit = resolver(t)
        if hit:
            return (vrel, hit)
    return None


# ── 词条机器出口（--dump-terms；下游 CLI / 索引引擎的唯一抽取入口） ─────

TERM_DUMP_KEYS = ("name", "aliases", "rel", "anchor", "line", "end_line",
                  "definition", "source", "tags")
TERM_REF_DUMP_KEYS = ("name_key", "from_rel", "line", "raw", "resolved")


def dump_terms(docs, terms_by_rel, resolve_term=None, root=None):
    """纯导出：词条注册表 + `[[名]]` 短形引用（不跑校验、不影响退出码）。

    顺序确定：terms 按 (rel, line, name)，refs 按 (from_rel, line, raw)——同一
    vault 同一份代码两次导出除 generated_at 外逐字节相同。tags 三桶桶内已排序去重。
    `refs.resolved` = 词条注册表唯一命中（与 R2 第三优先级回退同一判据）。"""
    terms = []
    for rel in sorted(terms_by_rel):
        for t in terms_by_rel[rel]:
            tags = t.get("tags") or empty_term_tags()
            terms.append({
                "name": t["name"],
                "aliases": list(t.get("aliases") or []),
                "rel": t["rel"],
                "anchor": t["anchor"],
                "line": t["line"],
                "end_line": t["endLine"],
                "definition": t["definition"],
                "source": t["source"],
                "tags": {"project": list(tags["project"]),
                         "topic": list(tags["topic"]),
                         "extra": list(tags["extra"])},
            })
    terms.sort(key=lambda t: (t["rel"], t["line"], t["name"]))

    refs = []
    for rel in sorted(docs):
        for target, anchor, _kind, line_no in docs[rel].links:
            if anchor is not None:
                continue  # 带锚引用不回退词条（与引擎同）
            raw = target.strip()
            if not raw or "/" in raw.replace("\\", "/"):
                continue  # 短形引用：无锚、无路径分隔
            hit = resolve_term(raw) if resolve_term else None
            refs.append({
                "name_key": term_target_key(normalize_target(raw)).strip().lower(),
                "from_rel": rel,
                "line": line_no,
                "raw": raw,
                "resolved": bool(hit),
            })
    refs.sort(key=lambda r: (r["from_rel"], r["line"], r["raw"]))

    return {
        "root": str(root) if root is not None else "",
        "generated_at": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "terms": terms,
        "refs": refs,
    }


def print_exempts(exempts):
    """行内豁免单独显形（不计违规）：豁免不是沉默，必须看得见理由。

    两类都显形：生效（压住了同/上一行的违规）与悬空（标了但该行无违规——标注
    过期或标错行，同样要看见）。不接受整文件豁免。"""
    if not exempts:
        return
    used = [e for e in exempts if e.get("used")]
    dangling = [e for e in exempts if not e.get("used")]
    print(f"── 行内豁免（{len(exempts)} 条：生效 {len(used)}、悬空 {len(dangling)}；均不计违规）──")
    for e in used:
        where = f"{e['vault']}/{e['file']}" if e.get("vault") else e["file"]
        print(f"  ✓ {where}:{e['line']}  理由：{e['reason']}")
    for e in dangling:
        where = f"{e['vault']}/{e['file']}" if e.get("vault") else e["file"]
        print(f"  · {where}:{e['line']}  悬空标注（该行无违规，标注可清理）理由：{e['reason']}")
    print()


def print_rule_counts(violations, exempts=None):
    """按类计数小表（汇总段）：R1-R6 的逐条清单照旧在上，这里只加一张总计面。
    零条也列出——「这一类今天没犯」和「这一类没检查」必须分得开。"""
    counts = summarise(violations)
    print("── 计数汇总（按类）──")
    known = set()
    for rid, name, _text in RULES:
        known.add(name)
        print(f"  {rid} {name:<20} {counts.get(name, 0)}")
    for name in sorted(k for k in counts if k not in known):
        print(f"  -- {name:<20} {counts[name]}")
    if exempts:
        used = sum(1 for e in exempts if e.get("used"))
        print(f"  行内豁免 {len(exempts)}（生效 {used}、悬空 {len(exempts) - used}）——不计违规")
    print(f"  合计 {len(violations)}")
    print()


def main():
    ap = argparse.ArgumentParser(description="wiki-docs 文档纪律校验器 + 图谱数据导出")
    ap.add_argument("--root", default=None,
                    help="vault 根目录（显式覆盖，向后兼容）。缺省 = 按 --project 解析："
                         "<data_root>/projects/<project>/.stand/docs（项目注册目录，"
                         "project.json 所在；数据根 = STAND_HOME → NEBFLOW_HOME → ~/.stand）")
    ap.add_argument("--glossary", default=None, help="词表路径（vault 相对；默认自动探测 *glossary*.md）")
    ap.add_argument("--project", default="stand",
                    help="项目名（--graph 输出的 project 名；缺省 --root 时同时是文档根的解析键）")
    ap.add_argument("--json", action="store_true", help="违规清单以 JSON 输出")
    ap.add_argument("--graph", metavar="OUT", default=None, help="导出图谱数据 JSON 到 OUT")
    ap.add_argument("--dump-terms", action="store_true",
                    help="词条机器出口：导出词条注册表 + [[名]] 短形引用（JSON，形状见脚本 "
                         "docstring）。纯导出——不跑校验、不影响退出码（恒 0）；顺序确定：" 
                         "terms 按 (rel, line)、refs 按 (from_rel, line, raw)")
    ap.add_argument("--exclude", action="append", default=[],
                    help="排除 glob（vault 相对，可多次；默认排除 assets/**）")
    ap.add_argument("--rules", action="store_true", help="打印规则与检查对照表后退出")
    ap.add_argument("--skip-concepts", action="store_true", help="跳过 R3 概念首现检查（词表缺席时自动跳过）")
    ap.add_argument("--include-subvaults", action="store_true",
                    help="#744-B：发现 <sub>/docs 子 vault 并逐 vault 独立校验（R1-R3 各自跑；"
                         "跨 vault 引用记 cross-vault-link 提示，不计违规、不影响退出码）")
    args = ap.parse_args()

    if args.rules:
        for rid, name, text in RULES:
            print(f"{rid} {name}: {text}")
        return 0

    # 落位解析（2026-11-03 作者令单源）：显式 --root 恒胜出（向后兼容）；缺省
    # 按 --project 解析到注册目录 .stand/docs。解析结果回写 args.root，下游零变化。
    args.root = args.root if args.root else str(resolve_project_docs_root(args.project))
    root = Path(args.root)
    if not root.is_dir():
        print(f"ERROR: vault 根不存在：{root}", file=sys.stderr)
        return 2

    excludes = args.exclude or []

    def prepare(vroot):
        """收集 + 解析器 + 词表探测（单 vault 一套，互不掺和；本 vault 的
        子 vault 目录整枝剪掉——独立图谱，#744-B）。"""
        # 剪枝只在 --include-subvaults 生效（无旗标的调用零变化——历史行为里
        # 子 vault 文档本来就并入父 vault 扫描）。
        vskip = [s[1] for s in discover_subvaults(vroot)] if args.include_subvaults else []
        vdocs, vatts = collect_docs(vroot, excludes, skip_dirs=vskip)
        vgloss = args.glossary
        if not vgloss:
            candidates = [rel for rel in vdocs if "glossary" in Path(rel).stem.lower()]
            vgloss = candidates[0] if candidates else ""
        vterms = {rel: terms_of(rel, d) for rel, d in vdocs.items()}
        vterm_resolver = make_term_resolver([t for ts in vterms.values() for t in ts])
        return vdocs, vatts, vgloss, make_resolver(list(vdocs.keys()) + vatts), vterms, vterm_resolver

    docs, attachments, glossary_rel, resolve, terms_by_rel, resolve_term = prepare(root)
    if not docs:
        print(f"ERROR: {root} 下未发现任何文档", file=sys.stderr)
        return 2

    # 纯导出先于一切校验分流：--dump-terms 不跑 check、恒 0 退出、不掺违规。
    if args.dump_terms:
        print(json.dumps(dump_terms(docs, terms_by_rel, resolve_term=resolve_term, root=root),
                         ensure_ascii=False, indent=1))
        return 0

    if args.graph:
        data = build_graph(docs, resolve, args.project, terms_by_rel, resolve_term)
        Path(args.graph).write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"graph → {args.graph}（nodes={len(data['nodes'])} edges={len(data['edges'])} unresolved={len(data['unresolved'])}）")

    # 逐 vault 独立校验（#744-B）：根 vault 恒跑；--include-subvaults 时每个
    # <sub>/docs 子 vault 用自己的 docs/词表/解析器独立跑一遍。
    subvaults = discover_subvaults(root) if args.include_subvaults else []
    vault_results = [("docs", root, docs, glossary_rel, resolve, attachments, terms_by_rel, resolve_term)]
    for name, rel, vpath in subvaults:
        vdocs, vatts, vgloss, vresolve, vterms, vterm_res = prepare(vpath)
        if vdocs:
            vault_results.append((rel, vpath, vdocs, vgloss, vresolve, vatts, vterms, vterm_res))

    per_vault = []
    vault_exempts = {}
    cross_links = []
    for rel, vpath, vdocs, vgloss, vresolve, vatts, vterms, vterm_res in vault_results:
        vexempts = []
        violations = check(vpath, vdocs, vresolve, vgloss, exclude_glossary_check=args.skip_concepts,
                           terms_by_rel=vterms, resolve_term=vterm_res, exempt_sink=vexempts)
        for e in vexempts:
            e["vault"] = "" if rel == "docs" else rel
        vault_exempts[rel] = vexempts
        per_vault.append({"vault": rel, "docs": len(vdocs), "violations": violations,
                          "counts": summarise(violations)})
        # 跨 vault 引用（info）：本 vault 未解析、但能在其它 vault 解析的链接。
        if len(vault_results) > 1:
            others = {orel: ores for orel, _op, _od, _og, ores, _oa, _t, _tr in vault_results if orel != rel}
            for vrel, doc in vdocs.items():
                for target, _anchor, _kind, line_no in doc.links:
                    if vresolve(target, from_rel=vrel):
                        continue
                    hit = resolve_cross_vault(others, target)
                    if hit:
                        cross_links.append({"from": f"{rel}/{vrel}", "line": line_no,
                                            "target": target, "inVault": hit[0], "resolved": hit[1]})

    total_violations = sum(len(r["violations"]) for r in per_vault)

    if args.json:
        if len(per_vault) == 1:
            # 无 --include-subvaults：--json 形状与历史逐字节同（机读契约不破）。
            print(json.dumps({"root": str(root), "violations": per_vault[0]["violations"],
                              "counts": per_vault[0]["counts"]}, ensure_ascii=False, indent=1))
        else:
            print(json.dumps({"root": str(root), "vaults": per_vault,
                              "crossVaultLinks": cross_links,
                              "violations": [v for r in per_vault for v in r["violations"]],
                              "counts": summarise([v for r in per_vault for v in r["violations"]])},
                             ensure_ascii=False, indent=1))
        return 1 if total_violations else 0

    if len(per_vault) == 1:
        # 单 vault：输出与历史逐字节同形（不带 --include-subvaults 的调用零变化）。
        violations = per_vault[0]["violations"]
        if violations:
            print(f"wiki-docs 校验：{len(violations)} 条违规（{root}）\n")
            by_rule = {}
            for v in violations:
                by_rule.setdefault(v["rule"], []).append(v)
            for rid, name, _text in RULES:
                bucket = by_rule.pop(name, [])
                if not bucket:
                    continue
                print(f"── {rid} {name}（{len(bucket)}）──")
                for v in bucket:
                    print(f"  {v['file']}:{v['line']}  {v['detail']}")
                print()
            for name, bucket in by_rule.items():
                print(f"── {name}（{len(bucket)}）──")
                for v in bucket:
                    print(f"  {v['file']}:{v['line']}  {v['detail']}")
                print()
        else:
            print(f"wiki-docs 校验：通过（{root}，{len(docs)} 篇文档，零违规）")
        # 豁免段（生效/悬空都显形）与按类计数小表：都追加在原有逐条清单之后，
        # R1-R6 的既有输出逐字节不动。
        print_exempts(vault_exempts.get("docs", []))
        print_rule_counts(violations, vault_exempts.get("docs", []))
        if not glossary_rel:
            print("提示：未发现词表（glossary）——R3 概念首现检查未启用。建 vault 词表：<vault>/glossary.md")
    else:
        print(f"wiki-docs 校验（逐 vault，#744-B）：{len(per_vault)} 个 vault，{total_violations} 条违规（{root}）\n")
        for r in per_vault:
            head = f"── vault {r['vault']}（{r['docs']} 篇，{len(r['violations'])} 条违规）──"
            print(head)
            for v in r["violations"]:
                print(f"  {v['rule']}  {v['file']}:{v['line']}  {v['detail']}")
            print_exempts(vault_exempts.get(r["vault"], []))
            print()
        if cross_links:
            print(f"── 跨 vault 引用（info，{len(cross_links)} 条，不计违规）──")
            for c in cross_links:
                print(f"  {c['from']}:{c['line']}  [[{c['target']}]] → {c['inVault']}/{c['resolved']}")
            print()

    return 1 if total_violations else 0


def summarise(violations):
    counts = {}
    for v in violations:
        counts[v["rule"]] = counts.get(v["rule"], 0) + 1
    return counts


if __name__ == "__main__":
    sys.exit(main())

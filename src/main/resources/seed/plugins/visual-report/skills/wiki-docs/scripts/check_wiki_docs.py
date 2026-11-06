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
                            命中 ⇒ R15 悬空词条（带路径/带锚的断链仍归 R2）
  R5 文档命名制           → 阶段件文件名 = `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`：
                            主题打头、时间块后置（时间打头 = 旧制 = 违规）；topic 不得
                            是 report/doc/notes 这类空词单独成题。校验面 = `.md` 文档；
                            无时间块的活文档（`<topic>.md`）合法；非 `.md` 资产不在本制范围
  R6 图随文档走           → 文档引用的每张图片（md `![]()`、wikilink 内嵌 `![[…]]`、
                            HTML `<img src>`）必须是 vault 内的**真实文件**，且用
                            vault 相对路径引用：绝对路径 / `file://` / `~` 家目录 /
                            `../` 越出 vault / http(s) 外链图床 / vault 内不存在
                            ⇒ 一类违规 `image-outside-vault`（理由逐条区分）。
                            行内豁免 `<!-- wiki-img-exempt: 理由 -->`（同行或上一行），
                            单条计数并在输出里显形——无整文件豁免

  ── 文档排版与缩写批（作者裁定 2026-11-05 11:09 / 11:11）─────────────
  R7 彩色克制             → 内联 HTML 着色（`<span style="color:…">`）一篇 ≤4 处
                            （建议 ≤3）、只准固定语义色（蓝 #5e6ad2 判定 / 红 #d0342c
                            风险 / 绿 #12874a 通过）、单处着色不覆盖整段（>120 字符）
  R8 正文缩进             → 正文段落首行以 `&emsp;&emsp;` 起行；半角空格缩进在 Markdown
                            渲染器里塌掉（2 空格）或变代码块（4 空格）；标题 / 列表 /
                            表格 / 引用 / 围栏 / 图片行 / 短行（<40 字符）豁免
  R9 缩写铁律             → 全大写 2-6 字母 token 与带点专有缩写（`E.C.`）在文档**首次
                            出现处**必须给出全称（括注 / 破折线式）或挂词条链接
                            `[[缩写]]`；允许清单（单位 / 通用技术词）写在
                            `../SKILL.md` 的标记块里，脚本只读不藏

  ── 写作风格批 v2（作者令 2026-11-05 12:09）──────────────────────────
  R10 平实语言           → 禁用词 / 避用词（抽象名词包裹、拟人化说法）命中即报
                            `文件:行` + 建议替换；词表写在 `../SKILL.md` 的标记块里
                            （`<!-- wiki-plainwords:start -->`），脚本只读不藏
  R11 结构三层           → 每个 `##` 章节：开头一句加粗的分点结论（缺 ⇒ 报
                            `doc-structure`）
  R16 细节折叠           → 该节敞开正文超过阈值（缺省 600 可视字符）却没有任何
                            `<details>` 折叠块 ⇒ 报 `detail-fold`（与 R11 同一批
                            裁定、同一条款，但**另占一个号**——它是一条独立断言）
  R12 图注               → 图片引用下一行（或 title 行）必须有一行图注
                            （`图 …` / `Figure …`）⇒ 缺则报 `image-caption`；
                            图注超过 120 字符 ⇒ 提示 `image-caption-long`（advisory）

  图谱面收缩（同批）：`orphan-doc`（零出链且零入链）从**违规**降为**建议**——
  它只服务图谱连线密度，不服务词条；词条面的断言（wikilink 形态、首现挂链、
  缩写展开、词条注册）一条未动。

  ── 只许引用词条批（作者令 2026-11-05；规则号 2026-11-05 让号重编到
     R13–R15——本批原挂 R7/R8/R9，与先落地的排版三禁撞号，按裁定让号）──
  R13 doc-reference       → 只许引用词条（作者令 2026-11-05）：正文裸 `.md` 路径、
                            markdown 链接指向文档、无锚短形 `[[X]]` 解析到文档却
                            解析不到已注册词条 —— 都是「引用文档」，一律违规；
                            带锚引用（`[[X#^term-slug]]` / `[[X#Heading]]`）、
                            已注册词条短形 `[[词条名]]`、`![[…]]` 嵌入通过
  R14 bare-image-path     → 图片文件名/路径以纯文本出现在正文（`attachments/a.png`、
                            `../x.jpg`、`/abs/y.png`）即违规；图片要么直接嵌入
                            （`![[a.png]]` / `![](…)` / `<img src=…>`），要么写成
                            可点击链接（`[说明](attachments/a.png)`）
  R15 dangling-term       → 无锚短形 `[[Name]]` 既解析不到文档也解析不到已注册词条
                            ⇒ 悬空（从 R2 broken-link 移出，同一行不双报）；带路径/
                            带锚的断链仍归 R2

三条门禁共用四条纪律：① 输出 `文件:行 → 原文 → 原因`；② 行内豁免
`<!-- wiki-term-exempt: 理由 -->`（同行或紧邻上一行、理由非空才生效）单独计数并
显形（含「标了但该行无违规」的悬空标注），不接受整文件豁免；③ 汇总段出按类计数
小表；④ 有违规即非零退出。

  ── 「图不显示」批（作者报障 2026-11-05；两条渲染器确定性断点）─────────
  R17 bare-name-embed      → 裸名嵌入：正文写 `![[x.png]]` 而图真身在
                              `assets/<batch>/x.png` 这类**子目录**里。前端
                              `imageCandidates()` 只找六个格位、不递归子目录，
                              六格全落空 ⇒ 页面渲染「图片加载失败」占位。违规并
                              给出**应写路径**（唯一命中一条 / 多处同名列候选）。
                              R6 判 ok（图确实在库里）与 R17 判违规并不矛盾：
                              R6 管「图在不在库内」，R17 管「渲染器找不找得到」。
  R18 fence-embed-vault-relative
                           → 围栏内嵌图**放行**（作者裁定 2026-11-05 23:40「以图
                              为主」：卡片体也走图片嵌入展开，`expandImageEmbeds()`
                              现已展开**已闭合 ```human 围栏正文**里的嵌入），但
                              路径必须是 vault 相对（含至少一级目录，如
                              `attachments/x.png` / `assets/<batch>/x.png`）：
                              裸文件名只在六格里的四格（文档同目录 + attachments/
                              assets/）成立，文档一挪就落空；vault 相对路径走第②格
                              「vault 根 + 引用」，与文档位置无关。
                              [2026-11-05 上一版本条禁令的原文保留在 git 历史]
  两条的生效面沿用 v2 的两档制（时间块 ≥ `--image-since` 硬查，更早的存量只打
  advisory；`--strict-style` 全库硬判）。两条**互斥不重叠**：围栏内的行只归
  R18（R17 让位），围栏外的行只归 R17 —— 一行只报一条，修完重跑看下一处。

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
import posixpath
import re
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import unquote

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
    ("R2", "citation-graph", "文档之间建立论文式引用关系：断链（目标/标题锚/块锚不存在，含引用词条未注册）即缺陷。零出链且零入链的孤儿文档自写作风格批 v2 起**只提示不违规**——那一条服务图谱连线密度，不服务词条（词条面由 R3/R4 与断链检查承担）"),
    ("R3", "concept-first-use", "名词概念第一次出现必须就地解释，或 [[引用]] 已解释它的文档；多篇文档不得各自发明同一概念的定义（词表 = 唯一定义源）"),
    ("R4", "term-registry", "词条注册表面：词表锚段落与 ^term-* 声明块必须按「名 — 定义」分隔约定抽出词条名（词条块结构合法）；同一文档内词条名不得重复；词条标签 v2 = 恰好一个 #proj/<slug> + 至少一个 #topic/<slug> + 其余 #facet/value，写在定义文字之后、^块锚之前，缺 project 或 topic 即 term-tag-missing；`[[名]]` 短形引用经词条注册表消解（词表命中优先，未注册 = R2 断链）"),
    ("R5", "doc-naming", "文档命名制：阶段件文件名 = `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`（topic 前置、时间块后置，`__` 分主题与时间、时间块内用 `_`）；时间打头（`^YYYYMMDD_...`）即旧制即违规；topic 不得是 report/doc/notes 空词单独成题。校验面 = `.md` 文档，无时间块的活文档合法，非 `.md` 资产不在本制范围"),
    ("R6", "image-outside-vault", "图随文档走：文档引用的图片（`![](…)` / `![[…]]` / `<img src>`）必须是与文档同住 vault 的真实文件，用 vault 相对路径引用；绝对路径 / `file://` / `~` / `../` 越出 vault / http(s) 外链图床 / vault 内不存在 = 违规（禁止 reports-evidence、worktree、`/tmp`、`~/.stand/uploads|evidence` 这类会消失的位置）。行内豁免 `<!-- wiki-img-exempt: 理由 -->` 单条计数并显形，无整文件豁免"),
    ("R7", "doc-color", "彩色克制（作者裁定 2026-11-05 11:09「适当使用彩色字体」）：内联 HTML 着色 `<span style=\"color:…\">` 一篇 ≤4 处（建议 ≤3），只准三个固定语义色——蓝 #5e6ad2 = 结论/判定、红 #d0342c = 风险/警告、绿 #12874a = 已验证/通过；单处着色不得覆盖整段（>120 字符）；一般强调用加粗，不许满篇彩色"),
    ("R8", "doc-indent", "正文缩进（作者裁定 2026-11-05 11:09「以及缩进」）：正文段落首行以 `&emsp;&emsp;` 起行（`&emsp;` 是 HTML 实体，Obsidian 与 marked 两渲染面都稳定生效；两个半角空格会被折叠、四个半角空格变代码块）。标题 / 列表 / 表格 / 引用 / 围栏 / 图片行 / HTML 块豁免；可视长度 <40 字符的短行豁免"),
    ("R9", "abbrev-expansion", "缩写铁律（作者裁定 2026-11-05 11:11「GCR、ICRP 这种缩写、机构缩写也要用词条解释」）：全大写 2-6 字母 token 与带点专有缩写（E.C. / U.S.）在**文档首次出现处**必须给出全称（前后括注或破折线式）或挂词条链接 `[[缩写]]`；单位与通用技术词按 SKILL.md 的允许清单标记块豁免；围栏代码块（含 Mermaid / HTML / SVG 样张）、行内代码、HTML 标签属性、URL、human 围栏内不扫"),
    ("R10", "plain-language", "平实语言（作者令 2026-11-05 12:09「不要用晦涩难懂的词汇，比如人面子，印象」）：禁用词/避用词表写在 SKILL.md 的 `<!-- wiki-plainwords:start -->` 标记块（`词 => 建议替换` 一行一条），命中即报 `文件:行` + 建议；能用平实动词+名词说清的，不用抽象名词包裹。围栏代码块与行内代码不扫（引原词谈词表是合法的）"),
    ("R11", "doc-structure", "结构三层（同批「整体文档先有总体结论，然后再是各分点的结论」）：每个 `##` 章节开头第一行必须是加粗的一句话分点结论（`**…**`，≤80 可视字符）；词条 / 附录 / 参考文献 / 变更记录这类结构节豁免——它们不是论述节"),
    ("R12", "image-caption", "图注（同批「图里尽量少文字解释，文字解释放到文档里，比如图的 caption」）：每个图片引用必须有一行图注——同一行后随的 `图 N …` / `Figure …`，或图片下一行（最多隔一空行）的图注行；缺则报。图注超过 120 字符 ⇒ 提示 `image-caption-long`（advisory：图注该是一句结论，长解释进正文）"),

    # 只许引用词条批：2026-11-05 让号重编到 R13–R15（原 R7/R8/R9 与上方排版三禁撞号）
    ("R13", "doc-reference", "只许引用词条（作者令 2026-11-05「只有引用词条，不允许引用文档」）：正文裸 `.md` 路径（`见 design/foo.md`）、markdown 链接指向文档（`[x](foo.md)` / `[x](foo.md#sec)`）、无锚短形 `[[X]]` 解析到文档却解析不到已注册词条 —— 三种形态都算「引用文档」= 违规；带锚引用（`[[X#^term-slug]]` / `[[X#Heading]]`）、已注册词条短形 `[[词条名]]`、`![[…]]` 嵌入通过。行内代码/围栏不算"),
    ("R14", "bare-image-path", "图片路径不得以纯文本出现：`attachments/a.png`、`../x.jpg`、`/abs/y.png` 写成正文即违规。图片只有两种合法形态——直接嵌入（`![[a.png]]` / `![](…)` / `<img src=…>`）或可点击跳转（`[说明](attachments/a.png)`）。行内代码/围栏不算"),
    ("R15", "dangling-term", "悬空词条引用：无锚短形 `[[Name]]` 既解析不到文档也解析不到已注册词条 ⇒ 违规（该形状从 R2 broken-link 移出，同一行不双报；带路径/带锚的断链仍归 R2 的 broken-anchor / broken-block）"),

    # 重号修正批（2026-11-05 14:xx）：detail-fold 原与 doc-structure 同时占 R11，
    # 让号到表尾 R16（最大号之后）。同一条款「细节放在点击展开后的内容」的两个
    # 独立断言从此各占一个号：R11 = 章节首行加粗结论，R16 = 敞开细节必须折叠。
    ("R16", "detail-fold", "细节折叠（同批「具体的论证细节，放在点击展开后的内容」）：一个 `##` 章节里敞开的正文超过阈值（缺省 600 可视字符，`--fold-threshold` 可调）却没有任何 `<details>` 折叠块 ⇒ 报；表格 / 列表 / 代码块 / 图注不算「敞开的长论证」，只算敞开的句子"),

    # 「图不显示」批（2026-11-05 作者报障）：两条堵的是渲染器确定性断点，
    # 不是风格偏好——命中即「页面必然不出图」。条款原文见 SKILL.md ⑥ 的两个绑定点。
    ("R17", "bare-name-embed", "图片引用必须是**渲染器能解析的路径**：正文写裸文件名 `![[x.png]]`（或 `![](x.png)` / `<img src=\"x.png\">`）时，前端只找六格——文档同目录 / vault 根 / `<vault>/attachments/` / `<vault>/assets/` / `<docdir>/attachments/` / `<docdir>/assets/`，**不递归子目录**；图真身在 `assets/<batch>/x.png` 这类二级目录而引用是裸名 ⇒ 一律报，并给出应写路径（唯一命中给一条；多处同名列候选）。R6 只管「图在不在库内」，本条管「渲染器找不找得到」"),
    ("R18", "fence-embed-vault-relative", "```human 卡片体里的图片嵌入**允许**（前端自卡片出图批 2026-11-05 起展开已闭合围栏正文里的 `![[…]]`：围栏是卡片体不是代码面），但路径必须是 **vault 相对路径**——带目录分量，如 `attachments/x.png` / `assets/<batch>/x.png`。裸文件名 `![[x.png]]` 只在渲染器六格里的四格成立（文档同目录 + `<vault>/attachments|assets`），文档一挪就落空；vault 相对路径走第②格「vault 根 + 引用」，与文档位置无关 ⇒ 卡片体（整篇文档的门面）里写裸名 = 违规，并给出应写路径。与 R17 按位置分片：围栏内归本条、围栏外归 `bare-name-embed`，一行只报一条"),

    # 以图为主批（作者令 2026-11-05 21:24「一定要以图为主……文字要尽量少……
    # 不得不补充的细节，就放在可点击展开的块里」）：条款原文见 SKILL.md ⑬。
    ("R19", "image-required", "以图为主：一篇文档至少要有一张图——图片引用（`![[…]]` / `![](…)` / `<img src>`）或 ```mermaid 围栏块，两种形态都算。一张图都没有的文档读者拿不到「图里一眼看懂」的那一层，只剩散文 ⇒ 违规。例外必须写在文档里：`<!-- wiki-figure-exempt: 理由 -->`（理由非空，对应条款 ⑬(d)「允许例外但必须显式声明为什么」）"),
    ("R20", "stacked-detail", "逐条细节不得敞在正文：一个 `##` 章节里连续 ≥12 行「逐条细节」（列表项 / 代码围栏内的行；```mermaid 与表格不算——前者是图、后者是压缩形态）且该节没有 `<details>` 折叠块 ⇒ 违规，提示移入 `<details><summary>过程</summary>`。已被折叠包住的行不判；一节只报最长那一段（缺省 12 行，`--fold-run` 可调）"),
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


# ── R6 图随文档走（图片物理位置；作者令 2026-11-05 09:24「文档相关的图片要放
# 文档里」；条款原文见 wiki-docs SKILL.md 强制条款 ⑥）───────────────────
#
# 抓取三种图片引用形态：markdown `![alt](path)`、wikilink 内嵌 `![[path]]`、
# HTML `<img src="path">`。断言四条：
#   ① vault 相对路径（非绝对、非 `~`、非 `file://`、非 http(s)）；
#   ② 解析后落在 vault 内（`../` 不得逃出 docs/）；
#   ③ 文件真实存在（引用即断链 = 违规）；
#   ④ 自包含 `data:` 内联算通过（字节就在文档里，无外链）。
# 解析语义两制并行：wikilink 内嵌按 Obsidian 语义（vault 相对 / 全库唯一 basename），
# markdown / HTML 按文档所在目录相对（再回退 vault 相对 / 唯一 basename），
# 与别处链接解析同一套宽严。
#
# 行内豁免：`<!-- wiki-img-exempt: 理由 -->`，与本条图片引用同行或紧邻上一行，
# 理由非空才生效；逐条计数并在输出里显形（悬空标注——标了但该行无图片引用——
# 同样打出，杜绝「整文件豁免」的错觉）。

IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".bmp", ".avif", ".ico", ".tif", ".tiff"}
MDIMAGE_RE = re.compile(r"!\[([^\]]*)\]\(\s*([^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)")
IMG_TAG_SRC_RE = re.compile(r"<img\b[^>]*?\bsrc\s*=\s*([\"']?)([^\"'\s>]+)\1", re.IGNORECASE)
IMG_EXEMPT_RE = re.compile(r"<!--\s*wiki-img-exempt\s*:\s*(.*?)\s*-->")
IMAGE_SCHEME_RE = re.compile(r"^([A-Za-z][A-Za-z0-9+.\-]*):(?://)?")
IMAGE_DATA_URI_RE = re.compile(r"^data:", re.IGNORECASE)

# 行内代码 `…` 里的图片语法是「语法样张」，不渲染成图 ⇒ 不算图片引用
# （链接面保持历史语义不变：prose 里的裸 [[…]] 仍按真链接解析）。
INLINE_CODE_RE = re.compile(r"(`+)(.+?)\1", re.DOTALL)

# 易失位置提示（命中只在违规理由里点名，帮助整改时认位置）
VOLATILE_IMAGE_HINTS = (
    ("reports-evidence/", "仓内 reports-evidence/（随仓库/工作树清理消失）"),
    (".nebflow/worktrees/", "worktree 路径（工作树一收就断）"),
    ("/tmp/", "/tmp 临时目录（重启即失）"),
    (".stand/uploads/", "~/.stand/uploads（上传缓存，会被清理）"),
    (".stand/evidence/", "~/.stand/evidence（证据区，会被清理）"),
)

# 图外挂提示（info 面，不计违规）：正文/围栏里点名 vault 外证据树的路径。
# 这类文档「图跟着仓库走」的风险最高——本批只提示，不改文档。
VOLATILE_PATH_RE = re.compile(
    r"(?:reports-evidence/|\.nebflow/worktrees/|\.stand/uploads/|\.stand/evidence/)"
    r"[^\s`\"'()<>|，。、：；（）【】「」《》]*"
)


# ── R17 裸名嵌入 / R18 human 围栏内嵌图（「图不显示」批；作者报障
# 2026-11-05，样本《agent-panel-three-row__20261105_201500.md》）─────────
#
# 这两条堵的不是风格问题，是**渲染器的确定性断点**：文档写对了、图也在库里，
# 页面照样出「图片加载失败」占位或一行字面量。根因在两处前端代码：
#
#   R17 bare-name-embed  ｜ `web/js/viewers/markdown.js` 的 `imageCandidates()`
#     候选表只有固定几格 —— ①文档同目录 ②vault 根 ③`<vault>/attachments/<ref>`
#     ④`<vault>/assets/<ref>` ⑤`<docdir>/attachments/<ref>` ⑥`<docdir>/assets/<ref>`
#     —— **一律不递归子目录**。正文写裸文件名 `![[x.png]]` 而图真身在
#     `assets/<batch>/x.png` 时六格全落空 ⇒ 占位失败。R6 对这种情况判 **ok**
#     （全库 basename 唯一命中 = 「图确实在库里」），这正是「检查全绿、图不显示」
#     的那条缝：R6 管「图在不在库内」，R17 管「渲染器找不找得到」。
#     判定：渲染器候选表解析不到、但同名文件在 vault 内确实存在 ⇒ 违规，并把
#     **应写路径**直接打出来（唯一命中给一条，多处同名给候选清单让作者选）。
#
#   R18 fence-embed-vault-relative  ｜ **围栏内嵌图本身已放行**（作者裁定
#     2026-11-05 23:40「卡片体也走图片嵌入展开」：`expandImageEmbeds()` 现在展开
#     **已闭合 ```human 围栏正文**里的嵌入 —— 围栏不是代码面，是人面卡片体；
#     见 `humanCardBodyLines()`）。放开位置之后剩下的那条真断点是**路径形态**：
#     卡片体里的嵌入必须是 vault 相对路径（含目录，`attachments/x.png` /
#     `assets/<batch>/x.png`），因为它走渲染器第②格「vault 根 + 引用」，与文档
#     所在目录无关；裸文件名只在六格里的四格成立（文档同目录 +
#     `<vault>/attachments|assets`），文档换目录就落空 —— 而卡片体正是「整篇
#     文档的门面」，最不该有一条位置依赖的引用。⇒ 围栏内裸名 = 违规，给出
#     vault 相对路径的应写形态。与 R17 **按位置分片**：围栏内的行只归 R18，
#     围栏外的行只归 R17，一行只报一条。
#
# 生效面沿用 R10-R12 的两档制：时间块 ≥ IMAGE_EMBED_STAMP 的阶段件硬查（进违规、
# 动退出码），更早的存量只打 advisory（fix what you touch；`--strict-style` 全库
# 整改时一并硬判）。理由同 v2：本制之前写下的文档不知道有这两条断点，硬判它们
# 等于凭空造违规；但读数必须打出来——存量命中多少、分布在哪，是作者的整改清单。
# 注：R18 的语义在 2026-11-05 卡片出图批**反转**过一次（原为「围栏内嵌图 = 违规」），
# 时间界沿用 IMAGE_EMBED_STAMP 不变——同一批文档同一批作者，只是规则方向换了。

IMAGE_EMBED_STAMP = "20261105_130000"

# 前端 imageCandidates() 的候选表（vault 相对形态，顺序与优先级一一对应）。
# 只镜像**路径语义**，不镜像 HTTP 铸票/级联——本脚本判「能否命中」，不判「怎么取字节」。
RENDERER_VAULT_ATTACH_DIRS = ("attachments", "assets")
RENDERER_DOC_SUBDIRS = ("attachments", "assets")

# 图片引用按语法决定的「应写形态」模板（修正建议直接可粘贴）
IMAGE_EMBED_SYNTAX_TPL = {
    "wikilink": "![[{path}]]",
    "markdown": "![]({path})",
    "html": '<img src="{path}">',
}


# ── R19 必须有图 / R20 逐条细节未折叠（以图为主批；作者令 2026-11-05 21:24）──
#
# 作者原话：「一定要以图为主，我们 visual report 很多内容都是关于如何使用专业的
# 工具画出更好的图。文字要尽量少，只说关键内容，然后不得不补充的细节，就放在
# 可点击展开的块里。」
#
# 两条机器面各管一头——「有没有图」与「文字有没有超载」：
#
#   R19 image-required  ｜ 文档一篇图都没有 ⇒ 违规。图的判据两种形态都算：
#     ① 图片引用（`![[…]]` / `![](…)` / `<img src>`，与 R6/R17/R18 同一抓取面）；
#     ② ```mermaid 围栏块（图也可以是代码画的图）。豁免必须写在文档里：
#     `<!-- wiki-figure-exempt: 理由 -->`（理由非空），对应条款 ⑬(d)
#     「允许例外，但必须在文档里显式声明为什么例外」。
#
#   R20 stacked-detail  ｜ 一个 `##` 章节里出现**连续 ≥ STACKED_RUN_MIN 行**的
#     「逐条细节」却没有 `<details>` 折叠块 ⇒ 违规，提示移入折叠块。逐条细节的
#     判据收得很窄（宁可漏报也不误伤）：
#       · 列表项（`- ` / `* ` / `+ ` / `1. `）——逐条清单 / 步骤堆叠；
#       · 代码围栏内的行（```bash 的命令清单、日志读数；```mermaid 除外——它是图）。
#     表格**不算**：表格是压缩形态（R16 同一口径），一屏能扫完的读数表不算铺散文。
#     已被 `<details>` 包住的行不算（已经折了）。阈值/判据写在此处，报告里逐字
#     照抄，作者可复核；`--fold-run` 可调。
#
# 生效面：R19/R20 有自己的时间界 FIGURE_STAMP（= 本条裁定的时刻），比它早的
# 存量只打 advisory（fix what you touch）；`--strict-style` 全库硬判。

FIGURE_STAMP = "20261105_212400"
STACKED_RUN_MIN = 12
FIGURE_EXEMPT_RE = re.compile(r"<!--\s*wiki-figure-exempt\s*:\s*(.*?)\s*-->")
FOLD_EXEMPT_RE = re.compile(r"<!--\s*wiki-fold-exempt\s*:\s*(.*?)\s*-->")
MERMAID_FENCE_RE = re.compile(r"^\s*(?:```+|~~~+)\s*mermaid\b", re.IGNORECASE)
SECTION_HEADING_RE = re.compile(r"^##\s+\S")
STACKED_LIST_RE = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+\S")
# 「结构性文档」不判 R19：目录 / 词表 / README 是导航与词条面，本来就不是图文报告
# （与孤儿文档豁免同一份理由表，见 R2 段的 index/glossary/readme）。
STRUCTURAL_DOC_STEMS = {"index", "readme", "glossary"}


# ── R7 彩色 / R8 缩进 / R9 缩写（文档排版与缩写批；作者裁定 2026-11-05
# 11:09「适当使用彩色字体，以及缩进」与 11:11「补充词条包含的范围，GCR、
# ICRP 这种缩写，机构缩写，也需要用词条解释」；条款原文见 wiki-docs
# SKILL.md 强制条款 ⑦（排版）与 ⑧（缩写铁律））──────────────────────
#
# 三件事的机器面：
#   R7 彩色  —— 居中/额度：一篇 ≤4 处（建议 ≤3），只准三个语义色，单处不覆盖整段。
#                颜色语义钉死，是因为颜色一旦自由发散就变成噪音：读者要在
#                「红色 = 出事了」上形成条件反射，就不能有第二种红。
#   R8 缩进  —— 段首 `&emsp;&emsp;`。渲染器实测（引擎自带 marked v12.0.2）：
#                两个半角空格 → `<p>  文本</p>`，HTML 折叠空白 ⇒ 视觉上塌掉；
#                四个半角空格 ⇒ `<pre><code>` 代码块（经典陷阱）；
#                `&emsp;&emsp;` ⇒ 实体原样留在 `<p>` 里，全角两格缩进稳定生效，
#                Obsidian（同为 HTML 实体解码）同理。
#   R9 缩写  —— 全大写 2-6 字母 token + 带点专有缩写（`E.C.` / `U.S.`；`e.g.` /
#                `i.e.` 全小写天然不匹配）在文档首次出现处必须展开或挂词条。
#                允许清单不藏脚本里：从 `../SKILL.md` 的标记块读取（见
#                ALLOWLIST_* 常量），脚本只做解析与报数。
#
# 生效面（存量过渡）：R7-R9 是 2026-11-05 之后的新制，全库 400 篇存量文档
# 不可能同批整改。判定分两档——
#   · 硬查（计入 violations、影响退出码）：阶段件文件名时间块 ≥
#     STYLE_CONTRACT_STAMP、或 frontmatter 显式声明 `style:`（作者/新文档主动
#     入制）、或 --strict-style（全库整改时用）。
#   · advisory（打印但不计违规、不影响退出码）：其余存量文档。改到哪篇，
#     哪篇入制——「fix what you touch」。

STYLE_CONTRACT_STAMP = "20261105_111100"

# 写作风格批 v2（作者令 2026-11-05 12:09）自己的生效界：R10/R11/R12/R16 从这一刻
# 起对「时间块 ≥ 此值」的阶段件硬查。与 R7-R9 的界分开，是因为两批的整改责任
# 不同——12:09 之前写的文档不知道有禁用词表与折叠骨架，硬判它们等于凭空造违规。
# 取值 = 本批交付时刻（新规则只判它之后写下的文档；存量走 advisory，全库整改用
# --strict-style）。frontmatter 的 `style:` 是上一批的开关，不追溯适用于本批。
STYLE_V2_STAMP = "20261105_130000"

# 固定语义色（三色都实测过亮/暗两主题对比度，见 SKILL.md 条款 ⑦）
DOC_COLOR_PALETTE = {
    "#5e6ad2": "结论/判定（蓝，插件主强调色，白底 4.70:1 / 暗底 3.85:1）",
    "#d0342c": "风险/警告（红，白底 4.99:1 / 暗底 3.62:1）",
    "#12874a": "已验证/通过（绿，白底 4.57:1 / 暗底 3.95:1）",
}
DOC_COLOR_BUDGET = 4          # 硬顶（建议 ≤3）
DOC_COLOR_SPAN_MAX = 120      # 单处着色覆盖的字符上限（超了就是整段刷色）
COLOR_STYLE_ATTR_RE = re.compile(r"""style\s*=\s*(["'])(.*?)\1""", re.IGNORECASE | re.DOTALL)
COLOR_VALUE_RE = re.compile(r"(?<![-\w])color\s*:\s*([^;\s\"'!]+)", re.IGNORECASE)
HTML_TAG_RE = re.compile(r"<[^>]+>")

INDENT_MARKER = "&emsp;&emsp;"
INDENT_RE = re.compile(r"^(?:&emsp;){2,}|^\u3000{2,}")
INDENT_MIN_CHARS = 40         # 短于这个可视长度的行不当「正文段落」（标签行/清单行）
INDENT_BARE_SPACES_RE = re.compile(r"^ {4,}\S")   # 四空格 = markdown 代码块的陷阱
INDENT_EXEMPT_START_RE = re.compile(
    r"^\s*(?:#{1,6}\s|[-*+]\s|\d+[.)]\s|>|\||<|!\[|\[\[|\$\$|-{3,}\s*$|\*{3,}\s*$|<!--)"
)

# 缩写 token：全大写 2-6 字母（不吃 `SiPM`/`MeV` 这类大小写混排，也不用为它们
# 维护清单）；带点专有缩写 `E.C.` / `U.S.` / `R.B.E.`（`e.g.`/`i.e.` 是小写，不匹配）。
ABBREV_CAPS_RE = re.compile(r"(?<![A-Za-z0-9_])([A-Z][A-Z]{1,5})(?![A-Za-z0-9_])")
ABBREV_DOTTED_RE = re.compile(r"(?<![A-Za-z0-9_.])((?:[A-Z]\.){2,5})(?![A-Za-z0-9_])")
URL_RE = re.compile(r"(?:https?|ftp)://\S+|www\.\S+", re.IGNORECASE)
FILE_EXT_TAIL = ("md", "markdown", "json", "jsonl", "py", "js", "mjs", "ts", "html",
                 "htm", "svg", "png", "jpg", "jpeg", "gif", "txt", "yml", "yaml",
                 "sh", "scala", "java", "css", "csv", "pdf", "toml")

# 允许清单：写在规范文件本身（`../SKILL.md` 的标记块），脚本只负责读。
ALLOWLIST_START = "<!-- wiki-abbrev-allowlist:start -->"
ALLOWLIST_END = "<!-- wiki-abbrev-allowlist:end -->"
ALLOWLIST_DEFAULT_DOC = Path(__file__).resolve().parent.parent / "SKILL.md"
ALLOWLIST_TOKEN_RE = re.compile(r"^[A-Za-z][A-Za-z0-9.]*$")


# ── R10 平实语言 / R11 结构三层 / R12 图注 / R16 细节折叠（写作风格批 v2；作者令 2026-11-05
# 12:09「不要用晦涩难懂的词汇……采用简要总结和细节的方式……先有总体结论，然后再
# 是各分点的结论，具体的论证细节放在点击展开后的内容……图里尽量少文字解释，文字
# 解释放到文档里，比如图的 caption」；条款原文见 SKILL.md ⑨⑩⑪）───────────
#
# 三件事的机器面：
#   R10 禁用词 —— 词表是**规范文件的一部分**（`../SKILL.md` 标记块，一行一条
#                `词 => 建议替换`），脚本只读。判定口径：能用平实动词+名词说清
#                的，不用抽象名词包裹。围栏代码块与行内代码不扫——引原词谈词表
#                本身就是合法的写法。
#   R11 结构  —— ① `##` 章节第一行必须是加粗的一句话结论。判据是「读者先看到
#                答案」，不是行数配额。
#   R16 折叠  —— ② 敞开正文超过阈值却没有 `<details>` 折叠块。判据是「细节点
#                一下才出来」。（R11/R16 出自同一条款「细节放在点击展开后的
#                内容」，但编号批 v2 重号修正时把 ② 拆出来独占 R16。）
#   R12 图注  —— 图里不放解释性长句，解释放图注与正文；因此没有图注的图就是
#                一张读者解不开的图。

PLAIN_BLOCK_START = "<!-- wiki-plainwords:start -->"
PLAIN_BLOCK_END = "<!-- wiki-plainwords:end -->"
PLAIN_DEFAULT_DOC = ALLOWLIST_DEFAULT_DOC          # 同一份规范文件
PLAIN_LINE_RE = re.compile(r"^(?P<word>[^#\s(（].*?)\s*(?:=>|→)\s*(?P<hint>\S.*)$")

STRUCTURE_HEADING_LEVEL = 2        # `##` = 章节（H1 只作文档标题）
STRUCTURE_SKIP_HEADINGS = ("词条", "附录", "参考文献", "引用文献", "references",
                           "reference", "变更记录", "changelog", "目录", "index",
                           "terms", "glossary", "术语表")
CONCLUSION_BOLD_RE = re.compile(r"^\s*(?:\*\*|<span\b[^>]*>\s*\*\*|<b>)")
CONCLUSION_MAX_CHARS = 80          # 「一句话」的上限；超了就不是结论而是段落
CONCLUSION_PROSE_MIN = 40          # 有 ≥40 可视字符的正文行，才算「论述节」
DETAIL_FOLD_THRESHOLD = 600        # 敞开正文的可视字符阈值（--fold-threshold 可调）
DETAILS_OPEN_RE = re.compile(r"<details\b", re.IGNORECASE)
DETAILS_CLOSE_RE = re.compile(r"</details\s*>", re.IGNORECASE)

CAPTION_RE = re.compile(r"^\s*(?:<[^>]+>\s*)*(?:\*\*)?\s*(?:图|图注|附图|Figure\s+\d|Fig\.?\s*\d)", re.IGNORECASE)
CAPTION_LOOKAHEAD = 2              # 图注行最多在图片行之后第 2 行（允许隔一个空行）
CAPTION_MAX_CHARS = 120            # 图注超过这个长度 ⇒ 提示（说明该是一句结论）

# ── R13/R14/R15 引用门禁（作者令 2026-11-05：只许引用词条）────────────────
#
# 作者原话：「以后我们就只允许引用词条，不允许引用文档」「不允许引用文档和图片
# 路径，文档就用词条，图片要么直接嵌入，要么就可以点击跳转」。三条机器面：
#
#   R13 doc-reference   ｜ 正文裸 `.md` 路径 / markdown 链接指向文档 / 无锚短形
#                          `[[X]]` 解析到文档却解析不到已注册词条
#   R14 bare-image-path ｜ 图片文件名/路径以纯文本出现（不在嵌入/可点击链接里）
#   R15 dangling-term   ｜ 无锚短形 `[[Name]]` 文档与词条双双解析不到（脱离 R2）
#
# 规则号 2026-11-05 让号重编：本批原挂 R7/R8/R9，与先落地的排版三禁（R7 doc-color /
# R8 doc-indent / R9 abbrev-expansion）撞号；排版三禁已在 main 生效且已有回归，故由
# 本批让号到当前最大号之后的 R13–R15，判定语义一字未改。
#
# 靶场只在**正文残片**上打：围栏行上游已剔（body_lines），行内代码 span、wikilink
# （含 ![[嵌入]]）、markdown 链接/图片（`[x](…)` / `![](…)`）、HTML 标签
# （`<img src>`）、裸 URL 都是合法承载面，先整段剥掉再找裸路径——否则
# `![[a.png]]` 里的 `a.png` 会被误判成 R14。

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
        self.image_refs = []              # R6：[{target, syntax, line, exempt|None}]
        self.image_exemptions = []        # R6：全部豁免标注 [(line, reason)]（含悬空标注）
        self.volatile_mentions = []       # R6 info 面：vault 外证据树路径点名 [(line, path)]
        self.human_lines = set()          # 行号集合：```human 围栏内的行（人面子 = 内容面）
        self.color_spans = []             # R7：[{line, color, text}]
        self.abbrev_occ = []              # R9：[{line, col, token, cited, expanded, hit}]
        self.lower_words = set()          # R9：本档出现过小写形态的词（全大写 = 强调，不是缩写）
        self.para_starts = []             # R8：正文段落首行 [(line, text)]
        self.raw_lines = []               # 原始行（R10-R12 要看得见 human 围栏与 details 块）
        self.fence_mask = []              # 代码围栏屏蔽表（与 raw_lines 等长）
        self.body_start = 0               # front-matter 之后的行索引
        self.plainwords = []              # R10：[{line, word, hint}]
        self.sections = []                # R11：[{line, heading, first_prose, prose_chars, has_details}]
        self.captions = []                # R12：[{line, target, caption, caption_line, too_long}]


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


def human_fence_mask(lines):
    """` ```human ` 围栏内的行（人面子是内容面，不是代码面）。

    R6 用它把围栏屏蔽「开个口」：报告头的关键图（条款 ⑤ 要求 ≥1 张，且必须写在
    human 围栏里）正是最该被查位置的图——一律当代码样张跳过就等于漏掉主图。
    其它围栏（```markdown / ```bash / 无语言）仍是代码样张面，不产生图片引用。"""
    mask = [False] * len(lines)
    state = ""          # "" 行外 | "human" 人面子围栏内 | "other" 其它围栏内
    marker = ""
    for i, line in enumerate(lines):
        m = FENCE_RE.match(line)
        if not state:
            if m:
                info = line.strip()[len(m.group(1)):].strip().lower()
                state = "human" if info.startswith("human") else "other"
                marker = m.group(1)[:3]
            continue
        if m and m.group(1).startswith(marker):
            state = ""
            marker = ""
            continue
        if state == "human":
            mask[i] = True
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
    doc.raw_lines = lines
    doc.fence_mask = mask
    doc.body_start = body_start
    human = human_fence_mask(lines)
    doc.human_lines = {i + 1 for i, flag in enumerate(human) if flag}
    collect_image_refs(doc, lines, mask, body_start)
    markdown = rel.lower().endswith((".md", ".markdown"))
    if markdown:
        doc.lower_words = collect_lower_words(text)
        collect_color_spans(doc, lines, mask)
        doc.para_starts = paragraph_starts(doc)
        collect_abbrev_occurrences(doc, lines, mask)
        collect_caption_refs(doc)
        analyse_sections(doc)
    return doc


def collect_lower_words(text):
    """本档里以「含小写字母」形态出现过的词（大写归一）→ {TOKEN}。

    用途：`ALL` / `PASS` / `HEAD` / `SKILL` 这类全大写只是强调或文件名残片，同一份
    文档里往往同时有小写形态（all / pass / head / skill）——那是英语单词，不是缩写，
    不能要求它「首次出现给全称」。判定只看同档自证，不引入外部词典。"""
    words = set()
    for m in re.finditer(r"(?<![A-Za-z0-9_])([A-Za-z][A-Za-z']{1,5})(?![A-Za-z0-9_])", text):
        word = m.group(1)
        if word != word.upper():
            words.add(word.upper())
    return words


def collect_image_refs(doc, lines, mask, body_start):
    """R6：抓文档里的图片引用（md `![]()` / wikilink 内嵌 `![[…]]` / `<img src>`）。

    fence 屏蔽：代码围栏里的示例不算引用（语法样张不是真图）。
    豁免标注 `<!-- wiki-img-exempt: 理由 -->` 同行或紧邻上一行生效（理由非空）；
    全部标注都登记进 doc.image_exemptions，悬空标注在输出里单列。"""
    exempt_by_line = {}
    human = human_fence_mask(lines)

    def content_line(i):
        """R6 的扫描面：非围栏行，或 human 围栏内的行（人面子是内容面）。"""
        return (not mask[i]) or human[i]

    for i in range(body_start, len(lines)):
        if not content_line(i):
            continue
        for m in IMG_EXEMPT_RE.finditer(lines[i]):
            reason = m.group(1).strip()
            if reason:
                exempt_by_line[i + 1] = reason
                doc.image_exemptions.append((i + 1, reason))
    for i in range(body_start, len(lines)):
        if not content_line(i):
            continue
        line = lines[i]
        line_no = i + 1
        scan = INLINE_CODE_RE.sub(lambda m: " " * len(m.group(0)), line)  # 行内代码样张不算引用
        found = []
        for m in WIKILINK_RE.finditer(scan):
            if m.group(1) != "!":
                continue
            target = m.group(2).split("|")[0].split("#")[0].strip()
            if Path(target).suffix.lower() in IMAGE_EXTENSIONS:
                found.append((target, "wikilink"))
        for m in MDIMAGE_RE.finditer(scan):
            found.append((m.group(2).strip(), "markdown"))
        for m in IMG_TAG_SRC_RE.finditer(scan):
            found.append((m.group(2).strip(), "html"))
        for target, syntax in found:
            exempt = exempt_by_line.get(line_no) or exempt_by_line.get(line_no - 1)
            doc.image_refs.append({"target": target, "syntax": syntax,
                                   "line": line_no, "exempt": exempt})
    # info 面（不计违规）：正文与围栏里的 vault 外证据树路径（取证入口常写进代码块）
    for i in range(body_start, len(lines)):
        for m in VOLATILE_PATH_RE.finditer(lines[i]):
            doc.volatile_mentions.append((i + 1, m.group(0)))


# ── R7 彩色 / R8 缩进 / R9 缩写：解析面采集 ──────────────────────────

def visible_len(text):
    """段落可视长度：去掉缩进实体与行内标记，CJK 按 1 字符计。"""
    t = INDENT_RE.sub("", text)
    t = WIKILINK_RE.sub(lambda m: m.group(2).split("|")[-1], t)
    t = MDIMAGE_RE.sub("", t)
    t = HTML_TAG_RE.sub("", t)
    t = t.replace("`", "")
    t = re.sub(r"[*_]", "", t)
    t = re.sub(r"\s+", " ", t)
    return len(t.strip())


def paragraph_starts(doc):
    """正文段落首行（R8 判定面）→ [(line_no, text)]。

    段落首行 = 非空、非结构性行（标题 / 列表 / 表格 / 引用 / 围栏 / 图片 / HTML /
    分隔线）、且不是上一行的续行。人面子（```human 围栏）自带卡片版式，不套段首缩进。
    四空格起行的行单独作为「段落」收进来——它会被渲染成代码块，正是要报的形态。"""
    starts = []
    prev_no = None
    prev_open = False       # 上一行是否让本行成为「续行」
    prev_blank = True
    for line_no, text in doc.body_lines:
        stripped = text.strip()
        contiguous = prev_open and prev_no is not None and line_no == prev_no + 1
        prev_no = line_no
        if not stripped:
            prev_open, prev_blank = False, True
            continue
        if line_no in doc.human_lines:
            prev_open, prev_blank = False, False
            continue
        if HEADING_RE.match(text) or INDENT_EXEMPT_START_RE.match(text):
            prev_open, prev_blank = False, False
            continue
        if CAPTION_RE.match(text.strip()):
            prev_open, prev_blank = False, False      # 图注行：紧贴图片，不缩进（R12 面）
            continue
        bare_spaces = bool(INDENT_BARE_SPACES_RE.match(text))
        # `not prev_blank` 是 R8 的判定命门（2026-11-05 收尾批修）：上一行是空行 ⇒
        # 本行是**段首**，哪怕它只用一两个半角空格起行也仍然是段首——两个半角空格
        # 不是缩进（渲染器折叠），正是 R8 要报的形态。缺这一项时这类行被当成
        # 「缩进续行」直接吞掉，段首缩进不足在 2 空格起行上假通过（实测零报错）。
        if text[:1].isspace() and not bare_spaces and not prev_blank:
            prev_open, prev_blank = True, False      # 列表续行 / 缩进续行
            continue
        if contiguous and not bare_spaces:
            prev_open, prev_blank = True, False      # 段落续行
            continue
        if bare_spaces and not prev_blank:
            prev_open, prev_blank = True, False      # 列表项内的四空格续行
            continue
        starts.append((line_no, text))
        prev_open, prev_blank = True, False
    return starts


def collect_color_spans(doc, lines, mask):
    """R7：抓内联 HTML 着色（`style="…color:…"`）→ [{line, color, text}]。

    围栏里的示例不扫（语法样张），但 human 围栏算内容面（结论/风险着色正写在里面）。
    行内代码里的样式样张同样跳过。"""
    for i in range(len(lines)):
        if mask[i] and (i + 1) not in doc.human_lines:
            continue
        scan = INLINE_CODE_RE.sub(lambda m: " " * len(m.group(0)), lines[i])
        for attr in COLOR_STYLE_ATTR_RE.finditer(scan):
            for cm in COLOR_VALUE_RE.finditer(attr.group(2)):
                rest = scan[attr.end():]
                close = rest.find("</span>")
                span_text = rest[:close] if close >= 0 else rest
                doc.color_spans.append({
                    "line": i + 1,
                    "color": cm.group(1).strip(),
                    "text": strip_inline_markup(HTML_TAG_RE.sub("", span_text)),
                })


def list_abbrev_tokens(part):
    """片段里的缩写 token（大写归一、带点缩写去尾点）。"""
    out = []
    for regex in (ABBREV_CAPS_RE, ABBREV_DOTTED_RE):
        for m in regex.finditer(part):
            out.append(m.group(1).upper().rstrip("."))
    return out


def mask_line_for_abbrev(text):
    """→ (扫描面文本, {token: 首次列})。

    扫描面 = 原文把 wikilink / 行内代码 / HTML 标签 / URL 全部打成等长空格——
    这几类的字符是语法或地址，不是「读者要理解的概念」。
    wikilink 里的 token 单列进 cited（`[[GCR]]` 就是挂词条链接，不是裸用）。"""
    cited = {}

    def blank_wikilink(m):
        inner = m.group(2)
        target, _sep, display = inner.partition("|")
        for part in (target, display):
            for tok in list_abbrev_tokens(part):
                cited.setdefault(tok, m.start())
        return " " * len(m.group(0))

    masked = WIKILINK_RE.sub(blank_wikilink, text)
    masked = INLINE_CODE_RE.sub(lambda m: " " * len(m.group(0)), masked)
    masked = HTML_TAG_RE.sub(lambda m: " " * len(m.group(0)), masked)
    masked = URL_RE.sub(lambda m: " " * len(m.group(0)), masked)
    return masked, cited


def abbrev_is_noise(masked, start, end):
    """文件名 / 路径段 / 复合标识符 / 全大写短语里的 token 不算缩写使用处。

    「全大写短语」（`IRON RULES`、`KEY NUMBERS`、`OPEN ITEMS`）是强调式的标题词，
    拆开要求展开反而错；真正的缩写几乎不会成对连写出场。"""
    if masked[end:end + 1] == ".":
        tail = masked[end + 1:end + 8].strip().lower()
        for ext in FILE_EXT_TAIL:
            if tail.startswith(ext) and (len(tail) == len(ext) or not tail[len(ext)].isalnum()):
                return True
    prev = masked[start - 1:start] if start else ""
    if prev in ("/", "\\", "-", "_", "."):
        return True
    if masked[end:end + 1] in ("/", "\\"):
        return True
    if re.search(r"(?<![A-Za-z0-9_])[A-Z][A-Z]{1,5}\s+$", masked[:start]):
        return True
    if re.match(r"^\s+[A-Z][A-Z]{1,5}(?![A-Za-z0-9_])", masked[end:]):
        return True
    return False


def abbrev_expanded(line, start, end):
    """首现处是否给了全称：右括注 / 右破折线式 / 左多词括注（`Galactic Cosmic Rays（GCR）`）。"""
    tail = line[end:]
    if re.match(r"^\s*[（(][^（()）]{2,}[）)]", tail):
        return True
    if re.match(r"^\s*(?:[—–]{1,2}|[:：])\s*\S{2,}", tail):
        return True
    head = line[:start]
    if re.search(r"[A-Za-z][A-Za-z0-9.\-]*(?:\s+[A-Za-z0-9.\-]+)+\s*[（(]\s*$", head):
        return True                      # 拉丁全称（≥2 词）+ 括注
    if re.search(r"[\u4e00-\u9fff]{4,30}\s*[（(]\s*$", head):
        return True                      # 中文全称（≥4 字）+ 括注
    return False


def collect_abbrev_occurrences(doc, lines, mask):
    """R9：抓全部缩写出现（裸用与 wikilink 引用都算出现，取首次出现判定）。"""
    for line_no, text in doc.body_lines:
        if line_no in doc.human_lines:
            continue                     # 人面子另有术语禁令（条款 ⑤），R9 不叠罚
        if HEADING_RE.match(text):
            continue                     # 标题不是「使用处」（同 R3 口径）
        masked, cited = mask_line_for_abbrev(text)
        occ = []
        for regex in (ABBREV_CAPS_RE, ABBREV_DOTTED_RE):
            for m in regex.finditer(masked):
                if abbrev_is_noise(masked, m.start(), m.end()):
                    continue
                token = m.group(1).upper().rstrip(".")
                occ.append((m.start(), token, token in cited,
                            abbrev_expanded(masked, m.start(), m.end())))
        for token, pos in cited.items():
            occ.append((pos, token, True, False))
        for pos, token, is_cited, expanded in sorted(occ, key=lambda t: t[0]):
            doc.abbrev_occ.append({"line": line_no, "col": pos, "token": token,
                                   "cited": is_cited, "expanded": expanded})


# ── R10 平实语言 / R11 结构三层 / R12 图注 / R16 细节折叠：解析面采集 ────

def scan_text(line):
    """R10/R11 的扫描面：行内代码与 HTML 标签打成等长空格（语法不是用词）。"""
    masked = INLINE_CODE_RE.sub(lambda m: " " * len(m.group(0)), line)
    return masked


def content_lines(doc):
    """内容行（行号, 原文）：非围栏行，或 human 卡片围栏内的行。

    human 卡片是**内容面**（作者令：每部分内容用卡片分隔），卡片里的标题就是
    读者最先看到的那句话——禁用词与结论句都要在卡片里查得见。"""
    for i in range(doc.body_start, len(doc.raw_lines)):
        if doc.fence_mask[i] and (i + 1) not in doc.human_lines:
            continue
        yield i + 1, doc.raw_lines[i]


def collect_plainword_hits(doc, blocklist):
    """R10：每个禁用词在本档的首次命中 → doc.plainwords[{line, word, hint}]。

    只报首次：同一个词重复出现是同一个毛病，报一次就够整改；多报只会把清单淹掉。"""
    if not blocklist:
        doc.plainwords = []
        return
    seen = set()
    doc.plainwords = []
    for line_no, text in content_lines(doc):
        scan = scan_text(text)
        hits = []
        for word in blocklist:
            pos = scan.find(word)
            if pos >= 0:
                hits.append((pos, word))
        for pos, word in sorted(hits):
            if word in seen:
                continue
            seen.add(word)
            doc.plainwords.append({"line": line_no, "word": word,
                                   "hint": blocklist[word]})


def _section_key(heading):
    return heading.strip().lower()


def analyse_sections(doc):
    """R11/R16：切 `##` 章节，量三件事——首行结论 / 敞开正文字数 / 有无折叠块。

    判据只有两个：读者先看到答案（首行加粗）、细节点一下才出来（有 `<details>`）。
    表格、列表、代码围栏、图注行不算「敞开的长论证」——它们本身就是压缩形态。"""
    current = None
    details_depth = 0
    for line_no, text in content_lines(doc):
        hm = HEADING_RE.match(text)
        if hm and len(hm.group(1)) <= STRUCTURE_HEADING_LEVEL:
            if len(hm.group(1)) == STRUCTURE_HEADING_LEVEL:
                current = {"line": line_no, "heading": hm.group(2).strip(),
                           "first_prose": None, "prose_chars": 0, "max_prose": 0,
                           "has_details": False, "skipped": False}
                doc.sections.append(current)
            else:
                current = None          # H1 换节：之前的 H2 章节结束
            details_depth = 0
            continue
        if current is None:
            continue
        stripped = text.strip()
        if not stripped:
            continue
        if DETAILS_OPEN_RE.search(text):
            current["has_details"] = True
            details_depth += 1
        if details_depth:
            if DETAILS_CLOSE_RE.search(text):
                details_depth = max(0, details_depth - 1)
            continue                    # 折叠块里的内容 = 已折叠，不计量
        if DETAILS_CLOSE_RE.search(text):
            details_depth = 0
            continue
        if (INDENT_EXEMPT_START_RE.match(text) or BLOCKID_RE.match(text)
                or CAPTION_RE.match(stripped)):
            continue                    # 结构性行 / 图注行：压缩形态，不计敞开正文
        scan = scan_text(text)
        chars = visible_len(scan)
        if chars <= 0:
            continue
        if current["first_prose"] is None:
            current["first_prose"] = (line_no, text)
        current["prose_chars"] += chars
        current["max_prose"] = max(current["max_prose"], chars)


def section_is_discursive(section, min_chars=CONCLUSION_PROSE_MIN):
    """这节算不算「论述节」：**有一行** ≥40 可视字符的正文，且不是词条/附录类结构节。

    量的是本节最长的那一行，不是首行——首行本来就该是「一句话结论」（往往只有
    十几个字），拿它当门槛会把每一节都判成「非论述节」，等于把 R11/R16 关掉。
    词条节、附录、参考文献、变更记录是**结构节**——它们开头是清单而不是结论，
    硬要求「开头一句加粗结论」只会把表格和清单改坏。"""
    key = _section_key(section["heading"])
    if any(skip in key for skip in STRUCTURE_SKIP_HEADINGS):
        return False
    return section.get("max_prose", 0) >= min_chars


def collect_caption_refs(doc):
    """R12：给每个图片引用找图注行 → doc.captions[{line, target, caption, caption_line, too_long}]。

    图注 = 图片行同一行后随的 `图 …` / `Figure …`，或图片之后第 1-2 行（允许隔一个
    空行）的图注行。图注只说结论；超过 120 字符就是「把正文塞进图里」，提示不改判。"""
    by_line = {}
    for i in range(doc.body_start, len(doc.raw_lines)):
        by_line[i + 1] = doc.raw_lines[i]
    for ref in doc.image_refs:
        line_no = ref["line"]
        caption, caption_line = "", None
        # ① 同一行后半段（`![](a.png) 图 1 …`）
        same = by_line.get(line_no, "")
        after = IMG_TAG_SRC_RE.sub("", MDIMAGE_RE.sub("", same))
        after = WIKILINK_RE.sub("", after).strip()
        if after and CAPTION_RE.match(after):
            caption, caption_line = after, line_no
        # ② 图片行之后第 1-2 行
        if caption_line is None:
            for delta in range(1, CAPTION_LOOKAHEAD + 1):
                cand = (by_line.get(line_no + delta) or "").strip()
                if not cand:
                    continue                # 允许隔一个空行
                if CAPTION_RE.match(cand):
                    caption, caption_line = cand, line_no + delta
                break
        too_long = bool(caption) and visible_len(caption) > CAPTION_MAX_CHARS
        doc.captions.append({"line": line_no, "target": ref["target"],
                             "caption": caption, "caption_line": caption_line,
                             "too_long": too_long})


# ── 允许清单（写在 SKILL.md，脚本只读）与生效面判定 ─────────────────

def load_abbrev_allowlist(doc_path=None, extra=()):
    """从规范文件的标记块读缩写允许清单 → {TOKEN: 分组标签}。

    清单是**规范文件的一部分**（作者要求：允许清单写进规范、不藏在脚本里），
    脚本缺文件或缺标记块时打 WARN 到 stderr 并以空清单继续（失败要响，不静默）。"""
    path = Path(doc_path) if doc_path else ALLOWLIST_DEFAULT_DOC
    allow = {}
    if not path.is_file():
        print(f"WARN: 缩写允许清单文件不存在：{path}（R9 按空清单判定）", file=sys.stderr)
    else:
        text = read_text(path) or ""
        block = re.search(re.escape(ALLOWLIST_START) + r"(.*?)" + re.escape(ALLOWLIST_END),
                          text, re.DOTALL)
        if not block:
            print(f"WARN: {path} 缺缩写允许清单标记块（{ALLOWLIST_START} … "
                  f"{ALLOWLIST_END}），R9 按空清单判定", file=sys.stderr)
        else:
            group = "未分组"
            for raw in block.group(1).splitlines():
                line = raw.strip()
                if not line or line.startswith("```"):
                    continue
                if line.startswith("#"):
                    group = line.lstrip("#").strip()
                    continue
                for tok in re.split(r"[\s,，、;；]+", line):
                    tok = tok.strip()
                    if tok and ALLOWLIST_TOKEN_RE.match(tok):
                        allow[tok.upper()] = group
    for tok in extra or ():
        tok = str(tok).strip()
        if tok and ALLOWLIST_TOKEN_RE.match(tok):
            allow[tok.upper()] = "命令行追加"
    return allow


def load_plainword_blocklist(doc_path=None, extra=()):
    """从规范文件的标记块读禁用词 / 避用词表 → {词: 建议替换}（有序）。

    形态：一行一条 `词 => 建议`（`=>` / `→` 都认），`#` 开头是分组注释。词表住在
    规范文件里，改词表就是改标准；脚本缺文件或缺标记块时打 WARN 到 stderr 并以空
    词表继续（失败要响，不静默）。"""
    path = Path(doc_path) if doc_path else PLAIN_DEFAULT_DOC
    block = {}
    if not path.is_file():
        print(f"WARN: 禁用词表文件不存在：{path}（R10 按空词表判定）", file=sys.stderr)
        return block
    text = read_text(path) or ""
    found = re.search(re.escape(PLAIN_BLOCK_START) + r"(.*?)" + re.escape(PLAIN_BLOCK_END),
                      text, re.DOTALL)
    if not found:
        print(f"WARN: {path} 缺禁用词表标记块（{PLAIN_BLOCK_START} … "
              f"{PLAIN_BLOCK_END}），R10 按空词表判定", file=sys.stderr)
        return block
    for raw in found.group(1).splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("```"):
            continue
        m = PLAIN_LINE_RE.match(line)
        if not m:
            continue
        word = m.group("word").strip()
        hint = m.group("hint").strip()
        if word:
            block[word] = hint
    for raw in extra or ():
        for chunk in re.split(r"[\s,，、;；]+", str(raw)):
            chunk = chunk.strip()
            if chunk:
                block.setdefault(chunk, "命令行追加（无建议）")
    return block


def style_scoped(rel, doc, since=STYLE_CONTRACT_STAMP, strict=False):
    """R7-R9 的硬查面：新制文档入硬查，存量文档先记 advisory。

    入制条件（任一）：--strict-style 全库整改；frontmatter 显式 `style:`（主动入制）；
    阶段件文件名时间块 ≥ 契约时刻（`<topic>__20261105_111100__x.md` 起）。"""
    if strict:
        return True
    if "style" in doc.frontmatter:
        return True
    return _time_block_at_least(rel, since)


def style_scoped_v2(rel, doc, since=STYLE_V2_STAMP, strict=False):
    """R10-R12 的硬查面：只看**时间界**与 --strict-style，不看 frontmatter 的 `style:`。

    理由：`style:` 是上一批（R7-R9 排版与缩写）的主动入制开关，那时的作者并不知道
    有禁用词表与折叠骨架；拿它去追溯判新规则，等于让旧文档凭一个它没听说过的契约
    变红。新规则只判「本批生效之后写下的文档」。"""
    if strict:
        return True
    return _time_block_at_least(rel, since)


def _time_block_at_least(rel, since):
    m = DOC_NAMING_TIME_RE.search(rel.rsplit("/", 1)[-1])
    if not m:
        return False
    return f"{m.group(1)}_{m.group(2)}" >= since


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


# ── R6 图片位置判定 ──────────────────────────────────────────────────

def vault_file_index(root):
    """vault 内全部文件的索引 → (文件集合, {小写 basename: [rel…]})。
    只做存在性判定，不读内容（图片可能很大，只 stat）。"""
    files = set()
    by_base = {}
    base = Path(root)
    for path in base.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(base).as_posix()
        files.add(rel)
        by_base.setdefault(posixpath.basename(rel).lower(), []).append(rel)
    return files, by_base


def image_candidates(rel, target, syntax):
    """引用 → 候选 vault 相对路径（按解析制排序）。
    wikilink 内嵌 = Obsidian 语义：先 vault 相对，再文档同目录；
    markdown / HTML = 文档所在目录相对，再 vault 相对。`%20` 等百分号编码先解码。"""
    t = unquote(target.strip())
    t = t.split("#", 1)[0].split("?", 1)[0].strip()
    doc_dir = posixpath.dirname(rel)
    if syntax == "wikilink":
        order = [t, posixpath.join(doc_dir, t)]
    else:
        order = [posixpath.join(doc_dir, t), t]
    out = []
    for cand in order:
        norm = posixpath.normpath(cand)
        if norm in (".", ""):
            continue
        if norm not in out:
            out.append(norm)
    return t, out


def volatile_hint(target):
    """命中已知易失位置时给一句点名（整改时知道该搬哪儿）。"""
    low = target.lower()
    for needle, why in VOLATILE_IMAGE_HINTS:
        if needle in low:
            return f"（命中易失位置：{why}）"
    return ""


def classify_image_ref(rel, target, syntax, vault_files, by_base, vault_abs):
    """→ (verdict, detail)；verdict = "ok" | "violation"。
    违规 detail 就是输出里的「失败原因」，逐类区分（绝对路径 / 越出 vault /
    外链 / 家目录 / 协议 / 不存在）。"""
    raw = target.strip()
    if not raw:
        return ("violation", "空图片引用（引用目标为空）")
    low = raw.lower()
    if IMAGE_DATA_URI_RE.match(raw):
        return ("ok", "data: 内联图（字节随文档，自包含）")
    if low.startswith(("http://", "https://")) or raw.startswith("//"):
        return ("violation",
                "外链图床/CDN 引用（离线优先不可依赖）——先把图落一份进 vault，正文只引 vault 内副本并注明来源"
                + volatile_hint(raw))
    scheme = IMAGE_SCHEME_RE.match(raw)
    if scheme and scheme.group(1).lower() != "file":
        return ("violation", f"非本地协议 `{scheme.group(1)}:` 的图片引用——图片必须是 vault 内的本地文件")
    if scheme:  # file:// —— 按绝对路径判定（它一定是绝对路径）
        return classify_absolute_image(raw[len("file:"):], raw, vault_abs)
    if raw.startswith("~"):
        return ("violation",
                "`~` 家目录路径（换机器/换用户即断链）——图片必须落进 vault，用 vault 相对路径引用"
                + volatile_hint(raw))
    if raw.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", raw):
        return classify_absolute_image(raw, raw, vault_abs)
    _t, cands = image_candidates(rel, raw, syntax)
    for cand in cands:
        if cand.startswith("../") or cand == "..":
            continue
        if cand in vault_files:
            return ("ok", cand)
    if cands and all(c.startswith("../") or c == ".." for c in cands):
        return ("violation",
                "相对路径 `../` 越出 vault——图必须放在 `<vault>/attachments/` 或 `<vault>/assets/` 里，"
                "用 vault 相对路径引用" + volatile_hint(raw))
    base = posixpath.basename(cands[0] if cands else raw).lower()
    hits = by_base.get(base) or []
    if hits:
        return ("ok", hits[0])  # 全库唯一/多处 basename 命中都算在 vault 内（够判定「图在不在库里」）
    return ("violation",
            "vault 内不存在该图片文件（死引用：换机器/清工作树后必然断链）——先把图落进 vault 再引用"
            + volatile_hint(raw))


def classify_absolute_image(path, raw, vault_abs):
    """绝对路径：即便恰好落在本 vault 也不合规（换机器/换 dataRoot 即断链）。"""
    try:
        resolved = Path(path).expanduser().resolve()
    except (OSError, RuntimeError):
        resolved = None
    inside = False
    if resolved is not None:
        try:
            resolved.relative_to(vault_abs)
            inside = True
        except ValueError:
            inside = False
    if inside:
        return ("violation",
                "绝对路径（虽落在本 vault，换机器/换 dataRoot 即断链）——改用 vault 相对路径"
                + volatile_hint(raw))
    return ("violation",
            "绝对路径且落在 vault 外——图片必须搬进 vault，正文用 vault 相对路径引用"
            + volatile_hint(raw))


# ── R17 裸名嵌入 / R18 围栏内嵌图的路径形态：渲染器镜像 ─────────────────

def renderer_candidates(rel, ref):
    """镜像 `web/js/viewers/markdown.js` 的 `imageCandidates()` → vault 相对候选表。

    前端那六格（文档同目录 / vault 根 / `<vault>/attachments|assets` /
    `<docdir>/attachments|assets`）的**路径语义**原样搬过来；`%20` 等百分号编码先
    解码（marked 会 percent-encode，前端 decodeRef 同一口径）。不镜像铸票与
    onerror 级联——本函数只回答「渲染器会不会去找这个文件」。"""
    src = unquote(str(ref or "").split("#", 1)[0].split("?", 1)[0]).strip()
    if not src:
        return []
    if src.startswith("./"):
        src = src[2:]
    doc_dir = posixpath.dirname(rel)
    out = []

    def push(path):
        norm = posixpath.normpath(path)
        if norm and norm != "." and not norm.startswith("../") and norm not in out:
            out.append(norm)

    if doc_dir:
        push(doc_dir + "/" + src)
    push(src)
    for sub in RENDERER_VAULT_ATTACH_DIRS:
        push(sub + "/" + src)
    if doc_dir:
        for sub in RENDERER_DOC_SUBDIRS:
            push(doc_dir + "/" + sub + "/" + src)
    return out


def renderer_hit(rel, ref, vault_files):
    """该引用在渲染器候选表里命中的 vault 相对路径；解析不到 → None。"""
    for cand in renderer_candidates(rel, ref):
        if cand in vault_files:
            return cand
    return None


def embed_syntax_hint(syntax, path):
    """按原语法给可粘贴的应写形态。"""
    tpl = IMAGE_EMBED_SYNTAX_TPL.get(syntax) or IMAGE_EMBED_SYNTAX_TPL["wikilink"]
    return tpl.format(path=path)


def fence_embed_path_ok(target):
    """R18 的路径形态判据：这条引用是不是**vault 相对路径**（含目录）。

    合规 = 有目录分量（`attachments/x.png` / `assets/<batch>/x.png`）——
    渲染器第②格「vault 根 + 引用」对任何文档目录都成立；裸文件名（`x.png`）
    只在六格里的四格成立（文档同目录 + `<vault>/attachments|assets`），文档一
    挪就落空。`./x.png` 与裸名同档（显式文档相对，不带 vault 语义）。
    """
    src = unquote(str(target or "").split("#", 1)[0].split("?", 1)[0])
    src = src.strip().replace("\\", "/")
    if not src:
        return False
    if src.startswith("./"):
        src = src[2:]
    return "/" in src


def collect_image_embed_issues(rel, doc, vault_files, by_base, vault_abs):
    """R17 / R18 判定面 → [{rule, line, target, detail}]。

    两条的靶都是**渲染期确定性失败**，按**位置**分片，一行只归一条——
    互不重叠、互不复述：

    · 围栏**内**（```human 摘要围栏 / 卡片体）⇒ 归 R18：嵌图**放行**
      （`expandImageEmbeds()` 自 card-image 批 2026-11-05 起展开已闭合围栏内的
      嵌入），但路径必须是 **vault 相对**（见 `fence_embed_path_ok`）。裸名 +
      图在子目录 ⇒ 照样「图片加载失败」，与围栏外同一个断点。
    · 围栏**外** ⇒ 归 R17：R6 判 ok（图确实在库里）但渲染器候选表解析不到 ⇒
      裸名/路径写歪。
    R6 已经报过的（死引用 / 绝对路径 / 外链 / 越界）两条都不复述——一处只报
    一次，修完重跑看下一处。行内豁免 `<!-- wiki-img-exempt: 理由 -->` 对两条都
    生效（历史存量的位置豁免）。"""
    issues = []
    for ref in doc.image_refs:
        target = ref["target"]
        line = ref["line"]
        syntax = ref["syntax"]
        in_fence = line in doc.human_lines
        if ref["exempt"]:
            continue
        status, _detail = classify_image_ref(rel, target, syntax, vault_files,
                                            by_base, vault_abs)
        if status != "ok":
            continue          # 位置面已经由 R6 报过，R17/R18 不复述
        implied = unquote(target).split("#", 1)[0].split("?", 1)[0].strip()
        base = posixpath.basename(implied).lower()
        hits = sorted(by_base.get(base) or [])
        better = [h for h in hits if "/" in h]
        if in_fence:
            # R18：围栏内嵌图已放行，只判「路径形态」= 有目录分量的 vault 相对。
            if fence_embed_path_ok(target) or not better:
                continue
            resolved = renderer_hit(rel, target, vault_files)
            why = ("这条路径**能**解析（裸名命中六格之一），但成不成立取决于文档"
                   "放在哪个目录；换一篇文档指同一张图就落空")
            if not resolved:
                why = ("渲染器六格全落空 ⇒ 卡片里落「图片加载失败」占位（与围栏外"
                       "的裸名同一个断点）")
            if len(better) == 1:
                fix = f"应写：`{embed_syntax_hint(syntax, better[0])}`"
            else:
                shown = " / ".join(f"`{embed_syntax_hint(syntax, h)}`" for h in better[:4])
                more = f" 等 {len(better)} 处" if len(better) > 4 else ""
                fix = f"库内同名 {len(better)} 处{more}，选实际那张：{shown}"
            issues.append({
                "rule": "fence-embed-vault-relative", "line": line, "target": target,
                "detail": (f"```human 卡片体里的图片嵌入（第 {line} 行）必须是 "
                           f"**vault 相对路径**（含目录，如 `attachments/x.png` / "
                           f"`assets/<batch>/x.png`）；`{target}` 是裸名——{why}；{fix}"),
            })
            continue
        # R17（围栏外）：渲染器解析得到 ⇒ 合规。
        if renderer_hit(rel, target, vault_files):
            continue
        if not hits:
            continue
        if "/" in implied.replace("\\", "/"):
            why = ("引用带了目录，但渲染器候选表不含该位置"
                   "（只找文档同目录 / vault 根 / attachments / assets 六个格位）")
        else:
            why = ("正文是裸文件名，图真身却在子目录里——前端 `imageCandidates()` "
                   "只找六个格位、**不递归子目录**，六格全落空即「图片加载失败」占位")
        if len(hits) == 1:
            fix = f"应写：`{embed_syntax_hint(syntax, hits[0])}`"
        else:
            shown = " / ".join(f"`{embed_syntax_hint(syntax, h)}`" for h in hits[:4])
            more = f" 等 {len(hits)} 处" if len(hits) > 4 else ""
            fix = f"库内同名 {len(hits)} 处{more}，选文档实际用的那张：{shown}"
        issues.append({
            "rule": "bare-name-embed", "line": line, "target": target,
            "detail": f"`{target}` → {why}；{fix}",
        })
    return issues


# ── R19 必须有图 / R20 逐条细节未折叠：判定面 ──────────────────────────

def doc_has_figure(doc):
    """一篇文档「有图」的两条判据：图片引用（R6/R17 同一抓取面）或 ```mermaid 块。"""
    if doc.image_refs:
        return True
    return any(MERMAID_FENCE_RE.match(line) for line in doc.raw_lines)


def declared_reason(regex, text):
    """文档内的豁免声明理由（`<!-- wiki-figure-exempt: 理由 -->`）；空理由 = 没声明。"""
    m = regex.search(text or "")
    return m.group(1).strip() if m else ""


def stacked_detail_runs(doc):
    """R20：每个 `##` 章节里「连续逐条细节」的最长段 → [{line, section, run}]。

    逐条细节 = 列表项 或 代码围栏内的行（```mermaid 除外，那是图）；表格不算
    （压缩形态，与 R16 同口径）。已被 `<details>` 包住的行不算（已经折了）。
    一节只报最长那一段——一处修完重跑会看到下一段。"""
    out = []
    section = ""
    section_line = 0
    run_start = None
    run = 0
    best = (0, 0)          # (run, start_line)
    has_details = False
    details_depth = 0
    in_mermaid = False

    def close_section():
        if section and not has_details and best[0] >= STACKED_RUN_MIN:
            out.append({"line": best[1], "section": section, "run": best[0]})

    for i in range(doc.body_start, len(doc.raw_lines)):
        line = doc.raw_lines[i]
        line_no = i + 1
        stripped = line.strip()
        if SECTION_HEADING_RE.match(line):
            close_section()
            section, section_line = stripped, line_no
            run, best, has_details, details_depth, in_mermaid = 0, (0, 0), False, 0, False
            run_start = None
            continue
        if not section:
            continue
        if DETAILS_CLOSE_RE.search(line):
            details_depth = max(0, details_depth - 1)
            run, run_start = 0, None
            continue
        if DETAILS_OPEN_RE.search(line):
            has_details = True
            details_depth += 1
            run, run_start = 0, None
            continue
        if details_depth:
            continue                      # 已折叠的行不参与判定
        if MERMAID_FENCE_RE.match(line):
            in_mermaid = True
            run, run_start = 0, None
            continue
        if FENCE_RE.match(line):
            if in_mermaid:
                in_mermaid = False        # mermaid 的收尾围栏
            run, run_start = 0, None      # 围栏标记行本身不计
            continue
        if in_mermaid:
            continue                      # 图的内容行不计量
        if not stripped:
            run, run_start = 0, None
            continue
        is_detail = bool(STACKED_LIST_RE.match(line)) or bool(doc.fence_mask[i])
        if is_detail:
            if run == 0:
                run_start = line_no
            run += 1
            if run > best[0]:
                best = (run, run_start)
        else:
            run, run_start = 0, None
    close_section()
    return out


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
    """R13/R14/R15 —— 只许引用词条的门禁（作者令 2026-11-05）。

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
            #    `![…](…)` 是嵌入面（R14 豁免的承载形态），不归本条。
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
            # R14 裸图片路径：纯文本形态（嵌入 / 可点击链接已整段剥掉）。
            for m in BARE_IMAGE_PATH_RE.finditer(mask_legal_carriers(line)):
                raw = m.group(0)
                emit("bare-image-path", line_no,
                     f"图片路径以纯文本出现「{raw}」——图片只有两种合法形态："
                     f"直接嵌入 `![[{basename(raw)}]]` 或可点击跳转 `[说明]({raw})`")

        # ③ 引用文档：无锚、非嵌入的 wikilink 解析到**文档**却解析不到**词条**。
        #    R15 悬空词条：同样的形状但文档与词条双双解析不到（这一形状从 R2 移出，
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


def check(root, docs, resolve, glossary_rel, exclude_glossary_check, terms_by_rel=None,
          resolve_term=None, style_opts=None, advisories=None, exempt_sink=None):
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

    # R2 — 孤儿文档（零出链且零入链）＝ 图谱连线密度，自写作风格批 v2 起降为
    # **建议**：它不服务词条（词条由 R3 首现必释 / R4 注册表面与断链检查承担），
    # 而作者令已明确「我们现在不创建图谱」。仍逐条打印，读者看得见哪几篇是孤岛，
    # 但它不进违规清单、不动退出码。index/glossary 照旧豁免。
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
        if not doc.links and rel not in linked and advisories is not None:
            advisories.append({"rule": "orphan-doc", "file": rel, "line": 1,
                               "detail": "零出链且零入链（图谱面建议，不计违规）——"
                                         "接入词条或一句话说明它的来源即可；词条面不靠它",
                               "scope": "graph"})

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
    # R6 — 图随文档走：图片必须是 vault 内真实文件，且用 vault 相对路径引用
    vault_files, by_base = vault_file_index(root)
    vault_abs = Path(root).resolve()
    for rel, doc in sorted(docs.items()):
        for ref in doc.image_refs:
            if ref["exempt"]:
                continue  # 行内豁免：单独计数、输出里显形（不是静默跳过）
            status, detail = classify_image_ref(rel, ref["target"], ref["syntax"],
                                                vault_files, by_base, vault_abs)
            if status != "ok":
                add("image-outside-vault", rel, ref["line"],
                    f"`{ref['target']}` → {detail}")

    # R7 彩色 / R8 缩进 / R9 缩写（排版与缩写批）——新制文档硬查，存量先记 advisory
    style_opts = style_opts or {}
    if style_opts.get("enabled", True):
        since = style_opts.get("since") or STYLE_CONTRACT_STAMP
        since_v2 = style_opts.get("since_v2") or STYLE_V2_STAMP
        since_img = style_opts.get("since_img") or IMAGE_EMBED_STAMP
        since_fig = style_opts.get("since_fig") or FIGURE_STAMP
        strict = bool(style_opts.get("strict"))
        allow = style_opts.get("allow") or {}
        stats = style_opts.setdefault("stats", {})
        for key in ("color", "paragraph", "abbrev", "exempt", "wordform",
                    "docs_hard", "docs_legacy", "plain", "sections", "unfolded",
                    "images", "nocaption", "caplong",
                    "img_hard", "img_legacy", "img_bare", "img_fence",
                    "fig_nomap", "fig_stacked", "fig_hard", "fig_legacy"):
            stats.setdefault(key, 0)
        for rel, doc in sorted(docs.items()):
            if not rel.lower().endswith((".md", ".markdown")):
                continue          # html 自带 CSS，nbsp 缩进制不适用
            scoped = style_scoped(rel, doc, since, strict)
            scoped_v2 = style_scoped_v2(rel, doc, since_v2, strict)
            stats["docs_hard" if scoped else "docs_legacy"] += 1

            def emit(rule, line, detail, _scoped=scoped, _rel=rel):
                if _scoped:
                    add(rule, _rel, line, detail)
                elif advisories is not None:
                    advisories.append({"rule": rule, "file": _rel, "line": line,
                                       "detail": detail, "scope": "legacy"})

            def emit_v2(rule, line, detail, _scoped=scoped_v2, _rel=rel):
                """R10-R12 的生效面：风格批 v2 有自己的时间界（作者令 12:09）。

                同一条 emit 的两档语义：新制文档进违规，存量只打 advisory。"""
                if _scoped:
                    add(rule, _rel, line, detail)
                elif advisories is not None:
                    advisories.append({"rule": rule, "file": _rel, "line": line,
                                       "detail": detail, "scope": "legacy"})

            # R17 — 裸名嵌入 / R18 — human 围栏内嵌图：渲染器断点（「图不显示」批）
            embed_issues = collect_image_embed_issues(rel, doc, vault_files, by_base, vault_abs)
            if embed_issues:
                scoped_img = style_scoped_v2(rel, doc, since_img, strict)
                stats["img_hard" if scoped_img else "img_legacy"] += len(embed_issues)
                for iss in embed_issues:
                    if iss["rule"] == "bare-name-embed":
                        stats["img_bare"] += 1
                    else:
                        stats["img_fence"] += 1
                    if scoped_img:
                        add(iss["rule"], rel, iss["line"], iss["detail"])
                    elif advisories is not None:
                        advisories.append({"rule": iss["rule"], "file": rel,
                                           "line": iss["line"], "detail": iss["detail"],
                                           "scope": "legacy"})

            # R19 — 必须有图 / R20 — 逐条细节未折叠（以图为主批，作者令 21:24）
            fig_issues = []
            full_text = "\n".join(doc.raw_lines)
            structural = Path(rel).stem.lower() in STRUCTURAL_DOC_STEMS
            if not structural and not doc_has_figure(doc):
                if not declared_reason(FIGURE_EXEMPT_RE, full_text):
                    fig_issues.append({
                        "rule": "image-required", "line": 1,
                        "detail": ("一篇图都没有（图片嵌入与 ```mermaid 块都没有）——"
                                   "visual report 以图为主：正文的主体是图，文字只做注解与结论；"
                                   "补一张图（截图 / 数据图表 / ```mermaid），"
                                   "或若本文确实不该有图，写 `<!-- wiki-figure-exempt: 理由 -->` 明说为什么"),
                    })
            runs = stacked_detail_runs(doc)
            if runs and declared_reason(FOLD_EXEMPT_RE, full_text):
                runs = []
            for run in runs:
                fig_issues.append({
                    "rule": "stacked-detail", "line": run["line"],
                    "detail": (f"章节「{run['section']}」连续 {run['run']} 行逐条细节敞在正文"
                               f"（第 {run['line']} 行起），该节没有折叠块——把这段移进 "
                               "`<details><summary>过程</summary>` + 空行 + 正文 + 空行 + "
                               "`</details>`（标题用「过程 / 取证读数 / 复现步骤」这类短标签）；"
                               "正文只留结论、决策与读数"),
                })
            if fig_issues:
                scoped_fig = style_scoped_v2(rel, doc, since_fig, strict)
                stats["fig_hard" if scoped_fig else "fig_legacy"] += len(fig_issues)
                for iss in fig_issues:
                    if iss["rule"] == "image-required":
                        stats["fig_nomap"] += 1
                    else:
                        stats["fig_stacked"] += 1
                    if scoped_fig:
                        add(iss["rule"], rel, iss["line"], iss["detail"])
                    elif advisories is not None:
                        advisories.append({"rule": iss["rule"], "file": rel,
                                           "line": iss["line"], "detail": iss["detail"],
                                           "scope": "legacy"})

            # R7 — 彩色：额度 / 语义色 / 跨度
            spans = doc.color_spans
            stats["color"] += len(spans)
            for sp in spans:
                value = sp["color"].strip().lower()
                if value not in DOC_COLOR_PALETTE:
                    emit("doc-color", sp["line"],
                         f"非语义色 `{sp['color']}`——彩色语义固定：蓝 #5e6ad2 结论/判定、"
                         "红 #d0342c 风险/警告、绿 #12874a 已验证/通过；一般强调用加粗")
                elif len(sp["text"]) > DOC_COLOR_SPAN_MAX:
                    emit("doc-color", sp["line"],
                         f"着色覆盖 {len(sp['text'])} 字符（上限 {DOC_COLOR_SPAN_MAX}）——彩色只给"
                         "关键词句，整段强调用加粗")
            if len(spans) > DOC_COLOR_BUDGET:
                emit("doc-color", spans[DOC_COLOR_BUDGET]["line"],
                     f"彩色 {len(spans)} 处，超出预算（建议 ≤3，硬顶 {DOC_COLOR_BUDGET}）——"
                     "多出来的收成加粗，颜色留给真正要读者停一下的结论与风险")

            # R8 — 正文段落首行缩进
            stats["paragraph"] += len(doc.para_starts)
            for line_no, text in doc.para_starts:
                if INDENT_RE.match(text) or visible_len(text) < INDENT_MIN_CHARS:
                    continue
                if INDENT_BARE_SPACES_RE.match(text):
                    emit("doc-indent", line_no,
                         "四空格起行会被渲染成**代码块**（不是缩进）——段首改用 `&emsp;&emsp;`")
                else:
                    emit("doc-indent", line_no,
                         "正文段落未缩进——段首以 `&emsp;&emsp;` 起行（两个半角空格被 HTML 折叠吃掉，"
                         "四个变代码块）")

            # R9 — 缩写首现必须展开或挂词条
            first = {}
            for occ in sorted(doc.abbrev_occ, key=lambda o: (o["line"], o["col"])):
                first.setdefault(occ["token"], occ)
            stats["abbrev"] += len(first)
            for token, occ in first.items():
                if token in allow:
                    stats["exempt"] += 1
                    continue
                if token in doc.lower_words:
                    stats["wordform"] = stats.get("wordform", 0) + 1
                    continue
                if occ["expanded"] or occ["cited"]:
                    continue
                emit("abbrev-expansion", occ["line"],
                     f"缩写「{token}」首现既无全称也无词条链接——写成 `全称（{token}）` / "
                     f"`{token}（全称）` / `[[{token}]]（全称）`；跨文档通用的词请注册成词条，"
                     "通用词（单位/技术词）才进 SKILL.md 允许清单")

            # R10 — 平实语言：禁用词命中即报（首次命中）
            plain = style_opts.get("plainwords") or {}
            if plain:
                collect_plainword_hits(doc, plain)
            stats["plain"] += len(doc.plainwords)
            for hit in doc.plainwords:
                hint = hit["hint"] or "换平实的动词+名词"
                emit_v2("plain-language", hit["line"],
                     f"「{hit['word']}」是禁用/避用词（读者要二次解码）——建议：{hint}；"
                     "能用平实动词+名词说清的，不用抽象名词包裹")

            # R11 — 结构三层：每节开头一句加粗结论；R16 — 敞开细节必须折叠
            fold_limit = style_opts.get("fold_threshold") or DETAIL_FOLD_THRESHOLD
            for sec in doc.sections:
                stats["sections"] += 1
                if not section_is_discursive(sec):
                    continue
                line_no, text = sec["first_prose"]
                scan = INDENT_RE.sub("", scan_text(text)).strip()
                if not CONCLUSION_BOLD_RE.match(scan):
                    emit_v2("doc-structure", line_no,
                         f"章节「{sec['heading']}」开头缺分点结论句——首行写一句加粗结论"
                         "（`**一句话结论。**`），论证细节往下放进折叠块")
                elif visible_len(scan) > CONCLUSION_MAX_CHARS:
                    emit_v2("doc-structure", line_no,
                         f"章节「{sec['heading']}」首行 {visible_len(scan)} 字符，超出一句话"
                         f"（上限 {CONCLUSION_MAX_CHARS}）——结论压成一句，其余进折叠块")
                if sec["prose_chars"] > fold_limit and not sec["has_details"]:
                    stats["unfolded"] += 1
                    emit_v2("detail-fold", sec["line"],
                         f"章节「{sec['heading']}」敞开正文 {sec['prose_chars']} 可视字符"
                         f"（阈值 {fold_limit}）且没有折叠块——把论证细节包进 "
                         "`<details><summary>论证细节</summary>…</details>`，结论与关键数字留在外面")

            # R12 — 图注：图下一行必须有图注；图注过长只提示
            stats["images"] += len(doc.captions)
            for cap in doc.captions:
                if not cap["caption"]:
                    stats["nocaption"] += 1
                    emit_v2("image-caption", cap["line"],
                         f"图 `{cap['target']}` 没有图注——图片下一行写一行 `图 N …`，"
                         "一句话说这张图证明了什么（图里少写字，解释放图注与正文）")
                elif cap["too_long"]:
                    stats["caplong"] += 1
                    note = {"rule": "image-caption-long", "file": rel, "line": cap["caption_line"],
                            "detail": f"图注 {visible_len(cap['caption'])} 字符（建议 ≤{CAPTION_MAX_CHARS}）"
                                      "——图注该是一句结论，长解释进正文；图不是正文的容器",
                            "scope": "advisory"}
                    if advisories is not None:
                        advisories.append(note)

    # R13/R14/R15 — 引用门禁：只许引用词条（文档路径、图片裸路径、悬空词条三种形状）
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
    global STACKED_RUN_MIN
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
    ap.add_argument("--style-since", default=STYLE_CONTRACT_STAMP,
                    help=f"R7-R9 硬查的时间界（文件名时间块 ≥ 此值即入硬查；缺省 {STYLE_CONTRACT_STAMP}）")
    ap.add_argument("--style-since-v2", default=STYLE_V2_STAMP,
                    help=f"R10-R12/R16 硬查的时间界（写作风格批 v2 自己的生效界；缺省 {STYLE_V2_STAMP}）")
    ap.add_argument("--image-since", default=IMAGE_EMBED_STAMP,
                    help=f"R17-R18 硬查的时间界（「图不显示」批自己的生效界；缺省 {IMAGE_EMBED_STAMP}）")
    ap.add_argument("--figure-since", default=FIGURE_STAMP,
                    help=f"R19-R20 硬查的时间界（「以图为主」批自己的生效界；缺省 {FIGURE_STAMP}）")
    ap.add_argument("--fold-run", type=int, default=STACKED_RUN_MIN,
                    help=f"R20 逐条细节连续行数阈值（缺省 {STACKED_RUN_MIN}）")
    ap.add_argument("--strict-style", action="store_true",
                    help="R7-R12/R16 与 R17-R18 全库硬查（存量整改批次用：所有文档都按新制判，不再记 advisory）")
    ap.add_argument("--skip-style", action="store_true",
                    help="跳过 R7 彩色 / R8 缩进 / R9 缩写 / R10 禁用词 / R11 结构 / R12 图注 / R16 折叠"
                         " / R17 裸名嵌入 / R18 围栏内嵌图路径（历史批次对照用）")
    ap.add_argument("--allowlist", default=None,
                    help="R9 缩写允许清单文件（缺省 = 本 skill 的 SKILL.md 标记块）")
    ap.add_argument("--allowlist-extra", action="append", default=[],
                    help="命令行追加允许 token（可多次；逗号/空白分隔）")
    ap.add_argument("--plainwords", default=None,
                    help="R10 禁用词表文件（缺省 = 本 skill 的 SKILL.md 标记块）")
    ap.add_argument("--plainwords-extra", action="append", default=[],
                    help="命令行追加禁用词（可多次；逗号/空白分隔）")
    ap.add_argument("--fold-threshold", type=int, default=DETAIL_FOLD_THRESHOLD,
                    help=f"R16 细节折叠的敞开正文阈值（可视字符；缺省 {DETAIL_FOLD_THRESHOLD}）")
    args = ap.parse_args()

    if args.fold_run and args.fold_run > 0:
        STACKED_RUN_MIN = args.fold_run

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
    image_readouts = {}
    image_docs = {}
    style_readouts = {}
    abbrev_allow = {} if args.skip_style else load_abbrev_allowlist(
        args.allowlist, [t for chunk in args.allowlist_extra
                         for t in re.split(r"[\s,，、;；]+", chunk) if t.strip()])
    plain_block = {} if args.skip_style else load_plainword_blocklist(
        args.plainwords, args.plainwords_extra)
    for rel, vpath, vdocs, vgloss, vresolve, vatts, vterms, vterm_res in vault_results:
        stats = {}
        advisories = []
        vexempts = []
        violations = check(vpath, vdocs, vresolve, vgloss, exclude_glossary_check=args.skip_concepts,
                           terms_by_rel=vterms, resolve_term=vterm_res,
                           style_opts={"enabled": not args.skip_style, "since": args.style_since,
                                       "since_v2": args.style_since_v2,
                                       "since_img": args.image_since,
                                       "since_fig": args.figure_since,
                                       "strict": args.strict_style, "allow": abbrev_allow,
                                       "plainwords": plain_block,
                                       "fold_threshold": args.fold_threshold,
                                       "stats": stats},
                           advisories=advisories, exempt_sink=vexempts)
        for e in vexempts:
            e["vault"] = "" if rel == "docs" else rel
        vault_exempts[rel] = vexempts
        per_vault.append({"vault": rel, "docs": len(vdocs), "violations": violations,
                          "counts": summarise(violations), "advisories": advisories,
                          "style": stats})
        image_readouts[rel] = image_readout(vdocs)
        image_docs[rel] = vdocs
        style_readouts[rel] = stats
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
            # 无 --include-subvaults：--json 形状与历史逐字节同（机读契约不破）；
            # 新批的可选面（advisories / style）只在有内容时追加，绿盘输出零变化。
            payload = {"root": str(root), "violations": per_vault[0]["violations"],
                       "counts": per_vault[0]["counts"]}
            if per_vault[0]["advisories"]:
                payload["advisories"] = per_vault[0]["advisories"]
            if style_readouts.get("docs"):
                payload["style"] = style_readouts["docs"]
            print(json.dumps(payload, ensure_ascii=False, indent=1))
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
        print()
        print_image_readout(violations, image_readouts["docs"], docs=image_docs["docs"])
        print_style_readout(style_readouts["docs"], per_vault[0]["advisories"],
                            since=args.style_since, strict=args.strict_style)
        # 豁免段（生效/悬空都显形）与按类计数小表：都追加在原有逐条清单之后，
        # R1-R6 与排版三禁的既有输出逐字节不动。
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
            print_image_readout(r["violations"], image_readouts[r["vault"]],
                                indent="  ", docs=image_docs.get(r["vault"]))
            print_style_readout(style_readouts.get(r["vault"], {}), r["advisories"],
                                indent="  ", since=args.style_since, strict=args.strict_style)
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


def image_readout(docs):
    """R6 读数（人读输出面）→ (图片引用总数, 生效豁免[(rel,line,target,理由)], 悬空标注[(rel,line,理由)])。

    豁免必须逐条显形：作者看得到「跳过了哪几张、为什么」，就不会有整文件豁免的错觉。
    悬空标注（标了但那行/下一行没有图片引用）单列——标注写错位置就是没豁免。"""
    total = 0
    exempt = []
    orphans = []
    for rel, doc in sorted(docs.items()):
        total += len(doc.image_refs)
        applied = set()
        for ref in doc.image_refs:
            if not ref["exempt"]:
                continue
            exempt.append((rel, ref["line"], ref["target"], ref["exempt"]))
            applied.add(ref["line"])
        for line, reason in doc.image_exemptions:
            if line in applied or (line + 1) in applied:
                continue
            orphans.append((rel, line, reason))
    return total, exempt, orphans


EVIDENCE_HINT_MAX = 20


def evidence_hint_lines(docs):
    """info 面：点名 vault 外证据树路径的文档（「图跟着仓库走」的存量风险面）。
    不计违规——取证入口写在代码块里本身合法，本批只提示、不动文档。"""
    hits = []
    for rel, doc in sorted(docs.items()):
        if doc.volatile_mentions:
            hits.append((rel, doc.volatile_mentions))
    return hits


IMAGE_ADVISORY_PRINT_MAX = 20
STYLE_ADVISORY_PRINT_MAX = 15


def print_style_readout(stats, advisories, indent="", since=STYLE_CONTRACT_STAMP, strict=False):
    """R7-R12 读数（人读输出面）。

    三段：① 排版与缩写计数（彩色几处 / 段落几段 / 缩写几个 / 允许清单豁免几个）；
    ② 结构、语言与图注计数（章节几节 / 未折叠几处 / 图几张 / 缺图注几条 / 禁用词几处）；
    ③ advisory 明细——**打印但不在违规清单里**：样式面是因为新制自 2026-11-05 起
    生效、全库存量文档不可能同批整改（fix what you touch，`--strict-style` 全库整改）；
    图谱面（orphan-doc）是因为作者令已明确不建图谱，连线密度只作建议。"""
    if not stats:
        return
    hard = stats.get("docs_hard", 0)
    legacy = stats.get("docs_legacy", 0)
    scope = "全库硬查（--strict-style）" if strict else f"硬查 {hard} 篇 · 存量 {legacy} 篇"
    print(f"{indent}排版与缩写（R7-R9）：{scope} · 彩色 {stats.get('color', 0)} 处 · "
          f"正文段落 {stats.get('paragraph', 0)} 段 · 缩写 {stats.get('abbrev', 0)} 个"
          f"（允许清单豁免 {stats.get('exempt', 0)} · 同档小写词形 {stats.get('wordform', 0)}）")
    print(f"{indent}结构与图注（R10-R12）：章节 {stats.get('sections', 0)} 节 · "
          f"未折叠细节 {stats.get('unfolded', 0)} 处 · 图 {stats.get('images', 0)} 张"
          f"（缺图注 {stats.get('nocaption', 0)} · 图注过长 {stats.get('caplong', 0)}）· "
          f"禁用词 {stats.get('plain', 0)} 处")
    img_hard = stats.get("img_hard", 0)
    img_legacy = stats.get("img_legacy", 0)
    print(f"{indent}图能不能显示（R17-R18，时间界 {IMAGE_EMBED_STAMP}）："
          f"裸名嵌入 {stats.get('img_bare', 0)} 处 · 围栏内裸名（R18） "
          f"{stats.get('img_fence', 0)} 处"
          f" —— 硬查 {img_hard} 条 · 存量 advisory {img_legacy} 条"
          "（围栏内嵌图自卡片出图批起已放行；命中即页面不出图或引用位置依赖，不是风格问题）")
    fig_hard = stats.get("fig_hard", 0)
    fig_legacy = stats.get("fig_legacy", 0)
    print(f"{indent}以图为主（R19-R20，时间界 {FIGURE_STAMP}）：无图文档 "
          f"{stats.get('fig_nomap', 0)} 篇 · 逐条堆叠 {stats.get('fig_stacked', 0)} 节"
          f"（阈值 {STACKED_RUN_MIN} 行）—— 硬查 {fig_hard} 条 · 存量 advisory {fig_legacy} 条")
    if not advisories:
        return
    graph = [a for a in advisories if a.get("scope") == "graph"]
    if graph:
        print(f"{indent}图谱连线（advisory，不计违规）：{len(graph)} 篇零出链且零入链"
              "——图谱面建议，词条面不靠它")
    rest = [a for a in advisories if a.get("scope") != "graph"]
    if not rest:
        return
    by_rule = summarise(rest)
    detail = " · ".join(f"{name} {n}" for name, n in sorted(by_rule.items()))
    print(f"{indent}  存量样式/缩写/图注（advisory，不计违规，时间界 {since}）：{len(rest)} 条 —— {detail}")
    for adv in rest[:STYLE_ADVISORY_PRINT_MAX]:
        print(f"{indent}    {adv['file']}:{adv['line']}  【{adv['rule']}】{adv['detail']}")
    if len(rest) > STYLE_ADVISORY_PRINT_MAX:
        print(f"{indent}    …（余 {len(rest) - STYLE_ADVISORY_PRINT_MAX} 条；"
              f"`--strict-style` 可把存量一并按违规判）")


def print_image_readout(violations, readout, indent="", docs=None):
    total, exempt, orphans = readout
    n_img = sum(1 for v in violations if v["rule"] == "image-outside-vault")
    print(f"{indent}图片纪律（R6 image-outside-vault）：图片引用 {total} 处 · 违规 {n_img} 条 · 行内豁免 {len(exempt)} 条")
    for rel, line, target, reason in exempt:
        print(f"{indent}  豁免 {rel}:{line}  {target}  ← {reason}")
    for rel, line, reason in orphans:
        print(f"{indent}  悬空豁免标注 {rel}:{line}  ← {reason}（该行及下一行无图片引用 ⇒ 未生效）")
    if docs is None:
        return
    hits = evidence_hint_lines(docs)
    if not hits:
        return
    print(f"{indent}  图外挂提示（info，不计违规）：{len(hits)} 篇文档点名 vault 外证据树路径")
    shown = 0
    for rel, mentions in hits:
        if shown >= EVIDENCE_HINT_MAX:
            print(f"{indent}    …（余 {len(hits) - shown} 篇，见 --json 违规清单外的存量盘点报告）")
            break
        line, path = mentions[0]
        extra = f" 等 {len(mentions)} 处" if len(mentions) > 1 else ""
        print(f"{indent}    {rel}:{line}  {path}{extra}")
        shown += 1


if __name__ == "__main__":
    sys.exit(main())

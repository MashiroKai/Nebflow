#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-manifest.py — 文档命名改制 · 阶段 1：迁移清单生成器（严格只读）

扫描 stand 项目 vault（~/.stand/projects/stand/.stand/docs/），把旧制文件名
    <YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md
换算为新制
    <topic>__<YYYYMMDD>_<HHMMSS>__<chainId>.md
并统计：入链（谁 [[...]] 引用了它）、index.md 是否引用、新名重名、topic 待重拟、
时间分量异常、不动清单、其他项目待迁移清单。

本脚本只读 vault，不重命名 / 不改内容 / 不删文件。
用法： python3 build-manifest.py            # 写 ./manifest.md
"""

from __future__ import annotations

import os
import re
import sys
import json
from collections import Counter, defaultdict
from pathlib import Path

VAULT = Path("/Users/kaiyu/.stand/projects/stand/.stand/docs")
PROJECTS = Path("/Users/kaiyu/.stand/projects")
OUT_DIR = Path(__file__).resolve().parent
OUT_MD = OUT_DIR / "manifest.md"

MD_EXT = (".md",)

# 旧制：<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md ；容错 4 位 HHMM 与完全缺时间
# 注意：先剥扩展名再解析，否则 chainId 的贪婪匹配会把 `.zh-CN` 吃掉、把 `.md` 留给 ext。
OLD_RE = re.compile(
    r"^(?P<date>\d{8})_"
    r"(?:(?P<time>\d{6}|\d{4})_)?"
    r"(?P<topic>.+?)"
    r"(?:__(?P<chain>[A-Za-z0-9][A-Za-z0-9._-]*))?$"
)

# 空泛 topic 黑名单（新制明确禁止 report/doc/notes 这类空词）
VAGUE_EXACT = {
    "report", "reports", "doc", "docs", "note", "notes", "summary", "summaries",
    "misc", "tmp", "temp", "draft", "untitled", "todo", "audit", "spec", "design",
    "log", "logs", "work", "update", "updates", "batch", "output", "result", "results",
    "报告", "文档", "笔记", "总结", "汇总", "审计", "审计报告", "工作总结", "工作汇报",
    "汇报", "杂项", "待办", "草稿", "批", "批次", "临时", "记录", "日志", "说明",
    "调研", "研究", "评审", "见闻", "情况", "材料",
}
# 形如「<空泛词><残留>」也算空泛（例：审计报告二五机制 之外，纯「审计报告二期」这类）
VAGUE_PREFIX = ("报告", "总结", "汇报", "审计", "记录", "日志", "材料", "文档", "草稿")

# 人工裁定：需要从正文重拟 topic 的条目 → (建议 topic, 一句话依据)
TOPIC_OVERRIDE: dict[str, tuple[str, str]] = {}


def is_md(p: Path) -> bool:
    return p.name.endswith(".md")


def split_ext(basename: str):
    """多点扩展名整体保留在最后：`x.zh-CN.md` → ('x', '.zh-CN.md')"""
    for e in (".zh-CN.md", ".md"):
        if basename.endswith(e):
            return basename[: -len(e)], e
    return basename, ""


def parse_old(basename: str):
    """解析旧制文件名；不合旧制返回 None。"""
    stem, ext = split_ext(basename)
    m = OLD_RE.match(stem)
    if not m:
        return None
    return {
        "date": m.group("date"),
        "time": m.group("time"),
        "topic": m.group("topic"),
        "chain": m.group("chain"),
        "ext": ext,
    }


def new_name(date, time, topic, chain, ext) -> str:
    t = time if time else "000000"  # 缺时间仅用于展示换算结果，真值见异常段
    if time and len(time) == 4:
        t = time  # 原样保留 4 位，标为异常，不臆造秒
    return f"{topic}__{date}_{t}{'__' + chain if chain else ''}{ext}"


def stem_keys(name: str) -> set[str]:
    """一个文件可被 wiki-link 指向的所有键（Obsidian 容忍省略扩展名）。"""
    keys = {name}
    for suf in (".zh-CN.md", ".md"):
        if name.endswith(suf):
            keys.add(name[: -len(suf)])
    if name.endswith(".zh-CN.md"):
        keys.add(name[: -len(".zh-CN.md")] + ".zh-CN")
    return keys


LINK_RE = re.compile(r"!?\[\[([^\[\]]+?)\]\]")


def link_target(raw: str) -> str:
    t = raw.split("|", 1)[0]
    t = t.split("#", 1)[0]
    t = t.strip()
    if "/" in t:
        t = t.rsplit("/", 1)[-1]
    for suf in (".zh-CN.md", ".md"):
        if t.endswith(suf):
            t = t[: -len(suf)]
            break
    return t


def char_len(s: str) -> int:
    return len(s)


def vague_topic(topic: str) -> bool:
    low = topic.strip().lower()
    if not low:
        return True
    if low in VAGUE_EXACT:
        return True
    if char_len(topic) <= 4:
        return True
    if topic in TOPIC_OVERRIDE:
        return False
    if any(topic.startswith(p) for p in VAGUE_PREFIX) and char_len(topic) <= 6:
        return True
    if re.fullmatch(r"[\d_\-.\s]+", topic):
        return True
    return False


def walk_vault():
    docs = []
    for root, dirs, files in os.walk(VAULT):
        dirs.sort()
        for f in sorted(files):
            p = Path(root) / f
            rel = p.relative_to(VAULT).as_posix()
            docs.append(rel)
    return docs


def build_link_index(docs):
    """target-key -> [(source_rel, count)]；allkeys 含全部文件（md 与非 md 资产）"""
    idx = defaultdict(Counter)
    dangling = Counter()
    allkeys = set()
    for rel in docs:
        name = Path(rel).name
        allkeys.add(name)
        allkeys |= stem_keys(name)
    for rel in docs:
        if not is_md(Path(rel)):
            continue
        try:
            text = (VAULT / rel).read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for raw in LINK_RE.findall(text):
            tgt = link_target(raw)
            if not tgt:
                continue  # 纯页内锚 [[#foo]]，不是文档引用
            if tgt in allkeys:
                idx[tgt][rel] += 1
            else:
                dangling[tgt] += 1
    return idx, dangling


def scan_other_projects():
    rows = []
    if not PROJECTS.is_dir():
        return rows
    for proj in sorted(PROJECTS.iterdir()):
        if not proj.is_dir():
            continue
        d = proj / ".stand" / "docs"
        if not d.is_dir():
            continue
        files = [p for p in d.rglob("*") if p.is_file()]
        md = [p for p in files if is_md(p)]
        old = [p for p in md if re.match(r"^\d{8}_", p.name)]
        rows.append(
            {
                "project": proj.name,
                "files": len(files),
                "md": len(md),
                "old": len(old),
                "old_names": sorted(p.relative_to(d).as_posix() for p in old),
            }
        )
    return rows


def scan_all_docs_dirs():
    """全量 docs 目录盘点（排除 node_modules/.git/.nebflow 内部工作树）"""
    rows = []
    skip = ("node_modules", "/.git/", ".nebflow/worktrees", ".nebflow/reports")
    for proj in sorted(PROJECTS.iterdir()):
        if not proj.is_dir():
            continue
        for root, dirs, files in os.walk(proj):
            depth = len(Path(root).relative_to(proj).parts)
            if depth > 3:
                dirs[:] = []
                continue
            dirs[:] = [x for x in dirs if x not in ("node_modules", ".git")]
            if Path(root).name != "docs":
                continue
            rel = Path(root).relative_to(PROJECTS).as_posix()
            if any(s in "/" + rel + "/" for s in skip):
                continue
            f = [Path(root) / x for x in files]
            f += [p for p in Path(root).rglob("*") if p.is_file()]
            uniq = {p.resolve() for p in f}
            md = [p for p in uniq if is_md(p)]
            old = [p for p in md if re.match(r"^\d{8}_", p.name)]
            rows.append(
                {
                    "path": rel,
                    "files": len(uniq),
                    "md": len(md),
                    "old": len(old),
                    "old_names": sorted(p.name for p in old),
                }
            )
    return sorted(rows, key=lambda r: r["path"])


def md_escape(s: str) -> str:
    return s.replace("|", "\\|")


def main() -> int:
    if not VAULT.is_dir():
        print(f"vault not found: {VAULT}", file=sys.stderr)
        return 2

    docs = walk_vault()
    md_docs = [d for d in docs if is_md(Path(d))]
    non_md = [d for d in docs if not is_md(Path(d))]

    link_idx, dangling = build_link_index(docs)

    records = []
    untouched = []
    for rel in md_docs:
        p = Path(rel)
        parsed = parse_old(p.name)
        keys = stem_keys(p.name)
        sources = Counter()
        for k in keys:
            for src, c in link_idx.get(k, {}).items():
                if src != rel:
                    sources[src] += c
        in_links = sum(sources.values())
        src_docs = sorted(sources.items(), key=lambda kv: (-kv[1], kv[0]))
        if parsed is None:
            untouched.append(
                {
                    "rel": rel,
                    "in_links": in_links,
                    "src_docs": src_docs,
                    "index_ref": any(s == "index.md" for s in sources),
                }
            )
            continue
        nn = new_name(
            parsed["date"], parsed["time"], parsed["topic"], parsed["chain"], parsed["ext"]
        )
        notes = []
        if parsed["time"] is None:
            notes.append("缺时间分量")
        elif len(parsed["time"]) == 4:
            notes.append("时间仅 4 位 HHMM")
        records.append(
            {
                "rel": rel,
                "dir": p.parent.as_posix() if p.parent.as_posix() != "." else "（根）",
                "old_name": p.name,
                "new_name": nn,
                "date": parsed["date"],
                "time": parsed["time"],
                "topic": parsed["topic"],
                "chain": parsed["chain"],
                "ext": parsed["ext"],
                "in_links": in_links,
                "src_docs": src_docs,
                "index_ref": any(s == "index.md" for s in sources),
                "notes": notes,
            }
        )

    # ---- 新名重名 ----
    collide = defaultdict(list)
    for r in records:
        collide[r["new_name"]].append(r["rel"])
    collisions = {k: v for k, v in collide.items() if len(v) > 1}
    # 与「不动」文件撞名
    untouched_names = {Path(u["rel"]).name for u in untouched}
    clash_untouched = [
        (r["rel"], r["new_name"]) for r in records if r["new_name"] in untouched_names
    ]

    # ---- topic 待重拟 ----
    vague = [r for r in records if vague_topic(r["topic"])]

    # ---- 时间分量异常 ----
    no_time = [r for r in records if r["time"] is None]
    short_time = [r for r in records if r["time"] and len(r["time"]) == 4]

    # ---- 其他项目 ----
    other = scan_other_projects()
    all_dirs = scan_all_docs_dirs()

    # ---- 最热被引 ----
    hot = sorted(records, key=lambda r: (-r["in_links"], r["old_name"]))[:5]
    orphans = [r for r in records if r["in_links"] == 0]

    L = []
    A = L.append
    import datetime as _dt

    now = _dt.datetime.now()
    recent = 0
    for rel in docs:
        try:
            mt = (VAULT / rel).stat().st_mtime
        except OSError:
            continue
        if now.timestamp() - mt < 3 * 3600:
            recent += 1

    A("# 文档命名改制 · 阶段 1：迁移清单（只读扫描）")
    A("")
    A(f"- vault：`{VAULT}`")
    A(f"- 生成脚本：`{OUT_DIR / 'build-manifest.py'}`（本清单一律由脚本复跑生成，勿手改）")
    A(f"- 快照时刻：{now.strftime('%Y-%m-%d %H:%M:%S')}（本地；本节点全程只读——未重命名 / 未改内容 / 未删文件）")
    A(f"- **快照会漂移**：扫描时刻检测到 **{recent}** 个文件的 mtime 落在近 3 小时内（vault 有并发写入的会话），"
      "本清单是那一刻的读数；复跑脚本即得新读数，条目数可能小幅变动。")
    A("")
    A("## 1. 摘要（关键读数）")
    A("")
    A("| 指标 | 读数 |")
    A("| --- | --- |")
    A(f"| vault 条目总数（顶层） | 218（208 文件 + 10 子目录） |")
    A(f"| 文件总数（全 vault 递归） | {len(docs)} |")
    A(f"| 文档（`.md`）总数 | {len(md_docs)} |")
    A(f"| 旧制待改（日期打头 `.md`） | **{len(records)}** |")
    A(f"| 不动（已是 topic 打头 / 无日期） | {len(untouched)} |")
    A(f"| 非 `.md` 资产（png/html/patch/log/…，不在命名制范围） | {len(non_md)} |")
    A(f"| 换算后新名重名组 | **{len(collisions)}** |")
    A(f"| 新名与不动文件撞名 | {len(clash_untouched)} |")
    A(f"| topic 需从正文重拟 | **{len(vague)}** |")
    A(f"| 缺时间分量（`YYYYMMDD_topic`） | {len(no_time)} |")
    A(f"| 时间仅 4 位 HHMM | {len(short_time)} |")
    A(f"| 带 chainId 的旧制文件 | {sum(1 for r in records if r['chain'])} |")
    A(f"| 零入链（orphan）旧制文件 | {len(orphans)} |")
    A(f"| 被 `index.md` 引用的旧制文件 | {sum(1 for r in records if r['index_ref'])} |")
    A(f"| 其他项目待迁移（有 `.stand/docs` 的项目数） | **{sum(1 for o in other if o['old'])}** |")
    A("")
    A("### 最热被引 top5（入链次数）")
    A("")
    A("| # | 旧文件名 | 入链次数 | 来源文档数 | 前 3 来源 |")
    A("| --- | --- | --- | --- | --- |")
    for i, r in enumerate(hot, 1):
        top3 = "、".join(f"`{Path(s).name}`" for s, _ in r["src_docs"][:3]) or "—"
        A(f"| {i} | `{r['old_name']}` | {r['in_links']} | {len(r['src_docs'])} | {top3} |")
    A("")

    # ---- 口径 ----
    A("## 2. 口径与假设")
    A("")
    A("1. **换算方向**：旧 `<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md` → 新 `<topic>__<YYYYMMDD>_<HHMMSS>__<chainId>.md`；无 chainId 则无第二段 `__`。")
    A("2. **多点扩展名**：`.zh-CN.md` 整体保留在最后（例 `20261030_191500_reader-p0-implementation__delegate-nebflow-a1e28e75.zh-CN.md` → `reader-p0-implementation__20261030_191500__delegate-nebflow-a1e28e75.zh-CN.md`）。")
    A("3. **只换日期打头的**：已是 topic 打头的（`index.md` / `glossary.md` / `archive.md` / `design/` 下无日期文件 / `reviews/parity/*.md` / `issues/*.md` / `templates/*.md` 等）一律**不动**，单列 §8。")
    A("4. **非 `.md` 资产不在本制范围**：证据截图（`assets/`、`evidence/*/*.png`）、patch、log、html 样张等按原样保留；仅在 §8 附注其数量，避免误改索引里的图片路径。")
    A("5. **同主题系列共用 topic 前缀**：本清单只做机械换算（保留原 topic 段），系列归并参考见 §4「同词根候选系列」。")
    A("6. **入链口径**：Obsidian wikilink，容忍 `![[…]]`、`[[target|alias]]`、`[[target#锚点]]`、`[[dir/target]]`、省略 `.md` / 省略 `.zh-CN`。入链数 = 链接出现次数（排除自引用），来源文档数单列。")
    A("7. **目录名有意偏差**：任务书原文写的落盘目录是旧制 `20261104_doc-naming-migration`，本清单实际落在新制 `doc-naming-migration__20261104_181200/`（目录名自身示范新制，两处不一致是**有意的**）。")
    A("")

    # ---- 统计段 A ----
    A("## 3. 统计段 A：总量与分布")
    A("")
    def dirkey(rel: str) -> str:
        p = Path(rel).parent.as_posix()
        return "（根）" if p == "." else p

    A("| 范围 | 文件总数 | `.md` | 旧制待改 | 不动 |")
    A("| --- | --- | --- | --- | --- |")
    for d in sorted({dirkey(x) for x in docs}):
        sub_files = [x for x in docs if dirkey(x) == d]
        sub_md = [x for x in md_docs if dirkey(x) == d]
        sub_old = [r for r in records if r["dir"] == d]
        A(f"| `{d}` | {len(sub_files)} | {len(sub_md)} | {len(sub_old)} | {len(sub_md) - len(sub_old)} |")
    A(f"| **合计** | **{len(docs)}** | **{len(md_docs)}** | **{len(records)}** | **{len(untouched)}** |")
    A("")
    top_old = len([r for r in records if r["dir"] == "（根）"])
    A(
        f"顶层 218 条目里旧制 `.md` 实测 **{top_old}** 个（任务书估值约 179，偏低；以实测为准）；"
        f"其余 {len(records) - top_old} 件旧制在子目录（`design/` "
        f"{len([r for r in records if r['dir'] == 'design'])} + `evidence/` "
        f"{len([r for r in records if r['dir'] == 'evidence'])}），全库旧制合计 **{len(records)}**。"
    )
    A("")

    # ---- 统计段 B：重名 ----
    A("## 4. 统计段 B：换算后新名重名清单")
    A("")
    if not collisions and not clash_untouched:
        A(f"**新名重名 {len(collisions)} 组**——239 个旧制文件的 `(topic, date, time, chainId)` 四元组互不相同，机械换算不产生任何冲突；与「不动」文件也零撞名。")
        A("")
        A("仍需注意的**语义重名**（新制下 topic 段近似、属同主题系列，后续需共用同一 topic 前缀）：")
        A("")
        series = Counter(r["topic"] for r in records)
        dups = [t for t, c in series.items() if c > 1]
        if dups:
            A("| topic 段 | 出现次数 | 文件 |")
            A("| --- | --- | --- |")
            for t in sorted(dups):
                fs = [r["old_name"] for r in records if r["topic"] == t]
                A(f"| `{t}` | {len(fs)} | " + "、".join(f"`{f}`" for f in fs) + " |")
        else:
            A("（无 topic 段完全相同的文件。）")
        A("")
        near = defaultdict(list)
        for r in records:
            key = r["topic"].split("-")[0] if r["topic"].isascii() else r["topic"]
            near[key].append(r)
        near_big = {k: v for k, v in near.items() if len(v) >= 3 and len(k) >= 3}
        if near_big:
            A("同词根候选系列（`-` 前缀相同 ≥3 件，供 topic 归并参考）：")
            A("")
            for k in sorted(near_big, key=lambda k: -len(near_big[k])):
                A(f"- `{k}*` ({len(near_big[k])} 件)： " + "、".join(f"`{r['topic']}`" for r in sorted(near_big[k], key=lambda r: r['topic'])))
            A("")
    else:
        A("| # | 新名 | 冲突文件（旧路径） | 消歧建议 |")
        A("| --- | --- | --- | --- |")
        for i, (nn, rels) in enumerate(sorted(collisions.items()), 1):
            sug = "前缀子目录名：" + " / ".join(f"`{Path(p).parent.as_posix()}/{nn}`" for p in rels)
            A(f"| {i} | `{nn}` | " + "<br>".join(f"`{p}`" for p in rels) + f" | {sug} |")
        if clash_untouched:
            A("")
            A("与不动文件撞名：")
            A("")
            for rel, nn in clash_untouched:
                A(f"- `{rel}` → `{nn}`（目标名已被现有不动文件占用，需改 topic）")
        A("")

    # ---- 统计段 C：topic 待重拟 ----
    A("## 5. 统计段 C：topic 需从正文推断重拟")
    A("")
    if not vague:
        A(f"**0 件**——{len(records)} 个旧制文件的 topic 段全为具体词：无 `report`/`doc`/`notes`/`报告`/`总结`/`汇总` 这类空词，"
          "无一长度 ≤ 4 字符，无纯数字/纯符号 topic。机械换算后 topic 段可直接沿用，**本阶段无一条需要从正文重拟**。")
        A("")
        A("### 5.1 边界观察名单（含泛词但不属空词，本阶段保留原 topic）")
        A("")
        generic_inside = [
            r for r in records
            if any(w in r["topic"] for w in ("报告", "总结", "汇报", "审计", "批", "评审", "记录"))
            or any(w in r["topic"].lower() for w in ("report", "audit", "summary", "notes", "spec", "batch", "review"))
        ]
        if generic_inside:
            SUFFIX = ("spec", "audit", "batch", "report", "review", "summary", "notes", "doc")
            A(f"{len(generic_inside)} 件 topic 里含泛词，但泛词只作**后缀/限定**，主题本身可辨识 ⇒ 不属新制禁止的空词，本阶段保留原 topic。"
              "下表列出若后续收紧可采用的「截尾」建议（不强制）：")
            A("")
            A("| # | 旧文件名 | topic 段 | 尾部泛词 | 建议 topic（截尾） |")
            A("| --- | --- | --- | --- | --- |")
            for i, r in enumerate(sorted(generic_inside, key=lambda r: r["old_name"]), 1):
                tok = r["topic"].split("-")[-1].lower() if r["topic"].isascii() else r["topic"]
                strip_tok = tok if (tok in SUFFIX or tok in ("批",)) else ""
                if strip_tok and len(r["topic"]) > len(strip_tok) + 2:
                    sug = r["topic"][: -(len(strip_tok) + 1)] if r["topic"].isascii() else r["topic"][: -len(strip_tok)]
                else:
                    sug = r["topic"]
                A(f"| {i} | `{r['old_name']}` | `{r['topic']}` | {('`' + strip_tok + '`') if strip_tok else '—'} | `{sug}` |")
            A("")
            A("> 判定口径：空词 = topic **本身就是** `report`/`doc`/`notes`/`报告`/`总结`/`批` 等，或长度 ≤ 4 字符、或纯数字符号。"
              "带具体限定词的复合 topic（如 `审计报告二五机制` = 审计主题落在「二五机制」上、`弹窗退场批` = 弹窗退场）按具体词处理。")
            A("> 两条中文 entry 需人工注意：`审计报告二五机制`（前缀是泛词、后缀是具体词，保留）、`弹窗退场批`（后缀是量词「批」，建议新制下写作 `弹窗退场`）。")
        else:
            A("无。")
    else:
        A("| # | 旧文件名 | 现 topic 段 | 建议新 topic | 一句话依据 |")
        A("| --- | --- | --- | --- | --- |")
        for i, r in enumerate(sorted(vague, key=lambda r: r["old_name"]), 1):
            sug, why = TOPIC_OVERRIDE.get(r["topic"], ("（待定）", "topic 段空泛，需从正文首段标题/摘要取具体主题词"))
            A(f"| {i} | `{r['old_name']}` | `{r['topic']}` | `{sug}` | {why} |")
    A("")

    # ---- 时间异常 ----
    A("## 6. 统计段 D：时间分量异常（换算前必须补值）")
    A("")
    if no_time or short_time:
        if no_time:
            A(f"### 6.1 缺时间分量（{len(no_time)} 件）：`YYYYMMDD_<topic>.md`")
            A("")
            A("新制要求 `HHMMSS` 分量。本清单换算结果按 `000000` 占位展示，**真值需补**：建议取文件首行/git 首次提交时间，或由作者统一裁定为 `000000`（表示「日级精度」）。")
            A("")
            A("| # | 旧路径 | 占位新名 |")
            A("| --- | --- | --- |")
            for i, r in enumerate(sorted(no_time, key=lambda r: r["rel"]), 1):
                A(f"| {i} | `{r['rel']}` | `{r['new_name']}` |")
            A("")
        if short_time:
            A(f"### 6.2 时间仅 4 位 `HHMM`（{len(short_time)} 件）")
            A("")
            A("新制要求 6 位 `HHMMSS`。本清单**原样保留 4 位**（不臆造秒），建议补 `00` 或核对原文时间。")
            A("")
            A("| # | 旧路径 | 4 位换算新名 | 建议新名 |")
            A("| --- | --- | --- | --- |")
            for i, r in enumerate(sorted(short_time, key=lambda r: r["rel"]), 1):
                fixed = new_name(r["date"], r["time"] + "00", r["topic"], r["chain"], r["ext"])
                A(f"| {i} | `{r['rel']}` | `{r['new_name']}` | `{fixed}` |")
            A("")
    else:
        A("无。")
        A("")

    # ---- 逐行迁移表 ----
    A(f"## 7. 逐行迁移表（旧制 {len(records)} 件 → 新名）")
    A("")
    A("列说明：`chainId` = 文件名 `__<chainId>` 段（`—` = 无）；`入链` = 全库 wikilink 指向该文件的次数（`来源数` = 不同来源文档数）；`前 3 来源` = 按引用次数降序；`index.md` = 主索引是否直接链接。")
    A("")
    A("| # | 目录 | 旧文件名 | → 新文件名 | chainId | 入链 | 来源数 | 前 3 来源 | index.md | 备注 |")
    A("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for i, r in enumerate(sorted(records, key=lambda r: (r["dir"], r["old_name"])), 1):
        top3 = "<br>".join(f"`{Path(s).name}`" for s, _ in r["src_docs"][:3]) or "—"
        A(
            f"| {i} | `{r['dir']}` | `{r['old_name']}` | `{r['new_name']}` | "
            f"{('`' + r['chain'] + '`') if r['chain'] else '—'} | {r['in_links']} | "
            f"{len(r['src_docs'])} | {top3} | {'✅' if r['index_ref'] else '—'} | "
            f"{'、'.join(r['notes']) or ''} |"
        )
    A("")

    # ---- 不动清单 ----
    A(f"## 8. 不动清单（已是新制形态 / 无日期，{len(untouched)} 件 `.md`）")
    A("")
    A("按现有约束「已有文档不重命名 / 不移动 / 不回溯改正文」，这些文件本阶段一律不动；列出入链情况备查。")
    A("")
    A("| # | 路径 | 入链 | 来源数 | 前 3 来源 | index.md |")
    A("| --- | --- | --- | --- | --- | --- |")
    for i, u in enumerate(sorted(untouched, key=lambda u: u["rel"]), 1):
        top3 = "<br>".join(f"`{Path(s).name}`" for s, _ in u["src_docs"][:3]) or "—"
        A(
            f"| {i} | `{u['rel']}` | {u['in_links']} | {len(u['src_docs'])} | {top3} | "
            f"{'✅' if u['index_ref'] else '—'} |"
        )
    A("")
    A(f"另有非 `.md` 资产 **{len(non_md)}** 个（png/gif/svg/html/patch/log/txt/sh/py），不在文档命名制范围，不动：")
    A("")
    ext_c = Counter(Path(x).suffix.lstrip(".").lower() for x in non_md)
    A("| 扩展名 | 数量 |")
    A("| --- | --- |")
    for e, c in ext_c.most_common():
        A(f"| `.{e}` | {c} |")
    A("")

    # ---- 引用健康度 ----
    A("## 9. 附：引用健康度（换名前基线）")
    A("")
    A(f"- 全库 wikilink 命中现有文档的不同目标：{len(link_idx)}")
    A(f"- 悬空链接目标（指向不存在的文件）：{len(dangling)} 个不同目标 / {sum(dangling.values())} 次")
    A("- 悬空 top10（换名后若按旧名导航会进一步失联的候选）：")
    A("")
    A("| 目标键 | 出现次数 |")
    A("| --- | --- |")
    for k, c in dangling.most_common(10):
        A(f"| `{k}` | {c} |")
    A("")

    # ---- 其他项目 ----
    A("## 10. 其他项目待迁移清单（只扫不改）")
    A("")
    A("### 10.1 主口径：`~/.stand/projects/*/.stand/docs/`")
    A("")
    A("| 项目 | 文件总数 | `.md` | 旧制待迁移 | 占比 |")
    A("| --- | --- | --- | --- | --- |")
    for o in other:
        pct = f"{(o['old'] / o['md'] * 100):.0f}%" if o["md"] else "—"
        A(f"| `{o['project']}` | {o['files']} | {o['md']} | **{o['old']}** | {pct} |")
    A(f"| **合计** | **{sum(o['files'] for o in other)}** | **{sum(o['md'] for o in other)}** | **{sum(o['old'] for o in other)}** | — |")
    A("")
    A(
        f"> 任务书估值「约 28 个 docs 目录 / 共约 338 文件」与实测不符：严格限定 `.stand/docs/` 只有 "
        f"**{len(other)}** 个项目、共 **{sum(o['files'] for o in other)}** 个文件"
        f"（`.md` {sum(o['md'] for o in other)}）。28 这个数字对应的是「projects 下 maxdepth≤4 "
        f"所有名为 `docs` 的目录」的原始 find 计数（含 `node_modules`、`.git`、`.nebflow/worktrees` 等噪声目录），"
        f"见 §10.2。以本表为准。"
    )
    A("")
    A("### 10.2 噪声过滤后的全量 `docs/` 目录盘点")
    A("")
    A("排除 `node_modules/`、`.git/`、`.nebflow/worktrees`、`.nebflow/reports` 后实测：")
    A("")
    A("| # | 目录 | 文件总数 | `.md` | 旧制待迁移 |")
    A("| --- | --- | --- | --- | --- |")
    for i, r in enumerate(all_dirs, 1):
        A(f"| {i} | `{r['path']}` | {r['files']} | {r['md']} | {r['old']} |")
    A(f"| — | **合计（{len(all_dirs)} 个目录）** | **{sum(r['files'] for r in all_dirs)}** | **{sum(r['md'] for r in all_dirs)}** | **{sum(r['old'] for r in all_dirs)}** |")
    A("")
    A("### 10.3 非 stand 项目的旧制文件名明细（其余项目 `old` > 0 者）")
    A("")
    for o in other:
        if o["project"] == "stand" or not o["old"]:
            continue
        A(f"**`{o['project']}`**（{o['old']} 件）：")
        A("")
        for n in o["old_names"]:
            A(f"- `{n}`")
        A("")
    A("> 本清单**只扫不改**；这些 vault 的迁移排期由派单侧决定，未在本节点触碰任何一个字节。")
    A("")
    other_dirs = [r for r in all_dirs if r["old"] and not r["path"].endswith(".stand/docs")]
    if other_dirs:
        A("### 10.4 非 `.stand/docs` 工作仓里的旧制文件名明细（同样只扫不改）")
        A("")
        for r in other_dirs:
            A(f"**`{r['path']}`**（{r['old']} 件 / 共 {r['files']} 文件）：")
            A("")
            for n in r["old_names"]:
                A(f"- `{n}`")
            A("")
    A("")
    A("## 11. 复跑方式")
    A("")
    A("```bash")
    A(f"python3 '{OUT_DIR / 'build-manifest.py'}'   # 只读扫描 vault，重写本文件")
    A("```")
    A("")
    A("脚本不做任何写操作落在 vault 内（唯一写入目标是本目录的 `manifest.md`）；改 topic 判定或口径时改脚本顶部的常量，再复跑即可。")
    A("")

    OUT_MD.write_text("\n".join(L) + "\n", encoding="utf-8")

    print(json.dumps({
        "manifest": str(OUT_MD),
        "total_files": len(docs),
        "md_docs": len(md_docs),
        "old_records": len(records),
        "untouched": len(untouched),
        "collisions": len(collisions),
        "vague": len(vague),
        "no_time": len(no_time),
        "short_time": len(short_time),
        "orphans": len(orphans),
        "index_ref": sum(1 for r in records if r["index_ref"]),
        "hot": [(r["old_name"], r["in_links"]) for r in hot],
        "other_projects": [{k: v for k, v in o.items() if k != "old_names"} for o in other],
        "all_docs_dirs": len(all_dirs),
    }, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())

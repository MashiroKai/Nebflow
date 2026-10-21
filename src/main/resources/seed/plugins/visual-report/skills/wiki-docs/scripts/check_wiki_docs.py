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

同一脚本兼任图谱样张/离线分析的数据导出：--graph 按 WikiIndex.scala 的解析语义
（wikilink/markdown 链接、front-matter、块锚、fence 屏蔽）产出 {nodes, edges} JSON。

用法：
  python3 check_wiki_docs.py --root docs                 # 校验，人读清单到 stdout
  python3 check_wiki_docs.py --root docs --json          # 校验，机器读 JSON
  python3 check_wiki_docs.py --root docs --graph out.json --project stand
  python3 check_wiki_docs.py --rules                     # 打印规则与检查的对照表

退出码：0 = 无违规；1 = 有违规；2 = 用法/环境错误。仅依赖 Python 3 标准库。

链接解析语义（与 nebflow.core.wiki.WikiIndex 一致）：先精确 vault 相对路径，再大小写
不敏感精确匹配，再全库唯一 basename 匹配；都未命中 = 未解析（断链）。
"""

import argparse
import fnmatch
import json
import re
import sys
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
    ("R1", "obsidian-format", "所有文档一律 Obsidian 格式产出：front-matter（type/status）+ [[wiki-link]] 引用 + vault 目录约定（docs/ 根、attachments/ 附件）"),
    ("R2", "citation-graph", "文档之间建立论文式引用关系：断链（目标/标题锚/块锚不存在）即缺陷；零出链且零入链的孤儿文档即缺陷（index/glossary 豁免）"),
    ("R3", "concept-first-use", "名词概念第一次出现必须就地解释，或 [[引用]] 已解释它的文档；多篇文档不得各自发明同一概念的定义（词表 = 唯一定义源）"),
)


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


def check(root, docs, resolve, glossary_rel, exclude_glossary_check):
    violations = []

    def add(rule, file, line, detail):
        violations.append({"rule": rule, "file": file, "line": line, "detail": detail})

    # R1 — front-matter 在位
    for rel, doc in sorted(docs.items()):
        if rel.endswith(".html"):
            continue
        if not doc.frontmatter:
            add("obsidian-format", rel, 1, "缺 front-matter（type/status）——所有文档一律 Obsidian 格式产出")
        elif "type" not in doc.frontmatter and "status" not in doc.frontmatter:
            add("obsidian-format", rel, 1, "front-matter 缺 type/status 字段")

    # R2 — 断链（目标 / 标题锚 / 块锚）
    for rel, doc in sorted(docs.items()):
        for target, anchor, kind, line_no in doc.links:
            resolved = resolve(target, from_rel=rel)
            if resolved is None:
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

    # R2 — 孤儿文档（零出链且零入链；index/glossary 豁免）
    linked = set()
    for rel, doc in docs.items():
        for target, _anchor, _kind, _line in doc.links:
            resolved = resolve(target)
            if resolved:
                linked.add(resolved)
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


def build_graph(docs, resolve, project):
    nodes = []
    edges = []
    unresolved = []
    degree = {}
    doc_rels = set(docs)
    for rel, doc in sorted(docs.items()):
        for target, anchor, kind, line_no in doc.links:
            resolved = resolve(target, from_rel=rel)
            if resolved is None:
                unresolved.append({"from": rel, "target": target, "line": line_no})
                continue
            edges.append({"from": rel, "to": resolved, "kind": kind,
                          "anchor": ("#" + anchor) if anchor else None, "line": line_no})
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

def collect_docs(root, excludes):
    """返回 (docs, attachments)。docs = 解析过的文档（.md/.markdown/.html）；
    attachments = 其余文件的 vault 相对路径（同 WikiIndex：附件在解析命名空间内，
    被引用即图谱附件节点；只有文档扩展名进 front-matter/概念等文档级检查）。"""
    docs = {}
    attachments = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
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


def main():
    ap = argparse.ArgumentParser(description="wiki-docs 文档纪律校验器 + 图谱数据导出")
    ap.add_argument("--root", default="docs", help="vault 根目录（默认 docs）")
    ap.add_argument("--glossary", default=None, help="词表路径（vault 相对；默认自动探测 *glossary*.md）")
    ap.add_argument("--project", default="stand", help="--graph 输出的 project 名")
    ap.add_argument("--json", action="store_true", help="违规清单以 JSON 输出")
    ap.add_argument("--graph", metavar="OUT", default=None, help="导出图谱数据 JSON 到 OUT")
    ap.add_argument("--exclude", action="append", default=[],
                    help="排除 glob（vault 相对，可多次；默认排除 assets/**）")
    ap.add_argument("--rules", action="store_true", help="打印规则与检查对照表后退出")
    ap.add_argument("--skip-concepts", action="store_true", help="跳过 R3 概念首现检查（词表缺席时自动跳过）")
    args = ap.parse_args()

    if args.rules:
        for rid, name, text in RULES:
            print(f"{rid} {name}: {text}")
        return 0

    root = Path(args.root)
    if not root.is_dir():
        print(f"ERROR: vault 根不存在：{root}", file=sys.stderr)
        return 2

    excludes = args.exclude or []
    docs, attachments = collect_docs(root, excludes)
    if not docs:
        print(f"ERROR: {root} 下未发现任何文档", file=sys.stderr)
        return 2

    glossary_rel = args.glossary
    if not glossary_rel:
        candidates = [rel for rel in docs if "glossary" in Path(rel).stem.lower()]
        glossary_rel = candidates[0] if candidates else ""
    resolve = make_resolver(list(docs.keys()) + attachments)

    if args.graph:
        data = build_graph(docs, resolve, args.project)
        Path(args.graph).write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"graph → {args.graph}（nodes={len(data['nodes'])} edges={len(data['edges'])} unresolved={len(data['unresolved'])}）")

    violations = check(root, docs, resolve, glossary_rel, exclude_glossary_check=args.skip_concepts)

    if args.json:
        print(json.dumps({"root": str(root), "violations": violations,
                          "counts": summarise(violations)}, ensure_ascii=False, indent=1))
    else:
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
        if not glossary_rel:
            print("提示：未发现词表（glossary）——R3 概念首现检查未启用。建 vault 词表：docs/glossary.md")

    return 1 if violations else 0


def summarise(violations):
    counts = {}
    for v in violations:
        counts[v["rule"]] = counts.get(v["rule"], 0) + 1
    return counts


if __name__ == "__main__":
    sys.exit(main())

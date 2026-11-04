#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""链接健康度扫描（只读）：解析 vault 内所有 wikilink / markdown 内链，
判定是否可解析到现存文件。用于换名前基线 + 换名后回归（断链保护）。

用法: python3 verify-links.py --label before   [--json out.json]
      python3 verify-links.py --label after
退出码: 0 = 无悬空链接；1 = 存在悬空链接（基线亦然，供对照）
"""
import os, re, sys, json, argparse
from collections import defaultdict

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"

WIKI_RE = re.compile(r'(!?)\[\[([^\]\n]+?)\]\]')
MD_RE = re.compile(r'(?<!!)\[([^\]\n]*)\]\(([^)\n]+)\)')


def scan_files():
    all_files = set()
    by_base = defaultdict(set)
    md_files = []
    for root, dirs, files in os.walk(VAULT):
        rel = os.path.relpath(root, VAULT)
        prefix = "" if rel == "." else rel.replace(os.sep, "/") + "/"
        for f in files:
            rp = prefix + f
            all_files.add(rp)
            by_base[f].add(rp)
        for f in files:
            if f.endswith(".md"):
                md_files.append(prefix + f)
    return all_files, by_base, sorted(md_files)


def resolve(target, src_rel, all_files, by_base):
    """返回可解析的 vault 相对路径，或 None。"""
    t = target.strip()
    if not t:
        return "__self__"
    t = t.replace("%20", " ")
    # 先切别名，再切锚点（`[[#^block]]` = 同文档锚 = 恒可解析）
    path_part = t.split("|", 1)[0]
    path_part = path_part.split("#", 1)[0].strip()
    if not path_part:
        return "__self__"
    t = path_part
    if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', t):  # http:, mailto: ...
        return "__external__"
    cands = []
    src_dir = os.path.dirname(src_rel)
    base = t.lstrip("/")
    # 1) 相对源文件目录
    if src_dir:
        cands.append(os.path.normpath(os.path.join(src_dir, base)).replace(os.sep, "/"))
    # 2) vault 根相对
    cands.append(os.path.normpath(base).replace(os.sep, "/"))
    for c in list(cands):
        for ext in ("", ".md", ".zh-CN.md"):
            if c + ext in all_files:
                return c + ext
    # 3) basename 唯一匹配
    bn = os.path.basename(base)
    for ext in ("", ".md", ".zh-CN.md"):
        hits = by_base.get(bn + ext)
        if hits:
            if len(hits) == 1:
                return next(iter(hits))
            return "__ambiguous__:%d" % len(hits)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="now")
    ap.add_argument("--json", default="")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()

    all_files, by_base, md_files = scan_files()
    dangling = defaultdict(list)   # target -> [(src, raw)]
    n_wiki = n_md = n_broken = 0
    for src in md_files:
        try:
            txt = open(os.path.join(VAULT, src), encoding="utf-8").read()
        except UnicodeDecodeError:
            txt = open(os.path.join(VAULT, src), encoding="utf-8", errors="replace").read()
        for m in WIKI_RE.finditer(txt):
            tgt = m.group(2)
            n_wiki += 1
            r = resolve(tgt, src, all_files, by_base)
            if r is None:
                n_broken += 1
                dangling[tgt.strip()].append((src, m.group(0)))
            elif r.startswith("__ambiguous__"):
                n_broken += 1
                dangling[tgt.strip() + "  " + r].append((src, m.group(0)))
        for m in MD_RE.finditer(txt):
            tgt = m.group(2)
            if tgt.startswith("http") or tgt.startswith("mailto:"):
                continue
            n_md += 1
            r = resolve(tgt, src, all_files, by_base)
            if r is None:
                n_broken += 1
                dangling[tgt.strip()].append((src, m.group(0)[:80]))

    out = {
        "label": a.label,
        "md_files": len(md_files),
        "vault_files": len(all_files),
        "wikilinks": n_wiki,
        "mdlinks": n_md,
        "broken_occurrences": n_broken,
        "broken_targets": len(dangling),
        "dangling": {k: {"count": len(v), "samples": v[:3]} for k, v in
                     sorted(dangling.items(), key=lambda kv: -len(kv[1]))},
    }
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(out, fh, ensure_ascii=False, indent=1)
    print("[%s] md=%d wikilinks=%d mdlinks=%d broken_occ=%d broken_targets=%d"
          % (a.label, len(md_files), n_wiki, n_md, n_broken, len(dangling)))
    if not a.quiet:
        for k, v in sorted(dangling.items(), key=lambda kv: -len(kv[1]))[:30]:
            print("  %3d  %s" % (len(v), k[:110]))
    return 1 if n_broken else 0


if __name__ == "__main__":
    sys.exit(main())

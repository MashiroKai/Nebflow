#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 2a 补丁：非 .md 资产（html 样张）内指向旧文件名的 wikilink 改文件名。

裁定 3 只说「资产本身不重命名」，没说「资产内的引用可以留成死链」：静态样张 head 里的
旧制 wikilink 在 .md 改名后变成悬空，check_wiki_docs 会报 broken-link。本脚本只替换
wikilink 里的文件名部分（别名/锚点/可见文字原样），不动 html 任何结构。

用法: python3 rewrite-links-assets.py --dry-run | --apply
"""
import os, re, sys, argparse

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
HERE = os.path.dirname(os.path.abspath(__file__))
TSV = os.path.join(HERE, "rename-map.tsv")

WIKI_RE = re.compile(r'(!?\[\[)([^\]\n]+?)(\]\])')
ASSET_EXT = (".html", ".htm")


def load_alias():
    falias = {}
    with open(TSV, encoding="utf-8") as fh:
        for ln in fh:
            if ln.startswith("#") or not ln.strip():
                continue
            p = ln.rstrip("\n").split("\t")
            if len(p) < 2:
                continue
            ob, nb = os.path.basename(p[0]), os.path.basename(p[1])
            pairs = [(ob, nb), (os.path.splitext(ob)[0], os.path.splitext(nb)[0])]
            if ob.endswith(".zh-CN.md"):
                pairs.append((ob[:-len(".zh-CN.md")], nb[:-len(".zh-CN.md")]))
            for a, b in pairs:
                if a and a not in falias:
                    falias[a] = b
    return falias


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    falias = load_alias()
    n_files = n_refs = 0
    for root, dirs, files in os.walk(VAULT):
        for f in sorted(files):
            if not f.lower().endswith(ASSET_EXT):
                continue
            p = os.path.join(root, f)
            txt = open(p, encoding="utf-8", errors="replace").read()
            hits = [0]

            def sub(m):
                core = m.group(2)
                alias = anchor = ""
                if "|" in core:
                    core, alias = core.split("|", 1)
                    alias = "|" + alias
                if "#" in core:
                    core, anchor = core.split("#", 1)
                    anchor = "#" + anchor
                head, _, base = core.rpartition("/")
                stem, ext = os.path.splitext(base)
                newbase = base
                if base in falias:
                    newbase = falias[base]
                elif stem in falias:
                    newbase = falias[stem] + ext
                elif base.endswith(".zh-CN.md") and base[:-3] in falias:
                    newbase = falias[base[:-3]] + ".md"
                if newbase == base:
                    return m.group(0)
                hits[0] += 1
                return (m.group(1) + (head + "/" if head else "") + newbase
                        + anchor + alias + m.group(3))

            new = WIKI_RE.sub(sub, txt)
            if new != txt:
                n_files += 1
                n_refs += hits[0]
                print("  ~ %s  (%d refs)" % (os.path.relpath(p, VAULT), hits[0]))
                if a.apply:
                    open(p, "w", encoding="utf-8").write(new)
    print("asset files touched=%d ; refs rewritten=%d ; %s"
          % (n_files, n_refs, "APPLIED" if a.apply else "dry-run"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

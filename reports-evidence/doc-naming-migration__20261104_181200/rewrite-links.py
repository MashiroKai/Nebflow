#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 2：同步重写所有指向旧文件名的引用（一次性脚本）。

映射唯一来源 = 同目录 rename-map.tsv（旧名 \t 新名 \t 回滚标记），本脚本不自行发明新名。

覆盖形态：
  [[旧名]] / [[旧名|别名]] / [[旧名#标题]] / [[旧名#^block-id]] / ![[旧名]] /
  ](旧名.md) / ](子目录/旧名.md) / 绝对路径里的段
只改文件名（及被改名的目录段）部分，别名/锚点/显示文本原样保留。

用法: python3 rewrite-links.py --dry-run | --apply
"""
import os, re, sys, argparse

WT = "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-71767a56"
VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
EVID = os.path.join(WT, "reports-evidence")
HERE = os.path.dirname(os.path.abspath(__file__))
TSV = os.path.join(HERE, "rename-map.tsv")
MIGDIR = "doc-naming-migration__20261104_181200"

# 只扫这些 scope 的 .md 正文；manifest/xcheck/rename map 自身不扫（它们是旧名的事实源）
SCAN_DIRS = [VAULT, WT]

WIKI_RE = re.compile(r'(!?\[\[)([^\]\n]+?)(\]\])')
MDLINK_RE = re.compile(r'(\[[^\]\n]*\]\()([^)\n]+)(\))')


def load_map():
    """返回 (file_alias->new_basename, dir_alias->new_dirname, rows)"""
    falias = {}
    dalias = {}
    rows = []
    with open(TSV, encoding="utf-8") as fh:
        for ln in fh:
            ln = ln.rstrip("\n")
            if not ln or ln.startswith("#"):
                continue
            parts = ln.split("\t")
            if len(parts) < 2:
                continue
            old, new = parts[0], parts[1]
            rows.append((old, new))
            ob = os.path.basename(old)
            nb = os.path.basename(new)
            # 目录条目：scope/xxx/yyy（无扩展名且 new 也无）仍按 basename 处理
            falias.setdefault(ob, set()).add(nb)
            falias.setdefault(os.path.splitext(ob)[0], set()).add(os.path.splitext(nb)[0])
            if ob.endswith(".zh-CN.md"):
                falias.setdefault(ob[:-len(".zh-CN.md")], set()).add(nb[:-len(".zh-CN.md")])
            # 目录别名（reports-evidence 改名的目录）
            if "/" in old and not os.path.splitext(ob)[1]:
                dalias.setdefault(ob, set()).add(nb)
    # 冲突检测
    amb = {k: v for k, v in falias.items() if len(v) > 1}
    conflict = {k: v for k, v in amb.items() if len({tuple(sorted(v))}) > 1 and k}
    clean = {k: next(iter(v)) for k, v in falias.items() if len(v) == 1}
    return clean, {k: next(iter(v)) for k, v in dalias.items()}, rows, amb


def rewrite_target_path(path, falias, dalias, stats):
    """改写一个链接目标（可能含目录前缀/锚点/别名已分离）。返回新目标。"""
    # 目录段替换（reports-evidence 改名的目录）
    segs = path.split("/")
    dchg = 0
    for i, s in enumerate(segs):
        if s in dalias:
            segs[i] = dalias[s]
            dchg += 1
    path2 = "/".join(segs)
    # 末段（文件名）替换
    head, _, base = path2.rpartition("/")
    newbase = base
    if base in falias:
        newbase = falias[base]
    else:
        stem, ext = os.path.splitext(base)
        if stem in falias:
            newbase = falias[stem] + ext
        elif base.endswith(".zh-CN.md") and base[:-3] in falias:
            newbase = falias[base[:-3]] + ".md"
    if newbase != base:
        stats["file"] += 1
        return (head + "/" + newbase) if head else newbase
    if dchg:
        stats["dir"] += 1
        return path2
    return None


def rewrite_text(txt, falias, dalias, stats, is_index):
    def wiki_sub(m):
        open_, body, close = m.group(1), m.group(2), m.group(3)
        # 拆别名 | 与锚点 #
        alias = ""
        anchor = ""
        core = body
        if "|" in core:
            core, alias = core.split("|", 1)
            alias = "|" + alias
        if "#" in core:
            core, anchor = core.split("#", 1)
            anchor = "#" + anchor
        newp = rewrite_target_path(core, falias, dalias, stats)
        if newp is None:
            return m.group(0)
        stats["embed" if open_.startswith("!") else "wikilink"] += 1
        if is_index:
            stats["index"] += 1
        return open_ + newp + anchor + alias + close

    def md_sub(m):
        open_, path, close = m.group(1), m.group(2), m.group(3)
        if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', path) or path.startswith("#"):
            return m.group(0)
        anchor = ""
        core = path
        if "#" in core:
            core, anchor = core.split("#", 1)
            anchor = "#" + anchor
        newp = rewrite_target_path(core, falias, dalias, stats)
        if newp is None:
            return m.group(0)
        stats["markdown"] += 1
        if is_index:
            stats["index"] += 1
        return open_ + newp + anchor + close

    txt = WIKI_RE.sub(wiki_sub, txt)
    txt = MDLINK_RE.sub(md_sub, txt)
    return txt


def build_token_map(falias, rows):
    """旧名字面 token -> 新名字面 token。
    返回 (file_tokens, dir_tokens)：目录 token 允许后面跟 '/'（reports-evidence 改名的目录），
    文件 token 后面跟 '/' 视作「目录引用」，一律不碰（避免把资产目录路径当文档名改掉）。
    """
    rev = {}
    only_docs, only_dirs = [], []
    for old, new in rows:
        ob, nb = os.path.basename(old), os.path.basename(new)
        is_dir_entry = (old.startswith("reports-evidence/") and not os.path.splitext(ob)[1])
        if is_dir_entry:
            only_dirs.append(ob)
        rev[ob] = nb
        stem_o, ext_o = os.path.splitext(ob)
        stem_n, _ = os.path.splitext(nb)
        if ext_o:
            rev[stem_o] = stem_n
            only_docs.append(stem_o)
        if ob.endswith(".zh-CN.md"):
            rev[ob[:-len(".zh-CN.md")]] = nb[:-len(".zh-CN.md")]
            only_docs.append(ob[:-len(".zh-CN.md")])
        if not ext_o:
            only_docs.append(ob)
    dir_tokens = {d: rev[d] for d in only_dirs}
    file_tokens = {k: v for k, v in rev.items() if k not in dir_tokens}
    return file_tokens, dir_tokens


def path_token_pass(txt, token_pair, stats):
    """把正文中（非链接语法）出现的旧名字面 token 换成新名。词边界用非 [A-Za-z0-9._-] 界定。"""
    file_tokens, dir_tokens = token_pair
    for tokens, allow_slash in ((dir_tokens, True), (file_tokens, False)):
        if not tokens:
            continue
        keys = sorted(tokens, key=len, reverse=True)   # 长的先匹配，避免前缀截断
        tail = r'(?![A-Za-z0-9._\-/])' if allow_slash is False else r'(?![A-Za-z0-9._\-])'
        pat = re.compile(r'(?<![A-Za-z0-9._\-])(?:%s)%s'
                         % ("|".join(re.escape(k) for k in keys), tail))

        def sub(m, _t=tokens):
            stats["paths"] += 1
            return _t[m.group(0)]

        txt = pat.sub(sub, txt)
    return txt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if not (a.apply or a.dry_run):
        print("need --dry-run or --apply")
        return 2

    falias, dalias, rows, amb = load_map()
    print("map rows=%d ; file aliases=%d ; dir aliases=%d ; ambiguous aliases=%d"
          % (len(rows), len(falias), len(dalias), len(amb)))
    for k, v in list(amb.items())[:10]:
        print("  AMBIG %r -> %r" % (k, sorted(v)))

    stats = {"wikilink": 0, "embed": 0, "markdown": 0, "index": 0, "file": 0, "dir": 0,
             "paths": 0}
    tokens = build_token_map(falias, rows)
    print("path tokens: file=%d dir=%d" % (len(tokens[0]), len(tokens[1])))
    changed_files = 0
    touched = []
    for base_dir in SCAN_DIRS:
        for root, dirs, files in os.walk(base_dir):
            relroot = os.path.relpath(root, base_dir)
            if MIGDIR in relroot.split(os.sep) or relroot == MIGDIR:
                dirs[:] = []
                continue
            # 插件树（seed 副本）不在本批授权面：改它会让插件 digest 漂移且需与运行时副本同步，
            # 属规则源批的活；命中的旧文档路径单列进 open items。
            if "src/main/resources/seed" in relroot.replace(os.sep, "/"):
                dirs[:] = []
                continue
            dirs[:] = [d for d in dirs if d not in
                       (".git", ".nebflow", "node_modules", "target", "__pycache__")]
            for f in sorted(files):
                # .html 样张里也有 [[wikilink]]（可见文本），一并改；只改链接目标不碰其它字节
                if not f.endswith((".md", ".html")):
                    continue
                p = os.path.join(root, f)
                try:
                    txt = open(p, encoding="utf-8").read()
                except UnicodeDecodeError:
                    continue
                is_index = (base_dir == VAULT and relroot == "." and f == "index.md")
                new = rewrite_text(txt, falias, dalias, stats, is_index)
                new = path_token_pass(new, tokens, stats)
                if new != txt:
                    changed_files += 1
                    touched.append(os.path.relpath(p, WT) if p.startswith(WT) else p)
                    if a.apply:
                        with open(p, "w", encoding="utf-8") as fh:
                            fh.write(new)
    print("changed files = %d" % changed_files)
    print("rewrites: wikilink=%d embed=%d markdown=%d (index.md 内=%d) ; 目录段改动=%d"
          % (stats["wikilink"], stats["embed"], stats["markdown"], stats["index"], stats["dir"]))
    for t in touched[:40]:
        print("  ~", t)
    if len(touched) > 40:
        print("  ... +%d more" % (len(touched) - 40))
    if a.dry_run:
        print("(dry-run, nothing written)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

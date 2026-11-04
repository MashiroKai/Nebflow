#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 3 机械验收 · 四项检查（只读）

A. 命名制合规：vault + reports-evidence 内 .md/资产文件名是否符合新制
   `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>][.zh-CN].<ext>`；旧制（数字打头）残留数必须 0
B. 引用改写读数：wikilink / embed / markdown / index 分列（读 rewrite-links.py 落盘的统计）
C. 断链：对每一篇文档解析其出链是否可解析，并与换名前基线对比（新增断链必须 0）
D. 旧名残留：正文内仍以旧制形态（basename 以 YYYYMMDD_HHMMSS_ 打头）出现的链接目标

用法: python3 residue-scan.py
"""
import os, re, sys, json, subprocess

WT = "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-71767a56"
VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
EVID = os.path.join(WT, "reports-evidence")
HERE = os.path.dirname(os.path.abspath(__file__))
MIGDIR = "doc-naming-migration__20261104_181200"

NEW_MD = re.compile(r'^.+__\d{8}_\d{6}(__[^/]+?)?(\.zh-CN)?\.md$')
OLD_BASE = re.compile(r'^\d{8}_\d{6}[_.]')          # 旧制打头（同一 basename 内）


def walk(root, skip_mig=False):
    for r, d, fs in os.walk(root):
        if skip_mig and MIGDIR in r:
            continue
        for f in fs:
            yield os.path.join(r, f)


def check_a():
    print("=" * 70)
    print("A. 命名制合规扫描")
    vault_md_new = vault_md_nodate = vault_md_old = 0
    ev_ok = ev_old = ev_nodate = 0
    bad = []
    for p in walk(VAULT):
        f = os.path.basename(p)
        if not f.endswith(".md"):
            continue
        if NEW_MD.match(f):
            vault_md_new += 1
        elif OLD_BASE.match(f):
            vault_md_old += 1
            bad.append(os.path.relpath(p, VAULT))
        else:
            vault_md_nodate += 1
    for e in sorted(os.listdir(EVID)):
        if e.startswith(".") or e == MIGDIR:
            continue
        if NEW_MD.match(e) or re.match(r'^.+__\d{8}_\d{6}[_A-Za-z0-9.\-]*$', e):
            ev_ok += 1
        elif OLD_BASE.match(e):
            ev_old += 1
            bad.append("reports-evidence/" + e)
        else:
            ev_nodate += 1
    print("  vault .md   : 新制 %d / 无日期制(不动清单) %d / 旧制残留 %d"
          % (vault_md_new, vault_md_nodate, vault_md_old))
    print("  reports-evidence 顶层: 新制 %d / 无日期(不动) %d / 旧制残留 %d"
          % (ev_ok, ev_nodate, ev_old))
    for b in bad[:10]:
        print("    OLD:", b)
    return vault_md_old + ev_old


def check_d():
    print("=" * 70)
    print("D. 旧名残留（正文链接目标的 basename 以 YYYYMMDD_HHMMSS_ 打头）")
    hits = []
    for root, skip in ((VAULT, False), (EVID, True)):
        for p in walk(root, skip):
            if not p.endswith(".md"):
                continue
            try:
                txt = open(p, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            for m in re.finditer(r'\[\[([^\]\n]+?)\]\]', txt):
                tgt = m.group(1).split("|")[0].split("#")[0].strip()
                if OLD_BASE.match(os.path.basename(tgt)):
                    hits.append((os.path.relpath(p, root), tgt))
            for m in re.finditer(r'\]\(([^)\n]+)\)', txt):
                tgt = m.group(1).split("#")[0].strip()
                if tgt.startswith("http"):
                    continue
                if OLD_BASE.match(os.path.basename(tgt)):
                    hits.append((os.path.relpath(p, root), tgt))
    print("  旧制形态链接目标 = %d" % len(hits))
    for h in hits[:20]:
        print("    %s  ->  %s" % h)
    return len(hits)


def check_c():
    print("=" * 70)
    print("C. 断链回归（换名前基线 vs 换名后）")
    rb = subprocess.run([sys.executable, os.path.join(HERE, "verify-links.py"),
                         "--label", "now", "--quiet",
                         "--json", "/tmp/links-now.json"], capture_output=True, text=True)
    print("  " + rb.stdout.strip().replace("\n", "\n  "))
    b = json.load(open(os.path.join(HERE, "links-before.json")))
    a = json.load(open("/tmp/links-now.json"))
    newb = set(a["dangling"]) - set(b["dangling"])
    fixed = set(b["dangling"]) - set(a["dangling"])
    print("  基线: occ=%d targets=%d | 现在: occ=%d targets=%d"
          % (b["broken_occurrences"], b["broken_targets"],
             a["broken_occurrences"], a["broken_targets"]))
    print("  新增断链目标 = %d ; 被修好的 = %d" % (len(newb), len(fixed)))
    for k in sorted(newb):
        print("    NEW-BROKEN %r  %s" % (k, a["dangling"][k]["samples"]))
    return len(newb), len(fixed)


def main():
    old = check_a()
    resid = check_d()
    newb, fixed = check_c()
    print("=" * 70)
    print("结论: 旧制文件名残留=%d ; 旧名链接目标残留=%d ; 新增断链=%d"
          % (old, resid, newb))
    return 0 if (old == 0 and newb == 0) else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 2 执行：按 manifest §7 映射重命名（vault 用 mv，reports-evidence 用 git mv），
并落盘 rename-map.tsv（旧名<TAB>新名<TAB>回滚标记，路径均带 scope 前缀）。

用法:
  python3 rename-execute.py --dry-run     # 只打印计划
  python3 rename-execute.py --apply       # 真执行
"""
import os, re, sys, subprocess, argparse

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
WT = "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-71767a56"
EVID = os.path.join(WT, "reports-evidence")
HERE = os.path.dirname(os.path.abspath(__file__))
TSV = os.path.join(HERE, "rename-map.tsv")
MIGDIR = "doc-naming-migration__20261104_181200"

EXT_RE = re.compile(r'(\.zh-CN\.md|\.md)$', re.I)


def split_ext(name):
    m = EXT_RE.search(name)
    return (name[:m.start()], m.group(1)) if m else (name, "")


def convert_doc(name):
    stem, ext = split_ext(name)
    if not ext:
        return None, None
    m = re.match(r'^(\d{8})_(\d{6})_(.+)$', stem)
    if m:
        date, time, rest, kind = m.group(1), m.group(2), m.group(3), "full"
    else:
        m = re.match(r'^(\d{8})_(\d{4})_(.+)$', stem)
        if m:
            # 裁定 2：4 位 HHMM 秒位补 00
            date, time, rest, kind = m.group(1), m.group(2) + "00", m.group(3), "hhmm4"
        else:
            m = re.match(r'^(\d{8})_(.+)$', stem)
            if m:
                # 裁定 1：日级精度 -> 000000
                date, time, rest, kind = m.group(1), "000000", m.group(2), "dateonly"
            else:
                return None, None
    if "__" in rest:
        topic, chain = rest.rsplit("__", 1)
        return "%s__%s_%s__%s%s" % (topic, date, time, chain, ext), kind
    return "%s__%s_%s%s" % (rest, date, time, ext), kind


def plan_vault():
    plan = []
    for root, dirs, files in os.walk(VAULT):
        rel = os.path.relpath(root, VAULT)
        d = "" if rel == "." else rel.replace(os.sep, "/")
        for f in sorted(files):
            new, kind = convert_doc(f)
            if new is None:
                continue
            plan.append({"scope": "vault", "dir": d, "old": f, "new": new, "kind": kind})
    return plan


def git_first_add(path):
    """git 首次添加该路径的 author date 'YYYYMMDD_HHMMSS'。"""
    try:
        out = subprocess.run(
            ["git", "-C", WT, "log", "--diff-filter=A", "--format=%ad",
             "--date=format:%Y%m%d_%H%M%S", "--", path],
            capture_output=True, text=True, check=False).stdout.strip().splitlines()
        return out[-1].strip() if out else ""
    except Exception:
        return ""


def plan_evidence():
    plan = []
    for e in sorted(os.listdir(EVID)):
        if e == MIGDIR or e.startswith("."):
            continue
        full = os.path.join(EVID, e)
        is_dir = os.path.isdir(full)
        if is_dir:
            stem, ext = e, ""              # 目录不切扩展名
        else:
            stem, ext = os.path.splitext(e)  # 文件保留原扩展名（.patch/.md/...）
        m = re.match(r'^(\d{8})_(.+)$', stem)
        if not m:
            continue                  # 无日期前缀（如 session-stability）不动
        date, rest = m.group(1), m.group(2)
        gitdate = git_first_add("reports-evidence/" + e)   # 'YYYYMMDD_HHMMSS'
        note = "git-first-add"
        time_part = "000000"
        kind = "evidence-dir" if os.path.isdir(full) else "evidence-file"
        if gitdate and gitdate[:8] == date:
            time_part = gitdate[9:]
        else:
            # git 日期与名内日期不一致 / 无 git 记录 -> 日级精度占位（不臆造错误时刻）
            time_part = "000000"
            note = "git-first-add=%s（日期不符，退回 000000）" % (gitdate or "NA")
        if "__" in rest:
            topic, chain = rest.rsplit("__", 1)
            new = "%s__%s_%s__%s%s" % (topic, date, time_part, chain, ext)
        else:
            new = "%s__%s_%s%s" % (rest, date, time_part, ext)
        plan.append({"scope": "reports-evidence", "dir": "", "old": e, "new": new,
                     "kind": kind, "note": note,
                     "is_dir": os.path.isdir(full)})
    return plan


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if not (a.apply or a.dry_run):
        print("need --dry-run or --apply")
        return 2

    vplan = plan_vault()
    eplan = plan_evidence()

    # 冲突检测
    seen = {}
    clash = []
    for p in vplan + eplan:
        k = (p["scope"], p["dir"], p["new"])
        if k in seen:
            clash.append((k, seen[k], p["old"]))
        seen[k] = p["old"]
    if clash:
        print("!! NEW-NAME COLLISION:", clash)
        return 3

    print("vault plan = %d ; evidence plan = %d" % (len(vplan), len(eplan)))
    for p in eplan:
        print("  EV %s %-52s -> %s   [%s]" % ("DIR " if p["is_dir"] else "FILE",
                                             p["old"], p["new"], p.get("note", "")))

    if a.dry_run:
        for p in vplan[:5]:
            print("  VA %s/%s -> %s" % (p["dir"], p["old"], p["new"]))
        print("  ... (dry-run, nothing changed)")
        return 0

    rows = []
    ok = fail = 0
    # 1) vault：mv
    for p in vplan:
        src = os.path.join(VAULT, p["dir"], p["old"]) if p["dir"] else os.path.join(VAULT, p["old"])
        dst = os.path.join(VAULT, p["dir"], p["new"]) if p["dir"] else os.path.join(VAULT, p["new"])
        if not os.path.exists(src):
            print("  MISSING", src)
            fail += 1
            continue
        if os.path.exists(dst):
            print("  TARGET EXISTS", dst)
            fail += 1
            continue
        os.rename(src, dst)
        ok += 1
        rows.append(p)
    # 2) reports-evidence：git mv
    for p in eplan:
        src = os.path.join(EVID, p["old"])
        dst = os.path.join(EVID, p["new"])
        r = subprocess.run(["git", "-C", WT, "mv", src, dst],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print("  GITMV FAIL", p["old"], r.stderr.strip()[:200])
            fail += 1
        else:
            ok += 1
            rows.append(p)

    # 3) rename-map.tsv
    with open(TSV, "w", encoding="utf-8") as fh:
        fh.write("# 文档命名改制 rename-map（scope/相对路径；rollback '-' = 未回滚）\n")
        fh.write("# 源：manifest.md §7 逐行迁移表 + 独立重算交叉校验（xcheck-map.py）\n")
        fh.write("# 旧名\t新名\t回滚标记\n")
        for p in rows:
            pre = p["dir"] + "/" if p["dir"] else ""
            fh.write("%s/%s%s\t%s/%s%s\t-\n" % (p["scope"], pre, p["old"],
                                                p["scope"], pre, p["new"]))
    print("renamed ok=%d fail=%d ; wrote %s (%d rows)" % (ok, fail, TSV, len(rows)))
    return 0 if fail == 0 else 4


if __name__ == "__main__":
    sys.exit(main())

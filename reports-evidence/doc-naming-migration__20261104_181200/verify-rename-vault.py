#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 2a 回归验证（只读）：证明 vault 改名 + 引用重写已完整、幂等、无新增断链。

用法:
  python3 verify-rename-vault.py --pre /tmp/pre-2a/docs [--json verify-vault.json]

判定口径：
  1) 映射表 239 条：旧名 0 存在 / 新名 239 存在
  2) 语言段（.zh-CN）仍在名尾；无新名冲突
  3) 逐篇等价：把 rename map 施加到迁移前快照，结果应与磁盘现状逐字相同
     （唯一允许的差异 = 迁移后又被其他节点改写的文件，单列）
  4) 链接健康：当前悬空集合 ⊆ 迁移前基线集合（新增悬空 = 断链，必须为 0）
  5) 引用改写条数：wikilink / embed / markdown / index.md 内
"""
import os, re, sys, json, argparse, importlib.util

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
HERE = os.path.dirname(os.path.abspath(__file__))
TSV = os.path.join(HERE, "rename-map.tsv")
BEFORE = os.path.join(HERE, "links-before.json")


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def vault_rows():
    rows = []
    with open(TSV, encoding="utf-8") as fh:
        for ln in fh:
            if ln.startswith("#") or not ln.strip():
                continue
            p = ln.rstrip("\n").split("\t")
            if len(p) < 2 or not p[0].startswith("vault/"):
                continue
            rows.append((p[0][len("vault/"):], p[1][len("vault/"):], p[2] if len(p) > 2 else "-"))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pre", default="/tmp/pre-2a/docs")
    ap.add_argument("--json", default=os.path.join(HERE, "verify-vault.json"))
    a = ap.parse_args()

    ev = load_module("rewrite_links", os.path.join(HERE, "rewrite-links.py"))
    vl = load_module("verify_links", os.path.join(HERE, "verify-links.py"))

    rows = vault_rows()
    res = {"map_rows": len(rows)}

    # 1) 存在性
    old_exists = [o for o, n, _ in rows if os.path.exists(os.path.join(VAULT, o))]
    new_missing = [n for o, n, _ in rows if not os.path.exists(os.path.join(VAULT, n))]
    res["old_still_present"] = old_exists
    res["new_missing"] = new_missing

    # 2) 语言段 + 冲突
    lang_bad = [n for o, n, _ in rows
                if o.endswith(".zh-CN.md") and not n.endswith(".zh-CN.md")]
    dupes = {}
    for o, n, _ in rows:
        dupes.setdefault(n, []).append(o)
    res["lang_segment_violations"] = lang_bad
    res["new_name_collisions"] = {k: v for k, v in dupes.items() if len(v) > 1}

    # 3) 逐篇等价：快照 + 映射 == 磁盘
    falias, dalias, _rows, amb = ev.load_map()
    stats = {"wikilink": 0, "embed": 0, "markdown": 0, "index": 0, "file": 0, "dir": 0}
    newpath = {o: n for o, n, _ in rows}
    identical, divergent, absent = [], [], []
    for root, dirs, files in os.walk(a.pre):
        for f in sorted(files):
            if not f.endswith(".md"):
                continue
            oldrel = os.path.relpath(os.path.join(root, f), a.pre).replace(os.sep, "/")
            txt = open(os.path.join(root, f), encoding="utf-8").read()
            cur = os.path.join(VAULT, newpath.get(oldrel, oldrel))
            is_index = (oldrel == "index.md")
            pred = ev.rewrite_text(txt, falias, dalias, stats, is_index)
            if not os.path.exists(cur):
                absent.append(oldrel)
                continue
            if open(cur, encoding="utf-8").read() == pred:
                identical.append(oldrel)
            else:
                divergent.append(oldrel)
    res["rewrite_stats"] = stats
    res["files_identical_to_prediction"] = len(identical)
    res["files_diverged_after_migration"] = divergent
    res["files_missing_on_disk"] = absent

    # 4) 链接健康（当前 vs 迁移前基线）
    all_files, by_base, md_files = vl.scan_files()
    now_broken = {}
    for src in md_files:
        txt = open(os.path.join(VAULT, src), encoding="utf-8", errors="replace").read()
        for m in vl.WIKI_RE.finditer(txt):
            if vl.resolve(m.group(2), src, all_files, by_base) is None:
                now_broken.setdefault(m.group(2).strip(), []).append(src)
        for m in vl.MD_RE.finditer(txt):
            t = m.group(2)
            if t.startswith("http") or t.startswith("mailto:"):
                continue
            if vl.resolve(t, src, all_files, by_base) is None:
                now_broken.setdefault(t.strip(), []).append(src)
    base = json.load(open(BEFORE, encoding="utf-8"))["dangling"] if os.path.exists(BEFORE) else {}
    new_dangling = {k: v for k, v in now_broken.items() if k not in base}
    res["dangling_now"] = {k: {"count": len(v), "samples": v[:3]} for k, v in now_broken.items()}
    res["dangling_new_vs_baseline"] = {k: v[:3] for k, v in new_dangling.items()}
    res["broken_occurrences_now"] = sum(len(v) for v in now_broken.values())
    res["broken_occurrences_baseline"] = sum(
        v["count"] for v in base.values()) if base else None

    # 5) 时间分量异常清单（与裁定 1/2 对账）
    dateonly = [o for o, n, _ in rows
                if os.path.basename(o).endswith(".md")
                and re.match(r'^\d{8}_(?!\d{4,6}_)', os.path.basename(o))]
    hhmm4 = [o for o, n, _ in rows if re.match(r'^\d{8}_\d{4}_', os.path.basename(o))]
    res["date_only_placeholder"] = {"count": len(dateonly), "old": dateonly,
                                    "new": [newpath[o] for o in dateonly]}
    res["hhmm4_zero_padded"] = {"count": len(hhmm4), "old": hhmm4,
                                "new": [newpath[o] for o in hhmm4]}

    json.dump(res, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print("map_rows                = %d" % res["map_rows"])
    print("old names still present = %d %s" % (len(old_exists), old_exists[:5]))
    print("new names missing       = %d %s" % (len(new_missing), new_missing[:5]))
    print("lang segment violations = %d ; new-name collisions = %d"
          % (len(lang_bad), len(res["new_name_collisions"])))
    print("rewrites: wikilink=%d embed=%d markdown=%d index.md-in=%d"
          % (stats["wikilink"], stats["embed"], stats["markdown"], stats["index"]))
    print("byte-identical to predicted rewrite = %d ; diverged-after-migration = %d ; missing = %d"
          % (len(identical), len(divergent), len(absent)))
    for d in divergent:
        print("   diverged:", d)
    print("broken occurrences now = %s (baseline %s) ; NEW dangling = %d"
          % (res["broken_occurrences_now"], res["broken_occurrences_baseline"], len(new_dangling)))
    for k, v in new_dangling.items():
        print("   NEW-BROKEN %r in %s" % (k, v[:2]))
    print("date-only placeholders = %d ; 4-digit HHMM zero-padded = %d"
          % (len(dateonly), len(hhmm4)))

    hard_fail = (old_exists or new_missing or lang_bad or res["new_name_collisions"] or absent)
    return 1 if hard_fail else 0


if __name__ == "__main__":
    sys.exit(main())

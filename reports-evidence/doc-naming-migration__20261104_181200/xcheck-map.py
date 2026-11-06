#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立重算旧制→新制文件名映射，并与 manifest.md §7 逐行迁移表交叉校验。
只读：本脚本不重命名、不改文件。
用法：python3 xcheck-map.py [--write-json]
"""
import os, re, sys, json

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
MANIFEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "manifest.md")

EXT_RE = re.compile(r'(\.zh-CN\.md|\.md)$', re.I)


def split_ext(name):
    m = EXT_RE.search(name)
    if not m:
        return name, ""
    return name[:m.start()], m.group(1)


def convert(name):
    """旧名 -> (新名, 分类) ；非旧制返回 (None, None)."""
    stem, ext = split_ext(name)
    if not ext:
        return None, None
    m = re.match(r'^(\d{8})_(\d{6})_(.+)$', stem)
    if m:
        date, time, rest = m.group(1), m.group(2), m.group(3)
        kind = "full"
    else:
        m = re.match(r'^(\d{8})_(\d{4})_(.+)$', stem)
        if m:
            date, time, rest = m.group(1), m.group(2) + "00", m.group(3)
            kind = "hhmm4"
        else:
            m = re.match(r'^(\d{8})_(.+)$', stem)
            if m:
                date, time, rest = m.group(1), "000000", m.group(2)
                kind = "dateonly"
            else:
                return None, None
    if "__" in rest:
        topic, chain = rest.rsplit("__", 1)
        new = "%s__%s_%s__%s%s" % (topic, date, time, chain, ext)
    else:
        topic, chain = rest, None
        new = "%s__%s_%s%s" % (topic, date, time, ext)
    return new, kind


def scan_vault():
    found = {}
    for root, dirs, files in os.walk(VAULT):
        rel = os.path.relpath(root, VAULT)
        for f in files:
            new, kind = convert(f)
            if new is None:
                continue
            d = "" if rel == "." else rel
            found[(d, f)] = (new, kind)
    return found


def parse_manifest_s7():
    rows = []
    with open(MANIFEST, encoding="utf-8") as fh:
        lines = fh.read().split("\n")
    in_s7 = False
    for ln in lines:
        if ln.startswith("## 7."):
            in_s7 = True
            continue
        if in_s7 and ln.startswith("## 8."):
            break
        if not in_s7 or not ln.startswith("|"):
            continue
        parts = [p.strip() for p in ln.split("|")]
        if len(parts) < 6:
            continue
        if not re.match(r'^\d+$', parts[1]):
            continue
        d = parts[2].strip("`")
        old = parts[3].strip("`")
        new = parts[4].strip("`")
        d = "" if d == "（根）" else d
        rows.append({"n": int(parts[1]), "dir": d, "old": old, "new": new})
    return rows


def main():
    rows = parse_manifest_s7()
    print("S7 rows =", len(rows))
    vault = scan_vault()
    print("vault old-scheme found =", len(vault))

    news = {}
    dup = []
    for k, (new, kind) in vault.items():
        key = (k[0], new)
        if key in news:
            dup.append((key, news[key], k[1]))
        news[key] = k[1]
    print("new-name collisions =", len(dup))
    for d in dup:
        print("  DUP", d)

    mset = {(r["dir"], r["old"]): r for r in rows}
    missing_in_manifest = sorted(k for k in vault if k not in mset)
    missing_on_disk = sorted(k for k in mset if k not in vault)
    print("on disk but not in S7 =", len(missing_in_manifest))
    for k in missing_in_manifest:
        print("  +", k[0], k[1], "->", vault[k][0])
    print("in S7 but not on disk =", len(missing_on_disk))
    for k in missing_on_disk:
        print("  -", k[0], k[1])

    diffs = []
    for k, (new, kind) in vault.items():
        r = mset.get(k)
        if r and r["new"] != new:
            diffs.append((k, r["new"], new, kind))
    print("name mismatches =", len(diffs))
    for d in diffs:
        print("  DIFF", d[0], "\n     manifest:", d[1], "\n     recompute:", d[2], "kind=", d[3])

    from collections import Counter
    print("kinds:", dict(Counter(v[1] for v in vault.values())))

    if "--write-json" in sys.argv:
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "xcheck-vault-scan.json")
        with open(out, "w", encoding="utf-8") as fh:
            json.dump({"%s|%s" % k: v for k, v in vault.items()}, fh, ensure_ascii=False, indent=1)
        print("wrote", out)

    ok = (len(diffs) == 0 and len(dup) == 0 and len(missing_in_manifest) == 0
          and len(missing_on_disk) == 0 and len(rows) == 239)
    print("XCHECK:", "OK" if ok else "MISMATCH")
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())

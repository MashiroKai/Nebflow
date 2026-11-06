#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 2a（vault 专用）：按 manifest §7 映射重命名 stand vault 文档，幂等、可回滚。

与 rename-execute.py 的区别：本脚本**只动 vault**（`~/.stand/projects/stand/.stand/docs`），
不碰 reports-evidence / 其他项目 docs / attachments 资产（裁定 4）。映射源是同目录
rename-map.tsv 的 `vault/` 行（manifest §7 = 239 行），脚本不自行发明新名。

用法:
  python3 rename-vault.py --check             # 只读：对账 239 条（旧名应全无、新名应全在）
  python3 rename-vault.py --apply             # 执行剩余 mv（当下应为 no-op）
  python3 rename-vault.py --emit-map          # 落盘 rename-map-vault.tsv（旧名/新名/回滚标记）
  python3 rename-vault.py --rollback <新名>   # 单篇改回旧名并标记回滚
  python3 rename-vault.py --rollback-all      # 全批回滚（谨慎）
退出码 0 = 与映射一致；1 = 存在不一致。
"""
import os, sys, argparse

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "rename-map.tsv")
OUT = os.path.join(HERE, "rename-map-vault.tsv")

HDR = ("# 阶段 2a vault rename-map（vault 相对路径；rollback '-' = 未回滚）\n"
       "# 源：manifest.md §7 逐行迁移表 239 行（scope=vault）；只含 .md\n"
       "# 旧名\t新名\t回滚标记\n")


def rows():
    out = []
    with open(SRC, encoding="utf-8") as fh:
        for ln in fh:
            if ln.startswith("#") or not ln.strip():
                continue
            p = ln.rstrip("\n").split("\t")
            if len(p) < 2 or not p[0].startswith("vault/"):
                continue
            out.append([p[0][len("vault/"):], p[1][len("vault/"):],
                        p[2] if len(p) > 2 else "-"])
    return out


def write_map(rs):
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(HDR)
        for old, new, rb in rs:
            fh.write("%s\t%s\t%s\n" % (old, new, rb))
    print("wrote %s (%d rows)" % (OUT, len(rs)))


def report(rs):
    old_left = [o for o, n, _ in rs if os.path.exists(os.path.join(VAULT, o))]
    new_missing = [n for o, n, _ in rs if not os.path.exists(os.path.join(VAULT, n))]
    rb = [(o, n, r) for o, n, r in rs if r != "-"]
    print("rows=%d ; old-still-present=%d ; new-missing=%d ; rolled-back=%d"
          % (len(rs), len(old_left), len(new_missing), len(rb)))
    for x in old_left[:10]:
        print("   OLD-STILL-PRESENT", x)
    for x in new_missing[:10]:
        print("   NEW-MISSING", x)
    return 0 if not (old_left or new_missing) else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--emit-map", action="store_true")
    ap.add_argument("--rollback", default="")
    ap.add_argument("--rollback-all", action="store_true")
    a = ap.parse_args()
    rs = rows()

    if a.emit_map:
        write_map(rs)

    if a.apply:
        done = 0
        for r in rs:
            old, new = r[0], r[1]
            s, d = os.path.join(VAULT, old), os.path.join(VAULT, new)
            if os.path.exists(s) and not os.path.exists(d):
                os.rename(s, d)
                done += 1
            elif os.path.exists(d) and not os.path.exists(s):
                pass                      # 已改名，幂等跳过
            else:
                print("   SKIP ambiguous old=%s new=%s (%s/%s)"
                      % (r[0], r[1], os.path.exists(s), os.path.exists(d)))
        print("applied=%d (no-op means already migrated)" % done)
        write_map(rs)
        return report(rs)

    targets = []
    if a.rollback_all:
        targets = [r for r in rs if r[2] == "-"]
    elif a.rollback:
        targets = [r for r in rs
                   if r[1] == a.rollback or os.path.basename(r[1]) == a.rollback]
        if not targets:
            print("!! no map row for %r" % a.rollback)
            return 2
    if targets:
        for r in targets:
            s, d = os.path.join(VAULT, r[1]), os.path.join(VAULT, r[0])
            if os.path.exists(s) and not os.path.exists(d):
                os.rename(s, d)
                r[2] = "rolled-back"
                print("   ROLLEDBACK %s -> %s" % (r[1], r[0]))
        write_map(rs)
        return report(rs)

    return report(rs)


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""旧制引用重写（文档命名迁移收口批 · 一次性脚本）

做一件事：vault 内**指向已改名对象**的旧制文件名替换成新名。
严格保守，三类不动：
  1. 冻结证据表 —— `docs-migration-sha256-table*` / `docs-landing-ledger-migration*`
     （两侧同路径对照的迁移账本，源侧 `docs/` 未改名，重写会破坏逐行可复核性）
  2. 「沿革行」—— 行内含 `原落库名` / `原名` 的，那是记载改名本身的句子，重写即抹掉沿革
  3. 解析不到映射表的旧名 —— 跨树引用（repo `docs/`、`reports/`）、截断省略写法（`…`）

用法：python3 rewrite-old-refs.py [--apply]
不带 --apply 只打印计划（dry-run）。
产物：rewrite-log.tsv（逐条：文件 / 行 / 旧名 / 新名）
"""
import os, re, sys, csv

VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
MAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rename-map.tsv")
EVMAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rename-map-evidence.tsv")
FROZEN = ('docs-migration-sha256-table', 'docs-landing-ledger-migration')
KEEP_LINE = ('原落库名', '原名')

TOK = re.compile(r'(?<![\w\-])20\d{6}_\d{6}_[^\s\)\]\}\|"\'`,;，。、；：<>]+')
SUFFIX = re.compile(r'(:[\d,\-]+)?$')


def load_map():
    m = {}
    for line in open(MAP, encoding='utf-8'):
        if not line.startswith('vault/'):
            continue
        o, n, _ = line.rstrip('\n').split('\t')
        m[os.path.basename(o[len('vault/'):])] = os.path.basename(n[len('vault/'):])
    for line in open(EVMAP, encoding='utf-8'):
        if not line.startswith('reports-evidence/'):
            continue
        o, n = line.rstrip('\n').split('\t')[:2]
        m[os.path.basename(o)] = os.path.basename(n)
    return m


def strip_suffix(tok):
    """分离 :105 / :82-86 这类行号尾，返回 (核心名, 尾)"""
    base = tok.rstrip('.')
    m = SUFFIX.search(base)
    if m and m.group(1):
        return base[:m.start()], m.group(1) + tok[len(base):]
    return base, tok[len(base):]


def main():
    apply_ = '--apply' in sys.argv
    m = load_map()
    log = []
    for root, ds, fs in os.walk(VAULT):
        ds[:] = [d for d in ds if not d.startswith('.') and d != '__pycache__']
        for f in sorted(fs):
            if not f.endswith(('.md', '.html', '.htm')):
                continue
            if f.startswith(FROZEN):
                continue
            p = os.path.join(root, f)
            rel = os.path.relpath(p, VAULT)
            text = open(p, encoding='utf-8', errors='ignore').read()
            out_lines, changed = [], 0
            for ln, line in enumerate(text.split('\n'), 1):
                if any(k in line for k in KEEP_LINE):
                    out_lines.append(line)
                    continue

                def sub(mo):
                    nonlocal changed
                    tok = mo.group(0)
                    core, tail = strip_suffix(tok)
                    base = os.path.basename(core)
                    new = m.get(base) or m.get(base + '.md') or m.get(base + '.zh-CN.md')
                    if not new:
                        return tok
                    head = core[:len(core) - len(base)]
                    changed += 1
                    log.append((rel, ln, tok, head + new + tail))
                    return head + new + tail

                out_lines.append(TOK.sub(sub, line))
            if changed:
                if apply_:
                    open(p, 'w', encoding='utf-8').write('\n'.join(out_lines))
                print(f"{'APPLIED' if apply_ else 'DRYRUN '} {changed:3d}  {rel}")
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'rewrite-log.tsv')
    if apply_:
        with open(out, 'w', newline='', encoding='utf-8') as fh:
            w = csv.writer(fh, delimiter='\t')
            w.writerow(['file', 'line', 'old', 'new'])
            w.writerows(log)
    print(f"\ntotal replacements: {len(log)}   files: {len({r[0] for r in log})}")
    print("干跑（未 --apply）" if not apply_ else f"写入 {out}")


if __name__ == '__main__':
    main()

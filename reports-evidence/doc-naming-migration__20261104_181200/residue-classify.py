#!/usr/bin/env python3
"""旧制引用残留逐条分类（文档命名迁移收口批 · 一次性脚本）

扫描 vault 全部 .md/.html，抽出所有 `20YYMMDD_HHMMSS_*` 形态的旧制 token，
逐条判定「是否仍指向一个已改名的对象」，并给出保留判据，输出 TSV。

分类（判据写死在脚本里，可复跑复核）：
  rewrite-done  已在本批改写（对照 rewrite-log.tsv 计数，不再作为残留）
  keep-frozen   冻结迁移账本：`docs-migration-sha256-table*` / `docs-landing-ledger-migration*`
                —— 迁移时刻的逐文件 sha256 对照（源侧 docs/ 未改名），重写会破坏逐行可复核性
  keep-history  「原落库名 / 原名」沿革行：那句话本身就在记载改名，重写即抹掉沿革
  keep-mapping-pair 旧名与新名同现一行（旧→新对照表行）：旧名是样本，不是待解析引用
  keep-ellipsis 截断省略写法（含 `…`）：不是可解析路径
  keep-crosstree 跨树引用：指向 repo `docs/` 或 `reports/` 过程件树、或本 vault 从未存在过的文档名
                （不在本批改名范围，无映射可换；硬改 = 编造文件名）
  VIOLATION    其余（仍可解析到已改名对象却仍是旧名）← 必须 0
"""
import os, re, csv, sys

HERE = os.path.dirname(os.path.abspath(__file__))
VAULT = "/Users/kaiyu/.stand/projects/stand/.stand/docs"
TOK = re.compile(r'(?<![\w\-])20\d{6}_\d{6}_[^\s\)\]\}\|"\'`,;，。、；：<>]+')
FROZEN = ('docs-migration-sha256-table', 'docs-landing-ledger-migration')


def load_map():
    m = {}
    for fn in ('rename-map.tsv', 'rename-map-evidence.tsv'):
        for line in open(os.path.join(HERE, fn), encoding='utf-8'):
            if line.startswith('vault/'):
                o, n, _ = line.rstrip('\n').split('\t')
                m[os.path.basename(o[6:])] = os.path.basename(n[6:])
            elif line.startswith('reports-evidence/'):
                o, n = line.rstrip('\n').split('\t')[:2]
                m[os.path.basename(o)] = os.path.basename(n)
    return m


def main():
    m = load_map()
    rows, cats = [], {}
    files = set()
    for root, ds, fs in os.walk(VAULT):
        ds[:] = [d for d in ds if not d.startswith('.') and d != '__pycache__']
        for f in sorted(fs):
            if not f.endswith(('.md', '.html', '.htm')):
                continue
            rel = os.path.relpath(os.path.join(root, f), VAULT)
            text = open(os.path.join(root, f), encoding='utf-8', errors='ignore').read()
            for ln, line in enumerate(text.split('\n'), 1):
                for mo in TOK.finditer(line):
                    tok = mo.group(0)
                    base = os.path.basename(tok.rstrip('.'))
                    resolvable = base in m or (base + '.md') in m
                    if f.startswith(FROZEN):
                        cat = 'keep-frozen'
                    elif '原落库名' in line or '原名' in line:
                        cat = 'keep-history'
                    elif '…' in tok:
                        cat = 'keep-ellipsis'
                    elif resolvable and (m.get(base) or m.get(base + '.md')) in line:
                        # 旧名与新名同现一行 = 旧→新对照表行，旧名是样本不是引用
                        cat = 'keep-mapping-pair'
                    elif resolvable:
                        cat = 'VIOLATION'
                    else:
                        cat = 'keep-crosstree'
                    cats[cat] = cats.get(cat, 0) + 1
                    files.add(rel)
                    rows.append((rel, ln, tok, cat, line.strip()[:220]))
    out = os.path.join(HERE, 'residue-classification.tsv')
    with open(out, 'w', newline='', encoding='utf-8') as fh:
        w = csv.writer(fh, delimiter='\t')
        w.writerow(['file', 'line', 'old_token', 'category', 'line_text'])
        w.writerows(rows)
    print(f"residual occurrences : {len(rows)}   files: {len(files)}")
    for k in sorted(cats):
        print(f"  {k:16s} {cats[k]}")
    print(f"tsv -> {out}")
    return 0 if cats.get('VIOLATION', 0) == 0 else 1


if __name__ == '__main__':
    sys.exit(main())

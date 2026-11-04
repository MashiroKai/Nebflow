#!/usr/bin/env python3
"""vault 断链扫描（文档命名迁移收口批 · 一次性脚本）

口径（为什么这样切）
--------------------
本 vault 的 `[[...]]` 同时承载三种语义，混在一起算「断链」会得到一堆假阳：
  1. 文档/附件引用 —— 目标带扩展名、带路径、或日期打头的旧制名  → 解析失败 = 真断链
  2. 术语引用（term citation）—— 裸 `[[DCL]]` / `[[概念名]]`，由词表 R4 校验，不是文件 → 不判断链
  3. 语法示例 —— 写在代码围栏或行内反引号里的 `[[目标]]` → 不参与解析（Obsidian 同此行为）

因此本脚本：
  * 先剥掉代码围栏（``` / ~~~）与行内代码 span，再抽取链接
  * 解析 `[[target]]` / `[[target|alias]]` / `[[target#anchor]]` / `![[embed]]` 与 `[text](path)`
  * 目标解析：vault 根相对、当前文件目录相对、basename 兜底（Obsidian 亦按 basename 解析）；`.md` / `.zh-CN` 可省略
  * 分桶：
      A. BROKEN  —— 目标带扩展名 / 带路径 / 日期打头（即必然是文件引用）且解析失败  ← 必须 0
      B. SUSPECT —— 裸目标（无扩展名、无路径、非日期打头）：可能是术语引用，也可能是尚未落库的文档名，只计数不判错
      C. 孤儿    —— 全 vault 零入链的 .md（不算错，只给数）

用法：python3 link-scan.py [--json out.json]
退出码：A 桶为 0 → 0，否则 1
"""
import os, re, sys, json, collections

VAULT = os.environ.get('VAULT_DIR', "/Users/kaiyu/.stand/projects/stand/.stand/docs")
WIKI = re.compile(r'!?\[\[([^\]\n]+?)\]\]')
MDLINK = re.compile(r'(?<!\\)\[([^\]\n]*)\]\(([^)\n]+)\)')
FENCE = re.compile(r'^\s*(```|~~~).*?^\s*\1', re.S | re.M)
INLINE_CODE = re.compile(r'`[^`\n]+`')
SKIP_DIRS = {'__pycache__', 'node_modules'}
DATE_FIRST = re.compile(r'^20\d{6}[_\-]')
DATE_ANY = re.compile(r'^(?:\S*__)?20\d{6}_\d{6}')


def collect():
    files = []
    for root, ds, fs in os.walk(VAULT):
        ds[:] = [d for d in ds if d not in SKIP_DIRS and not d.startswith('.')]
        for f in fs:
            files.append(os.path.relpath(os.path.join(root, f), VAULT))
    return sorted(files)


def strip_code(text):
    text = FENCE.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    return INLINE_CODE.sub(lambda m: " " * len(m.group(0)), text)


def targets_of(rel, text):
    for i, line in enumerate(strip_code(text).split("\n"), 1):
        for m in WIKI.finditer(line):
            t = m.group(1).split('|')[0].split('#')[0].strip()
            if t:
                yield ('wiki', t, i)
        for m in MDLINK.finditer(line):
            t = m.group(2).strip()
            if not t or t.startswith(('http://', 'https://', 'mailto:', '#', 'tel:')):
                continue
            t = t.split('#')[0].split('?')[0].strip()
            if t:
                yield ('md', t, i)


def resolve(rel, target, all_files):
    cands = [target]
    if not os.path.splitext(target)[1]:
        cands += [target + '.md', target + '.zh-CN.md']
    if target.endswith('.zh-CN'):
        cands += [target + '.md']
    for c in cands:
        for bd in ('', os.path.dirname(rel)):
            p = os.path.normpath(os.path.join(bd, c))
            if os.path.exists(os.path.join(VAULT, p)):
                return p
    want = {os.path.basename(c) for c in cands}
    for f in all_files:
        if os.path.basename(f) in want:
            return f
    return None


def is_file_ref(t):
    """是否必然是文件引用（而非术语 / 语法示例）"""
    if os.path.splitext(t)[1] in ('.md', '.html', '.htm', '.png', '.jpg', '.jpeg', '.svg', '.webp', '.pdf', '.patch', '.log', '.json', '.txt', '.gz', '.csv'):
        return True
    if '/' in t:
        return True
    if DATE_FIRST.match(t) or DATE_ANY.match(t):
        return True
    return False


def main():
    all_files = collect()
    broken, suspect = [], []
    inbound = collections.Counter()
    total = 0
    for rel in all_files:
        if not rel.endswith(('.md', '.html', '.htm')):
            continue
        try:
            text = open(os.path.join(VAULT, rel), encoding='utf-8', errors='ignore').read()
        except OSError:
            continue
        for kind, t, ln in targets_of(rel, text):
            total += 1
            hit = resolve(rel, t, all_files)
            if hit:
                inbound[hit] += 1
            elif is_file_ref(t):
                broken.append({'file': rel, 'line': ln, 'kind': kind, 'target': t})
            else:
                suspect.append({'file': rel, 'line': ln, 'kind': kind, 'target': t})
    docs = [f for f in all_files if f.endswith('.md')]
    orphans = sorted(d for d in docs if inbound.get(d, 0) == 0)
    res = {'vault': VAULT, 'files_total': len(all_files), 'md_total': len(docs),
           'links_total': total, 'broken': broken, 'broken_count': len(broken),
           'suspect': suspect, 'suspect_count': len(suspect),
           'orphans': orphans, 'orphan_count': len(orphans)}
    if '--json' in sys.argv:
        json.dump(res, open(sys.argv[sys.argv.index('--json') + 1], 'w'), ensure_ascii=False, indent=1)
    print(f"vault        : {VAULT}")
    print(f"files        : {len(all_files)}  (md {len(docs)})")
    print(f"links parsed : {total}")
    print(f"A. BROKEN    : {len(broken)}   (文件引用解析失败 —— 必须 0)")
    for b in broken[:60]:
        print(f"   - {b['file']}:{b['line']} [{b['kind']}] -> {b['target']}")
    if len(broken) > 60:
        print(f"   ... {len(broken)-60} more")
    print(f"B. SUSPECT   : {len(suspect)}  (裸目标：术语引用或未落库文档名，不判错)")
    for s in suspect[:15]:
        print(f"   · {s['file']}:{s['line']} -> {s['target']}")
    print(f"C. ORPHAN    : {len(orphans)}  (.md 零入链，不判错)")
    return 0 if not broken else 1


if __name__ == '__main__':
    sys.exit(main())

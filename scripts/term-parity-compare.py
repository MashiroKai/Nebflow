#!/usr/bin/env python3
"""跨语言词条面对比（term-system v2 · ② 的一致性断言执行体）。

输入两份 JSON：
  · scala  = target/term-parity-scala.json （TermParitySpec 落盘，WikiIndex.termsOf）
  · python = target/term-parity-python.json（check_wiki_docs.py --dump-terms --json）

比较口径（**只取四个字段**）：
    (canon_name, rel, anchor, tags{project,topic,extra})
排序后逐条相等即通过；差异逐条打印，退出码表达结论（0 = 相等，1 = 不等）。

canon_name = 剥掉行首的列表/标题符号（`- ` `* ` `+ ` `# `）后折叠空白——这是
唯一的一处归一，理由见输出里的 RAW_NAME 段：**列表写法是真实 vault 的主流写法**，
而两个实现目前对行首 `- ` 的处理不同（Scala 剥、python 保留）。归一之后两侧仍
必须逐条相等，名字的字面差异单独列出，不藏起来。
"""

import json
import re
import sys

BUCKETS = ("project", "topic", "extra")
BULLET_RE = re.compile(r"^[\s#*+\-]+")


def canon_name(raw):
    text = BULLET_RE.sub("", (raw or "").strip()).strip().strip("*").strip()
    return re.sub(r"\s+", " ", text)


def rows(payload):
    """→ [(canon_name, rel, anchor, tags, raw_name)]"""
    out = []
    for t in payload.get("terms") or []:
        tags = t.get("tags") or {}
        buckets = tuple(tuple(sorted(tags.get(b) or [])) for b in BUCKETS)
        out.append((canon_name(t.get("name")), t.get("rel"), t.get("anchor"), buckets, t.get("name")))
    out.sort(key=lambda r: (r[0], r[1] or "", r[2] or ""))
    return out


def main(argv):
    if len(argv) != 3:
        print("usage: term-parity-compare.py <scala.json> <python.json>", file=sys.stderr)
        return 2

    with open(argv[1], encoding="utf-8") as fh:
        scala = json.load(fh)
    with open(argv[2], encoding="utf-8") as fh:
        py = json.load(fh)

    s_rows = rows(scala)
    p_rows = rows(py)

    key = lambda r: (r[0], r[1], r[2], r[3])
    s_keys = [key(r) for r in s_rows]
    p_keys = [key(r) for r in p_rows]

    only_s = [r for r in s_rows if key(r) not in set(p_keys)]
    only_p = [r for r in p_rows if key(r) not in set(s_keys)]

    print("== 规范化对比：(name, rel, anchor, tags) 逐条相等 ==")
    print(f"scala  {len(s_rows)} 条　python {len(p_rows)} 条")
    for r in s_rows:
        print(f"  · {r[0]}　| {r[1]}　| {r[2]}　| project={list(r[3][0])} topic={list(r[3][1])} extra={list(r[3][2])}")

    ok = not only_s and not only_p and s_keys == p_keys
    if only_s:
        print("-- 仅 scala 有 --")
        for r in only_s:
            print(f"  - {r[0]}　| {r[1]}　| {r[2]}　| {r[3]}")
    if only_p:
        print("-- 仅 python 有 --")
        for r in only_p:
            print(f"  - {r[0]}　| {r[1]}　| {r[2]}　| {r[3]}")

    # 字面名差异（不进判定，但必须看得见）：同一 (rel, anchor) 两侧原始 name 不同。
    p_by_anchor = {(r[1], r[2]): r[4] for r in p_rows}
    raw_diff = []
    for r in s_rows:
        other = p_by_anchor.get((r[1], r[2]))
        if other is not None and other != r[4]:
            raw_diff.append((r[1], r[2], r[4], other))

    print()
    print("== 原始名差异（同一 (rel, anchor) 的 name 字面；仅列表符号前缀语义）==")
    if not raw_diff:
        print("  0 条 —— 两侧字面逐字相同")
    else:
        for rel, anchor, s_name, p_name in raw_diff:
            print(f"  ! {rel} {anchor}")
            print(f"      scala  = {s_name!r}")
            print(f"      python = {p_name!r}")
        print(f"  RAW_NAME_DIFF={len(raw_diff)}"
              "　（原因：check_wiki_docs.py:terms_of 只 .strip()，未剥行首 `- `；"
              "WikiIndex.termFromParagraph 剥 `^[#*-]+`。修法在上游一行正则，本节点无权改）")

    print()
    if ok:
        print(f"PARITY_OK {len(s_rows)}/{len(p_rows)}　RAW_NAME_DIFF={len(raw_diff)}")
        return 0
    print(f"PARITY_FAIL scala={len(s_rows)} python={len(p_rows)} "
          f"only_scala={len(only_s)} only_python={len(only_p)}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

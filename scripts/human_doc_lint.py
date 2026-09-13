#!/usr/bin/env python3
"""human_doc_lint.py — 人类可读文档检查器（R8「先落 lint 再包成 node 门」的 lint 面）。

真源：~/.nebflow/docs/Nebflow/visual-report-human-readable-spec.md（§C 硬约束 / §D 场景档位 /
§E 可视化 / §H R1–R10 裁定），条文口径逐条对齐；判定取值 = 作者 2026-09-13 裁定：
R1 = A 钉死 20,300 字符 + D1 条款文本归类；R3 = A 首屏 <8 KB 且 <80 行 + 四段骨架；
R6 = A 硬性出图 + 单文档 ≤3 图；R10 = A 附录豁免 + 5× 上限。

设计稿出处：spec 附录 A2「human_doc_lint.py 设计稿 v2 全文」（原设计位置
~/.nebflow/docs/Nebflow/tools/human_doc_lint.py；本实现落仓内 scripts/ 以便随构建复现与
后续 CI 化，判定口径不变）。

用法：
  python3 scripts/human_doc_lint.py <file> [--scene 产品设计] [--kind 条款|叙事] [--json] [--strict]
退出码：默认 0（**只警告不阻断**，R8=A）；--strict 时红 >0 ⇒ 1。
"""
import argparse
import json
import re
import sys

ID_RE = re.compile(
    r"n-[0-9a-f]{8}|chain-[0-9a-z-]+"
    r"|(?<![0-9A-Za-z_])[\w./-]+\.(?:scala|java|py|js|mjs|ts|json|md):\d+(?:-\d+)?"
    r"|(?<![0-9A-Za-z])(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}(?![0-9A-Za-z])"
    r"|(?:repo\s+main|commit|提交|哈希|digest)[\s`\"']+[0-9a-f]{7,40}(?![0-9A-Za-z])"
    r"|(?:宿主\s*)?PID\s*[0-9]{2,}", re.I | re.U)
RAW = re.compile(r"(?m)^\s*\$ |INFO |WARN |ERROR |^\d{4}-\d{2}-\d{2}|^[+-][^+-]")
SCENE = {"文献调研": 12764, "产品设计": 20300, "改bug": 8000, "改 bug": 8000, "事故复盘": 12764, "验收报告": 4000}
HARD_CAP = 20300            # R1 = A 钉死
FIRST_BYTES = 8192          # R3 = A 首屏 <8 KB
FIRST_LINES = 80            # R3 = A 首屏 <80 行
DENSITY = 0.030             # §C.3 术语裸用阈（语料中位）
SECTION_CAP = 6000          # §C.3 原文粘贴（正文单节）
APPENDIX_MULT = 5           # R10 附录 ≤ 正文 5×
IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)|<img\b|^```(?:mermaid|dot|graphviz|plantuml|wavedrom)", re.M | re.I)
ARTIFACT_RE = re.compile(r"值域|命名|判定式|口径|附录|清单")
FIVE = ("①做了什么", "②依据", "③没做什么", "④产出", "⑤关键假设")
SKELETON = ("### 结论", "### 要做什么", "### 大白话", "### 术语对照")


EN_WORDS = ("agent", "canvas", "node", "plugin", "prompt", "token", "skill", "pop", "digest",
            "commit", "markdown", "seed", "runtime", "figma")


def split_doc(t):
    """正文 = 首字节 → `## 附录` 之前；附录 = `## 附录` 之后。"""
    i = t.find("\n## 附录")
    return (t, "") if i < 0 else (t[:i], t[i + 1:])


def first_screen(t):
    """首屏 = 第一个 `## ` 章节（到第二个 `## ` 之前）。"""
    lines = t.split("\n")
    si = [j for j, l in enumerate(lines) if l.startswith("## ")]
    if not si:
        return ""
    end = si[1] if len(si) > 1 else len(lines)
    return "\n".join(lines[si[0]:end])


def sections(body):
    return re.split(r"(?m)^## ", body)[1:]


def lint(path, scene=None, kind="叙事"):
    t = open(path, encoding="utf-8").read()
    body, appendix = split_doc(t)
    fs = first_screen(t)
    out = []

    def item(name, reading, verdict, note=""):
        out.append({"item": name, "reading": reading, "verdict": verdict, "note": note})

    # ── 体量：硬顶 + 场景档位（字符口径） ──
    cap = SCENE.get(scene or "", None)
    cap = min(cap, HARD_CAP) if cap else HARD_CAP
    item("体量", f"chars={len(t)} bytes={len(t.encode())} lines={t.count(chr(10))} 正文={len(body)}",
         "绿" if len(body) <= cap else "红", f"上限={cap}{'' if scene else '(硬顶:未给 --scene)'}")
    # ── 首屏（R3 = A：<8 KB 且 <80 行 + 四段骨架） ──
    fb, fl = len(fs.encode()), fs.count("\n") + 1
    sk = [k in fs for k in SKELETON]
    item("首屏", f"bytes={fb} lines={fl} 骨架={dict(zip(SKELETON, sk))}",
         "绿" if fb < FIRST_BYTES and fl < FIRST_LINES and all(sk) else "红",
         f"限 <{FIRST_BYTES} B 且 <{FIRST_LINES} 行")
    # ── 大白话段（§C.2 第 3 段） ──
    m = re.search(r"### 大白话\n+(.+?)(?=\n### |\Z)", fs, re.S)
    if m:
        plain = m.group(1).strip()
        bad = [w for w in ("``", "`") if w in plain]
        if ID_RE.search(plain):
            bad.append("id/file:line")
        bad += [w for w in EN_WORDS if re.search(rf"(?<![A-Za-z]){w}(?![A-Za-z])", plain, re.I)]
        item("大白话", f"chars={len(plain)} 违规={bad}", "绿" if len(plain) <= 150 and not bad else "红", "≤150 字符且零术语")
    else:
        item("大白话", "段缺失", "红", "≤150 字符且零术语")
    # ── 禁项 1：术语裸用（D1 条款文本 ⇒ 密度豁免） ──
    share = body.count("`") / max(len(body), 1)
    item("禁项·术语密度", f"反引号={body.count('`')} 占比={share:.4f}",
         "豁免(条款文本类，判定人=独立复核节点)" if kind == "条款" else ("绿" if share <= DENSITY else "红"),
         f"阈 {DENSITY}")
    # ── 禁项 2：首屏裸 id ──
    ids = ID_RE.findall(fs)
    item("禁项·首屏裸id", f"命中={len(ids)} {ids[:6]}", "绿" if not ids else "红", "ID 正则 v2")
    # ── 禁项 3/4/5/6 ──
    mods = re.compile(r"已排查|已完成|已对齐|基本|若干")
    vac = [l for l in body.split("\n") if mods.search(re.sub(r"「[^」]*」", "", l)) and not re.search(r"\d", l)]
    item("禁项·无信息量", f"命中={len(vac)}", "绿" if not vac else "红", "行含空话且无数字")
    five = [k for k in FIVE if k in fs]
    item("禁项·交接体例(首屏)", f"命中={five}", "绿" if not five else "红", "§C.3 口径 = 首屏")
    rawblocks = [x.count("\n") for x in re.findall(r"```[\s\S]*?```", body) if x.count("\n") >= 8 and RAW.search(x)]
    mx = max([len(s) for s in sections(body)] or [0])
    item("禁项·原文粘贴", f"违规块={rawblocks} 最长节={mx}", "绿" if not rawblocks and mx <= SECTION_CAP else "红", f"节 ≤{SECTION_CAP}")
    openpath = [l for l in body.split("\n")
                if re.search(r"请看|请查阅|打开", l) and re.search(r"(~|/)[\w./-]*\.md", l)]
    item("禁项·指示开路径", f"命中={len(openpath)}", "绿" if not openpath else "红", "禁让人类去开路径")
    # ── 图预算（R6 = A：≤3 图 + 权重冲突解） ──
    imgs = len(IMAGE_RE.findall(t))
    declared = "图预算超限" in fs
    item("图预算", f"图引用={imgs} 首屏超限声明={declared}",
         "绿" if imgs <= 3 or declared else "红", "单文档 ≤3 图；超限须按权重取前 3 并在首屏声明")
    # ── 附录（R10 = A：≤5× 正文 + 须有目录 + 不得载唯一结论） ──
    if appendix:
        ratio = len(appendix) / max(len(body), 1)
        head = "\n".join(appendix.split("\n")[:12])
        toc = ("目录" in head) or bool(ARTIFACT_RE.search(head))
        item("附录", f"chars={len(appendix)} 正文={len(body)} 倍数={ratio:.2f}× 目录={toc}",
             "绿" if ratio <= APPENDIX_MULT and toc else "红",
             "≤5× 正文 + 须有目录 + 不得载唯一结论（末项由复核节点判）")
    else:
        item("附录", "无附录", "绿", "n/a")
    red = sum(1 for x in out if x["verdict"] == "红")
    return {"path": path, "items": out, "red": red}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--scene")
    ap.add_argument("--kind", default="叙事", choices=["叙事", "条款"])
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args()
    r = lint(a.path, a.scene, a.kind)
    if a.json:
        print(json.dumps(r, ensure_ascii=False))
    else:
        print(f"# {r['path']}  红={r['red']}")
        for x in r["items"]:
            print(f"  [{x['verdict']}] {x['item']}: {x['reading']}  ({x['note']})")
    sys.exit(1 if (a.strict and r["red"]) else 0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_check_wiki_docs_gate.py — 只许引用词条门禁（R13/R14/R15）的机械验收。

作者令 2026-11-05：「以后我们就只允许引用词条，不允许引用文档」「不允许引用文档
和图片路径，文档就用词条，图片要么直接嵌入，要么就可以点击跳转」。

断言六件事（全部对被测脚本以子进程实跑，不经 import——契约面 = CLI 输出与退出码）：

  ① 反向验红：夹具 vault（src/test/resources/wiki-term-gate/）里三处故意写坏的
     内容 ⇒ exit 非零，且 doc-reference / bare-image-path / dangling-term 各恰好
     1 条，文件:行对得上（a-doc.md:8 / b-doc.md:8 / c-doc.md:8）。
  ② 删掉那三行（在副本上真删）⇒ exit 0，三条计数全 0（门禁不误伤合规内容）。
  ③ 已注册词条短形 `[[门禁词条]]` 通过（R15 不报、R13 不报）。
  ④ 行内代码里的 `见 design/foo.md` / `[[裸词条]]` 通过（举例不是引用）。
  ⑤ `<!-- wiki-term-exempt: 迁移中 -->` 同行的违规被豁免：不致红，但计数显形
     （生效 1 条），悬空标注同样显形。
  ⑥ 输出纪律：违规行形如 `文件:行  原文 → 原因`，汇总段有按类计数小表。

用法：
  python3 test_check_wiki_docs_gate.py <check_wiki_docs.py 路径> [夹具 vault 路径]
  # 缺省 = 同目录的 check_wiki_docs.py + 仓库内 src/test/resources/wiki-term-gate

退出码：0 = 全绿；1 = 有断言失败（红）。仅依赖 Python 3 标准库。
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (Path(__file__).parent / "check_wiki_docs.py").resolve()
REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE = (Path(sys.argv[2]).resolve() if len(sys.argv) > 2
           else (REPO_ROOT / "src" / "test" / "resources" / "wiki-term-gate"))

# 夹具里三处靶子的确切位置（文件, 行号, 规则名）
TARGETS = (
    ("a-doc.md", 8, "doc-reference"),
    ("b-doc.md", 8, "bare-image-path"),
    ("c-doc.md", 8, "dangling-term"),
)
TARGET_MARK = "本档只故意写坏一处"

GATE_RULES = ("doc-reference", "bare-image-path", "dangling-term")


def run(args, cwd=None):
    env = dict(os.environ)
    env.pop("STAND_HOME", None)
    env.pop("NEBFLOW_HOME", None)
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args],
        cwd=str(cwd or REPO_ROOT), env=env, capture_output=True, text=True, timeout=120,
    )


def run_json(root):
    """跑校验（--json + 跳过 R3 概念首现——本批只测引用门禁面）。"""
    r = run(["--root", str(root), "--json", "--skip-concepts"])
    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        return r.returncode, None, {"__stderr__": r.stderr[:400], "__stdout__": r.stdout[:400]}
    return r.returncode, data.get("violations", []), data.get("counts", {})


def gate_only(violations):
    return [v for v in (violations or []) if v.get("rule") in GATE_RULES]


def write(p: Path, text: str) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def copy_fixture(dst: Path) -> Path:
    """夹具副本（绿色用例在副本上真删三行——提交在仓里的夹具保持红态）。"""
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(FIXTURE, dst)
    return dst


def delete_target_lines(root: Path) -> list:
    """真删：把三处靶子行从正文里去掉，返回被删的行（供回显）。"""
    removed = []
    for name, _line, _rule in TARGETS:
        p = root / name
        lines = p.read_text(encoding="utf-8").splitlines(keepends=True)
        kept = [l for l in lines if not l.startswith(TARGET_MARK)]
        removed.extend(l.strip() for l in lines if l.startswith(TARGET_MARK))
        p.write_text("".join(kept), encoding="utf-8")
    return removed


def min_vault(root: Path, body: str, term_doc: bool = True) -> Path:
    """最小合法 vault：index（名字豁免孤儿）+ glossary（已注册词条）+ 用例正文。"""
    write(root / "index.md", "---\ntype: index\nstatus: current\n---\n\n# 索引\n\n- [[门禁词条]]\n")
    write(root / "glossary.md",
          "---\ntype: glossary\nstatus: current\n---\n\n# 词汇表\n\n"
          "门禁词条 — 用例词条。 #proj/stand #topic/term-gate ^term-gate\n")
    if term_doc:
        write(root / "probe.md", "---\ntype: probe\nstatus: open\n---\n\n# 用例\n\n" + body)
    return root


def main() -> int:
    if not SCRIPT.is_file():
        print(f"FAIL: 被测脚本不存在：{SCRIPT}")
        return 1
    if not FIXTURE.is_dir():
        print(f"FAIL: 夹具 vault 不存在：{FIXTURE}")
        return 1
    failures = []
    tmp = Path(tempfile.mkdtemp(prefix="wikidocs-gate-"))
    try:
        # ── ① 反向验红：三处靶子各 1 条、位置对得上 ──────────────────
        exit_code, violations, counts = run_json(FIXTURE)
        if exit_code == 0:
            failures.append(f"① 夹具含三处违规却 exit 0（门禁没生效）：{counts}")
        got = gate_only(violations)
        by_rule = {}
        for v in got:
            by_rule.setdefault(v["rule"], []).append(v)
        for rule in GATE_RULES:
            n = len(by_rule.get(rule, []))
            if n != 1:
                failures.append(f"① {rule} 应恰好 1 条，实得 {n}：{by_rule.get(rule)}")
        for name, line, rule in TARGETS:
            hits = [v for v in by_rule.get(rule, []) if v["file"] == name and v["line"] == line]
            if not hits:
                failures.append(f"① {rule} 未落在 {name}:{line}：{by_rule.get(rule)}")

        # ── ⑥ 输出纪律：文件:行 原文 → 原因 + 计数汇总小表 ────────────
        human = run(["--root", str(FIXTURE), "--skip-concepts"])
        if human.returncode == 0:
            failures.append("① 人读输出 exit 0（应红）")
        for name, line, rule in TARGETS:
            pat = re.compile(rf"^\s+{re.escape(name)}:{line}\s+.*→.*$", re.MULTILINE)
            if not pat.search(human.stdout):
                failures.append(f"⑥ {name}:{line} 的输出不是 `文件:行 原文 → 原因` 形态："
                                f"{human.stdout[:300]}")
        for rid, rule in (("R13", "doc-reference"), ("R14", "bare-image-path"), ("R15", "dangling-term")):
            if not re.search(rf"^\s+{rid} {re.escape(rule)}\s+\d+$", human.stdout, re.MULTILINE):
                failures.append(f"⑥ 计数汇总缺 {rid} {rule} 行：{human.stdout[-400:]}")

        # ── ② 真删三行 ⇒ 绿（EXIT 0，三条计数全 0）────────────────────
        green_root = copy_fixture(tmp / "green")
        removed = delete_target_lines(green_root)
        if len(removed) != 3:
            failures.append(f"② 未删到三行靶子：{removed}")
        exit_code, violations, counts = run_json(green_root)
        if exit_code != 0:
            failures.append(f"② 删掉三行后应 exit 0，实得 {exit_code}：{violations}")
        for rule in GATE_RULES:
            if counts.get(rule):
                failures.append(f"② 删掉三行后 {rule} 仍报 {counts.get(rule)} 条：{violations}")

        # ── ③ 已注册词条短形通过 ─────────────────────────────────────
        root = min_vault(tmp / "c3" / "docs", "引用词条：[[门禁词条]]。\n")
        exit_code, violations, counts = run_json(root)
        if exit_code != 0 or gate_only(violations):
            failures.append(f"③ 已注册词条短形应零违规：exit={exit_code} {violations}")

        # ── ④ 行内代码里的路径与 wikilink 不算引用 ───────────────────
        root = min_vault(tmp / "c4" / "docs",
                         "语法示例：`见 design/foo.md`、`[[裸词条]]`、`![](attachments/x.png)`。\n")
        exit_code, violations, counts = run_json(root)
        if exit_code != 0 or gate_only(violations):
            failures.append(f"④ 行内代码里的示例应零违规：exit={exit_code} {violations}")

        # ── ⑤ 行内豁免：不致红但计数显形（生效 1 + 悬空 1）────────────
        root = min_vault(tmp / "c5" / "docs",
                         "存量引用：见 design/foo.md。 <!-- wiki-term-exempt: 迁移中 -->\n"
                         "\n"
                         "标注标错了行：[[门禁词条]]。 <!-- wiki-term-exempt: 标错行了 -->\n")
        exit_code, violations, counts = run_json(root)
        if counts.get("doc-reference"):
            failures.append(f"⑤ 豁免行的违规仍被计数：{counts}")
        human = run(["--root", str(root), "--skip-concepts"])
        if not re.search(r"行内豁免（2 条：生效 1、悬空 1", human.stdout):
            failures.append(f"⑤ 豁免计数未显形（生效/悬空）：{human.stdout[-400:]}")
        if "迁移中" not in human.stdout or "标错行了" not in human.stdout:
            failures.append(f"⑤ 豁免理由/悬空标注未显形：{human.stdout[-400:]}")
        if exit_code != 0:
            failures.append(f"⑤ 豁免后不应致红：exit={exit_code} {violations}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print("RED — 引用门禁（R13/R14/R15）验收失败：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print(f"GREEN — 引用门禁（R13/R14/R15）验收通过（被测脚本：{SCRIPT}；夹具：{FIXTURE}）："
          "反向验红 doc-reference/bare-image-path/dangling-term 各 1 条、删三行转绿、"
          "已注册词条通过、行内代码不算、行内豁免计数显形。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

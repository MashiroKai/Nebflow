#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_check_wiki_docs_terms.py — 词条标签 v2（term-system v2）机械验收。

断言七件事（全部对被测脚本以子进程实跑，不经 import——契约面 = CLI 输出与
退出码；每个用例自建 tempfile 最小 vault，互不掺和）：

  ① 多标签合法：恰好一个 #proj/ + 多个 #topic/ + #facet/ 入 extra ⇒ 零
     term-tag-missing。
  ② 缺 #proj/ ⇒ term-tag-missing（且原因指向 proj）。
  ③ 缺 #topic/ ⇒ term-tag-missing（且原因指向 topic）。
  ④ 标签写在 ^块锚之后 ⇒ 不被识别，等价缺标签 ⇒ term-tag-missing。
  ⑤ 别名 + 标签共存 ⇒ 别名照旧抽出、标签照旧生效，零违规。
  ⑥ 行内代码里的 `#x` 不是标签（不污染 project 桶）。
  ⑦ --dump-terms 字段形状 + 排序确定性（两次导出除 generated_at 外逐字节同）

用法：
  python3 test_check_wiki_docs_terms.py <check_wiki_docs.py 路径>
  # 缺省 = 同目录的 check_wiki_docs.py

退出码：0 = 全绿；1 = 有断言失败（红）。仅依赖 Python 3 标准库。
"""

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (Path(__file__).parent / "check_wiki_docs.py").resolve()

INDEX = """---
type: index
status: current
---

# 测试 vault 索引

- [[glossary]] — 词表。
"""

# 每个用例 = 一篇 glossary（词表块锚段落 = 词条声明），正文由用例给定。
GLOSSARY_HEAD = """---
type: glossary
status: current
---

# 词汇表

"""


def write(p: Path, text: str) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def run(script: Path, cwd: Path, args):
    env = dict(os.environ)
    env.pop("STAND_HOME", None)
    env.pop("NEBFLOW_HOME", None)
    return subprocess.run(
        [sys.executable, str(script), *args],
        cwd=str(cwd), env=env, capture_output=True, text=True, timeout=60,
    )


def make_vault(root: Path, glossary_body: str) -> Path:
    """最小合法 vault：index（已链接）+ glossary（用例正文）。"""
    write(root / "index.md", INDEX)
    write(root / "glossary.md", GLOSSARY_HEAD + glossary_body)
    return root


def check_rule(root: Path):
    """跑校验（--json + 跳过 R3 概念首现）：返回 (exit, violations, counts)。"""
    r = run(SCRIPT, root.parent, ["--root", str(root), "--json", "--skip-concepts"])
    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        return r.returncode, None, {"__stderr__": r.stderr[:400], "__stdout__": r.stdout[:400]}
    return r.returncode, data.get("violations", []), data.get("counts", {})


def dump(root: Path):
    """跑 --dump-terms：返回 (exit, 解析后的 JSON)。"""
    r = run(SCRIPT, root.parent, ["--root", str(root), "--dump-terms", "--json"])
    try:
        return r.returncode, json.loads(r.stdout)
    except json.JSONDecodeError:
        return r.returncode, {"__stderr__": r.stderr[:400], "__stdout__": r.stdout[:400]}


def ttm(violations):
    """只取 term-tag-missing。"""
    return [v for v in (violations or []) if v.get("rule") == "term-tag-missing"]


def main() -> int:
    if not SCRIPT.is_file():
        print(f"FAIL: 被测脚本不存在：{SCRIPT}")
        return 1
    failures = []
    tmp = Path(tempfile.mkdtemp(prefix="wikidocs-terms-"))

    def case(name, body):
        root = make_vault(tmp / name / "docs", body)
        return root

    try:
        # ── ① 多标签合法 ─────────────────────────────────────────────
        root = case("c1", "- 词条甲 — 定义甲。 #proj/stand #topic/kiwi #topic/banana #facet/orch ^term-alpha\n")
        exit_code, violations, counts = check_rule(root)
        if ttm(violations):
            failures.append(f"① 多标签合法应零 term-tag-missing：{ttm(violations)}")
        _, d = dump(root)
        t = (d.get("terms") or [{}])[0]
        tags = t.get("tags") or {}
        if sorted(tags.get("project") or []) != ["stand"]:
            failures.append(f"① project 桶错：{tags.get('project')}")
        if sorted(tags.get("topic") or []) != ["banana", "kiwi"]:
            failures.append(f"① topic 桶错（应排序去重）：{tags.get('topic')}")
        if tags.get("extra") != ["facet/orch"]:
            failures.append(f"① extra 桶错：{tags.get('extra')}")

        # ── ② 缺 #proj/ ─────────────────────────────────────────────
        root = case("c2", "- 词条乙 — 定义乙。 #topic/kiwi ^term-beta\n")
        _, violations, _ = check_rule(root)
        got = ttm(violations)
        if len(got) != 1:
            failures.append(f"② 缺 proj 应恰好 1 条 term-tag-missing：{violations}")
        elif "proj" not in got[0].get("detail", ""):
            failures.append(f"② 缺 proj 的 detail 未指向 proj：{got[0].get('detail')}")

        # ── ③ 缺 #topic/ ────────────────────────────────────────────
        root = case("c3", "- 词条丙 — 定义丙。 #proj/stand ^term-gamma\n")
        _, violations, _ = check_rule(root)
        got = ttm(violations)
        if len(got) != 1:
            failures.append(f"③ 缺 topic 应恰好 1 条 term-tag-missing：{violations}")
        elif "topic" not in got[0].get("detail", ""):
            failures.append(f"③ 缺 topic 的 detail 未指向 topic：{got[0].get('detail')}")

        # ── ④ 标签写在 ^块锚之后 ⇒ 不生效 ───────────────────────────
        root = case("c4", "- 词条丁 — 定义丁。 ^term-delta #proj/stand #topic/kiwi\n")
        _, violations, _ = check_rule(root)
        got = ttm(violations)
        if len(got) != 1:
            failures.append(f"④ 标签在 ^块锚之后应判 term-tag-missing：{violations}")
        elif "^term-delta" not in got[0].get("detail", ""):
            failures.append(f"④ detail 未点明块锚位置错：{got[0].get('detail')}")

        # ── ⑤ 别名 + 标签共存 ───────────────────────────────────────
        root = case("c5", "- 词条戊（epsilon，第五） — 定义戊。 #proj/stand #topic/kiwi ^term-epsilon\n")
        _, violations, _ = check_rule(root)
        if ttm(violations):
            failures.append(f"⑤ 别名+标签共存应零 term-tag-missing：{ttm(violations)}")
        _, d = dump(root)
        t = (d.get("terms") or [{}])[0]
        if len(t.get("aliases") or []) != 2:
            failures.append(f"⑤ 别名未抽出两条：{t.get('aliases')}")
        if (t.get("tags") or {}).get("project") != ["stand"]:
            failures.append(f"⑤ 别名共存时标签丢失：{t.get('tags')}")

        # ── ⑥ 行内代码里的 #x 不算标签 ──────────────────────────────
        root = case("c6", "- 词条己 — 定义己，例：`#proj/fake`。 #proj/stand #topic/kiwi ^term-zeta\n")
        _, violations, _ = check_rule(root)
        if ttm(violations):
            failures.append(f"⑥ 行内代码不应诱生 term-tag-missing：{ttm(violations)}")
        _, d = dump(root)
        tags = (d.get("terms") or [{}])[0].get("tags") or {}
        if tags.get("project") != ["stand"]:
            failures.append(f"⑥ 行内代码 `#proj/fake` 污染了 project 桶：{tags.get('project')}")
        # 行内代码内标签不能顶替真标签：只有代码里的 #topic/ 时仍判缺 topic
        root2 = case("c6b", "- 词条庚 — 定义庚，例：`#topic/fake`。 #proj/stand ^term-eta\n")
        _, violations2, _ = check_rule(root2)
        if len(ttm(violations2)) != 1:
            failures.append(f"⑥ 行内代码里的 #topic/ 不应顶替真标签：{violations2}")

        # ── ⑦ --dump-terms 字段形状 + 排序确定性 ────────────────────
        body = (
            "- 词条辛 — 定义辛。 #proj/stand #topic/kiwi #facet/a ^term-theta\n"
            "\n"
            "- 词条壬 — 定义壬。 #proj/stand #topic/kiwi ^term-iota\n"
        )
        root = case("c7", body)
        exit1, d1 = dump(root)
        exit2, d2 = dump(root)
        if exit1 != 0 or exit2 != 0:
            failures.append(f"⑦ --dump-terms 应恒 0 退出：{exit1}/{exit2} {str(d1)[:200]}")
        if sorted(d1) != ["generated_at", "refs", "root", "terms"]:
            failures.append(f"⑦ 顶层键形状错：{sorted(d1)}")
        want_term_keys = ["aliases", "anchor", "definition", "end_line", "line",
                          "name", "rel", "source", "tags"]
        for t in d1.get("terms") or []:
            if sorted(t) != want_term_keys:
                failures.append(f"⑦ 词条键形状错：{sorted(t)}")
                break
            if sorted(t.get("tags") or {}) != ["extra", "project", "topic"]:
                failures.append(f"⑦ tags 键形状错：{sorted(t.get('tags') or {})}")
                break
        # 排序确定性：terms 按 (rel, line, name) 单调
        keys = [(t["rel"], t["line"], t["name"]) for t in d1.get("terms") or []]
        if keys != sorted(keys):
            failures.append(f"⑦ terms 未按 (rel, line, name) 排序：{keys}")
        # 两次导出除 generated_at 外逐字节同
        d1s = json.dumps({k: v for k, v in d1.items() if k != "generated_at"},
                         ensure_ascii=False, sort_keys=True)
        d2s = json.dumps({k: v for k, v in d2.items() if k != "generated_at"},
                         ensure_ascii=False, sort_keys=True)
        if d1s != d2s:
            failures.append("⑦ 两次导出除 generated_at 外不一致（顺序不确定）")
        if d1.get("generated_at") and not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$",
                                                   d1["generated_at"]):
            failures.append(f"⑦ generated_at 形态错：{d1.get('generated_at')}")
        # 纯导出：--dump-terms 不掺 violations 键
        if "violations" in d1:
            failures.append("⑦ --dump-terms 掺入了 violations（不是纯导出）")
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print("RED — 词条标签 v2 验收失败：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print(f"GREEN — 词条标签 v2 验收通过（被测脚本：{SCRIPT}）：多标签合法 / 缺 proj / "
          "缺 topic / 标签在块锚之后不生效 / 别名共存 / 行内代码不算 / dump-terms 形状与确定性。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

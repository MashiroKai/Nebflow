#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_check_wiki_docs_resolution.py — 落位解析单源的机械验收（作者令 2026-11-03）。

断言三件事（全部对被测脚本以子进程实跑，不经 import——契约面 = CLI 输出与退出码）：

  ① 缺省根解析（改红的验收主断言）：造假项目（有 project.json + .stand/docs 的
     最小合法 vault，workspace docs/ 里故意放一篇**坏档**），不带 --root 跑 ⇒
     校验的必须是**注册目录新路径** `~/.stand(或 STAND_HOME)/projects/<name>/.stand/docs`
     且 exit 0——旧实现缺省根是相对 cwd 的 `docs`，必红（找不到/校验到错位置）。
  ② 数据根环境面：STAND_HOME 指向临时根 ⇒ 解析跟随（隔离实例面不教错路径）。
  ③ 显式 --root 向后兼容：--root 指向 workspace 内存量 vault ⇒ 照旧校验该路径
     （并能看到该 vault 里故意放的坏档 ⇒ exit 1，证明校验的确实是显式根）。

用法：
  python3 test_check_wiki_docs_resolution.py <check_wiki_docs.py 路径>
  # 缺省 = 同目录的 check_wiki_docs.py

退出码：0 = 全绿；1 = 有断言失败（红）。仅依赖 Python 3 标准库。
"""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (Path(__file__).parent / "check_wiki_docs.py").resolve()

GOOD_INDEX = """---
type: index
status: current
---

# 测试 vault 索引

- [[doc-a]] — 样例文档。
- [[glossary]] — 词表。
"""

GOOD_DOC_A = """---
type: research-log
status: open
---

# 文档 A

引用词表概念：[[GCR]]。回链索引：[[index]]。
"""

GOOD_GLOSSARY = """---
type: glossary
status: current
---

# 词汇表

GCR（银河宇宙线） — 来自银河系的高能粒子流。 ^gcr
"""

BAD_DOC = """# 坏档（无 frontmatter、零链接——触发 R1+R2）

这是一篇故意坏掉的文档，用于证明校验器校验的是**这个**目录。
"""


def write(p: Path, text: str) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def run(script: Path, cwd: Path, args, env_extra=None):
    env = dict(os.environ)
    env.pop("STAND_HOME", None)
    env.pop("NEBFLOW_HOME", None)
    env.update(env_extra or {})
    return subprocess.run(
        [sys.executable, str(script), *args],
        cwd=str(cwd), env=env, capture_output=True, text=True, timeout=60,
    )


def make_fake_project(home: Path, name: str = "fakeproj") -> tuple[Path, Path]:
    """注册目录（project.json + .stand/docs 最小合法 vault）+ workspace（坏档 docs/）。"""
    proj = home / "projects" / name
    ledger_docs = proj / ".stand" / "docs"
    write(proj / "project.json", json.dumps({"name": name, "workspace": str(home / "ws" / name)}))
    write(ledger_docs / "index.md", GOOD_INDEX)
    write(ledger_docs / "doc-a.md", GOOD_DOC_A)
    write(ledger_docs / "glossary.md", GOOD_GLOSSARY)
    ws_docs = home / "ws" / name / "docs"
    write(ws_docs / "bad.md", BAD_DOC)
    return proj, ws_docs


def main() -> int:
    if not SCRIPT.is_file():
        print(f"FAIL: 被测脚本不存在：{SCRIPT}")
        return 1
    failures = []
    tmp = Path(tempfile.mkdtemp(prefix="wikidocs-resolution-"))
    try:
        # ── ① 缺省根解析：注册目录新路径 ─────────────────────────────
        home1 = tmp / "home1"
        proj1, ws_docs1 = make_fake_project(home1, "stand")  # 脚本缺省 --project=stand
        r = run(SCRIPT, tmp, ["--json"], {"STAND_HOME": str(home1)})
        expected_root = str(proj1 / ".stand" / "docs")
        if r.returncode != 0:
            failures.append(f"① exit={r.returncode}（期望 0）。stdout={r.stdout[:400]} stderr={r.stderr[:400]}")
        else:
            data = json.loads(r.stdout)
            if data.get("root") != expected_root:
                failures.append(f"① 解析根错位：got {data.get('root')} want {expected_root}")
            if data.get("violations"):
                failures.append(f"① 新路径 vault 应零违规：{data.get('violations')}")
        # 不带 STAND_HOME（真 home）也必须解析到真 home 的注册目录路径形态，绝不解析到相对 cwd 的 docs
        r2 = run(SCRIPT, tmp, ["--rules"])  # 规则面自检：脚本本体可跑
        if r2.returncode != 0:
            failures.append(f"①-预检 --rules 失败：{r2.stderr[:200]}")

        # ── ② STAND_HOME 缺省即解析跟随（人读输出里带解析根路径）────────
        home2 = tmp / "home2"
        proj2, _ = make_fake_project(home2, "proj-b")
        r = run(SCRIPT, tmp, ["--project", "proj-b"], {"STAND_HOME": str(home2)})
        if r.returncode != 0 or str(proj2 / ".stand" / "docs") not in r.stdout:
            failures.append(f"② exit={r.returncode} 输出未含解析根。stdout={r.stdout[:300]} stderr={r.stderr[:300]}")

        # ── ③ 显式 --root 向后兼容：workspace 存量 vault 照旧可校验 ────
        home3 = tmp / "home3"
        _proj3, ws_docs3 = make_fake_project(home3, "proj-c")
        r = run(SCRIPT, tmp, ["--root", str(ws_docs3), "--json"], {"STAND_HOME": str(home3)})
        if r.returncode != 1:
            failures.append(f"③ 显式 --root 指向坏档 vault 应 exit 1（红），got {r.returncode}")
        else:
            data = json.loads(r.stdout)
            if data.get("root") != str(ws_docs3):
                failures.append(f"③ 显式 --root 被覆盖：got {data.get('root')} want {ws_docs3}")
            rules = {v.get("rule") for v in data.get("violations", [])}
            if "obsidian-format" not in rules:
                failures.append(f"③ 坏档违规面缺 R1：{data.get('violations')}")

        # ── ④ NEBFLOW_HOME legacy 兼容序 ────────────────────────────
        home4 = tmp / "home4"
        proj4, _ = make_fake_project(home4, "proj-d")
        r = run(SCRIPT, tmp, ["--project", "proj-d", "--json"], {"NEBFLOW_HOME": str(home4)})
        if r.returncode != 0 or json.loads(r.stdout).get("root") != str(proj4 / ".stand" / "docs"):
            failures.append(f"④ NEBFLOW_HOME 解析失败：exit={r.returncode} {r.stdout[:200]} {r.stderr[:200]}")
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print("RED — 落位解析验收失败：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print(f"GREEN — 落位解析验收通过（被测脚本：{SCRIPT}）：缺省根=注册目录 .stand/docs、"
          "STAND_HOME/NEBFLOW_HOME 跟随、显式 --root 向后兼容。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

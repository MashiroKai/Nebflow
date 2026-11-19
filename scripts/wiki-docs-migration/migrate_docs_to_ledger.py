#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""migrate_docs_to_ledger.py — 项目文档库搬家：workspace `docs/` → 项目注册目录 `.stand/docs/`

作者令 2026-11-03 20:02：「docs 放各 projects 下的 .stand（放 project.json 的地方）
目录下的 docs，不放各项目目录下」。本脚本 = 该裁定的**执行面**（幂等、可重跑、有
dry-run），只搬字节、不解释内容：

  源        `<workspace>/docs/`       （项目 workspace 仓库内，历史语料）
  目标      `<data_root>/projects/<project>/.stand/docs/`
                                     （注册目录，与 project.json 同级；data_root =
                                      `STAND_HOME` → `NEBFLOW_HOME` → `~/.stand`，
                                      与 check_wiki_docs.py / PathUtil.dataRoot 同序）

设计要点（逐条对应任务书验收）：

  ① **整树搬家**：子目录形状、文件名、mtime 原样保留 ⇒ 库内相对链接（含 `../assets/`
     这类同级回指）形状不变，天然继续有效。
  ② **链接修正**：只修**因落位变化而失效**的三类引用，不碰其它字节——
     (a) vault 相对但带 `docs/` 前缀的 wikilink（`[[docs/index|x]]`）⇒ 去掉前缀；
     (b) md 链接目标带 `docs/` 前缀；
     (c) 绝对路径里出现旧 vault 路径 ⇒ 改写为新 vault 绝对路径。
     其余链接原样保留（改写错了比不改更坏）。
  ③ **零丢失证明**：每个文件前后 sha256 逐条列出（`--report` 输出的对照表），
     并给出「源文件数 / 目标文件数 / sha 不匹配数」三行摘要。正文被 ② 改写的
     文件**单独标记**（`link-fixed`），其 sha 变化是**预期内**的，不计入丢失。
  ④ **幂等**：目标已存在且 sha256 相同 ⇒ 跳过（`unchanged`）；目标存在但 sha 不同
     ⇒ 拒绝覆盖并**报错退出**（不猜谁对——那是人工裁定面，`--force` 才覆盖）。
     重跑任意次结果一致；`--dry-run` 零写盘。
  ⑤ **清理**：`--delete-source` 只删「目标已校验同 sha」的源文件，再自底向上删空
     目录（`--delete-source` 隐含 `--verify-only` 前置，不做盲删）。

用法：
  python3 migrate_docs_to_ledger.py --project stand --dry-run
  python3 migrate_docs_to_ledger.py --project stand --report /tmp/migration.md
  python3 migrate_docs_to_ledger.py --from /path/to/docs --to /path/to/.stand/docs
  python3 migrate_docs_to_ledger.py --project stand --delete-source

退出码：0 = 成功（含无事可做）；1 = 有冲突/断链/丢失；2 = 用法或环境错误。
仅依赖 Python 3 标准库。
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path

DATA_ROOT_ENV_VARS = ("STAND_HOME", "NEBFLOW_HOME")
DEFAULT_HOME_DIRNAME = ".stand"
LEDGER_DOCS_REL = (".stand", "docs")

SKIP_NAMES = {".DS_Store", "Thumbs.db", "desktop.ini"}
SKIP_DIRS = {".git", "__pycache__", ".ipynb_checkpoints", "node_modules"}
TEXT_SUFFIXES = {".md", ".markdown", ".html", ".htm", ".txt"}

WIKILINK_RE = re.compile(r"(!?\[\[)([^\]\|#\n]*)((?:#[^\]\|#\n]*)?)(\|[^\]\n]*)?(\]\])")
MDLINK_RE = re.compile(r"(\]\(\s*)(<[^>\n]*>|[^)\s\n]+)([^)\n]*)(\))")


# ── 落位解析（与 check_wiki_docs.py 同公式，禁两套口径） ────────────────

def resolve_data_root(env=None):
    env = os.environ if env is None else env
    for var in DATA_ROOT_ENV_VARS:
        raw = env.get(var)
        if raw and raw.strip():
            return Path(raw.strip()).expanduser()
    return Path.home() / DEFAULT_HOME_DIRNAME


def project_docs_root(project, env=None):
    return resolve_data_root(env) / "projects" / project / Path(*LEDGER_DOCS_REL)


def workspace_docs_root(project, env=None):
    """从项目注册目录的 project.json 取 workspace，再拼 `docs/`（缺 = None）。"""
    pj = resolve_data_root(env) / "projects" / project / "project.json"
    if not pj.is_file():
        return None
    try:
        data = json.loads(pj.read_text(encoding="utf-8"))
    except Exception:
        return None
    ws = data.get("workspace")
    if not ws:
        return None
    return Path(ws).expanduser() / "docs"


# ── 文件枚举与摘要 ────────────────────────────────────────────────────

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def iter_files(root):
    """递归枚举 root 下的实文件（跳过噪声名/目录），返回排好序的 posix 相对路径。"""
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name in SKIP_NAMES:
                continue
            full = Path(dirpath) / name
            if not full.is_file():
                continue
            out.append(full.relative_to(root).as_posix())
    return sorted(out)


# ── 链接修正 ──────────────────────────────────────────────────────────

def _strip_docs_prefix(target, known):
    """`docs/x` → `x`（仅当 x 确实是 vault 内一条目标时；否则原样保留）。"""
    if not target.startswith("docs/"):
        return target
    stripped = target[len("docs/"):]
    if not stripped:
        return target
    # 候选：原样、补 .md、补 .markdown（多点名如 `x.zh-CN` 也要能命中 `x.zh-CN.md`）
    for cand in (stripped, stripped + ".md", stripped + ".markdown"):
        if cand in known:
            return stripped
    low = stripped.lower()
    for rel in known:
        if rel.lower() == low or rel.lower() in (low + ".md", low + ".markdown"):
            return stripped
    # 无法证实 ⇒ 保留（宁可留一条断链被校验器抓，也不静默改写）
    return target


def rewrite_links(text, old_root, new_root, known_targets):
    """按落位变化修正三类引用；返回 (新文本, 修正条数)。"""
    fixes = 0

    def fix_wikilink(m):
        nonlocal fixes
        head, target, anchor, alias, tail = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
        new = target
        t = target.strip()
        if t and not t.startswith(("/", "~")):
            s = _strip_docs_prefix(t, known_targets)
            if s != t:
                new = s
        # 绝对路径直接命中旧 vault ⇒ 换新 vault（wikilink 里较少见，保守处理）
        if t.startswith(str(old_root)):
            new = str(new_root) + t[len(str(old_root)):]
        if new != target:
            fixes += 1
        return f"{head}{new}{anchor}{alias or ''}{tail}"

    def fix_mdlink(m):
        nonlocal fixes
        head, raw, tail, close = m.group(1), m.group(2), m.group(3), m.group(4)
        angled = raw.startswith("<") and raw.endswith(">")
        target = raw[1:-1] if angled else raw
        new = target
        if target.startswith(("http://", "https://", "mailto:", "#", "tel:", "data:")):
            return m.group(0)
        if target.startswith(str(old_root)):
            new = str(new_root) + target[len(str(old_root)):]
        elif not target.startswith(("/", "~")):
            s = _strip_docs_prefix(target, known_targets)
            if s != target:
                new = s
        if new != target:
            fixes += 1
            return f"{head}{'<' + new + '>' if angled else new}{tail}{close}"
        return m.group(0)

    out = WIKILINK_RE.sub(fix_wikilink, text)
    out = MDLINK_RE.sub(fix_mdlink, out)
    return out, fixes


def unresolved_relative_links(vault_root, rel, text):
    """迁移后自检：相对 md 链接是否仍落在 vault 内（越界 = 落位推断有误）。"""
    bad = []
    for m in MDLINK_RE.finditer(text):
        raw = m.group(2)
        target = raw[1:-1] if raw.startswith("<") and raw.endswith(">") else raw
        if target.startswith(("http://", "https://", "mailto:", "#", "tel:", "data:")):
            continue
        base = (vault_root / rel).parent
        resolved = Path(os.path.normpath(str(base / target)))
        try:
            resolved.relative_to(vault_root)
        except ValueError:
            bad.append(target)
    return bad


def wikilink_prefix_leftovers(text):
    """仍有 `docs/` 前缀的 wikilink（可能是目标不在库内的悬空前缀）。"""
    leftovers = []
    for m in WIKILINK_RE.finditer(text):
        t = m.group(2).strip()
        if t.startswith("docs/"):
            leftovers.append(t)
    return leftovers


# ── 迁移主流程 ────────────────────────────────────────────────────────

def migrate(src, dst, dry_run, force, delete_source, rewrite):
    report = {
        "source": str(src),
        "target": str(dst),
        "dry_run": dry_run,
        "files": [],
        "summary": {},
        "problems": [],
    }
    if not src.is_dir():
        # 幂等：源已搬空/删除 = 无事可做（重跑必达此分支），不是错误。
        report["summary"] = {"source_files": 0, "target_files": 0, "missing": 0,
                             "copied": 0, "unchanged": 0, "updated": 0, "conflicts": 0,
                             "link_fixed": 0, "bytes": 0, "source_absent": True}
        return report

    rels = iter_files(src)
    known = set(rels)
    report["summary"]["source_files"] = len(rels)
    report["summary"]["bytes"] = sum((src / r).stat().st_size for r in rels)

    copied = unchanged = updated = conflicts = link_fixed = 0
    missing = 0

    for rel in rels:
        s_path = src / rel
        d_path = dst / rel
        s_sha = sha256_of(s_path)
        entry = {"rel": rel, "src_sha256": s_sha, "bytes": s_path.stat().st_size}

        # 链接修正（只对文本件，且只在真有必要时写）
        new_text = None
        if rewrite and s_path.suffix.lower() in TEXT_SUFFIXES:
            try:
                text = s_path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                text = None
            if text is not None:
                new_text, fixes = rewrite_links(text, src, dst, known)
                if fixes:
                    entry["link_fixes"] = fixes
                    link_fixed += 1

        if new_text is not None:
            payload = new_text.encode("utf-8")
            dst_sha = hashlib.sha256(payload).hexdigest()
        else:
            payload = None
            dst_sha = s_sha

        exists = d_path.exists()
        if exists:
            cur = sha256_of(d_path)
            if cur == dst_sha:
                unchanged += 1
                entry["status"] = "unchanged"
                entry["dst_sha256"] = cur
            elif force:
                if not dry_run:
                    d_path.parent.mkdir(parents=True, exist_ok=True)
                    if payload is not None:
                        d_path.write_bytes(payload)
                    else:
                        shutil.copy2(s_path, d_path)
                updated += 1
                entry["status"] = "updated(forced)"
                entry["dst_sha256_before"] = cur
                entry["dst_sha256"] = dst_sha
            else:
                conflicts += 1
                entry["status"] = "conflict"
                entry["dst_sha256"] = cur
                report["problems"].append(f"目标已存在且内容不同（拒绝覆盖）：{rel}")
        else:
            if not dry_run:
                d_path.parent.mkdir(parents=True, exist_ok=True)
                if payload is not None:
                    d_path.write_bytes(payload)
                    shutil.copystat(s_path, d_path)
                else:
                    shutil.copy2(s_path, d_path)
            copied += 1
            entry["status"] = "copied"
            entry["dst_sha256"] = dst_sha
        report["files"].append(entry)

    # 迁移后自检：目标侧逐文件可读、无（非预期）丢失
    if not dry_run and dst.is_dir():
        dst_rels = set(iter_files(dst))
        for rel in rels:
            if rel not in dst_rels:
                missing += 1
                report["problems"].append(f"目标缺失：{rel}")
        for rel in sorted(dst_rels):
            p = dst / rel
            if p.suffix.lower() not in TEXT_SUFFIXES:
                continue
            try:
                text = p.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for bad in unresolved_relative_links(dst, rel, text):
                report["problems"].append(f"相对链接越出 vault：{rel} → {bad}")
            for left in wikilink_prefix_leftovers(text):
                report["problems"].append(f"wikilink 仍带 docs/ 前缀（目标不在库内）：{rel} → {left}")

        if delete_source and not conflicts and not missing:
            # 删除判据 = 「目标内容就是本次该有的内容」（payload sha，不是源字节 sha）：
            # 链接修正过的文件两侧 sha 本就不同，用源 sha 判定会漏删（实测 2 件残留）。
            expected = {}
            for e in report["files"]:
                if e.get("dst_sha256"):
                    expected[e["rel"]] = e["dst_sha256"]
            removed = 0
            for rel in rels:
                s_path = src / rel
                d_path = dst / rel
                want = expected.get(rel)
                if want and d_path.is_file() and sha256_of(d_path) == want:
                    s_path.unlink()
                    removed += 1
            # 噪音件（.DS_Store 等，不入 vault）同清
            for dirpath, dirnames, filenames in os.walk(src):
                for name in filenames:
                    if name in SKIP_NAMES:
                        try:
                            (Path(dirpath) / name).unlink()
                        except OSError:
                            pass
            # 自底向上删空目录
            for dirpath, dirnames, filenames in os.walk(src, topdown=False):
                p = Path(dirpath)
                if p == src:
                    continue
                try:
                    if not any(p.iterdir()):
                        p.rmdir()
                except OSError:
                    pass
            try:
                if not any(src.iterdir()):
                    src.rmdir()
            except OSError:
                pass
            report["summary"]["source_deleted"] = removed

    report["summary"].update({
        "target_files": len(rels) if not dry_run else 0,
        "copied": copied,
        "unchanged": unchanged,
        "updated": updated,
        "conflicts": conflicts,
        "link_fixed": link_fixed,
        "missing": missing,
    })
    return report


def render_table(report):
    lines = []
    s = report["summary"]
    lines.append(f"# docs 迁移对照表（workspace → 项目注册目录）")
    lines.append("")
    lines.append(f"- 源：`{report['source']}`")
    lines.append(f"- 目标：`{report['target']}`")
    lines.append(f"- dry-run：{report['dry_run']}")
    lines.append("")
    lines.append("| 读数 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| 源文件数 | {s['source_files']} |")
    lines.append(f"| 源体积（bytes） | {s['bytes']} |")
    lines.append(f"| 复制（copied） | {s['copied']} |")
    lines.append(f"| 已一致（unchanged） | {s['unchanged']} |")
    lines.append(f"| 强制覆盖（updated） | {s['updated']} |")
    lines.append(f"| 冲突（conflict） | {s['conflicts']} |")
    lines.append(f"| 目标缺失（missing） | {s['missing']} |")
    lines.append(f"| 链接修正文件数 | {s['link_fixed']} |")
    lines.append("")
    lines.append("## 逐文件 sha256（前 → 后）")
    lines.append("")
    lines.append("| 相对路径 | 源 sha256 | 目标 sha256 | 状态 |")
    lines.append("|---|---|---|---|")
    for f in report["files"]:
        lines.append(
            f"| `{f['rel']}` | `{f['src_sha256'][:16]}…` | "
            f"`{(f.get('dst_sha256') or '-')[:16]}…` | {f.get('status', '?')}"
            + (f" (link-fixed ×{f['link_fixes']})" if f.get("link_fixes") else "")
            + " |"
        )
    if report["problems"]:
        lines.append("")
        lines.append("## 问题")
        for p in report["problems"]:
            lines.append(f"- {p}")
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="迁移项目文档库：workspace docs/ → 项目注册目录 .stand/docs/（幂等，支持 dry-run）"
    )
    ap.add_argument("--project", default="stand", help="项目名（缺省 stand）；--from/--to 缺省时由它派生")
    ap.add_argument("--from", dest="src", default=None, help="源 vault（缺省 = 项目 workspace 的 docs/）")
    ap.add_argument("--to", dest="dst", default=None, help="目标 vault（缺省 = 注册目录 .stand/docs/）")
    ap.add_argument("--dry-run", action="store_true", help="只算不写（零写盘）")
    ap.add_argument("--force", action="store_true", help="目标存在且内容不同时覆盖（默认拒绝）")
    ap.add_argument("--delete-source", action="store_true",
                    help="迁移校验通过后删除已确认同 sha 的源文件并清理空目录")
    ap.add_argument("--no-rewrite-links", action="store_true", help="不做链接修正（纯字节搬运）")
    ap.add_argument("--report", default=None, help="对照表输出路径（markdown）；- = stdout")
    ap.add_argument("--json", action="store_true", help="机器读 JSON 摘要（stdout）")
    args = ap.parse_args(argv)

    src = Path(args.src).expanduser() if args.src else workspace_docs_root(args.project)
    dst = Path(args.dst).expanduser() if args.dst else project_docs_root(args.project)
    if src is None:
        print(f"ERROR: 无法从项目 {args.project} 的 project.json 解析 workspace；请用 --from 显式指定", file=sys.stderr)
        return 2
    if Path(os.path.normpath(str(src))) == Path(os.path.normpath(str(dst))):
        print(f"ERROR: 源与目标同一目录：{src}", file=sys.stderr)
        return 2

    report = migrate(src, dst, args.dry_run, args.force, args.delete_source,
                     rewrite=not args.no_rewrite_links)

    if args.report:
        table = render_table(report)
        if args.report == "-":
            print(table)
        else:
            rp = Path(args.report).expanduser()
            rp.parent.mkdir(parents=True, exist_ok=True)
            rp.write_text(table, encoding="utf-8")
            print(f"对照表：{rp}")

    if args.json:
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    else:
        s = report["summary"]
        print(
            f"[{'dry-run' if args.dry_run else 'apply'}] {src} → {dst}\n"
            f"  源文件 {s['source_files']}（{s['bytes']} bytes）· copied {s['copied']} · "
            f"unchanged {s['unchanged']} · updated {s['updated']} · "
            f"conflict {s['conflicts']} · missing {s['missing']} · link-fixed {s['link_fixed']}"
        )
        if s.get("source_deleted") is not None:
            print(f"  源已清理 {s['source_deleted']} 件")

    problems = report["problems"]
    if problems:
        shown = problems[:20]
        for p in shown:
            print(f"  ! {p}", file=sys.stderr)
        if len(problems) > len(shown):
            print(f"  ! …另有 {len(problems) - len(shown)} 条", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

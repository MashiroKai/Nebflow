"""glossary_vault — vault 词条层（文件为唯一真相源，SQLite 只是索引缓存）。

设计依据：`docs/20261102_180500_doc-and-term-system-survey.md`（两层登记表、块锚约定、
为何不合并）。本模块只做四件事：

1. **解析**：把 vault 里的 markdown 解析成 文档/块锚/链接 三元组（正则与语义与
   `skills/wiki-docs/scripts/check_wiki_docs.py` 同款——不改那份脚本的任何判定，
   校验面直接调用它，见 `validate()`）。
2. **抽词条**：概念层 `docs/glossary.md`（块锚 `^slug`）、实体层 `docs/entities.md`
   （块锚 `^ent-<slug>`，刻意避开 `^term-` 命名空间）。
3. **建索引**：SQLite（FTS5）建在 `${PLUGIN_DATA}/index/`，随文件树签名失效即整库重建。
   **索引可随时删**：`rebuild` 是幂等全量重建，任何时刻删库都不会丢信息。
4. **写回**：`put()` 只改目标词条那一段的行，其余字节原样保留（`file` 是真相源）。

术语纪律（与词表 `glossary.md` 一致）：
- 声明形态 `规范名（别名1 / 别名2） — 一句话定义 ^锚`，别名用一个尾括号组、内部 ` / ` 分隔
  （与 check_wiki_docs.py 的 `term_alias_keys` 派生规则兼容）。
- 概念 slug 不得以 `ent-`/`term-` 开头；实体 slug 不得以 `term-` 开头，落盘时统一加
  `ent-` 前缀。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

# ── 与 check_wiki_docs.py 同语义的正则（只读复用语义，不改那份脚本）──────────

WIKILINK_RE = re.compile(r"(!?)\[\[([^\[\]]+?)\]\]")
MDLINK_RE = re.compile(r"\[([^\]]*)\]\(([^)\s]+\.md(?:#[^\)\s]*)?)\)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
BLOCKID_RE = re.compile(r"^(.*\S)\s+\^([A-Za-z0-9][A-Za-z0-9-]*)\s*$")
FENCE_RE = re.compile(r"^\s*(```+|~~~+)")
FRONTMATTER_KV_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$")
TERM_NAME_SEP_RE = re.compile(r"\s*[—–]\s*|\s*[:：]\s*")
ALIAS_SPLIT_RE = re.compile(r"\s+/\s+|\s*／\s*")

DOC_EXTENSIONS = {".md", ".markdown", ".html"}
CONCEPT_FILE = "glossary.md"
ENTITY_FILE = "entities.md"
ENTITY_ANCHOR_PREFIX = "ent-"
TERM_ANCHOR_PREFIX = "term-"
SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")

INDEX_VERSION = "1"


class VaultError(Exception):
    """vault 定位/读写失败：工具面转成可读错误，绝不吞掉。"""


# ── vault 根定位 ────────────────────────────────────────────────────


def plugin_data_dir() -> Path:
    for key in ("GLOSSARY_PLUGIN_DATA", "PLUGIN_DATA"):
        v = os.environ.get(key)
        if v:
            return Path(v)
    # 兜底：系统临时目录（本地调试用；引擎装载时恒注入 PLUGIN_DATA）。
    # 绝不回落到插件目录本身——那会在包内落索引，漂 digest。
    return Path(os.environ.get("TEMP") or "/tmp") / "visual-report-glossary-data"


def index_dir() -> Path:
    d = plugin_data_dir() / "index"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _as_docs_root(raw: str) -> Path | None:
    p = Path(raw).expanduser()
    if not p.is_dir():
        return None
    if p.name.lower() == "docs":
        return p
    nested = p / "docs"
    if nested.is_dir():
        return nested
    if (p / CONCEPT_FILE).is_file():
        return p
    return None


def resolve_vault(explicit: str | None = None) -> Path:
    """vault 根（= 项目工作区的 docs/ 目录）解析顺序：

    1. 工具参数 `vault`（可给 docs/ 目录，也可给工作区根——自动接上 `/docs`）
    2. 环境变量 `GLOSSARY_VAULT_ROOT`（mcp.json env 可设；不同项目各设一份）
    3. `${PLUGIN_DATA}/config.json` 的 `vaultRoot`（本机缺省值）
    4. 失败 → VaultError（点名三种配置途径）
    """
    tried: list[str] = []
    candidates: list[tuple[str, str]] = []
    if explicit:
        candidates.append(("arg vault", explicit))
    env = os.environ.get("GLOSSARY_VAULT_ROOT")
    if env:
        candidates.append(("env GLOSSARY_VAULT_ROOT", env))
    cfg_path = plugin_data_dir() / "config.json"
    if cfg_path.is_file():
        try:
            cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
            if isinstance(cfg.get("vaultRoot"), str):
                candidates.append((f"{cfg_path} vaultRoot", cfg["vaultRoot"]))
        except (OSError, ValueError):
            pass
    for label, raw in candidates:
        root = _as_docs_root(raw)
        if root is not None:
            return root
        tried.append(f"{label}={raw}")
    raise VaultError(
        "vault 根未定位：请用工具参数 `vault=` 指向项目 docs/ 目录，或设 env "
        f"GLOSSARY_VAULT_ROOT，或在 {cfg_path} 写 {{\"vaultRoot\": \"<工作区或 docs>\"}}"
        + ("；已尝试：" + "; ".join(tried) if tried else "")
    )


def vault_key(docs_root: Path) -> str:
    return hashlib.sha1(str(docs_root.resolve()).lower().encode("utf-8")).hexdigest()[:12]


def db_path(docs_root: Path) -> Path:
    return index_dir() / f"glossary-{vault_key(docs_root)}.db"


# ── 文档解析 ────────────────────────────────────────────────────────


def parse_frontmatter(lines: list[str]) -> tuple[dict, int]:
    if not lines or lines[0].strip() != "---":
        return {}, 0
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            fields = {}
            for raw in lines[1:i]:
                m = FRONTMATTER_KV_RE.match(raw)
                if m and m.group(2).strip().strip('"').strip("'"):
                    fields[m.group(1)] = m.group(2).strip().strip('"').strip("'")
            return fields, i + 1
    return {}, 0


def fenced_mask(lines: list[str]) -> list[bool]:
    mask = [False] * len(lines)
    opened, marker = False, ""
    for i, line in enumerate(lines):
        m = FENCE_RE.match(line)
        if m and not opened:
            opened, marker = True, m.group(1)[:3]
        elif m and opened and m.group(1).startswith(marker):
            opened, marker = False, ""
            mask[i] = True
            continue
        mask[i] = opened or bool(m)
    return mask


class Doc:
    __slots__ = ("rel", "frontmatter", "title", "headings", "blocks", "links", "body_lines")

    def __init__(self, rel: str):
        self.rel = rel
        self.frontmatter: dict = {}
        self.title = Path(rel).stem
        self.headings: list[tuple[str, int]] = []
        self.blocks: set[str] = set()
        self.links: list[tuple[str, str | None, str, int]] = []  # (target, anchor, kind, line)
        self.body_lines: list[tuple[int, str]] = []


def parse_doc(rel: str, text: str) -> Doc:
    doc = Doc(rel)
    lines = text.splitlines()
    fm, body_start = parse_frontmatter(lines)
    doc.frontmatter = fm
    mask = fenced_mask(lines)
    for i in range(body_start, len(lines)):
        line = lines[i]
        line_no = i + 1
        if mask[i]:
            continue
        doc.body_lines.append((line_no, line))
        hm = HEADING_RE.match(line)
        if hm:
            text_h = hm.group(2).strip()
            if text_h:
                doc.headings.append((text_h, line_no))
                if line.startswith("# "):
                    doc.title = text_h
            continue
        bm = BLOCKID_RE.match(line)
        if bm:
            doc.blocks.add(bm.group(2))
        for wm in WIKILINK_RE.finditer(line):
            inner = wm.group(2)
            target = inner.split("|")[0]
            anchor = None
            idx = target.find("#")
            if idx >= 0:
                anchor = target[idx + 1:].strip()
                target = target[:idx]
            target = target.strip()
            if target or anchor:
                doc.links.append((target, anchor, "embed" if wm.group(1) else "link", line_no))
        for mm in MDLINK_RE.finditer(line):
            dest = mm.group(2)
            anchor = None
            idx = dest.find("#")
            if idx >= 0:
                anchor = dest[idx + 1:].strip()
                dest = dest[:idx]
            doc.links.append((dest.strip(), anchor, "link", line_no))
    return doc


def solid_blocks(doc: Doc) -> list[dict]:
    """连续非空正文行组成一个块（遇空行/标题/围栏断组），带块锚信息。

    与 check_wiki_docs.py 的段落索引同构：块内末行 `… ^id` ⇒ 该块的锚。
    """
    out: list[dict] = []
    group: list[tuple[int, str]] = []

    def flush():
        if not group:
            return
        first_no = group[0][0]
        texts = [t for _n, t in group]
        joined = "\n".join(texts)
        bm = BLOCKID_RE.match(texts[-1])
        anchor = bm.group(2) if bm else None
        out.append({
            "line": first_no,
            "endLine": group[-1][0],
            "text": joined,
            "first": texts[0],
            "anchor": anchor,
            "lines": [t for _n, t in group],
        })
        group.clear()

    for line_no, text in doc.body_lines:
        if HEADING_RE.match(text):
            flush()
            continue
        if not text.strip():
            flush()
            continue
        group.append((line_no, text))
    flush()
    return out


def is_glossary_rel(rel: str) -> bool:
    return "glossary" in Path(rel).stem.lower()


def is_entities_rel(rel: str) -> bool:
    return Path(rel).stem.lower() == "entities"


def strip_anchor(text: str) -> str:
    return BLOCKID_RE.match(text).group(1) if BLOCKID_RE.match(text) else text


def split_name_definition(first_line: str) -> tuple[str, str]:
    """`规范名（别名） — 定义` → ("规范名（别名）", "定义")；无分隔符 → (first, "")。"""
    m = TERM_NAME_SEP_RE.search(first_line)
    if not m:
        return first_line.strip(), ""
    return first_line[: m.start()].strip(), first_line[m.end():].strip()


def alias_keys(name: str) -> list[str]:
    """尾括号组派生别名（与 check_wiki_docs.py term_alias_keys 同规则）+ 组内 ` / ` 拆开。"""
    t = name.strip()
    for close, open_ch in (("）", "（"), (")", "(")):
        if t.endswith(close) and t.rfind(open_ch) > 0:
            start = t.rfind(open_ch)
            head = t[:start].strip()
            inner = t[start + 1:-1].strip()
            keys = [head]
            keys.extend(p.strip() for p in ALIAS_SPLIT_RE.split(inner))
            return [k for k in keys if k and k != t]
    return []


def canonical_name(name: str) -> str:
    al = alias_keys(name)
    return al[0] if al else name.strip()


def clean_name(raw: str) -> str:
    return re.sub(r"^\s*(?:[-*+]|\d+\.)\s+", "", raw).strip().strip("*").strip()


# ── 词条抽取 ────────────────────────────────────────────────────────


def entries_of(rel: str, doc: Doc) -> tuple[list[dict], list[dict]]:
    """返回 (entries, bad_blocks)。entries 覆盖两层命名空间：

    - 概念层：词表文档（basename 含 glossary）里每个**带块锚**的块
    - 实体层：锚以 `ent-` 开头的块（任意文档；规范落点是 entities.md）
    - 文档内 `^term-*` 声明块不进 entries（它们是 R4 的注册面，由 validate 做命名空间检查）
    """
    entries: list[dict] = []
    bad: list[dict] = []
    for b in solid_blocks(doc):
        anchor = b["anchor"]
        if not anchor:
            continue
        kind = None
        slug = None
        if anchor.startswith(ENTITY_ANCHOR_PREFIX):
            kind = "entity"
            slug = anchor[len(ENTITY_ANCHOR_PREFIX):]
        elif is_glossary_rel(rel):
            kind = "concept"
            slug = anchor
        if kind is None:
            continue
        first_line = strip_anchor(b["lines"][0])
        name_raw, definition = split_name_definition(first_line)
        name = clean_name(name_raw)
        # 块内其余行（如 `来源：…`）归入 note，不进定义首句
        rest = b["lines"][1:]
        note = "\n".join(strip_anchor(x) for x in rest).strip()
        record = {
            "slug": slug,
            "kind": kind,
            "name": clean_name(canonical_name(name)),
            "raw_name": name,
            "aliases": alias_keys(name),
            "definition": definition,
            "note": note,
            "rel": rel,
            "line": b["line"],
            "endLine": b["endLine"],
            "anchor": anchor,
        }
        if not name or not definition:
            bad.append(record)
        entries.append(record)
    return entries, bad


def doc_term_blocks(rel: str, doc: Doc) -> list[dict]:
    """普通文档里的 `^term-*` 声明块（R4 注册面；不进 entries，只做命名空间检查）。"""
    out = []
    for b in solid_blocks(doc):
        a = b["anchor"]
        if not a or not a.startswith(TERM_ANCHOR_PREFIX):
            continue
        name_raw, _def = split_name_definition(strip_anchor(b["lines"][0]))
        name = clean_name(canonical_name(name_raw))
        if name:
            out.append({"name": name, "aliases": alias_keys(name_raw), "rel": rel,
                        "line": b["line"], "anchor": a})
    return out


# ── 链接解析（与 check_wiki_docs.py 四腿同构）────────────────────────

def normalize_target(target: str) -> str:
    t = target.replace("\\", "/").strip()
    while t.startswith("./"):
        t = t[2:]
    if not t.lower().endswith(".md") and "." not in t:
        t += ".md"
    return t


def _stem_key(name: str) -> str:
    b = name.replace("\\", "/").rsplit("/", 1)[-1]
    low = b.lower()
    for ext in (".markdown", ".md"):
        if low.endswith(ext):
            return b[: -len(ext)]
    return b


class Resolver:
    def __init__(self, doc_rels: list[str], attachments: list[str]):
        self.rels = set(doc_rels)
        self.by_lower = {r.lower(): r for r in doc_rels}
        self.by_base: dict[str, list[str]] = {}
        for r in doc_rels:
            self.by_base.setdefault(_stem_key(r).lower(), []).append(r)
        self.att = set(attachments)
        self.att_by_lower = {a.lower(): a for a in attachments}
        self.att_base: dict[str, list[str]] = {}
        for a in attachments:
            self.att_base.setdefault(_stem_key(a).lower(), []).append(a)

    def resolve(self, target: str, from_rel: str | None = None) -> str | None:
        if not target.strip():
            return from_rel
        t = normalize_target(target)
        if t in self.rels or t in self.att:
            return t
        low = t.lower()
        if low in self.by_lower:
            return self.by_lower[low]
        if low in self.att_by_lower:
            return self.att_by_lower[low]
        k = _stem_key(t).lower()
        m = self.by_base.get(k)
        if m and len(m) == 1:
            return m[0]
        m = self.att_base.get(k)
        if m and len(m) == 1:
            return m[0]
        return None


def select_term(key: str, terms: list[dict]) -> tuple[str, list[dict]]:
    """消解裁定（与 WikiIndex.selectTerm 同构）：词表唯一命中 > 唯一命中 > 多命中不猜。"""
    k = key.strip().lower()
    if not k:
        return ("notfound", [])
    hits = [t for t in terms if k in [x.lower() for x in [t["name"], *t["aliases"]]]]
    gloss = [t for t in hits if t["kind"] == "concept"]
    if len(gloss) == 1:
        return ("resolved", gloss)
    if len(gloss) > 1:
        return ("ambiguous", gloss)
    if len(hits) == 1:
        return ("resolved", hits)
    if len(hits) > 1:
        return ("ambiguous", hits)
    return ("notfound", [])


def _term_target_key(target: str) -> str:
    low = normalize_target(target).lower()
    if low.endswith(".markdown"):
        return normalize_target(target)[:-9]
    if low.endswith(".md"):
        return normalize_target(target)[:-3]
    return normalize_target(target)


# ── 索引 ────────────────────────────────────────────────────────────


def scan_tree(docs_root: Path) -> tuple[list[str], list[str], tuple]:
    """返回 (doc_rels, attachments, signature)。assets/ 与 check_wiki_docs 默认排除一致。"""
    doc_rels: list[str] = []
    atts: list[str] = []
    count = 0
    total = 0
    max_mtime = 0
    for dirpath, dirnames, filenames in os.walk(docs_root):
        dirnames.sort()
        rel_dir = os.path.relpath(dirpath, docs_root).replace("\\", "/")
        if rel_dir == ".":
            rel_dir = ""
        if rel_dir == "assets" or rel_dir.startswith("assets/"):
            dirnames[:] = []
            continue
        for name in sorted(filenames):
            p = Path(dirpath) / name
            rel = f"{rel_dir}/{name}" if rel_dir else name
            try:
                st = p.stat()
            except OSError:
                continue
            count += 1
            total += st.st_size
            max_mtime = max(max_mtime, st.st_mtime_ns)
            if p.suffix.lower() in DOC_EXTENSIONS:
                doc_rels.append(rel)
            else:
                atts.append(rel)
    return doc_rels, atts, (count, total, max_mtime)


def _read_text(p: Path) -> str | None:
    try:
        return p.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def _write_lf(p: Path, text: str) -> None:
    """写盘强制 LF：vault 里既有文档一律 LF，别让 Windows 的换行翻译把新文件写成 CRLF。"""
    with open(p, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def _fts_tokens(text: str) -> str:
    """CJK 逐字 + 拉丁/数字词 → FTS5(unicode61) 的短语命中即子串命中（中文检索的关键）。"""
    out: list[str] = []
    buf: list[str] = []
    for ch in text.lower():
        if "\u3400" <= ch <= "\u9fff" or "\uf900" <= ch <= "\ufaff":
            if buf:
                out.append("".join(buf))
                buf = []
            out.append(ch)
        elif ch.isalnum() or ch in "._-+#":
            buf.append(ch)
        else:
            if buf:
                out.append("".join(buf))
                buf = []
    if buf:
        out.append("".join(buf))
    return " ".join(out)


def _fts_query(query: str) -> str:
    toks = _fts_tokens(query).split()
    if not toks:
        return ""
    return '"' + " ".join(toks) + '"'


SCHEMA = """
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE entries(
  slug TEXT PRIMARY KEY, kind TEXT, name TEXT, raw_name TEXT, aliases TEXT,
  definition TEXT, note TEXT, rel TEXT, line INTEGER, endLine INTEGER, anchor TEXT,
  valid INTEGER);
CREATE TABLE docs(rel TEXT PRIMARY KEY, title TEXT, type TEXT, status TEXT,
  lines INTEGER, headings TEXT);
CREATE TABLE refs(
  from_rel TEXT, line INTEGER, target TEXT, anchor TEXT, kind TEXT,
  to_rel TEXT, to_anchor TEXT, to_slug TEXT, status TEXT);
CREATE VIRTUAL TABLE fts_text USING fts5(slug UNINDEXED, kind UNINDEXED, body);
CREATE INDEX refs_slug ON refs(to_slug);
CREATE INDEX refs_from ON refs(from_rel);
"""


class Vault:
    """一次工具调用的 vault 句柄：定位 + 索引新鲜度 + 查询/写入面。"""

    def __init__(self, docs_root: Path):
        self.root = docs_root
        self.db = db_path(docs_root)

    # ── 索引生命期 ──────────────────────────────────────────────

    def ensure_index(self, force: bool = False) -> dict:
        doc_rels, atts, sig = scan_tree(self.root)
        if force or not self.db.is_file():
            return self._build(doc_rels, atts, sig)
        try:
            con = sqlite3.connect(self.db)
            try:
                row = con.execute("SELECT v FROM meta WHERE k='signature'").fetchone()
                ver = con.execute("SELECT v FROM meta WHERE k='index_version'").fetchone()
            finally:
                con.close()
        except sqlite3.DatabaseError:
            return self._build(doc_rels, atts, sig)
        if not row or not ver or ver[0] != INDEX_VERSION or json.loads(row[0]) != list(sig):
            return self._build(doc_rels, atts, sig)
        return {"rebuilt": False, "docs": len(doc_rels), "attachments": len(atts)}

    def _build(self, doc_rels, atts, sig) -> dict:
        import time

        t0 = time.perf_counter()
        tmp = self.db.with_suffix(".tmp")
        if tmp.exists():
            tmp.unlink()
        con = sqlite3.connect(tmp)
        try:
            con.executescript(SCHEMA)
            docs: dict[str, Doc] = {}
            entries: list[dict] = []
            term_blocks: list[dict] = []
            for rel in doc_rels:
                text = _read_text(self.root / rel)
                if text is None:
                    continue
                doc = parse_doc(rel, text)
                docs[rel] = doc
                es, _bad = entries_of(rel, doc)
                entries.extend(es)
                term_blocks.extend(doc_term_blocks(rel, doc))
            resolver = Resolver(list(docs.keys()), atts)
            # 词条注册表（概念 > 实体 > 文档内 ^term-*），供短形引用第三优先级回退
            registry = list(entries) + [dict(t, kind="doc-term", slug=None) for t in term_blocks]

            # 重复 slug 时保留先出现者，重复项记进 meta 供 validate 报（slug 是 PK，否则 insert 就炸）
            seen_slug: dict[str, dict] = {}
            dup_slugs: list[str] = []
            for e in entries:
                if e["slug"] in seen_slug:
                    dup_slugs.append(f"{e['slug']}@{e['rel']}:{e['line']}")
                    continue
                seen_slug[e["slug"]] = e

            for rel, doc in docs.items():
                con.execute(
                    "INSERT INTO docs VALUES(?,?,?,?,?,?)",
                    (rel, doc.title, doc.frontmatter.get("type", ""), doc.frontmatter.get("status", ""),
                     len(doc.body_lines), json.dumps([h for h, _ in doc.headings][:12], ensure_ascii=False)),
                )
            for slug, e in seen_slug.items():
                con.execute(
                    "INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (slug, e["kind"], e["name"], e["raw_name"],
                     json.dumps(e["aliases"], ensure_ascii=False), e["definition"], e["note"],
                     e["rel"], e["line"], e["endLine"], e["anchor"],
                     1 if (e["name"] and e["definition"]) else 0),
                )
                body = _fts_tokens(" ".join([e["name"], e["raw_name"], " ".join(e["aliases"]),
                                             e["definition"], e["note"], slug]))
                con.execute("INSERT INTO fts_text(slug, kind, body) VALUES(?,?,?)",
                            (slug, e["kind"], body))
            for rel, doc in docs.items():
                for target, anchor, kind, line_no in doc.links:
                    resolved = resolver.resolve(target, from_rel=rel)
                    to_slug = None
                    to_anchor = None
                    status = "resolved"
                    if resolved is None:
                        hit = None
                        if anchor is None:
                            v, hits = select_term(_term_target_key(target), registry)
                            if v == "resolved":
                                hit = hits[0]
                            elif v == "ambiguous":
                                status = "ambiguous"
                        if hit is not None:
                            resolved = hit["rel"]
                            to_slug = hit["slug"]
                            to_anchor = "#^" + hit["anchor"]
                            status = "resolved"
                        else:
                            status = status if status == "ambiguous" else "unresolved"
                    else:
                        if anchor:
                            to_anchor = "#" + anchor
                            if anchor.startswith("^"):
                                blk = anchor[1:]
                                if blk in docs.get(resolved, Doc(resolved)).blocks:
                                    to_slug = blk[len(ENTITY_ANCHOR_PREFIX):] if blk.startswith(ENTITY_ANCHOR_PREFIX) else blk
                                    if to_slug not in seen_slug:
                                        to_slug = None
                                else:
                                    status = "broken-block"
                            else:
                                heads = {h.lower() for h, _ in docs.get(resolved, Doc(resolved)).headings}
                                if anchor.lstrip("#").strip().lower() not in heads:
                                    status = "broken-anchor"
                    con.execute(
                        "INSERT INTO refs VALUES(?,?,?,?,?,?,?,?,?)",
                        (rel, line_no, target, anchor, kind, resolved, to_anchor, to_slug, status))
            con.execute("INSERT INTO meta VALUES('signature', ?)", (json.dumps(list(sig)),))
            con.execute("INSERT INTO meta VALUES('index_version', ?)", (INDEX_VERSION,))
            con.execute("INSERT INTO meta VALUES('vault', ?)", (str(self.root),))
            con.execute("INSERT INTO meta VALUES('dup_slugs', ?)", (json.dumps(dup_slugs, ensure_ascii=False),))
            con.execute("INSERT INTO meta VALUES('built_at', ?)",
                        (json.dumps(__import__("time").strftime("%Y-%m-%dT%H:%M:%S")),))
            con.commit()
        finally:
            con.close()
        shutil.move(str(tmp), str(self.db))
        return {"rebuilt": True, "docs": len(doc_rels), "attachments": len(atts),
                "elapsed_ms": round((time.perf_counter() - t0) * 1000, 1)}

    def connect(self):
        self.ensure_index()
        return sqlite3.connect(self.db)

    # ── 读面 ────────────────────────────────────────────────────

    def search(self, query: str, limit: int = 10, kind: str | None = None) -> list[dict]:
        import difflib

        q = (query or "").strip()
        if not q:
            return []
        con = self.connect()
        try:
            rows = con.execute(
                "SELECT slug, kind, name, raw_name, aliases, definition, rel, line, anchor FROM entries"
                + (" WHERE kind=?" if kind else ""),
                ((kind,) if kind else ()),
            ).fetchall()
            refcount = dict(con.execute(
                "SELECT to_slug, COUNT(*) FROM refs WHERE to_slug IS NOT NULL GROUP BY to_slug"
            ).fetchall())
        finally:
            con.close()
        ql = q.lower()
        scored: list[tuple[float, dict]] = []
        fuzzy = difflib.SequenceMatcher()
        for slug, k, name, raw, aliases_j, definition, rel, line, anchor in rows:
            aliases = json.loads(aliases_j or "[]")
            hay = " ".join([name, raw, slug, " ".join(aliases), definition]).lower()
            score = 0.0
            where = ""
            if ql == name.lower() or ql == slug.lower():
                score, where = 100.0, "name"
            elif ql in [a.lower() for a in aliases]:
                score, where = 95.0, "alias"
            elif ql in name.lower() or ql in slug.lower():
                score, where = 85.0, "name"
            elif any(ql in a.lower() for a in aliases):
                score, where = 80.0, "alias"
            elif ql in (definition or "").lower():
                score, where = 70.0, "definition"
            else:
                fuzzy.set_seq2(ql)
                best = 0.0
                for cand in [name.lower(), *(a.lower() for a in aliases)]:
                    fuzzy.set_seq1(cand)
                    if fuzzy.real_quick_ratio() < 0.6:
                        continue
                    best = max(best, fuzzy.ratio())
                if best >= 0.6:
                    score, where = 55.0 * best, "fuzzy"
            if score <= 0:
                # FTS 命中（CJK 逐字子串）兜底
                continue
            scored.append((score, {
                "slug": slug, "kind": k, "name": name,
                "aliases": aliases,
                "definition": definition,
                "hit": where,
                "snippet": _snippet(hay if where in ("fuzzy",) else
                                    (definition if where == "definition" else " ".join([name, " / ".join(aliases), definition])), ql),
                "source": f"{rel}:{line}",
                "anchor": f"[[{Path(rel).stem}#^{anchor}]]",
                "backlinks": refcount.get(slug, 0),
            }))
        scored.sort(key=lambda x: (-x[0], x[1]["slug"]))
        if len(scored) < limit:
            scored.extend(self._fts_fallback(ql, kind, {s[1]["slug"] for s in scored}, refcount, limit - len(scored)))
        return [d for _s, d in scored[:limit]]

    def _fts_fallback(self, ql, kind, exclude, refcount, limit):
        if limit <= 0:
            return []
        con = self.connect()
        try:
            fq = _fts_query(ql)
            if not fq:
                return []
            try:
                rows = con.execute(
                    "SELECT e.slug, e.kind, e.name, e.aliases, e.definition, e.rel, e.line, e.anchor "
                    "FROM fts_text f JOIN entries e ON e.slug=f.slug WHERE fts_text MATCH ?"
                    + (" AND e.kind=?" if kind else "") + " ORDER BY rank LIMIT ?",
                    ((fq,) + ((kind,) if kind else ()) + (limit * 2,)),
                ).fetchall()
            except sqlite3.OperationalError:
                return []
        finally:
            con.close()
        out = []
        for slug, k, name, aliases_j, definition, rel, line, anchor in rows:
            if slug in exclude:
                continue
            out.append((50.0, {
                "slug": slug, "kind": k, "name": name,
                "aliases": json.loads(aliases_j or "[]"),
                "definition": definition,
                "hit": "fulltext",
                "snippet": _snippet(definition or name, ql),
                "source": f"{rel}:{line}",
                "anchor": f"[[{Path(rel).stem}#^{anchor}]]",
                "backlinks": refcount.get(slug, 0),
            }))
            if len(out) >= limit:
                break
        return out

    def find_entry(self, key: str) -> dict | None:
        """slug 精确 → name/alias 精确（唯一命中才给，多命中不猜）。"""
        con = self.connect()
        try:
            row = con.execute(
                "SELECT slug, kind, name, raw_name, aliases, definition, note, rel, line, endLine, anchor "
                "FROM entries WHERE slug=?", (key.strip().lstrip("^"),)).fetchone()
            if row:
                return _entry_dict(row)
            v, hits = select_term(key, self._all_terms(con))
            return _entry_dict_from_term(con, hits[0]) if v == "resolved" else None
        finally:
            con.close()

    def _all_terms(self, con) -> list[dict]:
        out = []
        for slug, kind, name, raw, aliases_j, definition, note, rel, line, endline, anchor in con.execute(
            "SELECT slug, kind, name, raw_name, aliases, definition, note, rel, line, endLine, anchor FROM entries"
        ):
            out.append({"slug": slug, "kind": kind, "name": name,
                        "aliases": json.loads(aliases_j or "[]"), "rel": rel,
                        "line": line, "anchor": anchor, "definition": definition,
                        "note": note, "raw_name": raw, "endLine": endline})
        return out

    def backlinks(self, slug: str) -> list[dict]:
        con = self.connect()
        try:
            return [
                {"doc": r[0], "line": r[1], "via": r[2], "anchor": r[3], "kind": r[4]}
                for r in con.execute(
                    "SELECT from_rel, line, target, to_anchor, kind FROM refs "
                    "WHERE to_slug=? ORDER BY from_rel, line", (slug,))
            ]
        finally:
            con.close()

    def doc_refs(self, doc_key: str) -> dict:
        con = self.connect()
        try:
            doc_rels = [r[0] for r in con.execute("SELECT rel FROM docs")]
            resolver = Resolver(doc_rels, [])
            rel = resolver.resolve(doc_key)
            if rel is None:
                rel = resolver.resolve(Path(doc_key).name)
            if rel is None:
                hits = [r for r in doc_rels if doc_key.lower() in r.lower()]
                rel = hits[0] if len(hits) == 1 else None
            if rel is None:
                return {"doc": None, "error": f"文档未在 vault 内定位：{doc_key}"}
            used = [
                {"slug": r[0], "name": r[1], "kind": r[2], "line": r[3], "via": r[4]}
                for r in con.execute(
                    "SELECT f.to_slug, e.name, e.kind, f.line, f.target FROM refs f "
                    "LEFT JOIN entries e ON e.slug=f.to_slug "
                    "WHERE f.from_rel=? AND f.to_slug IS NOT NULL ORDER BY f.line", (rel,))
            ]
            outlinks = [
                {"line": r[0], "target": r[1], "to": r[2], "status": r[3]}
                for r in con.execute(
                    "SELECT line, target, to_rel, status FROM refs WHERE from_rel=? ORDER BY line", (rel,))
            ]
            return {"doc": rel, "entries": used, "links": outlinks}
        finally:
            con.close()

    def stats(self) -> dict:
        con = self.connect()
        try:
            docs = con.execute("SELECT COUNT(*) FROM docs").fetchone()[0]
            by_kind = dict(con.execute("SELECT kind, COUNT(*) FROM entries GROUP BY kind").fetchall())
            refs = con.execute("SELECT COUNT(*) FROM refs").fetchone()[0]
            unresolved = con.execute("SELECT COUNT(*) FROM refs WHERE status IN ('unresolved','ambiguous')").fetchone()[0]
            built = con.execute("SELECT v FROM meta WHERE k='built_at'").fetchone()
            return {"vault": str(self.root), "index": str(self.db), "docs": docs,
                    "entries": by_kind, "refs": refs, "unresolvedRefs": unresolved,
                    "builtAt": json.loads(built[0]) if built else None}
        finally:
            con.close()

    # ── 写面（唯一真相 = markdown）───────────────────────────────

    def put(self, slug: str, kind: str, name: str, definition: str,
            aliases: list[str] | None = None, source: str | None = None) -> dict:
        slug = (slug or "").strip().lstrip("^")
        kind = (kind or "").strip().lower()
        if kind not in ("concept", "entity"):
            raise VaultError("kind 只能是 concept 或 entity（两层分开是设计裁决，不合并）")
        if kind == "entity" and slug.startswith(ENTITY_ANCHOR_PREFIX):
            slug = slug[len(ENTITY_ANCHOR_PREFIX):]
        if not SLUG_RE.match(slug):
            raise VaultError(f"slug '{slug}' 非法：只允许小写字母/数字/连字符，且首字符为字母或数字")
        if kind == "concept" and (slug.startswith(ENTITY_ANCHOR_PREFIX) or slug.startswith(TERM_ANCHOR_PREFIX)):
            raise VaultError("概念 slug 不得占用 `ent-`/`term-` 命名空间（实体层专用）")
        if kind == "entity" and slug.startswith(TERM_ANCHOR_PREFIX):
            raise VaultError("实体 slug 不得占用 `term-` 命名空间（校验器 R4 的注册面）")
        name = clean_name(name or "")
        definition = " ".join((definition or "").split())
        if not name:
            raise VaultError("name 不能为空")
        if not definition:
            raise VaultError("definition 不能为空（词条必须有「一句话定义」）")
        al = [a.strip() for a in (aliases or []) if a and a.strip() and a.strip() != name]
        anchor = slug if kind == "concept" else ENTITY_ANCHOR_PREFIX + slug
        target = self.root / (CONCEPT_FILE if kind == "concept" else ENTITY_FILE)
        decl_name = f"{name}（{' / '.join(al)}）" if al else name
        new_lines = [f"{decl_name} — {definition}"]
        if source:
            new_lines.append(f"来源：{source}")
        new_lines[-1] = new_lines[-1] + f" ^{anchor}"

        created_file = False
        if not target.is_file():
            if kind == "concept":
                raise VaultError(f"词表不存在：{target}（概念层只能写进既有词表，不自动新建）")
            _write_lf(target, _entities_skeleton())
            created_file = True

        text = target.read_text(encoding="utf-8")
        lines = text.splitlines()
        doc = parse_doc(target.name, text)
        block_span = _find_block_span(doc, anchor)
        if block_span:
            start, end = block_span
            # 已有同锚词条 → 只替换该块的行（其余字节原样）
            lines[start - 1:end] = new_lines
            action = "updated"
        else:
            # 追加到「词条区」末尾：文档末尾非空行之后插空行 + 新块
            while lines and not lines[-1].strip():
                lines.pop()
            if lines:
                lines.append("")
            lines.extend(new_lines)
            lines.append("")
            action = "created"

        tmp = target.with_suffix(".md.tmp")
        _write_lf(tmp, "\n".join(lines) + "\n")
        os.replace(tmp, target)
        rebuild = self.ensure_index(force=True)
        return {"ok": True, "action": action, "slug": slug, "kind": kind,
                "file": f"{target.parent.name}/{target.name}",
                "anchor": f"^{anchor}",
                "line": (block_span[0] if block_span else len(lines) - 1),
                "createdFile": created_file,
                "indexRebuilt": rebuild,
                "hint": "文件已落盘 = 真相源已更新；索引随之全量重建（可随时删索引，不影响内容）"}

    # ── 一致性检查 ─────────────────────────────────────────────

    def validate(self) -> dict:
        """一致性检查 = 条款校验器（R1-R4 权威，不改它的判定）+ 词条层新增两项。

        新增（本 MCP 面独有，校验器不含）：
        - `orphan-term`：词条定义存在但全库零引用（概念层与实体层都查）
        - `namespace-mixed`：两层命名空间混用（概念/实体同名、`^ent-*` 落到登记表外、
          `^term-*` 声明块与实体同名、同 slug 两层并存）
        """
        checker = _checker_path()
        base: dict = {"checker": str(checker), "checkerAvailable": checker.is_file()}
        violations: list[dict] = []
        if checker.is_file():
            try:
                proc = subprocess.run(
                    [sys.executable, "-X", "utf8", str(checker), "--root", str(self.root), "--json"],
                    capture_output=True, text=True, encoding="utf-8", timeout=300,
                )
                payload = json.loads(proc.stdout) if proc.stdout.strip() else {"violations": []}
                violations = payload.get("violations", [])
                base["checkerExit"] = proc.returncode
                base["checkerStderr"] = (proc.stderr or "").strip()[-800:]
            except (subprocess.SubprocessError, ValueError) as exc:  # pragma: no cover
                base["checkerError"] = f"{type(exc).__name__}: {exc}"
        else:
            base["checkerError"] = f"校验器不在位：{checker}（R1-R4 面子项跳检）"

        con = self.connect()
        try:
            entries = self._all_terms(con)
            doc_terms = []
            doc_rels = [r[0] for r in con.execute("SELECT rel FROM docs")]
            for rel in doc_rels:
                p = self.root / rel
                text = _read_text(p)
                if text is None:
                    continue
                doc_terms.extend(doc_term_blocks(rel, parse_doc(rel, text)))
            refcount = dict(con.execute(
                "SELECT to_slug, COUNT(*) FROM refs WHERE to_slug IS NOT NULL GROUP BY to_slug"
            ).fetchall())
            dup_row = con.execute("SELECT v FROM meta WHERE k='dup_slugs'").fetchone()
        finally:
            con.close()
        dup_slugs = json.loads(dup_row[0]) if dup_row else []

        extra: list[dict] = []
        for e in entries:
            if not refcount.get(e["slug"]):
                extra.append({"rule": "orphan-term", "file": e["rel"], "line": e["line"],
                              "detail": f"词条「{e['name']}」({e['kind']}, ^{e['anchor']}) 定义存在但全库零引用"
                                        f"——挂进正文引用或删条目（[[{Path(e['rel']).stem}#^{e['anchor']}]]）"})
        concept_names = {e["name"].lower(): e for e in entries if e["kind"] == "concept"}
        entity_names = {e["name"].lower(): e for e in entries if e["kind"] == "entity"}
        for key in sorted(set(concept_names) & set(entity_names)):
            c, en = concept_names[key], entity_names[key]
            extra.append({"rule": "namespace-mixed", "file": c["rel"], "line": c["line"],
                          "detail": f"「{c['name']}」同时是概念词条（{c['rel']}:{c['line']} ^"
                                    f"{c['anchor']}）与实体词条（{en['rel']}:{en['line']} ^"
                                    f"{en['anchor']}）——两层命名空间不得交叉"})
        for e in entries:
            if e["kind"] == "entity" and not is_entities_rel(e["rel"]):
                extra.append({"rule": "namespace-mixed", "file": e["rel"], "line": e["line"],
                              "detail": f"实体条目 ^{e['anchor']} 落在 {e['rel']}——实体规范落点是 "
                                        f"docs/{ENTITY_FILE}"})
            if e["kind"] == "concept" and not is_glossary_rel(e["rel"]):
                extra.append({"rule": "namespace-mixed", "file": e["rel"], "line": e["line"],
                              "detail": f"概念条目 ^{e['anchor']} 落在 {e['rel']}——概念规范落点是 "
                                        f"docs/{CONCEPT_FILE}"})
        for t in doc_terms:
            if t["name"].lower() in entity_names:
                extra.append({"rule": "namespace-mixed", "file": t["rel"], "line": t["line"],
                              "detail": f"`^{t['anchor']}` 声明的「{t['name']}」与实体条目同名——"
                                        f"实体用 ^ent-*，概念/文档词条用 ^term-* 或词表锚，二者不得混用"})
        for dup_line in dup_slugs:
            extra.append({"rule": "namespace-mixed", "file": CONCEPT_FILE, "line": 1,
                          "detail": f"slug 在词条层重复声明（{dup_line}）——块锚必须唯一，"
                                    f"同 slug 只保留先出现的那条"})

        allv = violations + extra
        by_rule: dict[str, int] = {}
        for v in allv:
            by_rule[v["rule"]] = by_rule.get(v["rule"], 0) + 1
        order = ["obsidian-format", "citation-graph", "concept-first-use", "term-registry",
                 "broken-link", "broken-block", "broken-anchor", "orphan-doc",
                 "glossary-format", "term-format", "term-duplicate", "duplicate-definition",
                 "orphan-term", "namespace-mixed"]
        allv.sort(key=lambda v: (order.index(v["rule"]) if v["rule"] in order else 99,
                                 v["file"], v["line"]))

        def bucket(name):
            return sum(1 for v in violations if v["rule"] == name)

        return {
            **base,
            "root": str(self.root),
            "total": len(allv),
            "checkerViolations": len(violations),
            "addedByGlossaryLayer": len(extra),
            "countsByBucket": {
                "broken-link": bucket("broken-link") + bucket("broken-block") + bucket("broken-anchor"),
                "missing-frontmatter": bucket("obsidian-format"),
                "concept-first-use": bucket("concept-first-use") + bucket("duplicate-definition"),
                "orphan-doc": bucket("orphan-doc"),
                "term-format": bucket("term-format") + bucket("term-duplicate") + bucket("glossary-format"),
                "orphan-term": sum(1 for v in extra if v["rule"] == "orphan-term"),
                "namespace-mixed": sum(1 for v in extra if v["rule"] == "namespace-mixed"),
            },
            "violations": allv,
        }

    def rebuild(self) -> dict:
        return self.ensure_index(force=True)


# ── 小工具 ──────────────────────────────────────────────────────────


def _snippet(text: str, needle: str, width: int = 90) -> str:
    text = " ".join((text or "").split())
    if not text:
        return ""
    idx = text.lower().find(needle)
    if idx < 0:
        return text[:width] + ("…" if len(text) > width else "")
    start = max(0, idx - width // 3)
    end = min(len(text), idx + len(needle) + width)
    return ("…" if start else "") + text[start:end] + ("…" if end < len(text) else "")


def _find_block_span(doc: Doc, anchor: str) -> tuple[int, int] | None:
    for b in solid_blocks(doc):
        if b["anchor"] == anchor:
            return (b["line"], b["endLine"])
    return None


def _entry_dict(row) -> dict:
    slug, kind, name, raw, aliases_j, definition, note, rel, line, endline, anchor = row
    return {"slug": slug, "kind": kind, "name": name, "rawName": raw,
            "aliases": json.loads(aliases_j or "[]"), "definition": definition,
            "note": note, "source": f"{rel}:{line}", "file": rel, "line": line,
            "endLine": endline, "anchor": f"[[{Path(rel).stem}#^{anchor}]]"}


def _entry_dict_from_term(con, term: dict) -> dict | None:
    if not term.get("slug"):
        return None
    row = con.execute(
        "SELECT slug, kind, name, raw_name, aliases, definition, note, rel, line, endLine, anchor "
        "FROM entries WHERE slug=?", (term["slug"],)).fetchone()
    return _entry_dict(row) if row else None


def _checker_path() -> Path:
    env = os.environ.get("GLOSSARY_CHECKER")
    if env:
        return Path(env)
    root = os.environ.get("PLUGIN_ROOT")
    base = Path(root) if root else Path(__file__).resolve().parent.parent
    return base / "skills" / "wiki-docs" / "scripts" / "check_wiki_docs.py"


def _entities_skeleton() -> str:
    return (
        "---\n"
        "type: entities\n"
        "status: current\n"
        "---\n"
        "\n"
        "# 实体登记表\n"
        "\n"
        "长期存在的具名对象（人名/机构/系统/产品）的**身份声明面**：一条 = 一个段落块锚 "
        "`^ent-<slug>`，形态「规范名（别名1 / 别名2） — 一句话身份 ^ent-<slug>」。"
        "本表与概念词表**分两层**、命名空间不交叉（`^ent-*` vs `^<slug>`），"
        "概念定义只在 [[glossary#^vault|词表]] 那边。\n"
    )

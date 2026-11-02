#!/usr/bin/env python3
"""glossary_server — visual-report 插件的 MCP 面（stdio，JSON-RPC 2.0 逐行）。

装载：`<插件根>/mcp.json`（装载器 `PluginRegistry.scala:784-794` 读插件根 mcp.json，
`PluginMcpManager.acquire` 启动本进程，serverId = `plugin_visual-report_glossary`）。
本进程只依赖 Python 3 标准库（sqlite3 自带 FTS5），不装任何三方包。

真相源纪律：markdown 里的块锚是权威，`${PLUGIN_DATA}/index/*.db` 只是可删的检索缓存
（见 glossary_vault.py 头注）。所有写操作都走 markdown 文件。

协议：MCP 2025-06-18，newline-delimited JSON（引擎 `StdioTransport` 逐行读写，
**响应 id 必须逐字回显**——数字 id 回字符串会让引擎读不到）。stdout 只写协议帧，
日志一律走 stderr（引擎把 stderr 转进 nebflow.mcp.stdio 日志面）。
"""

from __future__ import annotations

import json
import os
import sys
import traceback
from pathlib import Path

# 插件目录纪律：绝不在包内落 __pycache__（那会漂 digest、把「内容已变更」刷出来）。
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import glossary_vault as gv  # noqa: E402

SERVER_NAME = "visual-report-glossary"
SERVER_VERSION = "1.0.0"
DEFAULT_PROTOCOL = "2025-06-18"

VAULT_PARAM = {
    "type": "string",
    "description": "可选：目标 vault（项目工作区的 docs/ 目录，或工作区根——自动接 /docs）。"
                   "缺省用 env GLOSSARY_VAULT_ROOT / ${PLUGIN_DATA}/config.json 的 vaultRoot。",
}

TOOLS = [
    {
        "name": "glossary_search",
        "description": (
            "检索 vault 里的词条（概念层 docs/glossary.md 的 ^slug 与实体层 docs/entities.md 的 "
            "^ent-<slug>）。返回 slug、规范名、一句话定义、别名、命中片段、来源 file:line 与反向"
            "引用计数。用于「这个词我们怎么定义的 / 库里有没有这个词条」。中文按子串匹配，"
            "英文按词匹配，另有模糊兜底。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "检索词：规范名、别名、slug 或定义里的片段"},
                "limit": {"type": "integer", "description": "返回条数上限（默认 10，上限 50）", "minimum": 1, "maximum": 50},
                "kind": {"type": "string", "enum": ["concept", "entity"], "description": "只查某一层"},
                "vault": VAULT_PARAM,
            },
            "required": ["query"],
        },
    },
    {
        "name": "glossary_lookup",
        "description": (
            "精确取一条词条（slug 或规范名/别名唯一命中）+ 它被哪些文档引用（反向链接，带 "
            "file:line 与引用形态）。多命中不猜，回候选列表。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "slug": {"type": "string", "description": "词条 slug、块锚（^slug / ^ent-slug）或规范名/别名"},
                "vault": VAULT_PARAM,
            },
            "required": ["slug"],
        },
    },
    {
        "name": "glossary_put",
        "description": (
            "新建或更新一条词条——**写进 markdown 文件**（概念→docs/glossary.md 的 ^slug，"
            "实体→docs/entities.md 的 ^ent-<slug>），不写数据库。同 slug 已存在则只替换那一段，"
            "其余字节原样保留；实体表不存在时按规范骨架新建。写完索引自动全量重建。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "slug": {"type": "string", "description": "kebab-case slug（小写字母/数字/连字符）；实体自动加 ent- 前缀"},
                "kind": {"type": "string", "enum": ["concept", "entity"], "description": "concept = 概念层（词表），entity = 实体层（登记表）"},
                "name": {"type": "string", "description": "规范名（唯一、稳定）"},
                "definition": {"type": "string", "description": "一句话定义/身份（不超过一句）"},
                "aliases": {"type": "array", "items": {"type": "string"}, "description": "别名集（写进同一个尾括号组，` / ` 分隔）"},
                "source": {"type": "string", "description": "定义来源（文档路径/URL/证据锚），落成词条块第二行「来源：…」"},
                "vault": VAULT_PARAM,
            },
            "required": ["slug", "kind", "name", "definition"],
        },
    },
    {
        "name": "glossary_links",
        "description": (
            "双向查询引用关系：给 slug → 哪些文档引用了它（含直引 ^slug 与 [[名]] 短形引用）；"
            "给文档路径 → 这篇文档用了哪些词条，并列出全部出链及其解析状态。二选一。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "slug": {"type": "string", "description": "词条 slug / 块锚 / 规范名"},
                "doc": {"type": "string", "description": "文档路径（vault 相对路径、绝对路径或唯一 basename）"},
                "vault": VAULT_PARAM,
            },
        },
    },
    {
        "name": "glossary_validate",
        "description": (
            "跑词条层一致性检查，输出可定位到 file:line 的清单：① 权威条款校验器 R1-R4（直接调用 "
            "skills/wiki-docs/scripts/check_wiki_docs.py --json，不改它的任何判定）——断链/缺 "
            "front-matter/概念首现/孤儿文档/词条格式；② 本层新增两项——孤儿词条（定义存在但零引用）"
            "与两层命名空间混用（概念/实体同名、实体条目落到登记表外等）。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"vault": VAULT_PARAM},
        },
    },
    {
        "name": "glossary_reindex",
        "description": (
            "强制重建索引（正常不需手动跑：文件树签名一变，下一次工具调用自动重建；"
            "索引可随时删，删了下次调用自动重建）。返回重建耗时与条目统计。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"vault": VAULT_PARAM},
        },
    },
]


# ── 工具实现 ────────────────────────────────────────────────────────


def _vault(args: dict) -> gv.Vault:
    return gv.Vault(gv.resolve_vault(args.get("vault")))


def _as_json(payload) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=1)


def tool_search(args: dict) -> str:
    v = _vault(args)
    info = v.ensure_index()
    limit = min(int(args.get("limit") or 10), 50)
    kind = args.get("kind") or None
    hits = v.search(args.get("query", ""), limit=limit, kind=kind)
    return _as_json({
        "query": args.get("query", ""),
        "vault": str(v.root),
        "indexRebuilt": info,
        "count": len(hits),
        "results": hits,
        "hint": "查到了但要看上下文？glossary_lookup(slug) 给完整定义 + 反向链接。",
    })


def tool_lookup(args: dict) -> str:
    v = _vault(args)
    v.ensure_index()
    key = args.get("slug") or args.get("key") or ""
    if not key.strip():
        return _as_json({"error": "slug 不能为空"})
    entry = v.find_entry(key)
    if entry is None:
        near = v.search(key, limit=5)
        return _as_json({
            "found": False,
            "key": key,
            "candidates": [{"slug": h["slug"], "name": h["name"], "kind": h["kind"]} for h in near],
            "hint": "未命中；多命中不猜。用 glossary_search 或 glossary_put 新建。",
        })
    links = v.backlinks(entry["slug"])
    docs = sorted({b["doc"] for b in links})
    return _as_json({
        "found": True,
        "entry": entry,
        "backlinks": links,
        "backlinkDocs": docs,
        "backlinkDocsCount": len(docs),
        "backlinkCount": len(links),
    })


def tool_put(args: dict) -> str:
    v = _vault(args)
    return _as_json(v.put(
        slug=args.get("slug", ""),
        kind=args.get("kind", ""),
        name=args.get("name", ""),
        definition=args.get("definition", ""),
        aliases=args.get("aliases") or [],
        source=args.get("source") or None,
    ))


def tool_links(args: dict) -> str:
    v = _vault(args)
    v.ensure_index()
    slug = (args.get("slug") or "").strip()
    doc = (args.get("doc") or "").strip()
    if not slug and not doc:
        return _as_json({"error": "给 slug 或 doc 之一（slug = 词条→文档，doc = 文档→词条）"})
    if slug:
        entry = v.find_entry(slug)
        target = entry["slug"] if entry else slug.lstrip("^")
        links = v.backlinks(target)
        return _as_json({
            "mode": "slug",
            "slug": target,
            "name": entry["name"] if entry else None,
            "entryExists": bool(entry),
            "backlinkCount": len(links),
            "backlinks": links,
            "docs": sorted({b["doc"] for b in links}),
        })
    payload = v.doc_refs(doc)
    payload["mode"] = "doc"
    return _as_json(payload)


def tool_validate(args: dict) -> str:
    v = _vault(args)
    return _as_json(v.validate())


def tool_reindex(args: dict) -> str:
    v = _vault(args)
    info = v.rebuild()
    info["stats"] = v.stats()
    return _as_json(info)


HANDLERS = {
    "glossary_search": tool_search,
    "glossary_lookup": tool_lookup,
    "glossary_put": tool_put,
    "glossary_links": tool_links,
    "glossary_validate": tool_validate,
    "glossary_reindex": tool_reindex,
}


# ── JSON-RPC 面 ─────────────────────────────────────────────────────


def log(msg: str) -> None:
    print(f"[glossary-mcp] {msg}", file=sys.stderr, flush=True)


def respond(msg_id, result=None, error=None) -> None:
    frame = {"jsonrpc": "2.0", "id": msg_id}
    if error is not None:
        frame["error"] = error
    else:
        frame["result"] = result
    sys.stdout.write(json.dumps(frame, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle(msg: dict) -> None:
    method = msg.get("method") or ""
    msg_id = msg.get("id", None)
    is_notification = "id" not in msg
    params = msg.get("params") or {}

    if method == "initialize":
        requested = params.get("protocolVersion") or DEFAULT_PROTOCOL
        respond(msg_id, {
            "protocolVersion": requested,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": (
                "visual-report 词条层：glossary_search / glossary_lookup / glossary_put / "
                "glossary_links / glossary_validate（+ glossary_reindex）。文件是唯一真相源，"
                "写操作落 docs/glossary.md（^slug）与 docs/entities.md（^ent-<slug>）。"
            ),
        })
        return
    if method in ("notifications/initialized", "initialized"):
        log("handshake complete")
        return
    if method == "ping":
        respond(msg_id, {})
        return
    if method == "tools/list":
        respond(msg_id, {"tools": TOOLS})
        return
    if method == "tools/call":
        name = params.get("name") or ""
        arguments = params.get("arguments") or {}
        handler = HANDLERS.get(name)
        if handler is None:
            respond(msg_id, {
                "content": [{"type": "text", "text": f"Error: unknown tool '{name}'"}],
                "isError": True,
            })
            return
        try:
            text = handler(arguments)
            respond(msg_id, {"content": [{"type": "text", "text": text}], "isError": False})
        except gv.VaultError as exc:
            respond(msg_id, {"content": [{"type": "text", "text": f"Error: {exc}"}], "isError": True})
        except Exception as exc:  # noqa: BLE001 - 工具面绝不因为一个坏参数崩进程
            log("tool %s failed: %s\n%s" % (name, exc, traceback.format_exc()))
            respond(msg_id, {
                "content": [{"type": "text", "text": f"Error: {type(exc).__name__}: {exc}"}],
                "isError": True,
            })
        return
    if is_notification:
        return
    respond(msg_id, error={"code": -32601, "message": f"Method not found: {method}"})


def main() -> int:
    # Windows: 强制 UTF-8 双向，避免 GBK/CP936 把中文词条写坏
    try:
        sys.stdin.reconfigure(encoding="utf-8", errors="replace")
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    except (AttributeError, ValueError):  # pragma: no cover
        pass
    log(f"glossary MCP server up (pid={os.getpid()}, cwd={os.getcwd()}, "
        f"plugin_data={gv.plugin_data_dir()}, tools={len(TOOLS)})")
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError:
            log(f"unparseable frame: {raw[:200]}")
            continue
        if isinstance(msg, list):
            for one in msg:
                handle(one)
        else:
            handle(msg)
    log("stdin closed — exiting")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

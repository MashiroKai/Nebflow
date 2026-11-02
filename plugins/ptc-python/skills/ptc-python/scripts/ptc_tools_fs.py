#!/usr/bin/env python3
"""ptc_tools_fs —— 宿主侧文件系统工具面（MCP server，stdio，纯 stdlib）。

它是 PTC 反向通道的**落地端**：执行器以 MCP client 身份连本 server，代码里的
`tools.read(...)` 最终在这里把字节读出来/写下去。同一份 server 也可以被宿主直接
挂到节点上（逐次 tool-call 模式），两种用法共享同一套策略。

策略（全部来自环境变量，由执行器按本次执行计划注入；独立挂载时取缺省）：
  PTC_FS_ROOTS  os.pathsep 分隔的根清单（canonicalize 后做前缀判定）
  PTC_FS_WRITE  "1" 允许写/删改；"0"（缺省）只读
  PTC_MAX_VALUE_BYTES  单次工具返回值上限（缺省 16384）

返回约定（contracts/ptc/v1 §5）：content[0].text = JSON
  {"ok":true,"value":<any>,"bytes":n,"truncated":bool}
  {"ok":false,"error":{"code":"PTC_POLICY_FS"|"PTC_TOOL_ERROR","message":"..."}}
"""

import json
import os
import sys

PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "ptc-tools-fs"
SERVER_VERSION = "1.0.0"

_ROOTS = [os.path.realpath(p) for p in os.environ.get("PTC_FS_ROOTS", "").split(os.pathsep) if p.strip()]
if not _ROOTS:
    _ROOTS = [os.path.realpath(os.getcwd())]
_WRITE = os.environ.get("PTC_FS_WRITE", "0") == "1"
_MAX_VALUE_BYTES = int(os.environ.get("PTC_MAX_VALUE_BYTES", "16384"))
_MAX_READ_BYTES = 262144


# ── 结果封装 ────────────────────────────────────────────────────────────────
def ok(value, truncated=False, extra=None):
    payload = {"ok": True, "value": value, "truncated": bool(truncated),
               "bytes": len(json.dumps(value, ensure_ascii=False).encode("utf-8"))}
    if extra:
        payload.update(extra)
    return payload


def err(code, message):
    return {"ok": False, "error": {"code": code, "message": message}}


class Denied(Exception):
    pass


def _resolve(path, must_write=False):
    if not isinstance(path, str) or not path:
        raise Denied("path must be a non-empty string")
    p = os.path.realpath(os.path.join(_ROOTS[0], path) if not os.path.isabs(path) else path)
    for root in _ROOTS:
        if p == root or p.startswith(root + os.sep):
            if must_write and not _WRITE:
                raise Denied("write is not authorised for this execution (readonly profile)")
            return p
    raise Denied("path escapes the authorised fs_roots: %s" % path)


# ── 工具实现 ────────────────────────────────────────────────────────────────
def t_read(a):
    p = _resolve(a.get("path"))
    offset = int(a.get("offset", 0) or 0)
    limit = int(a.get("limit", 0) or 0)
    with open(p, "rb") as f:
        raw = f.read(_MAX_READ_BYTES + 1)
    truncated = len(raw) > _MAX_READ_BYTES
    raw = raw[:_MAX_READ_BYTES]
    text = raw.decode("utf-8", errors="replace")
    lines = text.splitlines()
    if offset:
        lines = lines[offset:]
    if limit:
        lines = lines[:limit]
    body = "\n".join(lines)
    if len(body.encode("utf-8")) > _MAX_VALUE_BYTES:
        body = body.encode("utf-8")[:_MAX_VALUE_BYTES].decode("utf-8", errors="replace")
        truncated = True
    return ok({"path": p, "text": body, "lines": len(lines)}, truncated)


def t_write(a):
    p = _resolve(a.get("path"), must_write=True)
    content = a.get("content", "")
    if not isinstance(content, str):
        raise Denied("content must be a string")
    if a.get("mkdirs", True):
        os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
    existed = os.path.exists(p)
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(content)
    return ok({"path": p, "bytes": len(content.encode("utf-8")), "created": not existed})


def t_edit(a):
    p = _resolve(a.get("path"), must_write=True)
    old, new = a.get("old"), a.get("new")
    if not isinstance(old, str) or not isinstance(new, str) or not old:
        raise Denied("edit requires non-empty string 'old' and string 'new'")
    count = int(a.get("count", 1) or 1)
    with open(p, "r", encoding="utf-8") as f:
        text = f.read()
    if old not in text:
        return err("PTC_TOOL_ERROR", "edit: 'old' text not found in %s" % p)
    text2 = text.replace(old, new, count)
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text2)
    return ok({"path": p, "replaced": text.count(old) if count == 0 else min(count, text.count(old))})


def t_glob(a):
    import fnmatch
    pattern = a.get("pattern", "*")
    base = _resolve(a.get("path", "."))
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            if fnmatch.fnmatch(rel, pattern) or fnmatch.fnmatch(name, pattern):
                out.append(rel)
                if len(out) >= 200:
                    return ok({"paths": out, "base": base}, truncated=True)
    return ok({"paths": sorted(out), "base": base})


def t_grep(a):
    import re
    pattern = a.get("pattern")
    if not isinstance(pattern, str) or not pattern:
        raise Denied("grep requires a 'pattern' string")
    try:
        rx = re.compile(pattern)
    except re.error as e:
        return err("PTC_TOOL_ERROR", "grep: invalid regex: %s" % e)
    base = _resolve(a.get("path", "."))
    max_results = int(a.get("max_results", 50) or 50)
    matches = []
    if os.path.isfile(base):
        files = [base]
    else:
        files = []
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if not d.startswith(".")]
            files.extend(os.path.join(dirpath, f) for f in filenames)
    for full in files:
        try:
            if os.path.getsize(full) > _MAX_READ_BYTES:
                continue
            with open(full, "r", encoding="utf-8", errors="replace") as f:
                for i, line in enumerate(f, 1):
                    if rx.search(line):
                        matches.append({"path": os.path.relpath(full, base).replace(os.sep, "/"),
                                        "line": i, "text": line.rstrip("\n")[:_MAX_VALUE_BYTES]})
                        if len(matches) >= max_results:
                            return ok({"matches": matches, "count": len(matches)}, truncated=True)
        except (OSError, UnicodeError):
            continue
    return ok({"matches": matches, "count": len(matches)})


def t_stat(a):
    p = _resolve(a.get("path"))
    if not os.path.exists(p):
        return ok({"path": p, "exists": False, "is_dir": False, "size": 0, "mtime_ms": 0})
    st = os.stat(p)
    return ok({"path": p, "exists": True, "is_dir": os.path.isdir(p),
               "size": st.st_size, "mtime_ms": int(st.st_mtime * 1000)})


def t_remove(a):
    p = _resolve(a.get("path"), must_write=True)
    recursive = bool(a.get("recursive", False))
    if not os.path.exists(p):
        return err("PTC_TOOL_ERROR", "remove: path does not exist: %s" % p)
    if os.path.isdir(p):
        if not recursive:
            return err("PTC_TOOL_ERROR", "remove: %s is a directory (pass recursive=true)" % p)
        import shutil
        shutil.rmtree(p)
    else:
        os.remove(p)
    return ok({"path": p, "removed": True})


TOOLS = {
    "read": (t_read, "Read a UTF-8 text file inside the authorised roots.",
             {"path": {"type": "string"}, "offset": {"type": "integer"}, "limit": {"type": "integer"}}, ["path"]),
    "write": (t_write, "Write a UTF-8 text file (creates parent dirs). Read-write profile only.",
              {"path": {"type": "string"}, "content": {"type": "string"}, "mkdirs": {"type": "boolean"}}, ["path", "content"]),
    "edit": (t_edit, "Replace one occurrence of `old` with `new` in a file. Read-write profile only.",
             {"path": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}, "count": {"type": "integer"}}, ["path", "old", "new"]),
    "glob": (t_glob, "List paths under a directory matching a shell glob (max 200).",
             {"pattern": {"type": "string"}, "path": {"type": "string"}}, ["pattern"]),
    "grep": (t_grep, "Regex search file contents under a path; returns {path,line,text} rows (max 50).",
             {"pattern": {"type": "string"}, "path": {"type": "string"}, "max_results": {"type": "integer"}}, ["pattern"]),
    "stat": (t_stat, "Stat a path: exists/is_dir/size/mtime_ms.",
             {"path": {"type": "string"}}, ["path"]),
    "remove": (t_remove, "Delete a file or (recursive=true) a directory. Read-write profile only.",
               {"path": {"type": "string"}, "recursive": {"type": "boolean"}}, ["path"]),
}


# ── MCP stdio 骨架 ─────────────────────────────────────────────────────────
def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def tool_list():
    out = []
    for name, (fn, desc, props, required) in TOOLS.items():
        out.append({"name": name, "description": desc,
                    "inputSchema": {"type": "object", "properties": props, "required": required}})
    return out


def call_tool(name, arguments):
    entry = TOOLS.get(name)
    if not entry:
        return {"content": [{"type": "text", "text": json.dumps(err("PTC_TOOL_ERROR", "unknown tool: %s" % name))}], "isError": True}
    try:
        result = entry[0](arguments or {})
    except Denied as d:
        result = err("PTC_POLICY_FS", str(d))
    except FileNotFoundError as e:
        result = err("PTC_TOOL_ERROR", "not found: %s" % e.filename)
    except Exception as e:  # noqa: BLE001 —— 工具错误必须结构化回传，不许静默
        result = err("PTC_TOOL_ERROR", "%s: %s" % (type(e).__name__, e))
    return {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
            "isError": not result.get("ok", False)}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        method = req.get("method")
        rid = req.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION}}})
        elif method in ("notifications/initialized", "notifications/cancelled"):
            continue
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": rid, "result": {"tools": tool_list()}})
        elif method == "tools/call":
            params = req.get("params") or {}
            send({"jsonrpc": "2.0", "id": rid,
                  "result": call_tool(params.get("name"), params.get("arguments") or {})})
        elif method == "ping":
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid,
                  "error": {"code": -32601, "message": "method not found: %s" % method}})


if __name__ == "__main__":
    main()

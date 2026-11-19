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
    # bytes = value 的**紧凑 JSON** 字节数（缺陷⑤）：与 ptc-javascript 的
    # `JSON.stringify(value)` 逐字节同义——旧实现用 json.dumps 缺省分隔符（", " / ": "），
    # 同一份 value 两侧字节数恒差几个空格，模型拿 bytes 做预算会得到两套数。
    payload = {"ok": True, "value": value, "truncated": bool(truncated),
               "bytes": len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))}
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
# 语义契约（缺陷⑤，2026-11-02——**与 ptc-javascript 的 ptc_tools_fs 逐字同义**，
# 两侧共同由 scripts/ptc/fs-contract.mjs 逐字比对；改一侧必须同时改另一侧）：
#   read   text = 按行切片后的**原始子串**（行终止符随行保留 ⇒ 读整文件与文件内容
#          逐字相等；旧实现用 str.splitlines() 吃掉了行尾换行 ⇒ 138 vs 141）
#   glob   pattern 按 "/" 切段匹配；`**` = 跨段（含零段），`*` = 段内，`?` = 单字符；
#          返回相对 base 的 "/" 路径，**排序后**取前 200，truncated = 命中数>200
#   grep   path 相对 base、按 (path, line) 排序、line 从 1 起、text 去掉行终止符
#          （含尾随 "\r"），命中数进 max_results 截断
_GLOB_MAX = 200


def _seg_regex(seg):
    import re
    out, i = [], 0
    while i < len(seg):
        c = seg[i]
        if c == "*":
            out.append("[^/]*")
        elif c == "?":
            out.append("[^/]")
        elif c == "[":
            j = seg.find("]", i + 1)
            if j < 0:
                out.append(re.escape(c))
            else:
                body = seg[i + 1:j]
                if body.startswith("!"):
                    body = "^" + body[1:]
                out.append("[" + body + "]")
                i = j
        else:
            out.append(re.escape(c))
        i += 1
    return re.compile("".join(out) + r"\Z")


_SEG_CACHE = {}


def _seg_match(seg, name):
    rx = _SEG_CACHE.get(seg)
    if rx is None:
        rx = _seg_regex(seg)
        _SEG_CACHE[seg] = rx
    return rx.match(name) is not None


def _glob_match(pattern, rel):
    """段式 shell glob：`**` 跨段（含零段），`*`/`?` 段内。"""
    pats = [s for s in pattern.replace("\\", "/").split("/") if s not in ("", ".")]
    segs = rel.split("/")

    def walk(pi, si):
        while pi < len(pats):
            if pats[pi] == "**":
                if pi == len(pats) - 1:
                    return True
                for k in range(si, len(segs) + 1):
                    if walk(pi + 1, k):
                        return True
                return False
            if si >= len(segs) or not _seg_match(pats[pi], segs[si]):
                return False
            pi += 1
            si += 1
        return si == len(segs)

    return walk(0, 0)


def _walk_files(base):
    """稳定序遍历：目录名/文件名均排序，点开头条目（文件与目录）一律跳过。"""
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        for name in sorted(filenames):
            if name.startswith("."):
                continue
            full = os.path.join(dirpath, name)
            out.append(os.path.relpath(full, base).replace(os.sep, "/"))
    return out


def _lines_of(text):
    """按 "\n" 切行（保留每行以外的终止符信息）：(行列表, 是否以 "\n" 结尾)。"""
    return text.split("\n"), text.endswith("\n")


def t_read(a):
    p = _resolve(a.get("path"))
    offset = int(a.get("offset", 0) or 0)
    limit = int(a.get("limit", 0) or 0)
    with open(p, "rb") as f:
        raw = f.read(_MAX_READ_BYTES + 1)
    truncated = len(raw) > _MAX_READ_BYTES
    raw = raw[:_MAX_READ_BYTES]
    text = raw.decode("utf-8", errors="replace")
    pieces, ends_nl = _lines_of(text)
    total = len(pieces) - 1 if ends_nl and text != "" else len(pieces)
    start = offset if offset > 0 else 0
    if start > total:
        start = total
    end = total if limit <= 0 else min(total, start + limit)
    selected = pieces[start:end]
    body = "\n".join(selected)
    # 切片触到文件尾且原文件以 "\n" 结尾 ⇒ 尾行终止符属于被选内容（逐字保留）。
    if end == total and ends_nl and total > 0:
        body += "\n"
    if len(body.encode("utf-8")) > _MAX_VALUE_BYTES:
        body = body.encode("utf-8")[:_MAX_VALUE_BYTES].decode("utf-8", errors="replace")
        truncated = True
    return ok({"path": p, "text": body, "lines": len(selected)}, truncated)


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
    pattern = str(a.get("pattern") or "*")
    base = _resolve(a.get("path", "."))
    root = base if os.path.isdir(base) else os.path.dirname(base)
    hits = [r for r in _walk_files(root) if _glob_match(pattern, r)]
    hits.sort()
    truncated = len(hits) > _GLOB_MAX
    return ok({"paths": hits[:_GLOB_MAX], "base": root}, truncated=truncated)


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
    if os.path.isdir(base):
        targets = [(rel, os.path.join(base, rel.replace("/", os.sep))) for rel in _walk_files(base)]
    else:
        targets = [(os.path.basename(base), base)]
    matches = []
    for rel, full in targets:
        try:
            if os.path.getsize(full) > _MAX_READ_BYTES:
                continue
            with open(full, "r", encoding="utf-8", errors="replace") as f:
                for i, line in enumerate(f, 1):
                    if rx.search(line):
                        shown = line[:-1] if line.endswith("\n") else line
                        if shown.endswith("\r"):
                            shown = shown[:-1]
                        matches.append({"path": rel, "line": i, "text": shown[:_MAX_VALUE_BYTES]})
        except (OSError, UnicodeError):
            continue
    matches.sort(key=lambda m: (m["path"], m["line"]))
    truncated = len(matches) > max_results
    return ok({"matches": matches[:max_results], "count": min(len(matches), max_results)}, truncated=truncated)


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

#!/usr/bin/env python3
"""ptc_runner —— PTC 代码运行时（受控子进程，纯 stdlib）。

由 ptc_executor 以 `python -I -B ptc_runner.py` 拉起，唯一通信面是 stdio 上的
`ptc.wire/v1`（NDJSON，见 contracts/ptc/v1/README.md §7）：
  host→runner  init / tool_result
  runner→host  log / tool_call / done

职责：
  1. 把用户代码的 stdout/stderr 收成 `log` 帧（协议通道只走 fd 1 的原始 write）；
  2. 注入 `tools`（授权工具名的可调用成员）与 `ptc`（exec_id/profile/tools/args）；
  3. 用 `sys.addaudithook` 把「联网 / 起进程 / 越根 IO / ctypes 逃逸」拦成 POLICY_DENIED；
  4. 区分 SYNTAX_ERROR（compile 期）与 RUNTIME_ERROR（执行期），把最后一处表达式值作 `value`。
"""

import ast
import json
import os
import sys
import traceback

WIRE = "wire/1"

_real_write = os.write
_stdin_fd = 0
_stdout_fd = 1
_state = {"profile": "readonly", "roots": [], "write": False, "tools": [], "args": {}, "exec_id": None}
_in_hook = False
_hook_off = False


def _emit(obj):
    obj["ptc"] = WIRE
    data = (json.dumps(obj, ensure_ascii=False, default=str) + "\n").encode("utf-8")
    while data:
        n = _real_write(_stdout_fd, data)
        data = data[n:]


def _readline():
    buf = bytearray()
    while True:
        ch = os.read(_stdin_fd, 1)
        if not ch:
            return None
        if ch == b"\n":
            return bytes(buf)
        buf += ch


class LogStream:
    """用户 stdout/stderr 的替身：一切输出都变成 log 帧，不进协议通道。"""

    def __init__(self, stream):
        self._stream = stream

    def write(self, s):
        if s:
            _emit({"type": "log", "stream": self._stream, "data": str(s)})
        return len(s)

    def flush(self):
        pass

    def isatty(self):
        return False

    def fileno(self):
        raise OSError("fileno is not available inside a PTC run")


class ToolCallError(Exception):
    def __init__(self, category, code, message):
        super().__init__(message)
        self.category = category
        self.code = code
        self.message = message


class PolicyDenied(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


_seq = [0]


def _tool_call(tool, args):
    _seq[0] += 1
    if tool not in _state["tools"]:
        raise PolicyDenied("PTC_TOOL_DENIED", "tool '%s' is not authorised for this execution" % tool)
    _emit({"type": "tool_call", "seq": _seq[0], "tool": tool, "args": args if isinstance(args, dict) else {}})
    while True:
        line = _readline()
        if line is None:
            raise ToolCallError("RUNTIME_ERROR", "PTC_RUNTIME", "executor closed the channel during a tool call")
        frame = json.loads(line.decode("utf-8"))
        if frame.get("type") != "tool_result" or frame.get("seq") != _seq[0]:
            continue
        if frame.get("ok"):
            return frame.get("value")
        e = frame.get("error") or {}
        raise ToolCallError(e.get("category", "TOOL_CALL_ERROR"), e.get("code", "PTC_TOOL_ERROR"),
                            e.get("message", "tool call failed"))


class _Tools:
    """`tools.<name>(args)` —— 授权工具名在编译期注入，未授权名字不注入（并给结构化拒绝）。"""

    def __init__(self, names, known):
        self._names = list(names)
        self._known = list(known)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        if name not in self._names:
            if name in self._known:
                raise PolicyDenied("PTC_TOOL_DENIED",
                                   "tool '%s' exists but is not authorised for this %s execution "
                                   "(authorised: %s)" % (name, _state["profile"], ", ".join(self._names)))
            raise AttributeError("tools.%s: unknown tool (authorised: %s)" % (name, ", ".join(self._names)))
        return lambda args=None, **kw: _tool_call(name, dict(args or {}, **kw))

    def __dir__(self):
        return self._names


class _Ptc:
    def __init__(self):
        self.exec_id = _state["exec_id"]
        self.profile = _state["profile"]
        self.tools = list(_state["tools"])
        self.args = _state["args"]


# ── 政策：审计钩子 ─────────────────────────────────────────────────────────
_NET_EVENTS = ("socket.__new__", "socket.connect", "socket.bind", "socket.getaddrinfo",
               "socket.gethostbyname", "socket.sethostname", "urllib.Request", "http.client.connect")
_PROC_EVENTS = ("subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn",
                "os.fork", "os.forkpty", "pty.spawn")
_ESCAPE_EVENTS = ("ctypes.dlopen", "ctypes.dlsym", "ctypes.dlsym/handle", "ctypes.call_function")


def _install_policy():
    roots = [os.path.realpath(r) for r in _state["roots"]]
    # 解释器自身的目录（stdlib / site-packages / 可执行体）允许读：允许代码 import 标准库，
    # 但不允许借此读宿主的其它文件；写仍然只允许落在 fs_roots。
    interp = {os.path.realpath(sys.base_prefix), os.path.realpath(sys.prefix),
              os.path.dirname(os.path.realpath(sys.executable))}
    read_roots = roots + [p for p in interp if p]

    def _in(path, scope):
        if not isinstance(path, (str, bytes)):
            return True  # fd-based operations are not path-confined; the runner keeps them observable
        p = os.path.realpath(path if isinstance(path, str) else path.decode("utf-8", "replace"))
        return any(p == r or p.startswith(r + os.sep) for r in scope)

    def hook(event, args):
        global _in_hook
        if _in_hook or _hook_off:
            return
        try:
            _in_hook = True
            if event in _NET_EVENTS:
                raise PolicyDenied("PTC_POLICY_NETWORK", "network access is denied inside a PTC run (%s)" % event)
            if event in _PROC_EVENTS:
                raise PolicyDenied("PTC_POLICY_SUBPROCESS", "spawning processes is denied inside a PTC run (%s)" % event)
            if event in _ESCAPE_EVENTS:
                raise PolicyDenied("PTC_POLICY_SUBPROCESS", "native code loading is denied inside a PTC run (%s)" % event)
            if event in ("open", "os.listdir", "os.scandir"):
                path = args[0] if args else None
                if event == "open":
                    mode = args[1] if len(args) > 1 else "r"
                    writing = isinstance(mode, str) and any(c in mode for c in "wax+")
                    if writing and not _state["write"]:
                        raise PolicyDenied("PTC_POLICY_FS",
                                           "write is not authorised for this execution (readonly profile)")
                    if writing and not _in(path, roots):
                        raise PolicyDenied("PTC_POLICY_FS",
                                           "write outside the authorised fs_roots: %s" % path)
                if not _in(path, read_roots):
                    raise PolicyDenied("PTC_POLICY_FS", "path escapes the authorised fs_roots: %s" % path)
        finally:
            _in_hook = False

    sys.addaudithook(hook)


# ── 主流程 ─────────────────────────────────────────────────────────────────
def _fmt_error(category, code, phase, exc, message=None):
    global _hook_off
    _hook_off = True
    try:
        tb = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__)) if exc else ""
    finally:
        _hook_off = False
    return {"category": category, "code": code, "phase": phase,
            "message": message if message is not None else str(exc),
            "detail": {"type": type(exc).__name__, "traceback": tb[-4000:]} if exc else None}


def main():
    init_line = _readline()
    if init_line is None:
        return
    init = json.loads(init_line.decode("utf-8"))
    _state.update({
        "profile": init.get("profile", "readonly"),
        "roots": init.get("fs_roots") or [],
        "write": init.get("profile") == "readwrite",
        "tools": init.get("allowed_tools") or [],
        "known": init.get("known_tools") or (init.get("allowed_tools") or []),
        "args": init.get("args") or {},
        "exec_id": init.get("exec_id"),
    })
    code = init.get("code", "")
    limits = init.get("limits") or {}

    # POSIX：内核级硬上限（Windows 走执行器侧的 Job Object / 轮询，见 ptc_executor.apply_memory_cap）
    if os.name != "nt":
        try:
            import resource
            mem = int(limits.get("max_memory_mb", 512)) * 1024 * 1024
            cpu = max(1, int(limits.get("max_cpu_ms", 60000)) // 1000)
            resource.setrlimit(resource.RLIMIT_AS, (mem, mem))
            resource.setrlimit(resource.RLIMIT_CPU, (cpu, cpu + 1))
        except Exception:  # noqa: BLE001 —— 上限拿不到就靠执行器侧的观测，绝不静默放宽
            pass

    # 用户输出改道 log 帧（协议通道保留 fd 1 的原始 write）
    sys.stdout = LogStream("stdout")
    sys.stderr = LogStream("stderr")

    try:
        compiled = compile(code, "<ptc>", "exec", dont_inherit=True)
    except SyntaxError as e:
        _emit({"type": "done", "ok": False,
               "error": _fmt_error("SYNTAX_ERROR", "PTC_SYNTAX", "compile", e,
                                   "%s: %s (line %s, col %s)" % (type(e).__name__, e.msg, e.lineno, e.offset))})
        return

    _install_policy()
    ns = {"__name__": "__ptc__", "__file__": "<ptc>",
          "tools": _Tools(_state["tools"], _state["known"]), "ptc": _Ptc()}

    value = None
    try:
        tree = ast.parse(code, "<ptc>", "exec")
        tail = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            tail = ast.Expression(tree.body[-1].value)
            tree.body = tree.body[:-1]
            ast.fix_missing_locations(tail)
        exec(compiled if tail is None else compile(tree, "<ptc>", "exec"), ns)
        if tail is not None:
            value = eval(compile(tail, "<ptc>", "eval"), ns)
        try:
            json.dumps(value, default=str)
        except (TypeError, ValueError):
            value = str(value)
    except MemoryError as e:
        _emit({"type": "done", "ok": False,
               "error": {"category": "LIMIT_EXCEEDED", "code": "PTC_LIMIT_MEMORY", "phase": "run",
                         "message": "allocation exceeded max_memory_mb=%s" % limits.get("max_memory_mb"),
                         "detail": {"type": type(e).__name__}}})
        return
    except PolicyDenied as e:
        _emit({"type": "done", "ok": False,
               "error": {"category": "POLICY_DENIED", "code": e.code, "phase": "run",
                         "message": e.message, "detail": None}})
        return
    except ToolCallError as e:
        _emit({"type": "done", "ok": False,
               "error": {"category": e.category if e.category in ("TOOL_CALL_ERROR", "POLICY_DENIED",
                                                                  "TIMEOUT", "LIMIT_EXCEEDED") else "TOOL_CALL_ERROR",
                         "code": e.code, "phase": "tool_call", "message": e.message, "detail": None}})
        return
    except SystemExit as e:
        _emit({"type": "done", "ok": False, "value": None,
               "error": {"category": "RUNTIME_ERROR", "code": "PTC_RUNTIME", "phase": "run",
                         "message": "code called sys.exit(%s); treated as a runtime termination" % e.code,
                         "detail": None}})
        return
    except BaseException as e:  # noqa: BLE001 —— 运行期异常一律结构化
        _emit({"type": "done", "ok": False,
               "error": _fmt_error("RUNTIME_ERROR", "PTC_RUNTIME", "run", e)})
        return

    _emit({"type": "done", "ok": True, "value": value})


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001 —— 运行时自身故障也要有结构化终帧
        try:
            _emit({"type": "done", "ok": False,
                   "error": {"category": "INTERNAL_ERROR", "code": "PTC_INTERNAL", "phase": "teardown",
                             "message": "%s: %s" % (type(e).__name__, e), "detail": None}})
        except Exception:
            pass
    finally:
        os._exit(0)

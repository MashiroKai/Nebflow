#!/usr/bin/env python3
"""ptc_executor —— PTC 插件（ptc-python）的 MCP server = 执行器。

宿主（agent 核心）以标准 MCP 挂载本进程（mcp.json → stdio），本进程暴露两个工具：
  ptc_run     只读档：read / glob / grep / stat
  ptc_run_rw  读写档：额外 write / edit / remove

一次执行 = 一次授权边界（manifest 定上界、请求只能收窄）：
  spawn runner（受控子进程）→ 转发 runner 的工具调用到工具 MCP server → 收台账与结果。

反向通道：本进程同时是工具 MCP server 的 **client**（`tools/call`），因此代码里的
`tools.read(...)` 最终由宿主侧工具 server 执行——标准 MCP，无协议扩展。
契约见 contracts/ptc/v1/README.md（本包 skills/ptc-python/contract/ 为同字节副本）。
"""

import json
import os
import queue
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN_ROOT = os.path.realpath(os.environ.get("PLUGIN_ROOT") or os.path.join(HERE, "..", "..", ".."))
PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "ptc-python"
SERVER_VERSION = "1.0.0"
CONTRACT = "ptc/v1"
NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def log(msg):
    sys.stderr.write("[ptc-python] %s\n" % msg)
    sys.stderr.flush()


# ── manifest ───────────────────────────────────────────────────────────────
def load_manifest():
    with open(os.path.join(PLUGIN_ROOT, "plugin.json"), "r", encoding="utf-8") as f:
        manifest = json.load(f)
    return manifest["extensions"]["ptc"]


def expand(value, plugin_data):
    if isinstance(value, str):
        return (value.replace("${PLUGIN_ROOT}", PLUGIN_ROOT)
                     .replace("${PLUGIN_DATA}", plugin_data)
                     .replace("${RUNTIME_SELF}", sys.executable))
    if isinstance(value, list):
        return [expand(v, plugin_data) for v in value]
    if isinstance(value, dict):
        return {k: expand(v, plugin_data) for k, v in value.items()}
    return value


PTC = load_manifest()
# PLUGIN_DATA 由宿主注入（<dataRoot>/plugin-data/<plugin>）；独立跑测试时落系统临时目录，
# **绝不**默认写进插件包根（包根未知条目会被装载器告警、且污染包 digest）。
PLUGIN_DATA = os.path.realpath(
    os.environ.get("PLUGIN_DATA")
    or os.path.join(os.environ.get("TEMP") or os.environ.get("TMP") or "/tmp", "ptc-plugin-data", "ptc-python"))
os.makedirs(PLUGIN_DATA, exist_ok=True)
DEFAULT_CWD = expand(PTC["workspace"]["default_cwd"], PLUGIN_DATA)
DEFAULT_ROOTS = [os.path.realpath(r) for r in expand(PTC["workspace"]["fs_roots"], PLUGIN_DATA)]
os.makedirs(DEFAULT_CWD, exist_ok=True)
RUNNER = os.path.join(HERE, "ptc_runner.py")


def tool_servers():
    return [expand(s, PLUGIN_DATA) for s in PTC["tool_servers"]]


# ── 工具 server 的 MCP client ───────────────────────────────────────────────
class ToolServer:
    """一次性工具 server 连接（每次执行新建、执行结束即拆：工具面状态不跨执行泄漏）。"""

    def __init__(self, spec, roots, write, max_value_bytes, timeout_ms):
        env = {"PATH": os.environ.get("PATH", ""), "SYSTEMROOT": os.environ.get("SYSTEMROOT", ""),
               "PTC_FS_ROOTS": os.pathsep.join(roots), "PTC_FS_WRITE": "1" if write else "0",
               "PTC_MAX_VALUE_BYTES": str(max_value_bytes)}
        env.update({k: str(v) for k, v in (spec.get("env") or {}).items()})
        self.timeout_ms = timeout_ms
        self._id = 0
        self._pending = {}
        self._lock = threading.Lock()
        self.proc = subprocess.Popen(
            [spec["command"]] + list(spec.get("args") or []),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, cwd=PLUGIN_ROOT, creationflags=NO_WINDOW)
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()
        self.init_error = None
        try:
            self.request("initialize", {"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                                        "clientInfo": {"name": SERVER_NAME + "-executor", "version": SERVER_VERSION}})
            self.notify("notifications/initialized", {})
        except Exception as e:  # noqa: BLE001
            self.init_error = "%s: %s" % (type(e).__name__, e)

    def _read_loop(self):
        stream = self.proc.stdout
        while True:
            line = stream.readline()
            if not line:
                break
            try:
                msg = json.loads(line.decode("utf-8", "replace"))
            except json.JSONDecodeError:
                continue
            rid = msg.get("id")
            if rid is None:
                continue
            with self._lock:
                slot = self._pending.get(rid)
            if slot is not None:
                slot.put(msg)

    def _send(self, obj):
        self.proc.stdin.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, method, params):
        with self._lock:
            self._id += 1
            rid = self._id
            slot = queue.Queue(maxsize=1)
            self._pending[rid] = slot
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        try:
            msg = slot.get(timeout=max(1.0, self.timeout_ms / 1000.0))
        finally:
            with self._lock:
                self._pending.pop(rid, None)
        if msg.get("error"):
            raise RuntimeError("tool server error: %s" % msg["error"].get("message"))
        return msg.get("result") or {}

    def call(self, name, arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        blocks = result.get("content") or []
        text = ""
        for b in blocks:
            if isinstance(b, dict) and isinstance(b.get("text"), str):
                text += b["text"]
        try:
            return json.loads(text) if text else {"ok": False, "error": {"code": "PTC_TOOL_ERROR",
                                                                        "message": "empty tool result"}}
        except json.JSONDecodeError:
            return {"ok": True, "value": text, "bytes": len(text.encode("utf-8"))}

    def close(self):
        try:
            if self.proc.poll() is None:
                self.proc.kill()
        except Exception:  # noqa: BLE001
            pass


# ── 资源观测 ───────────────────────────────────────────────────────────────
def rss_kb(pid):
    try:
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes

            class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
                _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                            ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                            ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            psapi = ctypes.WinDLL("psapi", use_last_error=True)
            handle = k32.OpenProcess(0x1000 | 0x0010, False, int(pid))
            if not handle:
                return None
            try:
                counters = PROCESS_MEMORY_COUNTERS()
                counters.cb = ctypes.sizeof(counters)
                if psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                    return int(counters.WorkingSetSize / 1024)
                return None
            finally:
                k32.CloseHandle(handle)
        with open("/proc/%d/status" % pid, "r") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
    except Exception:  # noqa: BLE001
        pass
    return None


def apply_memory_cap(pid, mb):
    """Windows：Job Object 硬上限（超限的分配在子进程里直接 MemoryError）。
    失败则退回父进程轮询（rss_kb 那条腿），不静默放弃限额。"""
    if os.name != "nt":
        return "rlimit"  # POSIX 由 runner 侧 setrlimit(RLIMIT_AS) 承担
    try:
        import ctypes
        from ctypes import wintypes

        class IO_COUNTERS(ctypes.Structure):
            _fields_ = [("ReadOperationCount", ctypes.c_ulonglong), ("WriteOperationCount", ctypes.c_ulonglong),
                        ("OtherOperationCount", ctypes.c_ulonglong), ("ReadTransferCount", ctypes.c_ulonglong),
                        ("WriteTransferCount", ctypes.c_ulonglong), ("OtherTransferCount", ctypes.c_ulonglong)]

        class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", wintypes.LARGE_INTEGER),
                        ("PerJobUserTimeLimit", wintypes.LARGE_INTEGER),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                        ("SchedulingClass", wintypes.DWORD)]

        class JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION),
                        ("IoInfo", IO_COUNTERS), ("ProcessMemoryLimit", ctypes.c_size_t),
                        ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                        ("PeakJobMemoryUsed", ctypes.c_size_t)]

        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        job = k32.CreateJobObjectW(None, None)
        if not job:
            return "poll-rss"
        info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
        info.BasicLimitInformation.LimitFlags = 0x100 | 0x2000  # PROCESS_MEMORY | KILL_ON_JOB_CLOSE
        info.ProcessMemoryLimit = int(mb) * 1024 * 1024
        if not k32.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info)):
            return "poll-rss"
        # SET_QUOTA | TERMINATE | QUERY_INFORMATION —— AssignProcessToJobObject 要求的最小集
        handle = k32.OpenProcess(0x0100 | 0x0001 | 0x0400, False, int(pid))
        if not handle:
            return "poll-rss"
        try:
            if not k32.AssignProcessToJobObject(job, handle):
                return "poll-rss"
        finally:
            k32.CloseHandle(handle)
        _state_jobs.append(job)
        return "job-object"
    except Exception:  # noqa: BLE001
        return "poll-rss"


_state_jobs = []


def close_jobs():
    if os.name != "nt":
        return
    import ctypes
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    while _state_jobs:
        try:
            k32.CloseHandle(_state_jobs.pop())
        except Exception:  # noqa: BLE001
            pass


def cpu_ms(pid):
    try:
        if os.name == "nt":
            return None
        with open("/proc/%d/stat" % pid, "r") as f:
            parts = f.read().split()
        return int((int(parts[13]) + int(parts[14])) * 1000 / os.sysconf("SC_CLK_TCK"))
    except Exception:  # noqa: BLE001
        return None


# ── 请求校验 ───────────────────────────────────────────────────────────────
class Invalid(Exception):
    pass


def _subset_subtree(path, roots):
    p = os.path.realpath(path)
    return any(p == r or p.startswith(r + os.sep) for r in roots)


def validate(args, tool_profile):
    limits_cfg = PTC["limits"]
    clamped = []
    if args.get("contract") not in (None, CONTRACT):
        raise Invalid("contract %r is not supported (this server speaks %s)" % (args.get("contract"), CONTRACT))
    if args.get("language") not in (None, PTC["runtime"]["kind"]):
        raise Invalid("language %r does not match this plugin's runtime %r"
                      % (args.get("language"), PTC["runtime"]["kind"]))
    code = args.get("code")
    if not isinstance(code, str) or not code.strip():
        raise Invalid("'code' must be a non-empty string")
    if len(code.encode("utf-8")) > 262144:
        raise Invalid("'code' exceeds the 256 KiB request cap")

    profile = args.get("profile") or tool_profile
    if profile not in ("readonly", "readwrite"):
        raise Invalid("profile must be readonly|readwrite")
    if tool_profile == "readonly" and profile == "readwrite":
        return _deny_escalation()  # ptc_run 不能靠参数把自己升级成读写档

    ceiling = list(PTC["profiles"][profile]["tools"])
    allowed = args.get("allowed_tools")
    if allowed is None:
        allowed = ceiling
    elif not isinstance(allowed, list) or not all(isinstance(t, str) for t in allowed):
        raise Invalid("allowed_tools must be a string array")
    else:
        extra = [t for t in allowed if t not in ceiling]
        if extra:
            raise Invalid("allowed_tools %s is not within the %s ceiling %s" % (extra, profile, ceiling))
        allowed = [t for t in ceiling if t in allowed]

    budget = dict(limits_cfg)
    req_limits = args.get("limits") or {}
    for key, cap_key in (("max_tool_calls", "max_tool_calls"), ("max_output_bytes", "max_output_bytes"),
                         ("max_memory_mb", "max_memory_mb"), ("max_cpu_ms", "max_cpu_ms")):
        if key in req_limits:
            v = req_limits[key]
            if not isinstance(v, int) or v <= 0:
                raise Invalid("limits.%s must be a positive integer" % key)
            if v < limits_cfg[cap_key]:
                budget[cap_key] = v
            else:
                clamped.append("limits." + key)

    timeout_ms = args.get("timeout_ms")
    if timeout_ms is None:
        timeout_ms = limits_cfg["default_timeout_ms"]
    elif not isinstance(timeout_ms, int) or timeout_ms <= 0:
        raise Invalid("timeout_ms must be a positive integer")
    elif timeout_ms > limits_cfg["max_timeout_ms"]:
        timeout_ms = limits_cfg["max_timeout_ms"]
        clamped.append("timeout_ms")

    roots = args.get("fs_roots")
    if roots is None:
        roots = DEFAULT_ROOTS
    else:
        if not isinstance(roots, list) or not all(isinstance(r, str) for r in roots) or not roots:
            raise Invalid("fs_roots must be a non-empty string array")
        roots = [os.path.realpath(r) for r in roots]
        if not all(_subset_subtree(r, DEFAULT_ROOTS) for r in roots):
            raise Invalid("fs_roots must stay inside the manifest roots %s" % DEFAULT_ROOTS)

    cwd = args.get("cwd") or DEFAULT_CWD
    if not _subset_subtree(cwd, roots):
        raise Invalid("cwd %r is outside fs_roots" % cwd)
    if not os.path.isdir(cwd):
        raise Invalid("cwd %r is not an existing directory" % cwd)

    return {"code": code, "profile": profile, "allowed_tools": allowed, "budget": budget,
            "timeout_ms": timeout_ms, "cwd": os.path.realpath(cwd), "roots": roots,
            "args": args.get("args") if isinstance(args.get("args"), dict) else {},
            "clamped": clamped}


def _deny_escalation():
    raise Invalid("ptc_run is the readonly entry point; use ptc_run_rw for a write-capable execution")


# ── 执行 ───────────────────────────────────────────────────────────────────
def execute(raw_args, tool_profile, tool_name):
    t0 = time.monotonic()
    exec_id = (raw_args or {}).get("exec_id") or ("ptc-" + os.urandom(4).hex())
    base = {"contract": CONTRACT, "exec_id": exec_id, "language": PTC["runtime"]["kind"],
            "profile": tool_profile, "stdout": "", "stderr": "", "stdout_truncated": False,
            "stderr_truncated": False, "tool_calls": [], "tool_call_count": 0, "files_written": [],
            "value": None, "clamped": []}

    def fail(category, code, phase, message, detail=None, partial_stdout="", partial_stderr=""):
        out = dict(base)
        out.update({"ok": False, "duration_ms": int((time.monotonic() - t0) * 1000),
                    "stdout": partial_stdout, "stderr": partial_stderr,
                    "error": {"category": category, "code": code, "phase": phase,
                              "message": message, "tool": None, "detail": detail},
                    "usage": {"wall_ms": int((time.monotonic() - t0) * 1000), "cpu_ms": None,
                              "max_rss_kb": None, "tool_calls": base["tool_call_count"],
                              "output_bytes": len(partial_stdout) + len(partial_stderr)}})
        return out

    try:
        plan = validate(raw_args or {}, tool_profile)
    except Invalid as e:
        return fail("INVALID_REQUEST", "PTC_INVALID_REQUEST", "validate", str(e))
    base["profile"] = plan["profile"]
    base["clamped"] = plan["clamped"]

    server = None
    try:
        server = ToolServer(tool_servers()[0], plan["roots"], plan["profile"] == "readwrite",
                            min(16384, plan["budget"]["max_output_bytes"]), 10000)
        if server.init_error:
            return fail("RUNTIME_UNAVAILABLE", "PTC_RUNTIME_UNAVAILABLE", "validate",
                        "tool server failed to start: %s" % server.init_error)
    except FileNotFoundError as e:
        return fail("RUNTIME_UNAVAILABLE", "PTC_RUNTIME_UNAVAILABLE", "validate",
                    "tool server runtime not found: %s" % e)
    except Exception as e:  # noqa: BLE001
        return fail("INTERNAL_ERROR", "PTC_INTERNAL", "teardown", "tool server spawn failed: %s: %s"
                    % (type(e).__name__, e))

    env = {"PATH": os.environ.get("PATH", ""), "SYSTEMROOT": os.environ.get("SYSTEMROOT", ""),
           "TEMP": os.environ.get("TEMP", ""), "TMP": os.environ.get("TMP", "")}
    try:
        runner = subprocess.Popen([sys.executable, "-I", "-B", RUNNER],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env=env, cwd=plan["cwd"], creationflags=NO_WINDOW)
    except FileNotFoundError as e:
        server.close()
        return fail("RUNTIME_UNAVAILABLE", "PTC_RUNTIME_UNAVAILABLE", "validate", "runner spawn failed: %s" % e)

    budget = plan["budget"]
    memory_mode = apply_memory_cap(runner.pid, budget["max_memory_mb"])
    out_q = queue.Queue()

    def pump(stream, tag):
        while True:
            line = stream.readline()
            if not line:
                break
            out_q.put((tag, line))

    threading.Thread(target=pump, args=(runner.stdout, "wire"), daemon=True).start()
    threading.Thread(target=pump, args=(runner.stderr, "stderr"), daemon=True).start()

    deadline = t0 + plan["timeout_ms"] / 1000.0
    budget = plan["budget"]
    stdout_parts, stderr_parts = [], []
    stdout_bytes = 0
    peak_rss = None
    ledger = []
    done = None
    stray = []
    outcome = None

    def record_output(text, is_err):
        nonlocal stdout_bytes
        room = budget["max_output_bytes"] - stdout_bytes
        truncated = False
        if room <= 0:
            truncated = True
        else:
            chunk = text if len(text) <= room else text[:room]
            if len(chunk) < len(text):
                truncated = True
            stdout_bytes += len(chunk.encode("utf-8", "replace"))
            (stderr_parts if is_err else stdout_parts).append(chunk)
        return truncated

    try:
        init = {"type": "init", "code": plan["code"], "language": PTC["runtime"]["kind"],
                "profile": plan["profile"], "allowed_tools": plan["allowed_tools"],
                "known_tools": sorted({t for p in PTC["profiles"].values() for t in p["tools"]}),
                "limits": budget, "cwd": plan["cwd"], "fs_roots": plan["roots"],
                "args": plan["args"], "exec_id": exec_id}
        runner.stdin.write((json.dumps(init, ensure_ascii=False) + "\n").encode("utf-8"))
        runner.stdin.flush()

        last_poll = 0.0
        while True:
            now = time.monotonic()
            if now > deadline:
                outcome = ("TIMEOUT", "PTC_TIMEOUT", "run",
                           "execution exceeded timeout_ms=%d" % plan["timeout_ms"])
                break
            if now - last_poll > 0.25:
                last_poll = now
                rss = rss_kb(runner.pid)
                if rss:
                    peak_rss = max(peak_rss or 0, rss)
                    if rss > budget["max_memory_mb"] * 1024:
                        outcome = ("LIMIT_EXCEEDED", "PTC_LIMIT_MEMORY", "run",
                                   "peak RSS %d KiB exceeded max_memory_mb=%d" % (rss, budget["max_memory_mb"]))
                        break
                cms = cpu_ms(runner.pid)
                if cms is not None and cms > budget["max_cpu_ms"]:
                    outcome = ("LIMIT_EXCEEDED", "PTC_LIMIT_CPU", "run",
                               "cpu %d ms exceeded max_cpu_ms=%d" % (cms, budget["max_cpu_ms"]))
                    break
            try:
                tag, line = out_q.get(timeout=min(0.25, max(0.01, deadline - now)))
            except queue.Empty:
                continue
            text = line.decode("utf-8", "replace")
            if tag == "stderr":
                record_output(text, True)
                continue
            text = text.strip()
            if not text:
                continue
            try:
                frame = json.loads(text)
            except json.JSONDecodeError:
                stray.append(text[:400])
                record_output(text + "\n", False)
                continue

            ftype = frame.get("type")
            if ftype == "log":
                record_output(frame.get("data") or "", frame.get("stream") == "stderr")
            elif ftype == "tool_call":
                seq = frame.get("seq")
                tool = frame.get("tool")
                targs = frame.get("args") or {}
                started = time.monotonic()
                reply = _dispatch_tool(plan, budget, ledger, server, tool, targs, exec_id)
                reply["seq"] = seq
                reply["duration_ms"] = int((time.monotonic() - started) * 1000)
                if ledger:
                    ledger[-1]["duration_ms"] = reply["duration_ms"]
                runner.stdin.write((json.dumps(reply, ensure_ascii=False) + "\n").encode("utf-8"))
                runner.stdin.flush()
            elif ftype == "done":
                done = frame
                break
    except Exception as e:  # noqa: BLE001
        outcome = ("INTERNAL_ERROR", "PTC_INTERNAL", "teardown", "%s: %s" % (type(e).__name__, e))
    finally:
        try:
            if runner.poll() is None:
                runner.kill()
        except Exception:  # noqa: BLE001
            pass
        close_jobs()
        server.close()

    duration_ms = int((time.monotonic() - t0) * 1000)
    stdout_text = "".join(stdout_parts)
    stderr_text = "".join(stderr_parts)
    response = dict(base)
    response.update({
        "stdout": stdout_text, "stderr": stderr_text,
        "stdout_truncated": stdout_bytes >= budget["max_output_bytes"],
        "tool_calls": ledger, "tool_call_count": len(ledger),
        "files_written": [c["args"].get("path") for c in ledger
                          if c["ok"] and c["tool"] in ("write", "edit", "remove")],
        "duration_ms": duration_ms,
        "usage": {"wall_ms": duration_ms, "cpu_ms": None, "max_rss_kb": peak_rss,
                  "tool_calls": len(ledger), "output_bytes": len(stdout_text.encode()) + len(stderr_text.encode())},
    })
    if outcome is not None:
        response.update({"ok": False, "error": {"category": outcome[0], "code": outcome[1], "phase": outcome[2],
                                                "message": outcome[3], "tool": None, "detail": None}})
    else:
        err = (done or {}).get("error")
        response.update({"ok": bool(done and done.get("ok")), "value": (done or {}).get("value"),
                         "error": None if not err else {"category": err.get("category", "RUNTIME_ERROR"),
                                                        "code": err.get("code", "PTC_RUNTIME"),
                                                        "phase": err.get("phase", "run"),
                                                        "message": err.get("message", ""),
                                                        "tool": None, "detail": err.get("detail")}})
    if stray:
        response["stderr"] = (response["stderr"] + "\n[stray-stdout] " + "\n".join(stray))[:8192]
    return response


def _dispatch_tool(plan, budget, ledger, server, tool, targs, exec_id):
    """白名单 + 预算双闸；工具 server 的错误按 code 前缀映射到契约的错误分类。"""
    entry = {"seq": len(ledger) + 1, "tool": tool, "args": targs if isinstance(targs, dict) else {},
             "ok": False, "duration_ms": 0}
    if tool not in plan["allowed_tools"]:
        entry["error"] = {"category": "POLICY_DENIED", "code": "PTC_TOOL_DENIED",
                          "message": "tool '%s' is not authorised (allowed: %s)" % (tool, plan["allowed_tools"])}
        ledger.append(entry)
        return {"type": "tool_result", "ok": False,
                "error": {"category": "POLICY_DENIED", "code": "PTC_TOOL_DENIED",
                          "message": entry["error"]["message"]}}
    if len(ledger) >= budget["max_tool_calls"]:
        entry["error"] = {"category": "LIMIT_EXCEEDED", "code": "PTC_LIMIT_TOOL_CALLS",
                          "message": "max_tool_calls=%d reached" % budget["max_tool_calls"]}
        ledger.append(entry)
        return {"type": "tool_result", "ok": False,
                "error": {"category": "LIMIT_EXCEEDED", "code": "PTC_LIMIT_TOOL_CALLS",
                          "message": entry["error"]["message"]}}

    try:
        envelope = server.call(tool, entry["args"])
    except Exception as e:  # noqa: BLE001
        entry["error"] = {"category": "TOOL_CALL_ERROR", "code": "PTC_TOOL_ERROR",
                          "message": "tool server failure: %s: %s" % (type(e).__name__, e)}
        ledger.append(entry)
        return {"type": "tool_result", "ok": False, "error": entry["error"]}

    if envelope.get("ok"):
        value = envelope.get("value")
        entry.update({"ok": True, "truncated": bool(envelope.get("truncated")),
                      "result_bytes": int(envelope.get("bytes") or 0)})
        ledger.append(entry)
        return {"type": "tool_result", "ok": True, "value": value}

    e = envelope.get("error") or {}
    code = e.get("code") or "PTC_TOOL_ERROR"
    category = "POLICY_DENIED" if code.startswith("PTC_POLICY") else "TOOL_CALL_ERROR"
    entry["error"] = {"category": category, "code": code, "message": e.get("message", "tool call failed")}
    ledger.append(entry)
    return {"type": "tool_result", "ok": False, "error": entry["error"]}


# ── MCP stdio 骨架 ─────────────────────────────────────────────────────────
def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def _schema():
    props = {
        "code": {"type": "string", "description": "Code to execute. Inside it, call host tools as tools.<name>(args)."},
        "language": {"enum": [PTC["runtime"]["kind"]], "description": "Must match this plugin's runtime."},
        "profile": {"enum": ["readonly", "readwrite"]},
        "allowed_tools": {"type": "array", "items": {"type": "string"},
                          "description": "Subset of the profile ceiling; narrows what the code may call."},
        "timeout_ms": {"type": "integer"},
        "limits": {"type": "object", "properties": {
            "max_tool_calls": {"type": "integer"}, "max_output_bytes": {"type": "integer"},
            "max_memory_mb": {"type": "integer"}, "max_cpu_ms": {"type": "integer"}}},
        "cwd": {"type": "string"},
        "fs_roots": {"type": "array", "items": {"type": "string"}},
        "args": {"type": "object", "description": "Bound to ptc.args inside the code."},
        "exec_id": {"type": "string"},
    }
    return {"type": "object", "properties": props, "required": ["code"]}


def tools_list():
    out = []
    for t in PTC["tools"]:
        readonly = t["profile"] == "readonly"
        desc = (
            "PTC v1 executor (%s, %s profile). Execute %s code that ORCHESTRATES host tool calls: "
            "inside the code call tools.<name>(args) for the tools %s. One call returns the whole "
            "program's stdout, completion value, and a per-call tool ledger. "
            "Use it when a task needs several tool calls in a loop/chain — one code round-trip instead "
            "of many tool round-trips. Network, subprocesses and paths outside fs_roots are denied."
            % (SERVER_NAME, t["profile"], PTC["runtime"]["kind"],
               ", ".join(PTC["profiles"][t["profile"]]["tools"]) if readonly else
               ", ".join(PTC["profiles"][t["profile"]]["tools"]))
        )
        out.append({"name": t["name"], "description": desc, "inputSchema": _schema()})
    return out


def main():
    profiles = {t["name"]: t["profile"] for t in PTC["tools"]}
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        method, rid = req.get("method"), req.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION}}})
        elif method in ("notifications/initialized", "notifications/cancelled"):
            continue
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": rid, "result": {"tools": tools_list()}})
        elif method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            if name not in profiles:
                send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "unknown tool: %s" % name}})
                continue
            try:
                response = execute(args, profiles[name], name)
            except Exception as e:  # noqa: BLE001 —— 执行器自身故障也要结构化
                response = {"contract": CONTRACT, "ok": False, "exec_id": args.get("exec_id"),
                            "language": PTC["runtime"]["kind"], "profile": profiles[name], "duration_ms": 0,
                            "stdout": "", "stderr": "", "tool_calls": [], "tool_call_count": 0,
                            "error": {"category": "INTERNAL_ERROR", "code": "PTC_INTERNAL", "phase": "teardown",
                                      "message": "%s: %s" % (type(e).__name__, e)},
                            "usage": {"wall_ms": 0, "cpu_ms": None, "max_rss_kb": None,
                                      "tool_calls": 0, "output_bytes": 0}}
            send({"jsonrpc": "2.0", "id": rid,
                  "result": {"content": [{"type": "text", "text": json.dumps(response, ensure_ascii=False)}],
                             "isError": not response.get("ok", False)}})
        elif method == "ping":
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "method not found: %s" % method}})


if __name__ == "__main__":
    main()

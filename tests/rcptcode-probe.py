#!/usr/bin/env python3
"""rcptcode 好友腿逐码黑盒探针（隔离实例 + 自有端口 + 本机 stub 上游）。

目的（考古开放项收口）：折叠点 ①② 修复后，**从网关 HTTP 边界**逐码登记
「上游拒绝码 ⇒ 客户端读到什么」。上游 = 本机 stub（**禁真实凭据、禁打生产上游**），
实例 = 隔离 home + 自有端口 8094（**禁触宿主 :8080**）。

🔴 诚实申报：上游是 stub（真 neblink-server 零触碰）⇒ 本探针证的是**网关转发面**
（状态码 + 体 + 是否折 502），不是生产上游的码集穷举。生产上游逐码登记仍是开放项。

用法：python3 probe.py            （读 /tmp/nb-rcptcode/cp.txt 的运行时 classpath）
"""
import json, os, re, signal, subprocess, sys, threading, time, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CP_FILE = "/tmp/nb-rcptcode/cp.txt"
OUT = "/tmp/nb-rcptcode/probe-readings.json"
WORKTREE = "/Users/kaiyu/Claude code/Nebflow/.nebflow/worktrees/rcptcode-impl"
HOME = "/tmp/nb-rcptcode/home"
GW_PORT = 8094
STUB_PORT = 8494
JAVA = "/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java"
GW = f"http://127.0.0.1:{GW_PORT}"
STUB = f"http://127.0.0.1:{STUB_PORT}"

# 上游形态表（uid → 该 uid 的上游应答）。一码一位。
ARMS = [
    ("up-403-notfriends", 403, {"error": "not_friends"}),
    ("up-403-notblocker", 403, {"error": "not_blocker"}),
    ("up-400-replyinvalid", 400, {"error": "REPLY_TARGET_INVALID"}),
    ("up-429-unknown", 429, {"error": "upstream_rate_limited"}),
    ("up-500", 500, {"error": "server_error"}),
    ("up-403-kicked", 403, {"error": "Missing or invalid token"}),
    ("up-201", 201, {"messageId": 77, "conversationId": "c1", "createdAt": 1789000000}),
    ("up-200", 200, {"messageId": 78, "conversationId": "c1", "createdAt": 1789000001}),
]

STUB_CALLS = []
INSTANCE = {"proc": None}


class Stub(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # 静音（读数走 STUB_CALLS）
        pass

    def _read(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode("utf-8", "replace") if n else ""

    def _send(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        # 🔴 每响应即关连接：否则 java http client 复用 keep-alive 连接，
        # 「停 stub 后仍被既有连接服务」⇒ 传输失败腿测不出来（实测踩过）。
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def _handle(self, method):
        path = urllib.parse.urlparse(self.path).path
        raw = self._read()
        STUB_CALLS.append({"method": method, "path": path, "auth": self.headers.get("Authorization"),
                           "body": raw[:400]})
        if path == "/api/device/enroll":
            dev = "d1"
            try:
                dev = json.loads(raw).get("deviceId", "d1")
            except Exception:
                pass
            return self._send(200, {"deviceToken": "tok-stub", "networkId": "n1", "deviceId": dev,
                                    "userId": "me", "username": "me", "displayName": "Me",
                                    "serverUrl": STUB, "peers": []})
        if path == "/api/device/login":
            return self._send(200, {"token": "tok-stub", "networkId": "n1", "deviceId": "d1", "peers": []})
        # 凭据腿（enroll 后重启走这条）= `/api/device/session`；心跳/register 同族。
        # 统一回 LoginResponse 形状（`token/networkId/deviceId/peers`）—— circe 派生
        # 解码器忽略未知键，多给不伤（LoginResponse / HeartbeatResponse 都吃）。
        if path.startswith("/api/device/"):
            return self._send(200, {"token": "tok-stub", "networkId": "n1", "deviceId": "d1",
                                    "peers": [], "expiresAt": 4102444800})
        if path == "/api/friends" and method == "GET":
            return self._send(200, {"friends": [], "incoming": [], "outgoing": []})
        m = re.match(r"^/api/friends/([^/]+)/messages$", path)
        if m and method == "POST":
            uid = m.group(1)
            for a_uid, status, body in ARMS:
                if a_uid == uid:
                    return self._send(status, body)
            return self._send(200, {"messageId": 99, "conversationId": "c1", "createdAt": 1789000002})
        if re.match(r"^/api/conversations(/|$)", path):
            if path.endswith("/messages"):
                return self._send(200, [])
            return self._send(200, [])
        return self._send(200, {})

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


import urllib.parse  # noqa: E402  (handler 内引用，放在此处仅为可读性)


def gw_call(method, path, token=None, payload=None, timeout=20):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # 连接层失败
        return None, f"{type(e).__name__}: {e}"


def start_instance():
    cp = open(CP_FILE).read().strip()
    os.makedirs(HOME, exist_ok=True)
    env = dict(os.environ)
    env["JAVA_HOME"] = "/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home"
    log = open("/tmp/nb-rcptcode/instance.log", "ab")
    p = subprocess.Popen([JAVA, "-Xmx768m", "-cp", cp, "nebflow.Main",
                          "--home", HOME, "--port", str(GW_PORT), "--no-browser"],
                         cwd=WORKTREE, env=env, stdout=log, stderr=subprocess.STDOUT)
    INSTANCE["proc"] = p
    for _ in range(180):
        if p.poll() is not None:
            raise RuntimeError(f"instance exited rc={p.returncode} (see instance.log)")
        st, _b = gw_call("GET", "/api/health", timeout=3)
        if st == 200:
            return p
        time.sleep(1)
    raise RuntimeError("instance health timeout (180s)")


def stop_instance():
    p = INSTANCE.get("proc")
    if not p or p.poll() is not None:
        return
    p.terminate()
    for _ in range(20):
        if p.poll() is not None:
            break
        time.sleep(0.5)
    if p.poll() is None:
        p.kill()
    p.wait(timeout=10)


def read_token():
    with open(os.path.join(HOME, "auth.json")) as f:
        return json.load(f)


def probe_all(token, tag):
    rows = []
    for uid, up_status, up_body in ARMS:
        st, body = gw_call("POST", f"/api/friends/{uid}/messages", token, {"body": "hi"})
        folded = "HTTP " in body
        rows.append({"uid": uid, "upstream": f"{up_status} {json.dumps(up_body, ensure_ascii=False)}",
                     "gateway_status": st, "gateway_body": body, "folded_text_form": folded})
        print(f"  {uid:<22} upstream={up_status:<4} gateway={st} body={body[:90]}", flush=True)
    return rows


def main():
    stub = ThreadingHTTPServer(("127.0.0.1", STUB_PORT), Stub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    out = {"stub": STUB, "gw": GW, "home": HOME}
    try:
        print(f"[1] stub 上游就绪 {STUB}", flush=True)
        start_instance()
        print(f"[2] 隔离实例就绪 {GW} (cwd={WORKTREE})", flush=True)
        token = read_token()
        st, body = gw_call("POST", "/api/neblink/enroll", token, {"server": STUB, "pairCode": "000000"})
        print(f"[3] enroll -> {st} {body[:160]}", flush=True)
        out["enroll"] = {"status": st, "body": body}
        # 探针前先确认会话面就绪（enroll 的 hot-swap 语义：未就绪则按「下一启动入网」重启一次）
        pre = gw_call("POST", "/api/friends/up-200/messages", token, {"body": "warm"})
        out["warmup"] = {"status": pre[0], "body": pre[1][:200]}
        print(f"[4] warmup -> {pre[0]} {pre[1][:160]}", flush=True)
        if pre[0] != 200:
            print("[4b] 会话未就绪 ⇒ 重启实例（enroll 落盘的配置在下次启动入网）", flush=True)
            stop_instance()
            start_instance()
            token = read_token()
            pre = gw_call("POST", "/api/friends/up-200/messages", token, {"body": "warm"})
            out["warmup_after_restart"] = {"status": pre[0], "body": pre[1][:200]}
            print(f"[4c] warmup(restart) -> {pre[0]} {pre[1][:160]}", flush=True)
        print("[5] 逐码探测（网关 HTTP 边界）", flush=True)
        out["arms"] = probe_all(token, "main")
        print("[6] 传输失败腿（停 stub + 关监听 ⇒ 连接失败）", flush=True)
        try:
            stub.shutdown()
            stub.server_close()  # shutdown() 只停循环，不关监听 ⇒ 必须 server_close 才真拒连
        except Exception:
            pass
        time.sleep(1)
        st_t, body_t = gw_call("POST", "/api/friends/up-200/messages", token, {"body": "x"})
        out["transport_failure"] = {"gateway_status": st_t, "gateway_body": body_t}
        print(f"  transport-failure   gateway={st_t} body={body_t[:90]}", flush=True)
        upstream_bodies = [c for c in STUB_CALLS if c["path"].endswith("/messages")]
        out["upstream_bodies"] = upstream_bodies[:10]
        out["upstream_call_count"] = len(STUB_CALLS)
        with open(OUT, "w") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
        print(f"[7] readings -> {OUT}", flush=True)
    finally:
        stop_instance()
        try:
            stub.shutdown()
            stub.server_close()
        except Exception:
            pass
        print("[8] 清理完成（实例已停 + stub 已关）", flush=True)


if __name__ == "__main__":
    main()
